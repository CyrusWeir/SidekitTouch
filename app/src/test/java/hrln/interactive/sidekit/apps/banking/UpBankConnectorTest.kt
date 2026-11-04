package hrln.interactive.sidekit.apps.banking

import hrln.interactive.sidekit.apps.integrations.JsonRequest
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class UpBankConnectorTest {
    private val account = """{"data":[{"id":"a","attributes":{"displayName":"Spending","balance":{"valueInBaseUnits":12345,"currencyCode":"AUD"}}}],"links":{"next":null}}"""
    private fun transaction(id: String, status: String) = """{"id":"$id","attributes":{"description":"Cafe","amount":{"valueInBaseUnits":-450,"currencyCode":"AUD"},"status":"$status","createdAt":"2026-11-04T12:00:00+11:00"},"relationships":{"account":{"data":{"id":"a"}}}}"""
    @Test fun `balances are authoritative pending transactions remain labelled and pages deduplicate`() = runTest {
        val calls = mutableListOf<String>()
        val connector = UpBankConnector("test-only", JsonRequest { url, auth, body ->
            calls += url; assertEquals("Bearer test-only", auth); assertNull(body)
            JSONObject(if (url.contains("/accounts")) account else if (url.contains("cursor")) """{"data":[${transaction("t1","HELD")},${transaction("t2","SETTLED")}],"links":{"next":null}}"""
            else """{"data":[${transaction("t1","HELD")}],"links":{"next":"https://api.up.com.au/api/v1/transactions?cursor=2"}}""")
        })
        val snapshot = connector.sync()
        assertEquals(12345L,snapshot.accounts.single().cents)
        assertEquals("HELD", snapshot.transactions.single().status)
        val more = connector.more(snapshot)
        assertEquals(2,more.transactions.size); assertNull(more.next)
        assertEquals(snapshot,UpSnapshot.parse(snapshot.json()))
        assertEquals(3,calls.size)
    }
    @Test fun `pagination cannot send a token to another host`() = runTest {
        var contacted = false
        val api = UpBankConnector("test-only", JsonRequest { _,_,_ -> contacted=true; JSONObject() })
        for (url in listOf("https://attacker.invalid/api/v1/transactions", "http://api.up.com.au/api/v1/transactions", "https://api.up.com.au:443/api/v1/transactions", "https://api.up.com.au@attacker.invalid/api/v1/transactions")) {
            try { api.more(UpSnapshot(emptyList(), emptyList(), url,0)); fail(url) } catch (_: IllegalArgumentException) {}
        }
        assertFalse(contacted)
    }
}
