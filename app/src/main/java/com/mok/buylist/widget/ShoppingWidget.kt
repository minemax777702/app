package com.mok.buylist.widget

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.mok.buylist.R
import com.mok.buylist.data.ShoppingItem
import com.mok.buylist.repository
import com.mok.buylist.themeSettings
import com.mok.buylist.ui.MainActivity
import com.mok.buylist.ui.theme.DarkPalette
import com.mok.buylist.ui.theme.LightPalette
import com.mok.buylist.ui.theme.ShoppingPalette
import com.mok.buylist.ui.theme.ThemeMode

class ShoppingWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val app = context.applicationContext as com.mok.buylist.ShoppingApp
        val themeSettings = context.themeSettings
        provideContent {
            val workspace by app.cloud.workspace.collectAsState()
            val repository = workspace?.repository ?: app.localRepository
            val mode by themeSettings.widget.collectAsState()
            androidx.compose.runtime.key(workspace?.namespace) {
                val items by repository.observeItems().collectAsState(emptyList())
                WidgetContent(items, mode, workspace?.namespace.orEmpty())
            }
        }
    }
}

private val ItemIdKey = ActionParameters.Key<String>("item_id")
private val WorkspaceKey = ActionParameters.Key<String>("workspace")

/** Цвет из палитры дизайна: «Авто» следует теме телефона, «Светлая»/«Тёмная» фиксируют цвет. */
private fun color(mode: ThemeMode, pick: (ShoppingPalette) -> Color): ColorProvider = when (mode) {
    ThemeMode.AUTO -> androidx.glance.color.ColorProvider(day = pick(LightPalette), night = pick(DarkPalette))
    ThemeMode.LIGHT -> ColorProvider(pick(LightPalette))
    ThemeMode.DARK -> ColorProvider(pick(DarkPalette))
}

@Composable
private fun WidgetContent(items: List<ShoppingItem>, mode: ThemeMode, namespace: String) {
    val context = LocalContext.current
    val openApp = actionStartActivity(Intent(context, MainActivity::class.java))
    Column(
        GlanceModifier
            .fillMaxSize()
            .background(color(mode) { it.widgetBg })
            .appWidgetBackground()
            .cornerRadius(28.dp)
            .padding(start = 8.dp, end = 8.dp, top = 12.dp, bottom = 8.dp),
    ) {
        Row(
            GlanceModifier
                .fillMaxWidth()
                .clickable(openApp)
                .padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                GlanceModifier.size(32.dp).background(color(mode) { it.accent }).cornerRadius(10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    provider = ImageProvider(R.drawable.ic_list_check),
                    contentDescription = null,
                    modifier = GlanceModifier.size(20.dp),
                    colorFilter = ColorFilter.tint(color(mode) { it.onAccent }),
                )
            }
            Spacer(GlanceModifier.width(10.dp))
            Text(
                text = context.getString(R.string.app_name),
                modifier = GlanceModifier.defaultWeight(),
                maxLines = 1,
                style = TextStyle(
                    color = color(mode) { it.text },
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                ),
            )
            if (items.isNotEmpty()) {
                // Фиксированный квадрат с радиусом в половину стороны — ровный круг при любом числе.
                Box(
                    GlanceModifier
                        .size(40.dp)
                        .background(color(mode) { it.boughtBg })
                        .cornerRadius(20.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = items.size.toString(),
                        maxLines = 1,
                        style = TextStyle(
                            color = color(mode) { it.chipText },
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center,
                        ),
                    )
                }
            }
        }

        if (items.isEmpty()) {
            Column(
                GlanceModifier.fillMaxSize().padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    GlanceModifier.size(64.dp).background(color(mode) { it.boughtBg }).cornerRadius(32.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Image(
                        provider = ImageProvider(R.drawable.ic_check),
                        contentDescription = null,
                        modifier = GlanceModifier.size(32.dp),
                        colorFilter = ColorFilter.tint(color(mode) { it.accent }),
                    )
                }
                Spacer(GlanceModifier.height(10.dp))
                Text(
                    text = context.getString(R.string.widget_empty),
                    style = TextStyle(
                        color = color(mode) { it.text },
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                    ),
                )
                Spacer(GlanceModifier.height(4.dp))
                Text(
                    text = context.getString(R.string.widget_empty_hint),
                    style = TextStyle(
                        color = color(mode) { it.textSecondary },
                        fontSize = 14.sp,
                        textAlign = TextAlign.Center,
                    ),
                )
            }
        } else {
            LazyColumn(GlanceModifier.fillMaxSize().padding(top = 4.dp)) {
                items(items, itemId = { it.id.hashCode().toLong() }) { item ->
                    Row(
                        GlanceModifier
                            .fillMaxWidth()
                            .clickable(
                                actionRunCallback<BuyItemAction>(actionParametersOf(ItemIdKey to item.id, WorkspaceKey to namespace)),
                            )
                            .padding(horizontal = 10.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Image(
                            provider = ImageProvider(R.drawable.ic_radio_unchecked),
                            contentDescription = null,
                            modifier = GlanceModifier.size(22.dp),
                            colorFilter = ColorFilter.tint(color(mode) { it.ring }),
                        )
                        Spacer(GlanceModifier.width(14.dp))
                        Text(
                            text = item.name + (item.weightGrams?.let { " · ${java.math.BigDecimal.valueOf(it.toLong(), 3).stripTrailingZeros().toPlainString()} кг" } ?: ""),
                            maxLines = 1,
                            style = TextStyle(
                                color = color(mode) { it.text },
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Medium,
                            ),
                        )
                    }
                }
            }
        }
    }
}

class BuyItemAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val id = parameters[ItemIdKey] ?: return
        val app = context.applicationContext as com.mok.buylist.ShoppingApp
        val workspace = app.cloud.workspace.value
        if (parameters[WorkspaceKey] != workspace?.namespace.orEmpty()) return
        val repository = workspace?.repository ?: app.localRepository
        repository.buy(id)
        repository.finishTripIfDone()
    }
}
