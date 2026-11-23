package com.mok.buylist.ui

import android.Manifest
import android.content.pm.PackageManager
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mok.buylist.R
import com.mok.buylist.lentaCatalog
import com.mok.buylist.data.Profile
import com.mok.buylist.repository
import com.mok.buylist.sync.QrMatrix
import com.mok.buylist.sync.SnapshotSection
import com.mok.buylist.data.LocalBackup
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.mok.buylist.ui.SyncController.Notice
import com.mok.buylist.ui.theme.LocalPalette
import java.time.LocalDate
import kotlin.math.floor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun SyncScreen(profile: Profile?, onRename: () -> Unit, addressRequest: Int, onActiveChange: (Boolean) -> Unit) {
    val p = LocalPalette.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val controller = remember { SyncController(context.repository, scope) }
    val state by controller.state.collectAsStateWithLifecycle()
    val activity = LocalActivity.current as? MainActivity
    var confirmUndo by rememberSaveable { mutableStateOf(false) }
    var showAddress by rememberSaveable { mutableStateOf(false) }
    val address by context.lentaCatalog.address.collectAsStateWithLifecycle()
    val scrollState = rememberScrollState()
    LaunchedEffect(Unit) { onActiveChange(false) }
    LaunchedEffect(addressRequest) { if (addressRequest > 0) scrollState.scrollTo(0) }
    LaunchedEffect(state.notice) {
        val notice = state.notice ?: return@LaunchedEffect
        val message = when (notice) {
            Notice.BACKUP_SAVED -> R.string.notice_backup_saved
            Notice.BACKUP_FAILED -> R.string.notice_backup_failed
            Notice.RESTORED -> R.string.notice_restored
            Notice.RESTORE_FAILED -> R.string.notice_restore_failed
            Notice.UNDONE -> R.string.notice_undone
            Notice.UNDO_FAILED -> R.string.notice_undo_failed
            Notice.LOCAL_BACKUP_FAILED -> R.string.notice_local_backup_failed
        }
        activity?.notifyUser(context.getString(message))
        controller.dismissNotice()
    }
    val saveBackup = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri != null) scope.launch {
            val ok = runCatching {
                val bytes = controller.backupBytes()
                withContext(Dispatchers.IO) {
                    val stream = context.contentResolver.openOutputStream(uri) ?: error("no output stream")
                    stream.use { it.write(bytes) }
                }
            }.isSuccess
            controller.onBackupWritten(ok)
        }
    }
    val openBackup = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            val bytes = runCatching {
                withContext(Dispatchers.IO) { context.contentResolver.openInputStream(uri)?.use { it.readBytes() } }
            }.getOrNull()
            controller.onBackupRead(bytes)
        }
    }
    Column(Modifier.fillMaxSize().background(p.background).verticalScroll(scrollState).padding(
        start = 16.dp, end = 16.dp, top = 16.dp,
        bottom = 16.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()),
        verticalArrangement = Arrangement.spacedBy(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        SyncAction(R.drawable.ic_store, if (address.isBlank()) stringResource(R.string.lenta_address_empty) else stringResource(R.string.lenta_address_value, address), onClick = { showAddress = true })
        if (profile != null) SyncAction(R.drawable.ic_edit, stringResource(R.string.sync_you, profile.name), onRename)
        BarcodeSourceSettingsSection()
        NearbyPanel(onUndo = { confirmUndo = true }, qrUndoMinutes = state.undoMinutes)
        Text(stringResource(R.string.backup_title), Modifier.fillMaxWidth().padding(start = 4.dp, top = 8.dp),
            color = p.textSecondary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(p.card)) {
            SyncAction(R.drawable.ic_history, stringResource(R.string.backup_save), onClick = { saveBackup.launch("spisok-pokupok-${LocalDate.now()}.buylist") })
            Box(Modifier.fillMaxWidth().padding(start = 56.dp, end = 16.dp).height(1.dp).background(p.background))
            SyncAction(R.drawable.ic_repeat, stringResource(R.string.backup_restore), onClick = { openBackup.launch(arrayOf("*/*")) })
        }
    }
    if (showAddress) LentaSettings { showAddress = false }
    if (confirmUndo) {
        AppSheet(
            onDismissRequest = { confirmUndo = false },
            containerColor = p.sheet,
            title = { Text(stringResource(R.string.sync_undo_title), color = p.text, fontWeight = FontWeight.ExtraBold) },
            text = { Text(stringResource(R.string.sync_undo_text), color = p.textSecondary) },
            confirmButton = {
                SheetActionButton(onClick = { confirmUndo = false; controller.undo() }, destructive = true) {
                    Text(stringResource(R.string.sync_undo), fontSize = 17.sp, fontWeight = FontWeight.ExtraBold)
                }
            },
            dismissButton = {
                SheetActionButton(onClick = { confirmUndo = false }) {
                    Text(stringResource(R.string.sync_undo_keep), fontSize = 17.sp, fontWeight = FontWeight.ExtraBold)
                }
            },
        )
    }
    state.pendingRestoreOwner?.let { owner ->
        AppSheet(
            onDismissRequest = controller::cancelRestore,
            containerColor = p.sheet,
            showSecondaryAction = true,
            title = { Text(stringResource(R.string.restore_owner_title, owner), color = p.text, fontWeight = FontWeight.ExtraBold) },
            text = { Text(stringResource(R.string.restore_owner_text), color = p.textSecondary) },
            confirmButton = {
                SheetActionButton(onClick = { controller.confirmRestore(asMe = true) }) {
                    Text(stringResource(R.string.restore_owner_me), fontSize = 17.sp, fontWeight = FontWeight.ExtraBold)
                }
            },
            dismissButton = {
                SheetActionButton(onClick = { controller.confirmRestore(asMe = false) }) {
                    Text(stringResource(R.string.restore_owner_other), fontSize = 17.sp, fontWeight = FontWeight.ExtraBold)
                }
            },
        )
    }
}

@Composable
private fun SyncAction(icon: Int, title: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val p = LocalPalette.current
    Row(modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick).padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(22.dp), tint = p.iconMuted)
        Text(title, Modifier.weight(1f), color = p.text, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        Text("›", color = p.textSecondary, fontSize = 24.sp, lineHeight = 24.sp)
    }
}

