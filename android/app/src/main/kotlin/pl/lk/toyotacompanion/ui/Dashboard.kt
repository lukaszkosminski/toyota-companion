package pl.lk.toyotacompanion.ui

import androidx.compose.material3.Icon
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.text.TextStyle
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.ButtonDefaults
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * The building blocks of the status screen (docs/SPEC.md §10.4): a card with an icon and a title that groups one subject,
 * a headline number with its unit and caption, and a tile of the menu. One place for spacing, shapes and type, so the
 * screen reads the same everywhere and the colours come from the theme (light, dark and dynamic).
 */
@Composable
fun DashboardCard(title: String, icon: ImageVector, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // Decorative (no description): a screen reader reads the title only.
                Icon(icon, contentDescription = null, modifier = Modifier.size(24.dp), tint = MaterialTheme.colorScheme.primary)
                Text(title, style = MaterialTheme.typography.titleMedium)
            }
            content()
        }
    }
}

/** One label and its value (docs/SPEC.md §45, §47): the label is quiet on the left, the value takes the rest on the right. */
data class LabelValue(val label: String, val value: String)

/** A [LabelValue] as one row: a long value (operations, a message) wraps inside its own column, the label never wraps. */
@Composable
fun LabelValueRow(row: LabelValue) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(row.label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(row.value, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.End, modifier = Modifier.weight(1f))
    }
}

/** `4,13 l/100 km` as one big number with its unit beside it, and a caption under it. */
@Composable
fun Headline(value: String, unit: String, caption: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(value, style = MaterialTheme.typography.displaySmall, color = MaterialTheme.colorScheme.primary)
            Text(unit, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 6.dp))
        }
        Text(caption, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * A small rounded label: the trend, a count; [container] says whether it is good news, bad news or neutral. The text
 * and the optional [icon] take a readable tone of [content] on that container.
 */
@Composable
fun Pill(text: String, container: Color, content: Color, icon: ImageVector? = null) {
    val ink = readable(content, tint = container)
    Row(
        modifier = Modifier
            .background(container, RoundedCornerShape(50))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (icon != null) Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp), tint = ink)
        Text(text, style = MaterialTheme.typography.labelLarge, color = ink)
    }
}

/** One tile of the menu: a big icon over a short label, the whole tile is the touch target (at least 88 dp high). */
@Composable
fun MenuTile(icon: ImageVector, label: String, onClick: () -> Unit, modifier: Modifier = Modifier, accent: Color = MaterialTheme.colorScheme.primary) {
    Card(
        onClick = onClick,
        modifier = modifier.heightIn(min = 88.dp),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = accent.copy(alpha = 0.14f)),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
        ) {
            val ink = readable(accent, tint = accent.copy(alpha = 0.14f))
            Icon(icon, contentDescription = null, modifier = Modifier.size(32.dp), tint = ink)
            Text(label, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center, color = ink)
        }
    }
}

/** The menu as rows of two equal tiles; an odd last tile keeps its half width. */
@Composable
fun MenuGrid(tiles: List<Triple<ImageVector, String, () -> Unit>>) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        var index = 0
        for (pair in tiles.chunked(2)) {
            // Equal height per row, even when one label wraps to two lines.
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                for ((icon, label, onClick) in pair) {
                    MenuTile(icon, label, onClick, Modifier.weight(1f).fillMaxHeight(), Palette.Series[index++ % Palette.Series.size])
                }
                if (pair.size == 1) Box(Modifier.weight(1f))
            }
        }
    }
}

/** A button label with a leading icon, sized and spaced like Material 3 buttons; call it inside the button's content. */
@Composable
fun IconLabel(icon: ImageVector, text: String) {
    Icon(icon, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
    Spacer(Modifier.size(ButtonDefaults.IconSpacing))
    Text(text)
}

/**
 * A line of text with a small decorative icon before it, both in [color] (default: the current content colour). The
 * icon sits at the first line, so a wrapped text keeps it at its start.
 */
@Composable
fun IconText(
    icon: ImageVector,
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyMedium,
    color: Color = Color.Unspecified,
) {
    val ink = if (color.isSpecified) color else LocalContentColor.current
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Icon(icon, contentDescription = null, modifier = Modifier.padding(top = 1.dp).size(18.dp), tint = ink)
        Text(text, style = style, color = ink)
    }
}
