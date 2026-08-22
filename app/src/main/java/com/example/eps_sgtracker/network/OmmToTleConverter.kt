package com.example.eps_sgtracker.network

import java.time.LocalDateTime
import java.util.Locale
import kotlin.math.abs

// Plain Kotlin mirror of a CelesTrak OMM (Orbit Mean-Elements Message) record - deliberately no
// org.json types here, so OmmToTleConverter can be exercised by plain JUnit tests without needing
// Robolectric or a JSON-mocking setup (org.json.* throws "not mocked" against the stub android.jar
// in a plain unit test). Parsing the actual org.json.JSONObject into this shape lives in
// CelestrakApi.kt.
data class OmmRecord(
    val objectName: String,
    val objectId: String?,
    val epoch: String,
    val meanMotionDot: Double,
    val meanMotionDdot: Double,
    val bstar: Double,
    val ephemerisType: Int,
    val classificationType: String,
    val noradCatId: Int,
    val elementSetNo: Int,
    val inclination: Double,
    val raOfAscNode: Double,
    val eccentricity: Double,
    val argOfPericenter: Double,
    val meanAnomaly: Double,
    val meanMotion: Double,
    val revAtEpoch: Int
)

/**
 * Synthesizes a classic fixed-width 3-line TLE text block from CelesTrak's OMM/JSON fields, so the
 * existing predict4java `TLE(String[])` parser - which only understands that fixed-column format
 * and has no concept of OMM - keeps working completely unmodified downstream.
 *
 * This exists because CelesTrak's own FORMAT=TLE endpoint stopped serving *any* data for catalog
 * numbers >= 100000 once the real-world catalog crossed that threshold: the legacy format's
 * catalog-number field is only 5 characters wide and physically cannot hold 6 digits. FORMAT=JSON
 * has no such limit and returns identical orbital data for every catalog number, so CelestrakApi
 * fetches JSON unconditionally and this converter reconstructs the text predict4java expects.
 *
 * Every field position below was verified column-by-column against predict4java 1.3.1's actual
 * TLE.java source (its constructor parses fixed substrings, e.g. `tle[1].substring(2, 7)` for the
 * catalog number) - not just against the public TLE spec - so this must stay in exact lockstep
 * with that library if it's ever upgraded.
 *
 * Every format call in this file passes Locale.US explicitly. The default-locale overloads render
 * numbers per the DEVICE's locale - a German/Italian/French phone turns "%08.4f" into "029,5998",
 * and predict4java's bare Double.parseDouble then throws, silently stranding every satellite (the
 * exception gets swallowed upstream). Integer formats are pinned too: "%d" is also
 * locale-sensitive (digit *shapes*, e.g. Eastern Arabic numerals), not just decimal separators.
 */
object OmmToTleConverter {

    fun toTleText(o: OmmRecord): String {
        val catField = formatCatalogField(o.noradCatId)
        val line1 = buildLine1(o, catField)
        val line2 = buildLine2(o, catField)
        return "${o.objectName.trim()}\n$line1\n$line2"
    }

    private fun buildLine1(o: OmmRecord, catField: String): String {
        val body = buildString {
            append("1 ")                                              // cols 1-2
            append(catField)                                          // cols 3-7
            append(o.classificationType.take(1).ifBlank { "U" })      // col 8
            append(" ")                                                // col 9
            append(formatIntlDesignator(o.objectId))                   // cols 10-17
            append(" ")                                                // col 18
            append(formatEpochField(o.epoch))                          // cols 19-32
            append(" ")                                                // col 33
            append(formatSignedDecimal(o.meanMotionDot))               // cols 34-43
            append(" ")                                                // col 44
            append(formatExpNotation(o.meanMotionDdot))                // cols 45-52
            append(" ")                                                // col 53
            append(formatExpNotation(o.bstar))                         // cols 54-61
            append(" ")                                                // col 62
            append(o.ephemerisType.toString().takeLast(1))             // col 63
            append(" ")                                                // col 64
            append("%04d".format(Locale.US, o.elementSetNo % 10000))   // cols 65-68
        }
        return finish(body)
    }

    private fun buildLine2(o: OmmRecord, catField: String): String {
        val body = buildString {
            append("2 ")                                 // cols 1-2
            append(catField)                              // cols 3-7
            append(" ")                                    // col 8
            append(formatAngleDeg(o.inclination))            // cols 9-16
            append(" ")                                       // col 17
            append(formatAngleDeg(o.raOfAscNode))               // cols 18-25
            append(" ")                                          // col 26
            append("%07d".format(Locale.US, Math.round(o.eccentricity * 1.0e7))) // cols 27-33
            append(" ")                                              // col 34
            append(formatAngleDeg(o.argOfPericenter))                 // cols 35-42
            append(" ")                                                // col 43
            append(formatAngleDeg(o.meanAnomaly))                       // cols 44-51
            append(" ")                                                  // col 52
            append(formatMeanMotion(o.meanMotion))                        // cols 53-63
            // The modulo is a defensive width guard, NOT a lossy narrowing: CelesTrak's OMM JSON
            // already publishes REV_AT_EPOCH wrapped to 5 digits, exactly as the legacy TLE format
            // did. Verified against live data - CATNR=25544 at epoch 2026-08-05T00:31:32.592 reads
            // REV_AT_EPOCH 57932, while ISS (launched 1998-11-20, ~15.5 rev/day) is really near
            // 157932. It rolled over around mid-2016 and the published field followed.
            //
            // So a satellite past 100000 revolutions genuinely reads 100000 low, and moving off the
            // TLE text format would NOT recover it - the true count never reaches this app at all.
            // Every other tracking tool shows the same wrapped number for the same reason, which
            // makes it the conventional value rather than a wrong one. This only keeps a malformed
            // or out-of-spec input from overflowing the five columns the field has.
            append("%05d".format(Locale.US, o.revAtEpoch % 100000))        // cols 64-68
        }
        return finish(body)
    }

    // predict4java's own catnum parsing (`Integer.parseInt(tle[1].substring(2, 7))`) is a bare
    // integer parse with no Alpha-5 awareness - the exact same limitation that made real Alpha-5
    // data (had CelesTrak served any) unparseable in the first place. This field is confirmed
    // unused anywhere downstream (grep for `catnum`/`getCatnum` in this app's source: zero
    // matches; the app always uses its own independently-tracked NORAD ID), so for catalog
    // numbers >= 100000 - which can't fit in 5 digits at all - this deliberately keeps the field
    // purely numeric (wrapping via modulo) rather than using a letter encoding, so it always
    // parses cleanly regardless of magnitude.
    private fun formatCatalogField(noradCatId: Int): String = "%05d".format(Locale.US, noradCatId % 100000)

    private fun formatIntlDesignator(objectId: String?): String {
        if (objectId.isNullOrBlank()) return " ".repeat(8)
        val parts = objectId.split("-")
        if (parts.size != 2 || parts[0].length < 2 || parts[1].length < 3) return " ".repeat(8)
        val yy = parts[0].takeLast(2)
        val nnn = parts[1].take(3)
        val piece = parts[1].drop(3)
        return "$yy$nnn$piece".padEnd(8).take(8)
    }

    // Cols 19-32: 2-digit epoch year immediately followed by fractional day-of-year (no separator
    // between them, matching the real format's contiguous "26204.83049110"-style field).
    private fun formatEpochField(epochIso: String): String {
        val ldt = LocalDateTime.parse(epochIso)
        val twoDigitYear = ldt.year % 100
        val secondsIntoDay = ldt.toLocalTime().toSecondOfDay().toDouble() + ldt.nano / 1.0e9
        val dayOfYearDouble = ldt.dayOfYear + secondsIntoDay / 86400.0
        return "%02d".format(Locale.US, twoDigitYear) + "%012.8f".format(Locale.US, dayOfYearDouble)
    }

    // Cols 34-43: signed decimal with no leading zero before the point, e.g. " .00009620".
    private fun formatSignedDecimal(value: Double): String {
        val sign = if (value < 0) "-" else " "
        val formatted = "%.8f".format(Locale.US, abs(value))
        // The field has no room for an integer part - the leading "0." is implied by the format.
        // Keeping only the fraction of a >= 1.0 input silently dropped the integer digits at full
        // field width, so 1.5 was read back downstream as 0.5 with nothing failing. The
        // startsWith check also catches the rounding edge (0.999999996 formats as "1.00000000").
        require(formatted.startsWith("0.")) {
            "Value $value has an integer part the TLE decimal field cannot express"
        }
        return "$sign." + formatted.substringAfter(".")
    }

    // 8-char TLE "implied decimal point" exponential notation used for both the second derivative
    // of mean motion and BSTAR: [sign][5-digit mantissa][exponent sign][1-digit exponent].
    private fun formatExpNotation(value: Double): String {
        val sign = if (value < 0) "-" else " "
        if (value == 0.0) return "${sign}00000+0"
        var mantissaFrac = abs(value)
        var exp = 0
        while (mantissaFrac >= 1.0) { mantissaFrac /= 10.0; exp++ }
        while (mantissaFrac < 0.1) { mantissaFrac *= 10.0; exp-- }
        var mantissa = Math.round(mantissaFrac * 100000.0).toInt()
        if (mantissa >= 100000) { mantissa /= 10; exp++ }
        // The field carries a SINGLE exponent digit, so only |exp| <= 9 is expressible. The
        // normalization loops above have no lower bound, so a value below 1e-10 produced a
        // two-digit exponent and a NINE-character field - which shifted every column after it and
        // made predict4java's fixed-substring parse read a different BSTAR entirely.
        //
        // Below the representable window the value is physically indistinguishable from zero for
        // SGP4, so encode zero rather than overflow the field.
        if (exp < -9) return "${sign}00000+0"
        // Above it the input is out of spec; fail loudly into TleRepository's retry-then-report
        // path rather than cache a numerically-corrupt element set, matching the policy
        // CelestrakApi already applies at the JSON boundary.
        require(exp <= 9) {
            "Exponent $exp exceeds the single digit the TLE field allows (value=$value)"
        }
        val expSign = if (exp < 0) "-" else "+"
        return "%s%05d%s%d".format(Locale.US, sign, mantissa, expSign, abs(exp))
    }

    // 8-char field used for inclination/RAAN/argument of perigee/mean anomaly: "XXX.XXXX".
    private fun formatAngleDeg(value: Double): String = "%08.4f".format(Locale.US, value)

    // 11-char field for mean motion: "XX.XXXXXXXX".
    private fun formatMeanMotion(value: Double): String = "%011.8f".format(Locale.US, value)

    // Appends the checksum and asserts the exact 69-character width both lines must have.
    //
    // This is the single cheapest guard in the file. predict4java parses by fixed substring
    // offsets, so ANY field overflowing its width silently shifts everything after it - and the
    // result is not a parse failure but a different, entirely plausible-looking number. A wrong
    // BSTAR yields pass predictions that are quietly minutes off with nothing to indicate it.
    // Checking the width once here covers every field at once, including any added later.
    private fun finish(body: String): String {
        val line = body + tleChecksum(body)
        require(line.length == 69) {
            "Synthesized TLE line is ${line.length} chars, expected 69: $line"
        }
        return line
    }

    // Standard TLE checksum: sum of all digits in the line (a bare '-' counts as 1, every other
    // non-digit character counts as 0), mod 10. predict4java doesn't actually validate this, but
    // it's cheap to get right and matches what a real TLE line looks like.
    private fun tleChecksum(line: String): Int {
        var sum = 0
        for (c in line) {
            sum += when {
                c.isDigit() -> c - '0'
                c == '-' -> 1
                else -> 0
            }
        }
        return sum % 10
    }
}
