/* This Source Code Form is subject to the terms of the Mozilla Public
   - License, v. 2.0. If a copy of the MPL was not distributed with this
   - file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.theme

import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.interaction.FocusInteraction
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Focus indication for Android TV: D-pad navigation has no pointer, so the focused
 * control must be obvious from across the room.
 *
 * Fenix's Material defaults only show a ripple on press, which leaves a remote user
 * with no idea where focus is. Providing this as `LocalIndication` covers the
 * components that use the default indication.
 */
object TvFocusIndication : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode =
        TvFocusNode(interactionSource)

    override fun equals(other: Any?): Boolean = other === this

    override fun hashCode(): Int = TvFocusIndication::class.hashCode()
}

private class TvFocusNode(
    private val interactionSource: InteractionSource,
) : Modifier.Node(), DrawModifierNode {
    private var focused by mutableStateOf(false)
    private var job: Job? = null

    override fun onAttach() {
        job = coroutineScope.launch {
            interactionSource.interactions.collect { interaction ->
                when (interaction) {
                    is FocusInteraction.Focus -> focused = true
                    is FocusInteraction.Unfocus -> focused = false
                }
            }
        }
    }

    override fun onDetach() {
        job?.cancel()
        job = null
    }

    override fun ContentDrawScope.draw() {
        // Content first, ring on top.
        drawContent()
        if (!focused) return

        val stroke = 3.dp.toPx()
        val inset = stroke / 2f
        drawRoundRect(
            color = TvFocusRingColor,
            topLeft = Offset(inset, inset),
            size = Size(size.width - stroke, size.height - stroke),
            cornerRadius = CornerRadius(8.dp.toPx()),
            style = Stroke(width = stroke),
        )
    }
}

/** High-contrast ring colour that reads on both dark and light surfaces. */
val TvFocusRingColor: Color = Color(0xFF7CC7FF)

/**
 * Draws a high-visibility ring while this element holds D-pad focus.
 *
 * Many Fenix components set `indication = null` (they were designed for touch), so
 * `LocalIndication` cannot reach them. Apply this modifier to any focusable element
 * that must be usable from a remote.
 */
@Composable
fun Modifier.tvFocusRing(
    cornerRadius: Dp = 12.dp,
    strokeWidth: Dp = 3.dp,
): Modifier {
    var focused by remember { mutableStateOf(false) }
    return this
        .onFocusChanged { focused = it.isFocused }
        .drawWithContent {
            drawContent()
            if (!focused) return@drawWithContent
            val stroke = strokeWidth.toPx()
            val inset = stroke / 2f
            drawRoundRect(
                color = TvFocusRingColor,
                topLeft = Offset(inset, inset),
                size = Size(size.width - stroke, size.height - stroke),
                cornerRadius = CornerRadius(cornerRadius.toPx(), cornerRadius.toPx()),
                style = Stroke(width = stroke),
            )
        }
}
