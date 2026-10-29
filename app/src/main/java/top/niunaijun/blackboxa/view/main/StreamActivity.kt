package top.niunaijun.blackboxa.view.main

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.animation.Animation
import android.view.animation.LinearInterpolator
import android.view.animation.RotateAnimation
import android.view.animation.ScaleAnimation
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.vcam.VirtualCameraBridge
import org.json.JSONObject
import top.niunaijun.blackboxa.BuildConfig
import top.niunaijun.blackboxa.R
import top.niunaijun.blackboxa.databinding.ActivityStreamBinding
import top.niunaijun.blackboxa.data.XmaxRealtimeBridge
import top.niunaijun.blackboxa.data.StreamAuthManager
import top.niunaijun.blackboxa.data.StreamPreviewBridge
import top.niunaijun.blackboxa.data.StreamSessionController
import top.niunaijun.blackboxa.view.base.BaseActivity

class StreamActivity : BaseActivity() {

    private lateinit var binding: ActivityStreamBinding

    private var cameraDevice: android.hardware.camera2.CameraDevice? = null
    private var captureSession: android.hardware.camera2.CameraCaptureSession? = null
    private var backgroundThread: android.os.HandlerThread? = null
    private var backgroundHandler: android.os.Handler? = null

    private var isStreaming = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private val TAG_STREAM_LIFECYCLE = "STREAM_LIFECYCLE"

    private val BACKEND_BASE_URL = BuildConfig.BACKEND_BASE_URL.trimEnd('/')
    private val DECART_REALTIME_MODEL = "x2.1"
    private val REQUEST_DECART_PERMISSIONS = 2202
    private val networkExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()
    private var selectedFaceImageUri: Uri? = null
    private var isFullscreenCamera = false
    @Volatile private var currentStreamSessionId: String = ""
    private var currentStreamModel: String = ""
    private var creditUsageSequence = 0L
    private var consecutiveCreditSyncFailures = 0
    @Volatile private var isCreditSyncInFlight = false

    private var selectedPlanName: String = "Test"
    private var selectedPlanPrice: Double = 10.0
    private var selectedPlanCredits: Int = 500
    private var selectedPlanStreamTime: String = "~4 min"

    private fun formatNaira(amount: Double): String {
        return "\u20A6" + String.format("%,.2f", amount)
    }

    private fun formatNairaWhole(amount: Int): String {
        return "\u20A6" + String.format("%,d", amount)
    }

    private var serverClockOffsetMs = 0L
    private var lastUsageEpoch = 0L
    private val pendingUsageSeconds = java.util.TreeSet<Long>()

    private fun collectUsage(): List<Long> = synchronized(pendingUsageSeconds) {
        pendingUsageSeconds.addAll(XmaxRealtimeBridge.completedOutputSeconds())
        pendingUsageSeconds.take(60)
    }

    private fun finishBackendSession(sessionId: String) {
        if (sessionId.isBlank()) return
        val seconds = collectUsage()
        val close = Runnable {
            runCatching { postBackendJson("end-session", JSONObject().put("sessionId", sessionId)
                .put("billingVersion", 2).put("epochSeconds", org.json.JSONArray(seconds))) }
        }
        try { networkExecutor.execute(close) }
        catch (_: java.util.concurrent.RejectedExecutionException) {
            Thread(close, "MorphlyEndSession").start()
        }
    }

    private val creditUsageRunnable = object : Runnable {
        override fun run() {
            if (!isStreaming) return
            val sessionId = currentStreamSessionId
            val seconds = collectUsage()
            if (!isCreditSyncInFlight && seconds.isNotEmpty()) {
                isCreditSyncInFlight = true
                networkExecutor.execute {
                    try {
                        val result = postBackendJson("heartbeat", JSONObject().put("sessionId", sessionId)
                            .put("billingVersion", 2).put("epochSeconds", org.json.JSONArray(seconds)))
                        if (result.first !in 200..299) throw IllegalStateException("Credit verification failed.")
                        val response = JSONObject(result.second)
                        val balance = response.getDouble("remainingCredits").toInt()
                        synchronized(pendingUsageSeconds) { pendingUsageSeconds.removeAll(seconds.toSet()) }
                        consecutiveCreditSyncFailures = 0
                        StreamAuthManager.setWalletBalance(this@StreamActivity, balance)
                        runOnUiThread {
                            updateSessionBalanceUI(balance)
                            if (response.optBoolean("shouldStop")) stopForCreditFailure("This session has ended.")
                        }
                    } catch (_: Exception) {
                        consecutiveCreditSyncFailures++
                        if (consecutiveCreditSyncFailures >= 2) stopForCreditFailure("Credit verification failed. Reconnect to continue.")
                    } finally {
                        isCreditSyncInFlight = false
                    }
                }
            }
            mainHandler.postDelayed(this, 3_000)
        }
    }

    private fun stopForCreditFailure(message: String) {
        runOnUiThread {
            if (!isStreaming) return@runOnUiThread
            Toast.makeText(this@StreamActivity, message, Toast.LENGTH_LONG).show()
            stopLiveStream()
            if (StreamAuthManager.getWalletBalance(this@StreamActivity) <= 0) {
                showPlanSelectionState()
            }
        }
    }

    private var selectedPresetView: android.widget.TextView? = null

    // Package IDs mapped from backend
    private var pkgBasicId = "basic"
    private var pkgProId = "pro"
    private var pkgEnterpriseId = "enterprise"
    private var pkgVipId = "vip"
    private var selectedPackageId = "basic"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityStreamBinding.inflate(layoutInflater)
        setContentView(binding.root)
        VirtualCameraBridge.setApplicationContext(this)
        VirtualCameraBridge.setActiveSource(applicationContext, VirtualCameraBridge.SOURCE_XMAX)

        // Initial view setup - show Splash screen first
        showSplashState()

        setupListeners()
        setupSpinners()

        val handledPaymentReturn = handlePaymentReturn(intent)
        if (!handledPaymentReturn) {
            // Wait for 2 seconds (simulating initialization) before checking authentication state
            Handler(Looper.getMainLooper()).postDelayed({
                if (StreamAuthManager.isLoggedIn(this)) {
                    showDashboardState()
                } else {
                    showAuthState()
                }
            }, 2000)
        }
    }

    private fun darkSpinnerAdapter(options: Array<String>) =
        object : android.widget.ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, options) {
            override fun getView(position: Int, convertView: View?, parent: android.view.ViewGroup): View =
                super.getView(position, convertView, parent).also {
                    (it as? android.widget.TextView)?.apply { setTextColor(Color.parseColor("#212121")); setPadding(28, 0, 28, 0) }
                }
        }.apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }

    private fun setupSpinners() {
        val cameraOptions = arrayOf("camera 1, facing front", "camera 2, facing back")
        val cameraAdapter = darkSpinnerAdapter(cameraOptions)
        binding.spinnerCameraSelect.adapter = cameraAdapter

        val qualityOptions = arrayOf("High – Best Output", "Medium – Standard", "Low – Data Saver")
        val qualityAdapter = darkSpinnerAdapter(qualityOptions)
        binding.spinnerQualitySelect.adapter = qualityAdapter
    }

    override fun onResume() {
        super.onResume()
        (pendingCheckoutTxRef ?: getPrefs().getString("pending_tx_ref", null))?.let { ref ->
            if (checkoutDialog?.isShowing != true && StreamAuthManager.isLoggedIn(this)) {
                pendingCheckoutTxRef = null
                getPrefs().edit().remove("pending_tx_ref").apply()
                verifyCheckoutPayment(ref, "")
            }
        }
        StreamSessionController.refreshWindow(this)
        if (binding.layoutDashboard.visibility == View.VISIBLE && !isStreaming) {
            startCameraPreview()
        }
        if (StreamAuthManager.isLoggedIn(this) &&
            (binding.layoutDashboard.visibility == View.VISIBLE ||
                binding.layoutPlanPayment.visibility == View.VISIBLE ||
                binding.layoutAccount.visibility == View.VISIBLE)
        ) {
            fetchBalanceFromBackend()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handlePaymentReturn(intent)
    }

    override fun onStart() {
        super.onStart()
        XmaxRealtimeBridge.setRendering(true)
    }

    override fun onStop() {
        XmaxRealtimeBridge.setRendering(false)
        super.onStop()
    }

    override fun onPause() {
        releaseCamera()
        super.onPause()
    }

    private fun handlePaymentReturn(intent: Intent?): Boolean {
        val data = intent?.data ?: return false
        if (data.scheme != "morphly" || data.host != "payment-return") {
            return false
        }

        if (StreamAuthManager.isLoggedIn(this)) {
            Toast.makeText(this, "Payment complete. Refreshing your credits...", Toast.LENGTH_LONG).show()
            showDashboardState()
            schedulePaymentBalanceRefresh()
        } else {
            Toast.makeText(this, "Payment complete. Sign in to refresh your credits.", Toast.LENGTH_LONG).show()
            showAuthState()
        }
        return true
    }

    private fun schedulePaymentBalanceRefresh() {
        // The browser redirect can arrive before Flutterwave finishes delivering
        // the webhook, so retry briefly instead of leaving a stale balance onscreen.
        listOf(2_000L, 5_000L, 10_000L, 20_000L).forEach { delayMs ->
            mainHandler.postDelayed({
                if (!isFinishing && !isDestroyed && StreamAuthManager.isLoggedIn(this)) {
                    fetchBalanceFromBackend()
                }
            }, delayMs)
        }
    }

    private fun showSplashState() {
        binding.layoutSplash.visibility = View.VISIBLE
        binding.layoutAuth.visibility = View.GONE
        binding.layoutDashboard.visibility = View.GONE
        binding.layoutWelcome.visibility = View.GONE
        binding.layoutStarterPack.visibility = View.GONE
        binding.layoutPlanPayment.visibility = View.GONE
        binding.layoutAccount.visibility = View.GONE

        // Soft pulse: M breathes, red glow behind it fades in and out.
        val pulse = ScaleAnimation(
            1f, 1.12f, 1f, 1.12f,
            Animation.RELATIVE_TO_SELF, 0.5f,
            Animation.RELATIVE_TO_SELF, 0.5f
        ).apply {
            duration = 1100
            repeatCount = Animation.INFINITE
            repeatMode = Animation.REVERSE
            interpolator = android.view.animation.AccelerateDecelerateInterpolator()
        }
        binding.ivSplashArc.startAnimation(pulse)
    }

    private fun showAuthState() {
        binding.ivSplashArc.clearAnimation()
        releaseCamera()
        binding.layoutSplash.visibility = View.GONE
        binding.layoutAuth.visibility = View.VISIBLE
        binding.layoutDashboard.visibility = View.GONE
        binding.layoutWelcome.visibility = View.GONE
        binding.layoutStarterPack.visibility = View.GONE
        binding.layoutPlanPayment.visibility = View.GONE
        binding.layoutAccount.visibility = View.GONE

        // Default tab is Sign In
        selectSignInTab()
    }

    private fun showWelcomeState() {
        binding.ivSplashArc.clearAnimation()
        releaseCamera()
        binding.layoutSplash.visibility = View.GONE
        binding.layoutAuth.visibility = View.GONE
        binding.layoutDashboard.visibility = View.GONE
        binding.layoutWelcome.visibility = View.VISIBLE
        binding.layoutStarterPack.visibility = View.GONE
        binding.layoutPlanPayment.visibility = View.GONE
        binding.layoutAccount.visibility = View.GONE

        fetchPackagesFromBackend()
    }

    private fun showStarterPackState() {
        showPlanSelectionState()
    }

    private fun showPlanSelectionState() {
        binding.ivSplashArc.clearAnimation()
        releaseCamera()
        binding.layoutSplash.visibility = View.GONE
        binding.layoutAuth.visibility = View.GONE
        binding.layoutDashboard.visibility = View.GONE
        binding.layoutWelcome.visibility = View.GONE
        binding.layoutStarterPack.visibility = View.VISIBLE
        binding.layoutPlanPayment.visibility = View.GONE
        binding.layoutAccount.visibility = View.GONE

        fetchPackagesFromBackend()
    }

    private fun showPlanPaymentState(packageId: String, name: String, price: Double, credits: Int, streamTime: String) {
        selectedPackageId = packageId
        selectedPlanName = name
        selectedPlanPrice = price
        selectedPlanCredits = credits
        selectedPlanStreamTime = streamTime

        binding.ivSplashArc.clearAnimation()
        releaseCamera()
        binding.layoutSplash.visibility = View.GONE
        binding.layoutAuth.visibility = View.GONE
        binding.layoutDashboard.visibility = View.GONE
        binding.layoutWelcome.visibility = View.GONE
        binding.layoutStarterPack.visibility = View.GONE
        binding.layoutPlanPayment.visibility = View.VISIBLE
        binding.layoutAccount.visibility = View.GONE

        // Populate details
        binding.tvCheckoutPlanTitle.text = "${name.uppercase()} PLAN"
        binding.tvCheckoutPlanSubtitle.text = String.format("%s • %,d CREDITS", formatNaira(price), credits)
        binding.tvCheckoutDetailPlan.text = name
        binding.tvCheckoutDetailCredits.text = String.format("%,d credits", credits)
        binding.tvCheckoutDetailStreamTime.text = streamTime
        binding.tvCheckoutDetailTotal.text = formatNaira(price)
    }
    private var updateCheckDone = false

    /** Once per launch: ask the server for the latest GitHub build and prompt if this install is older. */
    private fun checkForAppUpdateOnce() {
        if (updateCheckDone) return
        updateCheckDone = true
        networkExecutor.execute {
            try {
                val c = java.net.URL("$BACKEND_BASE_URL/app-version").openConnection() as java.net.HttpURLConnection
                c.connectTimeout = 8000; c.readTimeout = 8000
                if (c.responseCode != 200) return@execute
                val json = org.json.JSONObject(c.inputStream.bufferedReader().use { it.readText() })
                val latestCode = json.optInt("versionCode", 0)
                val installed = BuildConfig.VERSION_CODE
                if (latestCode <= installed) return@execute
                val mandatory = json.optInt("minVersionCode", 0) > installed
                val latestName = json.optString("latestVersion", "")
                val url = json.optString("downloadUrl", "https://luckyweb.cloud").ifBlank { "https://luckyweb.cloud" }
                runOnUiThread { if (!isFinishing && !isDestroyed) showUpdateDialog(latestName, url, mandatory) }
            } catch (e: Exception) {
                Log.w(TAG_STREAM_LIFECYCLE, "app_version_check_failed", e)
            }
        }
    }

    private fun showUpdateDialog(latestName: String, url: String, mandatory: Boolean) {
        val dp = resources.displayMetrics.density
        val red = android.graphics.Color.parseColor("#E53935")
        val box = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            val p = (24 * dp).toInt(); setPadding(p, p, p, (16 * dp).toInt())
            background = android.graphics.drawable.GradientDrawable().apply { setColor(android.graphics.Color.WHITE); cornerRadius = 16 * dp }
        }
        box.addView(android.widget.TextView(this).apply {
            text = "Update available"; setTextColor(red); textSize = 20f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        box.addView(android.widget.TextView(this).apply {
            text = "You are using an outdated version (${BuildConfig.VERSION_NAME}). Update to v$latestName." +
                if (mandatory) "\n\nThis update is required to keep using Morphly." else ""
            setTextColor(android.graphics.Color.parseColor("#424242")); textSize = 15f
            setPadding(0, (12 * dp).toInt(), 0, (20 * dp).toInt())
        })
        val dialog = android.app.Dialog(this)
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        val update = com.google.android.material.button.MaterialButton(this).apply {
            text = "UPDATE"; setTextColor(android.graphics.Color.WHITE)
            backgroundTintList = android.content.res.ColorStateList.valueOf(red); cornerRadius = (8 * dp).toInt()
            setOnClickListener {
                try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                catch (e: Exception) { Toast.makeText(this@StreamActivity, "Open $url in your browser", Toast.LENGTH_LONG).show() }
                if (!mandatory) dialog.dismiss()
            }
        }
        box.addView(update, android.widget.LinearLayout.LayoutParams(-1, (52 * dp).toInt()))
        if (!mandatory) {
            box.addView(android.widget.Button(this, null, android.R.attr.borderlessButtonStyle).apply {
                text = "Later"; setTextColor(red); setOnClickListener { dialog.dismiss() }
            }, android.widget.LinearLayout.LayoutParams(-1, (48 * dp).toInt()))
        }
        dialog.setContentView(box)
        dialog.setCancelable(!mandatory)
        dialog.setCanceledOnTouchOutside(false)
        dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
        dialog.window?.setLayout((resources.displayMetrics.widthPixels * 0.86).toInt(), android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
        dialog.show()
    }

    private fun showDashboardState() {
        checkForAppUpdateOnce()
        binding.ivSplashArc.clearAnimation()
        binding.layoutSplash.visibility = View.GONE
        binding.layoutAuth.visibility = View.GONE
        binding.layoutWelcome.visibility = View.GONE
        binding.layoutStarterPack.visibility = View.GONE
        binding.layoutPlanPayment.visibility = View.GONE
        binding.layoutAccount.visibility = View.GONE
        binding.layoutDashboard.visibility = View.VISIBLE

        fetchBalanceFromBackend()
        if (!isStreaming) startCameraPreview()
    }
    private fun fetchBalanceFromBackend() {
        networkExecutor.execute {
            try {
                val token = StreamAuthManager.getValidIdToken(
                    this@StreamActivity,
                    BACKEND_BASE_URL
                ) ?: throw IllegalStateException("Your session expired. Sign in again.")
                val url = java.net.URL("$BACKEND_BASE_URL/balance")
                val connection = url.openConnection() as java.net.HttpURLConnection
                connection.requestMethod = "GET"
                connection.setRequestProperty("Authorization", "Bearer $token")
                connection.connectTimeout = 8_000
                connection.readTimeout = 10_000

                val responseCode = connection.responseCode
                if (responseCode == 200) {
                    val jsonStr = java.io.BufferedReader(java.io.InputStreamReader(connection.inputStream)).use { reader ->
                        reader.readText()
                    }
                    val balanceJson = JSONObject(jsonStr)
                    val fetchedBalance = balanceJson.optDouble("balance", -1.0).toInt()
                    val fetchedUsed = balanceJson.optInt("used", -1)

                    if (fetchedBalance >= 0) {
                        StreamAuthManager.setWalletBalance(this, fetchedBalance)
                        promotePendingPlan(fetchedBalance)
                        runOnUiThread {
                            updateSessionBalanceUI(fetchedBalance, fetchedUsed)
                            if (binding.layoutAccount.visibility == View.VISIBLE) {
                                updateAccountOverlayFromBalance(balanceJson)
                            }
                        }
                    } else {
                        runOnUiThread {
                            updateSessionBalanceUI()
                        }
                    }
                } else if (responseCode == 401 || responseCode == 403) {
                    StreamAuthManager.logout(this@StreamActivity)
                    runOnUiThread {
                        Toast.makeText(
                            this@StreamActivity,
                            "Your session ended. Sign in again.",
                            Toast.LENGTH_LONG
                        ).show()
                        showAuthState()
                    }
                } else {
                    runOnUiThread {
                        updateSessionBalanceUI()
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                runOnUiThread {
                    updateSessionBalanceUI()
                }
            }
        }
    }

    private fun updateSessionBalanceUI(overrideRemaining: Int? = null, overrideUsed: Int? = null) {
        val remaining = overrideRemaining ?: StreamAuthManager.getWalletBalance(this)
        val used = (overrideUsed ?: 0).coerceAtLeast(0)
        val planTotal = maxOf(remaining + used, remaining, 1)

        binding.tvSessionPlanTotal.text = "$planTotal CR"
        binding.tvSessionUsed.text = "$used CR"
        binding.tvSessionRemaining.text = "$remaining CR"

        val progressPercent = ((remaining.coerceAtLeast(0) * 100) / planTotal).coerceIn(0, 100)
        binding.progressSessionRemaining.progress = progressPercent

        if (progressPercent < 20) {
            binding.progressSessionRemaining.setProgressTintList(android.content.res.ColorStateList.valueOf(Color.parseColor("#FF5252")))
        } else {
            binding.progressSessionRemaining.setProgressTintList(android.content.res.ColorStateList.valueOf(Color.parseColor("#D32F2F")))
        }
    }

    private fun selectSignInTab() {
        binding.tvTabSignIn.setBackgroundColor(ContextCompat.getColor(this, R.color.primary_gold)) // Gold Accent
        binding.tvTabSignIn.setTextColor(ContextCompat.getColor(this, R.color.dark_bg)) // Dark background color
        binding.tvTabCreateAccount.setBackgroundColor(ContextCompat.getColor(this, android.R.color.transparent))
        binding.tvTabCreateAccount.setTextColor(ContextCompat.getColor(this, R.color.secondary_text)) // Gray

        binding.formSignIn.visibility = View.VISIBLE
        binding.formCreateAccount.visibility = View.GONE
    }

    private fun selectCreateAccountTab() {
        binding.tvTabCreateAccount.setBackgroundColor(ContextCompat.getColor(this, R.color.primary_gold)) // Gold Accent
        binding.tvTabCreateAccount.setTextColor(ContextCompat.getColor(this, R.color.dark_bg)) // Dark background color
        binding.tvTabSignIn.setBackgroundColor(ContextCompat.getColor(this, android.R.color.transparent))
        binding.tvTabSignIn.setTextColor(ContextCompat.getColor(this, R.color.secondary_text)) // Gray

        binding.formSignIn.visibility = View.GONE
        binding.formCreateAccount.visibility = View.VISIBLE
    }

    private fun minimizeApp() {
        val intent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        startActivity(intent)
    }

    private fun openTelegram() {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://t.me/Luckyweb0"))
        try {
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "Telegram link: https://t.me/Luckyweb0", Toast.LENGTH_LONG).show()
        }
    }


    private fun selectPreset(textView: android.widget.TextView, prompt: String) {
        if (!binding.switchEnhance.isChecked) {
            Toast.makeText(this, "Please enable ENHANCE to select presets.", Toast.LENGTH_SHORT).show()
            return
        }
        selectedPresetView?.setBackgroundResource(R.drawable.bg_preset_pill)
        selectedPresetView?.setTextColor(Color.parseColor("#FFFFFF"))

        textView.setBackgroundColor(Color.parseColor("#E53935"))
        textView.setTextColor(Color.parseColor("#FFFFFF"))

        selectedPresetView = textView
        binding.etPromptDescription.setText(prompt)
    }

    private fun stopLiveStream() {
        finishBackendSession(currentStreamSessionId)
        isStreaming = false
        currentStreamSessionId = ""
        currentStreamModel = ""
        creditUsageSequence = 0
        consecutiveCreditSyncFailures = 0
        mainHandler.removeCallbacks(creditUsageRunnable)
        XmaxRealtimeBridge.disconnect()
        StreamSessionController.stop()
        StreamPreviewBridge.clear()
        binding.layoutDecartRemoteOutput.removeAllViews()
        binding.layoutDecartRemoteOutput.visibility = View.GONE
        binding.layoutAiOutputPlaceholder.visibility = View.VISIBLE
        binding.tvAiOutputTitle.text = "AI OUTPUT WILL APPEAR HERE"
        binding.tvAiOutputStatus.text = "Ready to connect live output"

        binding.btnConnectStream.isEnabled = true
        binding.btnConnectStream.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#D32F2F"))
        binding.btnConnectStream.setTextColor(Color.WHITE)

        binding.btnStopStream.isEnabled = false
        binding.btnStopStream.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#EEEEEE"))
        binding.btnStopStream.setTextColor(Color.parseColor("#9E9E9E"))

        Toast.makeText(this, "AI transformation engine stopped.", Toast.LENGTH_SHORT).show()
    }

    private fun showAccountOverlay() {
        binding.layoutAccount.visibility = View.VISIBLE

        val email = StreamAuthManager.getUserEmail(this)
        val name = StreamAuthManager.getUserName(this)
        val remaining = StreamAuthManager.getWalletBalance(this)
        binding.tvAccountName.text = name
        binding.tvAccountEmail.text = email
        binding.tvAccountDeviceId.text = StreamAuthManager.getDeviceId(this)
        binding.tvAccountLicenseStatus.text = "ACTIVE"
        binding.tvAccountLicenseStatus.setTextColor(Color.parseColor("#2E7D32"))
        binding.tvAccountLicenseExpires.text = getLastPlanLabel()
        binding.tvAccountMemberSince.text = formatMemberSince(getPrefs().getString("account_created_at", null))
        binding.btnAccountBuyCredits.visibility = View.GONE
        binding.tvAccountCreditsRemaining.text = "$remaining CR"
        binding.tvAccountCreditsUsed.text = "..."

        val referralCode = StreamAuthManager.getUserReferralCode(this)
        binding.tvAccountReferralCode.text = "YOUR CODE: $referralCode"
        binding.btnCopyReferral.setOnClickListener {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            val clip = android.content.ClipData.newPlainText("Referral Code", referralCode)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(this, "Referral Code copied to clipboard!", Toast.LENGTH_SHORT).show()
        }

        binding.btnAccountBack.setOnClickListener {
            binding.layoutAccount.visibility = View.GONE
        }

        binding.btnAccountBuyCredits.setOnClickListener {
            binding.layoutAccount.visibility = View.GONE
            showStarterPackState()
        }

        binding.btnAccountReplayTour.setOnClickListener {
            Toast.makeText(this, "Starting feature tour...", Toast.LENGTH_SHORT).show()
            binding.layoutAccount.visibility = View.GONE
        }

        binding.btnAccountSignOut.setOnClickListener {
            binding.layoutAccount.visibility = View.GONE
            if (currentStreamSessionId.isNotBlank()) stopLiveStream()
            releaseCamera()
            StreamAuthManager.logout(this)
            Toast.makeText(this, "Logged out", Toast.LENGTH_SHORT).show()
            showAuthState()
        }

        binding.btnAccountMinimize.setOnClickListener { minimizeApp() }
        binding.btnAccountClonedApps.setOnClickListener { finish() }
        binding.fabAccountTelegram.setOnClickListener { openTelegram() }
        fetchBalanceFromBackend()
    }

    private fun updateAccountOverlayFromBalance(balanceJson: JSONObject) {
        val userJson = balanceJson.optJSONObject("user")
        val licenseJson = balanceJson.optJSONObject("license")
        val remaining = balanceJson.optDouble("balance", StreamAuthManager.getWalletBalance(this).toDouble()).toInt()
        val used = balanceJson.optInt("used", 0).coerceAtLeast(0)
        val name = userJson?.optString("name")?.takeIf { it.isNotBlank() } ?: StreamAuthManager.getUserName(this)
        val email = userJson?.optString("email")?.takeIf { it.isNotBlank() } ?: StreamAuthManager.getUserEmail(this)
        val deviceId = userJson?.optString("deviceId")?.takeIf { it.isNotBlank() } ?: StreamAuthManager.getDeviceId(this)
        val referralCode = userJson?.optString("referralCode")?.takeIf { it.isNotBlank() } ?: StreamAuthManager.getUserReferralCode(this)
        val licenseStatus = licenseJson?.optString("status")?.takeIf { it.isNotBlank() } ?: "signed in"
        val licenseExpires = licenseJson?.optString("expiresAt")?.takeIf { it.isNotBlank() } ?: "Per second"

        StreamAuthManager.updateUserProfile(this, name = name, email = email, referralCode = referralCode)
        StreamAuthManager.setWalletBalance(this, remaining)

        binding.tvAccountName.text = name
        binding.tvAccountEmail.text = email
        binding.tvAccountDeviceId.text = deviceId
        binding.tvAccountCreditsRemaining.text = "$remaining CR"
        binding.tvAccountCreditsUsed.text = if (balanceJson.has("used")) "$used CR" else "See desktop"
        binding.tvAccountReferralCode.text = "YOUR CODE: $referralCode"
        binding.tvAccountLicenseStatus.text = "ACTIVE"
        val createdAt = userJson?.optString("createdAt")?.takeIf { it.isNotBlank() && it != "null" }
        if (createdAt != null) getPrefs().edit().putString("account_created_at", createdAt).apply()
        binding.tvAccountMemberSince.text = formatMemberSince(createdAt ?: getPrefs().getString("account_created_at", null))
        val backendPlan = listOf("currentPlan", "lastPlan", "planName", "lastPackageName")
            .mapNotNull { k -> userJson?.optString(k)?.takeIf { it.isNotBlank() && it != "null" } ?: balanceJson.optString(k).takeIf { it.isNotBlank() && it != "null" } }
            .firstOrNull()
        if (backendPlan != null) getPrefs().edit().putString("last_plan_name", backendPlan).apply()
        binding.tvAccountLicenseExpires.text = getLastPlanLabel()
        binding.tvAccountLicenseStatus.setTextColor(
            if (true || licenseStatus.isEmpty()) {
                Color.parseColor("#2E7D32")
            } else {
                Color.parseColor("#FF5252")
            }
        )
        if (licenseExpires.isEmpty()) Unit
    }

    private fun getPrefs() = getSharedPreferences("morphly_account", Context.MODE_PRIVATE)

    private fun getLastPlanLabel(): String = getPrefs().getString("last_plan_name", null)
        ?.takeIf { it.isNotBlank() }?.let { if (it.contains("plan", true)) it else "$it Plan" } ?: "No plan yet"

    /** Promote a checkout's plan to the current plan once the wallet balance actually grows. */
    private fun promotePendingPlan(newBalance: Int) {
        val prefs = getPrefs()
        val pending = prefs.getString("pending_plan_name", null) ?: return
        val before = prefs.getInt("pending_plan_balance", Int.MAX_VALUE)
        if (newBalance > before) {
            prefs.edit().putString("last_plan_name", pending).remove("pending_plan_name").remove("pending_plan_balance").apply()
        }
    }

    private fun formatMemberSince(raw: String?): String {
        if (raw.isNullOrBlank()) return "-"
        val millis = raw.toLongOrNull()?.let { if (it < 100_000_000_000L) it * 1000 else it } ?: run {
            val patterns = listOf("yyyy-MM-dd'T'HH:mm:ss.SSSX", "yyyy-MM-dd'T'HH:mm:ssX", "yyyy-MM-dd")
            patterns.firstNotNullOfOrNull { p ->
                try { java.text.SimpleDateFormat(p, java.util.Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.parse(raw)?.time } catch (e: Exception) { null }
            }
        } ?: return raw
        val date = java.text.SimpleDateFormat("MMM d, yyyy", java.util.Locale.US).format(java.util.Date(millis))
        val days = ((System.currentTimeMillis() - millis) / 86_400_000L).coerceAtLeast(0)
        val ago = when {
            days < 1 -> "today"
            days < 30 -> "$days day${if (days == 1L) "" else "s"} ago"
            days < 365 -> "${days / 30} month${if (days / 30 == 1L) "" else "s"} ago"
            else -> "${days / 365} year${if (days / 365 == 1L) "" else "s"} ago"
        }
        return "$date\n($ago)"
    }

    private fun startBackgroundThread() {
        if (backgroundThread == null) {
            backgroundThread = android.os.HandlerThread("CameraBackground").apply { start() }
            backgroundHandler = android.os.Handler(backgroundThread!!.looper)
        }
    }

    private fun stopBackgroundThread() {
        backgroundThread?.quitSafely()
        try {
            backgroundThread?.join()
            backgroundThread = null
            backgroundHandler = null
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun startCameraPreview() {
        binding.tvUserCamFallback.visibility = View.GONE
        binding.textureViewUserCam.visibility = View.VISIBLE

        if (androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            androidx.core.app.ActivityCompat.requestPermissions(this, arrayOf(android.Manifest.permission.CAMERA), 101)
            return
        }

        startBackgroundThread()

        val manager = getSystemService(Context.CAMERA_SERVICE) as android.hardware.camera2.CameraManager
        try {
            var frontCameraId: String? = null
            for (id in manager.cameraIdList) {
                val characteristics = manager.getCameraCharacteristics(id)
                val facing = characteristics.get(android.hardware.camera2.CameraCharacteristics.LENS_FACING)
                if (facing == android.hardware.camera2.CameraCharacteristics.LENS_FACING_FRONT) {
                    frontCameraId = id
                    break
                }
            }

            if (frontCameraId == null && manager.cameraIdList.isNotEmpty()) {
                frontCameraId = manager.cameraIdList[0]
            }

            if (frontCameraId != null) {
                if (binding.textureViewUserCam.isAvailable) {
                    openCamera(manager, frontCameraId)
                } else {
                    binding.textureViewUserCam.surfaceTextureListener = object : android.view.TextureView.SurfaceTextureListener {
                        override fun onSurfaceTextureAvailable(texture: android.graphics.SurfaceTexture, width: Int, height: Int) {
                            openCamera(manager, frontCameraId)
                        }
                        override fun onSurfaceTextureSizeChanged(texture: android.graphics.SurfaceTexture, width: Int, height: Int) {}
                        override fun onSurfaceTextureDestroyed(texture: android.graphics.SurfaceTexture): Boolean = true
                        override fun onSurfaceTextureUpdated(texture: android.graphics.SurfaceTexture) {}
                    }
                }
            } else {
                showCameraFallback()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            showCameraFallback()
        }
    }

    private fun openCamera(manager: android.hardware.camera2.CameraManager, cameraId: String) {
        try {
            if (androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA) != android.content.pm.PackageManager.PERMISSION_GRANTED) return
            manager.openCamera(cameraId, object : android.hardware.camera2.CameraDevice.StateCallback() {
                override fun onOpened(camera: android.hardware.camera2.CameraDevice) {
                    cameraDevice = camera
                    createCameraPreviewSession()
                }

                override fun onDisconnected(camera: android.hardware.camera2.CameraDevice) {
                    camera.close()
                    cameraDevice = null
                }

                override fun onError(camera: android.hardware.camera2.CameraDevice, error: Int) {
                    camera.close()
                    cameraDevice = null
                    showCameraFallback()
                }
            }, backgroundHandler)
        } catch (e: Exception) {
            e.printStackTrace()
            showCameraFallback()
        }
    }

    private fun createCameraPreviewSession() {
        val device = cameraDevice ?: return
        val texture = binding.textureViewUserCam.surfaceTexture ?: return
        texture.setDefaultBufferSize(640, 480)
        val surface = android.view.Surface(texture)

        try {
            val builder = device.createCaptureRequest(android.hardware.camera2.CameraDevice.TEMPLATE_PREVIEW)
            builder.addTarget(surface)

            val callback = object : android.hardware.camera2.CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: android.hardware.camera2.CameraCaptureSession) {
                    captureSession = session
                    try {
                        builder.set(android.hardware.camera2.CaptureRequest.CONTROL_AF_MODE, android.hardware.camera2.CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                        session.setRepeatingRequest(builder.build(), null, backgroundHandler)
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }

                override fun onConfigureFailed(session: android.hardware.camera2.CameraCaptureSession) {
                    showCameraFallback()
                }
            }
            device.createCaptureSession(listOf(surface), callback, null)
        } catch (e: Exception) {
            e.printStackTrace()
            showCameraFallback()
        }
    }

    private fun showCameraFallback() {
        runOnUiThread {
            binding.textureViewUserCam.visibility = View.GONE
            binding.tvUserCamFallback.visibility = View.VISIBLE
        }
    }

    private fun releaseCamera() {
        try {
            captureSession?.close()
            captureSession = null
            cameraDevice?.close()
            cameraDevice = null
        } catch (e: Exception) {
            e.printStackTrace()
        }
        stopBackgroundThread()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 101) {
            if (grantResults.isNotEmpty() && grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                startCameraPreview()
            } else {
                showCameraFallback()
            }
        } else if (requestCode == REQUEST_DECART_PERMISSIONS) {
            if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                connectToDecartSession()
            } else {
                Toast.makeText(this, "Camera and microphone permission are required for live output.", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun setupListeners() {
        // Tab switching
        binding.tvTabSignIn.setOnClickListener {
            selectSignInTab()
        }

        binding.tvTabCreateAccount.setOnClickListener {
            selectCreateAccountTab()
        }

        // Links click handlers
        binding.tvForgotPassword.setOnClickListener {
            val email = binding.etSignInEmail.text.toString().trim()
            if (email.isBlank() || !email.contains("@")) {
                Toast.makeText(this, "Enter your account email first.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            binding.tvForgotPassword.isEnabled = false
            networkExecutor.execute {
                val error = StreamAuthManager.requestPasswordReset(BACKEND_BASE_URL, email)
                runOnUiThread {
                    binding.tvForgotPassword.isEnabled = true
                    Toast.makeText(
                        this,
                        error ?: "If that account exists, a reset link has been sent.",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }

        binding.tvTermsLink.setOnClickListener {
            Toast.makeText(this, "Opening Terms & Conditions...", Toast.LENGTH_SHORT).show()
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://morphly.com/terms"))
            try {
                startActivity(intent)
            } catch (e: Exception) {
                // Fallback if no browser is installed in context
            }
        }

        // Sign In Action
        binding.btnSignInSubmit.setOnClickListener {
            val email = binding.etSignInEmail.text.toString().trim()
            val password = binding.etSignInPassword.text.toString().trim()

            if (email.isEmpty()) {
                Toast.makeText(this, "Please enter your email address", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (password.isEmpty()) {
                Toast.makeText(this, "Please enter your password", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (password.length < 6) {
                Toast.makeText(this, "Password must be at least 6 characters long", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val progressDialog = android.app.ProgressDialog(this).apply {
                setMessage("Signing in with backend...")
                setCancelable(false)
                show()
            }

            networkExecutor.execute {
                val errorMsg = StreamAuthManager.login(this@StreamActivity, BACKEND_BASE_URL, email, password)
                runOnUiThread {
                    progressDialog.dismiss()
                    if (errorMsg == null) {
                        Toast.makeText(this@StreamActivity, "Signed in successfully!", Toast.LENGTH_SHORT).show()
                        showDashboardState()
                    } else {
                        Toast.makeText(this@StreamActivity, errorMsg, Toast.LENGTH_LONG).show()
                    }
                }
            }
        }

        // Create Account Action
        binding.btnSignUpSubmit.setOnClickListener {
            val name = binding.etSignUpName.text.toString().trim()
            val email = binding.etSignUpEmail.text.toString().trim()
            val phone = binding.etSignUpPhone.text.toString().trim()
            val password = binding.etSignUpPassword.text.toString().trim()
            val confirmPassword = binding.etSignUpConfirmPassword.text.toString().trim()
            val referredByCode = binding.etSignUpReferral.text.toString().trim()

            if (name.isEmpty()) {
                Toast.makeText(this, "Please enter your full name", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (email.isEmpty()) {
                Toast.makeText(this, "Please enter your email address", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (!email.contains("@")) {
                Toast.makeText(this, "Please enter a valid email address", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (password.isEmpty()) {
                Toast.makeText(this, "Please choose a password", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (password.length < 6) {
                Toast.makeText(this, "Password must be at least 6 characters long", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (password != confirmPassword) {
                Toast.makeText(this, "Passwords do not match", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (!binding.cbTerms.isChecked) {
                Toast.makeText(this, "You must accept the Terms & Conditions to proceed", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val progressDialog = android.app.ProgressDialog(this).apply {
                setMessage("Registering with backend...")
                setCancelable(false)
                show()
            }

            networkExecutor.execute {
                val errorMsg = StreamAuthManager.signUp(this@StreamActivity, BACKEND_BASE_URL, name, email, phone, password, referredByCode)
                runOnUiThread {
                    progressDialog.dismiss()
                    if (errorMsg == null) {
                        Toast.makeText(this@StreamActivity, "Account created. Verify your email to receive your free credits.", Toast.LENGTH_LONG).show()
                        showDashboardState()
                    } else {
                        Toast.makeText(this@StreamActivity, errorMsg, Toast.LENGTH_LONG).show()
                    }
                }
            }
        }

        // Welcome Screen Actions
        binding.cardTryFirst.setOnClickListener {
            showPlanSelectionState()
        }
        binding.btnWelcomeMinimize.setOnClickListener {
            minimizeApp()
        }
        binding.btnWelcomeClonedApps.setOnClickListener {
            MainActivity.start(this@StreamActivity)
            finish()
        }
        binding.fabWelcomeTelegram.setOnClickListener {
            openTelegram()
        }

        // Credit Plan Selection Actions
        binding.btnPlanSelectionClose.setOnClickListener {
            showDashboardState()
        }
        binding.btnSelectPlanBasic.setOnClickListener {
            val price = binding.tvPlanBasicPrice.text.toString().replace("₦", "").replace(",", "").toDoubleOrNull() ?: 29000.0
            val credits = binding.tvPlanBasicCredits.text.toString().replace(",", "").replace(" Credits", "").toIntOrNull() ?: 1000
            val time = binding.tvPlanBasicTime.text.toString()
            showPlanPaymentState(pkgBasicId, "Basic", price, credits, time)
        }
        binding.btnSelectPlanPro.setOnClickListener {
            val price = binding.tvPlanProPrice.text.toString().replace("₦", "").replace(",", "").toDoubleOrNull() ?: 58000.0
            val credits = binding.tvPlanProCredits.text.toString().replace(",", "").replace(" Credits", "").toIntOrNull() ?: 2000
            val time = binding.tvPlanProTime.text.toString()
            showPlanPaymentState(pkgProId, "Pro", price, credits, time)
        }
        binding.btnSelectPlanEnterprise.setOnClickListener {
            val price = binding.tvPlanEnterprisePrice.text.toString().replace("₦", "").replace(",", "").toDoubleOrNull() ?: 145000.0
            val credits = binding.tvPlanEnterpriseCredits.text.toString().replace(",", "").replace(" Credits", "").toIntOrNull() ?: 5000
            val time = binding.tvPlanEnterpriseTime.text.toString()
            showPlanPaymentState(pkgEnterpriseId, "Enterprise", price, credits, time)
        }
        binding.btnSelectPlanVip.setOnClickListener {
            val price = binding.tvPlanVipPrice.text.toString().replace("₦", "").replace(",", "").toDoubleOrNull() ?: 290000.0
            val credits = binding.tvPlanVipCredits.text.toString().replace(",", "").replace(" Credits", "").toIntOrNull() ?: 10000
            val time = binding.tvPlanVipTime.text.toString()
            showPlanPaymentState(pkgVipId, "VIP plan", price, credits, time)
        }

        binding.btnStarterMinimize.setOnClickListener {
            minimizeApp()
        }
        binding.btnStarterClonedApps.setOnClickListener {
            MainActivity.start(this@StreamActivity)
            finish()
        }
        binding.fabStarterTelegram.setOnClickListener {
            openTelegram()
        }

        // Checkout / Payment Actions
        binding.btnCheckoutFlutterwave.setOnClickListener {
            processCheckoutPayment("Flutterwave")
        }

        binding.btnCheckoutBack.setOnClickListener {
            showPlanSelectionState()
        }
        binding.btnAccountBuyCredits.setOnClickListener {
            showPlanSelectionState()
        }
        binding.btnPaymentMinimize.setOnClickListener {
            minimizeApp()
        }
        binding.btnPaymentClonedApps.setOnClickListener {
            MainActivity.start(this@StreamActivity)
            finish()
        }
        binding.fabPaymentTelegram.setOnClickListener {
            openTelegram()
        }



        // Dashboard Top Bar Navigation - Hamburger Dropdown Menu
        binding.btnHamburgerMenu.setOnClickListener { view ->
            val popup = android.widget.PopupMenu(this, view)
            
            
            
            popup.menu.add("ACCOUNT")
            popup.setOnMenuItemClickListener { menuItem ->
                when (menuItem.title) {
                    "OBS" -> {
                        Toast.makeText(this, "OBS stream selected", Toast.LENGTH_SHORT).show()
                        true
                    }
                    "REC" -> {
                        Toast.makeText(this, "Recording stream selected", Toast.LENGTH_SHORT).show()
                        true
                    }
                    "SNAP" -> {
                        Toast.makeText(this, "Snapshot capture selected", Toast.LENGTH_SHORT).show()
                        true
                    }
                    "ACCOUNT" -> {
                        showAccountOverlay()
                        true
                    }
                    else -> false
                }
            }
            popup.show()
        }

        // Dashboard Live Stream Controls
        binding.btnConnectStream.setOnClickListener {
            if (!binding.cbAuditConsent.isChecked) {
                Toast.makeText(this, "Consent is required before connecting to the live engine.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            connectToDecartSession()
        }
        binding.btnStopStream.setOnClickListener {
            stopLiveStream()
        }

        // Mode Style / Face Swap toggle
        binding.btnModeStyle.setOnClickListener {
            binding.btnModeStyle.setBackgroundColor(Color.parseColor("#E53935"))
            binding.btnModeStyle.setTextColor(Color.parseColor("#FFFFFF"))
            binding.btnModeFaceSwap.setBackgroundColor(Color.TRANSPARENT)
            binding.btnModeFaceSwap.setTextColor(Color.parseColor("#757575"))
            binding.layoutFaceSwapUpload.visibility = View.GONE
            Toast.makeText(this, "Style mode selected", Toast.LENGTH_SHORT).show()
        }
        binding.btnModeFaceSwap.setOnClickListener {
            binding.btnModeFaceSwap.setBackgroundColor(Color.parseColor("#E53935"))
            binding.btnModeFaceSwap.setTextColor(Color.parseColor("#FFFFFF"))
            binding.btnModeStyle.setBackgroundColor(Color.TRANSPARENT)
            binding.btnModeStyle.setTextColor(Color.parseColor("#757575"))
            binding.layoutFaceSwapUpload.visibility = View.VISIBLE
            Toast.makeText(this, "Face Swap mode selected", Toast.LENGTH_SHORT).show()
        }

        // Upload Target Face Image Action
        binding.btnUploadFaceImage.setOnClickListener {
            pickFaceImage()
        }

        // Live Update switch toggle
        binding.switchLiveUpdate.setOnCheckedChangeListener { _, isChecked ->
            binding.etPromptDescription.isEnabled = isChecked
            binding.etPromptDescription.alpha = if (isChecked) 1.0f else 0.5f
        }

        // Enhance switch toggle
        binding.switchEnhance.setOnCheckedChangeListener { _, isChecked ->
            if (!isChecked) {
                selectedPresetView?.setBackgroundResource(R.drawable.bg_preset_pill)
                selectedPresetView?.setTextColor(Color.parseColor("#FFFFFF"))
                selectedPresetView = null
            }
            val alpha = if (isChecked) 1.0f else 0.5f
            binding.presetAnime.alpha = alpha
            binding.presetCyberpunk.alpha = alpha
            binding.presetFire.alpha = alpha
            binding.presetIce.alpha = alpha
            binding.presetAndroid.alpha = alpha
            binding.presetCosmos.alpha = alpha
            binding.presetHorror.alpha = alpha
            binding.presetPainting.alpha = alpha
            binding.presetNature.alpha = alpha
            binding.presetWarrior.alpha = alpha
            binding.presetNoir.alpha = alpha
            binding.presetWizard.alpha = alpha
        }

        // Other actions
        binding.btnBackground.setOnClickListener {
            Toast.makeText(this, "Opening virtual backgrounds selector...", Toast.LENGTH_SHORT).show()
        }
        binding.btnAbuse.setOnClickListener {
            Toast.makeText(this, "Abuse report sent successfully.", Toast.LENGTH_LONG).show()
        }
        binding.btnFullscreenCamera.setOnClickListener {
            toggleFullscreenCamera()
        }

        // Preset options binding
        binding.presetAnime.setOnClickListener { selectPreset(binding.presetAnime, "Convert video stream to standard high-definition anime style, with vibrant lines and shading.") }
        binding.presetCyberpunk.setOnClickListener { selectPreset(binding.presetCyberpunk, "Apply neon lights, futuristic cityscape reflections, and glowing enhancements.") }
        binding.presetFire.setOnClickListener { selectPreset(binding.presetFire, "Surround overlay with dramatic fire particle animations and heat distortions.") }
        binding.presetIce.setOnClickListener { selectPreset(binding.presetIce, "Add a frosty ice filter with frozen particles around the frame and blue tones.") }
        binding.presetAndroid.setOnClickListener { selectPreset(binding.presetAndroid, "Transform input face into a sleek mechanical robot/cyborg interface.") }
        binding.presetCosmos.setOnClickListener { selectPreset(binding.presetCosmos, "Replace background with deep space nebula, star fields, and cosmic colors.") }
        binding.presetHorror.setOnClickListener { selectPreset(binding.presetHorror, "Slightly darken atmosphere and apply scary shadow contours and cinematic horror filter.") }
        binding.presetPainting.setOnClickListener { selectPreset(binding.presetPainting, "Convert stream to oil painting texture with fine canvas brushstrokes.") }
        binding.presetNature.setOnClickListener { selectPreset(binding.presetNature, "Overlay realistic green leaf patterns, natural lighting, and organic colors.") }
        binding.presetWarrior.setOnClickListener { selectPreset(binding.presetWarrior, "Equip a metallic knight helmet overlay and battleground cinematic lights.") }
        binding.presetNoir.setOnClickListener { selectPreset(binding.presetNoir, "Classic black and white film noir style, with high contrast shadows and grain.") }
        binding.presetWizard.setOnClickListener { selectPreset(binding.presetWizard, "Apply magic particles, glowing spellbook light reflections, and wizard hat overlay.") }

        // Bottom Actions
        binding.btnMinimize.setOnClickListener {
            minimizeForVideoCall()
        }

        binding.btnClonedApps.setOnClickListener {
            openClonedAppsForVideoCall()
        }

        binding.btnDashboardMinimize.setOnClickListener { minimizeForVideoCall() }
        binding.btnDashboardClonedApps.setOnClickListener {
            openClonedAppsForVideoCall()
        }
        binding.fabDashboardTelegram.setOnClickListener { openTelegram() }
        binding.btnDashboardBuyCredits.setOnClickListener { showPlanSelectionState() }
    }

    private fun pickFaceImage() {
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "image/*"
        }
        startActivityForResult(intent, 1002)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 1002 && resultCode == RESULT_OK) {
            val uri = data?.data
            if (uri != null) {
                selectedFaceImageUri = uri
                binding.layoutUploadPlaceholder.visibility = View.GONE
                binding.ivUploadedFacePreview.visibility = View.VISIBLE
                binding.ivUploadedFacePreview.setImageURI(uri)
                binding.tvUploadedFileName.visibility = View.VISIBLE
                binding.tvUploadedFileName.text = "face_image.png"
                Toast.makeText(this, "Face image selected successfully!", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun ensureDecartRuntimePermissions(): Boolean {
        // RECORD_AUDIO must be granted to the host uid, or the cloned app's AudioRecord fails (WhatsApp: 'problem with your device's microphone').
        val required = mutableListOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        val missing = required.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), REQUEST_DECART_PERMISSIONS)
            return false
        }
        return true
    }

    private fun selectedModeKey(): String = "face_swap"

    private fun selectedQualityKey(): String {
        return when (binding.spinnerQualitySelect.selectedItemPosition) {
            0 -> "high"
            2 -> "low"
            else -> "medium"
        }
    }

    private fun selectedFrameRate(): Int {
        return when (selectedQualityKey()) {
            "high" -> 24
            "low" -> 15
            else -> 20
        }
    }

    private fun selectedVideoBitrate(): Int {
        return when (selectedQualityKey()) {
            "high" -> 1_000_000
            "low" -> 450_000
            else -> 750_000
        }
    }

    private fun selectedPresetLabel(): String {
        return selectedPresetView?.text?.toString().orEmpty()
    }

    private fun currentDecartPrompt(): String {
        return "Apply the selected face image to the live video while preserving natural motion."
    }

    private fun readFaceImageForDecart(uri: Uri): Pair<String, String> {
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        val boundsStream = contentResolver.openInputStream(uri)
            ?: throw IllegalStateException("Could not read selected face image.")
        boundsStream.use {
            android.graphics.BitmapFactory.decodeStream(it, null, bounds)
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw IllegalStateException("The selected face image is invalid.")
        }
        var sampleSize = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sampleSize > 1_024) {
            sampleSize *= 2
        }
        val options = android.graphics.BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888
        }
        val bitmap = contentResolver.openInputStream(uri)?.use {
            android.graphics.BitmapFactory.decodeStream(it, null, options)
        } ?: throw IllegalStateException("Could not decode selected face image.")
        val output = java.io.ByteArrayOutputStream()
        try {
            if (!bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 88, output)) {
                throw IllegalStateException("Could not prepare selected face image.")
            }
            if (output.size() > 4 * 1024 * 1024) {
                throw IllegalStateException("The selected face image is too large.")
            }
            return Pair(
                android.util.Base64.encodeToString(output.toByteArray(), android.util.Base64.NO_WRAP),
                "image/jpeg"
            )
        } finally {
            bitmap.recycle()
            output.close()
        }
    }

    private fun postBackendJson(path: String, payload: JSONObject): Pair<Int, String> {
        val token = StreamAuthManager.getValidIdToken(this, BACKEND_BASE_URL)
            ?: throw IllegalStateException("Your session expired. Sign in again.")
        val url = java.net.URL("$BACKEND_BASE_URL/$path")
        val connection = url.openConnection() as java.net.HttpURLConnection
        connection.requestMethod = "POST"
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        connection.setRequestProperty("Accept", "application/json")
        connection.setRequestProperty("Authorization", "Bearer $token")
        connection.doOutput = true
        connection.connectTimeout = 15000
        connection.readTimeout = 20000
        try {
            connection.outputStream.use { os ->
                os.write(payload.toString().toByteArray(charset("UTF-8")))
            }
            val responseCode = connection.responseCode
            val stream = if (responseCode in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream ?: connection.inputStream
            }
            val responseText = java.io.BufferedReader(java.io.InputStreamReader(stream)).use { reader ->
                reader.readText()
            }
            return Pair(responseCode, responseText)
        } finally {
            connection.disconnect()
        }
    }

    private fun parseBackendError(responseText: String, fallback: String): String {
        return try {
            JSONObject(responseText).optString("error", fallback).ifBlank { fallback }
        } catch (error: Exception) {
            fallback
        }
    }

    private fun sanitizeEngineText(message: String): String {
        return message
            .replace("DECART_API_KEY", "LIVE_ENGINE_API_KEY")
            .replace("api.decart.ai", "live engine API", ignoreCase = true)
            .replace("Decart WebRTC", "live engine", ignoreCase = true)
            .replace("Decart realtime", "live", ignoreCase = true)
            .replace("Decart", "live engine", ignoreCase = true)
    }

    private fun decartStatusFromResponse(response: JSONObject, prompt: String): String {
        val status = response.optString("status", "running")
        return "Live output $status. ${prompt.take(90)}"
    }

    private fun showMiniWindowPermissionNotice() {
        if (StreamSessionController.hasOverlayPermission(this)) {
            StreamSessionController.showMiniWindow(this)
            binding.tvAiOutputStatus.text = "Live output is feeding the virtual camera. Open cloned WhatsApp to use it."
            return
        }

        android.app.AlertDialog.Builder(this)
            .setTitle("Mini window permission")
            .setMessage("Allow Display over other apps so live output can stay visible while cloned WhatsApp uses the virtual camera.")
            .setPositiveButton("OPEN SETTINGS") { _, _ ->
                startActivity(StreamSessionController.overlayPermissionIntent(this))
            }
            .setNegativeButton("LATER", null)
            .show()
    }

    private fun minimizeForVideoCall() {
        if (isStreaming && StreamSessionController.isActive()) {
            StreamSessionController.showMiniWindow(this)
        }
        minimizeApp()
    }

    private fun openClonedAppsForVideoCall() {
        if (isStreaming && StreamSessionController.isActive()) {
            StreamSessionController.showMiniWindow(this)
        }
        // Keep this activity (and the live XMax session) alive; just bring the cloned apps list forward.
        StreamSessionController.openClonedApps(this@StreamActivity)
    }

    private fun connectToDecartSession() {
        if (!ensureDecartRuntimePermissions()) {
            return
        }
        if (StreamAuthManager.getWalletBalance(this) <= 0) {
            Toast.makeText(this, "No credits remaining. Buy credits before connecting.", Toast.LENGTH_LONG).show()
            return
        }

        val mode = selectedModeKey()
        if (mode == "face_swap" && selectedFaceImageUri == null) {
            Toast.makeText(this, "Select a face image before starting Face Swap.", Toast.LENGTH_LONG).show()
            return
        }

        VirtualCameraBridge.setApplicationContext(this)
        VirtualCameraBridge.setActiveSource(applicationContext, VirtualCameraBridge.SOURCE_XMAX)
        binding.tvAiOutputTitle.text = "CONNECTING LIVE OUTPUT"
        binding.tvAiOutputStatus.text = "Preparing live engine session..."
        binding.btnConnectStream.isEnabled = false
        binding.btnConnectStream.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#EEEEEE"))
        binding.btnConnectStream.setTextColor(Color.parseColor("#9E9E9E"))

        val deviceId = StreamAuthManager.getDeviceId(this)
        val prompt = currentDecartPrompt()
        val preset = selectedPresetLabel()
        val quality = selectedQualityKey()
        val fps = selectedFrameRate()

        networkExecutor.execute {
            try {
                var base64Image = ""
                var faceImageMimeType = ""
                if (mode == "face_swap") {
                    val faceImage = readFaceImageForDecart(
                        selectedFaceImageUri ?: throw IllegalStateException("Select a face image before starting Face Swap.")
                    )
                    base64Image = faceImage.first
                    faceImageMimeType = faceImage.second
                }

                val sessionResponse = postBackendJson("start-session", JSONObject()
                    .put("provider", "xmax").put("platform", "android")
                    .put("installationId", deviceId))
                if (sessionResponse.first !in 200..299) {
                    throw IllegalStateException(parseBackendError(sessionResponse.second, "Could not start XMax."))
                }
                val transformJson = JSONObject(sessionResponse.second)
                if (!transformJson.optBoolean("allowed") || transformJson.optString("provider") != "xmax") {
                    throw IllegalStateException(transformJson.optString("error", "XMax is not available."))
                }
                val clientApiKey = transformJson.getString("token")
                val sessionId = transformJson.getString("sessionId")
                currentStreamSessionId = sessionId
                val model = transformJson.getString("model")
                val maxSessionDuration = transformJson.getInt("maxSeconds")
                serverClockOffsetMs = transformJson.getLong("serverNow") - System.currentTimeMillis()
                val resolvedPrompt = prompt
                val useBackCamera = binding.spinnerCameraSelect.selectedItemPosition == 1

                runOnUiThread {
                    if (isDestroyed || isFinishing) {
                        finishBackendSession(sessionId)
                        return@runOnUiThread
                    }
                    binding.tvAiOutputTitle.text = "LIVE OUTPUT"
                    binding.tvAiOutputStatus.text = "Opening live camera..."
                    binding.layoutAiOutputPlaceholder.visibility = View.GONE
                    binding.layoutDecartRemoteOutput.visibility = View.VISIBLE
                    binding.layoutDecartRemoteOutput.removeAllViews()
                    releaseCamera()
                    XmaxRealtimeBridge.connect(
                        this@StreamActivity, binding.layoutDecartRemoteOutput,
                        clientApiKey, resolvedPrompt,
                        base64Image.takeIf { it.isNotBlank() }?.let { android.util.Base64.decode(it, android.util.Base64.DEFAULT) },
                        useBackCamera, fps, sessionId, serverClockOffsetMs,
                        { status -> binding.tvAiOutputStatus.text = status },
                        {
                            if (!isStreaming) startLiveStreamState(sessionId, model, maxSessionDuration)
                            binding.tvAiOutputStatus.text = "XMax video is feeding the virtual camera."
                            showMiniWindowPermissionNotice()
                        },
                        { error ->
                            stopLiveStream()
                            binding.btnConnectStream.isEnabled = true
                            binding.layoutDecartRemoteOutput.removeAllViews()
                            binding.layoutDecartRemoteOutput.visibility = View.GONE
                            binding.layoutAiOutputPlaceholder.visibility = View.VISIBLE
                            binding.tvAiOutputStatus.text = error.message ?: "XMax failed. Try again."
                        }
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG_STREAM_LIFECYCLE, "connect_live_engine_failed", e)
                runOnUiThread {
                    binding.btnConnectStream.isEnabled = true
                    binding.btnConnectStream.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#D32F2F"))
                    binding.btnConnectStream.setTextColor(Color.WHITE)
                    binding.tvAiOutputTitle.text = "LIVE OUTPUT ERROR"
                    binding.tvAiOutputStatus.text = sanitizeEngineText(e.message ?: "Unable to connect to the live engine.")
                    Toast.makeText(this@StreamActivity, sanitizeEngineText(e.message ?: "Unable to connect to the live engine."), Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun startLiveStreamState(
        sessionId: String = "morphly_${System.currentTimeMillis()}",
        model: String = DECART_REALTIME_MODEL,
        maxSessionDurationSeconds: Int = 900
    ) {
        synchronized(pendingUsageSeconds) { pendingUsageSeconds.clear() }
        lastUsageEpoch = (System.currentTimeMillis() + serverClockOffsetMs) / 1000L - 1L
        isStreaming = true
        currentStreamSessionId = sessionId
        currentStreamModel = model
        creditUsageSequence = 0
        consecutiveCreditSyncFailures = 0
        StreamSessionController.start(this, sessionId, model, maxSessionDurationSeconds) {
            stopLiveStream()
        }
        binding.btnStopStream.isEnabled = true
        binding.btnStopStream.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#212121"))
        binding.btnStopStream.setTextColor(Color.WHITE)

        binding.btnConnectStream.isEnabled = false
        binding.btnConnectStream.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#EEEEEE"))
        binding.btnConnectStream.setTextColor(Color.parseColor("#9E9E9E"))

        mainHandler.post(creditUsageRunnable)
    }

    private fun toggleFullscreenCamera() {
        isFullscreenCamera = !isFullscreenCamera
        if (isFullscreenCamera) {
            binding.panelCameraFeed.layoutParams.height = android.widget.RelativeLayout.LayoutParams.MATCH_PARENT
            binding.layoutDashboardControls.visibility = View.GONE
            binding.btnFullscreenCamera.text = "Collapse ⛶"
            Toast.makeText(this, "Fullscreen mode enabled", Toast.LENGTH_SHORT).show()
        } else {
            binding.panelCameraFeed.layoutParams.height = dpToPx(340)
            binding.layoutDashboardControls.visibility = View.VISIBLE
            binding.btnFullscreenCamera.text = "⛶"
            Toast.makeText(this, "Fullscreen mode disabled", Toast.LENGTH_SHORT).show()
        }
        binding.panelCameraFeed.requestLayout()
    }

    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }



    private var checkoutDialog: android.app.Dialog? = null
    private var pendingCheckoutTxRef: String? = null

    /** Flutterwave hosted checkout inside an in-app pop-up WebView (no external browser). */
    @android.annotation.SuppressLint("SetJavaScriptEnabled")
    private fun openCheckoutPopup(checkoutUrl: String, txRef: String) {
        if (Uri.parse(checkoutUrl).scheme != "https") {
            Toast.makeText(this, "The payment link is invalid.", Toast.LENGTH_LONG).show()
            return
        }
        checkoutDialog?.dismiss()
        val dp = resources.displayMetrics.density
        val root = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setBackgroundColor(android.graphics.Color.WHITE)
        }
        val header = android.widget.RelativeLayout(this).apply {
            setBackgroundColor(android.graphics.Color.parseColor("#E53935"))
            setPadding((16 * dp).toInt(), 0, 0, 0)
        }
        header.addView(android.widget.TextView(this).apply {
            text = "Secure payment"
            setTextColor(android.graphics.Color.WHITE)
            textSize = 15f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }, android.widget.RelativeLayout.LayoutParams(-2, -2).apply { addRule(android.widget.RelativeLayout.CENTER_VERTICAL) })
        val close = android.widget.TextView(this).apply {
            text = "\u2715"
            setTextColor(android.graphics.Color.WHITE)
            textSize = 20f
            gravity = android.view.Gravity.CENTER
            contentDescription = "Close"
        }
        header.addView(close, android.widget.RelativeLayout.LayoutParams((52 * dp).toInt(), -1).apply { addRule(android.widget.RelativeLayout.ALIGN_PARENT_END) })
        root.addView(header, android.widget.LinearLayout.LayoutParams(-1, (52 * dp).toInt()))
        val progress = android.widget.ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { isIndeterminate = true }
        root.addView(progress, android.widget.LinearLayout.LayoutParams(-1, (4 * dp).toInt()))
        val web = android.webkit.WebView(this)
        root.addView(web, android.widget.LinearLayout.LayoutParams(-1, 0, 1f))

        val dialog = android.app.Dialog(this, android.R.style.Theme_Material_Light_NoActionBar)
        var finished = false
        fun done(returnUri: Uri?) {
            if (finished) return
            finished = true
            Log.i(TAG_STREAM_LIFECYCLE, "checkout_done return=${returnUri != null}")
            dialog.dismiss()
            pendingCheckoutTxRef = null
            getPrefs().edit().remove("pending_tx_ref").apply()
            if (returnUri == null) {
                Toast.makeText(this, "Payment cancelled.", Toast.LENGTH_SHORT).show()
                return
            }
            // Only Flutterwave's redirect to our payment-complete page carries a trusted status.
            val status = if (returnUri.scheme == "https") returnUri.getQueryParameter("status").orEmpty() else ""
            val ref = returnUri.getQueryParameter("tx_ref") ?: returnUri.getQueryParameter("txRef") ?: txRef
            val txId = returnUri.getQueryParameter("transaction_id").orEmpty()
            if (status.isNotEmpty() && status != "successful" && status != "completed") {
                Toast.makeText(this, "Payment not completed. No charge was made.", Toast.LENGTH_LONG).show()
                return
            }
            verifyCheckoutPayment(ref, txId)
        }
        fun isReturn(uri: Uri): Boolean = uri.scheme == "morphly" ||
            (uri.scheme == "https" && uri.host?.endsWith("morphlysm2.vercel.app") == true && uri.path == "/payment-complete.html")
        fun confirmCancel() {
            android.app.AlertDialog.Builder(this)
                .setTitle("Cancel payment?")
                .setMessage("If you already sent the money, choose Keep paying and wait for confirmation.")
                .setPositiveButton("Cancel payment") { _, _ -> Log.i(TAG_STREAM_LIFECYCLE, "checkout_cancel_confirmed"); done(null) }
                .setNegativeButton("Keep paying", null)
                .show()
        }

        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.javaScriptCanOpenWindowsAutomatically = false
        web.webViewClient = object : android.webkit.WebViewClient() {
            override fun shouldOverrideUrlLoading(view: android.webkit.WebView, request: android.webkit.WebResourceRequest): Boolean {
                val uri = request.url
                Log.i(TAG_STREAM_LIFECYCLE, "checkout_nav scheme=${uri.scheme} host=${uri.host}")
                if (isReturn(uri)) { done(uri); return true }
                val scheme = uri.scheme.orEmpty()
                if (scheme == "https" || scheme == "http" || scheme == "about" || scheme == "data" || scheme == "blob") return false
                // Bank apps / USSD / intent:// links: open externally, keep the pop-up open.
                try {
                    val intent = if (scheme == "intent") Intent.parseUri(uri.toString(), Intent.URI_INTENT_SCHEME) else Intent(Intent.ACTION_VIEW, uri)
                    intent.addCategory(Intent.CATEGORY_BROWSABLE); intent.component = null; intent.selector = null
                    startActivity(intent)
                } catch (e: Exception) {
                    Log.w(TAG_STREAM_LIFECYCLE, "checkout_external_link_failed", e)
                }
                return true
            }
            override fun onPageStarted(view: android.webkit.WebView, url: String, favicon: android.graphics.Bitmap?) {
                val uri = Uri.parse(url)
                if (isReturn(uri)) { view.stopLoading(); done(uri) } else progress.visibility = View.VISIBLE
            }
            override fun onPageFinished(view: android.webkit.WebView, url: String) { progress.visibility = View.GONE }
        }
        web.webChromeClient = object : android.webkit.WebChromeClient() {
            // Page-initiated window.close() must not cancel the payment.
            override fun onCloseWindow(window: android.webkit.WebView?) { Log.i(TAG_STREAM_LIFECYCLE, "checkout_window_close_ignored") }
        }
        close.setOnClickListener { confirmCancel() }
        dialog.setContentView(root)
        dialog.setCancelable(false)
        dialog.setCanceledOnTouchOutside(false)
        dialog.setOnKeyListener { _, keyCode, event ->
            if (keyCode == android.view.KeyEvent.KEYCODE_BACK) {
                if (event.action == android.view.KeyEvent.ACTION_UP) { if (web.canGoBack()) web.goBack() else confirmCancel() }
                true
            } else false
        }
        dialog.setOnDismissListener { web.stopLoading(); web.destroy(); checkoutDialog = null }
        checkoutDialog = dialog
        pendingCheckoutTxRef = txRef.ifBlank { null }
        if (txRef.isNotBlank()) getPrefs().edit().putString("pending_tx_ref", txRef).apply()
        dialog.show()
        web.loadUrl(checkoutUrl)
    }

    private fun verifyCheckoutPayment(txRef: String, transactionId: String) {
        if (txRef.isBlank()) { schedulePaymentBalanceRefresh(); return }
        Toast.makeText(this, "Confirming payment...", Toast.LENGTH_SHORT).show()
        networkExecutor.execute {
            var message: String
            var ok = false
            try {
                val token = StreamAuthManager.getValidIdToken(this@StreamActivity, BACKEND_BASE_URL)
                    ?: throw Exception("Your session expired. Sign in again.")
                val connection = java.net.URL("$BACKEND_BASE_URL/payments/verify").openConnection() as java.net.HttpURLConnection
                connection.requestMethod = "POST"
                connection.connectTimeout = 15000
                connection.readTimeout = 30000
                connection.setRequestProperty("Content-Type", "application/json")
                connection.setRequestProperty("Authorization", "Bearer $token")
                connection.doOutput = true
                val body = org.json.JSONObject().put("txRef", txRef)
                if (transactionId.isNotBlank()) body.put("transaction_id", transactionId)
                connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                val code = connection.responseCode
                val text = (if (code in 200..299) connection.inputStream else connection.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
                val json = runCatching { org.json.JSONObject(text) }.getOrNull()
                ok = code == 200 && json?.optBoolean("verified") == true
                message = when {
                    ok -> "Payment successful! Credits added."
                    code == 200 -> "Payment is ${json?.optString("status", "pending")}. Your balance will update shortly."
                    else -> "Payment check failed: ${json?.optString("error") ?: "HTTP $code"}"
                }
            } catch (e: Exception) {
                message = "Payment check failed: ${e.message}"
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                Toast.makeText(this@StreamActivity, message, Toast.LENGTH_LONG).show()
                if (ok) showDashboardState()
                fetchBalanceFromBackend()
                schedulePaymentBalanceRefresh()
            }
        }
    }

    private fun showCryptoPaymentDetails(payment: org.json.JSONObject) {
        val payAddress = payment.optString("payAddress", "").trim()
        val payAmount = payment.optString("payAmount", "").trim()
        val payCurrency = payment.optString("payCurrency", "USDTTRC20").trim()
        val paymentId = payment.optString("paymentId", "").trim()
        val orderId = payment.optString("orderId", "").trim()

        if (payAddress.isEmpty() || payAmount.isEmpty()) {
            Toast.makeText(this, "Crypto payment details are incomplete. Please try again.", Toast.LENGTH_LONG).show()
            return
        }

        val dialog = android.app.Dialog(this)
        val root = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
        }

        val header = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(dpToPx(18), 0, dpToPx(12), 0)
            setBackgroundColor(Color.WHITE)
            addView(
                android.widget.TextView(this@StreamActivity).apply {
                    text = "Crypto Payment"
                    textSize = 20f
                    setTextColor(Color.rgb(28, 30, 36))
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                },
                android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 1f)
            )
            addView(
                android.widget.TextView(this@StreamActivity).apply {
                    text = "CLOSE"
                    textSize = 14f
                    letterSpacing = 0.12f
                    setTextColor(Color.rgb(45, 48, 56))
                    gravity = android.view.Gravity.CENTER
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                    setPadding(dpToPx(16), 0, dpToPx(4), 0)
                    setOnClickListener { dialog.dismiss() }
                },
                android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT
                )
            )
        }
        root.addView(header, android.widget.LinearLayout.LayoutParams(
            android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
            dpToPx(58)
        ))

        val content = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(dpToPx(20), dpToPx(16), dpToPx(20), dpToPx(20))
        }

        fun addLabel(text: String) {
            content.addView(android.widget.TextView(this).apply {
                this.text = text
                textSize = 12f
                setTextColor(Color.rgb(112, 116, 128))
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                setPadding(0, dpToPx(14), 0, dpToPx(6))
            })
        }

        fun addValue(text: String, mono: Boolean = false, selectable: Boolean = false) {
            content.addView(android.widget.TextView(this).apply {
                this.text = text
                textSize = if (mono) 14f else 16f
                setTextColor(Color.rgb(24, 27, 34))
                typeface = if (mono) android.graphics.Typeface.MONOSPACE else android.graphics.Typeface.DEFAULT_BOLD
                setTextIsSelectable(selectable)
                setPadding(dpToPx(12), dpToPx(10), dpToPx(12), dpToPx(10))
                background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(Color.rgb(245, 247, 250))
                    cornerRadius = dpToPx(8).toFloat()
                    setStroke(1, Color.rgb(224, 228, 236))
                }
            })
        }

        fun addCopyButton(label: String, value: String) {
            content.addView(android.widget.Button(this).apply {
                text = label
                setOnClickListener { copyToClipboard(label.removePrefix("Copy "), value) }
            }, android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dpToPx(8)
            })
        }

        content.addView(android.widget.TextView(this).apply {
            text = "Send exactly this amount to the address below."
            textSize = 15f
            setTextColor(Color.rgb(75, 80, 92))
        })

        addLabel("AMOUNT")
        addValue("$payAmount $payCurrency")
        addCopyButton("Copy Amount", payAmount)

        addLabel("NETWORK")
        addValue(cryptoNetworkLabel(payCurrency))

        addLabel("PAYMENT ADDRESS")
        addValue(payAddress, mono = true, selectable = true)
        addCopyButton("Copy Address", payAddress)

        if (paymentId.isNotEmpty()) {
            addLabel("PAYMENT ID")
            addValue(paymentId, mono = true, selectable = true)
        }

        if (orderId.isNotEmpty()) {
            addLabel("ORDER ID")
            addValue(orderId, mono = true, selectable = true)
        }

        content.addView(android.widget.TextView(this).apply {
            text = "Credits are added automatically after the blockchain payment is confirmed."
            textSize = 13f
            setTextColor(Color.rgb(112, 116, 128))
            setPadding(0, dpToPx(18), 0, 0)
        })

        content.addView(android.widget.Button(this).apply {
            text = "I HAVE PAID - REFRESH BALANCE"
            setOnClickListener {
                fetchBalanceFromBackend()
                Toast.makeText(this@StreamActivity, "Checking payment status...", Toast.LENGTH_SHORT).show()
            }
        }, android.widget.LinearLayout.LayoutParams(
            android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = dpToPx(14)
        })

        root.addView(android.widget.ScrollView(this).apply {
            addView(content)
        }, android.widget.LinearLayout.LayoutParams(
            android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
            0,
            1f
        ))

        dialog.setContentView(root)
        dialog.setOnDismissListener {
            if (StreamAuthManager.isLoggedIn(this)) {
                fetchBalanceFromBackend()
            }
        }
        dialog.show()
        dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.WHITE))
        dialog.window?.setDimAmount(0.72f)
        dialog.window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        dialog.window?.setLayout(
            android.view.WindowManager.LayoutParams.MATCH_PARENT,
            (resources.displayMetrics.heightPixels * 0.94f).toInt()
        )
    }

    private fun copyToClipboard(label: String, value: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText(label, value))
        Toast.makeText(this, "$label copied", Toast.LENGTH_SHORT).show()
    }

    private fun cryptoNetworkLabel(currency: String): String {
        return when (currency.uppercase()) {
            "USDTTRC20" -> "TRON (TRC20)"
            "USDTERC20" -> "Ethereum (ERC20)"
            "USDTBEP20" -> "BNB Smart Chain (BEP20)"
            "USDTMATIC" -> "Polygon"
            else -> currency
        }
    }

    private fun checkoutErrorMessage(error: Exception): String {
        val message = error.message ?: return "Checkout failed. Please sign in again."
        if (message.contains("not logged in", ignoreCase = true) ||
            message.contains("Unauthorized: invalid token", ignoreCase = true) ||
            message.contains("Unauthorized: missing authorization token", ignoreCase = true)
        ) {
            return "Your saved login session is invalid. Please sign in again."
        }
        if (message.contains("PRIVATE KEY", ignoreCase = true) ||
            message.contains("BEGIN ", ignoreCase = true) ||
            message.contains("Bearer ", ignoreCase = true) ||
            message.contains("Authorization", ignoreCase = true) ||
            message.contains("payment configuration", ignoreCase = true)
        ) {
            return "Payment gateway configuration is invalid. Please contact support."
        }
        return message
    }

    private fun processCheckoutPayment(gateway: String) {
        val packageId = selectedPackageId

        val progressDialog = android.app.ProgressDialog(this).apply {
            setMessage("Redirecting to $gateway payment gateway...")
            setCancelable(false)
            show()
        }

        networkExecutor.execute {
            try {
                val token = StreamAuthManager.getValidIdToken(
                    this@StreamActivity,
                    BACKEND_BASE_URL
                ) ?: throw Exception("Your session expired. Sign in again.")
                val url = java.net.URL("$BACKEND_BASE_URL/create-checkout")
                val connection = url.openConnection() as java.net.HttpURLConnection
                connection.requestMethod = "POST"
                connection.setRequestProperty("Content-Type", "application/json")
                connection.setRequestProperty("Authorization", "Bearer $token")
                connection.doOutput = true

                val jsonInputString = org.json.JSONObject().put("packageId", packageId).toString()
                connection.outputStream.use { os ->
                    val input = jsonInputString.toByteArray(Charsets.UTF_8)
                    os.write(input, 0, input.size)
                }

                if (connection.responseCode == 200) {
                    val reader = java.io.BufferedReader(java.io.InputStreamReader(connection.inputStream))
                    val response = java.lang.StringBuilder()
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        response.append(line)
                    }
                    reader.close()

                    val jsonObject = org.json.JSONObject(response.toString())
                    if (jsonObject.has("checkoutUrl")) {
                        val checkoutUrl = jsonObject.getString("checkoutUrl")
                        getPrefs().edit().putString("pending_plan_name", selectedPlanName)
                            .putInt("pending_plan_balance", StreamAuthManager.getWalletBalance(this@StreamActivity)).apply()
                        runOnUiThread {
                            progressDialog.dismiss()
                            openCheckoutPopup(checkoutUrl, jsonObject.optString("txRef"))
                        }
                    } else {
                        throw Exception("Missing checkoutUrl")
                    }
                } else {
                    val reader = java.io.BufferedReader(java.io.InputStreamReader(connection.errorStream))
                    val response = java.lang.StringBuilder()
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        response.append(line)
                    }
                    reader.close()
                    val errorObj = org.json.JSONObject(response.toString())
                    throw Exception(errorObj.optString("error", "Failed to create checkout"))
                }
            } catch (e: Exception) {
                e.printStackTrace()
                runOnUiThread {
                    progressDialog.dismiss()
                    val message = checkoutErrorMessage(e)
                    Toast.makeText(this@StreamActivity, "Checkout Error: $message", Toast.LENGTH_LONG).show()
                    if (message.contains("sign in again", ignoreCase = true)) {
                        showAuthState()
                    }
                }
            }
        }
    }

    private fun processCryptoCheckoutPayment() {
        val packageId = selectedPackageId

        val progressDialog = android.app.ProgressDialog(this).apply {
            setMessage("Generating Crypto Invoice...")
            setCancelable(false)
            show()
        }

        networkExecutor.execute {
            try {
                val token = StreamAuthManager.getValidIdToken(
                    this@StreamActivity,
                    BACKEND_BASE_URL
                ) ?: throw Exception("Your session expired. Sign in again.")
                val url = java.net.URL("$BACKEND_BASE_URL/create-crypto-checkout")
                val connection = url.openConnection() as java.net.HttpURLConnection
                connection.requestMethod = "POST"
                connection.setRequestProperty("Content-Type", "application/json")
                connection.setRequestProperty("Authorization", "Bearer $token")
                connection.doOutput = true

                val jsonInputString = org.json.JSONObject().put("packageId", packageId).toString()
                connection.outputStream.use { os ->
                    val input = jsonInputString.toByteArray(Charsets.UTF_8)
                    os.write(input, 0, input.size)
                }

                if (connection.responseCode == 200) {
                    val reader = java.io.BufferedReader(java.io.InputStreamReader(connection.inputStream))
                    val response = java.lang.StringBuilder()
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        response.append(line)
                    }
                    reader.close()

                    val jsonObject = org.json.JSONObject(response.toString())
                    if (jsonObject.has("cryptoPayment")) {
                        val cryptoPayment = jsonObject.getJSONObject("cryptoPayment")
                        runOnUiThread {
                            progressDialog.dismiss()
                            showCryptoPaymentDetails(cryptoPayment)
                        }
                    } else if (jsonObject.has("checkoutUrl")) {
                        val checkoutUrl = jsonObject.getString("checkoutUrl")
                        runOnUiThread {
                            progressDialog.dismiss()
                            openCheckoutPopup(checkoutUrl, "")
                        }
                    } else {
                        throw Exception("Missing checkoutUrl")
                    }
                } else {
                    val errorReader = java.io.BufferedReader(java.io.InputStreamReader(connection.errorStream))
                    val errorResponse = java.lang.StringBuilder()
                    var line: String?
                    while (errorReader.readLine().also { line = it } != null) {
                        errorResponse.append(line)
                    }
                    errorReader.close()
                    val errorObj = org.json.JSONObject(errorResponse.toString())
                    throw Exception(errorObj.optString("error", "Failed to create invoice"))
                }
            } catch (e: Exception) {
                e.printStackTrace()
                runOnUiThread {
                    progressDialog.dismiss()
                    val message = checkoutErrorMessage(e)
                    Toast.makeText(this@StreamActivity, "Crypto checkout failed: $message", Toast.LENGTH_LONG).show()
                    if (message.contains("sign in again", ignoreCase = true)) {
                        showAuthState()
                    }
                }
            }
        }
    }

    private fun openWhatsAppContact() {
        val message = "Hello Morphly Support, I would like to purchase the $selectedPlanName Plan for total ${formatNaira(selectedPlanPrice)}."
        try {
            val url = "https://api.whatsapp.com/send?phone=2349033333333&text=" + java.net.URLEncoder.encode(message, "UTF-8")
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "WhatsApp API: https://wa.me/2349033333333", Toast.LENGTH_LONG).show()
        }
    }

    private fun openEmailContact() {
        val subject = "Morphly Purchase Request - $selectedPlanName Plan"
        val body = "Hello,\n\nI want to purchase the $selectedPlanName Plan (${formatNaira(selectedPlanPrice)}).\nMy unique Device ID is: ${StreamAuthManager.getDeviceId(this)}"
        val intent = Intent(Intent.ACTION_SENDTO).apply {
            data = Uri.parse("mailto:support@morphly.com")
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_TEXT, body)
        }
        try {
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "Email: support@morphly.com", Toast.LENGTH_LONG).show()
        }
    }

    private fun fetchPackagesFromBackend() {
        networkExecutor.execute {
            try {
                val url = java.net.URL("$BACKEND_BASE_URL/packages")
                val connection = url.openConnection() as java.net.HttpURLConnection
                connection.requestMethod = "GET"
                connection.connectTimeout = 5000
                connection.readTimeout = 5000

                if (connection.responseCode == 200) {
                    val reader = java.io.BufferedReader(java.io.InputStreamReader(connection.inputStream))
                    val response = java.lang.StringBuilder()
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        response.append(line)
                    }
                    reader.close()

                    val jsonStr = response.toString()
                    try {
                        val jsonObject = org.json.JSONObject(jsonStr)
                        val packagesArray = jsonObject.getJSONArray("packages")
                        val count = packagesArray.length()
                        if (count > 0) {
                            runOnUiThread {
                                if (count > 0) {
                                    val p1 = packagesArray.getJSONObject(0)
                                    pkgBasicId = p1.optString("id", "basic")
                                    binding.tvPlanBasicTitle.text = p1.optString("name", "Basic")
                                    binding.tvPlanBasicPrice.text = formatNairaWhole(p1.optInt("price", 29000))
                                    binding.tvPlanBasicCredits.text = String.format("%,d", p1.optInt("credits", 1000)) + " Credits"
                                    binding.tvPlanBasicTime.text = p1.optString("timeLabel", "~8m 20s")
                                }

                                if (count > 1) {
                                    val p2 = packagesArray.getJSONObject(1)
                                    pkgProId = p2.optString("id", "pro")
                                    binding.tvPlanProTitle.text = p2.optString("name", "Pro")
                                    binding.tvPlanProPrice.text = formatNairaWhole(p2.optInt("price", 58000))
                                    binding.tvPlanProCredits.text = String.format("%,d", p2.optInt("credits", 2000)) + " Credits"
                                    binding.tvPlanProTime.text = p2.optString("timeLabel", "~16m 40s")
                                }

                                if (count > 2) {
                                    val p3 = packagesArray.getJSONObject(2)
                                    pkgEnterpriseId = p3.optString("id", "enterprise")
                                    binding.tvPlanEnterpriseTitle.text = p3.optString("name", "Enterprise")
                                    binding.tvPlanEnterprisePrice.text = formatNairaWhole(p3.optInt("price", 145000))
                                    binding.tvPlanEnterpriseCredits.text = String.format("%,d", p3.optInt("credits", 5000)) + " Credits"
                                    binding.tvPlanEnterpriseTime.text = p3.optString("timeLabel", "~41m 40s")
                                }

                                if (count > 3) {
                                    val p4 = packagesArray.getJSONObject(3)
                                    pkgVipId = p4.optString("id", "vip")
                                    binding.tvPlanVipTitle.text = p4.optString("name", "VIP")
                                    binding.tvPlanVipPrice.text = formatNairaWhole(p4.optInt("price", 290000))
                                    binding.tvPlanVipCredits.text = String.format("%,d", p4.optInt("credits", 10000)) + " Credits"
                                    binding.tvPlanVipTime.text = p4.optString("timeLabel", "~83m 20s")
                                }
                            }
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }


    private fun fetchNotificationFromBackend() {
        networkExecutor.execute {
            try {
                val url = java.net.URL("$BACKEND_BASE_URL/config")
                val connection = url.openConnection() as java.net.HttpURLConnection
                connection.requestMethod = "GET"
                connection.connectTimeout = 5000
                connection.readTimeout = 5000

                if (connection.responseCode == 200) {
                    val reader = java.io.BufferedReader(java.io.InputStreamReader(connection.inputStream))
                    val response = java.lang.StringBuilder()
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        response.append(line)
                    }
                    reader.close()

                    val jsonStr = response.toString()
                    try {
                        val jsonObject = org.json.JSONObject(jsonStr)
                        if (jsonObject.has("notification")) {
                            val notification = jsonObject.getString("notification")
                            if (notification.isNotBlank()) {
                                runOnUiThread {
                                    android.app.AlertDialog.Builder(this@StreamActivity)
                                        .setTitle("Notification")
                                        .setMessage(notification)
                                        .setPositiveButton("OK", null)
                                        .show()
                                }
                            }
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    override fun onDestroy() {
        finishBackendSession(currentStreamSessionId)
        currentStreamSessionId = ""
        isStreaming = false
        mainHandler.removeCallbacksAndMessages(null)
        XmaxRealtimeBridge.disconnect()
        StreamSessionController.stop()
        StreamPreviewBridge.clear()
        releaseCamera()
        stopBackgroundThread()
        networkExecutor.shutdown()
        super.onDestroy()
    }
}
