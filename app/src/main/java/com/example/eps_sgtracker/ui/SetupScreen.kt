package com.example.eps_sgtracker.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Copyright
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.SettingsInputAntenna
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.graphics.SolidColor
import com.example.eps_sgtracker.BuildConfig
import com.example.eps_sgtracker.data.CLOUD_LAYER_FEATURE_ENABLED
import com.example.eps_sgtracker.data.MAX_FORECAST_DAYS
import com.example.eps_sgtracker.data.MAX_MIN_PASS_ELEVATION_DEG
import com.example.eps_sgtracker.data.MIN_FORECAST_DAYS
import com.example.eps_sgtracker.data.MIN_MIN_PASS_ELEVATION_DEG
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.ui.text.input.KeyboardType
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs
import kotlin.math.roundToInt

// The cloud-layer feature is experimental and currently hidden from the UI. The toggle's visibility
// and the layer's actual rendering are deliberately the same switch - see CLOUD_LAYER_FEATURE_ENABLED
// for why hiding only the control is not enough to disable the feature.
private const val SHOW_CLOUD_LAYER_TOGGLE = CLOUD_LAYER_FEATURE_ENABLED

// Where the GPL's source requirement is actually discharged for a Play user. Someone who installed
// the binary from the store has no other route to the code, so this link is not decoration.
private const val SOURCE_URL = "https://github.com/DeMarlon/Satellite-Pass-Tracker"

// Identifies which entity a currently-open color picker dialog is editing - a single dialog
// instance is reused for both stations and satellites rather than duplicating the dialog wiring.
private sealed class ColorPickerTarget {
    data class Station(val code: String, val currentColor: Color) : ColorPickerTarget()
    data class Satellite(val noradId: Int, val currentColor: Color) : ColorPickerTarget()
    data class Theme(val currentColor: Color) : ColorPickerTarget()
}

// A delete action awaiting user confirmation - [label] names the entity in the confirmation
// prompt, [onConfirm] is the actual removal to run if the user confirms. Deletion is permanent
// (unlike the eye toggle), so both satellite and custom-station trash icons route through this
// instead of deleting immediately on tap.
private class PendingDelete(val label: String, val onConfirm: () -> Unit)

// Amber for the "orbital data is getting old" advisory - deliberately not colorScheme.error, which
// is reserved here for outright failure ("Never", "Failed to fetch"). A warn-level TLE still gives
// usable predictions; it just deserves a second look before anyone points hardware at the sky.
private val STALE_EPOCH_WARN_COLOR = Color(0xFFFFB74D)

// Same "yyyy-MM-dd HH:mm:ss", UTC-or-local convention as the aggregate "Most Recent Fetch" text
// below, so a satellite's own timestamp and the fleet-wide one are always directly comparable.
// Wall-clock only, deliberately: the halt window is half an hour, so a date adds nothing, and a
// fixed time avoids a per-second countdown that would recompose this whole screen at 1 Hz.
private fun formatClockTime(millis: Long, useUtc: Boolean): String {
    val formatter = SimpleDateFormat("HH:mm", Locale.US)
    formatter.timeZone = if (useUtc) TimeZone.getTimeZone("UTC") else TimeZone.getDefault()
    return formatter.format(Date(millis))
}

private fun formatOmmTimestamp(millis: Long?, useUtc: Boolean): String {
    if (millis == null || millis == 0L) return "Never"
    val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
    formatter.timeZone = if (useUtc) TimeZone.getTimeZone("UTC") else TimeZone.getDefault()
    return formatter.format(Date(millis))
}

@Composable
fun SetupScreen(viewModel: TrackerViewModel) {
    // Input capture states
    var noradInput by remember { mutableStateOf("") }
    var customName by remember { mutableStateOf("") }
    var customCode by remember { mutableStateOf("") }
    var customLat by remember { mutableStateOf("") }
    var customLon by remember { mutableStateOf("") }
    var customFormError by remember { mutableStateOf<String?>(null) }
    var colorPickerTarget by remember { mutableStateOf<ColorPickerTarget?>(null) }
    // Which satellite's ground-station relevance picker is currently open, if any.
    var stationPickerNoradId by remember { mutableStateOf<Int?>(null) }
    var pendingDelete by remember { mutableStateOf<PendingDelete?>(null) }
    var showInfoDialog by remember { mutableStateOf(false) }
    var showAboutDialog by remember { mutableStateOf(false) }
    val context = LocalContext.current

    // Collect orbital-data (OMM) synchronization state flows from the viewmodel
    val isUpdatingTles by viewModel.isUpdatingTles.collectAsStateWithLifecycle()
    val lastUpdatedText by viewModel.lastUpdatedText.collectAsStateWithLifecycle()
    val refreshHalt by viewModel.refreshHalt.collectAsStateWithLifecycle()
    val celestrakUnreachable by viewModel.celestrakUnreachable.collectAsStateWithLifecycle()
    val satelliteTleTimestamps by viewModel.satelliteTleTimestamps.collectAsStateWithLifecycle()
    val satelliteTleEpochs by viewModel.satelliteTleEpochs.collectAsStateWithLifecycle()
    val stationColorOverrides by viewModel.stationColorOverrides.collectAsStateWithLifecycle()
    val satelliteColorOverrides by viewModel.satelliteColorOverrides.collectAsStateWithLifecycle()
    // Hoisted here (rather than collected only inside their respective cards) since the
    // Satellites card's per-satellite station picker needs all of this too, and both the
    // Satellites card's per-satellite OMM timestamp and the Display Options card need
    // useUtcTime.
    val allAvailableStations by viewModel.availableStations.collectAsStateWithLifecycle()
    val activeCodes by viewModel.activeStationCodes.collectAsStateWithLifecycle()
    val satelliteNames by viewModel.satelliteNames.collectAsStateWithLifecycle()
    val useUtcTime by viewModel.useUtcTime.collectAsStateWithLifecycle()

    val trackedSats by viewModel.trackedSatellites.collectAsStateWithLifecycle()
    // Drag-to-reorder state. Hoisted above the LazyColumn (rather than living inside the Satellites
    // card's item{}) specifically so userScrollEnabled below can see draggingId: without that, the
    // list scrolls under the finger mid-drag and the row is chasing a moving target.
    var dragOrder by remember(trackedSats) { mutableStateOf(trackedSats.map { it.noradId }) }
    var draggingId by remember { mutableStateOf<Int?>(null) }
    var dragOffsetY by remember { mutableFloatStateOf(0f) }
    // The index the drag started from. dragOrder itself is deliberately NOT mutated while a drag is
    // in flight: reordering the forEach mid-drag rebuilds that row's layout node, which cancels the
    // pointerInput coroutine driving the gesture - one swap and the drag would die with draggingId
    // still set (row frozen mid-list, page unscrollable). Instead the order is frozen, rows are only
    // shifted visually, and the single real reorder is committed on release.
    var dragStartIndex by remember { mutableIntStateOf(-1) }
    // Rows are not uniform height (only satellites carry the OMM-timestamp footer, and names wrap
    // differently), so the swap threshold has to come from real measurements rather than an
    // assumed row height.
    val rowHeights = remember { mutableStateMapOf<Int, Int>() }
    val rowSpacingPx = with(LocalDensity.current) { 8.dp.toPx() }

    // Where the held row would land if released right now: walked out from dragStartIndex, consuming
    // each neighbour's measured height until the remaining travel no longer clears that neighbour's
    // midpoint. Derived from scratch on each call rather than accumulated, so it can't drift out of
    // sync with dragOffsetY. A function, not a remembered value, because the gesture below has to
    // evaluate it at release time - pointerInput's block doesn't restart while its key is unchanged,
    // so it would capture a stale snapshot of a val.
    fun computeDragTargetIndex(): Int {
        if (draggingId == null || dragStartIndex < 0) return -1
        var target = dragStartIndex
        var remaining = dragOffsetY
        if (remaining > 0f) {
            while (target + 1 <= dragOrder.lastIndex) {
                val span = (rowHeights[dragOrder[target + 1]] ?: 0) + rowSpacingPx
                if (remaining < span / 2f) break
                remaining -= span
                target++
            }
        } else {
            while (target - 1 >= 0) {
                val span = (rowHeights[dragOrder[target - 1]] ?: 0) + rowSpacingPx
                if (-remaining < span / 2f) break
                remaining += span
                target--
            }
        }
        return target
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
        // Locked while a row is held, so the page can't scroll out from under the drag.
        userScrollEnabled = draggingId == null
    ) {
        item {
            Box(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "Setup",
                    fontSize = 32.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.align(Alignment.Center)
                )
                // Mirrors the info button opposite it: same size, tint and popup mechanics, so
                // the two header affordances read as a pair rather than as two unrelated controls.
                IconButton(
                    onClick = { showAboutDialog = true },
                    modifier = Modifier.align(Alignment.CenterStart)
                ) {
                    Icon(
                        imageVector = Icons.Default.Copyright,
                        contentDescription = "About this app",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
                IconButton(
                    onClick = { showInfoDialog = true },
                    modifier = Modifier.align(Alignment.CenterEnd)
                ) {
                    Icon(
                        imageVector = Icons.Default.Info,
                        contentDescription = "How to use this app",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }

        // --- SATELLITES REGISTRATION SECTION ---
        item {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text(
                        text = "SATELLITES",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.primary,
                        letterSpacing = 1.sp,
                        fontWeight = FontWeight.Bold
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = noradInput,
                            onValueChange = { noradInput = it },
                            label = { Text("NORAD ID") },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            // Catalogue numbers are positive integers - the ADD button parses with
                            // toIntOrNull() - so a digits-only pad costs nothing and saves hunting
                            // for the number row. Deliberately NOT applied to Latitude/Longitude:
                            // KeyboardType.Number is digits-only on many IMEs, which would make
                            // McMurdo's -77.8390 impossible to type.
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                        )
                        Button(
                            onClick = {
                                noradInput.toIntOrNull()?.let { viewModel.addSatellite(it) }
                                noradInput = ""
                            }
                        ) {
                            Text("+ ADD")
                        }
                    }

                    // Distinguishes "still loading" from "the fetch tried and gave up" (bad/typo'd
                    // NORAD ID, decayed satellite, etc.) - both used to look identical.
                    val fetchFailedIds by viewModel.satelliteFetchFailed.collectAsStateWithLifecycle()
                    val hiddenSatelliteIds by viewModel.hiddenSatelliteIds.collectAsStateWithLifecycle()

                    val satellitesById = trackedSats.associateBy { it.noradId }
                    val orbitOffsets by viewModel.orbitOffsets.collectAsStateWithLifecycle()
                    val orbitOffsetDrafts = remember { mutableStateMapOf<Int, String>() }

                    val dragTargetIndex = computeDragTargetIndex()
                    val draggedSpanPx = ((rowHeights[draggingId] ?: 0) + rowSpacingPx)

                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        dragOrder.mapNotNull { satellitesById[it] }.forEachIndexed { rowIndex, sat ->
                            val resolvedName = satelliteNames[sat.noradId]
                            val fetchFailed = resolvedName == null && sat.noradId in fetchFailedIds
                            // Same flag, opposite side: the ID DID resolve at some point, but the
                            // most recent refresh did not succeed. Previously unreachable as a UI
                            // state, because the automatic path reported a failed refresh as a
                            // success and simply served the stale cache.
                            val refreshFailed = resolvedName != null && sat.noradId in fetchFailedIds
                            // "check NORAD ID" is only honest advice when CelesTrak actually
                            // answered. During an outage the ID is very likely fine and the app has
                            // simply never been able to ask, so saying otherwise sends the user off
                            // to re-verify a correct number.
                            val displayName = resolvedName ?: when {
                                fetchFailed && celestrakUnreachable != null -> "CelesTrak unreachable - will retry"
                                fetchFailed -> "Failed to fetch - check NORAD ID"
                                else -> "Fetching name..."
                            }
                            val satColor = getSatelliteColor(sat.noradId, satelliteColorOverrides)

                            val ommTimestampText = formatOmmTimestamp(satelliteTleTimestamps[sat.noradId], useUtcTime)
                            // Age of the TLE's own EPOCH, which is NOT the same as the fetch time
                            // shown above. A catalogue entry that stops being updated upstream is
                            // re-fetched successfully every cycle, so "Last OMM update at:" keeps
                            // reading as current while the orbital elements underneath go stale.
                            val epochAgeDays = satelliteTleEpochs[sat.noradId]?.let { epochMillis ->
                                ((System.currentTimeMillis() - epochMillis) / 86_400_000L)
                                    .coerceAtLeast(0L)
                            }
                            // Draft kept separate from the persisted value so a half-typed entry
                            // ("-", or momentarily empty) isn't stomped by the flow re-emitting.
                            val orbitOffsetText = orbitOffsetDrafts[sat.noradId]
                                ?: (orbitOffsets[sat.noradId] ?: 0).toString()

                            val isDragging = draggingId == sat.noradId
                            // The held row tracks the finger; the rows it has passed slide one slot
                            // the other way to open up the gap it would drop into.
                            val rowShiftPx = when {
                                isDragging -> dragOffsetY
                                dragTargetIndex < 0 -> 0f
                                rowIndex in (dragStartIndex + 1)..dragTargetIndex -> -draggedSpanPx
                                rowIndex in dragTargetIndex until dragStartIndex -> draggedSpanPx
                                else -> 0f
                            }

                            EntityRow(
                                modifier = Modifier
                                    .onSizeChanged { rowHeights[sat.noradId] = it.height }
                                    // The lifted row has to paint above its neighbours, otherwise it
                                    // slides *under* them as it crosses.
                                    .zIndex(if (isDragging) 1f else 0f)
                                    .offset { IntOffset(0, rowShiftPx.roundToInt()) }
                                    .shadow(
                                        elevation = if (isDragging) 8.dp else 0.dp,
                                        shape = MaterialTheme.shapes.small
                                    )
                                    .alpha(if (isDragging) 0.9f else 1f),
                                dragHandle = {
                                    // The gesture lives on a 40dp box, not on the glyph itself: a
                                    // 20dp target is well under the 48dp minimum and was easy to
                                    // miss, and every miss landed on the LazyColumn instead and
                                    // read as an ordinary scroll.
                                    Box(
                                        contentAlignment = Alignment.Center,
                                        modifier = Modifier
                                            .size(40.dp)
                                            .pointerInput(sat.noradId) {
                                                // Hand-rolled instead of
                                                // detectDragGesturesAfterLongPress: that variant only
                                                // claims the pointer once its ~500ms hold has
                                                // completed, so moving a finger any earlier left the
                                                // events unconsumed and the enclosing LazyColumn
                                                // scrolled the page instead - which is exactly what
                                                // made the drag feel unrecognised. Consuming the down
                                                // event here hands the grip ownership of the gesture
                                                // from first touch. Safe precisely because this 40dp
                                                // box exists only to drag: no other interaction is
                                                // being taken away from it.
                                                awaitEachGesture {
                                                    val down = awaitFirstDown(requireUnconsumed = false)
                                                    down.consume()
                                                    try {
                                                        dragStartIndex = dragOrder.indexOf(sat.noradId)
                                                        if (dragStartIndex < 0) return@awaitEachGesture
                                                        draggingId = sat.noradId
                                                        dragOffsetY = 0f
                                                        var moved = false
                                                        while (true) {
                                                            val event = awaitPointerEvent()
                                                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                                            if (!change.pressed) break
                                                            // Read the delta BEFORE consuming:
                                                            // positionChange() reports Offset.Zero
                                                            // once the change is consumed, so
                                                            // consuming first pins the drag at zero.
                                                            val dy = change.positionChange().y
                                                            change.consume()
                                                            if (dy != 0f) moved = true
                                                            dragOffsetY += dy
                                                        }
                                                        // The one and only mutation of the order, on
                                                        // release. Also the only persist: satellites
                                                        // feed the combine that retriggers
                                                        // recomputePasses(), so writing per drag frame
                                                        // would fire an SGP4 recompute per frame. A
                                                        // tap that never moved skips both.
                                                        val target = computeDragTargetIndex()
                                                        if (moved && target >= 0 && target != dragStartIndex) {
                                                            val reordered = dragOrder.toMutableList().apply {
                                                                add(target, removeAt(dragStartIndex))
                                                            }
                                                            dragOrder = reordered
                                                            viewModel.setSatelliteOrder(reordered)
                                                        }
                                                    } finally {
                                                        // In a finally so a cancelled gesture (the row
                                                        // being recomposed away mid-drag, the screen
                                                        // being left) can never strand draggingId set,
                                                        // which would freeze the row and leave the
                                                        // page permanently unscrollable.
                                                        draggingId = null
                                                        dragOffsetY = 0f
                                                        dragStartIndex = -1
                                                    }
                                                }
                                            }
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.DragHandle,
                                            contentDescription = "Drag to reorder",
                                            tint = if (isDragging) {
                                                MaterialTheme.colorScheme.primary
                                            } else {
                                                MaterialTheme.colorScheme.onSurfaceVariant
                                            },
                                            modifier = Modifier.size(24.dp)
                                        )
                                    }
                                },
                                title = displayName,
                                titleColor = if (fetchFailed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                                subtitle = "NORAD ID: ${sat.noradId}",
                                swatchColor = satColor,
                                onColorClick = { colorPickerTarget = ColorPickerTarget.Satellite(sat.noradId, satColor) },
                                isVisible = sat.noradId !in hiddenSatelliteIds,
                                onToggleVisibility = { viewModel.toggleSatelliteVisibility(sat.noradId) },
                                onDelete = {
                                    // Not `displayName` - that can be a status placeholder like
                                    // "Fetching name..." which would read strangely in the prompt.
                                    val label = resolvedName ?: "NORAD ${sat.noradId}"
                                    pendingDelete = PendingDelete(label) { viewModel.removeSatellite(sat.noradId) }
                                },
                                extraAction = {
                                    IconButton(onClick = { stationPickerNoradId = sat.noradId }) {
                                        Icon(
                                            imageVector = Icons.Default.SettingsInputAntenna,
                                            contentDescription = "Choose ground stations for this satellite",
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                },
                                // Per-satellite counterpart to the "Most Recent Fetch (any
                                // satellite)" aggregate below - more precise for spotting an OMM
                                // update problem affecting just this one satellite.
                                footer = {
                                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                            Text(
                                                text = "Last OMM update at:",
                                                fontSize = 10.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                            Text(
                                                text = ommTimestampText,
                                                fontSize = 10.sp,
                                                fontWeight = FontWeight.Medium,
                                                color = if (ommTimestampText == "Never") {
                                                    MaterialTheme.colorScheme.error
                                                } else {
                                                    MaterialTheme.colorScheme.onSurfaceVariant
                                                }
                                            )
                                        }
                                        // The last refresh attempt failed while an older element
                                        // set is still cached, so the satellite keeps tracking and
                                        // the row above shows when it WAS last updated. Needs its
                                        // own line because `fetchFailed` above is deliberately
                                        // gated on an unresolved name - it means "this NORAD ID
                                        // never resolved", which is a configuration problem with a
                                        // different fix. This one is transient: network, or
                                        // CelesTrak declining the request.
                                        if (refreshFailed) {
                                            Text(
                                                text = if (celestrakUnreachable != null) {
                                                    "CelesTrak unreachable - showing last known data"
                                                } else {
                                                    "Update failed - showing last known data"
                                                },
                                                fontSize = 10.sp,
                                                fontWeight = FontWeight.Medium,
                                                color = MaterialTheme.colorScheme.error
                                            )
                                        }
                                        // Surfaced only once it matters, so a healthy satellite
                                        // stays uncluttered. Advisory, never blocking: a stale
                                        // prediction still beats none, and only the operator knows
                                        // whether this target is worth pointing an antenna at.
                                        if (epochAgeDays != null && epochAgeDays >= TLE_EPOCH_WARN_DAYS) {
                                            val isVeryStale = epochAgeDays >= TLE_EPOCH_STALE_DAYS
                                            Text(
                                                text = if (isVeryStale) {
                                                    "Orbital data ${epochAgeDays}d old - pass times may be well off"
                                                } else {
                                                    "Orbital data ${epochAgeDays}d old"
                                                },
                                                fontSize = 10.sp,
                                                fontWeight = FontWeight.Medium,
                                                color = if (isVeryStale) {
                                                    MaterialTheme.colorScheme.error
                                                } else {
                                                    STALE_EPOCH_WARN_COLOR
                                                }
                                            )
                                        }
                                        // Last-resort alignment with an operator's own numbering:
                                        // revolution counts are computed from the ascending node,
                                        // but a ground segment counting from a different reference
                                        // can still sit a constant apart, which no TLE-derived
                                        // formula can recover.
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            Text(
                                                text = "Rev # offset:",
                                                fontSize = 10.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                            BasicTextField(
                                                value = orbitOffsetText,
                                                onValueChange = { text ->
                                                    orbitOffsetDrafts[sat.noradId] = text
                                                    // "-" alone is a legitimate intermediate state
                                                    // while typing a negative value, so it's kept in
                                                    // the draft without being committed.
                                                    val parsed = text.trim().let {
                                                        if (it.isEmpty() || it == "-") 0 else it.toIntOrNull()
                                                    }
                                                    if (parsed != null) viewModel.setOrbitOffset(sat.noradId, parsed)
                                                },
                                                singleLine = true,
                                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                                textStyle = LocalTextStyle.current.copy(
                                                    fontSize = 10.sp,
                                                    color = MaterialTheme.colorScheme.onSurface
                                                ),
                                                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                                                modifier = Modifier
                                                    .width(44.dp)
                                                    .background(
                                                        MaterialTheme.colorScheme.surface.copy(alpha = 0.6f),
                                                        MaterialTheme.shapes.extraSmall
                                                    )
                                                    .padding(horizontal = 6.dp, vertical = 3.dp)
                                            )
                                        }
                                    }
                                }
                            )
                        }

                        if (trackedSats.isEmpty()) {
                            Text(
                                text = "No satellites registered yet.",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }

        // --- ORBITAL DATA CONFIGURATION CARD ---
        item {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Text(
                        text = "ORBITAL DATA MANAGEMENT",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.primary,
                        letterSpacing = 1.sp,
                        fontWeight = FontWeight.Bold
                    )

                    Text(
                        text = "Orbit Mean-Elements Messages (OMM) from celestrak.org cache locally & refresh dynamically when records expire (48h threshold).",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        lineHeight = 16.sp
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = "Most Recent Fetch (any satellite):",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = lastUpdatedText,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            color = if (lastUpdatedText.contains("Never", ignoreCase = true)) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.primary
                            }
                        )
                    }

                    // CelesTrak answered with a non-200 and querying is paused. Reported rather
                    // than left as a bare "Update failed" on every satellite, because that reads as
                    // "try again" - and tapping again is exactly what accumulates HTTP errors
                    // toward the 50-in-2-hours threshold that gets an IP firewalled. Saying what
                    // happened and when it resolves is what stops the retry loop being human.
                    // takeIf guards against showing a deadline that has already passed: the halt
                    // itself is cleared lazily, on the next fetch attempt, so the state can outlive
                    // its own window if nothing has tried to query since.
                    refreshHalt?.takeIf { it.retryAtMillis > System.currentTimeMillis() }?.let { halt ->
                        Text(
                            text = "CelesTrak returned HTTP ${halt.httpCode}. Querying is paused " +
                                "until ${formatClockTime(halt.retryAtMillis, useUtcTime)} so this " +
                                "device isn't blocked - cached orbital data is still in use.",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.error
                        )
                    }

                    // The other half of the story the halt banner above tells. Deliberately says
                    // nothing about querying being paused, because it is not: no response was
                    // received, so nothing counted against CelesTrak's error budget and retrying
                    // the moment the connection returns is exactly the right thing to do. It also
                    // names both possible causes rather than guessing between them - telling which
                    // end is offline would need a network-state permission this app does not want.
                    celestrakUnreachable?.let { unreachable ->
                        Text(
                            text = "Could not reach celestrak.org \u2014 your device or " +
                                "celestrak.org might be offline. Last tried at " +
                                "${formatClockTime(unreachable.lastAttemptMillis, useUtcTime)} - " +
                                "cached orbital data is still in use.",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.error
                        )
                    }

                    Button(
                        onClick = { viewModel.manualRefreshAllSelectedTles() },
                        enabled = !isUpdatingTles,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (isUpdatingTles) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.5.dp,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Synchronizing Ephemeris...")
                        } else {
                            Text("Force OMM Update (all)")
                        }
                    }
                }
            }
        }

        // --- GROUND STATIONS ---
        // Every known station (predefined + custom) is always listed here, same philosophy as
        // the satellites list above: "tracked" and "currently active/visible" are independent,
        // so deactivating a station (closing its eye) never makes it harder to find again - no
        // separate "add existing station back" menu needed, unlike the old monitored/unmonitored
        // split-list-plus-dropdown design.
        item {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text(
                        text = "GROUND STATIONS",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.secondary,
                        letterSpacing = 1.sp,
                        fontWeight = FontWeight.Bold
                    )

                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        allAvailableStations.forEach { station ->
                            val stationColor = getStationColor(station.code, stationColorOverrides)
                            EntityRow(
                                title = "${station.name} | ${station.code}",
                                titleColor = MaterialTheme.colorScheme.onSurface,
                                subtitle = "Lat: ${String.format(Locale.US, "%.4f", station.latitude)}° • Lon: ${String.format(Locale.US, "%.4f", station.longitude)}°",
                                swatchColor = stationColor,
                                onColorClick = { colorPickerTarget = ColorPickerTarget.Station(station.code, stationColor) },
                                isVisible = station.code in activeCodes,
                                onToggleVisibility = { viewModel.toggleGroundStation(station.code) },
                                // Predefined stations (SVL, MCM, FUC, LAR) are permanent and have
                                // no delete path - only a custom station can be removed outright.
                                onDelete = if (station.isCustom) {
                                    {
                                        pendingDelete = PendingDelete(station.name) {
                                            viewModel.removeCustomGroundStation(station.code)
                                        }
                                    }
                                } else null
                            )
                        }

                        if (allAvailableStations.isEmpty()) {
                            Text(
                                text = "No ground stations available.",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }

        // --- CUSTOM GROUND STATIONS FORM ---
        item {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text(
                        text = "ADD CUSTOM GROUND STATION",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        letterSpacing = 1.sp,
                        fontWeight = FontWeight.Bold
                    )

                    OutlinedTextField(
                        value = customName,
                        onValueChange = { customName = it },
                        label = { Text("Station Name") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )

                    OutlinedTextField(
                        value = customCode,
                        onValueChange = { customCode = it },
                        label = { Text("Station Code (e.g., ALPHA)") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = customLat,
                            onValueChange = { customLat = it },
                            label = { Text("Latitude") },
                            modifier = Modifier.weight(1f),
                            singleLine = true
                        )
                        OutlinedTextField(
                            value = customLon,
                            onValueChange = { customLon = it },
                            label = { Text("Longitude") },
                            modifier = Modifier.weight(1f),
                            singleLine = true
                        )
                    }

                    if (customFormError != null) {
                        Text(
                            text = customFormError!!,
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.error
                        )
                    }

                    Button(
                        onClick = {
                            val parsedLat = customLat.toDoubleOrNull()
                            val parsedLon = customLon.toDoubleOrNull()
                            customFormError = when {
                                customName.isBlank() -> "Station name can't be empty."
                                customCode.isBlank() -> "Station code can't be empty."
                                // GroundStationRepository serializes as name|code|lat|lon and
                                // splits on '|', so a pipe in either free-text field shifts every
                                // later field. The row then fails to parse and is silently dropped
                                // on read - after the dialog has already reported success - and it
                                // is undeletable too, because removal matches on what is now the
                                // wrong field. Reject it here rather than corrupt the store.
                                customName.contains('|') ->
                                    "Station name can't contain the | character."
                                customCode.contains('|') ->
                                    "Station code can't contain the | character."
                                parsedLat == null || parsedLat < -90.0 || parsedLat > 90.0 ->
                                    "Latitude must be a number between -90 and 90."
                                parsedLon == null || parsedLon < -180.0 || parsedLon > 180.0 ->
                                    "Longitude must be a number between -180 and 180."
                                else -> null
                            }
                            if (customFormError != null) return@Button

                            val saved = viewModel.addCustomGroundStation(customName, customCode, parsedLat!!, parsedLon!!)
                            if (!saved) {
                                customFormError = "Code \"${customCode.trim().uppercase()}\" is already in use by another station."
                                return@Button
                            }

                            customName = ""
                            customCode = ""
                            customLat = ""
                            customLon = ""
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("SAVE STATION")
                    }
                }
            }
        }

        // --- System Settings ---
        item {
            val cloudLayerEnabled by viewModel.cloudLayerEnabled.collectAsStateWithLifecycle()
            val passLosGraceMinutes by viewModel.passLosGraceMinutes.collectAsStateWithLifecycle()
            var graceMinutesText by remember(passLosGraceMinutes) { mutableStateOf(passLosGraceMinutes.toString()) }
            val forecastDays by viewModel.forecastDays.collectAsStateWithLifecycle()
            var forecastDaysText by remember(forecastDays) { mutableStateOf(forecastDays.toString()) }
            val minPassElevationDeg by viewModel.minPassElevationDeg.collectAsStateWithLifecycle()
            var minElevationText by remember(minPassElevationDeg) { mutableStateOf(minPassElevationDeg.toString()) }
            // The live accent, whether it came from the user's override or the shipped default -
            // so opening the picker always starts from the color actually on screen.
            val currentThemeColor = MaterialTheme.colorScheme.primary

            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "System Settings",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        letterSpacing = 1.sp,
                        fontWeight = FontWeight.Bold
                    )

                    // Affects every timestamp in the app: Pass List AOS/LOS and the Last Sync
                    // Retrieval text below - both otherwise render in the phone's local time zone.
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { viewModel.setUseUtcTime(!useUtcTime) },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = useUtcTime,
                            onCheckedChange = { viewModel.setUseUtcTime(it) }
                        )
                        Text(
                            text = "Display times in UTC",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }

                    // How long a completed pass stays in Pass List after LOS before dropping off
                    // the top - 0 is valid and means "drop immediately at LOS".
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = "TRACK timeout after LOS (minutes)",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = graceMinutesText,
                            onValueChange = { text ->
                                graceMinutesText = text
                                text.trim().toIntOrNull()?.let { minutes ->
                                    if (minutes >= 0) viewModel.setPassLosGraceMinutes(minutes)
                                }
                            },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.width(80.dp)
                        )
                    }

                    // The peak elevation a pass must reach to be listed at all - applies to TRACK,
                    // PLAN and the 3D view's pass lines alike, since all three read the same
                    // computed lists.
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = "Minimum pass elevation (degrees)",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = minElevationText,
                            onValueChange = { text ->
                                minElevationText = text
                                text.trim().toIntOrNull()?.let { degrees ->
                                    if (degrees in MIN_MIN_PASS_ELEVATION_DEG..MAX_MIN_PASS_ELEVATION_DEG) {
                                        viewModel.setMinPassElevationDeg(degrees)
                                    }
                                }
                            },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.width(80.dp)
                        )
                    }
                    Text(
                        text = "$MIN_MIN_PASS_ELEVATION_DEG-$MAX_MIN_PASS_ELEVATION_DEG degrees. Passes that never " +
                                "rise this high are discarded.",
                        fontSize = 11.sp,
                        lineHeight = 14.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    // How far ahead the Plan screen forecasts.
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = "PLAN forecast range (days)",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = forecastDaysText,
                            onValueChange = { text ->
                                forecastDaysText = text
                                text.trim().toIntOrNull()?.let { days ->
                                    if (days in MIN_FORECAST_DAYS..MAX_FORECAST_DAYS) {
                                        viewModel.setForecastDays(days)
                                    }
                                }
                            },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.width(80.dp)
                        )
                    }
                    Text(
                        text = "$MIN_FORECAST_DAYS-$MAX_FORECAST_DAYS days. Predictions lose accuracy the further " +
                                "ahead they reach.",
                        fontSize = 11.sp,
                        lineHeight = 14.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )


                    // The app-wide accent. Same tappable swatch affordance as the satellite and
                    // ground-station rows, so "tap a circle to recolor it" means one thing
                    // everywhere.
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = "Theme color",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f)
                        )
                        Box(
                            modifier = Modifier
                                .size(20.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary)
                                .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f), CircleShape)
                                .clickable {
                                    colorPickerTarget = ColorPickerTarget.Theme(currentThemeColor)
                                }
                        )
                    }



                    if (SHOW_CLOUD_LAYER_TOGGLE) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { viewModel.setCloudLayerEnabled(!cloudLayerEnabled) },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = cloudLayerEnabled,
                                onCheckedChange = { viewModel.setCloudLayerEnabled(it) }
                            )
                            Text(
                                text = "Show cloud layer",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }

                        Text(
                            text = "Experimental. Cloud data is fetched once a day from NASA satellite imagery and is approximate - it may not cover the entire globe.",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            lineHeight = 16.sp
                        )
                    }
                }
            }
        }
    }

    val target = colorPickerTarget
    if (target != null) {
        ColorPickerDialog(
            initialColor = when (target) {
                is ColorPickerTarget.Station -> target.currentColor
                is ColorPickerTarget.Satellite -> target.currentColor
                is ColorPickerTarget.Theme -> target.currentColor
            },
            onDismiss = { colorPickerTarget = null },
            onColorSelected = { color ->
                when (target) {
                    is ColorPickerTarget.Station -> viewModel.setStationColor(target.code, color)
                    is ColorPickerTarget.Satellite -> viewModel.setSatelliteColor(target.noradId, color)
                    is ColorPickerTarget.Theme -> viewModel.setThemeColor(color)
                }
                colorPickerTarget = null
            },
            onReset = {
                when (target) {
                    is ColorPickerTarget.Station -> viewModel.clearStationColor(target.code)
                    is ColorPickerTarget.Satellite -> viewModel.clearSatelliteColor(target.noradId)
                    is ColorPickerTarget.Theme -> viewModel.clearThemeColor()
                }
                colorPickerTarget = null
            }
        )
    }

    val pickerNoradId = stationPickerNoradId
    if (pickerNoradId != null) {
        SatelliteStationPickerDialog(
            satelliteName = satelliteNames[pickerNoradId] ?: "NORAD $pickerNoradId",
            activeStations = allAvailableStations.filter { it.code in activeCodes },
            initiallySelectedCodes = viewModel.effectiveAllowedStations(pickerNoradId, activeCodes),
            onDismiss = { stationPickerNoradId = null },
            onConfirm = { codes ->
                viewModel.setAllowedStationsForSatellite(pickerNoradId, codes)
                stationPickerNoradId = null
            }
        )
    }

    val delete = pendingDelete
    if (delete != null) {
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Remove ${delete.label}?") },
            text = { Text("Are you sure you want to permanently remove ${delete.label}?") },
            confirmButton = {
                TextButton(onClick = {
                    delete.onConfirm()
                    pendingDelete = null
                }) { Text("Remove") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("Cancel") }
            }
        )
    }

    if (showInfoDialog) {
        AlertDialog(
            onDismissRequest = { showInfoDialog = false },
            title = { Text("How to use this app") },
            text = {
                Column(
                    modifier = Modifier
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val tips = listOf(
                        "Add a satellite by entering its NORAD catalog ID and tapping + ADD.",
                        "Activate the ground stations you want passes tracked from via the station's eye icon. You can also add custom ground stations.",
                        "Tap the antenna icon next to a satellite to choose which of your active ground stations are used to track it.",
                        "Tap a color swatch to pick a custom color for satellites or stations.",
                        "Each satellite's entry shows its own last OMM update time.",
                        "The calculated revolution number might be off for some satellites compared to other tools. You can add a manual offset for the displayed number if required.",
                        "Reorder the satellite list by dragging the grip handle at the left of each entry - purely for grouping/organization.",
                        "PLAN shows a long-range table of upcoming passes, amount of days defined in system settings.",
                        "TRACK shows upcoming passes within the next 10 hours with live countdowns - LEO passes are sorted by time at the top, GEO passes are listed at the bottom.",
                        "Press and hold a pass in TRACK to set a reminder notification ahead of AOS.",
                        "Open 3D VIEW for a live view showing your satellites and ground stations - drag with one finger to rotate, pinch to zoom, move two fingers to pan, and twist with two fingers to roll the view.",
                        "In 3D VIEW, the bottom-right buttons snap the view north-up or reset it; the bottom-left button allows setup of orbit lines (on/off and how far into the past/future they extend) and map details.",
                        "The 3D VIEW's top-right button toggles fullscreen mode, preventing display timeout",
                        "Enable \"Display times in UTC\" below if you'd rather see UTC than your phone's local time.",
                        "Adjust how long a completed pass lingers in TRACK after LOS with the TRACK timeout setting below. 0 removes it immediately at LOS.",
                        "The trash icon permanently deletes a satellite or custom ground station, with a confirmation prompt first."
                    )
                    tips.forEach { tip ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("•", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(tip, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 18.sp)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showInfoDialog = false }) { Text("Got it") }
            }
        )
    }

    if (showAboutDialog) {
        AlertDialog(
            onDismissRequest = { showAboutDialog = false },
            title = { Text("About") },
            text = {
                Column(
                    modifier = Modifier
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "SPT - Satellite Pass Tracker",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "Version ${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE})",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "Copyright \u00a9 2026 Marlon Deutsch",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Text(
                        text = "This program is free software: you can redistribute it and/or " +
                            "modify it under the terms of the GNU General Public License version " +
                            "3, as published by the Free Software Foundation.",
                        fontSize = 13.sp,
                        lineHeight = 18.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "It is distributed in the hope that it will be useful, but WITHOUT " +
                            "ANY WARRANTY; without even the implied warranty of MERCHANTABILITY " +
                            "or FITNESS FOR A PARTICULAR PURPOSE.",
                        fontSize = 13.sp,
                        lineHeight = 18.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    // The full licence text and every third-party attribution live in the repo;
                    // this is the only path there from a Play install.
                    TextButton(
                        onClick = {
                            try {
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(SOURCE_URL)))
                            } catch (e: ActivityNotFoundException) {
                                // No browser installed. The URL is spelled out below anyway, so
                                // there is nothing to recover and nothing worth crashing over.
                                e.printStackTrace()
                            }
                        },
                        contentPadding = PaddingValues(0.dp)
                    ) {
                        Text("Source code, licence and credits on GitHub", fontSize = 13.sp)
                    }
                    Text(
                        text = SOURCE_URL,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Text(
                        text = "CREDITS",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.primary,
                        letterSpacing = 1.sp,
                        fontWeight = FontWeight.Bold
                    )
                    val credits = listOf(
                        "Pass prediction by predict4java (GPL-2.0-or-later) - D. A. B. Johnson " +
                            "G4DPZ, from KD2BD's PREDICT and T. S. Kelso's SGP4/SDP4 models.",
                        "Orbital data (OMM) from CelesTrak, celestrak.org - please consider " +
                            "donating to them rather than to this app.",
                        "Map vectors from Natural Earth (public domain).",
                        "Earth textures: NASA Blue Marble Next Generation and Black Marble.",
                        "Cloud imagery from NASA GIBS (MODIS Terra).",
                        "OkHttp and AndroidX Jetpack Compose (Apache-2.0)."
                    )
                    credits.forEach { credit ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("\u2022", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(credit, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 18.sp)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showAboutDialog = false }) { Text("Close") }
            }
        )
    }
}

// Shared row layout for both tracked satellites and ground stations: a tappable color swatch,
// name/subtitle, an eye/eye-off visibility toggle, and an optional permanent-delete action (null
// when the entity can't be deleted - predefined ground stations). Having one shared component is
// what actually makes the two lists consistent, rather than two near-identical implementations
// that could drift apart later.
@Composable
private fun EntityRow(
    title: String,
    titleColor: Color,
    subtitle: String,
    swatchColor: Color,
    onColorClick: () -> Unit,
    isVisible: Boolean,
    onToggleVisibility: () -> Unit,
    onDelete: (() -> Unit)?,
    // Entity-specific action slotted in before the eye icon - currently only satellites use this,
    // for the per-satellite ground-station relevance picker; ground stations pass null.
    extraAction: (@Composable () -> Unit)? = null,
    // Optional third row below subtitle+buttons - currently only satellites use this, for the
    // per-satellite "Last OMM update" line; ground stations pass null.
    footer: (@Composable () -> Unit)? = null,
    // Leading grip affordance, rendered before the color swatch - currently only satellites use
    // this, for drag-to-reorder; ground stations pass null and are unaffected.
    dragHandle: (@Composable () -> Unit)? = null,
    // Callers supply the drag offset / measurement / z-order modifiers through this.
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f), shape = MaterialTheme.shapes.small)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        // Row 1: swatch + name, alone on the full row width - the name never has to compete with
        // the action buttons for space, so it only truncates when genuinely too long for the
        // *entire* row (previously it shared a row with three icon buttons, cutting off names
        // like "METOP-SGA1" much earlier than necessary).
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .alpha(if (isVisible) 1f else 0.5f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            dragHandle?.invoke()
            Box(
                modifier = Modifier
                    .size(20.dp)
                    .clip(CircleShape)
                    .background(swatchColor)
                    .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f), CircleShape)
                    .clickable(onClick = onColorClick)
            )
            Text(
                title,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = titleColor,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
        }

        // Row 2: secondary detail text + the action buttons.
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            // weight(fill = false): take up to the space left over after the buttons, but no
            // more - without this, an unconstrained Text here would push the buttons off the row
            // instead of truncating (same fix as the countdown/name-overflow issues elsewhere).
            Text(
                subtitle,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f, fill = false)
                    .alpha(if (isVisible) 1f else 0.5f)
            )

            Row(verticalAlignment = Alignment.CenterVertically) {
                extraAction?.invoke()
                IconButton(onClick = onToggleVisibility) {
                    Icon(
                        imageVector = if (isVisible) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                        contentDescription = if (isVisible) "Hide" else "Show",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (onDelete != null) {
                    IconButton(onClick = onDelete) {
                        Icon(
                            imageVector = Icons.Default.DeleteForever,
                            contentDescription = "Delete permanently",
                            tint = MaterialTheme.colorScheme.error.copy(alpha = 0.8f)
                        )
                    }
                }
            }
        }

        // Row 3 (optional): per-satellite OMM update timestamp.
        if (footer != null) {
            Box(modifier = Modifier.alpha(if (isVisible) 1f else 0.5f)) {
                footer()
            }
        }
    }
}
