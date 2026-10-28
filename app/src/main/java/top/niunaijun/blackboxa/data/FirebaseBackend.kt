package top.niunaijun.blackboxa.data

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

/** Talks to Morphly Android's own authenticated backend. */
object FirebaseBackend {
    const val PROJECT_ID = "nova-8fb74"

    fun request(url: String, payload: JSONObject? = null, token: String? = null): JSONObject {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 15_000
            connection.readTimeout = 20_000
            connection.instanceFollowRedirects = false
            connection.requestMethod = if (payload == null) "GET" else "POST"
            connection.setRequestProperty("Accept", "application/json")
            token?.let { connection.setRequestProperty("Authorization", "Bearer $it") }
            if (payload != null) {
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connection.doOutput = true
                connection.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
            }
            val status = connection.responseCode
            val input = if (status in 200..299) connection.inputStream else connection.errorStream
            val text = input?.bufferedReader()?.use { it.readText() }.orEmpty()
            val body = runCatching { JSONObject(text) }.getOrElse {
                throw IOException("Morphly returned an unexpected response (HTTP $status). Try again shortly.")
            }
            if (status !in 200..299) {
                val code = body.optJSONObject("error")?.optString("message")
                    ?: body.optString("error", body.optString("message", "Request failed (HTTP $status)"))
                throw IOException(if (URL(url).host.endsWith(".googleapis.com")) authMessage(code) else code)
            }
            return body
        } finally {
            connection.disconnect()
        }
    }

}
