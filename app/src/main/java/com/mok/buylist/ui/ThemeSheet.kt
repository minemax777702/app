package com.mok.buylist.ui

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mok.buylist.R
import com.mok.buylist.ui.theme.LocalPalette
import com.mok.buylist.ui.theme.ThemeMode

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThemeSheet(
    appMode: ThemeMode,
    widgetMode: ThemeMode,
    onAppMode: (ThemeMode) -> Unit,
    onWidgetMode: (ThemeMode) -> Unit,
    onDismiss: () -> Unit,
) {
    val p = LocalPalette.current
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = p.sheet,
        dragHandle = { SheetDragHandle(onDismiss) },
        contentColor = p.text,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(start = 20.dp, end = 20.dp, bottom = 20.dp),
        ) {
            NoticeInSheet()
            SheetHeader(stringResource(R.string.theme_title))
            SectionLabel(R.string.theme_app)
            ThemeSegments(appMode, onAppMode)
            SectionLabel(R.string.theme_widget, Modifier.padding(top = 20.dp))
            ThemeSegments(widgetMode, onWidgetMode)
        }
    }
}

@Composable
private fun SectionLabel(@StringRes title: Int, modifier: Modifier = Modifier) {
    Text(
        stringResource(title).uppercase(),
        modifier.padding(start = 4.dp, bottom = 10.dp, top = 6.dp),
        color = LocalPalette.current.textSecondary,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        fontWeight = FontWeight.ExtraBold,
        letterSpacing = 0.3.sp,
    )
}

@Composable
private fun ThemeSegments(selected: ThemeMode, onSelect: (ThemeMode) -> Unit) {
    val p = LocalPalette.current
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(p.field)
            .padding(4.dp)
            .selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        ThemeOption(ThemeMode.AUTO, R.string.theme_auto, R.drawable.ic_theme_auto, selected, onSelect, Modifier.weight(1f))
        ThemeOption(ThemeMode.LIGHT, R.string.theme_light, R.drawable.ic_sun, selected, onSelect, Modifier.weight(1f))
        ThemeOption(ThemeMode.DARK, R.string.theme_dark, R.drawable.ic_moon, selected, onSelect, Modifier.weight(1f))
    }
}

@Composable
private fun ThemeOption(
    mode: ThemeMode,
    @StringRes label: Int,
    @DrawableRes icon: Int,
    selected: ThemeMode,
    onSelect: (ThemeMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    val p = LocalPalette.current
    val isSelected = mode == selected
    Column(
        modifier
            .height(68.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(if (isSelected) p.accent else Color.Transparent)
            .selectable(selected = isSelected, role = Role.RadioButton, onClick = { onSelect(mode) }),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically),
    ) {
        Icon(
            painterResource(icon),
            contentDescription = null,
            Modifier.size(24.dp),
            tint = if (isSelected) p.onAccent else p.iconMuted,
        )
        Text(
            stringResource(label),
            color = if (isSelected) p.onAccent else p.text,
            fontSize = 14.sp,
            fontWeight = if (isSelected) FontWeight.ExtraBold else FontWeight.Bold,
        )
    }
}
