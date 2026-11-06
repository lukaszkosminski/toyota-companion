package pl.lk.toyotacompanion.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Test

/** `surfaceContainerHigh` of the light and the dark scheme in ui/theme/Theme.kt - the card background `readable` uses. */
private val LIGHT_CARD = Color(0xFFF1E6E5)
private val DARK_CARD = Color(0xFF362C2B)

private val PALETTE = mapOf(
    "Green" to Palette.Green, "Amber" to Palette.Amber, "Red" to Palette.Red, "Blue" to Palette.Blue,
    "Teal" to Palette.Teal, "Violet" to Palette.Violet, "Orange" to Palette.Orange, "Pink" to Palette.Pink,
)

/** docs/SPEC.md §10.4: text in a palette colour reaches WCAG AA (4.5:1) on the cards of both themes. */
class ContrastTest {
    @Test
    fun `contrast ratio follows wcag`() {
        assertEquals(21.0, contrastRatio(Color.Black, Color.White), 0.01)
        assertEquals(1.0, contrastRatio(Palette.Blue, Palette.Blue), 0.0001)
        assertEquals(contrastRatio(Color.White, Palette.Red), contrastRatio(Palette.Red, Color.White), 0.0001)
    }

    @Test
    fun `every palette colour is readable on both cards`() {
        for (card in listOf(LIGHT_CARD, DARK_CARD)) {
            for ((name, color) in PALETTE) {
                val text = readableOn(color, card)
                assertTrue(contrastRatio(text, card) >= MIN_TEXT_CONTRAST, "$name on $card: ${contrastRatio(text, card)}")
            }
        }
    }

    @Test
    fun `on a tinted tile the tint counts as background`() {
        for ((name, color) in PALETTE) {
            val tile = color.copy(alpha = 0.12f).compositeOver(LIGHT_CARD)
            assertTrue(contrastRatio(readableOn(color, tile), tile) >= MIN_TEXT_CONTRAST, name)
        }
    }

    @Test
    fun `a readable colour is left alone`() {
        assertEquals(Color.Black, readableOn(Color.Black, LIGHT_CARD))
        assertEquals(Color.White, readableOn(Color.White, DARK_CARD))
        // Amber on the dark card already passes - dark mode keeps the bright palette.
        assertEquals(Palette.Amber, readableOn(Palette.Amber, DARK_CARD))
    }

    @Test
    fun `light cards darken the colour, dark cards lighten it, the hue stays`() {
        val onLight = readableOn(Palette.Amber, LIGHT_CARD)
        assertTrue(onLight.luminance() < Palette.Amber.luminance())
        assertTrue(onLight.red > onLight.blue, "amber stays warm: $onLight")
        val onDark = readableOn(Palette.Blue, DARK_CARD)
        assertTrue(onDark.luminance() > Palette.Blue.luminance())
        assertTrue(onDark.blue > onDark.red, "blue stays blue: $onDark")
    }

    @Test
    fun `cost bar is empty for no money and visible for a little`() {
        val top = BigDecimal("300.00")
        assertEquals(0f, costBarShare(null, top))
        assertEquals(0f, costBarShare(BigDecimal.ZERO, top))
        assertEquals(0f, costBarShare(BigDecimal("0.00"), top))
        assertEquals(0f, costBarShare(BigDecimal("10"), BigDecimal.ZERO))
        assertEquals(0.03f, costBarShare(BigDecimal("1.00"), top))
        assertEquals(0.5f, costBarShare(BigDecimal("150.00"), top))
        assertEquals(1f, costBarShare(top, top))
    }
}
