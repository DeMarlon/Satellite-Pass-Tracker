package com.example.eps_sgtracker.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * [classifyFetchFailure] decides whether a fetch failure means "CelesTrak said no" or "CelesTrak
 * never answered", and the two demand opposite handling: the first halts the whole refresh run to
 * stay off CelesTrak's 50-errors-in-2-hours firewall list, the second is retried and merely
 * reported.
 *
 * The reason this is worth a test at all is that [CelestrakHttpException] and
 * [CelestrakNoDataException] are themselves IOExceptions. Anyone simplifying the classifier into a
 * plain `catch (IOException)` would silently reclassify a 403 as unreachable, the halt would stop
 * firing, and the only symptom would appear days later as a blocked IP.
 */
class ClassifyFetchFailureTest {

    @Test
    fun `http error passes through untouched so the halt still fires`() {
        val original = CelestrakHttpException(403)

        assertSame(original, classifyFetchFailure(original))
    }

    @Test
    fun `every http status is preserved, not just the forbidden case`() {
        for (code in listOf(400, 403, 404, 429, 500, 503)) {
            val classified = classifyFetchFailure(CelestrakHttpException(code))

            assertTrue(
                "HTTP $code must stay a CelestrakHttpException",
                classified is CelestrakHttpException
            )
            assertEquals(code, (classified as CelestrakHttpException).code)
        }
    }

    @Test
    fun `empty GP array passes through untouched`() {
        // A 200 with no data is a real answer about one catalog number, not a reachability problem,
        // and must stay terminal for that satellite alone.
        val original = CelestrakNoDataException(99999)

        assertSame(original, classifyFetchFailure(original))
    }

    @Test
    fun `transport failures become unreachable`() {
        val transportFailures = listOf(
            UnknownHostException("celestrak.org"),
            ConnectException("Connection refused"),
            SocketTimeoutException("timeout"),
            // What OkHttp raises when the whole-call timeout expires.
            InterruptedIOException("timeout"),
            IOException("unexpected end of stream")
        )

        for (failure in transportFailures) {
            val classified = classifyFetchFailure(failure)

            assertTrue(
                "${failure.javaClass.simpleName} must be reported as unreachable",
                classified is CelestrakUnreachableException
            )
            assertSame("the original cause must be preserved", failure, classified.cause)
        }
    }
}
