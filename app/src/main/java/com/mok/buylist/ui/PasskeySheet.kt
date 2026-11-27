package com.mok.buylist.ui

import android.app.Activity
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mok.buylist.R
import com.mok.buylist.cloud.*
import com.mok.buylist.ui.theme.LocalPalette
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
internal fun PasskeyAction(stage: PasskeyStage, registration: Boolean, enabled: Boolean=true, onClick: () -> Unit) {
    val p=LocalPalette.current
    val busy=stage!=PasskeyStage.IDLE
    val scale=if(busy) {
        val transition=rememberInfiniteTransition(label="passkey-pulse")
        transition.animateFloat(1f,0.88f,infiniteRepeatable(tween(650),RepeatMode.Reverse),label="pulse").value
    } else 1f
    val shape=RoundedCornerShape(22.dp)
    Row(Modifier.fillMaxWidth().clip(shape).background(p.card).border(1.dp,p.line,shape)
        .clickable(enabled=enabled && !busy,role=Role.Button,onClick=onClick).padding(12.dp),
        verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(48.dp).clip(RoundedCornerShape(16.dp)).background(p.actionFill),contentAlignment=Alignment.Center) {
            Icon(painterResource(R.drawable.ic_fingerprint),null,Modifier.size(27.dp).graphicsLayer {
                scaleX=scale;scaleY=scale
            },tint=p.actionInk)
        }
        AnimatedContent(stage,modifier=Modifier.weight(1f),label="passkey-stage") { current ->
            Column(verticalArrangement=Arrangement.spacedBy(3.dp)) {
                Text(when(current){
                    PasskeyStage.IDLE -> if(registration)"Создать ключ доступа" else "Войти по ключу доступа"
                    PasskeyStage.PREPARING -> "Подключение…"
                    PasskeyStage.CONFIRMING -> "Подтвердите вход"
                    PasskeyStage.VERIFYING -> "Проверяем ключ…"
                },color=p.text,fontWeight=FontWeight.SemiBold,fontSize=15.sp)
                if(current==PasskeyStage.IDLE) Text("Отпечаток, лицо или PIN",color=p.textSecondary,fontSize=12.sp)
            }
        }
    }
}

@Composable
internal fun PasskeySettingsSheet(api: CloudPasskeys, activity: Activity, onClose: () -> Unit, notify: (String)->Unit) {
    val p=LocalPalette.current
    val scope=rememberCoroutineScope()
    var entries by remember { mutableStateOf(emptyList<CloudPasskey>()) }
    var stage by remember { mutableStateOf(PasskeyStage.IDLE) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var removing by remember { mutableStateOf<CloudPasskey?>(null) }
    var deleting by remember { mutableStateOf(false) }
    suspend fun refresh() {
        loading=true;error=null
        try { entries=api.list() }
        catch(e:CancellationException){throw e}
        catch(e:Exception){error=passkeyError(e).ifBlank{"Не удалось загрузить ключи"}}
        finally{loading=false}
    }
    LaunchedEffect(Unit){refresh()}
    val busy=loading || deleting || stage!=PasskeyStage.IDLE
    AppSheet(onDismissRequest={if(!busy)onClose()},containerColor=p.sheet,title={SheetHeader("Ключи доступа")},text={
        Column(verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(p.card).padding(18.dp)) {
                Column(Modifier.fillMaxWidth(),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.size(64.dp).clip(RoundedCornerShape(22.dp)).background(p.actionFill),contentAlignment=Alignment.Center){
                        Icon(painterResource(R.drawable.ic_fingerprint),null,Modifier.size(36.dp),tint=p.actionInk)
                    }
                    Text("Вход без пароля",color=p.text,fontSize=20.sp,fontWeight=FontWeight.SemiBold)
                    Text("Ключ сохраняется в менеджере паролей устройства.",color=p.textSecondary,fontSize=13.sp,
                        textAlign=androidx.compose.ui.text.style.TextAlign.Center)
                }
            }
            if(error!=null) {
                Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)){
                    Text(error.orEmpty(),Modifier.weight(1f),color=p.textSecondary,fontSize=13.sp)
                    FreshCutToolbarAction(R.drawable.ic_sync,"Повторить",{scope.launch{refresh()}},enabled=!busy)
                }
            } else {
                PasskeyAction(stage,true,enabled=!busy) {
                    stage=PasskeyStage.PREPARING
                    scope.launch {
                        try { api.register(activity){stage=it};notify("Ключ доступа создан");refresh() }
                        catch(e:CancellationException){throw e}
                        catch(e:Exception){passkeyError(e).takeIf{it.isNotBlank()}?.let(notify)}
                        finally{stage=PasskeyStage.IDLE}
                    }
                }
                if(entries.isNotEmpty()) FreshCutSectionTitle("Ваши ключи · ${entries.size}")
                entries.forEach { key ->
                    val date=runCatching {DateTimeFormatter.ofPattern("dd.MM.yyyy").format(Instant.parse(key.createdAt).atZone(ZoneId.systemDefault()))}.getOrDefault("")
                    FreshCutListRow(R.drawable.ic_fingerprint,key.name.ifBlank{"Ключ доступа"},date,showChevron=false,
                        trailing={FreshCutToolbarAction(R.drawable.ic_trash,"Удалить ключ",{removing=key},enabled=!busy)})
                }
            }
        }
    },confirmButton={})
    removing?.let { key ->
        AppSheet(onDismissRequest={if(!deleting)removing=null},containerColor=p.sheet,title={SheetHeader("Удалить ключ доступа?")},text={
            Text("С этим ключом больше нельзя будет войти. Вход с паролем останется доступен.",color=p.textSecondary,fontSize=14.sp)
        },confirmButton={SheetActionButton(onClick={
            deleting=true
            scope.launch {
                try{api.remove(key.id);removing=null;notify("Ключ доступа удалён");refresh()}
                catch(e:CancellationException){throw e}
                catch(e:Exception){passkeyError(e).takeIf{it.isNotBlank()}?.let(notify)}
                finally{deleting=false}
            }
        },enabled=!deleting,destructive=true){Text("Удалить")}})
    }
}
