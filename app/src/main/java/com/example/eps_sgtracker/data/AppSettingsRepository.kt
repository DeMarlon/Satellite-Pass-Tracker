package com.example.eps_sgtracker.data

import android.content.Context
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

private val Context.appSettingsDataStore by preferencesDataStore(
    name = "app_settings",
    // See ReminderRepository for why: an unparseable file would otherwise throw out of .data,
    // through stateIn, into viewModelScope - which has no CoroutineExceptionHandler - on launch.
    // This store is the worst place for that, since MainActivity reads themeColor during onCreate.
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() }
)

enum class TrajectoryDurationUnit { MINUTES, REVOLUTIONS }

// Master switch for the experimental cloud-layer feature, currently hidden pending a possible
// re-introduction. Everything underneath it (CloudRepository, GibsCloudApi, the 3D view's cloud
// rendering, the Setup toggle) is intentionally kept intact, so flipping this to true brings the
// whole feature back - including each user's previously stored choice, which is left untouched.
//
// This lives here, gating cloudLayerEnabledFlow itself, rather than only hiding the toggle in
// SetupScreen. Hiding the control alone does NOT disable the feature: the rendering reads the
// persisted preference, so anyone who had switched the layer on before it was hidden kept it
// permanently, with the only means of turning it off now removed from the UI.
const val CLOUD_LAYER_FEATURE_ENABLED = false

// How many minutes a completed pass lingers in the Pass List after LOS before disappearing - the
// default before this became user-configurable. 0 is a valid, deliberate choice: it means a pass
// drops off the moment LOS passes.
const val DEFAULT_PASS_LOS_GRACE_MINUTES = 15

// How many days ahead the Plan screen's tabular forecast reaches. 5 days is a deliberate default:
// SGP4 error for LEO is dominated by along-track drift, which shifts pass *times* rather than
// distorting the geometry, and at this horizon that stays within a minute or two for typical
// satellites - invisible at the Plan screen's HH:mm resolution. Beyond a week it degrades enough
// (especially for low-perigee/high-drag objects) that the numbers stop being trustworthy, hence
// the cap.
const val DEFAULT_FORECAST_DAYS = 5
const val MIN_FORECAST_DAYS = 1
const val MAX_FORECAST_DAYS = 7

// The peak elevation a LEO pass has to reach before it counts as a pass at all. This is the LEO
// counterpart to the geostationary classification in recomputePasses: a satellite that clips the
// horizon for two minutes and never rises above a degree is geometrically real but operationally
// useless, and listing it only buries the passes that matter.
//
// User-configurable rather than a constant because the right value is site-specific - terrain and
// antenna pattern differ per ground station. 5 degrees clears the 0-3 degree noise without hiding
// marginal-but-real passes; 0 restores exactly the behaviour from before this existed, which also
// makes it easy to confirm what the filter is responsible for.
const val DEFAULT_MIN_PASS_ELEVATION_DEG = 5
const val MIN_MIN_PASS_ELEVATION_DEG = 0
const val MAX_MIN_PASS_ELEVATION_DEG = 30

// How the 3D view's orbit/trajectory lines are configured: whether they're drawn at all, and how
// far the trailing (past) and leading (future) arcs extend - each expressible in either absolute
// minutes or in that satellite's own revolutions (converted per-satellite from its mean motion).
data class TrajectoryConfig(
    val enabled: Boolean = true,
    val pastValue: Float = 10f,
    val pastUnit: TrajectoryDurationUnit = TrajectoryDurationUnit.MINUTES,
    val futureValue: Float = 1f,
    val futureUnit: TrajectoryDurationUnit = TrajectoryDurationUnit.REVOLUTIONS
)

class AppSettingsRepository(private val context: Context) {

    // Guards the READ path, which the corruptionHandler does not: .data still throws IOException
    // for ordinary I/O failure. Every public flow below maps off this, never .data directly.
    private val prefs: Flow<Preferences> = context.appSettingsDataStore.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }

    companion object {
        private val CLOUD_LAYER_ENABLED_KEY = booleanPreferencesKey("cloud_layer_enabled")
        private val USE_UTC_TIME_KEY = booleanPreferencesKey("use_utc_time")
        private val STATION_COLOR_OVERRIDES_KEY = stringSetPreferencesKey("station_color_overrides")
        private val SATELLITE_COLOR_OVERRIDES_KEY = stringSetPreferencesKey("satellite_color_overrides")
        private val TRAJECTORY_ENABLED_KEY = booleanPreferencesKey("trajectory_enabled")
        private val TRAJECTORY_PAST_VALUE_KEY = floatPreferencesKey("trajectory_past_value")
        private val TRAJECTORY_PAST_UNIT_KEY = stringPreferencesKey("trajectory_past_unit")
        private val TRAJECTORY_FUTURE_VALUE_KEY = floatPreferencesKey("trajectory_future_value")
        private val TRAJECTORY_FUTURE_UNIT_KEY = stringPreferencesKey("trajectory_future_unit")
        private val PASS_LOS_GRACE_MINUTES_KEY = intPreferencesKey("pass_los_grace_minutes")
        private val FORECAST_DAYS_KEY = intPreferencesKey("forecast_days")
        private val THEME_COLOR_KEY = intPreferencesKey("theme_color")
        private val SHOW_COUNTRY_BORDERS_KEY = booleanPreferencesKey("show_country_borders")
        private val SHOW_COUNTRY_LABELS_KEY = booleanPreferencesKey("show_country_labels")
        private val PHOTOREALISTIC_EARTH_KEY = booleanPreferencesKey("photorealistic_earth")
        private val MIN_PASS_ELEVATION_DEG_KEY = intPreferencesKey("min_pass_elevation_deg")
        private val SHOW_GRATICULE_KEY = booleanPreferencesKey("show_graticule")

        private fun parseUnit(raw: String?, fallback: TrajectoryDurationUnit): TrajectoryDurationUnit =
            TrajectoryDurationUnit.entries.firstOrNull { it.name == raw } ?: fallback
    }

    // Experimental and network-dependent, so it defaults to off - and while the feature is switched
    // off at build time, it reads as off no matter what is stored (see CLOUD_LAYER_FEATURE_ENABLED).
    val cloudLayerEnabledFlow: Flow<Boolean> = prefs.map { preferences ->
        CLOUD_LAYER_FEATURE_ENABLED && (preferences[CLOUD_LAYER_ENABLED_KEY] ?: false)
    }

    suspend fun setCloudLayerEnabled(enabled: Boolean) {
        context.appSettingsDataStore.edit { preferences ->
            preferences[CLOUD_LAYER_ENABLED_KEY] = enabled
        }
    }

    // Defaults to off (phone-local time) - matches how every timestamp in the app already
    // rendered before this setting existed.
    val useUtcTimeFlow: Flow<Boolean> = prefs.map { preferences ->
        preferences[USE_UTC_TIME_KEY] ?: false
    }

    suspend fun setUseUtcTime(enabled: Boolean) {
        context.appSettingsDataStore.edit { preferences ->
            preferences[USE_UTC_TIME_KEY] = enabled
        }
    }

    val passLosGraceMinutesFlow: Flow<Int> = prefs.map { preferences ->
        preferences[PASS_LOS_GRACE_MINUTES_KEY] ?: DEFAULT_PASS_LOS_GRACE_MINUTES
    }

    suspend fun setPassLosGraceMinutes(minutes: Int) {
        context.appSettingsDataStore.edit { preferences ->
            // 0 is valid and meaningful (see DEFAULT_PASS_LOS_GRACE_MINUTES) - only reject
            // negative input, which has no sensible interpretation here.
            preferences[PASS_LOS_GRACE_MINUTES_KEY] = minutes.coerceAtLeast(0)
        }
    }

    val minPassElevationDegFlow: Flow<Int> = prefs.map { preferences ->
        preferences[MIN_PASS_ELEVATION_DEG_KEY] ?: DEFAULT_MIN_PASS_ELEVATION_DEG
    }

    suspend fun setMinPassElevationDeg(degrees: Int) {
        context.appSettingsDataStore.edit { preferences ->
            // Clamped rather than validated at the call site: 0 is a deliberate "show everything"
            // choice, and an absurd upper value would silently empty both pass screens.
            preferences[MIN_PASS_ELEVATION_DEG_KEY] =
                degrees.coerceIn(MIN_MIN_PASS_ELEVATION_DEG, MAX_MIN_PASS_ELEVATION_DEG)
        }
    }

    val forecastDaysFlow: Flow<Int> = prefs.map { preferences ->
        preferences[FORECAST_DAYS_KEY] ?: DEFAULT_FORECAST_DAYS
    }

    // Overrides the theme's primary accent - the color behind headings, section labels, active nav
    // items, the "next pass" highlight and so on. Null means "no override", which keeps whatever
    // the theme itself defines rather than baking today's default into storage, so a future change
    // to the shipped palette still reaches users who never customized it.
    val themeColorFlow: Flow<Color?> = prefs.map { preferences ->
        preferences[THEME_COLOR_KEY]?.let { Color(it) }
    }

    suspend fun setThemeColor(color: Color) {
        context.appSettingsDataStore.edit { preferences ->
            preferences[THEME_COLOR_KEY] = color.toArgb()
        }
    }

    suspend fun clearThemeColor() {
        context.appSettingsDataStore.edit { preferences ->
            preferences.remove(THEME_COLOR_KEY)
        }
    }

    suspend fun setForecastDays(days: Int) {
        context.appSettingsDataStore.edit { preferences ->
            preferences[FORECAST_DAYS_KEY] = days.coerceIn(MIN_FORECAST_DAYS, MAX_FORECAST_DAYS)
        }
    }

    // Off by default: the priciest, newest-mechanism layer in the 3D view's Natural Earth data
    // (roughly doubles per-frame vector point volume) - same risk profile that already justifies
    // cloudLayerEnabled being optional among otherwise always-on decorative layers. Independent
    // from showCountryLabelsFlow so borders and labels can be toggled separately.
    val showCountryBordersFlow: Flow<Boolean> = prefs.map { preferences ->
        preferences[SHOW_COUNTRY_BORDERS_KEY] ?: false
    }

    suspend fun setShowCountryBorders(enabled: Boolean) {
        context.appSettingsDataStore.edit { preferences ->
            preferences[SHOW_COUNTRY_BORDERS_KEY] = enabled
        }
    }

    // Off by default: a real TextMeasurer-cache-pressure risk from up to ~177 country name labels.
    // Independent from showCountryBordersFlow so a user can show borders without the label
    // clutter, or vice versa.
    val showCountryLabelsFlow: Flow<Boolean> = prefs.map { preferences ->
        preferences[SHOW_COUNTRY_LABELS_KEY] ?: false
    }

    suspend fun setShowCountryLabels(enabled: Boolean) {
        context.appSettingsDataStore.edit { preferences ->
            preferences[SHOW_COUNTRY_LABELS_KEY] = enabled
        }
    }

    // The lat/lon grid on the globe. Defaults to TRUE, unlike the country borders/labels next to it:
    // the graticule was drawn unconditionally before it became a setting, so anything else would
    // silently take it away from everyone on upgrade.
    val showGraticuleFlow: Flow<Boolean> = prefs.map { preferences ->
        preferences[SHOW_GRATICULE_KEY] ?: true
    }

    suspend fun setShowGraticule(enabled: Boolean) {
        context.appSettingsDataStore.edit { preferences ->
            preferences[SHOW_GRATICULE_KEY] = enabled
        }
    }

    // Swaps the hand-drawn vector globe for NASA satellite imagery lit by a GPU shader. Defaults off
    // and stays a user choice for two reasons: it needs API 33 for RuntimeShader, and it holds
    // roughly 19MB of texture that a low-end device may not want to spend. Persisted like every
    // other display toggle, so a device that can run it only has to be told once.
    val photorealisticEarthFlow: Flow<Boolean> = prefs.map { preferences ->
        preferences[PHOTOREALISTIC_EARTH_KEY] ?: false
    }

    suspend fun setPhotorealisticEarth(enabled: Boolean) {
        context.appSettingsDataStore.edit { preferences ->
            preferences[PHOTOREALISTIC_EARTH_KEY] = enabled
        }
    }

    // All five trajectory fields as one value object, read and written together - the settings
    // dialog edits/applies them as a unit, and a combined flow means a save can never emit a
    // half-updated mix of old and new fields to observers.
    val trajectoryConfigFlow: Flow<TrajectoryConfig> = prefs.map { preferences ->
        val defaults = TrajectoryConfig()
        TrajectoryConfig(
            enabled = preferences[TRAJECTORY_ENABLED_KEY] ?: defaults.enabled,
            pastValue = preferences[TRAJECTORY_PAST_VALUE_KEY] ?: defaults.pastValue,
            pastUnit = parseUnit(preferences[TRAJECTORY_PAST_UNIT_KEY], defaults.pastUnit),
            futureValue = preferences[TRAJECTORY_FUTURE_VALUE_KEY] ?: defaults.futureValue,
            futureUnit = parseUnit(preferences[TRAJECTORY_FUTURE_UNIT_KEY], defaults.futureUnit)
        )
    }

    suspend fun setTrajectoryConfig(config: TrajectoryConfig) {
        context.appSettingsDataStore.edit { preferences ->
            preferences[TRAJECTORY_ENABLED_KEY] = config.enabled
            preferences[TRAJECTORY_PAST_VALUE_KEY] = config.pastValue
            preferences[TRAJECTORY_PAST_UNIT_KEY] = config.pastUnit.name
            preferences[TRAJECTORY_FUTURE_VALUE_KEY] = config.futureValue
            preferences[TRAJECTORY_FUTURE_UNIT_KEY] = config.futureUnit.name
        }
    }

    // User-picked colors that override getStationColor/getSatelliteColor's hashed default.
    // Deliberately a standalone override table rather than a field on GroundStation/
    // TrackedSatellite: the predefined station pool is a hardcoded list that's never persisted
    // at all, and satellites are persisted as bare NORAD IDs with no other per-satellite data,
    // so neither has anywhere to carry a field like this. A parallel "key|argb" set - serialized
    // the same way GroundStationRepository already serializes custom stations - applies
    // uniformly to predefined and custom entries alike and needs no migration of existing data.
    val stationColorOverridesFlow: Flow<Map<String, Color>> = prefs.map { preferences ->
        parseStationColorOverrides(preferences[STATION_COLOR_OVERRIDES_KEY])
    }

    val satelliteColorOverridesFlow: Flow<Map<Int, Color>> = prefs.map { preferences ->
        parseSatelliteColorOverrides(preferences[SATELLITE_COLOR_OVERRIDES_KEY])
    }

    suspend fun setStationColor(code: String, color: Color) {
        context.appSettingsDataStore.edit { preferences ->
            val current = preferences[STATION_COLOR_OVERRIDES_KEY] ?: emptySet()
            preferences[STATION_COLOR_OVERRIDES_KEY] =
                current.filterNot { it.substringBeforeLast('|') == code }.toSet() + "$code|${color.toArgb()}"
        }
    }

    suspend fun clearStationColor(code: String) {
        context.appSettingsDataStore.edit { preferences ->
            val current = preferences[STATION_COLOR_OVERRIDES_KEY] ?: emptySet()
            preferences[STATION_COLOR_OVERRIDES_KEY] = current.filterNot { it.substringBeforeLast('|') == code }.toSet()
        }
    }

    suspend fun setSatelliteColor(noradId: Int, color: Color) {
        context.appSettingsDataStore.edit { preferences ->
            val current = preferences[SATELLITE_COLOR_OVERRIDES_KEY] ?: emptySet()
            preferences[SATELLITE_COLOR_OVERRIDES_KEY] =
                current.filterNot { it.substringBeforeLast('|') == noradId.toString() }.toSet() + "$noradId|${color.toArgb()}"
        }
    }

    suspend fun clearSatelliteColor(noradId: Int) {
        context.appSettingsDataStore.edit { preferences ->
            val current = preferences[SATELLITE_COLOR_OVERRIDES_KEY] ?: emptySet()
            preferences[SATELLITE_COLOR_OVERRIDES_KEY] =
                current.filterNot { it.substringBeforeLast('|') == noradId.toString() }.toSet()
        }
    }

    private fun parseStationColorOverrides(raw: Set<String>?): Map<String, Color> =
        raw.orEmpty().mapNotNull { entry ->
            val idx = entry.lastIndexOf('|')
            val argb = if (idx >= 0) entry.substring(idx + 1).toIntOrNull() else null
            if (argb != null) entry.substring(0, idx) to Color(argb) else null
        }.toMap()

    private fun parseSatelliteColorOverrides(raw: Set<String>?): Map<Int, Color> =
        raw.orEmpty().mapNotNull { entry ->
            val idx = entry.lastIndexOf('|')
            if (idx < 0) return@mapNotNull null
            val noradId = entry.substring(0, idx).toIntOrNull() ?: return@mapNotNull null
            val argb = entry.substring(idx + 1).toIntOrNull() ?: return@mapNotNull null
            noradId to Color(argb)
        }.toMap()
}
