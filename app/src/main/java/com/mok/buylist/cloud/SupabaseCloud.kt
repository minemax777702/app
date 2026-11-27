package com.mok.buylist.cloud

import android.content.Context
import android.util.Base64
import androidx.glance.appwidget.updateAll
import com.mok.buylist.BuildConfig
import com.mok.buylist.data.AppDatabase
import com.mok.buylist.data.LentaCatalog
import com.mok.buylist.data.ShoppingRepository
import com.mok.buylist.data.UserProfile
import com.mok.buylist.sync.Snapshot
import com.mok.buylist.sync.SyncCodec
import com.mok.buylist.sync.SyncMerge
import com.mok.buylist.widget.ShoppingWidget
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.FlowType
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.builtin.Email
import io.github.jan.supabase.auth.status.SessionStatus
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.result.PostgrestResult
import io.github.jan.supabase.realtime.Realtime
import io.github.jan.supabase.realtime.PostgresAction
import io.github.jan.supabase.realtime.channel
import io.github.jan.supabase.realtime.postgresChangeFlow
import io.github.jan.supabase.realtime.realtime
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*

internal data class CloudPerson(val id: String, val name: String, val admin: Boolean, val active: Boolean)
internal data class CloudGroup(val id: String, val name: String, val owner: String)
internal data class CloudList(val id: String, val name: String, val owner: String?, val group: String?, val revision: Long)
internal data class CloudMember(val group: String, val user: String)
internal data class CloudDirectory(
    val me: CloudPerson? = null, val people: List<CloudPerson> = emptyList(),
    val groups: List<CloudGroup> = emptyList(), val lists: List<CloudList> = emptyList(),
    val members: List<CloudMember> = emptyList(),
) {
    fun manages(group: CloudGroup) = me?.let { it.id == group.owner || it.admin } == true
    fun participates(group: CloudGroup) = me?.let { person -> group.owner == person.id || members.any { it.group == group.id && it.user == person.id } } == true
    fun manages(list: CloudList) = me != null && (list.owner == me.id || groups.any { it.id == list.group && participates(it) })
}
internal data class CloudWorkspace(val namespace: String, val userId: String, val listId: String,
    val db: AppDatabase, val profile: UserProfile, val repository: ShoppingRepository, val catalog: LentaCatalog,
    val job: Job)

// PostgREST returns a JSON array even for one row. Avoid generic KType/reflection
// resolution in the mobile release: all values already have a concrete JSON type.
private fun PostgrestResult.rows(): List<JsonObject> =
    Json.parseToJsonElement(data).jsonArray.map { it.jsonObject }

private fun JsonObject.string(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull
private fun JsonObject.flag(key: String) = this[key]?.jsonPrimitive?.booleanOrNull == true
private fun JsonObject.person() = CloudPerson(string("id")!!, string("display_name")!!, string("role") == "admin", flag("active"))
private fun JsonObject.group() = CloudGroup(string("id")!!, string("name")!!, string("owner_id")!!)
private fun JsonObject.list() = CloudList(string("id")!!, string("name")!!, string("owner_id"), string("group_id"), getValue("revision").jsonPrimitive.long)

/** Auth owns the persisted session. Room is a separate offline cache for each account/list. */
@OptIn(FlowPreview::class)
internal class SupabaseCloud(private val context: Context, parentScope: CoroutineScope) {
    private val scope = CoroutineScope(parentScope.coroutineContext + SupervisorJob(parentScope.coroutineContext[Job]) +
        CoroutineExceptionHandler { _, error -> reportFailure("worker", error) })
    val configured = BuildConfig.SUPABASE_URL.startsWith("https://") && BuildConfig.SUPABASE_KEY.isNotBlank()
    private val client = if (configured) createSupabaseClient(BuildConfig.SUPABASE_URL, BuildConfig.SUPABASE_KEY) {
        httpEngine = OkHttp.create()
        install(Auth) {
            flowType = FlowType.PKCE
            scheme = "com.mok.buylist"
            host = "auth-confirm"
            // Keep the persisted session stable across Activity/background transitions.
            enableLifecycleCallbacks = false
        }
        install(Postgrest)
        install(Realtime)
    } else null
    private fun api() = checkNotNull(client) { "Облако не настроено" }
    private val prefs = context.getSharedPreferences("buylist_supabase", Context.MODE_PRIVATE)
    private val mutex = Mutex() // local cache and pending mutations; no network calls under this lock
    private val refreshMutex = Mutex()
    private val signals = Channel<Unit>(Channel.CONFLATED)
    private val caches = mutableMapOf<String, CloudWorkspace>()
    private val _directory = MutableStateFlow(CloudDirectory())
    val directory = _directory.asStateFlow()
    private val _workspace = MutableStateFlow<CloudWorkspace?>(null)
    val workspace = _workspace.asStateFlow()
    private val _signedIn = MutableStateFlow(false)
    val signedIn = _signedIn.asStateFlow()
    private val _status = MutableStateFlow<String?>(null)
    val status = _status.asStateFlow()
    @Volatile private var account: String? = null
    private val _pendingCount = MutableStateFlow(0)
    val pendingCount = _pendingCount.asStateFlow()

    private fun reportFailure(stage: String, error: Throwable) {
        // Keep exception types and stack frames for diagnosis, but never log request URLs, tokens or data.
        val frames = generateSequence(error) { it.cause }.take(8).joinToString("\n") { cause ->
            cause.javaClass.name + "\n" + cause.stackTrace.take(16).joinToString("\n")
        }
        android.util.Log.e("BuyListCloud", "$stage: $frames")
        if (stage.startsWith("realtime")) return // REST remains usable without WebSocket.
        _status.value = if (generateSequence(error) { it.cause }.take(8).any { it is java.io.IOException })
            "Нет соединения" else "Не удалось загрузить облако"
    }

    private suspend fun publishWorkspace(value: CloudWorkspace?) = withContext(Dispatchers.Main.immediate) {
        _workspace.value = value
    }

    init {
        if (client != null) scope.launch {
            // A previously authenticated account may open its own persisted cache immediately.
            mutex.withLock {
                prefs.getString("last_account", null)?.let { id ->
                    account = id
                    restoreDirectory(id)
                    _signedIn.value = _directory.value.me != null
                    if (_signedIn.value) _status.value = "Офлайн"
                }
            }
            client.auth.sessionStatus.collect { state ->
                when (state) {
                    is SessionStatus.Authenticated -> {
                        val id = client.auth.currentUserOrNull()?.id ?: return@collect
                        mutex.withLock {
                            if (account != id) {
                                resetWorkspace()
                                account = id
                                _directory.value = CloudDirectory()
                                restoreDirectory(id)
                            }
                            prefs.edit().putString("last_account", id).apply()
                            _signedIn.value = true
                        }
                        signal()
                    }
                    is SessionStatus.NotAuthenticated -> mutex.withLock {
                        prefs.edit().remove("last_account").apply()
                        resetWorkspace(); account = null; _signedIn.value = false; _directory.value = CloudDirectory(); _status.value = null
                        updateWidget()
                    }
                    is SessionStatus.RefreshFailure -> _status.value = "Офлайн"
                    else -> Unit
                }
            }
        }
        if (client != null) {
            scope.launch {
                signedIn.collectLatest { signed ->
                    if (!signed) return@collectLatest
                    while (currentCoroutineContext().isActive) {
                        try {
                            val channel = client.channel("buylist-changes")
                            try {
                            coroutineScope {
                                listOf("profiles", "groups", "group_members", "shopping_lists").forEach { tableName ->
                                    val changes = channel.postgresChangeFlow<PostgresAction>(schema = "public") { table = tableName }
                                    launch { changes.collect { signal() } }
                                }
                                channel.subscribe(blockUntilSubscribed = true)
                                signal()
                                awaitCancellation()
                            }
                            } finally {
                                withContext(NonCancellable) { runCatching { client.realtime.removeChannel(channel) } }
                            }
                        } catch (e: CancellationException) { throw e }
                        catch (e: Exception) { reportFailure("realtime", e); delay(5000) }
                        catch (e: LinkageError) { reportFailure("realtime linkage", e); delay(15000) }
                    }
                }
            }
            scope.launch {
                while (isActive) {
                    withTimeoutOrNull(15000) { signals.receive() }
                    if (signedIn.value) refresh()
                }
            }
        }
    }

    fun signal() { signals.trySend(Unit) }
    suspend fun signIn(email: String, password: String) {
        api().auth.signInWith(Email) { this.email = email.trim(); this.password = password }
        signal()
    }
    suspend fun signUp(name: String, email: String, password: String): Boolean {
        api().auth.signUpWith(Email, redirectUrl = EMAIL_REDIRECT) {
            this.email = email.trim(); this.password = password
            data = buildJsonObject { put("display_name", name.trim().take(50)) }
        }
        return api().auth.currentUserOrNull() != null
    }
    suspend fun confirmEmail(code: String): Boolean {
        val auth = api().auth
        auth.awaitInitialization()
        if (auth.currentUserOrNull() != null) return true
        auth.exchangeCodeForSession(code)
        signal()
        return auth.currentUserOrNull() != null
    }

    companion object {
        const val EMAIL_REDIRECT = "com.mok.buylist://auth-confirm"
    }

    suspend fun signOut() {
        // Do not wait for connectivity to leave an account on this device.
        mutex.withLock {
            prefs.edit().remove("last_account").commit()
            account = null
            resetWorkspace(); _signedIn.value = false; _directory.value = CloudDirectory(); _status.value = null
            _pendingCount.value = 0
        }
        api().auth.clearSession()
        updateWidget()
    }
    private suspend fun resetWorkspace() {
        publishWorkspace(null)
        caches.values.forEach { closeLater(it) }
        caches.clear()
    }

    private fun closeLater(cache: CloudWorkspace) {
        cache.job.cancel()
        scope.launch(Dispatchers.IO) {
            cache.job.join()
            delay(1500)
            cache.db.close()
        }
    }

    private fun rememberDirectory(uid: String, profiles: List<JsonObject>, groups: List<JsonObject>,
                                  lists: List<JsonObject>, members: List<JsonObject>) {
        val data = buildJsonObject {
            put("profiles", JsonArray(profiles)); put("groups", JsonArray(groups))
            put("lists", JsonArray(lists)); put("members", JsonArray(members))
        }
        prefs.edit().putString("directory_$uid", data.toString()).apply()
    }

    private fun decodeDirectory(uid: String, profiles: List<JsonObject>, groups: List<JsonObject>,
                                lists: List<JsonObject>, members: List<JsonObject>): CloudDirectory {
        val people = profiles.map { it.person() }
        val me = people.firstOrNull { it.id == uid && it.active }
        return CloudDirectory(me, people.sortedBy { it.name },
            groups.filterNot { it.flag("archived") }.map { it.group() }.sortedBy { it.name },
            lists.filterNot { it.flag("archived") }.map { it.list() }.sortedBy { it.name },
            members.filter { it.flag("active") }.map { CloudMember(it.string("group_id")!!, it.string("user_id")!!) })
    }

    private suspend fun restoreDirectory(uid: String) {
        val saved = prefs.getString("directory_$uid", null) ?: return
        try {
            val json = Json.parseToJsonElement(saved).jsonObject
            fun get(key: String) = json.getValue(key).jsonArray.map { it.jsonObject }
            val directory = withPending(uid, decodeDirectory(uid, get("profiles"),get("groups"),get("lists"),get("members")))
            val me = directory.me ?: return
            _directory.value = directory
            val id = prefs.getString("selected_$uid", null)
            val selected = directory.lists.firstOrNull { it.id == id }
                ?: directory.lists.firstOrNull { it.owner == uid } ?: directory.lists.firstOrNull()
            if (selected != null) {
                publishWorkspace(open(me, selected, cachedOnly = true))
                _status.value = "Офлайн"
                updateWidget()
            }
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { _directory.value = CloudDirectory() }
    }

    private suspend fun rows(table: String): List<JsonObject> {
        try { return api().from(table).select().rows() }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { throw IllegalStateException("Directory table: $table", e) }
    }
    private fun authenticated(uid: String): Boolean =
        account == uid && api().auth.currentUserOrNull()?.id == uid

    suspend fun refresh() = refreshMutex.withLock {
        val uid = account ?: return@withLock
        if (!authenticated(uid)) { _status.value = "Офлайн"; return@withLock }
        try {
            val profiles = rows("profiles")
            if (!authenticated(uid)) return@withLock
            if (profiles.none { it.string("id") == uid && it.flag("active") }) {
                mutex.withLock {
                    if (account != uid) return@withLock
                    prefs.edit().remove("directory_$uid").remove("last_account").apply()
                    resetWorkspace(); _directory.value = CloudDirectory(); _status.value = "Доступ отключён"
                }
                updateWidget()
                return@withLock
            }
            // The queue is durable. A lost HTTP response is safe: created rows have client UUIDs.
            var pendingError: Exception? = null
            try { flushPending(uid) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { pendingError = e; reportFailure("pending CRUD", e) }
            if (!authenticated(uid)) return@withLock
            val groups = rows("groups")
            val listRows = rows("shopping_lists")
            val members = rows("group_members")
            val currentCaches = mutex.withLock {
                if (!authenticated(uid)) return@withLock emptyList<CloudWorkspace>()
                val directory = withPending(uid, decodeDirectory(uid, profiles, groups, listRows, members))
                val me = requireNotNull(directory.me)
                rememberDirectory(uid, profiles, groups, listRows, members)
                _directory.value = directory
                val preferred = prefs.getString("selected_$uid", null)
                val selected = directory.lists.firstOrNull { it.id == preferred }
                    ?: directory.lists.firstOrNull { it.owner == uid } ?: directory.lists.firstOrNull()
                val allowed = directory.lists.mapTo(HashSet()) { it.id }
                if (_workspace.value?.listId?.let { it !in allowed } == true) publishWorkspace(null)
                caches.values.filter { it.userId != uid || it.listId !in allowed }.forEach {
                    closeLater(it); caches.remove(it.namespace)
                }
                // Open Room without any network wait. Incoming data will be merged afterwards.
                if (_workspace.value?.listId != selected?.id)
                    publishWorkspace(selected?.let { open(me, it, cachedOnly = true) })
                migrateGuest(me, directory)
                caches.values.toList()
            }
            updateWidget()
            val realLists = listRows.mapNotNull { it.string("id") }.toSet()
            var ok = pendingError == null
            for (cache in currentCaches) {
                if (!authenticated(uid)) return@withLock
                if (cache.listId !in realLists || !cache.job.isActive) continue
                try {
                    val person = _directory.value.me ?: break
                    if (cache.profile.profile.value?.name != person.name) cache.repository.saveProfile(person.name)
                    sync(cache)
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { reportFailure("sync", e); ok = false }
                catch (e: LinkageError) { reportFailure("sync linkage", e); ok = false }
            }
            if (authenticated(uid) && ok) _status.value = null
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { reportFailure("refresh", e) }
        catch (e: LinkageError) { reportFailure("refresh linkage", e) }
    }

    /** Preserve the original offline database. Never transfer it to a different account. */
    private suspend fun migrateGuest(me: CloudPerson, directory: CloudDirectory) {
        val app = context.applicationContext as com.mok.buylist.ShoppingApp
        val snapshot = app.localRepository.exportSnapshot()
        if (snapshot.items.isEmpty() && snapshot.archives.isEmpty() && snapshot.products.isEmpty() &&
            snapshot.customProducts.isEmpty() && snapshot.stores.isEmpty()) return
        val bound = prefs.getString("guest_owner", null)
        if (bound != null && bound != me.id) return
        val hash = java.security.MessageDigest.getInstance("SHA-256").digest(SyncCodec.encode(snapshot))
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        if (hash == prefs.getString("guest_hash", null)) return
        val targetId = prefs.getString("guest_list", null)
        val target = if (targetId == null) directory.lists.firstOrNull { it.owner == me.id }
            else directory.lists.firstOrNull { it.id == targetId && it.owner == me.id }
        if (target == null) return
        check(prefs.edit().putString("guest_owner", me.id).putString("guest_list", target.id).commit())
        val cache = open(me, target, cachedOnly = true)
        cache.repository.applyRemote(snapshot)
        check(prefs.edit().putString("guest_hash", hash).commit())
    }

    private suspend fun open(me: CloudPerson, list: CloudList, cachedOnly: Boolean = false): CloudWorkspace {
        val namespace = "cloud-${me.id}-${list.id}"
        caches[namespace]?.let { return it }
        val db = AppDatabase.create(context, "$namespace.db")
        val job = SupervisorJob(scope.coroutineContext[Job])
        val cacheScope = CoroutineScope(scope.coroutineContext + job)
        val profile = UserProfile(context, "profile-$namespace").apply { adopt(me.id, me.name) }
        val repository = ShoppingRepository(context, db, profile)
        val catalog = LentaCatalog(context, db, cacheScope)
        val value = CloudWorkspace(namespace, me.id, list.id, db, profile, repository, catalog, job)
        try {
            // Pull before making a new list editable. Never treat a missing remote row as an empty list.
            if (!cachedOnly) sync(value)
            repository.saveProfile(me.name)
            caches[namespace] = value
            cacheScope.launch {
                db.invalidationTracker.createFlow("items","archived_lists","tombstones","users","lenta_products","product_barcodes","custom_products","stores","custom_product_barcodes","store_products")
                    .debounce(400).collect { signal() }
            }
            return value
        } catch (e: Exception) {
            job.cancel()
            db.close()
            throw e
        }
    }

    suspend fun select(listId: String?) {
        mutex.withLock {
            val me = requireNotNull(_directory.value.me)
            val list = _directory.value.lists.single { it.id == listId }
            val next = open(me, list, cachedOnly = true)
            check(prefs.edit().putString("selected_${me.id}", list.id).commit())
            publishWorkspace(next)
        }
        signal()
        updateWidget()
    }
    private suspend fun updateWidget() {
        try { ShoppingWidget().updateAll(context) } catch (e: CancellationException) { throw e } catch (_: Exception) {}
    }

    private suspend fun sync(cache: CloudWorkspace) {
        repeat(4) {
            check(authenticated(cache.userId) && cache.job.isActive)
            val row = api().from("list_states").select { filter { eq("list_id",cache.listId) } }.rows().single()
            val revision = row.getValue("revision").jsonPrimitive.long
            val encoded = row.string("payload").orEmpty()
            val remote = if (encoded.isBlank()) Snapshot() else SyncCodec.decode(Base64.decode(encoded, Base64.NO_WRAP))
            val local = cache.repository.exportSnapshot()
            val merged = SyncMerge.merge(local, remote)
            if (merged != SyncMerge.merge(local, local)) cache.repository.applyRemote(remote)
            if (merged == SyncMerge.merge(remote, remote)) return
            val data = Base64.encodeToString(SyncCodec.encode(merged), Base64.NO_WRAP)
            require(data.length <= 8388608)
            check(authenticated(cache.userId) && cache.job.isActive)
            val saved = api().from("list_states").update(body = buildJsonObject {
                put("revision", revision+1); put("payload", data)
            }) { select(); filter { eq("list_id", cache.listId); eq("revision", revision) } }.rows()
            if (saved.size == 1) return
        }
        error("Concurrent update")
    }

    private suspend fun update(table: String, id: String, values: JsonObject) {
        check(api().from(table).update(body = values) { select(); filter { eq("id", id) } }.rows().size == 1)
    }

    // Metadata mutations are written locally first. Never persist a password in this queue.
    private fun pending(uid: String): List<JsonObject> =
        prefs.getString("pending_$uid", null)?.let { Json.parseToJsonElement(it).jsonArray.map { row -> row.jsonObject } }.orEmpty()

    private fun storePending(uid: String, rows: List<JsonObject>) {
        check(prefs.edit().putString("pending_$uid", JsonArray(rows).toString()).commit())
        _pendingCount.value = rows.size
    }

    private fun withPending(uid: String, initial: CloudDirectory): CloudDirectory {
        val changes = pending(uid)
        _pendingCount.value = changes.size
        return changes.fold(initial) { dir, op -> project(dir, op) }
    }

    private fun project(dir: CloudDirectory, op: JsonObject): CloudDirectory {
        val kind = op.string("kind")
        val id = requireNotNull(op.string("id"))
        val name = op.string("name").orEmpty()
        return when(kind) {
            "group_create" -> if (dir.groups.any { it.id == id }) dir else dir.copy(groups =
                (dir.groups + CloudGroup(id, name, requireNotNull(dir.me).id)).sortedBy { it.name })
            "group_rename" -> dir.copy(groups = dir.groups.map { if (it.id == id) it.copy(name = name) else it }.sortedBy { it.name })
            "group_remove" -> dir.copy(groups = dir.groups.filterNot { it.id == id }, lists = dir.lists.filterNot { it.group == id },
                members = dir.members.filterNot { it.group == id })
            "list_create" -> if (dir.lists.any { it.id == id }) dir else {
                val group = op.string("group")
                if (group != null && dir.groups.none { it.id == group && dir.participates(it) }) dir
                else dir.copy(lists = (dir.lists + CloudList(id, name, if (group == null) dir.me?.id else null, group, 0)).sortedBy { it.name })
            }
            "list_rename" -> dir.copy(lists = dir.lists.map { if (it.id == id) it.copy(name = name) else it }.sortedBy { it.name })
            "list_remove" -> dir.copy(lists = dir.lists.filterNot { it.id == id })
            "member" -> {
                val user = requireNotNull(op.string("user"))
                val members = dir.members.filterNot { it.group == id && it.user == user }
                dir.copy(members = if (op.flag("active")) members + CloudMember(id,user) else members)
            }
            else -> error("Unknown pending operation")
        }
    }

    private suspend fun enqueue(kind: String, id: String, name: String? = null, group: String? = null,
                                user: String? = null, active: Boolean? = null) {
        mutex.withLock {
            val me = requireNotNull(directory.value.me)
            check(account == me.id)
            val op = buildJsonObject {
                put("op", java.util.UUID.randomUUID().toString())
                put("kind", kind); put("id", id)
                name?.let { put("name", it) }; group?.let { put("group", it) }
                user?.let { put("user", it) }; active?.let { put("active", it) }
            }
            val projected = project(directory.value, op)
            storePending(me.id, pending(me.id) + op)
            _directory.value = projected
            if (_workspace.value?.listId?.let { current -> projected.lists.none { it.id == current } } == true) {
                val selected = projected.lists.firstOrNull { it.owner == me.id } ?: projected.lists.firstOrNull()
                publishWorkspace(selected?.let { open(me, it, cachedOnly = true) })
            }
        }
        signal()
    }

    private suspend fun flushPending(uid: String) {
        while (authenticated(uid)) {
            val op = mutex.withLock { pending(uid).firstOrNull() } ?: return
            val id = requireNotNull(op.string("id"))
            val kind = requireNotNull(op.string("kind"))
            val table = if (kind.startsWith("group_")) "groups" else "shopping_lists"
            when (kind) {
                "group_create", "list_create" -> {
                    if (api().from(table).select { filter { eq("id", id) } }.rows().isEmpty()) {
                        val values = buildJsonObject {
                            put("id", id); put("name", requireNotNull(op.string("name")))
                            if (kind == "list_create") {
                                val group = op.string("group")
                                if (group == null) put("owner_id",uid) else put("group_id",group)
                            }
                        }
                        check(authenticated(uid))
                        api().from(table).insert(body = JsonArray(listOf(values)))
                    }
                }
                "group_rename", "list_rename" -> update(table,id,buildJsonObject { put("name",requireNotNull(op.string("name"))) })
                "group_remove", "list_remove" -> {
                    // An archived row may no longer be editable; recognize the completed operation on retry.
                    val row = api().from(table).select { filter { eq("id",id) } }.rows().singleOrNull()
                    if (row != null && !row.flag("archived")) update(table,id,buildJsonObject { put("archived",true) })
                }
                "member" -> {
                    val user = requireNotNull(op.string("user"))
                    val values = buildJsonObject { put("group_id",id); put("user_id",user); put("active",op.flag("active")) }
                    val old = api().from("group_members").select { filter { eq("group_id",id); eq("user_id",user) } }.rows().singleOrNull()
                    if (old == null) api().from("group_members").insert(body = JsonArray(listOf(values)))
                    else if (old.flag("active") != op.flag("active"))
                        check(api().from("group_members").update(body = buildJsonObject { put("active",op.flag("active")) }) {
                            select(); filter { eq("group_id",id); eq("user_id",user) }
                        }.rows().size == 1)
                }
                else -> error("Unknown pending operation")
            }
            mutex.withLock {
                val rest = pending(uid).filterNot { it.string("op") == op.string("op") }
                if (account == uid) storePending(uid, rest)
                else check(prefs.edit().putString("pending_$uid",JsonArray(rest).toString()).commit())
            }
        }
    }

    suspend fun renameSelf(name: String) {
        val id = requireNotNull(directory.value.me).id
        update("profiles", id, buildJsonObject { put("display_name",name.trim().take(50)) })
        signal()
    }
    suspend fun updatePerson(id: String, name: String, admin: Boolean, active: Boolean) {
        update("profiles",id,buildJsonObject { put("display_name",name.trim().take(50)); put("role",if (admin) "admin" else "user"); put("active",active) })
        signal()
    }
    suspend fun createPerson(name: String, email: String, password: String): Boolean = withContext(Dispatchers.IO) {
        check(directory.value.me?.admin == true)
        val token = requireNotNull(api().auth.currentAccessTokenOrNull())
        val connection = java.net.URL(BuildConfig.SUPABASE_URL + "/functions/v1/manage-users").openConnection() as java.net.HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 10000; connection.readTimeout = 20000
            connection.doOutput = true
            connection.setRequestProperty("apikey", BuildConfig.SUPABASE_KEY)
            connection.setRequestProperty("Authorization", "Bearer $token")
            connection.setRequestProperty("Content-Type", "application/json")
            val payload = buildJsonObject { put("name",name.trim()); put("email",email.trim()); put("password",password) }.toString()
            connection.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
            check(connection.responseCode == 201)
            val response = connection.inputStream.bufferedReader().use { it.readText() }
            signal()
            Json.parseToJsonElement(response).jsonObject.flag("confirmation_sent")
        } finally { connection.disconnect() }
    }
    suspend fun saveGroup(id: String?, name: String) =
        enqueue(if (id == null) "group_create" else "group_rename", id ?: java.util.UUID.randomUUID().toString(), name.trim().take(100))
    suspend fun removeGroup(id: String) = enqueue("group_remove", id)
    suspend fun member(group: String, person: String, active: Boolean) = enqueue("member", group, user = person, active = active)
    suspend fun saveList(id: String?, name: String, group: String?) =
        enqueue(if (id == null) "list_create" else "list_rename", id ?: java.util.UUID.randomUUID().toString(), name.trim().take(100), group)
    suspend fun removeList(id: String) = enqueue("list_remove", id)
}
