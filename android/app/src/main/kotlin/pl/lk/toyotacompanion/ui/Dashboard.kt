package pl.lk.toyotacompanion.ui

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * The building blocks of the status screen (docs/SPEC.md §10.4): a card with an emoji and a title that groups one subject,
 * a headline number with its unit and caption, and a tile of the menu. One place for spacing, shapes and type, so the
 * screen reads the same everywhere and the colours come from the theme (light, dark and dynamic).
 */
@Composable
fun DashboardCard(title: String, emoji: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(emoji, style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { contentDescription = "" })
                Text(title, style = MaterialTheme.typography.titleMedium)
            }
            content()
        }
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

/** A small rounded label: the trend, a count; [container] says whether it is good news, bad news or neutral. */
@Composable
fun Pill(text: String, container: Color, content: Color) {
    Box(
        modifier = Modifier
            .background(container, RoundedCornerShape(50))
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge, color = content)
    }
}

/** One tile of the menu: a big emoji over a short label, the whole tile is the touch target (at least 88 dp high). */
@Composable
fun MenuTile(emoji: String, label: String, onClick: () -> Unit, modifier: Modifier = Modifier, accent: Color = MaterialTheme.colorScheme.primary) {
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
            Text(emoji, style = MaterialTheme.typography.headlineMedium)
            Text(label, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center, color = accent)
        }
    }
}

/** The menu as rows of two equal tiles; an odd last tile keeps its half width. */
@Composable
fun MenuGrid(tiles: List<Triple<String, String, () -> Unit>>) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        var index = 0
        for (pair in tiles.chunked(2)) {
            // Equal height per row, even when one label wraps to two lines.
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                for ((emoji, label, onClick) in pair) {
                    MenuTile(emoji, label, onClick, Modifier.weight(1f).fillMaxHeight(), Palette.Series[index++ % Palette.Series.size])
                }
                if (pair.size == 1) Box(Modifier.weight(1f))
            }
        }
    }
}
