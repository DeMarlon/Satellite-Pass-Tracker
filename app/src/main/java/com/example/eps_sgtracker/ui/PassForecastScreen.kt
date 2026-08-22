package com.example.eps_sgtracker.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import com.example.eps_sgtracker.model.SatellitePass
import com.example.eps_sgtracker.model.hasExpired
import com.example.eps_sgtracker.model.passInstanceKey
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

// Space reserved down the right edge for the scrollbar - also its touch width, so the handle is
// grabbable rather than a hairline. Applied to the list and the column headers alike so the two
// stay aligned.
private val SCROLLBAR_GUTTER = 16.dp
private val SCROLLBAR_WIDTH = 5.dp

// Fixed, rather than sized in proportion to how much of the list is on screen. A proportional
// thumb has to be derived from the *currently visible* item count, and because rows here differ in
// height (day dividers and group headers are much shorter than pass rows) that count changes as
// you scroll - so the thumb visibly grew and shrank in your hand. A constant length is also the
// better grab target, which matters now that it drives scrolling.
private val SCROLLBAR_THUMB_LENGTH = 48.dp

// Pill text sits this far inside its badge, so column headers need the same inset to line up with
// the text rather than with the badge's outer edge.
private val PILL_TEXT_INSET = 6.dp

// Column widths, shared by the header and the data rows so the two can never drift apart.
//
// Weighted towards the satellite name, which is the only column whose content isn't a known fixed
// width. The others are sized to what they actually have to hold, so the slack goes where it buys
// something: at 360dp this leaves REV ~41dp (five monospace digits need ~36), GS ~34dp (a
// three-letter pill plus its 12dp of inset needs ~31) and AOS/LOS ~50dp each (HH:mm at 13sp
// monospace needs ~39), while the name gets ~103dp of text width instead of ~85dp.
//
// Note the cells are dp and their contents are sp, so a large accessibility font scale eventually
// overruns them whatever the weights are - AOS/LOS, the tightest of the fixed columns, start
// clipping somewhere past ~1.3x. Taking the name's extra width mostly from GS rather than from the
// time columns is what keeps that threshold close to where it already was.
private const val COLUMN_WEIGHT_SATELLITE = 2.4f
private const val COLUMN_WEIGHT_REV = 0.85f
private const val COLUMN_WEIGHT_GS = 0.7f
private const val COLUMN_WEIGHT_TIME = 1.05f

// A hard floor on the gaps between columns. Weights alone can't guarantee separation - they only
// divide up whatever width exists - so on a narrow screen centred values ran together. Spacing is
// subtracted before the weights are applied, so these gaps survive at any width.
//
// Deliberately not uniform: the first three gaps separate columns that are already visually
// distinct (a colored pill, a plain number, another colored pill), so they can be tight and hand
// the reclaimed width to the satellite name. AOS and LOS are two adjacent runs of identical-looking
// digits, which need a real gap to stay tellable apart at a glance.
private val COLUMN_SPACING_TIGHT = 4.dp
private val COLUMN_SPACING_TIME = 10.dp

// Bounds for the satellite name's auto-shrinking. Names run from "ISS" to "METOP-SGA1" and beyond,
// and truncating them ("METOP-S...") loses exactly the tail that distinguishes one spacecraft in a
// series from another - so the type scales down to fit instead. The floor keeps it legible; a name
// long enough to hit it still ellipsizes as a last resort.
private val SATELLITE_NAME_MAX_SIZE = 12.sp
private val SATELLITE_NAME_MIN_SIZE = 7.sp

/**
 * A draggable fast-scroll handle for [state]. Compose ships no scrollbar for LazyColumn, and a
 * multi-day plan runs to hundreds of rows, where paging through by swipe is slow and gives no sense
 * of position.
 *
 * Position maps to item *index* rather than pixel offset - with variable row heights an exact pixel
 * mapping would need the full content height, which a lazy list never measures. Index mapping makes
 * the whole range reachable, which is what a fast-scroller is for.
 */
@Composable
private fun FastScrollbar(
    state: LazyListState,
    itemCount: Int,
    color: Color,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    // Nothing to scroll: leave the gutter empty rather than showing a handle that can't move.
    if (state.layoutInfo.visibleItemsInfo.isEmpty() || itemCount <= state.layoutInfo.visibleItemsInfo.size) {
        Box(modifier)
        return
    }

    BoxWithConstraints(modifier) {
        val density = LocalDensity.current
        val trackPx = with(density) { maxHeight.toPx() }
        val thumbPx = with(density) { SCROLLBAR_THUMB_LENGTH.toPx() }
        val maxOffset = (trackPx - thumbPx).coerceAtLeast(1f)

        // The handle's own position while a drag is in progress; -1 means "not dragging, follow the
        // list". Accumulating the drag here rather than re-deriving it from firstVisibleItemIndex
        // each event is essential: scrollToItem is asynchronous, so that index lags behind the
        // finger, and adding each delta to a stale base made the handle crawl to a halt.
        var dragOffsetPx by remember { mutableFloatStateOf(-1f) }

        // Read at call time, never captured: the visible-item count shifts as rows of differing
        // heights scroll past, and baking it into a pointerInput key would restart the gesture
        // detector mid-drag - which is what made the handle stop partway down.
        fun maxIndex(): Int =
            (itemCount - state.layoutInfo.visibleItemsInfo.size.coerceAtLeast(1)).coerceAtLeast(1)

        fun scrollToFraction(fraction: Float) {
            val target = (fraction.coerceIn(0f, 1f) * maxIndex()).roundToInt().coerceIn(0, itemCount - 1)
            scope.launch { state.scrollToItem(target) }
        }

        val offsetPx = if (dragOffsetPx >= 0f) {
            dragOffsetPx
        } else {
            maxOffset * (state.firstVisibleItemIndex.toFloat() / maxIndex()).coerceIn(0f, 1f)
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                // Keyed only on itemCount - the one thing that genuinely invalidates the mapping.
                .pointerInput(itemCount) {
                    detectDragGestures(
                        onDragStart = { start ->
                            // Grabbing anywhere on the track jumps there first, so a long list can
                            // be crossed in one gesture instead of dragging from wherever the
                            // handle happens to sit.
                            dragOffsetPx = (start.y - thumbPx / 2f).coerceIn(0f, maxOffset)
                            scrollToFraction(dragOffsetPx / maxOffset)
                        },
                        onDragEnd = { dragOffsetPx = -1f },
                        onDragCancel = { dragOffsetPx = -1f }
                    ) { change, drag ->
                        change.consume()
                        dragOffsetPx = (dragOffsetPx + drag.y).coerceIn(0f, maxOffset)
                        scrollToFraction(dragOffsetPx / maxOffset)
                    }
                }
        ) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset { IntOffset(0, offsetPx.roundToInt()) }
                    .width(SCROLLBAR_WIDTH)
                    .height(SCROLLBAR_THUMB_LENGTH)
                    .background(color, RoundedCornerShape(SCROLLBAR_WIDTH / 2))
            )
        }
    }
}

/**
 * How the forecast rows are grouped. Chronological is the default because it reads as an
 * operational schedule; the other two answer "when is this satellite up" / "what is this station
 * doing", which a pure timeline makes you scan for.
 */
private enum class ForecastSort(val label: String) {
    CHRONOLOGICAL("Chronological"),
    BY_SATELLITE("By satellite"),
    BY_GROUND_STATION("By ground station")
}

/** A rendered row, or the divider between two days. */
private sealed class ForecastEntry {
    data class Pass(val pass: SatellitePass) : ForecastEntry()
    /** Labels are pre-rendered ("DOY 221 - Sat, 09 Aug 2026") since only the builder holds a calendar. */
    data class DayDivider(val previousLabel: String, val nextLabel: String) : ForecastEntry()
    data class GroupHeader(val label: String) : ForecastEntry()
}

/**
 * The Plan screen: a dense, multi-day tabular pass forecast, deliberately without countdowns. Where
 * the Countdown screen answers "what is happening right now", this one answers "what does the week
 * look like", so it trades live urgency for span and scannability.
 */
@Composable
fun PassForecastScreen(viewModel: TrackerViewModel) {
    val isLoading by viewModel.forecastLoading.collectAsStateWithLifecycle()
    val useUtcTime by viewModel.useUtcTime.collectAsStateWithLifecycle()
    val forecastDays by viewModel.forecastDays.collectAsStateWithLifecycle()
    val satelliteColorOverrides by viewModel.satelliteColorOverrides.collectAsStateWithLifecycle()
    val stationColorOverrides by viewModel.stationColorOverrides.collectAsStateWithLifecycle()

    // Held as State objects rather than unwrapped, so visiblePasses below can read them from inside
    // its own calculation and track them itself.
    val passListState = viewModel.forecastPasses.collectAsStateWithLifecycle()
    val graceState = viewModel.passLosGraceMinutes.collectAsStateWithLifecycle()
    val tick = viewModel.countdownTicker.collectAsStateWithLifecycle()

    // Expired passes are filtered HERE, at render time, not only where the forecast is computed.
    //
    // recomputeForecast does purge them, but only when it runs - on a config change, or on its own
    // 30-minute ticker. Backgrounded for hours the process gets frozen, that delay never completes,
    // and the screen kept showing whatever was true when it was last foregrounded: passes hours past
    // LOS, still listed, until a full restart. Track never had the problem because it has always
    // re-filtered against the live clock on every tick, which is what this restores here.
    //
    // Split in two on purpose. buildForecastEntries groups, sorts and inserts day dividers across
    // what can be several hundred rows, so it must not run per tick - and it does not, because
    // derivedStateOf only notifies when the filtered list actually CHANGES, which is a handful of
    // times an hour. Only the cheap O(n) filter repeats. Track could afford to be less careful with
    // its ~20 entries; this list cannot.
    val visiblePasses by remember {
        derivedStateOf {
            val now = tick.value
            val graceMillis = graceState.value * 60_000L
            passListState.value.filterNot { it.hasExpired(now, graceMillis) }
        }
    }

    // Saveable, not a plain remember: navigating away disposes this screen's composition, so a
    // chosen sort order was being thrown away on every tab switch. Saved state survives that, and
    // survives being backgrounded (even through an OS kill, since it is restored from the saved
    // instance state) - while still resetting to the default when the app is genuinely closed.
    // Same mechanism that already preserves the scroll position via rememberLazyListState below.
    //
    // ForecastSort needs no custom Saver: a Kotlin enum inherits java.io.Serializable, which is one
    // of the types Compose's default saver will put in a Bundle.
    var sort by rememberSaveable { mutableStateOf(ForecastSort.CHRONOLOGICAL) }

    // Computed once here rather than per row: a multi-day forecast can run to hundreds of rows, and
    // SimpleDateFormat is expensive enough to matter at that count.
    val timeFormatter = remember(useUtcTime) {
        SimpleDateFormat("HH:mm", Locale.getDefault()).apply {
            timeZone = if (useUtcTime) TimeZone.getTimeZone("UTC") else TimeZone.getDefault()
        }
    }
    val doyCalendar = remember(useUtcTime) {
        Calendar.getInstance(
            if (useUtcTime) TimeZone.getTimeZone("UTC") else TimeZone.getDefault(),
            Locale.US
        )
    }
    val dateFormatter = remember(useUtcTime) {
        SimpleDateFormat("EEE, dd MMM yyyy", Locale.getDefault()).apply {
            timeZone = if (useUtcTime) TimeZone.getTimeZone("UTC") else TimeZone.getDefault()
        }
    }

    val entries = remember(visiblePasses, sort, useUtcTime) {
        buildForecastEntries(visiblePasses, sort, doyCalendar, dateFormatter)
    }
    val listState = rememberLazyListState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(16.dp)
    ) {
        Text(
            "Pass Plan",
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "NEXT $forecastDays DAY${if (forecastDays == 1) "" else "S"} • ${visiblePasses.size} PASSES",
                fontSize = 11.sp,
                letterSpacing = 1.sp,
                color = MaterialTheme.colorScheme.primary
            )
            SortSelector(current = sort, onSelect = { sort = it })
        }

        Spacer(modifier = Modifier.height(8.dp))
        // Same gutter as the list below, so the column headers stay aligned with their rows.
        ForecastHeaderRow(Modifier.padding(end = SCROLLBAR_GUTTER))
        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))

        when {
            isLoading && visiblePasses.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        "Computing $forecastDays-day forecast...",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            visiblePasses.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "No upcoming passes. Configure satellites and ground stations in the Setup tab.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
            else -> Box(modifier = Modifier.fillMaxSize()) {
                LazyColumn(
                    state = listState,
                    // Inset by the gutter so no row ever runs under the handle.
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(end = SCROLLBAR_GUTTER)
                ) {
                items(
                    count = entries.size,
                    key = { index ->
                        when (val entry = entries[index]) {
                            // Prefixed so a pass key can never collide with a divider or header key.
                            is ForecastEntry.Pass -> "p|${passInstanceKey(entry.pass)}"
                            is ForecastEntry.DayDivider -> "d|$index|${entry.nextLabel}"
                            is ForecastEntry.GroupHeader -> "g|${entry.label}"
                        }
                    }
                ) { index ->
                    when (val entry = entries[index]) {
                        is ForecastEntry.GroupHeader -> GroupHeaderRow(entry.label)
                        is ForecastEntry.DayDivider -> DayDividerRow(entry.previousLabel, entry.nextLabel)
                        is ForecastEntry.Pass -> ForecastPassRow(
                            pass = entry.pass,
                            timeFormatter = timeFormatter,
                            satelliteColor = getSatelliteColor(entry.pass.noradId, satelliteColorOverrides),
                            stationColor = getStationColor(entry.pass.groundStationCode, stationColorOverrides)
                        )
                    }
                }
                }

                FastScrollbar(
                    state = listState,
                    itemCount = entries.size,
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f),
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .fillMaxHeight()
                        .width(SCROLLBAR_GUTTER)
                )
            }
        }
    }
}

/**
 * Flattens the sorted passes into rows plus the separators between them. Day dividers are inserted
 * *within* each group, so a satellite's own multi-day run still reads as days rather than one
 * undifferentiated column of times.
 */
private fun buildForecastEntries(
    passes: List<SatellitePass>,
    sort: ForecastSort,
    calendar: Calendar,
    dateFormatter: SimpleDateFormat
): List<ForecastEntry> {
    if (passes.isEmpty()) return emptyList()

    fun dayOfYear(millis: Long): Int {
        calendar.timeInMillis = millis
        return calendar.get(Calendar.DAY_OF_YEAR)
    }

    fun dayLabel(millis: Long): String = "DOY ${dayOfYear(millis)}  -  ${dateFormatter.format(Date(millis))}"

    val entries = mutableListOf<ForecastEntry>()

    fun appendChronologicalRun(run: List<SatellitePass>) {
        var previousDoy: Int? = null
        var previousMillis = 0L
        run.sortedBy { it.aosMillis }.forEach { pass ->
            val doy = dayOfYear(pass.aosMillis)
            val previous = previousDoy
            if (previous != null && previous != doy) {
                entries.add(ForecastEntry.DayDivider(dayLabel(previousMillis), dayLabel(pass.aosMillis)))
            }
            entries.add(ForecastEntry.Pass(pass))
            previousDoy = doy
            previousMillis = pass.aosMillis
        }
    }

    when (sort) {
        ForecastSort.CHRONOLOGICAL -> appendChronologicalRun(passes)
        ForecastSort.BY_SATELLITE ->
            passes.groupBy { it.satelliteName }
                .toSortedMap(String.CASE_INSENSITIVE_ORDER)
                .forEach { (name, group) ->
                    entries.add(ForecastEntry.GroupHeader(name))
                    appendChronologicalRun(group)
                }
        ForecastSort.BY_GROUND_STATION ->
            passes.groupBy { it.groundStationCode }
                .toSortedMap(String.CASE_INSENSITIVE_ORDER)
                .forEach { (code, group) ->
                    entries.add(ForecastEntry.GroupHeader(code))
                    appendChronologicalRun(group)
                }
    }
    return entries
}

@Composable
private fun SortSelector(current: ForecastSort, onSelect: (ForecastSort) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        Surface(
            onClick = { expanded = true },
            shape = RoundedCornerShape(6.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(start = 10.dp, end = 4.dp, top = 4.dp, bottom = 4.dp)
            ) {
                Text(current.label, fontSize = 12.sp)
                Icon(
                    imageVector = Icons.Default.ArrowDropDown,
                    contentDescription = "Change sort order",
                    modifier = Modifier.size(20.dp)
                )
            }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            ForecastSort.entries.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option.label) },
                    onClick = {
                        onSelect(option)
                        expanded = false
                    }
                )
            }
        }
    }
}

@Composable
private fun ForecastHeaderRow(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Satellite names vary wildly in length, so that column stays left-aligned and gets the
        // pill's own text inset (aligning to the badge's outer edge instead read as shifted left).
        // Every other column holds fixed-width content, so header and value are both centred - that
        // way the label always sits squarely over what it describes rather than drifting to one
        // side of it.
        HeaderCell("SATELLITE", Modifier.weight(COLUMN_WEIGHT_SATELLITE).padding(start = PILL_TEXT_INSET))
        Spacer(Modifier.width(COLUMN_SPACING_TIGHT))
        HeaderCell("REV", Modifier.weight(COLUMN_WEIGHT_REV), TextAlign.Center)
        Spacer(Modifier.width(COLUMN_SPACING_TIGHT))
        HeaderCell("GS", Modifier.weight(COLUMN_WEIGHT_GS), TextAlign.Center)
        Spacer(Modifier.width(COLUMN_SPACING_TIGHT))
        HeaderCell("AOS", Modifier.weight(COLUMN_WEIGHT_TIME), TextAlign.Center)
        Spacer(Modifier.width(COLUMN_SPACING_TIME))
        HeaderCell("LOS", Modifier.weight(COLUMN_WEIGHT_TIME), TextAlign.Center)
    }
}

/**
 * The satellite name, scaled down just enough to fit its pill instead of being truncated.
 *
 * Truncation is especially bad for this content: spacecraft in a series differ only in their tail
 * ("METOP-SGA1" vs "METOP-SGB1"), so "METOP-S..." collapses distinct satellites into one label.
 *
 * This used to be a hand-rolled loop that stepped [SATELLITE_NAME_MAX_SIZE] down while
 * `TextLayoutResult.didOverflowWidth` was true, and it never fired once: that flag is
 * `size.width < multiParagraph.width`, and under an ellipsizing overflow the paragraph is laid out
 * clamped to the cell width, which makes the two equal by construction. Every name rendered at the
 * maximum size and ellipsized, and the floor was never reached by anything. [TextAutoSize] does the
 * search properly (and in fewer passes - it bisects rather than stepping), so the size is a layout
 * concern again rather than composition state that only ever shrank.
 *
 * A name long enough to bottom out at the floor still ellipsizes, but from the middle, keeping the
 * tail that identifies which spacecraft in the series this is.
 */
@Composable
private fun AutoShrinkingName(name: String, modifier: Modifier = Modifier) {
    Text(
        text = name,
        modifier = modifier,
        autoSize = TextAutoSize.StepBased(
            minFontSize = SATELLITE_NAME_MIN_SIZE,
            maxFontSize = SATELLITE_NAME_MAX_SIZE,
            stepSize = 0.25.sp
        ),
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onPrimaryContainer,
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.MiddleEllipsis
    )
}

@Composable
private fun HeaderCell(text: String, modifier: Modifier, align: TextAlign = TextAlign.Start) {
    Text(
        text = text,
        modifier = modifier,
        fontSize = 9.sp,
        letterSpacing = 1.sp,
        fontWeight = FontWeight.Bold,
        textAlign = align,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun GroupHeaderRow(label: String) {
    Text(
        text = label.uppercase(),
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.sp,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 14.dp, bottom = 4.dp)
    )
}

/**
 * Divider between two days: the day being left above the line, the day being entered below it, so
 * each label sits against the rows it actually describes.
 */
@Composable
private fun DayDividerRow(previousLabel: String, nextLabel: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp, bottom = 6.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        Text(
            text = previousLabel,
            fontSize = 9.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
        Text(
            text = nextLabel,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.9f)
        )
    }
}

@Composable
private fun ForecastPassRow(
    pass: SatellitePass,
    timeFormatter: SimpleDateFormat,
    satelliteColor: Color,
    stationColor: Color
) {
    // Scoped to this row's LazyColumn key, so an open line survives being scrolled off and back.
    // Deliberately without the Track screen's chevron affordance: these are dense table rows and
    // there is no horizontal room for one without stealing it from a column that carries data.
    var expanded by rememberSaveable { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded }
            .animateContentSize()
    ) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Same colored-pill idiom as the Track screen's rows, just sized down for a dense table:
        // the satellite's own color is the badge background rather than the text color.
        Box(modifier = Modifier.weight(COLUMN_WEIGHT_SATELLITE)) {
            Surface(
                shape = RoundedCornerShape(4.dp),
                color = satelliteColor
            ) {
                AutoShrinkingName(
                    name = pass.satelliteName,
                    modifier = Modifier.padding(horizontal = PILL_TEXT_INSET, vertical = 2.dp)
                )
            }
        }
        Spacer(Modifier.width(COLUMN_SPACING_TIGHT))
        Text(
            text = "${pass.orbitNumber}",
            modifier = Modifier.weight(COLUMN_WEIGHT_REV),
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
            textAlign = TextAlign.Center,
            maxLines = 1,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.width(COLUMN_SPACING_TIGHT))
        Box(
            modifier = Modifier.weight(COLUMN_WEIGHT_GS),
            contentAlignment = Alignment.Center
        ) {
            Surface(
                shape = RoundedCornerShape(4.dp),
                color = stationColor
            ) {
                Text(
                    text = pass.groundStationCode,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    maxLines = 1,
                    modifier = Modifier.padding(horizontal = PILL_TEXT_INSET, vertical = 2.dp)
                )
            }
        }
        Spacer(Modifier.width(COLUMN_SPACING_TIGHT))
        // softWrap off: on a very narrow screen a wrapped "17:32" would silently become two lines
        // and push the row's height out of step with its neighbours.
        Text(
            text = timeFormatter.format(Date(pass.aosMillis)),
            modifier = Modifier.weight(COLUMN_WEIGHT_TIME),
            fontSize = 13.sp,
            fontFamily = FontFamily.Monospace,
            textAlign = TextAlign.Center,
            maxLines = 1,
            softWrap = false
        )
        Spacer(Modifier.width(COLUMN_SPACING_TIME))
        Text(
            text = timeFormatter.format(Date(pass.losMillis)),
            modifier = Modifier.weight(COLUMN_WEIGHT_TIME),
            fontSize = 13.sp,
            fontFamily = FontFamily.Monospace,
            textAlign = TextAlign.Center,
            maxLines = 1,
            softWrap = false,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }

        // The two figures the table itself has no column for. The Plan forecast excludes
        // geostationary satellites outright, so every row here has a real arc and a real duration -
        // no need for the Track drawer's GEO guard.
        if (expanded) {
            Text(
                text = "Max. EL: ${pass.maxElevationDeg.roundToInt()}°" +
                    "  •  Duration: ${formatPassDuration(pass.losMillis - pass.aosMillis)}",
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = PILL_TEXT_INSET, bottom = 6.dp)
            )
        }
    }
}
