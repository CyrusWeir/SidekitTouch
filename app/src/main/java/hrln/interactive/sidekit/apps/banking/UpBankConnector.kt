package hrln.interactive.sidekit.apps.banking

import hrln.interactive.sidekit.apps.integrations.JsonHttp
import hrln.interactive.sidekit.apps.integrations.JsonRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.io.IOException

data class UpAccount(val id: String, val name: String, val cents: Long, val currency: String)
data class UpTransaction(val id: String, val accountId: String, val description: String, val cents: Long, val currency: String, val status: String, val createdAt: String)
data class UpSnapshot(val accounts: List<UpAccount>, val transactions: List<UpTransaction>, val next: String?, val at: Long) {
    fun json() = JSONObject().put("accounts", JSONArray(accounts.map { JSONObject().put("id",it.id).put("name",it.name).put("cents",it.cents).put("currency",it.currency) }))
        .put("transactions", JSONArray(transactions.map { JSONObject().put("id",it.id).put("accountId",it.accountId).put("description",it.description).put("cents",it.cents).put("currency",it.currency).put("status",it.status).put("createdAt",it.createdAt) })).put("at",at).put("next", next)
    companion object {
        fun parse(j: JSONObject): UpSnapshot = UpSnapshot(j.getJSONArray("accounts").objects().map { UpAccount(it.getString("id"),it.getString("name"),it.getLong("cents"),it.getString("currency")) }, j.getJSONArray("transactions").objects().map { UpTransaction(it.getString("id"),it.getString("accountId"),it.getString("description"),it.getLong("cents"),it.getString("currency"),it.getString("status"),it.getString("createdAt")) }, j.optString("next").takeUnless { it.isBlank() || it == "null" }, j.getLong("at"))
    }
}

/** Personal Up API. GET only; there is no payment or transfer operation in this connector. */
class UpBankConnector(private val token: String, private val http: JsonRequest = JsonHttp) {
    suspend fun sync(): UpSnapshot = withContext(Dispatchers.IO) {
        require(token.isNotBlank()) { "Enter your Up personal token" }
        val accounts = mutableListOf<UpAccount>()
        var url: String? = "$BASE/accounts?page%5Bsize%5D=100"
        val visited = mutableSetOf<String>()
        while (url != null) {
            if (!visited.add(url) || visited.size > 100) throw IOException("Invalid account pagination")
            val page = get(url)
            accounts += page.getJSONArray("data").objects().map { a -> val v = a.getJSONObject("attributes"); val b = v.getJSONObject("balance")
                UpAccount(a.getString("id"), v.getString("displayName"), b.getLong("valueInBaseUnits"), b.getString("currencyCode")) }
            url = next(page)
        }
        val page = get("$BASE/transactions?page%5Bsize%5D=100")
        UpSnapshot(accounts.distinctBy { it.id }, transactions(page), next(page), System.currentTimeMillis())
    }
    suspend fun more(snapshot: UpSnapshot): UpSnapshot = withContext(Dispatchers.IO) {
        val url = snapshot.next ?: return@withContext snapshot
        val page = get(url)
        snapshot.copy(transactions = (snapshot.transactions + transactions(page)).distinctBy { it.id }, next = next(page))
    }
    private fun get(url: String): JSONObject {
        val uri = URI(url)
        require(uri.scheme == "https" && uri.host == "api.up.com.au" && uri.port == -1 && uri.userInfo == null && uri.path.startsWith("/api/v1/")) { "Invalid Up pagination URL" }
        return http.request(url, "Bearer ${token.trim()}", null)
    }
    private fun transactions(page: JSONObject) = page.getJSONArray("data").objects().map { t ->
        val a = t.getJSONObject("attributes"); val amount = a.getJSONObject("amount")
        UpTransaction(t.getString("id"), t.getJSONObject("relationships").getJSONObject("account").getJSONObject("data").getString("id"), a.getString("description"), amount.getLong("valueInBaseUnits"), amount.getString("currencyCode"), a.getString("status"), a.getString("createdAt"))
    }
    private fun next(page: JSONObject) = page.optJSONObject("links")?.optString("next")?.takeUnless { it.isBlank() || it == "null" }
    companion object { const val BASE = "https://api.up.com.au/api/v1" }
}
private fun JSONArray.objects() = List(length()) { getJSONObject(it) }
