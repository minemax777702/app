package com.mok.buylist.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mok.buylist.R
import com.mok.buylist.ShoppingApp
import com.mok.buylist.ui.theme.LocalPalette
import kotlinx.coroutines.launch

@Composable
internal fun CloudShoppingRoot() {
    val app = LocalContext.current.applicationContext as ShoppingApp
    val p = LocalPalette.current
    val cloud = app.cloud
    val directory by cloud.directory.collectAsStateWithLifecycle()
    val current = cloud.workspace.collectAsStateWithLifecycle().value
    val signedIn by cloud.signedIn.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val prefs = remember { app.getSharedPreferences("onboarding", android.content.Context.MODE_PRIVATE) }
    var welcome by rememberSaveable { mutableStateOf(!prefs.getBoolean("seen",false) && app.localProfile.profile.value == null) }
    fun finishWelcome() { prefs.edit().putBoolean("seen",true).apply(); welcome = false }
    LaunchedEffect(signedIn) { if (signedIn) finishWelcome() }
    var selector by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var destination by rememberSaveable { mutableStateOf("LIST") }

    val picker: @Composable () -> Unit = {
        if (directory.me != null && directory.lists.isNotEmpty()) {
            Row(Modifier.fillMaxWidth().padding(start=20.dp,end=20.dp,bottom=8.dp)
                .clip(RoundedCornerShape(16.dp)).background(p.card).clickable { selector=true }
                .padding(horizontal=14.dp,vertical=11.dp),
                verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                Icon(painterResource(R.drawable.ic_list_check),null,tint=p.text,modifier=Modifier.size(22.dp))
                Text(directory.lists.firstOrNull { it.id==current?.listId }?.name ?: "Списки",
                    Modifier.weight(1f),color=p.text,fontWeight=FontWeight.SemiBold,fontSize=15.sp)
                Icon(painterResource(R.drawable.ic_chevron_down),"Выбрать список",tint=p.textSecondary,modifier=Modifier.size(20.dp))
            }
        }
    }

    key(current?.namespace) {
        val owner = remember { object : ViewModelStoreOwner { override val viewModelStore=ViewModelStore() } }
        DisposableEffect(owner) { onDispose { owner.viewModelStore.clear() } }
        val factory = remember(current) {
            object : androidx.lifecycle.ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T =
                    ShoppingViewModel(app,current) as T
            }
        }
        val model: ShoppingViewModel = viewModel(viewModelStoreOwner=owner,factory=factory)
        ShoppingScreen(model, initialDestination=destination, onDestinationChange={ destination=it }, listPicker=picker)
    }
    if (welcome && !signedIn) AppSheet(onDismissRequest={finishWelcome()},containerColor=p.sheet,
        title={SheetHeader("Список покупок")},text={SupabasePanel(authOnly=true)},
        confirmButton={FreshCutSecondaryButton("Продолжить без входа",{finishWelcome()})})
    if (selector) AppSheet(onDismissRequest={if(!busy)selector=false},containerColor=p.sheet,title={SheetHeader("Списки покупок")},text={
        Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
            fun choose(id:String) {
                if(busy)return
                busy=true;error=false
                scope.launch {
                    try { cloud.select(id);selector=false }
                    catch(e:kotlinx.coroutines.CancellationException){throw e}
                    catch(_:Exception){error=true}
                    finally{busy=false}
                }
            }
            val personal = directory.lists.filter { it.group == null }
            if(personal.isNotEmpty()) FreshCutSectionTitle("Личные списки")
            personal.forEach { row -> FreshCutListRow(R.drawable.ic_list_check,row.name,
                selected=current?.listId==row.id,showChevron=false,onClick={choose(row.id)}) }
            directory.groups.filter{directory.participates(it)}.forEach { group ->
                val lists=directory.lists.filter{it.group==group.id}
                if(lists.isNotEmpty()) {
                    FreshCutSectionTitle(group.name)
                    lists.forEach { row -> FreshCutListRow(R.drawable.ic_list_check,row.name,
                        selected=current?.listId==row.id,showChevron=false,onClick={choose(row.id)}) }
                }
            }
            if(error)Text("Не удалось открыть список",color=p.error,fontSize=13.sp)
        }
    },confirmButton={})
}
