package com.example.eps_sgtracker.ui

import android.app.Application
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.eps_sgtracker.data.AppSettingsRepository
import com.example.eps_sgtracker.data.DEFAULT_PASS_LOS_GRACE_MINUTES
import com.example.eps_sgtracker.data.DEFAULT_FORECAST_DAYS
import com.example.eps_sgtracker.data.DEFAULT_MIN_PASS_ELEVATION_DEG
import com.example.eps_sgtracker.data.GroundStationRepository
import com.example.eps_sgtracker.data.PassReminder
import com.example.eps_sgtracker.data.ReminderRepository
import com.example.eps_sgtracker.data.SatelliteRepository
import com.example.eps_sgtracker.data.CelestrakUnreachable
import com.example.eps_sgtracker.data.RefreshHalt
import com.example.eps_sgtracker.data.TleFetchResult
import com.example.eps_sgtracker.data.TleRepository
import com.example.eps_sgtracker.data.TrajectoryConfig
import com.example.eps_sgtracker.data.TrajectoryDurationUnit
import com.example.eps_sgtracker.data.AppDatabase
import com.example.eps_sgtracker.model.*
import com.example.eps_sgtracker.network.CelestrakApi
import com.example.eps_sgtracker.notifications.ReminderScheduler

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.TimeUnit
import java.util.concurrent.ConcurrentHashMap

import com.github.amsacode.predict4java.TLE
import com.github.amsacode.predict4java.GroundStationPosition
import com.github.amsacode.predict4java.Satellite
import com.github.amsacode.predict4java.SatelliteFactory
import com.github.amsacode.predict4java.SatPos
import com.github.amsacode.predict4java.PassPredictor
import com.github.amsacode.predict4java.SatPassTime

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor

// Well within TleRepository's 48h staleness threshold, so a long-running session still catches
// an expired TLE promptly rather than only ever refreshing at startup or on manual request.
private const val PERIODIC_TLE_CHECK_INTERVAL_MS = 6 * 60 * 60 * 1000L

// getSatellite3DPosition/getSatelliteTrajectory only need a sub-satellite geodetic position (lat/
// lon/altitude), not anything relative to a real observer, so the observer location is irrelevant
// - reused as a shared constant rather than reconstructed on every call/sample.
private val DUMMY_OBSERVER = GroundStationPosition(0.0, 0.0, 0.0)

// Ceiling on a single satellite's TLE resolution (network fetch, including TleRepository's own
// internal retries), independent of every other satellite's resolution - see resolveOneSatellite.
// TleRepository.forceRefreshSatelliteTle's worst case (3 attempts x CelesTrak's 15s callTimeout,
// plus 800ms+1600ms backoff) is ~47s; 60s leaves headroom above that without touching its
// already-proven retry policy.
private const val PER_SATELLITE_FETCH_TIMEOUT_MS = 60_000L

// Caps how many satellites' TLE fetches are in flight at once - deliberately below OkHttp's own
// default 5-concurrent-requests-per-host cap, so a permit holder is guaranteed its request is
// dispatched immediately rather than sitting queued inside OkHttp while its own timeout clock is
// already running (which would report "fetch failed" for a request that never actually started).
private const val MAX_CONCURRENT_TLE_FETCHES = 4

// How often the pass list recomputes on its own, independent of any ground-station/data change,
// so the rolling 10h window (see recomputePasses) keeps advancing during a long-running session
// instead of only ever being refreshed by a discrete user/data event.
private const val PASS_RECOMPUTE_INTERVAL_MS = 5 * 60 * 1000L

// How close to the ascending node an element set's epoch has to be for it to count as
// "generated at the node" - see propagatedOrbitNumber. Live CelesTrak data puts node-generated
// epochs within ~2 seconds and every other epoch 6+ minutes out, so this threshold sits in a wide
// empty band between the two populations rather than anywhere near real values.
private const val NODE_EPOCH_TOLERANCE_SECONDS = 60.0

// How often the Plan screen's forecast extends its horizon on its own. Far slower than the pass
// list's 5 minutes because a forecast run is an order of magnitude more SGP4 work - and it only
// ever needs to cover the sliver of new time that has appeared since the last run.
private const val FORECAST_REFRESH_INTERVAL_MS = 30 * 60 * 1000L

// How old a TLE's EPOCH may get before the Setup row flags it. SGP4 propagation error grows with
// time from epoch and is dominated by along-track drift, which moves pass TIMES rather than
// distorting the geometry: within a week that is typically under a minute, by two weeks it can be
// many minutes, and past a month the AOS predicted here can miss the real pass entirely. These are
// advisory thresholds for the UI - nothing is suppressed, because a stale prediction is still far
// better than none, and only the user knows whether their target is worth pointing an antenna at.
const val TLE_EPOCH_WARN_DAYS = 7L
const val TLE_EPOCH_STALE_DAYS = 14L

// Pause inserted between successive batches of MAX_CONCURRENT_TLE_FETCHES in resolveSatelliteData
// - on top of tleFetchSemaphore's concurrency cap, not a replacement for it. Without this, a large
// tracked list refreshing all at once (e.g. every satellite crossing the 48h staleness threshold
// together) fires its requests as fast as the network allows once each batch's permits free up,
// which can look like a burst to CelesTrak's servers even though no single request is doing
// anything wrong - this is what a real rate-limit response (HTTP 503) during testing this session
// was most likely triggered by. CelesTrak's own documented update cycle is 2 hours ("there is no
// need for you to check more often"), so spending an extra handful of seconds pacing a refresh
// costs nothing in practice while meaningfully smoothing out request bursts for large lists.
private const val TLE_FETCH_BATCH_DELAY_MS = 3_000L

// Sampling step for a pass's sky track (see getSkyTrack). A typical LEO pass runs 8-16 minutes, so
// 10 seconds yields 50-100 points - smooth at plot size without turning the arc into a polygon.
private const val SKY_TRACK_STEP_SECONDS = 10

private enum class TleRefreshMode {
    /** Refresh only if missing or older than TleRepository's 48h staleness threshold. */
    REFRESH_IF_EXPIRED,
    /** Always hit the network, bypassing cache/staleness entirely. */
    FORCE_REFRESH
}

class TrackerViewModel(application: Application) : AndroidViewModel(application) {

    private val stationRepository = GroundStationRepository(application.applicationContext)
    private val satelliteRepository = SatelliteRepository(application.applicationContext)
    private val appSettingsRepository = AppSettingsRepository(application.applicationContext)
    private val reminderRepository = ReminderRepository(application.applicationContext)

    // Clean, consistent instantiation mapping straight to our dependencies
    private val database = AppDatabase.getDatabase(application.applicationContext)
    private val tleRepository = TleRepository(database.tleDao())

    private val activeTleCache = ConcurrentHashMap<Int, String>()
    private val satelliteNameCache = ConcurrentHashMap<Int, String>()

    // Ready-to-use SGP4/SDP4 propagators, keyed by NORAD ID and kept in lockstep with
    // activeTleCache. Building one is expensive: TLE(...) is a full fixed-column text parse of
    // ~20 numeric fields, and SatelliteFactory.createSatellite constructs either an SGP4 or - for
    // anything deep-space - an SDP4 propagator, whose initialisation computes secular and
    // resonance terms. getSatellite3DPosition used to redo BOTH on every call, and the draw loop
    // calls it once per tracked satellite and once per active pass at 30 fps: six satellites and
    // two passes came to roughly 240 parses and 240 propagator constructions per second, on the UI
    // thread, inside the draw pass.
    //
    // Safe to share, checked against predict4java 1.3.1's sources rather than assumed:
    // AbstractSatellite is stateful (julUTC, satPos, s4, qoms24, perigee, eclipseDepth) but
    // synchronizes every public entry point, and getPosition assigns `satPos = new SatPos()` on
    // entry, so it hands back a fresh object per call rather than a reused one. There is no
    // aliasing hazard for a caller holding a previous result.
    //
    // Monitor contention is a non-issue too: the pass-computation path never reads this map. It
    // builds its own propagators via PassPredictor and the local createSatellite in the GEO
    // branch, so a background SGP4 sweep can never block the renderer on the same lock.
    private val propagatorCache = ConcurrentHashMap<Int, Satellite>()

    // A satellite's name is resolved from its TLE as soon as the fetch succeeds - independent of
    // whether it currently has any qualifying pass. A satellite whose GEO position sits below the
    // 15-degree elevation gate for every active station (see recomputePasses) legitimately
    // gets zero entries in calculatedPasses, so anything deriving the display name by scanning
    // that list (as SetupScreen used to) would show "Fetching name..." forever even though the
    // name was known all along. This StateFlow mirror of satelliteNameCache exists so UI can
    // observe name resolution directly instead of inferring it from pass visibility.
    // Each resolved satellite's TLE EPOCH, which is a different thing from when the OMM was last
    // fetched. They diverge exactly when it matters: if a catalogue entry stops being updated
    // upstream, every refresh re-fetches the same element set, so the fetch timestamp keeps
    // advancing to "just now" while the epoch stays months old. SGP4 error is dominated by
    // along-track drift, which shifts pass TIMES rather than distorting geometry, so the result is
    // a confidently-wrong AOS with a healthy-looking freshness indicator - the worst failure mode
    // available to a tracking app. Populated where the TLE is already parsed (applyFetchResult).
    private val _satelliteTleEpochs = MutableStateFlow<Map<Int, Long>>(emptyMap())
    val satelliteTleEpochs: StateFlow<Map<Int, Long>> = _satelliteTleEpochs.asStateFlow()

    private val _satelliteNames = MutableStateFlow<Map<Int, String>>(emptyMap())
    val satelliteNames: StateFlow<Map<Int, String>> = _satelliteNames.asStateFlow()

    // Distinguishes "still waiting on the TLE fetch" from "the fetch tried and gave up" (see
    // TleRepository.forceRefreshSatelliteTle's retries) - without this, a satellite with a
    // typo'd or decayed/invalid NORAD ID looks identical to one that's simply still loading,
    // forever, with no way for the user to tell the two apart.
    private val _satelliteFetchFailed = MutableStateFlow<Set<Int>>(emptySet())
    val satelliteFetchFailed: StateFlow<Set<Int>> = _satelliteFetchFailed.asStateFlow()

    val availableStations: StateFlow<List<GroundStation>> = stationRepository.allStationsFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, GroundStationRepository.predefinedStations)

    val trackedSatellites: StateFlow<List<TrackedSatellite>> = satelliteRepository.trackedSatelliteIdsFlow
        .map { idList -> idList.map { TrackedSatellite(it) } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    // Tracked-but-hidden NORAD IDs - a satellite can be temporarily hidden from the countdown/
    // Pass List/3D view without losing its tracked status, for quick reactivation later.
    val hiddenSatelliteIds: StateFlow<Set<Int>> = satelliteRepository.hiddenSatelliteIdsFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    // The single source of truth for "which tracked satellites should actually be computed/
    // rendered right now" - both recomputePasses and Satellite3DView filter through this
    // (rather than each re-deriving trackedSatellites minus hiddenSatelliteIds separately) so
    // there's exactly one place that defines what "hidden" means.
    val visibleTrackedSatellites: StateFlow<List<TrackedSatellite>> =
        combine(trackedSatellites, hiddenSatelliteIds) { tracked, hidden ->
            tracked.filterNot { hidden.contains(it.noradId) }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun toggleSatelliteVisibility(noradId: Int) {
        viewModelScope.launch {
            satelliteRepository.setSatelliteHidden(noradId, noradId !in hiddenSatelliteIds.value)
        }
    }

    val forecastDays: StateFlow<Int> = appSettingsRepository.forecastDaysFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, DEFAULT_FORECAST_DAYS)

    // Null means "use the theme's own accent" - see AppSettingsRepository.themeColorFlow.
    val themeColor: StateFlow<Color?> = appSettingsRepository.themeColorFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    fun setThemeColor(color: Color) {
        viewModelScope.launch { appSettingsRepository.setThemeColor(color) }
    }

    fun clearThemeColor() {
        viewModelScope.launch { appSettingsRepository.clearThemeColor() }
    }

    fun setForecastDays(days: Int) {
        viewModelScope.launch { appSettingsRepository.setForecastDays(days) }
    }

    // The Plan screen's multi-day forecast. Deliberately a separate list and job from
    // calculatedPasses: the countdown list is rebuilt wholesale every 5 minutes, which a forecast
    // this expensive could never survive being cancelled by.
    private val _forecastPasses = MutableStateFlow<List<SatellitePass>>(emptyList())
    val forecastPasses: StateFlow<List<SatellitePass>> = _forecastPasses.asStateFlow()

    private val _forecastLoading = MutableStateFlow(false)
    val forecastLoading: StateFlow<Boolean> = _forecastLoading.asStateFlow()

    private var forecastJob: Job? = null

    // Latched rebuild intent - see recomputeForecast. Survives the cancellation of a run that was
    // asked for a full rebuild but never got to commit one, so the run replacing it still does.
    private var pendingFullRebuild = false
    // Wall-clock instant the last forecast run computed out to. An incremental run only has to
    // cover [forecastHorizonMillis, now + forecastDays], which after a 30-minute tick is half an
    // hour of new time rather than the whole multi-day window.
    private var forecastHorizonMillis: Long = 0L

    // Manual per-satellite revolution-number correction - see propagatedOrbitNumber for why a
    // residual constant offset can survive a correct node-anchored computation.
    val orbitOffsets: StateFlow<Map<Int, Int>> = satelliteRepository.orbitOffsetsFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    fun setOrbitOffset(noradId: Int, offset: Int) {
        viewModelScope.launch { satelliteRepository.setOrbitOffset(noradId, offset) }
    }

    // Purely presentational: reorders the Setup screen's satellite list. Callers should only invoke
    // this once a drag actually ends, never per drag frame - trackedSatellites feeds
    // visibleTrackedSatellites, which is one of the flows in the init{} combine that retriggers
    // recomputePasses(), so a write per frame would fire a full SGP4 recompute per frame.
    fun setSatelliteOrder(orderedIds: List<Int>) {
        viewModelScope.launch { satelliteRepository.setSatelliteOrder(orderedIds) }
    }

    // Per-satellite ground station relevance - independent of the show/hide-everywhere toggle
    // above. A satellite absent from configuredSatelliteIds has never had this customized, so
    // effectiveAllowedStations() below treats it as "every active station applies" (matching
    // behavior from before this feature existed); once configured, only the explicitly saved set
    // applies, even if that set is empty.
    val allowedStationsBySatellite: StateFlow<Map<Int, Set<String>>> = satelliteRepository.allowedStationsFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    val configuredSatelliteIds: StateFlow<Set<Int>> = satelliteRepository.allowedStationsConfiguredFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    fun setAllowedStationsForSatellite(noradId: Int, codes: Set<String>) {
        viewModelScope.launch {
            satelliteRepository.setAllowedStations(noradId, codes)
        }
    }

    /** The ground station codes that actually apply to [noradId] right now, given [activeCodes]. */
    fun effectiveAllowedStations(noradId: Int, activeCodes: Set<String>): Set<String> =
        if (noradId in configuredSatelliteIds.value) {
            allowedStationsBySatellite.value[noradId].orEmpty().intersect(activeCodes)
        } else {
            activeCodes
        }

    val activeStationCodes: StateFlow<Set<String>> = stationRepository.activeStationCodesFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    // Experimental and network-dependent, so it defaults to off.
    val cloudLayerEnabled: StateFlow<Boolean> = appSettingsRepository.cloudLayerEnabledFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    fun setCloudLayerEnabled(enabled: Boolean) {
        viewModelScope.launch { appSettingsRepository.setCloudLayerEnabled(enabled) }
    }

    // User-picked colors that override the hashed default (see AppSettingsRepository for why
    // these live in a standalone override table rather than on GroundStation/TrackedSatellite).
    val stationColorOverrides: StateFlow<Map<String, Color>> = appSettingsRepository.stationColorOverridesFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    val satelliteColorOverrides: StateFlow<Map<Int, Color>> = appSettingsRepository.satelliteColorOverridesFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    fun setStationColor(code: String, color: Color) {
        viewModelScope.launch { appSettingsRepository.setStationColor(code, color) }
    }

    fun clearStationColor(code: String) {
        viewModelScope.launch { appSettingsRepository.clearStationColor(code) }
    }

    fun setSatelliteColor(noradId: Int, color: Color) {
        viewModelScope.launch { appSettingsRepository.setSatelliteColor(noradId, color) }
    }

    fun clearSatelliteColor(noradId: Int) {
        viewModelScope.launch { appSettingsRepository.clearSatelliteColor(noradId) }
    }

    private val _calculatedPasses = MutableStateFlow<List<SatellitePass>>(emptyList())
    val calculatedPasses: StateFlow<List<SatellitePass>> = _calculatedPasses.asStateFlow()

    // True only while recomputePasses()'s own body is running - deliberately NOT the sole input
    // to the public isLoading below (see there for why).
    private val _isLoading = MutableStateFlow(false)

    private val _isUpdatingTles = MutableStateFlow(false)
    val isUpdatingTles: StateFlow<Boolean> = _isUpdatingTles.asStateFlow()

    // True while Stage B (recomputePasses) is actively computing, OR while at least one visible
    // tracked satellite hasn't yet gotten a first answer (name or fetch-failure) from Stage A
    // (resolveSatelliteData). Without the second half, Stage B would finish near-instantly against
    // an empty/partial activeTleCache on cold start, and PassListScreen would briefly show "No
    // active tracks" for satellites that are configured and simply still resolving in the
    // background.
    val isLoading: StateFlow<Boolean> = combine(
        _isLoading, visibleTrackedSatellites, satelliteNames, satelliteFetchFailed
    ) { computing, visible, names, failed ->
        computing || visible.any { it.noradId !in names && it.noradId !in failed }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    // Defaults to off (phone-local time), matching how every timestamp in the app already
    // rendered before this setting existed.
    val useUtcTime: StateFlow<Boolean> = appSettingsRepository.useUtcTimeFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    // How long a completed pass stays visible after LOS before dropping off the Pass List -
    // user-configurable (Setup > Display Options), 0 meaning "drop immediately at LOS". Read
    // synchronously (.value) from recomputePasses() below, and also drives its own recompute
    // trigger in init{} so changing it takes effect immediately rather than waiting for the next
    // otherwise-triggered recompute.
    val passLosGraceMinutes: StateFlow<Int> = appSettingsRepository.passLosGraceMinutesFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, DEFAULT_PASS_LOS_GRACE_MINUTES)

    fun setPassLosGraceMinutes(minutes: Int) {
        viewModelScope.launch { appSettingsRepository.setPassLosGraceMinutes(minutes) }
    }

    // The peak elevation a LEO pass must reach to be listed at all - see
    // DEFAULT_MIN_PASS_ELEVATION_DEG. Read synchronously (.value) by both pass computations below,
    // and driving its own recompute trigger in init{} so a change takes effect immediately.
    val minPassElevationDeg: StateFlow<Int> = appSettingsRepository.minPassElevationDegFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, DEFAULT_MIN_PASS_ELEVATION_DEG)

    fun setMinPassElevationDeg(degrees: Int) {
        viewModelScope.launch { appSettingsRepository.setMinPassElevationDeg(degrees) }
    }

    // Per-satellite last-OMM-fetch timestamps, for Setup's per-satellite display - see
    // TleRepository.allTleTimestampsFlow for why this is more diagnostically useful than the
    // aggregate lastUpdatedText below (which only reflects whichever satellite happened to fetch
    // most recently, not any specific one).
    val satelliteTleTimestamps: StateFlow<Map<Int, Long>> = tleRepository.allTleTimestampsFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    // Orbit-line rendering configuration for the 3D view - defaults match the pre-settings
    // behavior exactly (10 minutes of trailing arc, one full revolution of leading arc).
    val trajectoryConfig: StateFlow<TrajectoryConfig> = appSettingsRepository.trajectoryConfigFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, TrajectoryConfig())

    fun setTrajectoryConfig(config: TrajectoryConfig) {
        viewModelScope.launch { appSettingsRepository.setTrajectoryConfig(config) }
    }

    // Whether the 3D view draws country borders + name labels - see AppSettingsRepository for why
    // this defaults to off. Presented in the same settings dialog as trajectoryConfig (both are
    // 3D-view display settings the user reaches via the bottom-left button) but is otherwise
    // completely independent of it - a country-borders toggle has nothing to do with orbit-line
    // duration/units.
    val showCountryBorders: StateFlow<Boolean> = appSettingsRepository.showCountryBordersFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    fun setShowCountryBorders(enabled: Boolean) {
        viewModelScope.launch { appSettingsRepository.setShowCountryBorders(enabled) }
    }

    // Independent of showCountryBorders - a user can show either, both, or neither.
    val showCountryLabels: StateFlow<Boolean> = appSettingsRepository.showCountryLabelsFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    fun setShowCountryLabels(enabled: Boolean) {
        viewModelScope.launch { appSettingsRepository.setShowCountryLabels(enabled) }
    }

    // The lat/lon grid. Defaults to on - see showGraticuleFlow for why it differs from the
    // borders/labels toggles it sits beside.
    val showGraticule: StateFlow<Boolean> = appSettingsRepository.showGraticuleFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    fun setShowGraticule(enabled: Boolean) {
        viewModelScope.launch { appSettingsRepository.setShowGraticule(enabled) }
    }

    // Whether the 3D view renders Earth from satellite imagery via a GPU shader instead of the
    // hand-drawn vector layers. Only meaningful on API 33+ (RuntimeShader); the view falls back to
    // the vector renderer on its own if the platform or the textures can't support it, so this flag
    // being true is a request rather than a guarantee.
    val photorealisticEarth: StateFlow<Boolean> = appSettingsRepository.photorealisticEarthFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    fun setPhotorealisticEarth(enabled: Boolean) {
        viewModelScope.launch { appSettingsRepository.setPhotorealisticEarth(enabled) }
    }

    // Whether the 3D view is in fullscreen (bottom nav bar + the view's own controls hidden).
    // Deliberately NOT persisted via AppSettingsRepository like every other display toggle: it
    // lives here only so MainAppShell can drop the nav bar, and restoring it on a fresh launch
    // would strand the user in a chrome-less screen with no obvious way back out.
    private val _is3DFullscreen = MutableStateFlow(false)
    val is3DFullscreen: StateFlow<Boolean> = _is3DFullscreen.asStateFlow()

    fun set3DFullscreen(enabled: Boolean) {
        _is3DFullscreen.value = enabled
    }

    // Active pass reminders, keyed by PassReminder.key so the Pass List can cheaply look up
    // "does this pass have a reminder" via reminderKeyFor().
    val passReminders: StateFlow<Map<String, PassReminder>> = reminderRepository.remindersFlow
        .map { list -> list.associateBy { it.key } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    /**
     * The reminder-store key identifying [pass] - matches [PassReminder.key]'s format.
     *
     * Keyed on the revolution number rather than on AOS so it survives a TLE refresh nudging the
     * pass by a few seconds; see [PassReminder] for what went wrong when it didn't. Not suitable as
     * a list key: it is stable across recomputes but not guaranteed unique within one list.
     */
    fun reminderKeyFor(pass: SatellitePass): String =
        "${pass.noradId}|${pass.groundStationCode}|${pass.orbitNumber}"

    fun setPassReminder(pass: SatellitePass, leadMinutes: Int) {
        val reminder = PassReminder(
            noradId = pass.noradId,
            stationCode = pass.groundStationCode,
            orbitNumber = pass.orbitNumber,
            aosMillis = pass.aosMillis,
            leadMinutes = leadMinutes,
            satelliteName = pass.satelliteName
        )
        viewModelScope.launch {
            reminderRepository.addReminder(reminder)
            ReminderScheduler.schedule(getApplication(), reminder)
        }
    }

    fun removePassReminder(pass: SatellitePass) {
        val existing = passReminders.value[reminderKeyFor(pass)] ?: return
        viewModelScope.launch {
            reminderRepository.removeReminder(existing.key)
            ReminderScheduler.cancel(getApplication(), existing)
        }
    }

    fun setUseUtcTime(enabled: Boolean) {
        viewModelScope.launch { appSettingsRepository.setUseUtcTime(enabled) }
    }

    // Raw millis rather than a pre-formatted string, kept private - lastUpdatedText below derives
    // its display string from this combined with useUtcTime, so toggling the UTC setting
    // reformats the existing timestamp immediately without needing a fresh DB read. null means
    // "not yet loaded", distinct from the real 0L "never updated" value
    // TleRepository.getLatestTleTimestamp() returns when nothing has ever been cached.
    private val _lastSyncTimestampMillis = MutableStateFlow<Long?>(null)

    /**
     * Non-null while CelesTrak querying is suspended after a non-200 response.
     *
     * Surfaced so a halt can be reported rather than showing every satellite a bare "Update
     * failed", which invites another Force Update tap - the exact behaviour that accumulates HTTP
     * errors toward CelesTrak firewalling the IP.
     */
    val refreshHalt: StateFlow<RefreshHalt?> = tleRepository.refreshHalt

    /**
     * Non-null while the last completed refresh could not reach CelesTrak at all.
     *
     * The counterpart to [refreshHalt], and the reason both exist separately: a non-200 is CelesTrak
     * declining, an unreachable host is the network failing. They call for opposite advice - one
     * says stop tapping Force Update, the other says check your connection and try again - and
     * collapsing them into one "update failed" told the user neither.
     */
    val celestrakUnreachable: StateFlow<CelestrakUnreachable?> = tleRepository.celestrakUnreachable

    val lastUpdatedText: StateFlow<String> = combine(
        _lastSyncTimestampMillis, useUtcTime
    ) { millis, utc ->
        when (millis) {
            null -> "Loading last update time..."
            0L -> "Never updated"
            else -> {
                val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
                formatter.timeZone = if (utc) TimeZone.getTimeZone("UTC") else TimeZone.getDefault()
                formatter.format(Date(millis))
            }
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, "Loading last update time...")

    val countdownTicker = flow {
        while (true) {
            emit(System.currentTimeMillis())
            delay(1000)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), System.currentTimeMillis())

    private var recomputePassesJob: Job? = null
    private val tleFetchSemaphore = Semaphore(MAX_CONCURRENT_TLE_FETCHES)

    // Foreground gate for the periodic tickers in init{}. Driven by MainActivity's onResume /
    // onPause rather than ProcessLifecycleOwner, which would mean pulling in lifecycle-process for
    // a single boolean - this Activity is the process's only one, so its state IS the app's.
    //
    // Without this the 5-minute ticker ran a full satellite-by-station SGP4 sweep - the app's most
    // expensive operation - with nothing on screen. delay() does not wake a sleeping device, but
    // the App Freezer that would otherwise stall these only applies on API 34+ and only once the
    // process is fully cached, so across a minSdk-26 range they ran at full cost any time the
    // screen was on for some other app.
    private val appInForeground = MutableStateFlow(true)

    // Seeded to "now" so the first onResume - which arrives immediately after construction - is
    // skipped: init{} below already drives the startup computations.
    private var lastResumeRecomputeMillis = System.currentTimeMillis()

    private suspend fun awaitForeground() {
        if (!appInForeground.value) appInForeground.first { it }
    }

    // One bad cycle must not terminate a `while (true)` ticker. An exception escaping a direct
    // child of viewModelScope reaches the thread's default uncaught handler and kills the process
    // (a SupervisorJob stops propagation to SIBLINGS, not handling); and if it merely cancels the
    // coroutine, that loop is dead for the rest of the process lifetime while every sibling keeps
    // running - so the app looks healthy while silently never refreshing again.
    private inline fun guarded(block: () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * Called from `MainActivity.onResume`. The elapsed-time gate matters because onResume also
     * fires on rotation, on returning from a permission dialog, on multi-window resize and on
     * every app switch - an unconditional recompute there cancelled the retained ViewModel's
     * already-computed passes and replaced the list with a full-screen spinner every single time
     * the user turned their phone.
     *
     * PASS_RECOMPUTE_INTERVAL_MS is the threshold rather than an arbitrary constant: being away
     * for less than one ticker period means nothing was missed that an in-session tick would not
     * also have skipped.
     */
    fun onAppResumed() {
        appInForeground.value = true
        val now = System.currentTimeMillis()
        if (now - lastResumeRecomputeMillis < PASS_RECOMPUTE_INTERVAL_MS) return
        lastResumeRecomputeMillis = now
        recomputePasses()
        recomputeForecast(fullRebuild = false)
    }

    /** Called from `MainActivity.onPause`; parks the periodic tickers. */
    fun onAppPaused() {
        appInForeground.value = false
    }

    init {
        // Reminders for passes that already started serve no purpose - sweep them on startup so
        // the store and the Pass List's bell indicators never accumulate stale entries.
        viewModelScope.launch(Dispatchers.IO) {
            reminderRepository.pruneExpired(System.currentTimeMillis())
        }

        viewModelScope.launch(Dispatchers.IO) {
            guarded { refreshExpiredTlesAndRecompute() }
            // getTleRefreshingIfExpired only ever runs from here (startup) and addSatellite()
            // - a satellite tracked continuously for more than PERIODIC_TLE_CHECK_INTERVAL_MS
            // without the app restarting or a manual Force TLE Update would otherwise never have
            // its TLE refreshed again, silently drifting further from reality the longer the
            // session runs (e.g. a kiosk/dashboard left open for days).
            while (true) {
                delay(PERIODIC_TLE_CHECK_INTERVAL_MS)
                awaitForeground()
                guarded { refreshExpiredTlesAndRecompute() }
            }
        }

        // Stage B only - no network I/O, so this is cheap to run far more often than the TLE
        // staleness check above. Without a recompute purpose-built for it, the rolling 10h pass
        // window (see recomputePasses) only ever advanced in one coarse jump every 6h (as a side
        // effect of the loop above), and PassListScreen's own filtering only ever removes expired
        // passes from the existing list - nothing repopulated the far edge as real time passed
        // within a session.
        viewModelScope.launch {
            while (true) {
                delay(PASS_RECOMPUTE_INTERVAL_MS)
                awaitForeground()
                recomputePasses()
            }
        }

        viewModelScope.launch {
            // availableStations matters here too, not just which codes are active: it starts out
            // seeded with only the hardcoded predefined pool (see its declaration above) and only
            // picks up custom stations once the DataStore read completes, asynchronously and on
            // no fixed schedule relative to the other flows. Leaving it out of this combine meant
            // that if a custom station's activeStationCodes entry was already set but its
            // GroundStation object hadn't arrived in availableStations yet at the moment this
            // last fired, that station would silently never make it into any pass calculation -
            // and since nothing else re-triggers a recompute, it stayed wrong indefinitely once
            // the app settled after startup. visibleTrackedSatellites (rather than
            // trackedSatellites directly) also picks up hiddenSatelliteIds changes, so toggling a
            // satellite's visibility recomputes the pass list too. allowedStationsBySatellite/
            // configuredSatelliteIds do the same for the per-satellite station allow-list.
            //
            // Deliberately Stage B (recomputePasses) only, never Stage A: this collector can fire
            // repeatedly in quick succession during startup's multi-emission settling (eager-
            // default empty value, then the real DataStore-loaded value), and previously that
            // meant a ground-station/config change could cancel an unrelated in-flight TLE fetch
            // via the old shared calculatePassesJob. Stage B has no network I/O, so cancelling and
            // restarting it freely on every emission is cheap and safe.
            combine(
                visibleTrackedSatellites,
                activeStationCodes,
                availableStations,
                allowedStationsBySatellite,
                configuredSatelliteIds
            ) { _, _, _, _, _ -> }.collect {
                recomputePasses()
            }
        }

        // Separate from the 5-way combine above (kotlinx.coroutines' typed combine() overloads
        // top out at 5 flows) - changing the LOS grace threshold should reflow the list
        // immediately rather than waiting for the next otherwise-triggered recompute.
        viewModelScope.launch {
            passLosGraceMinutes.collect { recomputePasses() }
        }

        // Same reasoning: a revolution-number offset edit should be visible right away.
        viewModelScope.launch {
            orbitOffsets.collect {
                recomputePasses()
                recomputeForecast(fullRebuild = true)
            }
        }

        // And again for the minimum-elevation gate, which changes which passes exist on BOTH
        // screens - so unlike the grace threshold it has to rebuild the forecast too.
        viewModelScope.launch {
            minPassElevationDeg.collect {
                recomputePasses()
                recomputeForecast(fullRebuild = true)
            }
        }

        // The forecast is deliberately NOT driven by the 5-minute recomputePasses ticker: at up to
        // 7 days of look-ahead it costs roughly an order of magnitude more SGP4 work, so it gets
        // its own much slower cadence and only ever extends the horizon incrementally.
        viewModelScope.launch {
            combine(
                visibleTrackedSatellites,
                activeStationCodes,
                availableStations,
                allowedStationsBySatellite,
                configuredSatelliteIds
            ) { _, _, _, _, _ -> }.collect {
                recomputeForecast(fullRebuild = true)
            }
        }

        viewModelScope.launch {
            forecastDays.collect { recomputeForecast(fullRebuild = true) }
        }

        viewModelScope.launch {
            while (true) {
                delay(FORECAST_REFRESH_INTERVAL_MS)
                awaitForeground()
                recomputeForecast(fullRebuild = false)
            }
        }
    }

    private suspend fun refreshExpiredTlesAndRecompute() {
        val targetIds = satelliteRepository.trackedSatelliteIdsFlow.first()
        resolveSatelliteData(targetIds, TleRefreshMode.REFRESH_IF_EXPIRED)
        updateLastSyncTimestampDisplay()
        // The combine(visibleTrackedSatellites, activeStationCodes, availableStations, ...)
        // collector in init{} fires its own recomputePasses() as soon as those flows resolve,
        // which is almost always faster than resolveSatelliteData's network refreshes above - so
        // that first pass gets computed from whatever TLE happened to already be cached, stale or
        // not. For a satellite whose cached TLE genuinely needed refreshing, that stale
        // computation can land just the wrong side of a near-threshold check (e.g. the GEO
        // 15-degree elevation gate) even though it's otherwise accurate enough to render a
        // plausible position elsewhere. Recompute once more now that every expired TLE is
        // guaranteed fresh.
        recomputePasses()
        // Fresh elements invalidate every already-computed forecast pass, so this one has to be a
        // full rebuild rather than an incremental horizon extension.
        recomputeForecast(fullRebuild = true)
    }

    fun manualRefreshAllSelectedTles() {
        // Re-entrancy guard: the button only disables on the next recomposition, leaving a frame
        // in which a fast double-tap starts a second full-catalogue refresh. Two concurrent runs
        // share nothing but the fetch semaphore, which defeats the deliberate
        // TLE_FETCH_BATCH_DELAY_MS pacing.
        if (_isUpdatingTles.value) return
        viewModelScope.launch {
            _isUpdatingTles.value = true
            // try/finally because SetupScreen gates the button on `enabled = !isUpdatingTles`.
            // Anything throwing in here - a DataStore read failure on the very first line, say -
            // used to leave the flag stuck true, greying out Force TLE Update for the rest of the
            // session, on top of killing the process outright.
            try {
                val targetIds = satelliteRepository.trackedSatelliteIdsFlow.first()
                resolveSatelliteData(targetIds, TleRefreshMode.FORCE_REFRESH)
                updateLastSyncTimestampDisplay()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                _isUpdatingTles.value = false
            }
            recomputePasses()
            recomputeForecast(fullRebuild = true)
        }
    }

    fun updateLastSyncTimestampDisplay() {
        viewModelScope.launch(Dispatchers.IO) {
            _lastSyncTimestampMillis.value = tleRepository.getLatestTleTimestamp()
        }
    }

    // Pure timestamp comparison, independent of whatever getCountdownString happens to render -
    // needed because that text used to be the only way UI code detected "is this pass active
    // right now" (a literal `== "ONGOING"` string match), which broke once LEO passes started
    // rendering a real LOS countdown instead of that fixed string while active.
    fun isPassOngoing(pass: SatellitePass, currentMillis: Long): Boolean =
        currentMillis in pass.aosMillis until pass.losMillis

    fun getCountdownString(pass: SatellitePass, currentMillis: Long): String {
        val diffMillis = pass.aosMillis - currentMillis
        val losDiffMillis = pass.losMillis - currentMillis

        return when {
            losDiffMillis <= 0 -> "Passed"
            diffMillis <= 0 -> {
                // GEO's losMillis is just the far edge of the 10h prediction window, not a real
                // horizon event, so a countdown to it wouldn't mean anything - only LEO passes
                // get a real countdown-to-LOS once active.
                if (pass.isGeo) "ONGOING" else "LOS ${formatDuration(losDiffMillis)}"
            }
            else -> formatDuration(diffMillis)
        }
    }

    private fun formatDuration(millis: Long): String {
        val hours = TimeUnit.MILLISECONDS.toHours(millis)
        val minutes = TimeUnit.MILLISECONDS.toMinutes(millis) % 60
        val seconds = TimeUnit.MILLISECONDS.toSeconds(millis) % 60
        // Locale.US explicitly: the default-locale overload renders %d with the DEVICE locale
        // digit SHAPES, so an ar-EG / fa-IR / bn-IN phone showed countdowns as native numerals and
        // broke column alignment against the Locale.US timestamps everywhere else.
        return String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)
    }

    fun getTleEpochDate(tle: TLE): Date {
        val fullYear = if (tle.year < 57) 2000 + tle.year else 1900 + tle.year
        // Locale.US explicitly: Calendar.getInstance(TimeZone) alone uses the *default locale*, and
        // on a device set to e.g. th-TH or ja-JP-u-ca-japanese that returns a non-Gregorian calendar
        // where set(Calendar.YEAR, 2026) means an entirely different year - corrupting every epoch.
        val calendar = Calendar.getInstance(TimeZone.getTimeZone("UTC"), Locale.US).apply {
            clear()
            set(Calendar.YEAR, fullYear)
        }
        val epochMillis = (tle.refepoch - 1.0) * 24.0 * 60.0 * 60.0 * 1000.0
        return Date(calendar.timeInMillis + epochMillis.toLong())
    }

    /**
     * The revolution number the satellite is in at [atMillis].
     *
     * Revolutions increment at the ASCENDING NODE, so the count is anchored to a node crossing
     * rather than to the element set's epoch. Which node, and what number that node's revolution
     * carries, depends on where in the orbit the epoch falls - and that is what the previous
     * implementation got wrong.
     *
     * `u = (argument of perigee + mean anomaly) mod 360` is the argument of latitude at epoch: how
     * far past the ascending node the satellite was when the elements were generated (a
     * near-circular approximation, exact to ~1e-4 revolutions at LEO eccentricities). Checked
     * against live CelesTrak data, u is sharply bimodal:
     *
     *  - **Node-generated** (ISS 0.106 deg, METOP-B 0.118 deg, NOAA-20 0.139 deg, NOAA-18
     *    0.181 deg, NOAA-19 359.975 deg): epoch is within a couple of SECONDS of the node. Roughly
     *    two thirds of the catalogue. Here REV_AT_EPOCH is the count of revolutions *completed* at
     *    that node, so the revolution beginning there - the one every later pass belongs to - is
     *    REV_AT_EPOCH + 1. Not adding that 1 is precisely why these satellites read one too low.
     *  - **Mid-orbit** (AQUA 21.3 deg, TERRA 102.7 deg): epoch is 6-28 minutes past the node, with
     *    no boundary ambiguity - REV_AT_EPOCH plainly labels the revolution in progress. These
     *    already read correctly and must not be shifted.
     *
     * The old formula `orbitnum + floor(u/360 + n*dt)` handles the mid-orbit case correctly but has
     * no notion of the node-generated case at all, which is why an earlier attempt to fix this by
     * adding the u/360 term changed nothing for the satellites that were actually wrong: for them
     * u/360 is ~0.0003, so it rounds away entirely.
     *
     * The two cases are distinguished by how far the epoch sits from the node *in time*, not by a
     * bare angle: the observed gap is enormous (~2 seconds versus 6+ minutes), so
     * [NODE_EPOCH_TOLERANCE_SECONDS] sits in a wide empty band rather than near any real data.
     * A satellite still off by a constant afterwards is a genuine numbering-convention difference
     * that no TLE-derived formula can recover - that is what the per-satellite offset is for.
     */
    private fun propagatedOrbitNumber(tle: TLE, atMillis: Long, offset: Int = 0): Int {
        val epochMillis = getTleEpochDate(tle).time
        val periodSeconds = 86400.0 / tle.meanmo

        val u = (((tle.argper + tle.meanan) % 360.0) + 360.0) % 360.0
        // Signed, so an epoch a hair BEFORE the node (NOAA-19's 359.975 deg) reads as a small
        // negative offset rather than very nearly a full revolution.
        val signedU = if (u <= 180.0) u else u - 360.0
        val secondsFromNode = signedU / 360.0 * periodSeconds

        val anchorMillis: Long
        val baseRev: Int
        if (abs(secondsFromNode) < NODE_EPOCH_TOLERANCE_SECONDS) {
            anchorMillis = epochMillis - (secondsFromNode * 1000.0).toLong()
            baseRev = tle.orbitnum + 1
        } else {
            anchorMillis = epochMillis - (u / 360.0 * periodSeconds * 1000.0).toLong()
            baseRev = tle.orbitnum
        }

        val revsSinceAnchor = floor((atMillis - anchorMillis) / 1000.0 / periodSeconds).toInt()
        // Re-wrapped to 5 digits like the source field: without this a satellite sitting just below
        // the rollover reports 100004 where every other tool shows 4.
        //
        // The rollover itself is inherent to the published data, not a limitation of this app:
        // CelesTrak's OMM JSON serves REV_AT_EPOCH already wrapped, so a satellite past its
        // 100000th revolution (ISS crossed around mid-2016) reads 100000 low at the source and the
        // true count never reaches us - see OmmToTleConverter's cols 64-68 for the measurement.
        //
        // mod() rather than %: floored modulo is what keeps this in 0..99999 even when a negative
        // user offset pushes the sum below zero, where % would return a negative revolution.
        return (baseRev + revsSinceAnchor + offset).mod(100000)
    }

    fun addSatellite(noradId: Int) {
        if (trackedSatellites.value.any { it.noradId == noradId }) return
        viewModelScope.launch {
            satelliteRepository.saveSatelliteId(noradId)
            // resolveSatelliteData suspends until this satellite's own resolution completes (or
            // times out) before returning, so recomputePasses() below always sees a just-resolved
            // (or definitively failed) entry rather than racing the combine() collector's own
            // independent trigger from trackedSatellites picking up the new ID - that race no
            // longer exists now that the collector only ever calls Stage B, which reads whatever
            // Stage A has resolved so far rather than trying to resolve anything itself.
            resolveSatelliteData(listOf(noradId), TleRefreshMode.REFRESH_IF_EXPIRED)
            updateLastSyncTimestampDisplay()
            recomputePasses()
        }
    }

    fun removeSatellite(noradId: Int) {
        // These NORAD-ID-keyed caches are otherwise only ever added to, never pruned - leaking a
        // stale entry indefinitely (and letting getSatelliteName/getSatellite3DPosition keep
        // resolving a removed satellite) until the process restarts.
        activeTleCache.remove(noradId)
        propagatorCache.remove(noradId)
        satelliteNameCache.remove(noradId)
        _satelliteNames.update { it - noradId }
        _satelliteTleEpochs.update { it - noradId }
        _satelliteFetchFailed.update { it - noradId }
        viewModelScope.launch {
            satelliteRepository.removeSatelliteId(noradId)
            appSettingsRepository.clearSatelliteColor(noradId)
            tleRepository.deleteTle(noradId)
            updateLastSyncTimestampDisplay()
        }
    }

    fun toggleGroundStation(code: String) {
        viewModelScope.launch {
            val currentSet = activeStationCodes.value
            val nextSet = if (currentSet.contains(code)) currentSet - code else currentSet + code
            stationRepository.saveActiveStationCodes(nextSet)
        }
    }

    /**
     * Returns false without saving if [code] is blank or already used by another station
     * (predefined or custom) - codes are relied on as unique keys throughout (active-station
     * toggling, pass-list grouping, 3D-view marker/color lookup), so a collision would silently
     * conflate two different stations.
     */
    fun addCustomGroundStation(name: String, code: String, lat: Double, lon: Double): Boolean {
        val normalizedCode = code.trim().uppercase()
        if (normalizedCode.isBlank()) return false
        // Backstop for GroundStationRepository's name|code|lat|lon serialization: a pipe in either
        // free-text field shifts every later field, so the row fails to parse on read and is also
        // undeletable (removal matches the wrong field). SetupScreen reports this specifically;
        // this keeps the invariant if another caller ever appears.
        if (name.contains('|') || normalizedCode.contains('|')) return false
        if (availableStations.value.any { it.code.equals(normalizedCode, ignoreCase = true) }) return false

        val newStation = GroundStation(name = name, code = normalizedCode, latitude = lat, longitude = lon, isCustom = true)
        viewModelScope.launch {
            stationRepository.saveCustomStation(newStation)
        }
        return true
    }

    // Predefined stations (SVL, MCM, FUC, LAR) are permanent and have no delete path - only a
    // custom station's serialized entry can be removed.
    fun removeCustomGroundStation(code: String) {
        viewModelScope.launch {
            stationRepository.removeCustomStation(code)
            appSettingsRepository.clearStationColor(code)
            satelliteRepository.removeStationFromAllAllowLists(code)
        }
    }

    // Stage A: resolves TLE + display name for [targetIds], independent of ground-station/
    // visibility/config state - this function and everything it calls never reads
    // activeStationCodes/availableStations/allowedStationsBySatellite/hiddenSatelliteIds. Each
    // satellite resolves in its own coroutine (supervisorScope + launch, not coroutineScope +
    // async: nothing needs a return value, and a supervisorScope means one satellite's uncaught
    // failure can never cancel its siblings), gated by tleFetchSemaphore and bounded by
    // PER_SATELLITE_FETCH_TIMEOUT_MS, so one stuck/slow satellite can never block another's
    // resolution or delay this function's return. This is the ONLY function that writes
    // activeTleCache/satelliteNameCache/_satelliteNames/_satelliteFetchFailed - callers can rely
    // on this having returned before those are guaranteed up to date for [targetIds].
    //
    // [targetIds] is processed in MAX_CONCURRENT_TLE_FETCHES-sized batches with a
    // TLE_FETCH_BATCH_DELAY_MS pause between them, rather than fanning every ID out at once - a
    // large tracked list (e.g. every satellite crossing the 48h staleness threshold in the same
    // periodic check) would otherwise fire requests back-to-back as fast as tleFetchSemaphore's
    // permits free up, which is indistinguishable from hammering to CelesTrak's servers even
    // though every individual request is legitimate. A single-satellite call (addSatellite) is
    // exactly one batch, so this adds no latency to the common case.
    private suspend fun resolveSatelliteData(targetIds: List<Int>, mode: TleRefreshMode) {
        withContext(Dispatchers.IO) {
            val batches = targetIds.chunked(MAX_CONCURRENT_TLE_FETCHES)
            batches.forEachIndexed { index, batch ->
                supervisorScope {
                    batch.forEach { noradId ->
                        launch { resolveOneSatellite(noradId, mode) }
                    }
                }
                if (index < batches.lastIndex) delay(TLE_FETCH_BATCH_DELAY_MS)
            }
        }
    }

    private suspend fun resolveOneSatellite(noradId: Int, mode: TleRefreshMode) {
        try {
            val result = tleFetchSemaphore.withPermit {
                // Timeout starts only after the permit is acquired, so it always bounds real
                // fetch time rather than queueing time behind the semaphore.
                withTimeoutOrNull(PER_SATELLITE_FETCH_TIMEOUT_MS) {
                    when (mode) {
                        TleRefreshMode.REFRESH_IF_EXPIRED ->
                            tleRepository.getTleRefreshingIfExpired(noradId)
                        TleRefreshMode.FORCE_REFRESH -> {
                            val text = tleRepository.forceRefreshSatelliteTle(noradId)
                            TleFetchResult(text, refreshFailed = text == null)
                        }
                    }
                }
            }
            // withTimeoutOrNull yields null on timeout, which is a failed refresh like any other.
            applyFetchResult(noradId, result ?: TleFetchResult(null, refreshFailed = true))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            e.printStackTrace()
            _satelliteFetchFailed.update { it + noradId }
        }
    }

    private fun applyFetchResult(noradId: Int, result: TleFetchResult) {
        val tleRawText = result.rawTleText
        if (tleRawText == null) {
            // Covers both a genuine repository failure and a timeout (withTimeoutOrNull returns
            // null either way) - both correctly resolve to the same "failed to fetch" UI state.
            // Deliberately does not touch _satelliteNames/activeTleCache here: a previously-
            // resolved name/TLE from an earlier successful run must survive a later failed
            // refresh attempt rather than being cleared out from under the UI.
            _satelliteFetchFailed.update { it + noradId }
            return
        }
        val tleLines = tleRawText.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (tleLines.size < 3) return

        // Parse BEFORE touching any cache: the predict4java TLE constructor does bare
        // Double.parseDouble/Integer.parseInt on fixed columns and throws on anything malformed;
        // caching first left unparseable text in activeTleCache, which getSatellite3DPosition then
        // re-parsed from the 30 FPS draw loop every frame - one bad satellite meant roughly thirty
        // failed parses and stack traces per second. Throwing here instead leaves every cache
        // untouched and lets the catch in resolveOneSatellite mark the fetch failed.
        val tle = TLE(arrayOf(tleLines[0], tleLines[1], tleLines[2]))
        val resolvedName = tle.name ?: "NORAD ID: $noradId"

        // The whole point of TleFetchResult. A refresh that was due and failed still yields usable
        // text - the previously cached element set - so the satellite keeps tracking rather than
        // disappearing. But it raises the SAME failure flag a manual Force Update would, instead of
        // being reported as a successful resolve. Without this, the automatic path silently served
        // months-old elements as though they had just been fetched, while the manual path on the
        // identical failure reported an error: same cause, two different answers.
        if (result.refreshFailed) {
            _satelliteFetchFailed.update { it + noradId }
        } else {
            _satelliteFetchFailed.update { it - noradId }
        }
        activeTleCache[noradId] = tleRawText
        // Overwrites any existing propagator, which is the whole point: this runs on every
        // successful refresh, so a satellite whose elements were just updated gets a propagator
        // built from the NEW ones. Skip this and the draw loop would keep propagating from
        // superseded elements for the rest of the session while every freshness indicator in the
        // UI read as current - exactly the silently-wrong-answer failure B6 exists to prevent.
        // Built here, off the UI thread, rather than lazily from the first frame that needs it.
        propagatorCache[noradId] = SatelliteFactory.createSatellite(tle)
        satelliteNameCache[noradId] = resolvedName
        _satelliteNames.update { it + (noradId to resolvedName) }
        _satelliteTleEpochs.update { it + (noradId to getTleEpochDate(tle).time) }
    }

    // Stage B: computes calculatedPasses purely from already-resolved data (activeTleCache/
    // satelliteNameCache, populated by resolveSatelliteData) plus current ground-station/config
    // state - no network I/O, no dependency on Stage A having *just* run, just reads whatever's
    // cached right now. That makes it cheap and safe to cancel/restart on every ground-station/
    // visibility/config change (exactly as calculateAllPasses used to be overall), without risk of
    // ever aborting an in-flight fetch, since it no longer performs any.
    fun recomputePasses() {
        recomputePassesJob?.cancel()

        val currentSats = visibleTrackedSatellites.value
        val allStations = availableStations.value
        val selectedCodes = activeStationCodes.value
        val configuredIds = configuredSatelliteIds.value
        val allowedMap = allowedStationsBySatellite.value
        val offsets = orbitOffsets.value

        if (currentSats.isEmpty()) {
            _calculatedPasses.value = emptyList()
            // Closes a stuck-spinner gap: without this, cancelling a running computation via this
            // same early-return path (e.g. removing your last tracked satellite mid-computation)
            // would leave _isLoading stuck true forever, since the cancelled run's own
            // `_isLoading.value = false` at the end of its coroutine never executes.
            _isLoading.value = false
            return
        }

        recomputePassesJob = viewModelScope.launch(Dispatchers.Default) {
            _isLoading.value = true
            val leoPasses = mutableListOf<SatellitePass>()
            val geoPasses = mutableListOf<SatellitePass>()
            val activeStations = allStations.filter { selectedCodes.contains(it.code) }

            val graceMillis = passLosGraceMinutes.value * 60_000L
            val minElevation = minPassElevationDeg.value
            val startTime = Date()
            val maxPredictionTime = startTime.time + (10 * 60 * 60 * 1000L)
            // 5 minutes wider than the grace threshold so a pass right at the edge of the linger
            // window is still generated by the predictor rather than lost at the boundary.
            val lookbackStartTime = Date(startTime.time - graceMillis - (5 * 60 * 1000L))

            for (sat in currentSats) {
                ensureActive()
                try {
                    val tleRawText = activeTleCache[sat.noradId] ?: continue
                    val tleLines = tleRawText.lines().map { it.trim() }.filter { it.isNotEmpty() }
                    if (tleLines.size < 3) continue

                    val tle = TLE(arrayOf(tleLines[0], tleLines[1], tleLines[2]))
                    val resolvedName = satelliteNameCache[sat.noradId] ?: "NORAD ID: ${sat.noradId}"

                    // Independent of the show/hide-everywhere visibility toggle: a satellite
                    // that's never had this customized (not in configuredIds) still sees every
                    // active station, matching behavior from before this feature existed - only a
                    // satellite the user has explicitly narrowed down skips stations outside its
                    // saved allow-list.
                    val stationsForSat = if (sat.noradId in configuredIds) {
                        val allowed = allowedMap[sat.noradId].orEmpty()
                        activeStations.filter { it.code in allowed }
                    } else {
                        activeStations
                    }

                    for (station in stationsForSat) {
                        val groundStationPos = GroundStationPosition(
                            station.latitude, station.longitude, station.altitudeMeters
                        )

                        // 1. TRUE GEOSTATIONARY DETECTION LAYER
                        // Gated by Mean Motion (~1 rev/day), low eccentricity (circular), and low inclination (equatorial).
                        // This ensures Tundra orbits (which have a 24h period but high inclination/eccentricity)
                        // fall through to the standard LEO moving predictor engine.
                        if (tle.isGeostationary()) {
                            val satellite = SatelliteFactory.createSatellite(tle)
                            val satPos = satellite.getPosition(groundStationPos, startTime)
                            val elevationDeg = Math.toDegrees(satPos.elevation)

                            // visibility counts over 15 degree elevation
                            if (elevationDeg >= 15) {
                                geoPasses.add(
                                    SatellitePass(
                                        satelliteName = "$resolvedName (GEO)",
                                        noradId = sat.noradId,
                                        groundStationCode = station.code,
                                        aosMillis = startTime.time,
                                        losMillis = maxPredictionTime,
                                        // Propagated to "now", not the raw rev-at-epoch - a GEO
                                        // satellite still completes ~1 revolution/day, so the raw
                                        // value drifts a rev behind for every day of TLE age. Not
                                        // shown in the UI regardless (CelesTrak's REV_AT_EPOCH is
                                        // unreliable for GEO - METEOSAT-9 publishes 746 against a
                                        // true count near 7600) but kept accurate for consistency.
                                        orbitNumber = propagatedOrbitNumber(tle, startTime.time, offsets[sat.noradId] ?: 0),
                                        isGeo = true
                                    )
                                )
                            }
                            continue // Safely bypass the interval engine for true stationary targets
                        }

                        // 2. STANDARD LEO SATELLITE PREDICTOR ENGINE
                        // Skip stations this orbit can geometrically never reach - see
                        // canEverBeVisible's doc comment for why this guard is load-bearing,
                        // not just an optimization.
                        if (!canEverBeVisible(tle, station)) continue

                        val passPredictor = PassPredictor(tle, groundStationPos)
                        val satPasses = passPredictor.getPasses(lookbackStartTime, 10, false)

                        satPasses?.forEach { rawPass ->
                            val aosDate = rawPass.startTime
                            val losDate = rawPass.endTime

                            if (aosDate != null && losDate != null) {
                                if (aosDate.time > maxPredictionTime) return@forEach
                                if (!isUsableLeoPass(rawPass, minElevation)) return@forEach
                                // Keep recently-completed passes within the display grace window -
                                // this purge (not the screen filter) is what actually controls how
                                // long a pass lingers; PassListScreen's own filter must apply the
                                // exact same threshold or a pass could vanish from under it.
                                if (losDate.time < startTime.time - graceMillis) return@forEach

                                leoPasses.add(
                                    SatellitePass(
                                        satelliteName = resolvedName,
                                        noradId = sat.noradId,
                                        groundStationCode = station.code,
                                        aosMillis = aosDate.time,
                                        losMillis = losDate.time,
                                        orbitNumber = propagatedOrbitNumber(tle, aosDate.time, offsets[sat.noradId] ?: 0),
                                        isGeo = false,
                                        // Already computed by getPasses above and previously thrown
                                        // away - see SatellitePass for what they mean and why the
                                        // angles are degrees here but radians elsewhere.
                                        maxElevationDeg = rawPass.maxEl,
                                        tcaMillis = rawPass.tca?.time ?: 0L,
                                        aosAzimuthDeg = rawPass.aosAzimuth,
                                        losAzimuthDeg = rawPass.losAzimuth
                                    )
                                )
                            }
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }

            // --- SEPARATE SORTING & MERGING ---
            // Sort LEO passes chronologically by true AOS timestamp
            val sortedLeo = leoPasses.sortedBy { it.aosMillis }.mapIndexed { index, pass ->
                pass.copy(isNext = index == 0) // Only a LEO pass can be the immediate "Next" tracking target
            }

            // Sort GEO passes alphabetically or by station code
            val sortedGeo = geoPasses.sortedBy { it.satelliteName }

            // Combine them with LEO strictly at the top and GEO strictly at the bottom
            _calculatedPasses.value = sortedLeo + sortedGeo
            _isLoading.value = false
        }
    }

    /**
     * Computes the Plan screen's multi-day forecast.
     *
     * Two modes. A [fullRebuild] discards everything and recomputes the whole window - required
     * whenever previously-computed passes have been invalidated (the satellite/station set changed,
     * fresh orbital elements arrived, the horizon setting moved, an orbit offset was edited). An
     * incremental run instead computes only `[forecastHorizonMillis, now + forecastDays]` and
     * appends, which after the 30-minute tick is half an hour of new time rather than several days.
     *
     * Expired passes are purged on every run using the same `passLosGraceMinutes` the countdown
     * list uses, so both screens agree on when a completed pass disappears.
     */
    fun recomputeForecast(fullRebuild: Boolean) {
        forecastJob?.cancel()

        // Latch the rebuild intent across cancellations. The cancel() above may be discarding a
        // full rebuild that never reached its `forecastHorizonMillis = newHorizon` commit, and the
        // run replacing it would otherwise honour only its OWN argument. An onResume-triggered
        // incremental - which fires on every app switch and rotation - landing on top of a
        // cold-start rebuild therefore computed against pre-refresh elements and never extended the
        // horizon, leaving the Plan screen showing superseded AOS/LOS times indefinitely.
        if (fullRebuild) pendingFullRebuild = true
        val effectiveFullRebuild = pendingFullRebuild

        val currentSats = visibleTrackedSatellites.value
        val allStations = availableStations.value
        val selectedCodes = activeStationCodes.value
        val configuredIds = configuredSatelliteIds.value
        val allowedMap = allowedStationsBySatellite.value
        val offsets = orbitOffsets.value
        val days = forecastDays.value

        if (currentSats.isEmpty()) {
            _forecastPasses.value = emptyList()
            forecastHorizonMillis = 0L
            pendingFullRebuild = false
            _forecastLoading.value = false
            return
        }

        // LAZY so forecastJob is assigned before the body can run - the finally below compares
        // against it to decide whether this is still the current run.
        val job = viewModelScope.launch(Dispatchers.Default, start = CoroutineStart.LAZY) {
            _forecastLoading.value = true
            try {
                val nowMillis = System.currentTimeMillis()
                val graceMillis = passLosGraceMinutes.value * 60_000L
                val minElevation = minPassElevationDeg.value
                val newHorizon = nowMillis + days * 24L * 60L * 60L * 1000L

                // An incremental run can only start where the last one stopped; anything earlier is
                // already in the list. A rebuild (or a first run) starts from now, minus the same
                // lookback the countdown list uses so a pass in its linger window is still produced.
                val searchStart = if (effectiveFullRebuild || forecastHorizonMillis <= 0L) {
                    nowMillis - graceMillis - 5 * 60 * 1000L
                } else {
                    forecastHorizonMillis
                }

                val existing = if (effectiveFullRebuild) emptyList() else _forecastPasses.value
                val collected = mutableListOf<SatellitePass>()

                if (searchStart < newHorizon) {
                    val hoursAhead = ceil((newHorizon - searchStart) / 3_600_000.0).toInt().coerceAtLeast(1)
                    val activeStations = allStations.filter { selectedCodes.contains(it.code) }

                    for (sat in currentSats) {
                        ensureActive()
                        try {
                            val tleRawText = activeTleCache[sat.noradId] ?: continue
                            val tleLines = tleRawText.lines().map { it.trim() }.filter { it.isNotEmpty() }
                            if (tleLines.size < 3) continue

                            // Parsed once per satellite rather than once per station - the countdown
                            // path rebuilds this inside the station loop, which is wasted work that
                            // matters a lot more across a multi-day window.
                            val tle = TLE(arrayOf(tleLines[0], tleLines[1], tleLines[2]))
                            val resolvedName = satelliteNameCache[sat.noradId] ?: "NORAD ID: ${sat.noradId}"
                            val offset = offsets[sat.noradId] ?: 0

                            // A geostationary satellite has no discrete passes to forecast - it is
                            // either continuously in view or never - so it is excluded here rather
                            // than emitted with a meaningless multi-day "LOS" at the window edge.
                            if (tle.isGeostationary()) continue

                            val stationsForSat = if (sat.noradId in configuredIds) {
                                val allowed = allowedMap[sat.noradId].orEmpty()
                                activeStations.filter { it.code in allowed }
                            } else {
                                activeStations
                            }

                            for (station in stationsForSat) {
                                // The countdown path only checks for cancellation per satellite; at
                                // this window size a single satellite-station search is long enough
                                // that it needs its own check to stay responsive.
                                ensureActive()
                                if (!canEverBeVisible(tle, station)) continue

                                val groundStationPos = GroundStationPosition(
                                    station.latitude, station.longitude, station.altitudeMeters
                                )
                                val predictor = PassPredictor(tle, groundStationPos)
                                predictor.getPasses(Date(searchStart), hoursAhead, false)?.forEach { rawPass ->
                                    val aosDate = rawPass.startTime ?: return@forEach
                                    val losDate = rawPass.endTime ?: return@forEach
                                    if (aosDate.time > newHorizon) return@forEach
                                    if (!isUsableLeoPass(rawPass, minElevation)) return@forEach
                                    if (losDate.time < nowMillis - graceMillis) return@forEach
                                    collected.add(
                                        SatellitePass(
                                            satelliteName = resolvedName,
                                            noradId = sat.noradId,
                                            groundStationCode = station.code,
                                            aosMillis = aosDate.time,
                                            losMillis = losDate.time,
                                            orbitNumber = propagatedOrbitNumber(tle, aosDate.time, offset),
                                            isGeo = false,
                                            // Populated here as well as in recomputePasses so both
                                            // lists stay structurally identical - a Plan column or
                                            // a minimum-elevation filter then needs no further
                                            // plumbing on this side.
                                            maxElevationDeg = rawPass.maxEl,
                                            tcaMillis = rawPass.tca?.time ?: 0L,
                                            aosAzimuthDeg = rawPass.aosAzimuth,
                                            losAzimuthDeg = rawPass.losAzimuth
                                        )
                                    )
                                }
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }
                }

                ensureActive()
                // getPasses always overshoots its window by one pass, so an incremental run
                // reliably re-emits a pass the previous run already produced. AOS is the right
                // dedup key here (rather than the reminder system's revolution number): both runs
                // propagate from the same elements, so a duplicate is bit-identical.
                _forecastPasses.value = (existing + collected)
                    .filterNot { it.hasExpired(nowMillis, graceMillis) }
                    .distinctBy(::passInstanceKey)
                    .sortedBy { it.aosMillis }
                forecastHorizonMillis = newHorizon
                // Committed - the latched rebuild intent is satisfied.
                pendingFullRebuild = false
            } finally {
                // Only the CURRENT run may clear the flag. cancel() is asynchronous, so a job that
                // was just cancelled can reach this finally AFTER its replacement has already set
                // the flag true. That hid the spinner while the real computation was still running,
                // and PassForecastScreen then rendered its empty state as though the forecast had
                // finished and genuinely found nothing.
                if (forecastJob === coroutineContext[Job]) _forecastLoading.value = false
            }
        }
        forecastJob = job
        job.start()
    }

    /** One sample of a pass's arc across the observer's sky, in DEGREES (predict4java uses radians). */
    data class SkyPoint(val azimuthDeg: Float, val elevationDeg: Float)

    /**
     * The azimuth/elevation track of [pass] as seen from its own ground station, for the Track
     * screen's sky plot.
     *
     * Computed on demand when a pass's detail drawer opens, rather than alongside the pass itself:
     * one pass at this sampling rate is negligible SGP4 work, but doing it for every pass in the
     * list on every recompute would not be. Deliberately uncached for the same reason - recomputing
     * costs less than reasoning about when a TLE refresh should invalidate a stored track.
     *
     * Returns an empty list rather than throwing if the station or the TLE can't be resolved (a
     * station deleted while its drawer is open, a TLE evicted by removeSatellite). The plot then
     * renders its rings with no arc, which is the right thing to show for "no data" anyway.
     */
    suspend fun getSkyTrack(pass: SatellitePass): List<SkyPoint> = withContext(Dispatchers.Default) {
        try {
            val station = availableStations.value.firstOrNull { it.code == pass.groundStationCode }
                ?: return@withContext emptyList()
            val tleRawText = activeTleCache[pass.noradId] ?: return@withContext emptyList()
            val tleLines = tleRawText.lines().map { it.trim() }.filter { it.isNotEmpty() }
            if (tleLines.size < 3) return@withContext emptyList()

            val predictor = PassPredictor(
                TLE(arrayOf(tleLines[0], tleLines[1], tleLines[2])),
                GroundStationPosition(station.latitude, station.longitude, station.altitudeMeters)
            )

            // getPositions samples symmetrically around a reference instant, so centre on the pass
            // and span half its duration each way. The extra minute of slack, combined with the
            // below-horizon filter below, guarantees the arc actually reaches the horizon at both
            // ends rather than stopping short of it.
            val centreMillis = (pass.aosMillis + pass.losMillis) / 2
            val halfSpanMinutes = ceil((pass.losMillis - pass.aosMillis) / 2.0 / 60_000.0)
                .toInt().coerceAtLeast(1) + 1

            predictor
                .getPositions(Date(centreMillis), SKY_TRACK_STEP_SECONDS, halfSpanMinutes, halfSpanMinutes)
                .map {
                    SkyPoint(
                        azimuthDeg = Math.toDegrees(it.azimuth).toFloat(),
                        elevationDeg = Math.toDegrees(it.elevation).toFloat()
                    )
                }
                .filter { it.elevationDeg >= 0f }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    fun getSatelliteName(noradId: Int): String = satelliteNameCache[noradId] ?: "NORAD $noradId"

    /**
     * The cached propagator for [noradId], or null if the satellite has no usable TLE.
     *
     * Normally a plain map hit - applyFetchResult builds the propagator off the UI thread the
     * moment a TLE resolves. The lazy branch covers the gap where activeTleCache holds text with
     * no matching propagator, which today only happens for entries cached by a build predating
     * this cache.
     */
    private fun propagatorFor(noradId: Int): Satellite? {
        propagatorCache[noradId]?.let { return it }

        val tleRawText = activeTleCache[noradId] ?: return null
        return try {
            val tleLines = tleRawText.lines().map { it.trim() }.filter { it.isNotEmpty() }
            if (tleLines.size < 3) return null
            val tle = TLE(arrayOf(tleLines[0], tleLines[1], tleLines[2]))
            SatelliteFactory.createSatellite(tle).also { propagatorCache[noradId] = it }
        } catch (e: Exception) {
            // Drop the text that failed to parse instead of leaving it to fail again on the next
            // frame. Same reasoning as applyFetchResult's parse-before-cache ordering, and it
            // closes the last route by which one bad TLE could drive printStackTrace at 30 Hz.
            activeTleCache.remove(noradId)
            e.printStackTrace()
            null
        }
    }

    fun getSatellite3DPosition(noradId: Int, timeMillis: Long): Triple<Double, Double, Double>? {
        // Called per tracked satellite AND per active pass from inside the 30 fps draw lambda, so
        // everything reusable across frames has to live in propagatorFor - what remains here is
        // the SGP4 evaluation itself, which genuinely depends on timeMillis.
        val satellite = propagatorFor(noradId) ?: return null
        return try {
            satPosToCartesian(satellite.getPosition(DUMMY_OBSERVER, Date(timeMillis)))
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    // offsetMillis is relative to the trajectory's centerTimeMillis - negative for the trailing
    // (past) arc, non-negative for the leading (future) arc - so the renderer can style the two
    // differently without needing to re-derive "which half is this point on".
    data class TrajectoryPoint(val x: Double, val y: Double, val z: Double, val offsetMillis: Long)

    // A satellite's ground track, sampled around centerTimeMillis, for drawing a trajectory arc in
    // the 3D view. Parses the TLE and builds the SGP4 propagator *once* and reuses it across every
    // sample - unlike naively calling getSatellite3DPosition in a loop, which would redo both for
    // every single point (wasteful for the ~100+ samples a full trajectory needs). How far the
    // trailing/leading arcs extend comes from the user-configurable trajectoryConfig: durations in
    // minutes convert directly, durations in revolutions scale by THIS satellite's own orbital
    // period (from its mean motion), so "1 revolution" is always a complete closed loop regardless
    // of the orbit's altitude.
    fun getSatelliteTrajectory(noradId: Int, centerTimeMillis: Long): List<TrajectoryPoint>? {
        return try {
            val tleRawText = activeTleCache[noradId] ?: return null
            val tleLines = tleRawText.lines().map { it.trim() }.filter { it.isNotEmpty() }
            if (tleLines.size < 3) return null

            val tle = TLE(arrayOf(tleLines[0], tleLines[1], tleLines[2]))
            // Defensive: every catalogued object has a positive mean motion, so this should be
            // unreachable. But the whole sampling loop below is derived from it - a zero would make
            // orbitalPeriodMillis saturate to Long.MAX_VALUE, the past+future sum overflow negative,
            // and the loop then run from -Long.MAX_VALUE upward in 20-second steps appending to a
            // list until the process died. Cheap to rule out at the top.
            if (tle.meanmo <= 0.0 || !tle.meanmo.isFinite()) return null
            // A geostationary satellite doesn't move relative to Earth - its "trajectory" would
            // just be its own position repeated, so skip it entirely rather than draw a
            // meaningless zero-length arc. Same classification recomputePasses() already uses.
            if (tle.isGeostationary()) return null

            // meanmo is in revolutions/day, so a day's worth of milliseconds divided by it gives
            // this specific orbit's real period.
            val orbitalPeriodMillis = (24.0 * 60.0 * 60.0 * 1000.0 / tle.meanmo).toLong()

            val config = trajectoryConfig.value
            fun toMillis(value: Float, unit: TrajectoryDurationUnit): Long = when (unit) {
                TrajectoryDurationUnit.MINUTES -> (value * 60_000.0).toLong()
                TrajectoryDurationUnit.REVOLUTIONS -> (value * orbitalPeriodMillis.toDouble()).toLong()
            }
            val pastMillis = toMillis(config.pastValue, config.pastUnit)
            val futureMillis = toMillis(config.futureValue, config.futureUnit)
            // 20s sampling normally, stretched for very long windows so extreme settings can't
            // produce thousands of SGP4 samples per satellite per recompute.
            val stepMillis = maxOf(20_000L, (pastMillis + futureMillis) / 400L)

            val satellite = SatelliteFactory.createSatellite(tle)
            val points = ArrayList<TrajectoryPoint>()
            var offset = -pastMillis
            while (offset <= futureMillis) {
                val (x, y, z) = satPosToCartesian(satellite.getPosition(DUMMY_OBSERVER, Date(centerTimeMillis + offset)))
                points.add(TrajectoryPoint(x, y, z, offset))
                offset += stepMillis
            }
            points
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun satPosToCartesian(satPos: SatPos): Triple<Double, Double, Double> {
        val latRad = satPos.latitude
        val lonRad = satPos.longitude
        val altitudeKm = satPos.altitude

        // Normalized radius where Earth Radius = 1.0
        val r = 1.0 + (altitudeKm / 6378.137)

        // Standard Spherical-to-Cartesian coordinate conversion
        val x = r * Math.cos(latRad) * Math.sin(lonRad)
        val y = r * Math.sin(latRad)
        val z = r * Math.cos(latRad) * Math.cos(lonRad)

        return Triple(x, y, z)
    }
}

private const val EARTH_MU_KM3_S2 = 398600.4418
// Degrees of slack added on top of the theoretical visibility boundary before treating a
// station as permanently out of reach - covers this check's own simplifying assumption (a
// perfectly circular orbit; real orbits have some eccentricity) so a real, if marginal, pass
// is never mistakenly filtered out. Comfortably larger than any realistic LEO eccentricity
// would shift the true boundary by.
private const val VISIBILITY_MARGIN_DEG = 5.0

// predict4java's PassPredictor.nextSatPass() searches forward in time for the satellite's
// elevation to cross above the horizon via an unbounded `do { ... } while (elevation < 0.0)`
// loop, with no iteration cap or bail-out condition. If a satellite can NEVER be seen from a
// given station at all - e.g. a mid-inclination LEO orbit (ISS, ~51.6 degrees) and a near-polar
// ground station (Svalbard 78.2N, McMurdo -77.8S) whose latitude the ground track never reaches
// even at the horizon's outer edge - that search runs forever. Because recomputePasses()'s
// per-satellite station loop is unguarded, this hangs the whole computation before it ever
// reaches the *other*, actually-visible stations for that satellite: configuring even one
// permanently-below-horizon station for a satellite silently zeroed out every pass for every
// station, not just the unreachable one. This is a cheap geometric pre-check (circular-orbit
// approximation: the ground track reaches latitudes up to the orbital inclination, extended by
// the horizon's angular radius at the satellite's altitude) to skip calling into that loop
// entirely for a station+satellite pair that can never produce a pass, rather than trying to
// survive it.
/**
 * Whether a predicted LEO pass is worth listing, given the user's [minElevationDeg] gate.
 *
 * The counterpart to the geostationary classification in recomputePasses: that one decides which
 * engine a satellite belongs to, this one decides whether an individual pass rises far enough to be
 * a pass at all. A satellite clipping the horizon for two minutes without reaching a degree of
 * elevation is geometrically real but useless to point an antenna at, and listing it buries the
 * passes that matter.
 *
 * Elevation rather than duration deliberately: elevation is the physically meaningful quantity, and
 * duration is confounded by altitude - a higher orbit yields a longer pass at the *same* elevation,
 * so a duration threshold would filter inconsistently across a mixed satellite list. Short passes
 * drop out anyway, since a pass that barely clears the horizon is necessarily brief.
 *
 * Shared by both pass computations rather than inlined at each, so the countdown and the forecast
 * can never disagree about which passes exist - the same hazard passLosGraceMinutes has to respect.
 */
private fun isUsableLeoPass(rawPass: SatPassTime, minElevationDeg: Int): Boolean =
    rawPass.maxEl >= minElevationDeg

/**
 * Whether these elements describe a true geostationary satellite, and therefore which propagation
 * path it takes.
 *
 * Gated on all three of mean motion (~1 rev/day), low eccentricity (circular) and low inclination
 * (equatorial) together. Any one alone is not enough: a Tundra orbit has a 24-hour period but is
 * both eccentric and steeply inclined, and it must fall through to the standard moving-satellite
 * predictor rather than being pinned in place.
 *
 * Shared rather than repeated at each of its three call sites - the countdown, the forecast and the
 * 3D trajectory - because this is a decision about which engine runs, not a display detail. Widening
 * the window in one copy would leave Track treating a satellite as stationary while Plan ran it
 * through the pass predictor and the globe drew it an orbit track, with nothing to point at why.
 */
private fun TLE.isGeostationary(): Boolean =
    meanmo in 0.8..1.2 && eccn < 0.05 && incl < 15.0

private fun canEverBeVisible(tle: TLE, station: GroundStation): Boolean {
    // Same defensive guard as getSatelliteTrajectory: a zero mean motion would divide by zero
    // below and propagate NaN through the comparison, which silently returns false and drops the
    // satellite from every pass computation. Let the real predictor deal with such a TLE instead.
    if (tle.meanmo <= 0.0 || !tle.meanmo.isFinite()) return true
    val meanMotionRadPerSec = tle.meanmo * 2.0 * Math.PI / 86400.0
    val semiMajorAxisKm = Math.cbrt(EARTH_MU_KM3_S2 / (meanMotionRadPerSec * meanMotionRadPerSec))
    val altitudeKm = semiMajorAxisKm - 6378.137
    // A malformed/unrealistic altitude shouldn't be silently swallowed by this guard - let the
    // real predictor run and surface whatever it does with it.
    if (altitudeKm <= 0.0) return true
    val horizonAngleDeg = Math.toDegrees(Math.acos(6378.137 / (6378.137 + altitudeKm)))
    val maxGroundTrackLatDeg = if (tle.incl <= 90.0) tle.incl else 180.0 - tle.incl
    return Math.abs(station.latitude) <= maxGroundTrackLatDeg + horizonAngleDeg + VISIBILITY_MARGIN_DEG
}