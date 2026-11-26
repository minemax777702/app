package com.mok.buylist.ui

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.material3.TextButton
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
    var widgetExpanded by rememberSaveable { mutableStateOf(false) }
    AppModalSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = p.sheet,
        dragHandle = { SheetDragHandle(onDismiss) },
        contentColor = p.text,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, bottom = 20.dp),
        ) {
            NoticeInSheet()
            SheetHeader("Оформление")
            androidx.compose.foundation.layout.Spacer(Modifier.height(12.dp))
            ThemeSegments(appMode, onAppMode)
            // Раздельная настройка виджета сохранена как дополнительная секция.
            TextButton(onClick = { widgetExpanded = !widgetExpanded }) {
                Text(if (widgetExpanded) "Скрыть настройки виджета" else "Тема виджета", color = p.text)
            }
            if (widgetExpanded) {
                SectionLabel(R.string.theme_widget)
                ThemeSegments(widgetMode, onWidgetMode)
            }
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
    Column(Modifier.fillMaxWidth().selectableGroup(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ThemeOption(ThemeMode.AUTO, R.string.theme_auto, R.drawable.ic_theme_auto, selected, onSelect)
        ThemeOption(ThemeMode.LIGHT, R.string.theme_light, R.drawable.ic_sun, selected, onSelect)
        ThemeOption(ThemeMode.DARK, R.string.theme_dark, R.drawable.ic_moon, selected, onSelect)
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
    FreshCutListRow(icon, stringResource(label), if (mode == ThemeMode.AUTO) "Как на устройстве" else "",
        selected = mode == selected, onClick = { onSelect(mode) })
}
