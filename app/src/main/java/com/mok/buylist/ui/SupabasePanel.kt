package com.mok.buylist.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Switch
import androidx.compose.material3.Icon
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mok.buylist.R
import com.mok.buylist.ShoppingApp
import com.mok.buylist.cloud.*
import com.mok.buylist.ui.theme.LocalPalette
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun SupabasePanel(authOnly: Boolean = false, adminOnly: Boolean = false, fixedLogout: Boolean = false) {
    val p = LocalPalette.current
    val app = LocalContext.current.applicationContext as ShoppingApp
    val cloud = app.cloud
    val activity = androidx.activity.compose.LocalActivity.current as? MainActivity
    val directory by cloud.directory.collectAsStateWithLifecycle()
    val signedIn by cloud.signedIn.collectAsStateWithLifecycle()
    val workspace by cloud.workspace.collectAsStateWithLifecycle()
    val status by cloud.status.collectAsStateWithLifecycle()
    val pendingCount by cloud.pendingCount.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    fun notify(text: String) { activity?.notifyAccount(text) }
    fun notifyPending(text: String) { notify("$text · Ожидает синхронизации") }
    var registration by remember { mutableStateOf(false) }
    var passkeyStage by remember { mutableStateOf(PasskeyStage.IDLE) }
    var passkeySheet by remember { mutableStateOf(false) }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var authNotice by remember { mutableStateOf<String?>(null) }
    var authFailed by remember { mutableStateOf(false) }
    var tab by remember { mutableIntStateOf(0) }
    var editingList by remember { mutableStateOf<CloudList?>(null) }
    var listSheet by remember { mutableStateOf(false) }
    var editingGroup by remember { mutableStateOf<CloudGroup?>(null) }
    var groupSheet by remember { mutableStateOf(false) }
    var person by remember { mutableStateOf<CloudPerson?>(null) }
    var editName by remember { mutableStateOf(false) }
    var createUser by remember { mutableStateOf(false) }
    var createUserGroup by remember { mutableStateOf<String?>(null) }
    var addingMembers by remember { mutableStateOf(false) }
    var leavingGroup by remember { mutableStateOf<CloudGroup?>(null) }
    var newListGroup by remember { mutableStateOf<String?>(null) }
    var deletion by remember { mutableStateOf<Pair<String, suspend () -> Unit>?>(null) }
    fun perform(action: suspend () -> Unit) {
        if (busy) return
        busy = true
        scope.launch {
            try { action() }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { notify("Не удалось выполнить операцию") }
            finally { busy = false }
        }
    }
    if (adminOnly && (!signedIn || directory.me?.admin != true || directory.me?.active != true)) return
    if (!cloud.configured) {
        Text("Облачное хранилище недоступно", color = p.textSecondary, fontSize = 14.sp)
        return
    }
    if (!signedIn) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            FreshCutTabs(listOf("Вход","Регистрация"), if (registration) 1 else 0, {
                if (!busy && passkeyStage == PasskeyStage.IDLE) { registration = it == 1; authNotice = null }
            })
            if (registration) SheetTextField(name,{ name=it.take(50); authNotice=null },"Имя")
            SheetTextField(email,{ email=it.take(254); authNotice=null },"Электронная почта",keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Email))
            SheetTextField(password,{ password=it.take(128); authNotice=null },"Пароль",keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Password),
                visualTransformation=PasswordVisualTransformation())
            authNotice?.let { message ->
                Text(message, Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
                    color = if (authFailed) p.error else p.text, fontSize = 14.sp)
            }
            val registering = registration
            FreshCutSecondaryButton(if (busy) "Подключение…" else if (registration) "Зарегистрироваться" else "Войти", {
                perform {
                    authNotice = null
                    authFailed = false
                    try {
                        if (registering) {
                            if (!cloud.signUp(name,email,password)) {
                                registration = false
                                authNotice = "Подтвердите почту по ссылке в письме, затем войдите."
                            }
                        } else cloud.signIn(email,password)
                        password = ""
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        authFailed = true
                        authNotice = if (registering) "Не удалось зарегистрироваться. Проверьте данные и подключение." else "Не удалось войти. Проверьте почту, пароль и подключение."
                    }
                }
            },enabled=!busy && passkeyStage==PasskeyStage.IDLE && email.contains('@') && password.length>=6 && (!registration || name.isNotBlank()))
            if(!registration && activity!=null) PasskeyAction(passkeyStage, false, enabled=!busy) {
                if(passkeyStage!=PasskeyStage.IDLE)return@PasskeyAction
                passkeyStage=PasskeyStage.PREPARING
                scope.launch {
                    try { cloud.passkeys.signIn(activity){passkeyStage=it}; password="";notify("Вход по ключу доступа выполнен") }
                    catch(e:CancellationException){throw e}
                    catch(e:Exception){passkeyError(e).takeIf{it.isNotBlank()}?.let{authFailed=true;authNotice=it}}
                    finally{passkeyStage=PasskeyStage.IDLE}
                }
            }
        }
        return
    }
    if (authOnly) return
    val me = directory.me
    if (me == null) {
        Text(status ?: "Загрузка…", color=p.textSecondary,fontSize=14.sp)
        FreshCutSecondaryButton("Повторить", { perform { cloud.refresh() } }, enabled = !busy)
        FreshCutSecondaryButton("Выйти",{ perform { cloud.signOut() } },enabled=!busy)
        return
    }
    Column(verticalArrangement=Arrangement.spacedBy(12.dp)) {
        if (!adminOnly) FreshCutListRow(R.drawable.ic_user_round, me.name,
            onClick={ if(!busy) editName=true }, showChevron=false,
            trailing=if(activity!=null) {{
                FreshCutToolbarAction(R.drawable.ic_shield,"Безопасность",
                    {passkeySheet=true},enabled=!busy)
            }} else null)
        if (pendingCount > 0) Text("Ожидают синхронизации: $pendingCount",color=p.textSecondary,fontSize=13.sp)
        status?.let { Text(it,color=p.textSecondary,fontSize=13.sp) }
        val tabs = if(adminOnly) listOf("Пользователи","Группы") else listOf("Списки","Группы")
        val selectedTab = tab.coerceAtMost(tabs.lastIndex)
        FreshCutTabs(tabs,selectedTab,{ tab=it })
        when {
            !adminOnly && selectedTab == 0 -> {
                directory.lists.filter{it.group==null}.forEach { row ->
                    FreshCutListRow(R.drawable.ic_list_check,row.name,
                        selected=workspace?.listId==row.id,showChevron=false,
                        onClick={ editingList=row;newListGroup=null;listSheet=true })
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement=Arrangement.End) { FreshCutToolbarAction(R.drawable.ic_plus,"Создать список",{ editingList=null;newListGroup=null;listSheet=true },enabled=!busy) }
            }
            selectedTab == 1 -> {
                directory.groups.filter { adminOnly || directory.participates(it) }.forEach { group ->
                    val memberCount = directory.members.count{it.group==group.id && it.user!=group.owner}+1
                    val subtitle = if(adminOnly) "Участников: $memberCount" else "Списков: ${directory.lists.count{it.group==group.id}} · Участников: $memberCount"
                    FreshCutListRow(R.drawable.ic_group,group.name,subtitle,showChevron=false,
                        onClick={ editingGroup=group;addingMembers=false;groupSheet=true })
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement=Arrangement.End) { FreshCutToolbarAction(R.drawable.ic_plus,"Создать группу",{editingGroup=null;addingMembers=false;groupSheet=true},enabled=!busy) }
            }
            else -> {
                directory.people.forEach { row ->
                    FreshCutListRow(R.drawable.ic_user_round,row.name,when {
                        !row.active -> "Отключён"
                        row.admin -> "Администратор"
                        else -> "Пользователь"
                    },showChevron=me.id!=row.id,onClick=if(me.id!=row.id) {{person=row}} else null)
                }
                FreshCutSecondaryButton("Создать пользователя",{createUserGroup=null;createUser=true},enabled=!busy)
            }
        }
        if (!adminOnly && !fixedLogout) FreshCutSecondaryButton("Выйти",{perform{
            cloud.signOut()
            if(activity!=null) try { cloud.passkeys.clearCredentialState(activity) }
            catch(e:CancellationException){throw e} catch(_:Exception){}
        }},enabled=!busy)
    }

    if(passkeySheet && !adminOnly && activity!=null) PasskeySettingsSheet(cloud.passkeys,activity,{passkeySheet=false},::notify,cloud::signOutOtherSessions)

    if(editName) {
        var title by remember { mutableStateOf(me.name) }
        AppSheet(onDismissRequest={editName=false},containerColor=p.sheet,title={SheetHeader("Профиль")},text={
            SheetTextField(title,{title=it.take(50)},"Имя")
        },confirmButton={SheetActionButton(onClick={ editName=false;perform{cloud.renameSelf(title);notify("Имя профиля изменено")} },enabled=!busy && title.isNotBlank()){Text("Сохранить")}})
    }
    if(listSheet) {
        val row=editingList
        var title by remember(row){mutableStateOf(row?.name.orEmpty())}
        var group by remember(row,newListGroup){mutableStateOf(row?.group ?: newListGroup)}
        AppSheet(onDismissRequest={listSheet=false},containerColor=p.sheet,title={SheetHeader(if(row==null)"Новый список" else "Список")},text={
            Column(verticalArrangement=Arrangement.spacedBy(10.dp)){
                SheetTextField(title,{title=it.take(100)},"Название")
                if(row==null){
                    val choices=directory.groups.filter{directory.participates(it)}
                    FilterSelect("Группа",choices.firstOrNull{it.id==group}?.name ?: "Личный",listOf("Личный")+choices.map{it.name}) { index -> group=choices.getOrNull(index-1)?.id }
                }else SheetDeleteButton("Удалить список",{listSheet=false;deletion="Удалить список?" to suspend { cloud.removeList(row.id);notifyPending("Список удалён · ${row.name}") }},enabled=!busy)
            }
        },confirmButton={SheetActionButton(onClick={listSheet=false;perform{cloud.saveList(row?.id,title,group);notifyPending((if(row==null) "Список создан" else "Список изменён")+" · "+title.trim())}},enabled=!busy && title.isNotBlank()){Text("Сохранить")}})
    }
    if(groupSheet) {
        val original = editingGroup
        val row = original?.let { current -> directory.groups.firstOrNull { it.id == current.id } }
        var title by remember(original?.id){mutableStateOf(original?.name.orEmpty())}
        var query by remember(original?.id, addingMembers){mutableStateOf("")}
        var chosen by remember(original?.id, addingMembers){mutableStateOf(emptySet<String>())}
        var discardName by remember(original?.id) { mutableStateOf(false) }
        var pendingRemoval by remember(original?.id) { mutableStateOf<CloudPerson?>(null) }
        var memberLimit by remember(original?.id, addingMembers, query) { mutableIntStateOf(5) }
        val manage = original == null || (row != null && directory.manages(row))
        val members = if(row == null) emptyList() else directory.people.filter { user ->
            user.id == row.owner || directory.members.any { it.group == row.id && it.user == user.id }
        }.sortedWith(compareByDescending<CloudPerson> { it.id == row.owner }.thenBy { it.name })
        val memberIds = members.mapTo(HashSet()) { it.id }
        val available = directory.people.filter { it.active && it.id !in memberIds }
        val visible = available.filter { it.name.contains(query.trim(), ignoreCase = true) }.sortedBy { it.name }
        val nameChanged = manage && row != null && title.trim() != row.name
        val actionsEnabled = !busy && !nameChanged
        AppSheet(onDismissRequest={
            if(!busy) {
                when {
                    pendingRemoval != null -> pendingRemoval = null
                    discardName -> discardName = false
                    addingMembers -> addingMembers = false
                    nameChanged || (original == null && title.isNotBlank()) -> discardName = true
                    else -> groupSheet = false
                }
            }
        },containerColor=p.sheet,title={
            SheetHeader(when {
                pendingRemoval != null -> "Исключить участника?"
                discardName -> "Отменить изменения?"
                addingMembers -> "Добавить участников"
                original == null -> "Новая группа"
                else -> "Группа"
            })
        },text={
            Column(verticalArrangement=Arrangement.spacedBy(12.dp)){
                if (pendingRemoval != null) {
                    Text(pendingRemoval?.name.orEmpty(), color = p.text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                    Text("Участник потеряет доступ к общим спискам этой группы.", color = p.textSecondary, fontSize = 14.sp)
                    FreshCutSecondaryButton("Отмена", { pendingRemoval = null }, enabled = !busy)
                } else if (discardName) {
                    Text("Название не будет сохранено.", color = p.textSecondary, fontSize = 14.sp)
                    FreshCutSecondaryButton("Продолжить редактирование", { discardName = false }, enabled = !busy)
                } else if(addingMembers && row != null && manage) {
                    if(available.isNotEmpty()) {
                        SheetTextField(query,{query=it.take(100)},"Поиск пользователя")
                        visible.take(memberLimit).forEach { user ->
                            FreshCutListRow(R.drawable.ic_user_round,user.name,
                                selected=user.id in chosen,showChevron=false,onClick={
                                    chosen=if(user.id in chosen) chosen-user.id else chosen+user.id
                                })
                        }
                        if (visible.size > memberLimit) FreshCutSecondaryButton("Показать ещё", { memberLimit += 10 }, enabled = !busy)
                        if(query.isNotBlank() && visible.isEmpty()) Text(
                            stringResource(R.string.empty_user_search_description), color=p.textSecondary,fontSize=13.sp)
                    } else if(!me.admin) Text(
                        stringResource(R.string.empty_users_description),color=p.textSecondary,fontSize=13.sp)
                    if(me.admin) FreshCutListRow(R.drawable.ic_user_plus,"Создать пользователя",
                        showChevron=false,onClick={
                            createUserGroup=row.id;groupSheet=false;createUser=true
                        })
                } else if(original != null && row == null) {
                    Text("Группа недоступна",color=p.textSecondary,fontSize=14.sp)
                } else {
                    if(manage) SheetTextField(title,{title=it.take(100)},"Название", readOnly = busy)
                    else Text(row?.name.orEmpty(),color=p.text,fontSize=20.sp,fontWeight=FontWeight.SemiBold)
                    if (nameChanged) Text("Сохраните название перед другими действиями.", color = p.textSecondary, fontSize = 13.sp)
                    if(row!=null) {
                        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){
                            FreshCutSectionTitle("Участники · ${members.size}",Modifier.weight(1f))
                            if(manage) FreshCutToolbarAction(R.drawable.ic_user_plus,"Добавить участников",
                                {addingMembers=true},primary=true,enabled=actionsEnabled)
                        }
                        Column(verticalArrangement=Arrangement.spacedBy(6.dp)) {
                            members.forEach { user ->
                                val owner=user.id==row.owner
                                FreshCutListRow(R.drawable.ic_user_round,user.name,
                                    if(owner) "Владелец" else if(!user.active) "Доступ отключён" else "",
                                    showChevron=false,
                                    trailing=if(manage && !owner) {{ FreshCutToolbarAction(R.drawable.ic_close,
                                        "Исключить из группы",{pendingRemoval=user},enabled=actionsEnabled) }} else null)
                            }
                        }
                        if(!adminOnly && directory.participates(row)) {
                            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){
                                FreshCutSectionTitle("Списки",Modifier.weight(1f))
                                FreshCutToolbarAction(R.drawable.ic_plus,"Создать список",{
                                    groupSheet=false;editingList=null;newListGroup=row.id;listSheet=true
                                },enabled=actionsEnabled && directory.participates(row))
                            }
                            directory.lists.filter{it.group==row.id}.forEach{item ->
                                FreshCutListRow(R.drawable.ic_list_check,item.name,selected=workspace?.listId==item.id,showChevron=false,
                                    onClick=if(actionsEnabled && directory.manages(item)) {{groupSheet=false;editingList=item;newListGroup=row.id;listSheet=true}} else null)
                            }
                        }
                        if(!adminOnly && directory.participates(row)) {
                            FreshCutListRow(R.drawable.ic_log_out,"Выйти из группы",showChevron=false,onClick={
                                if(actionsEnabled){groupSheet=false;leavingGroup=row}
                            })
                        }
                        if(manage) {
                            SheetDeleteButton("Удалить группу",{
                                groupSheet=false;deletion="Удалить группу?" to suspend {cloud.removeGroup(row.id);notifyPending("Группа удалена · ${row.name}")}
                            },enabled=actionsEnabled)
                        }
                    }
                }
                if(pendingCount>0 && row!=null && pendingRemoval==null && !discardName) Text("Изменения ожидают синхронизации",color=p.textSecondary,fontSize=12.sp)
            }
        },confirmButton={
            val removal = pendingRemoval
            if (removal != null) {
                SheetActionButton(onClick = {
                    if (row != null && manage && removal.id != row.owner && removal.id in memberIds) perform {
                        cloud.member(row.id, removal.id, false)
                        notifyPending("Участник исключён · ${removal.name} · ${row.name}")
                        pendingRemoval = null
                    }
                }, destructive = true, enabled = !busy && row != null && manage && removal.id != row.owner && removal.id in memberIds) {
                    Text("Исключить")
                }
            } else if (discardName) {
                SheetActionButton(onClick = { discardName = false; groupSheet = false }, enabled = !busy, destructive = true) {
                    Text("Не сохранять")
                }
            } else if(addingMembers && row!=null && manage && available.isNotEmpty()) {
                SheetActionButton(onClick={perform{
                    val selected=chosen.intersect(available.mapTo(HashSet()){it.id})
                    selected.forEach { cloud.member(row.id,it,true) }
                    if(selected.isNotEmpty()) notifyPending("Участники добавлены · ${row.name} · ${selected.size}")
                    addingMembers=false
                }},enabled=!busy && chosen.isNotEmpty()){Text("Добавить · ${chosen.size}")}
            } else if(!addingMembers && manage && (original==null || title.trim()!=row?.name)) {
                SheetActionButton(onClick={perform{cloud.saveGroup(row?.id,title);notifyPending((if(row==null) "Группа создана" else "Группа изменена")+" · "+title.trim());groupSheet=false}},
                    enabled=!busy && title.isNotBlank()){Text(if(original==null)"Создать" else "Сохранить")}
            }
        })
    }
    leavingGroup?.let { row ->
        val owner = row.owner == me.id
        val candidates = directory.people.filter { it.id != me.id && it.active &&
            directory.members.any { member -> member.group == row.id && member.user == it.id } }
        var successor by remember(row.id) { mutableStateOf<String?>(null) }
        AppSheet(onDismissRequest={if(!busy)leavingGroup=null},containerColor=p.sheet,
            title={SheetHeader("Выйти из группы?")},text={
                Column(verticalArrangement=Arrangement.spacedBy(8.dp)){
                    Text(row.name,color=p.text,fontSize=17.sp,fontWeight=FontWeight.SemiBold)
                    if(owner) {
                        Text(if(candidates.isEmpty()) "Вы единственный владелец. Добавьте участника для передачи владения или удалите группу."
                            else "Выберите нового владельца. Общие списки останутся у участников.",color=p.textSecondary,fontSize=14.sp)
                        candidates.forEach { user ->
                            FreshCutListRow(R.drawable.ic_user_round,user.name,selected=successor==user.id,showChevron=false,
                                onClick={successor=user.id})
                        }
                    } else Text("Общие списки больше не будут доступны. Ваши личные покупки сохранятся.",color=p.textSecondary,fontSize=14.sp)
                    Text("Перед выходом синхронизируем изменения. Требуется интернет.",color=p.textSecondary,fontSize=12.sp)
                }
            },confirmButton={
                if(!owner || candidates.isNotEmpty()) SheetActionButton(onClick={perform{
                    cloud.leaveGroup(row.id,if(owner)successor else null)
                    leavingGroup=null;notify("Выход из группы · ${row.name}")
                }},enabled=!busy && (!owner || successor!=null),destructive=true){
                    Text(if(busy)"Синхронизация…" else if(owner)"Передать и выйти" else "Выйти")
                }
            })
    }
    if(adminOnly && me.admin) person?.let{row->
        var personName by remember(row){mutableStateOf(row.name)}
        var admin by remember(row){mutableStateOf(row.admin)}
        var active by remember(row){mutableStateOf(row.active)}
        var groupQuery by remember(row.id){mutableStateOf("")}
        AppSheet(onDismissRequest={person=null},containerColor=p.sheet,title={SheetHeader(row.name)},text={
            Column(verticalArrangement=Arrangement.spacedBy(10.dp)){
                SheetTextField(personName,{personName=it.take(50)},"Имя")
                FilterSelect("Роль",if(admin) "Администратор" else "Пользователь",listOf("Пользователь","Администратор")) { admin=it==1 }
                Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                    Text("Доступ к приложению",modifier=Modifier.weight(1f),color=p.text)
                    Switch(checked=active,onCheckedChange={active=it},enabled=!busy)
                }
                if(row.active) {
                    FreshCutSectionTitle("Группы")
                    SheetTextField(groupQuery,{groupQuery=it.take(100)},"Найти группу")
                    directory.groups.filter{g -> directory.manages(g) && (g.owner==row.id || directory.members.any{it.group==g.id && it.user==row.id} || (groupQuery.isNotBlank() && g.name.contains(groupQuery.trim(),ignoreCase=true)))}.forEach{g ->
                        val owner=g.owner==row.id
                        val included=owner || directory.members.any{it.group==g.id && it.user==row.id}
                        FreshCutListRow(R.drawable.ic_group,g.name,selected=included,showChevron=false,
                            onClick=if(owner)null else {{perform{cloud.member(g.id,row.id,!included);notifyPending((if(included) "Участник исключён" else "Участник добавлен")+" · "+row.name+" · "+g.name)}}})
                    }
                }
            }
        },confirmButton={SheetActionButton(onClick={person=null;perform{cloud.updatePerson(row.id,personName,admin,active);notify((if(!active && row.active) "Доступ отключён" else if(active && !row.active) "Доступ восстановлен" else "Пользователь изменён")+" · "+personName.trim())}},enabled=!busy){Text("Сохранить")}})
    }
    deletion?.let{(title,action)->
        AppSheet(onDismissRequest={deletion=null},containerColor=p.sheet,title={SheetHeader(title)},text={
            Text("Покупки на телефоне сохранятся",color=p.textSecondary,fontSize=14.sp)
        },confirmButton={SheetActionButton(onClick={deletion=null;perform(action)},destructive=true,enabled=!busy){Text("Удалить")}})
    }
    if(me.admin && createUser) {
        var newName by remember { mutableStateOf("") }
        var newEmail by remember { mutableStateOf("") }
        var newPassword by remember { mutableStateOf("") }
        AppSheet(onDismissRequest={if(!busy){createUser=false;if(createUserGroup!=null){groupSheet=true;addingMembers=true};createUserGroup=null}},containerColor=p.sheet,title={SheetHeader("Новый пользователь")},text={
            Column(verticalArrangement=Arrangement.spacedBy(12.dp)) {
                SheetTextField(newName,{newName=it.take(50)},"Имя")
                SheetTextField(newEmail,{newEmail=it.take(254)},"Электронная почта",keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Email))
                SheetTextField(newPassword,{newPassword=it.take(128)},"Пароль",keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Password),visualTransformation=PasswordVisualTransformation())
            }
        },confirmButton={SheetActionButton(onClick={perform{
            val created=cloud.createPerson(newName,newEmail,newPassword)
            newPassword="";createUser=false
            val targetGroup=createUserGroup
            createUserGroup=null
            if(targetGroup!=null) {
                cloud.member(targetGroup,created.id,true)
                addingMembers=false
                groupSheet=true
            }
            notify("Пользователь создан · ${newName.trim()}" + (if(created.confirmationSent) "" else " · Письмо подтверждения не отправлено") + (if(targetGroup!=null) " · Добавление в группу ожидает синхронизации" else ""))
            cloud.refresh()
        }},enabled=!busy && newName.isNotBlank() && newEmail.contains('@') && newPassword.length>=8){Text(if(busy)"Создание…" else "Создать")}})
    }
}
