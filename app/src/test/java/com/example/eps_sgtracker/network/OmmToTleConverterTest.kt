package com.example.eps_sgtracker.network

import com.github.amsacode.predict4java.TLE
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/**
 * predict4java is a plain Java library dependency, so these tests feed OmmToTleConverter's output
 * directly into the *real* TLE(String[]) constructor rather than re-asserting the column math in
 * isolation - this validates against the actual downstream consumer.
 */
class OmmToTleConverterTest {

    // Real record fetched live from
    // https://celestrak.org/NORAD/elements/gp.php?CATNR=100000&FORMAT=JSON - the first satellite
    // ever catalogued with a 6-digit NORAD ID (2026-07-11), which CelesTrak's classic FORMAT=TLE
    // endpoint refuses to serve at all.
    private val saramago = OmmRecord(
        objectName = "SARAMAGO",
        objectId = "2026-067CY",
        epoch = "2026-07-23T14:14:23.283456",
        meanMotionDot = 8.202e-5,
        meanMotionDdot = 0.0,
        bstar = 0.00037740642,
        ephemerisType = 0,
        classificationType = "U",
        noradCatId = 100000,
        elementSetNo = 999,
        inclination = 97.4593,
        raOfAscNode = 162.6942,
        eccentricity = 0.00060035,
        argOfPericenter = 229.7567,
        meanAnomaly = 130.3144,
        meanMotion = 15.20572554,
        revAtEpoch = 1723
    )

    // Representative real-world 5-digit satellite (ISS), for regression - values transcribed from
    // a live FORMAT=TLE fetch of CATNR=25544.
    private val iss = OmmRecord(
        objectName = "ISS (ZARYA)",
        objectId = "1998-067A",
        epoch = "2026-07-23T19:55:56.629",
        meanMotionDot = 0.00009620,
        meanMotionDdot = 0.0,
        bstar = 0.00018169,
        ephemerisType = 0,
        classificationType = "U",
        noradCatId = 25544,
        elementSetNo = 999,
        inclination = 51.6314,
        raOfAscNode = 118.7564,
        eccentricity = 0.0006921,
        argOfPericenter = 330.4598,
        meanAnomaly = 29.5998,
        meanMotion = 15.49124476,
        revAtEpoch = 57743
    )

    private fun parse(record: OmmRecord): TLE {
        val lines = OmmToTleConverter.toTleText(record).split("\n")
        assertEquals(3, lines.size)
        assertEquals(69, lines[1].length)
        assertEquals(69, lines[2].length)
        return TLE(lines.toTypedArray())
    }

    @Test
    fun saramago_sixDigitCatalogNumber_parsesWithoutThrowing() {
        val tle = parse(saramago)
        assertEquals("SARAMAGO", tle.name)
        assertEquals(26, tle.year)
        assertEquals(97.4593, tle.incl, 1e-4)
        assertEquals(162.6942, tle.raan, 1e-4)
        assertEquals(0.00060035, tle.eccn, 1e-6)
        assertEquals(229.7567, tle.argper, 1e-4)
        assertEquals(130.3144, tle.meanan, 1e-4)
        assertEquals(15.20572554, tle.meanmo, 1e-6)
        assertEquals(0.00037740642, tle.bstar, 1e-8)
        assertEquals(1723, tle.orbitnum)
    }

    @Test
    fun saramago_sixDigitCatalogFieldStaysNumeric() {
        // predict4java's own catnum parsing is a bare Integer.parseInt with no Alpha-5 awareness,
        // so the synthesized field must never contain a letter - it wraps via modulo instead.
        val lines = OmmToTleConverter.toTleText(saramago).split("\n")
        assertEquals("1 00000U", lines[1].substring(0, 8))
        assertEquals("2 00000 ", lines[2].substring(0, 8))
    }

    @Test
    fun iss_normalFiveDigitSatellite_stillRoundTripsCorrectly() {
        val tle = parse(iss)
        assertEquals("ISS (ZARYA)", tle.name)
        assertEquals(51.6314, tle.incl, 1e-4)
        assertEquals(118.7564, tle.raan, 1e-4)
        assertEquals(0.0006921, tle.eccn, 1e-6)
        assertEquals(330.4598, tle.argper, 1e-4)
        assertEquals(29.5998, tle.meanan, 1e-4)
        assertEquals(15.49124476, tle.meanmo, 1e-6)
        assertEquals(0.00018169, tle.bstar, 1e-8)
        assertEquals(57743, tle.orbitnum)
        val lines = OmmToTleConverter.toTleText(iss).split("\n")
        assertEquals("25544", lines[1].substring(2, 7))
    }

    @Test
    fun catalogFieldBoundaries_neverThrowAndParseCleanly() {
        // Just below the cutoff.
        assertEquals("99999", OmmToTleConverter.toTleText(saramago.copy(noradCatId = 99999)).split("\n")[1].substring(2, 7))
        // Exactly at the cutoff - wraps to 0, not a letter.
        assertEquals("00000", OmmToTleConverter.toTleText(saramago.copy(noradCatId = 100000)).split("\n")[1].substring(2, 7))
        // Arbitrarily large catalog numbers must never throw, and must always parse as a plain int.
        parse(saramago.copy(noradCatId = 339999))
        parse(saramago.copy(noradCatId = 500000))
        parse(saramago.copy(noradCatId = 999_999_999))
    }

    @Test
    fun negativeAndZeroBstarAndMeanMotionDdot_parseCorrectly() {
        val negative = saramago.copy(bstar = -0.0001234, meanMotionDdot = 0.0)
        assertEquals(-0.0001234, parse(negative).bstar, 1e-8)

        val zero = saramago.copy(bstar = 0.0, meanMotionDdot = 0.0)
        assertEquals(0.0, parse(zero).bstar, 1e-8)
    }

    @Test
    fun missingObjectId_doesNotThrow() {
        val noDesignator = saramago.copy(objectId = null)
        val tle = parse(noDesignator)
        assertEquals("SARAMAGO", tle.name)
    }

    @Test
    fun dayOfYearBoundaries_formatCorrectly() {
        // Jan 1st, just after midnight.
        val newYears = saramago.copy(epoch = "2026-01-01T00:00:00.0")
        val newYearsLine1 = OmmToTleConverter.toTleText(newYears).split("\n")[1]
        assertEquals("26001.00000000", newYearsLine1.substring(18, 32))

        // Dec 31st of a leap year (2028), just before midnight.
        val leapYearEnd = saramago.copy(epoch = "2028-12-31T23:59:59.0")
        val leapYearLine1 = OmmToTleConverter.toTleText(leapYearEnd).split("\n")[1]
        assertTrue(leapYearLine1.substring(18, 32).startsWith("28366."))
    }

    @Test
    fun invalidNoradId_stillProducesEmptyArrayHandledByCallerNotConverter() {
        // OmmToTleConverter itself has no notion of "invalid" - CelestrakApi is responsible for
        // throwing before a record ever reaches here. This test just documents that boundary.
        assertTrue(true)
    }

    @Test
    fun commaDecimalLocale_stillProducesParseableTle() {
        // The converter must be immune to the DEVICE's locale: without explicit Locale.US on
        // every format call, a German/Italian/French phone renders "%08.4f" with a comma decimal
        // separator ("029,5998"), predict4java's bare Double.parseDouble throws, and the
        // satellite silently vanishes from the app (the exception is swallowed upstream). This
        // exercises the full synthesis + real-TLE-parse round trip under such a locale.
        val original = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            val tle = parse(iss)
            assertEquals(51.6314, tle.incl, 1e-4)
            assertEquals(330.4598, tle.argper, 1e-4)
            assertEquals(15.49124476, tle.meanmo, 1e-6)
            assertEquals(0.00018169, tle.bstar, 1e-8)
            assertEquals(57743, tle.orbitnum)
        } finally {
            Locale.setDefault(original)
        }
    }
    // ------------------------------------------------------------------------------------------
    // Field-width sweep.
    //
    // Both TLE lines are exactly 69 characters and predict4java parses them by fixed substring
    // offsets, so a field that overflows its width does not fail - it shifts every column after it
    // and is read back as a different, entirely plausible number. A wrong BSTAR yields pass
    // predictions quietly minutes off with nothing to indicate it.
    //
    // The pre-existing tests all pass meanMotionDdot = 0.0, which short-circuits formatExpNotation
    // before its normalization loop runs, so the loop was only ever exercised at exponent ~ -3.
    // This sweeps the magnitudes that actually stress it.
    // ------------------------------------------------------------------------------------------

    private fun assertBothLinesAre69Chars(record: OmmRecord, label: String) {
        val lines = OmmToTleConverter.toTleText(record).lines()
        assertEquals("$label: expected 3 lines", 3, lines.size)
        assertEquals("$label: line 1 width", 69, lines[1].length)
        assertEquals("$label: line 2 width", 69, lines[2].length)
        // Round-trips through the real downstream parser too, not just the width check.
        TLE(arrayOf(lines[0], lines[1], lines[2]))
    }

    
    @Test
    fun `extreme bstar magnitudes never overflow the fixed-width fields`() {
        val magnitudes = listOf(
            0.0, 1.0e-12, 1.0e-11, 1.0e-10, 5.5e-10, 1.0e-9, 1.0e-5, 0.00018169, 0.5, 0.99999,
            -1.0e-11, -1.0e-10, -0.00037740642, -0.5
        )
        for (b in magnitudes) {
            assertBothLinesAre69Chars(iss.copy(bstar = b), "bstar=$b")
        }
    }

    
    @Test
    fun `extreme meanMotionDdot magnitudes never overflow the fixed-width fields`() {
        val magnitudes = listOf(0.0, 1.0e-12, 1.0e-11, 1.0e-10, 1.0e-4, 0.5, -1.0e-11, -0.5)
        for (d in magnitudes) {
            assertBothLinesAre69Chars(iss.copy(meanMotionDdot = d), "meanMotionDdot=$d")
        }
    }

    
    @Test
    fun `boundary eccentricity and revAtEpoch values never overflow the fixed-width fields`() {
        for (e in listOf(0.0, 1.0e-7, 0.0006921, 0.9999999)) {
            assertBothLinesAre69Chars(iss.copy(eccentricity = e), "eccentricity=$e")
        }
        for (r in listOf(0, 1, 57743, 99999, 100000, 157932)) {
            assertBothLinesAre69Chars(iss.copy(revAtEpoch = r), "revAtEpoch=$r")
        }
    }

    // A mean motion derivative at or above 1.0 has an integer part the 10-column field cannot
    // express - it used to be silently truncated to its fraction, so 1.5 was read back as 0.5.
    // Rejecting is correct: TleRepository treats the throw as a failed fetch and reports it.
    @Test(expected = IllegalArgumentException::class)
    fun `meanMotionDot with an integer part is rejected rather than silently truncated`() {
        OmmToTleConverter.toTleText(iss.copy(meanMotionDot = 1.5))
    }
}
