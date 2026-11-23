package com.mok.buylist.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
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
    val background = if (secondary) p.chipBg else if (destructive) p.error else p.accent
    Button(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth().height(56.dp),
        shape = RoundedCornerShape(28.dp), colors = ButtonDefaults.buttonColors(
            containerColor = background, contentColor = if (secondary) p.chipText else p.onAccent,
            disabledContainerColor = background.copy(alpha = 0.38f), disabledContentColor = p.onAccent.copy(alpha = 0.7f),
        ), content = content)
}

@Composable
internal fun SheetTextField(value: String, onValueChange: (String) -> Unit, hint: String,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default, keyboardActions: KeyboardActions = KeyboardActions.Default,
    isError: Boolean = false, trailingAction: (@Composable () -> Unit)? = null) {
    val p = LocalPalette.current
    val lineColor = if (isError) p.error else p.accent
    Box(Modifier.fillMaxWidth().height(60.dp).clip(RoundedCornerShape(20.dp)).background(p.field)
        .drawBehind { val line = 3.dp.toPx(); drawRect(lineColor, Offset(0f, size.height - line), Size(size.width, line)) }
        .padding(horizontal = 18.dp), contentAlignment = Alignment.CenterStart) {
        BasicTextField(value = value, onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth().padding(end = if (trailingAction == null) 0.dp else 48.dp), singleLine = true,
            textStyle = TextStyle(color = p.text, fontFamily = Manrope, fontSize = 18.sp, fontWeight = FontWeight.SemiBold),
            cursorBrush = SolidColor(p.accent), keyboardOptions = keyboardOptions, keyboardActions = keyboardActions,
            decorationBox = { inner -> Box { if (value.isEmpty()) Text(hint, color = p.textSecondary, fontSize = 18.sp, fontWeight = FontWeight.SemiBold); inner() } })
        if (trailingAction != null) Box(Modifier.align(Alignment.CenterEnd)) { trailingAction() }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SheetDragHandle(onDismiss: () -> Unit) {
    Box(Modifier.fillMaxWidth().height(40.dp).clickable(onClickLabel = "Закрыть", onClick = onDismiss),
        contentAlignment = Alignment.Center) {
        BottomSheetDefaults.DragHandle()
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
    ModalBottomSheet(
        onDismissRequest = { if (!required) onDismissRequest() },
        sheetState = state,
        containerColor = containerColor,
        contentColor = LocalPalette.current.text,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        dragHandle = if (required) null else { { SheetDragHandle(onDismissRequest) } },
    ) {
        BackHandler(enabled = required) { }
        Column(Modifier.fillMaxWidth().imePadding().navigationBarsPadding().verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp).padding(bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            NoticeInSheet()
            Box(Modifier.fillMaxWidth()) { title() }
            text()
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                confirmButton()
                if (showSecondaryAction) CompositionLocalProvider(LocalSecondaryAction provides true) { dismissButton?.invoke() }
            }
        }
    }
}
