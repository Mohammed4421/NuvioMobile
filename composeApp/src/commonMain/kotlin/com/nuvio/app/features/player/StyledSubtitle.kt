package com.nuvio.app.features.player

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun StyledSubtitleOverlay(text: String, style: SubtitleStyleState, modifier: Modifier = Modifier) {
    val fontFamily = rememberSubtitleFontFamily(style.appearance.fontId)
    if (text.isBlank()) return
    BoxWithConstraints(modifier.fillMaxSize()) {
        val scale = (maxHeight / 360.dp).coerceAtMost(1f)
        Box(
            Modifier.fillMaxSize().padding(
                horizontal = 16.dp * scale,
                vertical = maxHeight * (0.0533f + style.bottomOffset.coerceIn(0, SUBTITLE_BOTTOM_OFFSET_MAX) / 1000f),
            ),
            contentAlignment = Alignment.BottomCenter,
        ) {
            StyledSubtitleText(text, style, scale = scale, fontFamily = fontFamily)
        }
    }
}

@Composable
internal fun StyledSubtitleText(
    text: String,
    style: SubtitleStyleState,
    modifier: Modifier = Modifier,
    scale: Float = 1f,
    fontFamily: FontFamily = rememberSubtitleFontFamily(style.appearance.fontId),
) {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    BoxWithConstraints(modifier) {
        val paddingX = with(density) { (8.dp * scale).toPx() }
        val paddingY = with(density) { (4.dp * scale).toPx() }
        val maxTextWidth = (constraints.maxWidth - paddingX * 2).toInt().coerceAtLeast(1)
        val layout = measurer.measure(
            text = AnnotatedString(withRightToLeftLineMarks(text)),
            style = TextStyle(
                fontFamily = fontFamily,
                fontSize = (style.fontSizeSp * scale).sp,
                fontWeight = if (style.bold) FontWeight.Bold else FontWeight.Normal,
                textAlign = TextAlign.Center,
                textDirection = TextDirection.ContentOrLtr,
            ),
            constraints = Constraints(maxWidth = maxTextWidth),
        )
        val textHeight = with(density) { (layout.size.height + paddingY * 2).toDp() }
        val radius = with(density) { (style.appearance.backgroundRadius.dp * scale).toPx() }
        val outline = with(density) { (normalizeSubtitleOutlineWidth(style.outlineWidth).dp * scale).toPx() }
        val shadowSize = with(density) { (style.appearance.shadowSize.dp * scale).toPx() }
        val brightness = style.brightnessFactor
        Canvas(
            Modifier.fillMaxWidth().height(textHeight).semantics { this.text = AnnotatedString(text) },
        ) {
            val boxWidth = layout.size.width + paddingX * 2
            val left = (size.width - boxWidth) / 2f
            val origin = Offset(left + paddingX, paddingY)
            drawRoundRect(
                color = style.backgroundColor.dimmedBy(brightness),
                topLeft = Offset(left, 0f),
                size = androidx.compose.ui.geometry.Size(boxWidth, size.height),
                cornerRadius = CornerRadius(radius),
            )
            if (style.outlineEnabled && outline > 0) {
                drawText(
                    layout, color = style.outlineColor.copy(alpha = style.outlineColor.alpha * style.textColor.alpha).dimmedBy(brightness),
                    topLeft = origin, drawStyle = Stroke(outline * 2), shadow = Shadow.None,
                )
            }
            drawText(
                layout, color = style.textColor.dimmedBy(brightness), topLeft = origin, drawStyle = Fill,
                shadow = if (style.appearance.shadowEnabled) Shadow(
                    color = Color.Black.copy(alpha = style.appearance.shadowOpacity / 100f * style.textColor.alpha),
                    offset = Offset(shadowSize, shadowSize),
                    blurRadius = shadowSize * 2,
                ) else Shadow.None,
            )
        }
    }
}

/**
 * Each line with Arabic or Hebrew letters starts right to left, even when its first word is Latin
 * ("Netflix تقدم…."): the first strong letter decides a line's direction, so such a line was laid out
 * left to right and its end punctuation moved (independent review GEM-SUB-01). Lines without them
 * (English in a two-language subtitle) keep their own direction, and so do lines that already carry
 * direction marks (ExoPlayer's cues, set by PlayerSubtitleRtlFix): only libmpv's plain text changes.
 */
internal fun withRightToLeftLineMarks(text: String): String =
    text.split('\n').joinToString("\n") { line ->
        if (line.any { it.isRightToLeftLetter() } && line.none { it.isDirectionMark() }) "\u200F$line" else line
    }

private fun Char.isDirectionMark(): Boolean = this in '\u200E'..'\u200F' || this in '\u202A'..'\u202E' || this in '\u2066'..'\u2069'

private fun Char.isRightToLeftLetter(): Boolean =
    this in '\u0590'..'\u08FF' || this in '\uFB1D'..'\uFDFF' || this in '\uFE70'..'\uFEFF'
