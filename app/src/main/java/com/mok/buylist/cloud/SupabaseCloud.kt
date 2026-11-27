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
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.builtin.Email
import io.github.jan.supabase.auth.status.SessionStatus
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.postgrest.from
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
    fun manages(list: CloudList) = list.owner == me?.id && me != null ||
        groups.any { it.id == list.group && it.owner == me?.id }
}
internal data class CloudWorkspace(val namespace: String, val userId: String, val listId: String,
    val db: AppDatabase, val profile: UserProfile, val repository: ShoppingRepository, val catalog: LentaCatalog,
    val job: Job)

private fun JsonObject.string(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull
private fun JsonObject.flag(key: String) = this[key]?.jsonPrimitive?.booleanOrNull == true
private fun JsonObject.person() = CloudPerson(string("id")!!, string("display_name")!!, string("role") == "admin", flag("active"))
private fun JsonObject.group() = CloudGroup(string("id")!!, string("name")!!, string("owner_id")!!)
private fun JsonObject.list() = CloudList(string("id")!!, string("name")!!, string("owner_id"), string("group_id"), getValue("revision").jsonPrimitive.long)

/** Auth owns the persisted session. Room is a separate offline cache for each account/list. */
@OptIn(FlowPreview::class)
internal class SupabaseCloud(private val context: Context, private val scope: CoroutineScope) {
    val configured = BuildConfig.SUPABASE_URL.startsWith("https://") && BuildConfig.SUPABASE_KEY.isNotBlank()
    private val client = if (configured) createSupabaseClient(BuildConfig.SUPABASE_URL, BuildConfig.SUPABASE_KEY) {
        httpEngine = OkHttp.create()
        install(Auth)
        install(Postgrest)
        install(Realtime)
    } else null
    private fun api() = checkNotNull(client) { "Облако не настроено" }
    private val prefs = context.getSharedPreferences("buylist_supabase", Context.MODE_PRIVATE)
    private val mutex = Mutex()
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
    private var account: String? = null

    init {
        if (client != null) scope.launch {
            client.auth.sessionStatus.collect { state ->
                when (state) {
                    is SessionStatus.Authenticated -> {
                        val id = client.auth.currentUserOrNull()?.id ?: return@collect
                        mutex.withLock {
                            if (account != id) { resetWorkspace(); _directory.value = CloudDirectory(); account = id }
                            _signedIn.value = true
                        }
                        signal()
                    }
                    is SessionStatus.NotAuthenticated -> mutex.withLock {
                        resetWorkspace(); account = null; _signedIn.value = false; _directory.value = CloudDirectory(); _status.value = null
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
                        } catch (e: CancellationException) { throw e }
                        catch (_: Exception) { delay(5000) }
                        finally { withContext(NonCancellable) { runCatching { client.realtime.removeChannel(channel) } } }
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
        api().auth.signUpWith(Email) {
            this.email = email.trim(); this.password = password
            data = buildJsonObject { put("display_name", name.trim().take(50)) }
        }
        return api().auth.currentUserOrNull() != null
    }
    suspend fun signOut() {
        api().auth.signOut()
        mutex.withLock {
            resetWorkspace(); account = null; _signedIn.value = false; _directory.value = CloudDirectory(); _status.value = null
        }
    }
    private fun resetWorkspace() {
        _workspace.value = null
        caches.values.forEach { it.job.cancel() }
        // Old UI coroutines can still be unwinding; do not close a live Room database here.
        caches.clear()
    }

    private suspend fun rows(table: String) = api().from(table).select().decodeList<JsonObject>()
    suspend fun refresh() = mutex.withLock {
        val uid = account ?: return@withLock
        try {
            val people = rows("profiles").map { it.person() }
            val me = people.firstOrNull { it.id == uid && it.active }
            if (me == null) {
                resetWorkspace(); _directory.value = CloudDirectory(); _status.value = "Доступ отключён"
                return@withLock
            }
            val groups = rows("groups").filterNot { it.flag("archived") }.map { it.group() }.sortedBy { it.name }
            val lists = rows("shopping_lists").filterNot { it.flag("archived") }.map { it.list() }.sortedBy { it.name }
            val members = rows("group_members").filter { it.flag("active") }.map { CloudMember(it.string("group_id")!!, it.string("user_id")!!) }
            _directory.value = CloudDirectory(me, people.sortedBy { it.name }, groups, lists, members)
            val preferred = prefs.getString("selected_$uid", null)
            val selected = if (preferred == "local") null else lists.firstOrNull { it.id == preferred }
                ?: lists.firstOrNull { it.owner == uid }
            if (_workspace.value?.listId != selected?.id) {
                _workspace.value = if (selected == null) null else open(me, selected)
                updateWidget()
            }
            val allowed = lists.mapTo(HashSet()) { it.id }
            caches.values.filter { it.userId != uid || it.listId !in allowed }.forEach { it.job.cancel(); caches.remove(it.namespace) }
            var ok = true
            for (cache in caches.values.toList()) {
                try {
                    if (cache.profile.profile.value?.name != me.name) cache.repository.saveProfile(me.name)
                    sync(cache)
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) { ok = false }
            }
            _status.value = if (ok) null else "Офлайн"
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { _status.value = "Офлайн" }
    }

    private suspend fun open(me: CloudPerson, list: CloudList): CloudWorkspace {
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
            sync(value)
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

    suspend fun select(listId: String?) = mutex.withLock {
        val me = requireNotNull(_directory.value.me)
        val next = if (listId == null) null else open(me, _directory.value.lists.single { it.id == listId })
        check(prefs.edit().putString("selected_${me.id}", listId ?: "local").commit())
        _workspace.value = next
        updateWidget()
        signal()
    }
    private suspend fun updateWidget() {
        try { ShoppingWidget().updateAll(context) } catch (e: CancellationException) { throw e } catch (_: Exception) {}
    }

    private suspend fun sync(cache: CloudWorkspace) {
        repeat(4) {
            val row = api().from("list_states").select { filter { eq("list_id",cache.listId) } }.decodeSingle<JsonObject>()
            val revision = row.getValue("revision").jsonPrimitive.long
            val encoded = row.string("payload").orEmpty()
            val remote = if (encoded.isBlank()) Snapshot() else SyncCodec.decode(Base64.decode(encoded, Base64.NO_WRAP))
            val local = cache.repository.exportSnapshot()
            val merged = SyncMerge.merge(local, remote)
            if (merged != SyncMerge.merge(local, local)) cache.repository.applyRemote(remote)
            if (merged == SyncMerge.merge(remote, remote)) return
            val data = Base64.encodeToString(SyncCodec.encode(merged), Base64.NO_WRAP)
            require(data.length <= 8388608)
            val saved = api().from("list_states").update(buildJsonObject {
                put("revision", revision+1); put("payload", data)
            }) { select(); filter { eq("list_id", cache.listId); eq("revision", revision) } }.decodeList<JsonObject>()
            if (saved.size == 1) return
        }
        error("Concurrent update")
    }

    private suspend fun update(table: String, id: String, values: JsonObject) {
        check(api().from(table).update(values) { select(); filter { eq("id", id) } }.decodeList<JsonObject>().size == 1)
        signal()
    }
    suspend fun renameSelf(name: String) {
        update("profiles", requireNotNull(directory.value.me).id, buildJsonObject { put("display_name",name.trim().take(50)) })
        refresh()
    }
    suspend fun updatePerson(id: String, admin: Boolean, active: Boolean) {
        update("profiles",id,buildJsonObject { put("role",if (admin) "admin" else "user"); put("active",active) })
        refresh()
    }
    suspend fun saveGroup(id: String?, name: String) {
        val values = buildJsonObject { put("name",name.trim().take(100)) }
        if (id == null) api().from("groups").insert(values) else update("groups",id,values)
        refresh()
    }
    suspend fun removeGroup(id: String) {
        update("groups",id,buildJsonObject { put("archived",true) }); refresh()
    }
    suspend fun member(group: String, person: String, active: Boolean) {
        val existing = api().from("group_members").select { filter { eq("group_id",group); eq("user_id",person) } }.decodeList<JsonObject>()
        if (existing.isEmpty()) {
            api().from("group_members").insert(buildJsonObject { put("group_id",group); put("user_id",person); put("active",active) })
        } else check(api().from("group_members").update(buildJsonObject { put("active",active) }) {
            select(); filter { eq("group_id",group); eq("user_id",person) }
        }.decodeList<JsonObject>().size == 1)
        refresh()
    }
    suspend fun saveList(id: String?, name: String, group: String?) {
        if (id == null) api().from("shopping_lists").insert(buildJsonObject {
            put("name",name.trim().take(100))
            if (group == null) put("owner_id",requireNotNull(directory.value.me).id) else put("group_id",group)
        }) else update("shopping_lists",id,buildJsonObject { put("name",name.trim().take(100)) })
        refresh()
    }
    suspend fun removeList(id: String) {
        update("shopping_lists",id,buildJsonObject { put("archived",true) }); refresh()
    }
    suspend fun importLocal(local: ShoppingRepository) = mutex.withLock {
        val cache = requireNotNull(_workspace.value)
        check(directory.value.lists.any { it.id == cache.listId && it.owner == cache.userId })
        cache.repository.applyRemote(local.exportSnapshot())
        signal()
    }
}
