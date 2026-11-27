package com.mok.buylist.ui

import androidx.compose.foundation.layout.*
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
    val directory by cloud.directory.collectAsStateWithLifecycle()
    val signedIn by cloud.signedIn.collectAsStateWithLifecycle()
    val workspace by cloud.workspace.collectAsStateWithLifecycle()
    val status by cloud.status.collectAsStateWithLifecycle()
    val pendingCount by cloud.pendingCount.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var registration by remember { mutableStateOf(false) }
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
    var newListGroup by remember { mutableStateOf<String?>(null) }
    var deletion by remember { mutableStateOf<Pair<String, suspend () -> Unit>?>(null) }
    fun perform(action: suspend () -> Unit) {
        if (busy) return
        busy = true; message = null
        scope.launch {
            try { action() }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { message = "Не удалось выполнить операцию. Повторите попытку." }
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
            FreshCutTabs(listOf("Вход","Регистрация"), if (registration) 1 else 0, { registration = it == 1; message = null })
            if (registration) SheetTextField(name,{ name=it.take(50) },"Имя")
            SheetTextField(email,{ email=it.take(254) },"Электронная почта",keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Email))
            SheetTextField(password,{ password=it.take(128) },"Пароль",keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Password),
                visualTransformation=PasswordVisualTransformation())
            FreshCutSecondaryButton(if (busy) "Подключение…" else if (registration) "Зарегистрироваться" else "Войти", {
                perform {
                    if (registration) {
                        if (!cloud.signUp(name,email,password)) {
                            registration=false; message="Подтвердите почту и войдите"
                        }
                    } else cloud.signIn(email,password)
                    password=""
                }
            },enabled=!busy && email.contains('@') && password.length>=6 && (!registration || name.isNotBlank()))
            message?.let { Text(it,color=p.textSecondary,fontSize=13.sp) }
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
                    FreshCutListRow(R.drawable.ic_group,group.name,subtitle,
                        onClick={ editingGroup=group;groupSheet=true })
                }
                FreshCutSecondaryButton("Создать группу",{editingGroup=null;groupSheet=true},enabled=!busy)
            }
            else -> {
                directory.people.forEach { row ->
                    FreshCutListRow(R.drawable.ic_user_round,row.name,when {
                        !row.active -> "Отключён"
                        row.admin -> "Администратор"
                        else -> "Пользователь"
                    },showChevron=me.id!=row.id,onClick=if(me.id!=row.id) {{person=row}} else null)
                }
                FreshCutSecondaryButton("Создать пользователя",{createUser=true},enabled=!busy)
            }
        }
        message?.let { Text(it,color=p.error,fontSize=13.sp) }
        if (!adminOnly) FreshCutSecondaryButton("Выйти",{perform{cloud.signOut()}},enabled=!busy)
    }

    if(editName) {
        var title by remember { mutableStateOf(me.name) }
        AppSheet(onDismissRequest={editName=false},containerColor=p.sheet,title={SheetHeader("Профиль")},text={
            SheetTextField(title,{title=it.take(50)},"Имя")
        },confirmButton={SheetActionButton(onClick={ editName=false;perform{cloud.renameSelf(title)} },enabled=!busy && title.isNotBlank()){Text("Сохранить")}})
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
                }else FreshCutSecondaryButton("Удалить список",{listSheet=false;deletion="Удалить список?" to suspend { cloud.removeList(row.id) }},enabled=!busy)
            }
        },confirmButton={SheetActionButton(onClick={listSheet=false;perform{cloud.saveList(row?.id,title,group)}},enabled=!busy && title.isNotBlank()){Text("Сохранить")}})
    }
    if(groupSheet) {
        val row=editingGroup
        var title by remember(row){mutableStateOf(row?.name.orEmpty())}
        val manage=row==null || (if(adminOnly) directory.manages(row) else row.owner==me.id)
        AppSheet(onDismissRequest={groupSheet=false},containerColor=p.sheet,title={SheetHeader(if(row==null)"Новая группа" else row.name)},text={
            Column(verticalArrangement=Arrangement.spacedBy(10.dp)){
                if(manage) SheetTextField(title,{title=it.take(100)},"Название")
                if(row!=null) {
                    if(!adminOnly && directory.participates(row)) {
                        FreshCutSectionTitle("Списки")
                        directory.lists.filter{it.group==row.id}.forEach{item ->
                            FreshCutListRow(R.drawable.ic_list_check,item.name,selected=workspace?.listId==item.id,showChevron=false,
                                onClick={groupSheet=false;editingList=item;newListGroup=row.id;listSheet=true})
                        }
                        FreshCutSecondaryButton("Создать список",{groupSheet=false;editingList=null;newListGroup=row.id;listSheet=true},enabled=!busy)
                    }
                    FreshCutSectionTitle("Участники")
                    directory.people.filter{it.active}.forEach{user->
                        val owner=user.id==row.owner
                        val member=owner || directory.members.any{it.group==row.id && it.user==user.id}
                        if(manage || member) FreshCutListRow(R.drawable.ic_user_round,user.name,if(owner)"Владелец" else if(member)"Участник" else "",
                            selected=member,showChevron=false,onClick=if(manage && !owner) {{perform{cloud.member(row.id,user.id,!member)}}} else null)
                    }
                    if(manage) FreshCutSecondaryButton("Удалить группу",{groupSheet=false;deletion="Удалить группу?" to suspend {cloud.removeGroup(row.id)}},enabled=!busy)
                }
            }
        },confirmButton={if(manage)SheetActionButton(onClick={groupSheet=false;perform{cloud.saveGroup(row?.id,title)}},enabled=!busy && title.isNotBlank()){Text("Сохранить")}})
    }
    if(adminOnly && me.admin) person?.let{row->
        var personName by remember(row){mutableStateOf(row.name)}
        var admin by remember(row){mutableStateOf(row.admin)}
        var active by remember(row){mutableStateOf(row.active)}
        AppSheet(onDismissRequest={person=null},containerColor=p.sheet,title={SheetHeader(row.name)},text={
            Column(verticalArrangement=Arrangement.spacedBy(10.dp)){
                SheetTextField(personName,{personName=it.take(50)},"Имя")
                FreshCutListRow(R.drawable.ic_user_round,"Пользователь",selected=!admin,showChevron=false,onClick={admin=false})
                FreshCutListRow(R.drawable.ic_user_round,"Администратор",selected=admin,showChevron=false,onClick={admin=true})
                FreshCutSecondaryButton(if(active)"Отключить доступ" else "Восстановить доступ",{active=!active})
                if(row.active) {
                    FreshCutSectionTitle("Группы")
                    directory.groups.filter{directory.manages(it)}.forEach{g ->
                        val owner=g.owner==row.id
                        val included=owner || directory.members.any{it.group==g.id && it.user==row.id}
                        FreshCutListRow(R.drawable.ic_group,g.name,selected=included,showChevron=false,
                            onClick=if(owner)null else {{perform{cloud.member(g.id,row.id,!included)}}})
                    }
                }
            }
        },confirmButton={SheetActionButton(onClick={person=null;perform{cloud.updatePerson(row.id,personName,admin,active)}},enabled=!busy){Text("Сохранить")}})
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
        AppSheet(onDismissRequest={if(!busy)createUser=false},containerColor=p.sheet,title={SheetHeader("Новый пользователь")},text={
            Column(verticalArrangement=Arrangement.spacedBy(12.dp)) {
                SheetTextField(newName,{newName=it.take(50)},"Имя")
                SheetTextField(newEmail,{newEmail=it.take(254)},"Электронная почта",keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Email))
                SheetTextField(newPassword,{newPassword=it.take(128)},"Пароль",keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Password),visualTransformation=PasswordVisualTransformation())
                message?.let{Text(it,color=p.error,fontSize=13.sp)}
            }
        },confirmButton={SheetActionButton(onClick={perform{
            val sent=cloud.createPerson(newName,newEmail,newPassword)
            newPassword="";createUser=false
            message=if(sent)"Пользователь создан" else "Пользователь создан. Письмо подтверждения не отправлено"
        }},enabled=!busy && newName.isNotBlank() && newEmail.contains('@') && newPassword.length>=8){Text(if(busy)"Создание…" else "Создать")}})
    }
}
