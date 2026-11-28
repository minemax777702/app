package com.mok.buylist.capture

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.TextUnitType
import com.mok.buylist.ui.theme.Manrope
import org.json.JSONArray
import org.json.JSONObject

/** Только uiCapture: измерения реального Compose, без подключения к рабочей базе. */
@OptIn(ExperimentalComposeUiApi::class)
class LayoutCaptureReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val roots = JSONArray()
        val texts = JSONArray()
        fun inspect(view: View) {
            if (view is ViewRootForTest && view.isShown) {
                val location = IntArray(2)
                view.getLocationOnScreen(location)
                val rootIndex = roots.length()
                roots.put(JSONObject().put("x", location[0]).put("y", location[1])
                    .put("width", view.width).put("height", view.height))
                fun visit(node: SemanticsNode, parent: Int?) {
                    val config = node.config
                    if (config.getOrNull(SemanticsProperties.Password) != null) return
                    val bounds = node.boundsInRoot
                    val pos = node.positionInRoot
                    val results = mutableListOf<TextLayoutResult>()
                    config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(results)
                    for (result in results) {
                        if (bounds.width <= 0f || bounds.height <= 0f || result.size.width <= 0) continue
                        val input = result.layoutInput
                        val style = input.style
                        val value = input.text.text
                        if (value.isBlank() || !pos.x.isFinite() || !pos.y.isFinite()) continue
                        fun px(unit: TextUnit, base: Float = 0f): Float? = when (unit.type) {
                            TextUnitType.Sp -> with(input.density) { unit.toPx() }
                            TextUnitType.Em -> unit.value * base
                            else -> null
                        }?.takeIf { it.isFinite() }
                        val font = px(style.fontSize) ?: continue
                        val lines = JSONArray()
                        for (i in 0 until result.lineCount) {
                            lines.put(JSONObject()
                                .put("start", result.getLineStart(i))
                                .put("end", result.getLineEnd(i, visibleEnd = true))
                                .put("left", result.getLineLeft(i))
                                .put("right", result.getLineRight(i))
                                .put("top", result.getLineTop(i))
                                .put("bottom", result.getLineBottom(i))
                                .put("baseline", result.getLineBaseline(i))
                                .put("ellipsized", result.isLineEllipsized(i)))
                        }
                        texts.put(JSONObject().put("root", rootIndex).put("id", node.id)
                            .put("parent", parent ?: JSONObject.NULL).put("text", value)
                            .put("x", location[0]+pos.x).put("y", location[1]+pos.y)
                            .put("width", result.size.width).put("height", result.size.height)
                            .put("clip", JSONArray(listOf(location[0]+bounds.left, location[1]+bounds.top,
                                location[0]+bounds.right, location[1]+bounds.bottom)))
                            .put("fontPx", font).put("weight", style.fontWeight?.weight ?: 400)
                            .put("family", if (style.fontFamily == Manrope) "Manrope" else "unresolved")
                            .put("fontStyle", style.fontStyle?.toString() ?: "Normal")
                            .put("align", style.textAlign.toString())
                            .put("lineHeightPx", px(style.lineHeight, font) ?: JSONObject.NULL)
                            .put("letterSpacingPx", px(style.letterSpacing, font) ?: JSONObject.NULL)
                            .put("colorArgb", if (style.color != Color.Unspecified) style.color.toArgb().toLong() and 0xffffffffL else JSONObject.NULL)
                            .put("lines", lines))
                    }
                    node.children.forEach { visit(it, node.id) }
                }
                visit(view.semanticsOwner.unmergedRootSemanticsNode, null)
            }
            if (view is ViewGroup) for (i in 0 until view.childCount) inspect(view.getChildAt(i))
        }
        WindowInspector.getGlobalWindowViews().forEach(::inspect)
        val result = JSONObject().put("schema", 1).put("density", context.resources.displayMetrics.density.toDouble())
            .put("fontScale", context.resources.configuration.fontScale.toDouble()).put("roots", roots).put("texts", texts)
        context.openFileOutput("compose-layout.json", Context.MODE_PRIVATE).use {
            it.write(result.toString().toByteArray(Charsets.UTF_8))
        }
        resultCode = android.app.Activity.RESULT_OK
    }
}
