package app.jabs.torboxdrop.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** TorBox wire values: 1 follows the account setting, 2 always seeds, 3 never seeds. */
@Composable
internal fun TorrentSeedingChooser(
    selected: Int,
    onSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val current = SEEDING_OPTIONS.firstOrNull { it.value == selected } ?: SEEDING_OPTIONS.first()
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Text("Torrent seeding", style = MaterialTheme.typography.titleSmall)
        Text(
            current.description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            SEEDING_OPTIONS.forEach { option ->
                val active = option.value == current.value
                Surface(
                    onClick = { onSelected(option.value) },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp),
                    color = if (active) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surface.copy(alpha = 0.55f)
                    },
                    contentColor = if (active) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    border = BorderStroke(
                        1.dp,
                        if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                    ),
                ) {
                    Text(
                        text = option.label,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 10.dp),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                }
            }
        }
    }
}

private data class SeedingOption(val value: Int, val label: String, val description: String)

private val SEEDING_OPTIONS = listOf(
    SeedingOption(1, "Auto", "Use the seeding preference saved in your TorBox account."),
    SeedingOption(2, "Always", "Seed this torrent after it finishes."),
    SeedingOption(3, "Never", "Do not seed this torrent after it finishes."),
)
