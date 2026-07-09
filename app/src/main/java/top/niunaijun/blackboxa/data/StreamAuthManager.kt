package top.niunaijun.blackboxa.data

import android.content.Context
import android.content.SharedPreferences

object StreamAuthManager {
    private const val PREF_NAME = "MorphlyStreamPrefs"
    private const val KEY_IS_LOGGED_IN = "is_logged_in"
    private const val KEY_USER_NAME = "user_name"
    private const val KEY_USER_EMAIL = "user_email"
    private const val KEY_USER_PHONE = "user_phone"
    private const val KEY_USER_CREDITS = "user_credits"
    private const val KEY_USER_DEVICE_ID = "user_device_id"
    private const val KEY_USER_REFERRAL_CODE = "user_referral_code"
    private const val KEY_USER_TOKEN = "user_token"
    private val ID_TOKEN_PATTERN = Regex("^[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+$")

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    }

    private fun isUsableIdToken(token: String?): Boolean {
        val value = token?.trim() ?: return false
        if (value.contains("PRIVATE KEY", ignoreCase = true) ||
            value.contains("BEGIN ") ||
            value.contains('\n') ||
            value.contains('\r') ||
            value.startsWith("Bearer ", ignoreCase = true)
        ) {
            return false
        }
        return ID_TOKEN_PATTERN.matches(value)
    }

    private fun clearInvalidAuth(context: Context) {
        getPrefs(context).edit().apply {
            putBoolean(KEY_IS_LOGGED_IN, false)
            putString(KEY_USER_TOKEN, null)
            apply()
        }
    }

    private fun generateRandomHex(length: Int): String {
        val chars = "0123456789ABCDEF"
        return (1..length)
            .map { chars.random() }
            .joinToString("")
    }

    private fun generateDeviceId(): String {
        return "MP-${generateRandomHex(4)}-${generateRandomHex(4)}-${generateRandomHex(4)}"
    }

    /**
     * Checks if the user is currently authenticated/logged into the streaming flow.
     */
    fun isLoggedIn(context: Context): Boolean {
        val prefs = getPrefs(context)
        val token = prefs.getString(KEY_USER_TOKEN, null)
        val loggedIn = prefs.getBoolean(KEY_IS_LOGGED_IN, false)
        if (loggedIn && !isUsableIdToken(token)) {
            clearInvalidAuth(context)
            return false
        }
        return loggedIn
    }

    fun getUserReferralCode(context: Context): String {
        return getPrefs(context).getString(KEY_USER_REFERRAL_CODE, "N/A") ?: "N/A"
    }

    /**
     * Signs in the user by calling the Vercel API backend, which proxies to Supabase Auth.
     * Returns null on success, or an error string on failure.
     */
    fun login(context: Context, baseUrl: String, email: String, password: String): String? {
        try {
            val url = java.net.URL("$baseUrl/login")
            val connection = url.openConnection() as java.net.HttpURLConnection
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.doOutput = true
            connection.connectTimeout = 8000
            connection.readTimeout = 8000

            val payload = org.json.JSONObject().apply {
                put("email", email)
                put("password", password)
            }.toString()

            val os = connection.outputStream
            os.write(payload.toByteArray(charset("UTF-8")))
            os.close()

            val responseCode = connection.responseCode
            val stream = if (responseCode == 200) connection.inputStream else connection.errorStream
            val reader = java.io.BufferedReader(java.io.InputStreamReader(stream))
            val response = java.lang.StringBuilder()
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                response.append(line)
            }
            reader.close()

            val responseJson = org.json.JSONObject(response.toString())
            if (responseCode == 200) {
                val userJson = responseJson.getJSONObject("user")
                val name = userJson.getString("name")
                val balance = userJson.getInt("balance")
                val refCode = userJson.optString("referralCode", "")
                val token = responseJson.optString("token", "").trim()
                if (!isUsableIdToken(token)) {
                    return "Sign-in token is invalid. Please check the backend Firebase API key and sign in again."
                }

                val prefs = getPrefs(context)
                val existingEmail = prefs.getString(KEY_USER_EMAIL, "")

                prefs.edit().apply {
                    putBoolean(KEY_IS_LOGGED_IN, true)
                    putString(KEY_USER_NAME, name)
                    putString(KEY_USER_EMAIL, email)
                    putInt(KEY_USER_CREDITS, balance)
                    putString(KEY_USER_REFERRAL_CODE, refCode)
                    putString(KEY_USER_TOKEN, token)

                    if (existingEmail != email) {
                        putString(KEY_USER_DEVICE_ID, generateDeviceId())
                    } else {
                        if (prefs.getString(KEY_USER_DEVICE_ID, null) == null) {
                            putString(KEY_USER_DEVICE_ID, generateDeviceId())
                        }
                    }
                    apply()
                }
                return null // Success
            } else {
                return responseJson.optString("error", "Invalid credentials")
            }
        } catch (e: Exception) {
            e.printStackTrace()
            return e.message ?: "Connection error"
        }
    }

    /**
     * Registers a new user by calling the Vercel API backend, which proxies to Supabase Auth.
     * Returns null on success, or an error string on failure.
     */
    fun signUp(context: Context, baseUrl: String, name: String, email: String, phone: String, password: String, referredByCode: String? = null): String? {
        try {
            val url = java.net.URL("$baseUrl/signup")
            val connection = url.openConnection() as java.net.HttpURLConnection
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.doOutput = true
            connection.connectTimeout = 8000
            connection.readTimeout = 8000

            val payload = org.json.JSONObject().apply {
                put("name", name)
                put("email", email)
                put("phone", phone)
                put("password", password)
                if (!referredByCode.isNullOrEmpty()) {
                    put("referredByCode", referredByCode)
                }
            }.toString()

            val os = connection.outputStream
            os.write(payload.toByteArray(charset("UTF-8")))
            os.close()

            val responseCode = connection.responseCode
            val stream = if (responseCode == 200) connection.inputStream else connection.errorStream
            val reader = java.io.BufferedReader(java.io.InputStreamReader(stream))
            val response = java.lang.StringBuilder()
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                response.append(line)
            }
            reader.close()

            val responseJson = org.json.JSONObject(response.toString())
            if (responseCode == 200) {
                // Auto sign in locally on signup success
                val userJson = responseJson.optJSONObject("user")
                val refCode = userJson?.optString("referralCode", "") ?: ""
                val balance = userJson?.optInt("balance", 0) ?: 0
                val token = responseJson.optString("token", "").trim()

                if (!isUsableIdToken(token)) {
                    return login(context, baseUrl, email, password)
                }

                val prefs = getPrefs(context)
                prefs.edit().apply {
                    putBoolean(KEY_IS_LOGGED_IN, true)
                    putString(KEY_USER_NAME, name)
                    putString(KEY_USER_EMAIL, email)
                    putString(KEY_USER_PHONE, phone)
                    putInt(KEY_USER_CREDITS, balance)
                    putString(KEY_USER_REFERRAL_CODE, refCode)
                    putString(KEY_USER_TOKEN, token)
                    putString(KEY_USER_DEVICE_ID, generateDeviceId())
                    apply()
                }
                return null // Success
            } else {
                return responseJson.optString("error", "Registration failed")
            }
        } catch (e: Exception) {
            e.printStackTrace()
            return e.message ?: "Connection error"
        }
    }

    /**
     * Logs the user out and clears the local session.
     */
    fun logout(context: Context) {
        getPrefs(context).edit().apply {
            putBoolean(KEY_IS_LOGGED_IN, false)
            putString(KEY_USER_NAME, null)
            putString(KEY_USER_EMAIL, null)
            putString(KEY_USER_PHONE, null)
            putInt(KEY_USER_CREDITS, 0)
            putString(KEY_USER_DEVICE_ID, null)
            putString(KEY_USER_TOKEN, null)
            apply()
        }
    }


    fun getUserName(context: Context): String {
        return getPrefs(context).getString(KEY_USER_NAME, "User") ?: "User"
    }

    fun getUserEmail(context: Context): String {
        return getPrefs(context).getString(KEY_USER_EMAIL, "user@gmail.com") ?: "user@gmail.com"
    }

    fun getWalletBalance(context: Context): Int {
        return getPrefs(context).getInt(KEY_USER_CREDITS, 0)
    }

    fun getDeviceId(context: Context): String {
        val prefs = getPrefs(context)
        var devId = prefs.getString(KEY_USER_DEVICE_ID, null)
        if (devId == null) {
            devId = generateDeviceId()
            prefs.edit().putString(KEY_USER_DEVICE_ID, devId).apply()
        }
        return devId
    }

    fun getIdToken(context: Context): String? {
        val token = getPrefs(context).getString(KEY_USER_TOKEN, null)?.trim()
        if (!isUsableIdToken(token)) {
            clearInvalidAuth(context)
            return null
        }
        return token
    }

 

    fun addCredits(context: Context, amount: Int) {
        val prefs = getPrefs(context)
        val currentCredits = prefs.getInt(KEY_USER_CREDITS, 0)
        prefs.edit().putInt(KEY_USER_CREDITS, currentCredits + amount).apply()
    }
}
