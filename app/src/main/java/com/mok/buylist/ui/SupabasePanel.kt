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
internal fun SupabasePanel() {
    val p = LocalPalette.current
    val app = LocalContext.current.applicationContext as ShoppingApp
    val cloud = app.cloud
    val directory by cloud.directory.collectAsStateWithLifecycle()
    val signedIn by cloud.signedIn.collectAsStateWithLifecycle()
    val workspace by cloud.workspace.collectAsStateWithLifecycle()
    val status by cloud.status.collectAsStateWithLifecycle()
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
    var confirmImport by remember { mutableStateOf(false) }
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
    val me = directory.me
    if (me == null) {
        Text(status ?: "Загрузка…", color=p.textSecondary,fontSize=14.sp)
        FreshCutSecondaryButton("Повторить", { perform { cloud.refresh() } }, enabled = !busy)
        FreshCutSecondaryButton("Выйти",{ perform { cloud.signOut() } },enabled=!busy)
        return
    }
    Column(verticalArrangement=Arrangement.spacedBy(12.dp)) {
        FreshCutListRow(R.drawable.ic_user_round, me.name, if(me.admin) "Администратор" else "Пользователь",
            onClick={ if(!busy) editName=true })
        status?.let {
            Text(it,color=p.textSecondary,fontSize=13.sp)
            FreshCutSecondaryButton("Повторить", { perform { cloud.refresh() } }, enabled = !busy)
        }
        FreshCutTabs(listOf("Списки","Группы","Пользователи"),tab,{ tab=it })
        when(tab) {
            0 -> {
                FreshCutListRow(R.drawable.ic_archive,"На телефоне",selected=workspace==null,onClick={ perform { cloud.select(null) } })
                directory.lists.forEach { row ->
                    FreshCutListRow(R.drawable.ic_list_check,row.name,
                        if(row.group==null) "Личный" else directory.groups.firstOrNull{it.id==row.group}?.name.orEmpty(),
                        selected=workspace?.listId==row.id, onClick={ perform { cloud.select(row.id) } },
                        trailing=if(directory.manages(row)) {{ androidx.compose.material3.TextButton(onClick={ editingList=row;listSheet=true },enabled=!busy){Text("Изм.")} }} else null)
                }
                FreshCutSecondaryButton("Создать список",{ editingList=null;listSheet=true },enabled=!busy)
                if(workspace!=null && directory.lists.any{it.id==workspace?.listId && it.owner==me.id})
                    FreshCutSecondaryButton("Перенести покупки с телефона",{confirmImport=true},enabled=!busy)
            }
            1 -> {
                directory.groups.forEach { group ->
                    FreshCutListRow(R.drawable.ic_group,group.name,"Участников: "+(directory.members.count{it.group==group.id && it.user!=group.owner}+1),
                        onClick={ editingGroup=group;groupSheet=true })
                }
                FreshCutSecondaryButton("Создать группу",{editingGroup=null;groupSheet=true},enabled=!busy)
            }
            else -> directory.people.forEach { row ->
                FreshCutListRow(R.drawable.ic_user_round,row.name,when {
                    !row.active -> "Отключён"
                    row.admin -> "Администратор"
                    else -> "Пользователь"
                },showChevron=me.admin && me.id!=row.id,onClick=if(me.admin && me.id!=row.id) {{person=row}} else null)
            }
        }
        message?.let { Text(it,color=p.error,fontSize=13.sp) }
        FreshCutSecondaryButton("Выйти",{perform{cloud.signOut()}},enabled=!busy)
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
        var group by remember(row){mutableStateOf(row?.group)}
        AppSheet(onDismissRequest={listSheet=false},containerColor=p.sheet,title={SheetHeader(if(row==null)"Новый список" else "Список")},text={
            Column(verticalArrangement=Arrangement.spacedBy(10.dp)){
                SheetTextField(title,{title=it.take(100)},"Название")
                if(row==null){
                    FreshCutListRow(R.drawable.ic_user_round,"Личный",selected=group==null,onClick={group=null})
                    directory.groups.filter{it.owner==me.id}.forEach{g->FreshCutListRow(R.drawable.ic_group,g.name,selected=group==g.id,onClick={group=g.id})}
                }else FreshCutSecondaryButton("Удалить список",{listSheet=false;deletion="Удалить список?" to suspend { cloud.removeList(row.id) }},enabled=!busy)
            }
        },confirmButton={SheetActionButton(onClick={listSheet=false;perform{cloud.saveList(row?.id,title,group)}},enabled=!busy && title.isNotBlank()){Text("Сохранить")}})
    }
    if(groupSheet) {
        val row=editingGroup
        var title by remember(row){mutableStateOf(row?.name.orEmpty())}
        val manage=row==null || directory.manages(row)
        AppSheet(onDismissRequest={groupSheet=false},containerColor=p.sheet,title={SheetHeader(if(row==null)"Новая группа" else row.name)},text={
            Column(verticalArrangement=Arrangement.spacedBy(10.dp)){
                if(manage) SheetTextField(title,{title=it.take(100)},"Название")
                if(row!=null) {
                    FreshCutSectionTitle("Участники")
                    directory.people.filter{it.active}.forEach{user->
                        val owner=user.id==row.owner
                        val member=owner || directory.members.any{it.group==row.id && it.user==user.id}
                        if(manage || member) FreshCutListRow(R.drawable.ic_user_round,user.name,if(owner)"Владелец" else if(member)"Участник" else "",
                            selected=member,showChevron=manage && !owner,onClick=if(manage && !owner) {{perform{cloud.member(row.id,user.id,!member)}}} else null)
                    }
                    if(manage) FreshCutSecondaryButton("Удалить группу",{groupSheet=false;deletion="Удалить группу?" to suspend {cloud.removeGroup(row.id)}},enabled=!busy)
                }
            }
        },confirmButton={if(manage)SheetActionButton(onClick={groupSheet=false;perform{cloud.saveGroup(row?.id,title)}},enabled=!busy && title.isNotBlank()){Text("Сохранить")}})
    }
    person?.let{row->
        var admin by remember(row){mutableStateOf(row.admin)}
        var active by remember(row){mutableStateOf(row.active)}
        AppSheet(onDismissRequest={person=null},containerColor=p.sheet,title={SheetHeader(row.name)},text={
            Column(verticalArrangement=Arrangement.spacedBy(10.dp)){
                FreshCutListRow(R.drawable.ic_user_round,"Пользователь",selected=!admin,onClick={admin=false})
                FreshCutListRow(R.drawable.ic_user_round,"Администратор",selected=admin,onClick={admin=true})
                FreshCutSecondaryButton(if(active)"Отключить доступ" else "Восстановить доступ",{active=!active})
            }
        },confirmButton={SheetActionButton(onClick={person=null;perform{cloud.updatePerson(row.id,admin,active)}},enabled=!busy){Text("Сохранить")}})
    }
    deletion?.let{(title,action)->
        AppSheet(onDismissRequest={deletion=null},containerColor=p.sheet,title={SheetHeader(title)},text={
            Text("Покупки на телефоне сохранятся",color=p.textSecondary,fontSize=14.sp)
        },confirmButton={SheetActionButton(onClick={deletion=null;perform(action)},destructive=true,enabled=!busy){Text("Удалить")}})
    }
    if(confirmImport) AppSheet(onDismissRequest={confirmImport=false},containerColor=p.sheet,title={SheetHeader("Перенести покупки?")},text={
        Text("Покупки и архив с телефона будут добавлены в личный список",color=p.textSecondary,fontSize=14.sp)
    },confirmButton={SheetActionButton(onClick={confirmImport=false;perform{cloud.importLocal(app.localRepository)}},enabled=!busy){Text("Перенести")}})
}
