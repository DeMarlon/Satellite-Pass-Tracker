package com.example.eps_sgtracker.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.eps_sgtracker.model.GroundStation

/**
 * Lets the user narrow down which of the currently-active ground stations are actually relevant
 * to one satellite - independent of the satellite's own show/hide-everywhere eye toggle. Only
 * active stations are offered here (a station the user hasn't turned on globally isn't a
 * meaningful choice for a single satellite either).
 */
@Composable
fun SatelliteStationPickerDialog(
    satelliteName: String,
    activeStations: List<GroundStation>,
    initiallySelectedCodes: Set<String>,
    onDismiss: () -> Unit,
    onConfirm: (Set<String>) -> Unit
) {
    var selected by remember(initiallySelectedCodes) { mutableStateOf(initiallySelectedCodes) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Ground stations for $satelliteName") },
        text = {
            if (activeStations.isEmpty()) {
                Text(
                    "No active ground stations. Activate one in the Ground Stations list first.",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                // Scrollable: AlertDialog gives its text slot a bounded height and does not
                // scroll it. The station list is unbounded (users can add any number of custom
                // stations), so past roughly eight - fewer at a large font scale - the bottom
                // checkboxes fell outside the slot with no way to reach them.
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        "Satellite uses the stations checked below.",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    activeStations.forEach { station ->
                        val checked = station.code in selected
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    selected = if (checked) selected - station.code else selected + station.code
                                },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = checked,
                                onCheckedChange = { isChecked ->
                                    selected = if (isChecked) selected + station.code else selected - station.code
                                }
                            )
                            Text("${station.name} (${station.code})", fontSize = 14.sp)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(selected) }) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
