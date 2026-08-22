package com.example.eps_sgtracker.ui

import android.Manifest
import android.content.Intent
import android.provider.Settings
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.net.toUri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.eps_sgtracker.notifications.ReminderScheduler
import com.example.eps_sgtracker.model.SatellitePass
import com.example.eps_sgtracker.model.hasExpired
import com.example.eps_sgtracker.model.passInstanceKey
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import androidx.compose.ui.graphics.Color
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PassListScreen(viewModel: TrackerViewModel = viewModel()) {
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()
    val passReminders by viewModel.passReminders.collectAsStateWithLifecycle()

    // Held as State objects rather than unwrapped with `by`, so the derivedStateOf below can read
    // them from inside its own calculation and track them itself - see activePasses.
    val passListState = viewModel.calculatedPasses.collectAsStateWithLifecycle()
    val tick = viewModel.countdownTicker.collectAsStateWithLifecycle()
    // The pass a long-press opened the reminder dialog for, if any.
    var reminderTarget by remember { mutableStateOf<SatellitePass?>(null) }

    // Shown after a reminder is set while only inexact alarms are permitted - see the onSet handler.
    var showExactAlarmPrompt by remember { mutableStateOf(false) }

    val context = LocalContext.current
    // Holds the reminder action while the POST_NOTIFICATIONS request is in flight, so it can run
    // once the system dialog resolves. The action runs regardless of the outcome: the alarm
    // itself never needs the permission - denying it only means the OS won't SHOW the eventual
    // notification, which is the user's explicit choice to make.
    var pendingReminderAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ ->
        pendingReminderAction?.invoke()
        pendingReminderAction = null
    }

    fun withNotificationPermission(action: () -> Unit) {
        val needsRequest = Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        if (needsRequest) {
            pendingReminderAction = action
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            action()
        }
    }

    // Completed passes linger for this many minutes after LOS before dropping off - the same
    // user-configurable value recomputePasses() uses for its own purge, so the second-by-second
    // filtering here and the periodic list rebuild there always agree on the cutoff.
    val graceState = viewModel.passLosGraceMinutes.collectAsStateWithLifecycle()

    // derivedStateOf, not remember(currentMillis): the ticker fires at 1Hz but this list's contents
    // change only when a pass expires or a different pass becomes "next" - a few times an hour. A
    // remember keyed on the tick allocated a fresh List every second, and every reader (the
    // LazyColumn and every visible card) recomposed with it. derivedStateOf still re-evaluates each
    // second, which is a cheap O(n) filter over a handful of passes, but only notifies readers when
    // the RESULT differs; SatellitePass is a data class, so that comparison is structural and the
    // list is equal ~59 seconds out of 60. The live countdown is unaffected - it ticks in its own
    // leaf composable (see CountdownText), which is the only thing that needs per-second updates.
    //
    // The "next upcoming" lookup is also hoisted out of the per-pass mapping, where it previously
    // re-scanned the whole list for every element - O(n^2) on every single tick.
    val activePasses by remember {
        derivedStateOf {
            val now = tick.value
            val passes = passListState.value
            val graceMillis = graceState.value * 60_000L
            val nextAosMillis = passes.firstOrNull { it.aosMillis > now }?.aosMillis
            passes
                .filterNot { it.hasExpired(now, graceMillis) }
                .map { it.copy(isNext = it.aosMillis == nextAosMillis) }
        }
    }

    Column(modifier = Modifier
        .fillMaxSize()
        .background(MaterialTheme.colorScheme.background)
        .padding(16.dp)
    ) {
        Text(
            "Satellite Pass Tracker",
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(16.dp))
        Text("Pass Timeline: UPCOMING LEO PASSES (10h) | GEO PASSES AT BOTTOM", fontSize = 11.sp, letterSpacing = 1.sp, color = MaterialTheme.colorScheme.primary)
        Spacer(modifier = Modifier.height(8.dp))

        when {
            isLoading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            activePasses.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No active tracks. Configure targets in the Setup tab.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            else -> {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    // Keyed rather than positional: LazyColumn otherwise reuses a row slot by
                    // index, so per-row state (the detail drawer's expanded flag, the countdown's
                    // auto-shrunk font size) could be inherited by whichever pass happens to land
                    // in that position after the list shifts. The key also scopes each row's
                    // rememberSaveable state, so an expanded drawer survives being scrolled off
                    // screen and back.
                    //
                    // Deliberately NOT reminderKeyFor(): that identifies a pass across recomputes,
                    // which is what a reminder needs, but it isn't guaranteed unique inside a
                    // single list - and LazyColumn hard-fails on a duplicate key. AOS is unique per
                    // satellite/station here by construction. The trade is that a TLE refresh
                    // collapses an open drawer, which is cosmetic and rare.
                    items(
                        activePasses,
                        key = { passInstanceKey(it) }
                    ) { pass ->
                        PassRow(
                            pass = pass,
                            viewModel = viewModel,
                            reminderSet = passReminders.containsKey(viewModel.reminderKeyFor(pass)),
                            onLongPress = {
                                // A reminder only makes sense ahead of AOS - GEO entries have no
                                // real AOS event, and an ongoing/passed pass has nothing left to
                                // announce. Read here rather than during composition: this lambda
                                // runs on the input event, so it sees the current tick without
                                // subscribing this scope to it.
                                if (!pass.isGeo && pass.aosMillis > tick.value) {
                                    reminderTarget = pass
                                }
                            }
                        )
                    }
                }
            }
        }
    }

    val target = reminderTarget
    if (target != null) {
        val useUtcTime by viewModel.useUtcTime.collectAsStateWithLifecycle()
        PassReminderDialog(
            pass = target,
            existingLeadMinutes = passReminders[viewModel.reminderKeyFor(target)]?.leadMinutes,
            useUtcTime = useUtcTime,
            onDismiss = { reminderTarget = null },
            onSet = { leadMinutes ->
                withNotificationPermission { viewModel.setPassReminder(target, leadMinutes) }
                reminderTarget = null
                // The reminder is scheduled either way - ReminderScheduler falls back to an
                // inexact alarm. But that fallback is Doze-rate-limited to roughly one firing per
                // 9-15 minute window, which for a pass lasting minutes can mean the notification
                // arrives after the pass is over. On API 33+ this permission is not granted by
                // default, so without this prompt most new users would silently get the degraded
                // behaviour with nothing indicating it.
                if (!ReminderScheduler.canScheduleExact(context)) showExactAlarmPrompt = true
            },
            onRemove = {
                viewModel.removePassReminder(target)
                reminderTarget = null
            }
        )
    }

    if (showExactAlarmPrompt) {
        AlertDialog(
            onDismissRequest = { showExactAlarmPrompt = false },
            title = { Text("Reminder may be late") },
            text = {
                Text(
                    "Your reminder is set, but Android is limiting this app to approximate alarms, " +
                        "which can arrive several minutes late - possibly after the pass has ended.\n\n" +
                        "Allowing exact alarms lets reminders fire at the time you chose."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showExactAlarmPrompt = false
                    if (Build.VERSION.SDK_INT >= 31) {
                        context.startActivity(
                            Intent(
                                Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                                "package:${context.packageName}".toUri()
                            )
                        )
                    }
                }) { Text("Open settings") }
            },
            dismissButton = {
                TextButton(onClick = { showExactAlarmPrompt = false }) { Text("Not now") }
            }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PassRow(
    pass: SatellitePass,
    viewModel: TrackerViewModel,
    reminderSet: Boolean,
    onLongPress: () -> Unit
) {
    val useUtcTime by viewModel.useUtcTime.collectAsStateWithLifecycle()
    val uiFormatter = remember(useUtcTime) {
        SimpleDateFormat("HH:mm:ss", Locale.getDefault()).apply {
            timeZone = if (useUtcTime) TimeZone.getTimeZone("UTC") else TimeZone.getDefault()
        }
    }
    val formattedAos = remember(pass.aosMillis, uiFormatter) { uiFormatter.format(Date(pass.aosMillis)) }
    val formattedLos = remember(pass.losMillis, uiFormatter) { uiFormatter.format(Date(pass.losMillis)) }

    // Both of these are derived from the 1Hz ticker but each flips at most twice over a whole
    // pass's lifetime, so they go through derivedStateOf: this row then recomposes when one
    // actually changes rather than on every tick. Reading the ticker directly here (as this row
    // used to) put the entire card - every Text, Surface, border and the whole time grid - inside a
    // scope that invalidated once a second. The countdown, which genuinely does change every
    // second, is isolated in CountdownText so its updates don't drag the rest of the card along.
    val tick = viewModel.countdownTicker.collectAsStateWithLifecycle()
    val isOngoing by remember(pass) { derivedStateOf { viewModel.isPassOngoing(pass, tick.value) } }
    // Equivalent to the old `getCountdownString(...) == "Passed"` string match, without depending
    // on that function's exact wording: it returns "Passed" precisely when LOS is in the past.
    val isPassed by remember(pass) { derivedStateOf { pass.losMillis <= tick.value } }

    val stationColorOverrides by viewModel.stationColorOverrides.collectAsStateWithLifecycle()
    val satelliteColorOverrides by viewModel.satelliteColorOverrides.collectAsStateWithLifecycle()

    // Scoped to this row's LazyColumn key (see the items(...) call), so an open drawer survives
    // being scrolled off screen and back rather than collapsing.
    var expanded by rememberSaveable { mutableStateOf(false) }
    // GEO entries have no horizon-to-horizon arc to plot and no real AOS/LOS to measure a duration
    // between - the card already renders both as "N/A" - so they stay non-expandable, matching how
    // they're already excluded from the reminder long-press.
    val canExpand = !pass.isGeo

    Card(
        modifier = Modifier
            .fillMaxWidth()
            // Clipped to the card's own shape so the press ripple stays inside the rounded corners.
            // combinedClickable disambiguates the two gestures itself: a tap toggles the detail
            // drawer, press-and-hold still opens the reminder dialog.
            .clip(RoundedCornerShape(12.dp))
            .combinedClickable(
                onClick = { if (canExpand) expanded = !expanded },
                onLongClick = onLongPress
            )
            .border(
                width = 1.dp,
                color = if (pass.isNext && !isPassed) MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                else MaterialTheme.colorScheme.outline.copy(alpha = 0.15f),
                shape = RoundedCornerShape(12.dp)
            ),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
        )
    ) {
        Column(
            // animateContentSize rather than wrapping the drawer in AnimatedVisibility: the latter
            // always emits a layout node even when hidden, which this Column's spacedBy would then
            // pad around, leaving a dead gap at the card's bottom edge while collapsed.
            modifier = Modifier
                .padding(16.dp)
                .animateContentSize(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // 1. Top Identity Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    // weight(fill = false): take up to the space left over after the status dot,
                    // but no more - without this, neither sibling in this SpaceBetween Row is
                    // width-constrained, so a long satellite name (some catalog/debris entries run
                    // much longer than "METOP-SGA1") just overflowed straight past the card edge,
                    // pushing or overlapping the status dot instead of making room for it.
                    modifier = Modifier.weight(1f, fill = false),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Satellite name pill badge - same swatch color as the satellite's marker/
                    // trajectory elsewhere in the app (Setup list, 3D view), same "colored pill"
                    // pattern as the ground-station badge right next to it.
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = getSatelliteColor(pass.noradId, satelliteColorOverrides),
                        modifier = Modifier.weight(1f, fill = false)
                    ) {
                        Text(
                            text = pass.satelliteName,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                        )
                    }

                    // Ground Station pill badge
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = getStationColor(pass.groundStationCode, stationColorOverrides),
                        modifier = Modifier.padding(horizontal = 2.dp)
                    ) {
                        Text(
                            text = pass.groundStationCode,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }

                    if (reminderSet) {
                        Icon(
                            imageVector = Icons.Default.NotificationsActive,
                            contentDescription = "Reminder set",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }

                // Active Status Indicator Dot
                if (pass.isNext && !isPassed || pass.isGeo) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(
                                if (isOngoing) MaterialTheme.colorScheme.error
                                else MaterialTheme.colorScheme.secondary
                            )
                    )
                }
            }

            // 2. Secondary Meta Information
            // Rev # is omitted for GEO: CelesTrak's REV_AT_EPOCH is unreliable for geostationary
            // satellites (METEOSAT-9 publishes 746 against a true count near 7600), so showing a
            // number there would be confidently wrong rather than merely approximate.
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (pass.isGeo) {
                        "NORAD ID: ${pass.noradId}"
                    } else {
                        "NORAD ID: ${pass.noradId}  •  Rev #${pass.orbitNumber}"
                    },
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.weight(1f)
                )

                // Nothing else on the card signals that it can be tapped, so the chevron carries
                // the whole affordance - it rotates to point up while the drawer is open.
                if (canExpand) {
                    val chevronRotation by animateFloatAsState(
                        targetValue = if (expanded) 180f else 0f,
                        label = "passDetailChevron"
                    )
                    Icon(
                        imageVector = Icons.Default.ExpandMore,
                        contentDescription = if (expanded) "Hide pass detail" else "Show pass detail",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        modifier = Modifier
                            .size(18.dp)
                            .rotate(chevronRotation)
                    )
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.1f))

            // 3. Time Details Grid (Headers locked directly above values)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // --- AOS Column ---
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.weight(1f) // Gives equal spacing weight
                ) {
                    Text(
                        text = "AOS",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = if (pass.isGeo) "N/A" else formattedAos,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium,
                        color = if (pass.isGeo) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f) else MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Center
                    )
                }

// --- LOS Column ---
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        text = "LOS",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = if (pass.isGeo) "N/A" else formattedLos,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium,
                        color = if (pass.isGeo) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f) else MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Center
                    )
                }

// Countdown Block
                Column(
                    modifier = Modifier.weight(1.2f),
                    horizontalAlignment = Alignment.End
                ) {
                    Text("COUNTDOWN", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(modifier = Modifier.height(2.dp))
                    CountdownText(pass = pass, viewModel = viewModel)
                }
            }

            // 4. Detail drawer - opens beneath the pass, inside the same card.
            if (expanded && canExpand) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.1f))
                PassDetailDrawer(pass = pass, viewModel = viewModel)
            }
        }
    }
}

/**
 * The live countdown, deliberately its own composable rather than inline in [PassRow].
 *
 * This is the only part of a pass card whose content genuinely changes every second, and every
 * `@Composable` call is its own recomposition scope - so collecting the 1Hz ticker here confines
 * the per-second invalidation to this one Text. Reading it one level up (as this row used to) put
 * the whole card in that scope, recomposing every label, pill, border and divider once a second per
 * visible row for the sake of eight characters of text.
 */
@Composable
private fun CountdownText(pass: SatellitePass, viewModel: TrackerViewModel) {
    val currentMillis by viewModel.countdownTicker.collectAsStateWithLifecycle()

    val countdownDisplay = if (pass.isGeo) "ONGOING" else viewModel.getCountdownString(pass, currentMillis)
    val isOngoing = viewModel.isPassOngoing(pass, currentMillis)
    val isPassed = pass.losMillis <= currentMillis

    // The active-LEO-pass variant ("LOS HH:MM:SS") is long enough to wrap onto a second line on
    // narrower phones or with a larger system font-scale (accessibility) setting - that silently
    // grew this card taller than its siblings and threw off the row's alignment. Rather than
    // hand-tune a fixed font size that only happens to fit on whichever phone this was tested on,
    // shrink it just enough to always stay on one line, on any device. Keyed on the text's LENGTH,
    // so the shrink settles once per format rather than re-running on every tick.
    var countdownFontSize by remember(countdownDisplay.length) { mutableStateOf(14.sp) }
    Text(
        text = countdownDisplay,
        fontSize = countdownFontSize,
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Bold,
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Clip,
        textAlign = TextAlign.End,
        modifier = Modifier.fillMaxWidth(),
        onTextLayout = { result ->
            if (result.didOverflowWidth && countdownFontSize > 9.sp) {
                countdownFontSize = (countdownFontSize.value - 1).sp
            }
        },
        color = when {
            pass.isGeo -> MaterialTheme.colorScheme.error // GEO pass always ongoing
            isOngoing -> MaterialTheme.colorScheme.error // Alerts ongoing status
            isPassed -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
            else -> MaterialTheme.colorScheme.primary // Clean countdown color
        }
    )
}

// The plot's share of the drawer's width, and - because it is square - its height too. A fraction
// rather than a fixed size so it tracks the screen; everything inside the plot then derives from
// whatever this resolves to, so this single number is the only place its overall size is decided.
//
// That squareness is also why it is the right knob for constraining the drawer's HEIGHT: shrinking
// the fraction pulls both axes in together, so the layout gets smaller without changing shape. The
// one thing that does not scale with it is the rim labels, whose size comes from the text metrics,
// so the circle gives up proportionally more room to them the smaller this gets - which is what
// puts a floor under how far it is worth taking this.
private const val SKY_PLOT_WIDTH_FRACTION = 0.48f
// Ceiling on that share, so a tablet's width can't turn a square plot into a drawer taller than the
// card above it. A guard rail, not a layout constant.
private val SKY_PLOT_MAX_SIZE = 200.dp
// The N/E/S/W letters and the AOS/LOS end labels sit on separate radii outside the horizon ring.
// Both offsets are expressed as multiples of the labels' own measured LINE HEIGHT rather than in dp,
// so the whole arrangement tracks the system font scale: the text is sized in sp, and any gap fixed
// in dp is silently invalidated the moment a user turns text size up.
private const val SKY_PLOT_CARDINAL_OFFSET_RATIO = 0.55f
private const val SKY_PLOT_RING_GAP_RATIO = 0.35f
// How far the peak-elevation value sits from its marker.
private val SKY_PLOT_PEAK_LABEL_GAP = 15.dp
// Elevation rings drawn inside the horizon circle. 30/60 is the usual convention, and enough to
// judge an arc's height by eye without cluttering a plot this small.
private val SKY_PLOT_RINGS = listOf(30f, 60f)
// Cardinal points as (label, azimuth in degrees).
private val SKY_PLOT_CARDINALS = listOf("N" to 0f, "E" to 90f, "S" to 180f, "W" to 270f)

/**
 * The tap-to-open detail drawer beneath a pass: how the pass crosses the sky on the left, how long
 * it lasts on the right.
 *
 * These two answer the question the card above can't - a 9-degree grazing pass and an 85-degree
 * overhead one have identical AOS/LOS rows, and only the arc's height tells them apart.
 */
@Composable
private fun PassDetailDrawer(pass: SatellitePass, viewModel: TrackerViewModel) {
    val satelliteColorOverrides by viewModel.satelliteColorOverrides.collectAsStateWithLifecycle()

    // Sampled off the main thread, and only once a drawer is actually open - see
    // TrackerViewModel.getSkyTrack for why the track isn't computed alongside the pass itself.
    // Keyed on the pass's identity rather than on the object, so the isNext flag flipping over on a
    // neighbouring pass doesn't throw this computation away and redo it.
    var track by remember(pass.noradId, pass.groundStationCode, pass.aosMillis) {
        mutableStateOf(emptyList<TrackerViewModel.SkyPoint>())
    }
    LaunchedEffect(pass.noradId, pass.groundStationCode, pass.aosMillis) {
        track = viewModel.getSkyTrack(pass)
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Sized as a share of the drawer rather than a fixed square. That is what makes room for the
        // rim labels: a fixed canvas could not grow to fit them, which forced drawSkyLabel's bounds
        // clamp to drag an end label back inward on top of a cardinal letter whenever a pass rose or
        // set near due east/west. Claiming the drawer's idle width fixes that and enlarges the chart
        // at the same time.
        //
        // A fraction rather than weight(1f) specifically so the spacer below stays weighted: a
        // weighted plot would have the Row reserve the whole leftover width for it, and the moment
        // SKY_PLOT_MAX_SIZE capped its measured width the duration column would be stranded beside
        // the plot with an empty gutter to its right instead of sitting at the drawer's edge.
        SkyPlot(
            track = track,
            maxElevationDeg = pass.maxElevationDeg,
            trackColor = getSatelliteColor(pass.noradId, satelliteColorOverrides),
            modifier = Modifier
                .fillMaxWidth(SKY_PLOT_WIDTH_FRACTION)
                .widthIn(max = SKY_PLOT_MAX_SIZE)
                .aspectRatio(1f)
        )

        Spacer(modifier = Modifier.weight(1f))

        Column(horizontalAlignment = Alignment.End) {
            Text(
                "DURATION",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = formatPassDuration(pass.losMillis - pass.aosMillis),
                fontSize = 26.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

/**
 * A polar sky chart of one pass, as seen from its ground station: zenith at the centre, horizon at
 * the rim, north up. The higher the arc reaches toward the middle, the better the pass.
 *
 * [maxElevationDeg] labels the culmination marker and comes from the pass itself rather than from
 * [track], because predict4java's own `SatPassTime.getMaxEl()` is the authoritative figure.
 */
@Composable
private fun SkyPlot(
    track: List<TrackerViewModel.SkyPoint>,
    maxElevationDeg: Double,
    trackColor: Color,
    modifier: Modifier = Modifier
) {
    val textMeasurer = rememberTextMeasurer()

    // Every colour and style is resolved out here: the Canvas draw lambda is not a composable
    // scope, so MaterialTheme is unreachable inside it.
    val gridColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.30f)
    val horizonColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.60f)
    val cardinalStyle = TextStyle(
        fontSize = 8.sp,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
    )
    val peakStyle = TextStyle(
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        fontFamily = FontFamily.Monospace,
        color = MaterialTheme.colorScheme.onSurface
    )
    // The hashed per-satellite colours are tuned as pill BACKGROUNDS (value 0.45, light text over
    // them), which is too dim for a thin stroke on a dark card. Lightening toward white keeps the
    // satellite's hue recognisable while making the arc readable - and works for a user-picked
    // override colour too, rather than assuming the hash's HSV parameters.
    val lineColor = lerp(trackColor, Color.White, 0.3f)
    // Deliberately the arc's colour rather than the grid's: these two label the pass, not the sky.
    val endLabelStyle = TextStyle(
        fontSize = 8.sp,
        fontWeight = FontWeight.Bold,
        color = lineColor
    )

    // Measured once and reused for both placement and drawing. "W" is the widest cardinal and the
    // end labels are both three characters, so these two boxes bound every rim label there is.
    val cardinalMetrics = textMeasurer.measure("W", cardinalStyle)
    val endMetrics = textMeasurer.measure("AOS", endLabelStyle)

    Canvas(modifier = modifier) {
        val cardinalOffset = cardinalMetrics.size.height * SKY_PLOT_CARDINAL_OFFSET_RATIO
        // Sized for the worst case at every bearing. What separates two rim labels is their extent
        // along the RADIAL direction, and that depends on where they sit: due east/west the radial
        // direction is horizontal, so a label reaches back toward the centre by its full WIDTH; due
        // north/south it is only the line height. Budgeting width everywhere is slightly generous at
        // the top and bottom, but it is uniform, and a ring whose radius shifted with the direction
        // the pass happened to run would read worse than one that doesn't move.
        val endLabelOffset = cardinalOffset +
            cardinalMetrics.size.width / 2f +
            endMetrics.size.height * SKY_PLOT_RING_GAP_RATIO +
            endMetrics.size.width / 2f

        // The canvas has to hold the OUTER half of the end label as well, so that is what the circle
        // gives up. Deriving it here rather than reserving a fixed inset is what lets the chart stay
        // correct at any font scale: at large text sizes the radius shrinks to make room instead of
        // the labels colliding.
        val radius = size.minDimension / 2f - (endLabelOffset + endMetrics.size.width / 2f)
        if (radius <= 0f) return@Canvas
        val centre = Offset(size.width / 2f, size.height / 2f)
        val gridStroke = Stroke(width = 1.dp.toPx())

        SKY_PLOT_RINGS.forEach { elevation ->
            drawCircle(
                color = gridColor,
                radius = (90f - elevation) / 90f * radius,
                center = centre,
                style = gridStroke
            )
        }
        // The horizon reads slightly stronger than the inner rings - it's the plot's boundary, not
        // just another gradation.
        drawCircle(color = horizonColor, radius = radius, center = centre, style = gridStroke)
        // Zenith tick, so "straight overhead" has a visible mark to judge an arc against.
        drawCircle(color = gridColor, radius = 1.5.dp.toPx(), center = centre)

        SKY_PLOT_CARDINALS.forEach { (label, azimuth) ->
            drawSkyLabel(textMeasurer, label, azimuth, centre, radius + cardinalOffset, cardinalStyle)
        }

        // Rings and cardinals are drawn unconditionally above, so an empty track renders an empty
        // chart rather than a blank square while the samples are still being computed.
        if (track.size < 2) return@Canvas

        val arc = Path()
        track.forEachIndexed { index, point ->
            val projected = skyProject(point.azimuthDeg, point.elevationDeg, centre, radius)
            if (index == 0) arc.moveTo(projected.x, projected.y) else arc.lineTo(projected.x, projected.y)
        }
        drawPath(
            path = arc,
            color = lineColor,
            style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        )

        // The two ends are distinguished by shape as well as by label, so the arc still reads
        // correctly at a glance: an open circle where the pass rises, an arrowhead pointing the way
        // it travels where it sets.
        val aosPoint = skyProject(track.first().azimuthDeg, track.first().elevationDeg, centre, radius)
        val losPoint = skyProject(track.last().azimuthDeg, track.last().elevationDeg, centre, radius)

        drawCircle(
            color = lineColor,
            radius = 2.5.dp.toPx(),
            center = aosPoint,
            style = Stroke(width = 1.5.dp.toPx())
        )

        // Heading at LOS, taken from the arc's final segment. A degenerate last step (two samples
        // projecting onto the same pixel) falls back to "straight out from the zenith", which is
        // where a setting pass is headed anyway.
        val secondLast = track[track.size - 2]
        val heading = losPoint - skyProject(secondLast.azimuthDeg, secondLast.elevationDeg, centre, radius)
        drawPath(
            path = arrowHeadAt(
                tip = losPoint,
                direction = if (hypot(heading.x, heading.y) > 0.5f) heading else losPoint - centre,
                lengthPx = 7.dp.toPx()
            ),
            color = lineColor
        )

        val aosAzimuth = track.first().azimuthDeg
        val losAzimuth = track.last().azimuthDeg
        drawSkyLabel(textMeasurer, "AOS", aosAzimuth, centre, radius + endLabelOffset, endLabelStyle)

        // On a near-zero-elevation grazing pass the two ends share almost the same bearing, which
        // would stack these two labels on top of each other. Comparing the projected anchors rather
        // than the bearings themselves keeps the test in the units that actually matter - the same
        // angular gap is far tighter on a small plot than a large one. The arrowhead already
        // distinguishes the ends, so nudging LOS out one line height is enough.
        val aosAnchor = skyProject(aosAzimuth, 0f, centre, radius + endLabelOffset)
        val losAnchor = skyProject(losAzimuth, 0f, centre, radius + endLabelOffset)
        val anchorGap = hypot(losAnchor.x - aosAnchor.x, losAnchor.y - aosAnchor.y)
        val losOffset = if (anchorGap < endMetrics.size.width) {
            endLabelOffset + endMetrics.size.height
        } else {
            endLabelOffset
        }
        drawSkyLabel(textMeasurer, "LOS", losAzimuth, centre, radius + losOffset, endLabelStyle)

        // Culmination marker, anchored on the highest SAMPLE rather than on the pass's tcaMillis.
        // That keeps it right even for a pass computed before tcaMillis existed (anything still
        // cached from before this field was added), while the number beside it stays authoritative.
        val peak = track.maxByOrNull { it.elevationDeg } ?: return@Canvas
        val peakPoint = skyProject(peak.azimuthDeg, peak.elevationDeg, centre, radius)
        drawCircle(color = lineColor, radius = 3.5.dp.toPx(), center = peakPoint)

        // Culmination is the arc's closest approach to the zenith, so the space just INSIDE it is
        // always clear - offsetting the label toward the centre keeps it off the line and inside
        // the plot at the same time. A near-overhead pass leaves no room inside, so that case drops
        // the label below the marker instead.
        val toCentre = centre - peakPoint
        val distance = hypot(toCentre.x, toCentre.y)
        val gap = SKY_PLOT_PEAK_LABEL_GAP.toPx()
        val labelCentre = if (distance > gap * 1.5f) {
            peakPoint + Offset(toCentre.x / distance, toCentre.y / distance) * gap
        } else {
            peakPoint + Offset(0f, gap)
        }
        val peakLabel = textMeasurer.measure("${maxElevationDeg.roundToInt()}°", peakStyle)
        drawText(
            peakLabel,
            topLeft = Offset(
                labelCentre.x - peakLabel.size.width / 2f,
                labelCentre.y - peakLabel.size.height / 2f
            )
        )
    }
}

/**
 * Draws one of the sky plot's rim labels centred at [azimuthDeg] on a ring of [labelRadius],
 * clamped so it always stays inside the canvas.
 *
 * The clamp is what makes the label placement safe: a pass rises and sets at whatever azimuth its
 * geometry dictates, so a three-character label on a due-east end would otherwise run past the
 * right edge and be clipped. Nudging it back inside trades a little radial accuracy for never
 * losing a character.
 */
private fun DrawScope.drawSkyLabel(
    textMeasurer: TextMeasurer,
    label: String,
    azimuthDeg: Float,
    centre: Offset,
    labelRadius: Float,
    style: TextStyle
) {
    val measured = textMeasurer.measure(label, style)
    // Projecting at elevation 0 against an inflated radius lands the label just outside the horizon
    // ring, without needing a second bit of trigonometry.
    val anchor = skyProject(azimuthDeg, 0f, centre, labelRadius)
    drawText(
        measured,
        topLeft = Offset(
            (anchor.x - measured.size.width / 2f)
                .coerceIn(0f, (size.width - measured.size.width).coerceAtLeast(0f)),
            (anchor.y - measured.size.height / 2f)
                .coerceIn(0f, (size.height - measured.size.height).coerceAtLeast(0f))
        )
    )
}

/**
 * A filled triangular arrowhead with its point at [tip], aimed along [direction] and [lengthPx]
 * long. Used to mark which end of a sky-plot arc the satellite is travelling towards.
 */
private fun arrowHeadAt(tip: Offset, direction: Offset, lengthPx: Float): Path {
    val magnitude = hypot(direction.x, direction.y)
    // Straight up is an arbitrary but harmless choice - the caller only ever passes a degenerate
    // direction when it has no better one to offer either.
    val ux = if (magnitude < 1e-3f) 0f else direction.x / magnitude
    val uy = if (magnitude < 1e-3f) -1f else direction.y / magnitude
    val baseX = tip.x - ux * lengthPx
    val baseY = tip.y - uy * lengthPx
    // (-uy, ux) is the unit normal, giving the two base corners either side of the shaft.
    val halfWidth = lengthPx * 0.5f
    return Path().apply {
        moveTo(tip.x, tip.y)
        lineTo(baseX - uy * halfWidth, baseY + ux * halfWidth)
        lineTo(baseX + uy * halfWidth, baseY - ux * halfWidth)
        close()
    }
}

/**
 * Projects an azimuth/elevation pair onto the sky plot: zenith at [centre], horizon at [radius],
 * north up and azimuth increasing clockwise - the convention every sky chart uses, and the one a
 * compass bearing reads directly onto.
 *
 * Distance from the centre is the COMPLEMENT of elevation, so the higher a pass climbs the nearer
 * the middle it draws.
 */
private fun skyProject(azimuthDeg: Float, elevationDeg: Float, centre: Offset, radius: Float): Offset {
    val r = ((90f - elevationDeg) / 90f).coerceIn(0f, 1f) * radius
    val azimuthRad = Math.toRadians(azimuthDeg.toDouble())
    return Offset(
        centre.x + r * sin(azimuthRad).toFloat(),
        centre.y - r * cos(azimuthRad).toFloat()
    )
}

// LOS - AOS as m:ss. A LEO pass runs roughly 5-16 minutes, so there is no hours field to carry.
// Locale.US matches every other formatted figure in the app (see TrackerViewModel.formatDuration),
// so a device locale using non-Latin digits still renders a readable timer.
//
// Internal rather than file-private so the Plan screen's own pass drawer formats durations
// identically - the same figure shown two ways would be worse than not showing it twice at all.
internal fun formatPassDuration(durationMillis: Long): String {
    val totalSeconds = (durationMillis / 1000L).coerceAtLeast(0L)
    return String.format(Locale.US, "%d:%02d", totalSeconds / 60, totalSeconds % 60)
}

// Opened by press-and-holding a (future, non-GEO) pass row. Sets, updates, or removes the
// reminder for that specific pass.
@Composable
private fun PassReminderDialog(
    pass: SatellitePass,
    existingLeadMinutes: Int?,
    useUtcTime: Boolean,
    onDismiss: () -> Unit,
    onSet: (Int) -> Unit,
    onRemove: () -> Unit
) {
    var leadText by remember { mutableStateOf((existingLeadMinutes ?: 10).toString()) }
    // Same UTC/local convention as every other timestamp in the app (PassRow's own formatter).
    val aosFormatter = remember(useUtcTime) {
        SimpleDateFormat("HH:mm:ss", Locale.getDefault()).apply {
            timeZone = if (useUtcTime) TimeZone.getTimeZone("UTC") else TimeZone.getDefault()
        }
    }
    val aosText = remember(pass.aosMillis, aosFormatter) { aosFormatter.format(Date(pass.aosMillis)) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Pass reminder") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "${pass.satelliteName} #${pass.orbitNumber} over ${pass.groundStationCode}, AOS: $aosText",
                    fontWeight = FontWeight.SemiBold
                )
                OutlinedTextField(
                    value = leadText,
                    onValueChange = { leadText = it },
                    label = { Text("Minutes before AOS") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.width(200.dp)
                )
                if (existingLeadMinutes != null) {
                    Text(
                        text = "A reminder is currently set for $existingLeadMinutes min before AOS.",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val leadMinutes = leadText.trim().toIntOrNull()
                if (leadMinutes != null && leadMinutes > 0) onSet(leadMinutes)
            }) { Text(if (existingLeadMinutes != null) "Update" else "Set") }
        },
        dismissButton = {
            Row {
                if (existingLeadMinutes != null) {
                    TextButton(onClick = onRemove) { Text("Remove") }
                }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        }
    )
}

// `overrides` is checked first so a user's custom pick always wins; the hash is purely the
// fallback default for anything the user hasn't picked a color for.
fun getStationColor(code: String, overrides: Map<String, Color> = emptyMap()): Color {
    overrides[code]?.let { return it }
    // Hash every station code - predefined (SVL, MCM) or custom - into a stable Hue value (0-360)
    val hash = abs(code.hashCode())
    val hue = (hash % 360).toFloat()
    // We use a lower Lightness/Value (0.45) to ensure white text stays legible on top of it
    return Color.hsv(hue, 0.70f, 0.45f)
}

// Mirrors getStationColor's hashing scheme so every tracked satellite gets its own stable color
// too - lets a dot, its label, and any pass line touching it read as "the same satellite" at a
// glance, consistent with how station colors already work. Pass lines in Satellite3DView gradient
// directly between this and the station's color rather than blending them into one flat color,
// so the line itself shows which end is which.
fun getSatelliteColor(noradId: Int, overrides: Map<Int, Color> = emptyMap()): Color {
    overrides[noradId]?.let { return it }
    return Color.hsv((abs(noradId) % 360).toFloat(), 0.70f, 0.45f)
}