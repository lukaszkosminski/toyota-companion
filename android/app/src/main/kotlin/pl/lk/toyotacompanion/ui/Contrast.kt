package pl.lk.toyotacompanion.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance

/** WCAG AA for normal text (docs/SPEC.md §10.4). */
const val MIN_TEXT_CONTRAST = 4.5

/** WCAG 2 contrast ratio of two opaque colours, 1..21. */
fun contrastRatio(a: Color, b: Color): Double {
    val la = a.luminance().toDouble()
    val lb = b.luminance().toDouble()
    return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
}

/**
 * [color] as text on [background]: unchanged when it already reaches [min]:1, otherwise the same hue mixed towards
 * black (light background) or white (dark background) in small steps until it does. The palette keeps its meaning
 * (amber stays amber-brown), only the tone moves. A translucent [color] is composited over [background] first.
 */
fun readableOn(color: Color, background: Color, min: Double = MIN_TEXT_CONTRAST): Color {
    val solid = if (color.alpha < 1f) color.compositeOver(background) else color
    if (contrastRatio(solid, background) >= min) return solid
    val target = if (background.luminance() > 0.18f) Color.Black else Color.White
    for (step in 1..STEPS) {
        val mixed = lerp(solid, target, step / STEPS.toFloat())
        if (contrastRatio(mixed, background) >= min) return mixed
    }
    return target
}

private const val STEPS = 40

/**
 * [color] readable as text on the cards of the current theme. The background is `surfaceContainerHigh` - the least
 * favourable card background in both themes (darkest light one, lightest dark one) - with [tint] on top when the text
 * sits on a tinted tile or pill.
 */
@Composable
fun readable(color: Color, tint: Color? = null): Color {
    val card = MaterialTheme.colorScheme.surfaceContainerHigh
    val background = tint?.compositeOver(card) ?: card
    return remember(color, background) { readableOn(color, background) }
}
