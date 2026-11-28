package com.mok.buylist.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.border
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.semantics
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mok.buylist.R
import com.mok.buylist.ui.theme.LocalPalette
import com.mok.buylist.ui.theme.UiTextStyles

private val LocalSecondaryAction = staticCompositionLocalOf { false }
internal val LocalSheetClose = staticCompositionLocalOf<(() -> Unit)?> { null }

@Composable
internal fun SheetActionButton(onClick: () -> Unit, enabled: Boolean = true, destructive: Boolean = false, content: @Composable RowScope.() -> Unit) {
    val p = LocalPalette.current
    val secondary = LocalSecondaryAction.current
    val background = if (secondary) p.field else if (destructive) p.error else p.actionFill
    val ink = if (secondary) p.text else if (destructive) p.onDanger else p.actionInk
    Button(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = if (secondary) UiMetrics.fieldHeight else UiMetrics.primaryHeight),
        shape = RoundedCornerShape(UiMetrics.controlRadius), colors = ButtonDefaults.buttonColors(
            containerColor = background, contentColor = ink,
            disabledContainerColor = p.field, disabledContentColor = p.textSecondary.copy(alpha = 0.7f),
        )) {
        ProvideTextStyle(UiTextStyles.action) { content() }
    }
}

@Composable
internal fun SheetDeleteButton(title: String, onClick: () -> Unit, enabled: Boolean = true, icon: Int = R.drawable.ic_trash) {
    val p = LocalPalette.current
    OutlinedButton(onClick=onClick, enabled=enabled, modifier=Modifier.fillMaxWidth().heightIn(min = UiMetrics.fieldHeight),
        shape=RoundedCornerShape(UiMetrics.controlRadius), border=androidx.compose.foundation.BorderStroke(1.dp,p.error),
        colors=ButtonDefaults.outlinedButtonColors(contentColor=p.error)) {
        Icon(painterResource(icon),null,Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(title, style = UiTextStyles.action, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}

@Composable
internal fun SheetTextField(value: String, onValueChange: (String) -> Unit, hint: String,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default, keyboardActions: KeyboardActions = KeyboardActions.Default,
    isError: Boolean = false, trailingAction: (@Composable () -> Unit)? = null, placeholder: String = "", readOnly: Boolean = false,
    visualTransformation: androidx.compose.ui.text.input.VisualTransformation = androidx.compose.ui.text.input.VisualTransformation.None) {
    val p = LocalPalette.current
    var focused by remember { mutableStateOf(false) }
    val fieldVerticalPadding = if (LocalDensity.current.fontScale >= 1.3f) 8.dp else 0.dp
    val lineColor = when { isError -> p.error; focused -> p.text; readOnly -> p.line; else -> p.textSecondary }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(hint, color = if (isError) p.error else p.textSecondary, style = UiTextStyles.fieldLabel)
        Box(Modifier.fillMaxWidth().heightIn(min = UiMetrics.fieldHeight).clip(RoundedCornerShape(UiMetrics.controlRadius))
            .background(p.card).border(1.dp, lineColor, RoundedCornerShape(UiMetrics.controlRadius))
            .padding(start = 16.dp, end = if (trailingAction == null) 16.dp else 2.dp), contentAlignment = Alignment.CenterStart) {
            BasicTextField(value = value, onValueChange = onValueChange, readOnly = readOnly,
                modifier = Modifier.fillMaxWidth().padding(end = if (trailingAction == null) 0.dp else UiMetrics.touchTarget)
                    .padding(vertical = fieldVerticalPadding)
                    .onFocusChanged { focused = it.isFocused }.semantics {
                        contentDescription = hint
                        if (isError) error("Проверьте значение поля")
                    }, singleLine = true,
                textStyle = UiTextStyles.fieldValue.copy(color = p.text),
                cursorBrush = SolidColor(p.text), keyboardOptions = keyboardOptions, keyboardActions = keyboardActions,
                visualTransformation = visualTransformation,
                decorationBox = { inner -> Box { if (value.isEmpty() && placeholder.isNotEmpty()) Text(placeholder, color = p.textSecondary, style = UiTextStyles.fieldPlaceholder); inner() } })
            if (trailingAction != null) Box(Modifier.align(Alignment.CenterEnd)) { trailingAction() }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SheetDragHandle(onDismiss: () -> Unit) {
    Box(Modifier.fillMaxWidth().height(24.dp), contentAlignment = Alignment.TopCenter) {
        Box(Modifier.padding(top = 10.dp).size(40.dp, 4.dp).clip(RoundedCornerShape(2.dp)).background(LocalPalette.current.textSecondary))
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
    confirmButton: (@Composable () -> Unit)? = null,
    dismissButton: (@Composable () -> Unit)? = null,
    required: Boolean = false,
    showSecondaryAction: Boolean = false,
    minBodyHeight: androidx.compose.ui.unit.Dp = 0.dp,
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
        Column(Modifier.fillMaxWidth().imePadding().navigationBarsPadding()
            .heightIn(min = minBodyHeight).padding(horizontal = UiMetrics.screenInset)
            .padding(top = if (required) 24.dp else 0.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.SpaceBetween) {
            Row(Modifier.fillMaxWidth().heightIn(min = UiMetrics.touchTarget), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.weight(1f)) {
                    CompositionLocalProvider(LocalSheetClose provides null) {
                        ProvideTextStyle(MaterialTheme.typography.headlineSmall.copy(fontSize = 24.sp,
                            lineHeight = 30.sp, fontWeight = FontWeight.SemiBold)) { title() }
                    }
                }
                if (!required) SheetCloseButton(onDismissRequest)
            }
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(top = 16.dp)) {
                text()
            }
            if (confirmButton != null || (showSecondaryAction && dismissButton != null)) {
                Column(Modifier.fillMaxWidth().padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(UiMetrics.gap)) {
                    confirmButton?.invoke()
                    if (showSecondaryAction) CompositionLocalProvider(LocalSecondaryAction provides true) { dismissButton?.invoke() }
                }
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
    sheetGesturesEnabled: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
        containerColor = containerColor,
        contentColor = contentColor,
        shape = RoundedCornerShape(topStart = UiMetrics.sheetRadius, topEnd = UiMetrics.sheetRadius),
        dragHandle = dragHandle,
        sheetGesturesEnabled = sheetGesturesEnabled,
        scrimColor = Color.Black.copy(alpha = 0.40f),
        content = {
            val scope = this
            CompositionLocalProvider(LocalSheetClose provides onDismissRequest) {
                NoticeInSheet()
                scope.content()
            }
        },
    )
}


@Composable
internal fun SheetCloseButton(onClose: () -> Unit) {
    val p = LocalPalette.current
    Box(Modifier.size(UiMetrics.touchTarget).clip(RoundedCornerShape(UiMetrics.iconRadius)).background(p.field)
        .clickable(role = androidx.compose.ui.semantics.Role.Button, onClickLabel = "Закрыть", onClick = onClose), contentAlignment = Alignment.Center) {
        Icon(painterResource(R.drawable.ic_close), contentDescription = "Закрыть",
            tint = p.text, modifier = Modifier.size(24.dp))
    }
}

@Composable
internal fun SheetFixedValue(label: String, value: String) {
    SheetTextField(value, {}, label, readOnly = true)
}


/** Search results own vertical scrolling; dismissal is only an explicit handle gesture. */
@Composable
internal fun SearchSheetDragHandle(onDismiss: () -> Unit) {
    val p = LocalPalette.current
    val close = androidx.compose.runtime.rememberUpdatedState(onDismiss)
    Box(
        Modifier.fillMaxWidth().height(24.dp)
            .pointerInput(Unit) {
                var distance = 0f
                detectVerticalDragGestures(
                    onDragStart = { distance = 0f },
                    onVerticalDrag = { change, delta ->
                        change.consume()
                        distance = (distance + delta).coerceAtLeast(0f)
                    },
                    onDragEnd = { if (distance >= 40.dp.toPx()) close.value() },
                    onDragCancel = { distance = 0f },
                )
            },
        contentAlignment = Alignment.TopCenter,
    ) {
        Box(Modifier.padding(top = 10.dp).size(40.dp, 4.dp)
            .clip(RoundedCornerShape(2.dp)).background(p.textSecondary))
    }
}
