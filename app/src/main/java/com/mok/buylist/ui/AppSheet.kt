package com.mok.buylist.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.activity.compose.BackHandler
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mok.buylist.R
import com.mok.buylist.ui.theme.LocalPalette
import com.mok.buylist.ui.theme.Manrope

private val LocalSecondaryAction = staticCompositionLocalOf { false }

@Composable
internal fun SheetActionButton(onClick: () -> Unit, enabled: Boolean = true, destructive: Boolean = false, content: @Composable RowScope.() -> Unit) {
    val p = LocalPalette.current
    val secondary = LocalSecondaryAction.current
    val background = if (secondary) p.field else if (destructive) p.error else p.actionFill
    val ink = if (secondary) p.text else if (destructive) p.onDanger else p.actionInk
    Button(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth().height(if (secondary) 52.dp else 54.dp),
        shape = RoundedCornerShape(16.dp), colors = ButtonDefaults.buttonColors(
            containerColor = background, contentColor = ink,
            disabledContainerColor = p.field, disabledContentColor = p.textSecondary.copy(alpha = 0.7f),
        ), content = content)
}

@Composable
internal fun SheetTextField(value: String, onValueChange: (String) -> Unit, hint: String,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default, keyboardActions: KeyboardActions = KeyboardActions.Default,
    isError: Boolean = false, trailingAction: (@Composable () -> Unit)? = null) {
    val p = LocalPalette.current
    var focused by remember { mutableStateOf(false) }
    val lineColor = if (isError) p.error else if (focused) p.text else p.line
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(hint, color = if (isError) p.error else p.textSecondary, fontSize = 12.sp,
            fontWeight = FontWeight.Medium)
        Box(Modifier.fillMaxWidth().height(54.dp).clip(RoundedCornerShape(16.dp))
            .background(p.card).border(1.dp, lineColor, RoundedCornerShape(16.dp))
            .padding(horizontal = 16.dp), contentAlignment = Alignment.CenterStart) {
            BasicTextField(value = value, onValueChange = onValueChange,
                modifier = Modifier.fillMaxWidth().padding(end = if (trailingAction == null) 0.dp else 48.dp)
                    .onFocusChanged { focused = it.isFocused }, singleLine = true,
                textStyle = TextStyle(color = p.text, fontFamily = Manrope, fontSize = 15.sp, fontWeight = FontWeight.Medium),
                cursorBrush = SolidColor(p.text), keyboardOptions = keyboardOptions, keyboardActions = keyboardActions,
                decorationBox = { inner -> inner() })
            if (trailingAction != null) Box(Modifier.align(Alignment.CenterEnd)) { trailingAction() }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SheetDragHandle(onDismiss: () -> Unit) {
    Box(Modifier.fillMaxWidth().height(40.dp).clickable(onClickLabel = "Закрыть", onClick = onDismiss),
        contentAlignment = Alignment.Center) {
        Box(Modifier.size(36.dp, 4.dp).clip(RoundedCornerShape(2.dp)).background(LocalPalette.current.line))
    }
}

/** Общая оболочка внутренних форм и подтверждений. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AppSheet(
    onDismissRequest: () -> Unit,
    containerColor: Color,
    title: @Composable () -> Unit,
    text: @Composable () -> Unit,
    confirmButton: @Composable () -> Unit,
    dismissButton: (@Composable () -> Unit)? = null,
    required: Boolean = false,
    showSecondaryAction: Boolean = false,
) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true, confirmValueChange = {
        !required || it != SheetValue.Hidden
    })
    AppModalSheet(
        onDismissRequest = { if (!required) onDismissRequest() },
        sheetState = state,
        containerColor = containerColor,
        contentColor = LocalPalette.current.text,
        dragHandle = if (required) null else { { SheetDragHandle(onDismissRequest) } },
    ) {
        BackHandler(enabled = required) { }
        Column(Modifier.fillMaxWidth().imePadding().navigationBarsPadding().verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp).padding(top = if (required) 24.dp else 4.dp, bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            NoticeInSheet()
            Box(Modifier.fillMaxWidth()) {
                ProvideTextStyle(MaterialTheme.typography.headlineSmall.copy(fontSize = 24.sp, fontWeight = FontWeight.SemiBold)) { title() }
            }
            text()
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                confirmButton()
                if (showSecondaryAction) CompositionLocalProvider(LocalSecondaryAction provides true) { dismissButton?.invoke() }
            }
        }
    }
}

/** Общая оболочка для всех нижних панелей, включая первый запуск. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AppModalSheet(
    onDismissRequest: () -> Unit,
    containerColor: Color,
    sheetState: SheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    contentColor: Color = LocalPalette.current.text,
    dragHandle: (@Composable () -> Unit)? = { SheetDragHandle(onDismissRequest) },
    content: @Composable ColumnScope.() -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
        containerColor = containerColor,
        contentColor = contentColor,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        dragHandle = dragHandle,
        content = content,
    )
}

