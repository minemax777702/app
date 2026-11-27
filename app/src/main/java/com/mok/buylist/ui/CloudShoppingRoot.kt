package com.mok.buylist.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
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
    val app=LocalContext.current.applicationContext as ShoppingApp
    val p=LocalPalette.current
    val directory by app.cloud.directory.collectAsStateWithLifecycle()
    val current by app.cloud.workspace.collectAsStateWithLifecycle()
    val status by app.cloud.status.collectAsStateWithLifecycle()
    val scope=rememberCoroutineScope()
    var selector by remember{mutableStateOf(false)}
    var error by remember{mutableStateOf(false)}
    var busy by remember{mutableStateOf(false)}
    Column(Modifier.fillMaxSize().background(p.background)){
        if(directory.me!=null && directory.lists.isNotEmpty()){
            Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal=20.dp,vertical=6.dp)
                .clip(RoundedCornerShape(16.dp)).background(p.card).clickable{selector=true}.padding(12.dp),
                verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)){
                Icon(painterResource(if(current==null)R.drawable.ic_user_round else R.drawable.ic_list_check),null,tint=p.text,modifier=Modifier.size(22.dp))
                Text(directory.lists.firstOrNull{it.id==current?.listId}?.name?:"На телефоне",Modifier.weight(1f),color=p.text,fontWeight=FontWeight.SemiBold,fontSize=15.sp)
                status?.let{Text(it,color=p.textSecondary,fontSize=12.sp)}
                Icon(painterResource(R.drawable.ic_chevron_down),"Выбрать список",tint=p.textSecondary,modifier=Modifier.size(20.dp))
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth().then(if(directory.me!=null && directory.lists.isNotEmpty())Modifier.consumeWindowInsets(WindowInsets.statusBars) else Modifier)){
            key(current?.namespace){
                val owner=remember{object:ViewModelStoreOwner{override val viewModelStore=ViewModelStore()}}
                DisposableEffect(owner){onDispose{owner.viewModelStore.clear()}}
                val model:ShoppingViewModel=viewModel(viewModelStoreOwner=owner,factory=androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.getInstance(app))
                ShoppingScreen(model)
            }
        }
    }
    if(selector)AppSheet(onDismissRequest={if(!busy)selector=false},containerColor=p.sheet,title={SheetHeader("Списки покупок")},text={
        Column(verticalArrangement=Arrangement.spacedBy(8.dp)){
            fun choose(id:String?){
                if(busy)return
                busy=true;error=false
                scope.launch{
                    try{app.cloud.select(id);selector=false}
                    catch(e:kotlinx.coroutines.CancellationException){throw e}
                    catch(_:Exception){error=true}
                    finally{busy=false}
                }
            }
            FreshCutListRow(R.drawable.ic_user_round,"На телефоне",selected=current==null,onClick={choose(null)})
            directory.lists.forEach{row->FreshCutListRow(R.drawable.ic_list_check,row.name,selected=current?.listId==row.id,onClick={choose(row.id)})}
            if(error)Text("Не удалось открыть список",color=p.error,fontSize=13.sp)
        }
    },confirmButton={})
}
