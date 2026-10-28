package top.niunaijun.blackboxa.data

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

object StreamAuthManager {
    private const val PREF_NAME = "MorphlyStreamPrefs"
    private const val KEYSTORE_ALIAS = "MorphlySessionKeyV1"
    private const val KEY_IS_LOGGED_IN = "is_logged_in"
    private const val KEY_USER_NAME = "user_name"
    private const val KEY_USER_EMAIL = "user_email"
    private const val KEY_USER_PHONE = "user_phone"
    private const val KEY_USER_CREDITS = "user_credits"
    private const val KEY_USER_DEVICE_ID = "user_device_id"
    private const val KEY_USER_REFERRAL_CODE = "user_referral_code"
    private const val KEY_USER_TOKEN = "user_token_enc"
    private const val KEY_REFRESH_TOKEN = "refresh_token_enc"
    private const val KEY_TOKEN_EXPIRES_AT = "token_expires_at"
    private const val LEGACY_TOKEN_KEY = "user_token"
    private val JWT_PATTERN = Regex("^[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+$")
    private val secureRandom = SecureRandom()

    private fun getPrefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    private fun sessionKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(KEYSTORE_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            "AndroidKeyStore"
        )
        generator.init(
            KeyGenParameterSpec.Builder(
                KEYSTORE_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, sessionKey())
        val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        val combined = ByteArray(cipher.iv.size + encrypted.size)
        System.arraycopy(cipher.iv, 0, combined, 0, cipher.iv.size)
        System.arraycopy(encrypted, 0, combined, cipher.iv.size, encrypted.size)
        return Base64.encodeToString(combined, Base64.NO_WRAP)
    }

    private fun decrypt(value: String?): String? {
        if (value.isNullOrBlank()) return null
        return try {
            val combined = Base64.decode(value, Base64.NO_WRAP)
            if (combined.size <= 12) return null
            val iv = combined.copyOfRange(0, 12)
            val encrypted = combined.copyOfRange(12, combined.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, sessionKey(), GCMParameterSpec(128, iv))
            String(cipher.doFinal(encrypted), Charsets.UTF_8)
        } catch (_: Exception) {
            null
        }
    }

    private fun usableAccessToken(token: String?): Boolean {
        val value = token?.trim() ?: return false
        return !value.contains('\n') &&
            !value.contains('\r') &&
            !value.startsWith("Bearer ", ignoreCase = true) &&
            JWT_PATTERN.matches(value) && runCatching {
                val claims = JSONObject(String(Base64.decode(value.split('.')[1], Base64.URL_SAFE or Base64.NO_WRAP), Charsets.UTF_8))
                claims.optString("iss") == "https://securetoken.google.com/${FirebaseBackend.PROJECT_ID}" &&
                    claims.optString("aud") == FirebaseBackend.PROJECT_ID
            }.getOrDefault(false)
    }

    private fun clearInvalidAuth(context: Context) {
        getPrefs(context).edit()
            .putBoolean(KEY_IS_LOGGED_IN, false)
            .remove(KEY_USER_TOKEN)
            .remove(KEY_REFRESH_TOKEN)
            .remove(KEY_TOKEN_EXPIRES_AT)
            .remove(LEGACY_TOKEN_KEY)
            .apply()
    }

    private fun randomHex(bytes: Int): String {
        val value = ByteArray(bytes)
        secureRandom.nextBytes(value)
        return value.joinToString("") { "%02X".format(it) }
    }

    private fun generateDeviceId(): String =
        "MP-${randomHex(2)}-${randomHex(2)}-${randomHex(2)}"

    private fun readResponse(connection: HttpURLConnection): Pair<Int, JSONObject> {
        val code = connection.responseCode
        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
        val text = stream?.let {
            BufferedReader(InputStreamReader(it)).use(BufferedReader::readText)
        }.orEmpty()
        return code to JSONObject(if (text.isBlank()) "{}" else text)
    }

    private fun saveSession(
        context: Context,
        response: JSONObject,
        fallbackEmail: String,
        fallbackName: String? = null,
        fallbackPhone: String? = null
    ) {
        val token = response.optString("token").trim()
        val refreshToken = response.optString("refreshToken").trim()
        if (!usableAccessToken(token) || refreshToken.isBlank()) {
            throw IllegalStateException("The server returned an invalid session.")
        }
        val user = response.optJSONObject("user") ?: JSONObject()
        val expiresAtSeconds = response.optLong(
            "expiresAt",
            (System.currentTimeMillis() / 1000L) + response.optLong("expiresIn", 3600L)
        )
        val prefs = getPrefs(context)
        val email = user.optString("email", fallbackEmail).ifBlank { fallbackEmail }.lowercase()
        val existingEmail = prefs.getString(KEY_USER_EMAIL, "")
        prefs.edit().apply {
            putBoolean(KEY_IS_LOGGED_IN, true)
            putString(KEY_USER_NAME, user.optString("name", fallbackName ?: email.substringBefore('@')))
            putString(KEY_USER_EMAIL, email)
            putString(KEY_USER_PHONE, user.optString("phone", fallbackPhone ?: ""))
            putInt(KEY_USER_CREDITS, user.optInt("balance", if (getUserEmail(context) == email) getWalletBalance(context) else 0).coerceAtLeast(0))
            putString(KEY_USER_REFERRAL_CODE, user.optString("referralCode", ""))
            putString(KEY_USER_TOKEN, encrypt(token))
            putString(KEY_REFRESH_TOKEN, encrypt(refreshToken))
            putLong(KEY_TOKEN_EXPIRES_AT, expiresAtSeconds)
            remove(LEGACY_TOKEN_KEY)
            if (existingEmail != email || prefs.getString(KEY_USER_DEVICE_ID, null) == null) {
                putString(KEY_USER_DEVICE_ID, generateDeviceId())
            }
            apply()
        }
    }

    fun isLoggedIn(context: Context): Boolean {
        val prefs = getPrefs(context)
        if (!prefs.getBoolean(KEY_IS_LOGGED_IN, false)) return false
        if (!usableAccessToken(decrypt(prefs.getString(KEY_USER_TOKEN, null)))) {
            clearInvalidAuth(context)
            return false
        }
        return true
    }

    fun login(context: Context, baseUrl: String, email: String, password: String): String? {
        return try {
            val response = FirebaseBackend.auth(baseUrl, "signInWithPassword", JSONObject()
                .put("email", email.trim()).put("password", password).put("returnSecureToken", true))
            val normalized = FirebaseBackend.session(response, email.trim(), email.substringBefore('@'))
            val token = normalized.getString("token")
            FirebaseBackend.request("$baseUrl/firebase-register", JSONObject(), token)
            saveSession(context, normalized, email.trim())
            refreshWallet(context, baseUrl, token)
            null
        } catch (error: Exception) {
            error.message ?: "Unable to reach Firebase. Check your connection."
        }
    }

    fun signUp(context: Context, baseUrl: String, name: String, email: String,
               phone: String, password: String, referredByCode: String? = null): String? {
        return try {
            val response = FirebaseBackend.auth(baseUrl, "signUp", JSONObject()
                .put("email", email.trim()).put("password", password).put("returnSecureToken", true))
            FirebaseBackend.auth(baseUrl, "update", JSONObject()
                .put("idToken", response.getString("idToken")).put("displayName", name))
            val normalized = FirebaseBackend.session(response, email.trim(), name)
            val token = normalized.getString("token")
            // The desktop endpoint provisions accounts idempotently in the existing database.
            FirebaseBackend.request("$baseUrl/firebase-register", JSONObject()
                .put("referralCode", referredByCode.orEmpty()), token)
            saveSession(context, normalized, email.trim(), name, phone)
            refreshWallet(context, baseUrl, token)
            null
        } catch (error: Exception) {
            error.message ?: "Registration could not finish. Sign in to retry account setup."
        }
    }

    private fun refreshWallet(context: Context, baseUrl: String, token: String) {
        runCatching {
            val wallet = FirebaseBackend.request("$baseUrl/wallet", token = token)
            setWalletBalance(context, wallet.getDouble("credits").toInt())
        }
    }

    fun requestPasswordReset(baseUrl: String, email: String): String? = try {
        FirebaseBackend.auth(baseUrl, "sendOobCode", JSONObject()
            .put("requestType", "PASSWORD_RESET").put("email", email.trim()))
        null
    } catch (error: Exception) {
        error.message ?: "Could not send a password reset link."
    }

    @Synchronized
    fun getValidIdToken(context: Context, baseUrl: String): String? {
        val prefs = getPrefs(context)
        val current = decrypt(prefs.getString(KEY_USER_TOKEN, null))
        val expiresAt = prefs.getLong(KEY_TOKEN_EXPIRES_AT, 0L)
        if (usableAccessToken(current) && expiresAt > System.currentTimeMillis() / 1000L + 60L) return current
        val refreshToken = decrypt(prefs.getString(KEY_REFRESH_TOKEN, null)) ?: return null
        return try {
            val refreshed = FirebaseBackend.refresh(baseUrl, refreshToken)
            val normalized = FirebaseBackend.session(refreshed, getUserEmail(context), getUserName(context))
            saveSession(context, normalized, getUserEmail(context), getUserName(context))
            normalized.getString("token")
        } catch (_: Exception) {
            if (usableAccessToken(current) && expiresAt > System.currentTimeMillis() / 1000L) current else null
        }
    }

    fun logout(context: Context) {
        val deviceId = getDeviceId(context)
        getPrefs(context).edit()
            .clear()
            .putString(KEY_USER_DEVICE_ID, deviceId)
            .apply()
    }

    fun getUserName(context: Context): String =
        getPrefs(context).getString(KEY_USER_NAME, "User") ?: "User"

    fun getUserEmail(context: Context): String =
        getPrefs(context).getString(KEY_USER_EMAIL, "") ?: ""

    fun getUserReferralCode(context: Context): String =
        getPrefs(context).getString(KEY_USER_REFERRAL_CODE, "N/A") ?: "N/A"

    fun getWalletBalance(context: Context): Int =
        getPrefs(context).getInt(KEY_USER_CREDITS, 0)

    fun setWalletBalance(context: Context, balance: Int) {
        getPrefs(context).edit().putInt(KEY_USER_CREDITS, balance.coerceAtLeast(0)).apply()
    }

    fun updateUserProfile(
        context: Context,
        name: String? = null,
        email: String? = null,
        phone: String? = null,
        referralCode: String? = null
    ) {
        getPrefs(context).edit().apply {
            if (!name.isNullOrBlank()) putString(KEY_USER_NAME, name)
            if (!email.isNullOrBlank()) putString(KEY_USER_EMAIL, email)
            if (!phone.isNullOrBlank()) putString(KEY_USER_PHONE, phone)
            if (!referralCode.isNullOrBlank()) putString(KEY_USER_REFERRAL_CODE, referralCode)
            apply()
        }
    }

    fun getDeviceId(context: Context): String {
        val prefs = getPrefs(context)
        return prefs.getString(KEY_USER_DEVICE_ID, null)
            ?: generateDeviceId().also { prefs.edit().putString(KEY_USER_DEVICE_ID, it).apply() }
    }

    fun getIdToken(context: Context): String? {
        val token = decrypt(getPrefs(context).getString(KEY_USER_TOKEN, null))
        if (!usableAccessToken(token)) {
            clearInvalidAuth(context)
            return null
        }
        return token
    }

    fun addCredits(context: Context, amount: Int) {
        setWalletBalance(context, getWalletBalance(context) + amount)
    }
}
