package top.niunaijun.blackboxa.data

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

/** Uses the desktop's existing Firebase project and server-owned wallet. */
object FirebaseBackend {
    const val PROJECT_ID = "luckyweb-f546e"
    private var cachedApiKey: String? = null

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
                throw IOException(if (URL(url).host.endsWith(".googleapis.com")) authMessage(code) else body.optString("details", "Morphly request failed (HTTP $status)."))
            }
            return body
        } finally {
            connection.disconnect()
        }
    }

    @Synchronized
    fun apiKey(baseUrl: String): String {
        cachedApiKey?.let { return it }
        val base = URL(baseUrl)
        require(base.protocol == "https" && base.host == "live.morphly.fun") {
            "The Firebase configuration must come from Morphly's trusted server."
        }
        val config = request("${baseUrl.trimEnd('/')}/public-config")
        val firebase = config.optJSONObject("firebaseConfig")
            ?: throw IOException("Firebase configuration is unavailable.")
        check(config.optString("backend") == "firebase" && firebase.optString("projectId") == PROJECT_ID) {
            "The server is connected to a different Firebase project."
        }
        return firebase.optString("apiKey").takeIf { it.isNotBlank() }
            ?.also { cachedApiKey = it }
            ?: throw IOException("Firebase authentication is not configured.")
    }

    fun auth(baseUrl: String, operation: String, payload: JSONObject): JSONObject =
        request("https://identitytoolkit.googleapis.com/v1/accounts:$operation?key=${apiKey(baseUrl)}", payload)

    fun refresh(baseUrl: String, refreshToken: String): JSONObject {
        val connection = URL("https://securetoken.googleapis.com/v1/token?key=${apiKey(baseUrl)}")
            .openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 15_000
            connection.readTimeout = 20_000
            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            connection.doOutput = true
            val encoded = java.net.URLEncoder.encode(refreshToken, "UTF-8")
            connection.outputStream.use { it.write("grant_type=refresh_token&refresh_token=$encoded".toByteArray()) }
            val status = connection.responseCode
            val input = if (status in 200..299) connection.inputStream else connection.errorStream
            val body = JSONObject(input?.bufferedReader()?.use { it.readText() }.orEmpty().ifBlank { "{}" })
            if (status !in 200..299) throw IOException("Your Firebase session expired. Sign in again.")
            return JSONObject().put("idToken", body.getString("id_token"))
                .put("refreshToken", body.getString("refresh_token"))
                .put("expiresIn", body.optLong("expires_in", 3600))
        } finally {
            connection.disconnect()
        }
    }

    fun session(response: JSONObject, email: String, name: String): JSONObject = JSONObject()
        .put("token", response.getString("idToken"))
        .put("refreshToken", response.getString("refreshToken"))
        .put("expiresIn", response.optLong("expiresIn", 3600))
        .put("user", JSONObject().put("email", email)
            .put("name", response.optString("displayName").ifBlank { name }))

    private fun authMessage(code: String): String = when (code.substringBefore(" :")) {
        "INVALID_LOGIN_CREDENTIALS", "EMAIL_NOT_FOUND", "INVALID_PASSWORD" -> "Email or password is incorrect."
        "EMAIL_EXISTS" -> "This email already has an account. Sign in instead."
        "USER_DISABLED" -> "This account is disabled. Contact Morphly support."
        "TOO_MANY_ATTEMPTS_TRY_LATER" -> "Too many attempts. Please wait before trying again."
        "OPERATION_NOT_ALLOWED" -> "Email sign-in is not enabled for this Firebase project."
        "INVALID_EMAIL" -> "Enter a valid email address."
        else -> if (code.startsWith("WEAK_PASSWORD")) "Use a stronger password of at least six characters."
            else "The request failed. Please try again or contact Morphly support."
    }
}
