package hrln.interactive.sidekit.apps.tripplanning

import hrln.interactive.sidekit.apps.integrations.JsonRequest
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.ZonedDateTime

@RunWith(RobolectricTestRunner::class)
class NswTransitTest {
    private fun fixture(name: String) = JSONObject(javaClass.getResource("/transit/$name.json")!!.readText())
    @Test fun `392Fan service resolves stops and keeps real planned and estimated departures separate`() = runTest {
        var url = ""
        val api = NswTransit(JsonRequest { path, auth, body ->
            url = path; assertNull(auth); assertNull(body)
            fixture(if (path.contains("stop-finder")) "stops" else "departures")
        }, { ZonedDateTime.parse("2026-11-04T12:00:00+11:00[Australia/Sydney]") })
        assertTrue(api.stops("Central").isNotEmpty())
        assertTrue(url.contains("name=Central"))
        val departures = api.departures(TransitStop("G203519", "Monash Gardens", "stop"))
        assertTrue(url.contains("date=20261104&time=1200"))
        assertFalse(url.contains("20261104+"))
        assertTrue(departures.isNotEmpty())
        assertEquals("392", departures.first().route)
        assertFalse(departures.first().live)
        assertEquals("12:01", transitTime(departures.first().planned))
        assertEquals("04 Nov 12:02 – 04 Nov 12:05", savedTripTime("2026-11-04T01:02:30Z – 2026-11-04T01:05:18Z"))
    }
    @Test fun `journeys send selected stop ids and Sydney departure time and decode actual API response`() = runTest {
        val api = NswTransit(JsonRequest { url, auth, body ->
            assertEquals("https://transportnsw.info/api/graphql", url); assertNull(auth)
            val input = body!!.getJSONObject("variables").getJSONObject("input")
            assertEquals("10101100", input.getString("from")); assertEquals("10101101", input.getString("to"))
            assertEquals("2026-11-04T12:00+11:00", input.getString("departureDateTime"))
            fixture("journeys")
        })
        val journeys = api.journeys(TransitStop("10101100", "Central", "stop"),TransitStop("10101101", "Town Hall", "stop"),"2026-11-04 12:00")
        assertEquals(5, journeys.size); assertEquals("T8", journeys.first().legs.first().route)
        assertEquals("Walk",journeys.last().legs.single().route)
        assertFalse(journeys.first().live)
    }
    @Test fun `GraphQL errors are not presented as an empty success`() = runTest {
        val api = NswTransit(JsonRequest { _, _, _ -> JSONObject("{errors:[{message:'backend error'}]}") })
        try { api.journeys(TransitStop("a","A","stop"),TransitStop("b","B","stop"),""); fail() }
        catch (expected: java.io.IOException) { assertTrue(expected.message!!.contains("could not plan")) }
    }
}
