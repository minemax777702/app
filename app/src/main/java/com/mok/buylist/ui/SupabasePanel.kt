package com.mok.buylist.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
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
internal fun SupabasePanel(authOnly: Boolean = false, adminOnly: Boolean = false) {
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
            FreshCutTabs(listOf("Вход","Регистрация"), if (registration) 1 else 0, { registration = it == 1 })
            if (registration) SheetTextField(name,{ name=it.take(50) },"Имя")
            SheetTextField(email,{ email=it.take(254) },"Электронная почта",keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Email))
            SheetTextField(password,{ password=it.take(128) },"Пароль",keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Password),
                visualTransformation=PasswordVisualTransformation())
            FreshCutSecondaryButton(if (busy) "Подключение…" else if (registration) "Зарегистрироваться" else "Войти", {
                perform {
                    if (registration) {
                        if (!cloud.signUp(name,email,password)) {
                            registration=false; notify("Подтвердите почту и войдите")
                        }
                    } else cloud.signIn(email,password)
                    password=""
                }
            },enabled=!busy && passkeyStage==PasskeyStage.IDLE && email.contains('@') && password.length>=6 && (!registration || name.isNotBlank()))
            if(!registration && activity!=null) PasskeyAction(passkeyStage, false, enabled=!busy) {
                if(passkeyStage!=PasskeyStage.IDLE)return@PasskeyAction
                passkeyStage=PasskeyStage.PREPARING
                scope.launch {
                    try { cloud.passkeys.signIn(activity){passkeyStage=it}; password="";notify("Вход по ключу доступа выполнен") }
                    catch(e:CancellationException){throw e}
                    catch(e:Exception){passkeyError(e).takeIf{it.isNotBlank()}?.let(::notify)}
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
            onClick={ if(!busy) editName=true }, showChevron=false)
        if (!adminOnly && activity!=null) FreshCutListRow(R.drawable.ic_fingerprint,"Ключи доступа",
            "Вход без пароля",showChevron=false,onClick={passkeySheet=true})
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
                FreshCutSecondaryButton("Создать список",{ editingList=null;newListGroup=null;listSheet=true },enabled=!busy)
            }
            selectedTab == 1 -> {
                directory.groups.filter { adminOnly || directory.participates(it) }.forEach { group ->
                    val memberCount = directory.members.count{it.group==group.id && it.user!=group.owner}+1
                    val subtitle = if(adminOnly) "Участников: $memberCount" else "Списков: ${directory.lists.count{it.group==group.id}} · Участников: $memberCount"
                    FreshCutListRow(R.drawable.ic_group,group.name,subtitle,showChevron=false,
                        onClick={ editingGroup=group;addingMembers=false;groupSheet=true })
                }
                FreshCutSecondaryButton("Создать группу",{editingGroup=null;addingMembers=false;groupSheet=true},enabled=!busy)
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
        if (!adminOnly) FreshCutSecondaryButton("Выйти",{perform{
            cloud.signOut()
            if(activity!=null) try { cloud.passkeys.clearCredentialState(activity) }
            catch(e:CancellationException){throw e} catch(_:Exception){}
        }},enabled=!busy)
    }

    if(passkeySheet && !adminOnly && activity!=null) PasskeySettingsSheet(cloud.passkeys,activity,{passkeySheet=false},::notify)

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
                    FreshCutListRow(R.drawable.ic_user_round,"Личный",selected=group==null,showChevron=false,onClick={group=null})
                    directory.groups.filter{directory.participates(it)}.forEach{g->FreshCutListRow(R.drawable.ic_group,g.name,selected=group==g.id,showChevron=false,onClick={group=g.id})}
                }else FreshCutSecondaryButton("Удалить список",{listSheet=false;deletion="Удалить список?" to suspend { cloud.removeList(row.id);notifyPending("Список удалён · ${row.name}") }},enabled=!busy)
            }
        },confirmButton={SheetActionButton(onClick={listSheet=false;perform{cloud.saveList(row?.id,title,group);notifyPending((if(row==null) "Список создан" else "Список изменён")+" · "+title.trim())}},enabled=!busy && title.isNotBlank()){Text("Сохранить")}})
    }
    if(groupSheet) {
        val original = editingGroup
        val row = original?.let { current -> directory.groups.firstOrNull { it.id == current.id } }
        var title by remember(original?.id){mutableStateOf(original?.name.orEmpty())}
        var query by remember(original?.id, addingMembers){mutableStateOf("")}
        var chosen by remember(original?.id, addingMembers){mutableStateOf(emptySet<String>())}
        val manage = original == null || (row != null && directory.manages(row))
        val members = if(row == null) emptyList() else directory.people.filter { user ->
            user.id == row.owner || directory.members.any { it.group == row.id && it.user == user.id }
        }.sortedWith(compareByDescending<CloudPerson> { it.id == row.owner }.thenBy { it.name })
        val memberIds = members.mapTo(HashSet()) { it.id }
        val available = directory.people.filter { it.active && it.id !in memberIds }
        val visible = available.filter { it.name.contains(query.trim(), ignoreCase = true) }
        AppSheet(onDismissRequest={
            if(!busy) { if(addingMembers) addingMembers=false else groupSheet=false }
        },containerColor=p.sheet,title={
            SheetHeader(if(addingMembers) "Добавить участников" else if(original==null) "Новая группа" else "Группа")
        },text={
            Column(verticalArrangement=Arrangement.spacedBy(12.dp)){
                if(addingMembers && row != null && manage) {
                    if(available.isNotEmpty()) {
                        SheetTextField(query,{query=it.take(100)},"Поиск пользователя")
                        visible.forEach { user ->
                            FreshCutListRow(R.drawable.ic_user_round,user.name,
                                selected=user.id in chosen,showChevron=false,onClick={
                                    chosen=if(user.id in chosen) chosen-user.id else chosen+user.id
                                })
                        }
                        if(visible.isEmpty()) Text("Пользователи не найдены",color=p.textSecondary,fontSize=13.sp)
                    } else Text("Нет пользователей для добавления",color=p.textSecondary,fontSize=14.sp)
                    if(me.admin) FreshCutListRow(R.drawable.ic_user_plus,"Создать пользователя",
                        showChevron=false,onClick={
                            createUserGroup=row.id;groupSheet=false;createUser=true
                        })
                } else if(original != null && row == null) {
                    Text("Группа недоступна",color=p.textSecondary,fontSize=14.sp)
                } else {
                    if(manage) SheetTextField(title,{title=it.take(100)},"Название")
                    else Text(row?.name.orEmpty(),color=p.text,fontSize=20.sp,fontWeight=FontWeight.SemiBold)
                    if(row!=null) {
                        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){
                            FreshCutSectionTitle("Участники · ${members.size}",Modifier.weight(1f))
                            if(manage) FreshCutToolbarAction(R.drawable.ic_user_plus,"Добавить участников",
                                {addingMembers=true},primary=true,enabled=!busy)
                        }
                        Column(verticalArrangement=Arrangement.spacedBy(6.dp)) {
                            members.forEach { user ->
                                val owner=user.id==row.owner
                                FreshCutListRow(R.drawable.ic_user_round,user.name,
                                    if(owner) "Владелец" else if(!user.active) "Доступ отключён" else "",
                                    showChevron=false,
                                    trailing=if(manage && !owner) {{ FreshCutToolbarAction(R.drawable.ic_user_minus,
                                        "Исключить из группы",{perform{cloud.member(row.id,user.id,false);notifyPending("Участник исключён · ${user.name} · ${row.name}")}},enabled=!busy) }} else null)
                            }
                        }
                        if(!adminOnly && directory.participates(row)) {
                            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){
                                FreshCutSectionTitle("Списки",Modifier.weight(1f))
                                FreshCutToolbarAction(R.drawable.ic_plus,"Создать список",{
                                    groupSheet=false;editingList=null;newListGroup=row.id;listSheet=true
                                },enabled=!busy)
                            }
                            directory.lists.filter{it.group==row.id}.forEach{item ->
                                FreshCutListRow(R.drawable.ic_list_check,item.name,selected=workspace?.listId==item.id,showChevron=false,
                                    onClick={groupSheet=false;editingList=item;newListGroup=row.id;listSheet=true})
                            }
                            if(directory.lists.none{it.group==row.id}) Text("Нет списков",color=p.textSecondary,fontSize=13.sp)
                        }
                        if(!adminOnly && directory.participates(row)) {
                            FreshCutListRow(R.drawable.ic_log_out,"Выйти из группы",showChevron=false,onClick={
                                if(!busy){groupSheet=false;leavingGroup=row}
                            })
                        }
                        if(manage) Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.End) {
                            FreshCutToolbarAction(R.drawable.ic_trash,"Удалить группу",{
                                groupSheet=false;deletion="Удалить группу?" to suspend {cloud.removeGroup(row.id);notifyPending("Группа удалена · ${row.name}")}
                            },enabled=!busy)
                        }
                    }
                }
                if(pendingCount>0 && row!=null) Text("Изменения ожидают синхронизации",color=p.textSecondary,fontSize=12.sp)
            }
        },confirmButton={
            if(addingMembers && row!=null && manage && available.isNotEmpty()) {
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
        AppSheet(onDismissRequest={person=null},containerColor=p.sheet,title={SheetHeader(row.name)},text={
            Column(verticalArrangement=Arrangement.spacedBy(10.dp)){
                SheetTextField(personName,{personName=it.take(50)},"Имя")
                FreshCutListRow(R.drawable.ic_user_round,"Пользователь",selected=!admin,showChevron=false,onClick={admin=false})
                FreshCutListRow(R.drawable.ic_shield,"Администратор",selected=admin,showChevron=false,onClick={admin=true})
                FreshCutSecondaryButton(if(active)"Отключить доступ" else "Восстановить доступ",{active=!active})
                if(row.active) {
                    FreshCutSectionTitle("Группы")
                    directory.groups.filter{directory.manages(it)}.forEach{g ->
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
    if(adminOnly && me.admin && createUser) {
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
