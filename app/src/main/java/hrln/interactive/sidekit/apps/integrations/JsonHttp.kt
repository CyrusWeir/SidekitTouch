package hrln.interactive.sidekit.apps.integrations

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

fun interface JsonRequest {
    fun request(url: String, authorization: String?, body: JSONObject?): JSONObject
}

object JsonHttp : JsonRequest {
    override fun request(url: String, authorization: String?, body: JSONObject?): JSONObject {
        val connection = URL(url).openConnection() as HttpURLConnection
        require(connection.url.protocol == "https") { "HTTPS required" }
        connection.connectTimeout = 15_000; connection.readTimeout = 20_000
        connection.instanceFollowRedirects = false
        connection.setRequestProperty("Accept", "application/json")
        authorization?.let { connection.setRequestProperty("Authorization", it) }
        try {
            if (body != null) {
                connection.requestMethod = "POST"; connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write(body.toString().toByteArray()) }
            }
            val code = connection.responseCode
            if (code !in 200..299) throw IOException(when (code) {
                401, 403 -> "Access denied. Check your connection credentials."
                429 -> "Request limit reached. Try again later."
                else -> "Service unavailable (HTTP $code). Try again."
            })
            val bytes = connection.inputStream.use { readLimited(it, 8 * 1024 * 1024) }
            if (bytes.size > 8 * 1024 * 1024) throw IOException("Service response too large")
            return JSONObject(String(bytes, Charsets.UTF_8))
        } finally { connection.disconnect() }
    }
}

fun readLimited(input: java.io.InputStream, limit: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
    while (true) { val n = input.read(buffer); if (n < 0) break; if (out.size() + n > limit) throw IOException("Response exceeds size limit"); out.write(buffer, 0, n) }
    return out.toByteArray()
}
