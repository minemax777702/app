package com.mok.buylist.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MenuDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import com.mok.buylist.ui.theme.LocalPalette

/** Единый стиль всплывающих меню FRESH CUT. */
@Composable
internal fun AppDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val p = LocalPalette.current
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        offset = DpOffset(0.dp, UiMetrics.gap),
        shape = RoundedCornerShape(UiMetrics.cardRadius),
        containerColor = p.sheet,
        tonalElevation = 0.dp,
        shadowElevation = 4.dp,
        border = BorderStroke(1.dp, p.line),
        content = content,
    )
}

@Composable
internal fun AppDropdownMenuItem(
    text: @Composable () -> Unit,
    onClick: () -> Unit,
    enabled: Boolean = true,
    leadingIcon: (@Composable () -> Unit)? = null,
    trailingIcon: (@Composable () -> Unit)? = null,
) {
    val p = LocalPalette.current
    DropdownMenuItem(
        text = text,
        onClick = onClick,
        enabled = enabled,
        leadingIcon = leadingIcon,
        trailingIcon = trailingIcon,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
        colors = MenuDefaults.itemColors(
            textColor = p.text,
            leadingIconColor = p.text,
            trailingIconColor = p.textSecondary,
        ),
    )
}
