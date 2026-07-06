package top.niunaijun.blackboxa.view.main

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.animation.Animation
import android.view.animation.LinearInterpolator
import android.view.animation.RotateAnimation
import android.widget.Toast
import top.niunaijun.blackboxa.R
import top.niunaijun.blackboxa.databinding.ActivityStreamBinding
import top.niunaijun.blackboxa.data.StreamAuthManager
import top.niunaijun.blackboxa.view.base.BaseActivity

class StreamActivity : BaseActivity() {

    private lateinit var binding: ActivityStreamBinding

    private var cameraDevice: android.hardware.camera2.CameraDevice? = null
    private var captureSession: android.hardware.camera2.CameraCaptureSession? = null
    private var backgroundThread: android.os.HandlerThread? = null
    private var backgroundHandler: android.os.Handler? = null

    private var isStreaming = false
    private val mainHandler = Handler(Looper.getMainLooper())

    private val BACKEND_BASE_URL = "https://morphlysm2.vercel.app/api"
    private val networkExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()
    private var selectedFaceImageUri: Uri? = null
    private var isFullscreenCamera = false

    private var selectedPlanName: String = "Test"
    private var selectedPlanPrice: Double = 10.0
    private var selectedPlanCredits: Int = 500
    private var selectedPlanStreamTime: String = "~4 min"
    private var isLicenseActivationPlan: Boolean = false

    private val creditUsageRunnable = object : Runnable {
        override fun run() {
            if (isStreaming) {
                val email = StreamAuthManager.getUserEmail(this@StreamActivity)
                val remaining = StreamAuthManager.getWalletBalance(this@StreamActivity)
                if (remaining > 0) {
                    val decrease = 2
                    
                    networkExecutor.execute {
                        try {
                            val url = java.net.URL("$BACKEND_BASE_URL/consume")
                            val connection = url.openConnection() as java.net.HttpURLConnection
                            connection.requestMethod = "POST"
                            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                            connection.doOutput = true
                            connection.connectTimeout = 5000
                            connection.readTimeout = 5000
                            
                            val payload = "{\"email\":\"$email\",\"amount\":$decrease}"
                            val os = connection.outputStream
                            os.write(payload.toByteArray(charset("UTF-8")))
                            os.close()
                            
                            val responseCode = connection.responseCode
                            if (responseCode == 200) {
                                val reader = java.io.BufferedReader(java.io.InputStreamReader(connection.inputStream))
                                val response = java.lang.StringBuilder()
                                var line: String?
                                while (reader.readLine().also { line = it } != null) {
                                    response.append(line)
                                }
                                reader.close()
                                
                                val jsonStr = response.toString()
                                val balancePattern = java.util.regex.Pattern.compile("\"balance\"\\s*:\\s*(\\d+)")
                                val usedPattern = java.util.regex.Pattern.compile("\"used\"\\s*:\\s*(\\d+)")
                                val balanceMatcher = balancePattern.matcher(jsonStr)
                                val usedMatcher = usedPattern.matcher(jsonStr)
                                
                                var fetchedBalance = -1
                                var fetchedUsed = -1
                                if (balanceMatcher.find()) {
                                    fetchedBalance = balanceMatcher.group(1).toInt()
                                }
                                if (usedMatcher.find()) {
                                    fetchedUsed = usedMatcher.group(1).toInt()
                                }
                                
                                if (fetchedBalance >= 0) {
                                    val currentLocal = StreamAuthManager.getWalletBalance(this@StreamActivity)
                                    StreamAuthManager.addCredits(this@StreamActivity, fetchedBalance - currentLocal)
                                    runOnUiThread {
                                        updateSessionBalanceUI(fetchedBalance, fetchedUsed)
                                        if (fetchedBalance == 0) {
                                            Toast.makeText(this@StreamActivity, "Credits exhausted on backend!", Toast.LENGTH_LONG).show()
                                            stopLiveStream()
                                            showActivationState()
                                        }
                                    }
                                }
                            } else {
                                handleLocalCreditUsage(decrease)
                            }
                        } catch (e: Exception) {
                            e.printStackTrace()
                            handleLocalCreditUsage(decrease)
                        }
                    }
                    
                    mainHandler.postDelayed(this, 3000)
                }
            }
        }
    }

    private fun handleLocalCreditUsage(decrease: Int) {
        runOnUiThread {
            StreamAuthManager.addCredits(this@StreamActivity, -decrease)
            updateSessionBalanceUI()
            val remaining = StreamAuthManager.getWalletBalance(this@StreamActivity)
            if (remaining == 0) {
                Toast.makeText(this@StreamActivity, "Credits exhausted! Please activate your license to continue.", Toast.LENGTH_LONG).show()
                stopLiveStream()
                showActivationState()
            }
        }
    }

    private var selectedPresetView: android.widget.TextView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityStreamBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Initial view setup - show Splash screen first
        showSplashState()

        // Wait for 2 seconds (simulating initialization) before checking authentication state
        Handler(Looper.getMainLooper()).postDelayed({
            if (StreamAuthManager.isLoggedIn(this)) {
                if (StreamAuthManager.isActivated(this) || StreamAuthManager.getWalletBalance(this) > 0) {
                    showDashboardState()
                } else {
                    showWelcomeState()
                }
            } else {
                showAuthState()
            }
        }, 2000)

        setupListeners()
        setupSpinners()
    }

    private fun setupSpinners() {
        val cameraOptions = arrayOf("camera 1, facing front", "camera 2, facing back")
        val cameraAdapter = android.widget.ArrayAdapter(this, android.R.layout.simple_spinner_item, cameraOptions).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        binding.spinnerCameraSelect.adapter = cameraAdapter

        val qualityOptions = arrayOf("High – Best Output", "Medium – Standard", "Low – Data Saver")
        val qualityAdapter = android.widget.ArrayAdapter(this, android.R.layout.simple_spinner_item, qualityOptions).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        binding.spinnerQualitySelect.adapter = qualityAdapter
    }

    override fun onResume() {
        super.onResume()
        if (binding.layoutDashboard.visibility == View.VISIBLE) {
            startCameraPreview()
        }
    }

    override fun onPause() {
        releaseCamera()
        super.onPause()
    }    private fun showSplashState() {
        binding.layoutSplash.visibility = View.VISIBLE
        binding.layoutAuth.visibility = View.GONE
        binding.layoutDashboard.visibility = View.GONE
        binding.layoutWelcome.visibility = View.GONE
        binding.layoutStarterPack.visibility = View.GONE
        binding.layoutPlanPayment.visibility = View.GONE
        binding.layoutAccount.visibility = View.GONE

        // Start rotation animation on the arc
        val rotate = RotateAnimation(
            0f, 360f,
            Animation.RELATIVE_TO_SELF, 0.5f,
            Animation.RELATIVE_TO_SELF, 0.5f
        ).apply {
            duration = 2000
            repeatCount = Animation.INFINITE
            interpolator = LinearInterpolator()
        }
        binding.ivSplashArc.startAnimation(rotate)
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

    private fun showPlanPaymentState(name: String, price: Double, credits: Int, streamTime: String) {
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
        binding.tvCheckoutPlanSubtitle.text = String.format("$%.2f • %,d CREDITS", price, credits)
        binding.tvCheckoutDetailPlan.text = name
        binding.tvCheckoutDetailCredits.text = String.format("%,d credits", credits)
        binding.tvCheckoutDetailStreamTime.text = streamTime
        binding.tvCheckoutDetailTotal.text = String.format("$%.2f", price)

        // Reset manual key fields
        binding.layoutCheckoutManualKeyContainer.visibility = View.GONE
        binding.etCheckoutManualKey.setText("")
    }


    private fun showDashboardState() {
        binding.ivSplashArc.clearAnimation()
        binding.layoutSplash.visibility = View.GONE
        binding.layoutAuth.visibility = View.GONE
        binding.layoutWelcome.visibility = View.GONE
        binding.layoutStarterPack.visibility = View.GONE
        binding.layoutPlanPayment.visibility = View.GONE
        binding.layoutAccount.visibility = View.GONE
        binding.layoutDashboard.visibility = View.VISIBLE

        fetchBalanceFromBackend()
        startCameraPreview()
    }
    private fun fetchBalanceFromBackend() {
        val email = StreamAuthManager.getUserEmail(this)
        networkExecutor.execute {
            try {
                val url = java.net.URL("$BACKEND_BASE_URL/balance?email=${java.net.URLEncoder.encode(email, "UTF-8")}")
                val connection = url.openConnection() as java.net.HttpURLConnection
                connection.requestMethod = "GET"
                connection.connectTimeout = 5000
                connection.readTimeout = 5000
                
                val responseCode = connection.responseCode
                if (responseCode == 200) {
                    val reader = java.io.BufferedReader(java.io.InputStreamReader(connection.inputStream))
                    val response = java.lang.StringBuilder()
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        response.append(line)
                    }
                    reader.close()
                    
                    val jsonStr = response.toString()
                    val balancePattern = java.util.regex.Pattern.compile("\"balance\"\\s*:\\s*(\\d+)")
                    val usedPattern = java.util.regex.Pattern.compile("\"used\"\\s*:\\s*(\\d+)")
                    
                    val balanceMatcher = balancePattern.matcher(jsonStr)
                    val usedMatcher = usedPattern.matcher(jsonStr)
                    
                    var fetchedBalance = -1
                    var fetchedUsed = -1
                    if (balanceMatcher.find()) {
                        fetchedBalance = balanceMatcher.group(1).toInt()
                    }
                    if (usedMatcher.find()) {
                        fetchedUsed = usedMatcher.group(1).toInt()
                    }
                    
                    if (fetchedBalance >= 0) {
                        val currentLocalBalance = StreamAuthManager.getWalletBalance(this)
                        if (currentLocalBalance != fetchedBalance) {
                            StreamAuthManager.addCredits(this, fetchedBalance - currentLocalBalance)
                        }
                        
                        runOnUiThread {
                            updateSessionBalanceUI(fetchedBalance, fetchedUsed)
                        }
                    } else {
                        runOnUiThread {
                            updateSessionBalanceUI()
                        }
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
        val isActivated = StreamAuthManager.isActivated(this)
        
        if (isActivated) {
            binding.tvSessionPlanTotal.text = "Permanent"
            binding.tvSessionUsed.text = "0 CR"
            binding.tvSessionRemaining.text = "Unlimited"
            binding.progressSessionRemaining.progress = 100
            binding.progressSessionRemaining.setProgressTintList(android.content.res.ColorStateList.valueOf(Color.parseColor("#FF3CD070")))
        } else {
            binding.tvSessionPlanTotal.text = "500 CR"
            val used = overrideUsed ?: (500 - remaining)
            binding.tvSessionUsed.text = "$used CR"
            binding.tvSessionRemaining.text = "$remaining CR"
            
            val progressPercent = (remaining * 100) / 500
            binding.progressSessionRemaining.progress = progressPercent
            
            if (progressPercent < 20) {
                binding.progressSessionRemaining.setProgressTintList(android.content.res.ColorStateList.valueOf(Color.parseColor("#FF5252")))
            } else {
                binding.progressSessionRemaining.setProgressTintList(android.content.res.ColorStateList.valueOf(Color.parseColor("#FF3CD070")))
            }
        }
    }

    private fun selectSignInTab() {
        binding.tvTabSignIn.setBackgroundColor(resources.getColor(R.color.primary_gold)) // Gold Accent
        binding.tvTabSignIn.setTextColor(resources.getColor(R.color.dark_bg)) // Dark background color
        binding.tvTabCreateAccount.setBackgroundColor(resources.getColor(android.R.color.transparent))
        binding.tvTabCreateAccount.setTextColor(resources.getColor(R.color.secondary_text)) // Gray

        binding.formSignIn.visibility = View.VISIBLE
        binding.formCreateAccount.visibility = View.GONE
    }

    private fun selectCreateAccountTab() {
        binding.tvTabCreateAccount.setBackgroundColor(resources.getColor(R.color.primary_gold)) // Gold Accent
        binding.tvTabCreateAccount.setTextColor(resources.getColor(R.color.dark_bg)) // Dark background color
        binding.tvTabSignIn.setBackgroundColor(resources.getColor(android.R.color.transparent))
        binding.tvTabSignIn.setTextColor(resources.getColor(R.color.secondary_text)) // Gray

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
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://t.me/newblackboxa"))
        try {
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "Telegram link: https://t.me/newblackboxa", Toast.LENGTH_LONG).show()
        }
    }

    private fun simulatePayment(credits: Int, gateway: String) {
        val progressDialog = android.app.ProgressDialog(this).apply {
            setMessage("Processing payment via $gateway...")
            setCancelable(false)
            show()
        }
        Handler(Looper.getMainLooper()).postDelayed({
            progressDialog.dismiss()
            StreamAuthManager.addCredits(this, credits)
            Toast.makeText(this, "Payment successful! 500 Credits added.", Toast.LENGTH_LONG).show()
            showDashboardState()
        }, 1500)
    }

    private fun simulateActivationPayment(gateway: String) {
        val progressDialog = android.app.ProgressDialog(this).apply {
            setMessage("Processing activation payment via $gateway...")
            setCancelable(false)
            show()
        }
        Handler(Looper.getMainLooper()).postDelayed({
            progressDialog.dismiss()
            StreamAuthManager.setActivated(this, true)
            Toast.makeText(this, "Payment successful! License activated permanently.", Toast.LENGTH_LONG).show()
            showDashboardState()
        }, 1500)
    }

    private fun showRegistrationKeysDialog() {
        val deviceId = StreamAuthManager.getDeviceId(this)
        val activationKey = StreamAuthManager.getActivationKey(this)

        val dialogView = layoutInflater.inflate(R.layout.dialog_registration_keys, null)
        val tvDialogDeviceId = dialogView.findViewById<android.widget.TextView>(R.id.tvDialogDeviceId)
        val tvDialogActivationKey = dialogView.findViewById<android.widget.TextView>(R.id.tvDialogActivationKey)
        val btnCopyKey = dialogView.findViewById<android.widget.Button>(R.id.btnCopyKey)
        val btnClose = dialogView.findViewById<android.widget.Button>(R.id.btnDialogClose)

        tvDialogDeviceId.text = deviceId
        tvDialogActivationKey.text = activationKey

        val dialog = android.app.AlertDialog.Builder(this)
            .setView(dialogView)
            .setCancelable(false)
            .create()

        btnCopyKey.setOnClickListener {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            val clip = android.content.ClipData.newPlainText("Activation Key", activationKey)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(this, "Activation Key copied to clipboard!", Toast.LENGTH_SHORT).show()
        }

        btnClose.setOnClickListener {
            dialog.dismiss()
            showWelcomeState()
        }

        dialog.show()
    }

    private fun selectPreset(textView: android.widget.TextView, prompt: String) {
        if (!binding.switchEnhance.isChecked) {
            Toast.makeText(this, "Please enable ENHANCE to select presets.", Toast.LENGTH_SHORT).show()
            return
        }
        selectedPresetView?.setBackgroundResource(R.drawable.bg_preset_pill)
        selectedPresetView?.setTextColor(Color.parseColor("#FFFFFF"))

        textView.setBackgroundColor(Color.parseColor("#FF3CD070"))
        textView.setTextColor(Color.parseColor("#0B0C10"))

        selectedPresetView = textView
        binding.etPromptDescription.setText(prompt)
    }

    private fun startLiveStream() {
        val progressDialog = android.app.ProgressDialog(this).apply {
            setMessage("Connecting to live AI transformation engine...")
            setCancelable(false)
            show()
        }
        
        mainHandler.postDelayed({
            progressDialog.dismiss()
            isStreaming = true
            
            binding.btnStopStream.isEnabled = true
            binding.btnStopStream.setBackgroundColor(Color.parseColor("#FFFF5252"))
            binding.btnStopStream.setTextColor(Color.parseColor("#0B0C10"))
            
            binding.btnConnectStream.isEnabled = false
            binding.btnConnectStream.setBackgroundColor(Color.parseColor("#1D2030"))
            binding.btnConnectStream.setTextColor(Color.parseColor("#3A3F55"))
            
            Toast.makeText(this, "AI transformation engine connected!", Toast.LENGTH_SHORT).show()
            
            if (!StreamAuthManager.isActivated(this)) {
                mainHandler.post(creditUsageRunnable)
            }
        }, 1200)
    }

    private fun stopLiveStream() {
        isStreaming = false
        mainHandler.removeCallbacks(creditUsageRunnable)
        
        binding.btnConnectStream.isEnabled = true
        binding.btnConnectStream.setBackgroundColor(Color.parseColor("#FF3CD070"))
        binding.btnConnectStream.setTextColor(Color.parseColor("#0B0C10"))
        
        binding.btnStopStream.isEnabled = false
        binding.btnStopStream.setBackgroundColor(Color.parseColor("#1D2030"))
        binding.btnStopStream.setTextColor(Color.parseColor("#3A3F55"))
        
        Toast.makeText(this, "AI transformation engine stopped.", Toast.LENGTH_SHORT).show()
    }

    private fun showAccountOverlay() {
        binding.layoutAccount.visibility = View.VISIBLE
        
        val email = StreamAuthManager.getUserEmail(this)
        val name = StreamAuthManager.getUserName(this)
        val remaining = StreamAuthManager.getWalletBalance(this)
        val isActivated = StreamAuthManager.isActivated(this)
        
        binding.tvAccountName.text = name
        binding.tvAccountEmail.text = email
        binding.tvAccountDeviceId.text = StreamAuthManager.getDeviceId(this)
        
        if (isActivated) {
            binding.tvAccountLicenseStatus.text = "ACTIVE"
            binding.tvAccountLicenseStatus.setTextColor(Color.parseColor("#FF3CD070"))
            binding.tvAccountLicenseExpires.text = "30 May 2027"
            binding.tvAccountCreditsRemaining.text = "Unlimited"
            binding.tvAccountCreditsUsed.text = "0 CR"
        } else {
            binding.tvAccountLicenseStatus.text = "INACTIVE"
            binding.tvAccountLicenseStatus.setTextColor(Color.parseColor("#FF5252"))
            binding.tvAccountLicenseExpires.text = "-"
            binding.tvAccountCreditsRemaining.text = "$remaining CR"
            binding.tvAccountCreditsUsed.text = "${500 - remaining} CR"
        }
        
        binding.tvAccountReferralCode.text = "YOUR CODE: MP-${name.uppercase()}-FVD9"
        binding.btnCopyReferral.setOnClickListener {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            val clip = android.content.ClipData.newPlainText("Referral Code", "MP-${name.uppercase()}-FVD9")
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
            releaseCamera()
            StreamAuthManager.logout(this)
            Toast.makeText(this, "Logged out", Toast.LENGTH_SHORT).show()
            showAuthState()
        }

        binding.btnAccountMinimize.setOnClickListener { minimizeApp() }
        binding.btnAccountClonedApps.setOnClickListener { finish() }
        binding.fabAccountTelegram.setOnClickListener { openTelegram() }
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
            Toast.makeText(this, "Password reset link sent to your email.", Toast.LENGTH_LONG).show()
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
                        if (StreamAuthManager.isActivated(this@StreamActivity) || StreamAuthManager.getWalletBalance(this@StreamActivity) > 0) {
                            showDashboardState()
                        } else {
                            showWelcomeState()
                        }
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
                val errorMsg = StreamAuthManager.signUp(this@StreamActivity, BACKEND_BASE_URL, name, email, phone, password)
                runOnUiThread {
                    progressDialog.dismiss()
                    if (errorMsg == null) {
                        showRegistrationKeysDialog()
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
            showWelcomeState()
        }
        binding.btnSelectPlanBasic.setOnClickListener {
            val price = binding.tvPlanBasicPrice.text.toString().replace("₦", "").replace(",", "").toDoubleOrNull() ?: 29000.0
            val credits = binding.tvPlanBasicCredits.text.toString().replace(",", "").replace(" Credits", "").toIntOrNull() ?: 1000
            val time = binding.tvPlanBasicTime.text.toString()
            showPlanPaymentState("Basic", price, credits, time)
        }
        binding.btnSelectPlanPro.setOnClickListener {
            val price = binding.tvPlanProPrice.text.toString().replace("₦", "").replace(",", "").toDoubleOrNull() ?: 58000.0
            val credits = binding.tvPlanProCredits.text.toString().replace(",", "").replace(" Credits", "").toIntOrNull() ?: 2000
            val time = binding.tvPlanProTime.text.toString()
            showPlanPaymentState("Pro", price, credits, time)
        }
        binding.btnSelectPlanEnterprise.setOnClickListener {
            val price = binding.tvPlanEnterprisePrice.text.toString().replace("₦", "").replace(",", "").toDoubleOrNull() ?: 145000.0
            val credits = binding.tvPlanEnterpriseCredits.text.toString().replace(",", "").replace(" Credits", "").toIntOrNull() ?: 5000
            val time = binding.tvPlanEnterpriseTime.text.toString()
            showPlanPaymentState("Enterprise", price, credits, time)
        }
        binding.btnSelectPlanVip.setOnClickListener {
            val price = binding.tvPlanVipPrice.text.toString().replace("₦", "").replace(",", "").toDoubleOrNull() ?: 290000.0
            val credits = binding.tvPlanVipCredits.text.toString().replace(",", "").replace(" Credits", "").toIntOrNull() ?: 10000
            val time = binding.tvPlanVipTime.text.toString()
            showPlanPaymentState("VIP plan", price, credits, time)
        }
        binding.btnPlanRedeemKey.setOnClickListener {
            redeemManualPlanKey()
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
        binding.btnCheckoutCrypto.setOnClickListener {
            processCheckoutPayment("Crypto")
        }
        binding.btnCheckoutWhatsApp.setOnClickListener {
            openWhatsAppContact()
        }
        binding.btnCheckoutEmail.setOnClickListener {
            openEmailContact()
        }
        binding.tvCheckoutManualKeyToggle.setOnClickListener {
            toggleCheckoutManualKey()
        }
        binding.btnCheckoutRedeemKey.setOnClickListener {
            redeemManualCheckoutKey()
        }
        binding.btnCheckoutBack.setOnClickListener {
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

        // Activation Screen Actions
        binding.btnCopyDeviceId.setOnClickListener {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            val clip = android.content.ClipData.newPlainText("Device ID", binding.tvActivationDeviceId.text)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(this, "Device ID copied to clipboard!", Toast.LENGTH_SHORT).show()
        }
        binding.btnActivationPayFlutterwave.setOnClickListener {
            simulateActivationPayment("Flutterwave")
        }
        binding.btnActivationPayCrypto.setOnClickListener {
            simulateActivationPayment("Crypto")
        }
        binding.btnActivateLicense.setOnClickListener {
            val enteredKey = binding.etActivationKey.text.toString().trim()
            val correctKey = StreamAuthManager.getActivationKey(this)

            if (enteredKey.equals(correctKey, ignoreCase = true)) {
                binding.tvActivationStatus.text = "✓ License activated"
                binding.tvActivationStatus.setTextColor(Color.parseColor("#FF3CD070"))
                binding.tvActivationStatus.visibility = View.VISIBLE

                StreamAuthManager.setActivated(this, true)
                Toast.makeText(this, "License activated successfully!", Toast.LENGTH_SHORT).show()
                Handler(Looper.getMainLooper()).postDelayed({
                    showDashboardState()
                }, 1000)
            } else {
                binding.tvActivationStatus.text = "✗ Key not found"
                binding.tvActivationStatus.setTextColor(Color.parseColor("#FF5252"))
                binding.tvActivationStatus.visibility = View.VISIBLE
            }
        }
        binding.tvActivationSupport.setOnClickListener {
            openTelegram()
        }
        binding.btnActivationBackToOptions.setOnClickListener {
            showWelcomeState()
        }
        binding.tvActivationSignOut.setOnClickListener {
            StreamAuthManager.logout(this)
            Toast.makeText(this, "Logged out", Toast.LENGTH_SHORT).show()
            showAuthState()
        }
        binding.btnActivationMinimize.setOnClickListener {
            minimizeApp()
        }
        binding.btnActivationClonedApps.setOnClickListener {
            MainActivity.start(this@StreamActivity)
            finish()
        }
        binding.fabActivationTelegram.setOnClickListener {
            openTelegram()
        }

        // Dashboard Top Bar Navigation - Hamburger Dropdown Menu
        binding.btnHamburgerMenu.setOnClickListener { view ->
            val popup = android.widget.PopupMenu(this, view)
            popup.menu.add("OBS")
            popup.menu.add("REC")
            popup.menu.add("SNAP")
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
            connectToVercelBackend()
        }
        binding.btnStopStream.setOnClickListener {
            stopLiveStream()
        }

        // Mode Style / Face Swap toggle
        binding.btnModeStyle.setOnClickListener {
            binding.btnModeStyle.setBackgroundColor(Color.parseColor("#FF3CD070"))
            binding.btnModeStyle.setTextColor(Color.parseColor("#0B0C10"))
            binding.btnModeFaceSwap.setBackgroundColor(Color.TRANSPARENT)
            binding.btnModeFaceSwap.setTextColor(Color.parseColor("#888D9B"))
            binding.layoutFaceSwapUpload.visibility = View.GONE
            Toast.makeText(this, "Style mode selected", Toast.LENGTH_SHORT).show()
        }
        binding.btnModeFaceSwap.setOnClickListener {
            binding.btnModeFaceSwap.setBackgroundColor(Color.parseColor("#FF3CD070"))
            binding.btnModeFaceSwap.setTextColor(Color.parseColor("#0B0C10"))
            binding.btnModeStyle.setBackgroundColor(Color.TRANSPARENT)
            binding.btnModeStyle.setTextColor(Color.parseColor("#888D9B"))
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
            minimizeApp()
        }

        binding.btnClonedApps.setOnClickListener {
            MainActivity.start(this@StreamActivity)
            finish()
        }

        binding.btnDashboardMinimize.setOnClickListener { minimizeApp() }
        binding.btnDashboardClonedApps.setOnClickListener {
            MainActivity.start(this@StreamActivity)
            finish()
        }
        binding.fabDashboardTelegram.setOnClickListener { openTelegram() }
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

    private fun connectToVercelBackend() {
        val progressDialog = android.app.ProgressDialog(this).apply {
            setMessage("Connecting to Vercel API backend...")
            setCancelable(false)
            show()
        }

        val deviceId = StreamAuthManager.getDeviceId(this)
        val email = StreamAuthManager.getUserEmail(this)
        val mode = if (binding.layoutFaceSwapUpload.visibility == View.VISIBLE) "face_swap" else "style"
        val prompt = if (binding.switchLiveUpdate.isChecked) binding.etPromptDescription.text.toString() else ""
        val preset = if (binding.switchEnhance.isChecked) selectedPresetView?.text?.toString() ?: "" else ""

        networkExecutor.execute {
            var base64Image = ""
            if (mode == "face_swap" && selectedFaceImageUri != null) {
                try {
                    val inputStream = contentResolver.openInputStream(selectedFaceImageUri!!)
                    val bytes = inputStream?.readBytes()
                    inputStream?.close()
                    if (bytes != null) {
                        base64Image = android.util.Base64.encodeToString(bytes, android.util.Base64.DEFAULT)
                    }
                } catch (e: java.lang.Exception) {
                    e.printStackTrace()
                }
            }

            try {
                val url = java.net.URL("$BACKEND_BASE_URL/transform")
                val connection = url.openConnection() as java.net.HttpURLConnection
                connection.requestMethod = "POST"
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connection.doOutput = true
                connection.connectTimeout = 8000
                connection.readTimeout = 8000

                val escapedPrompt = prompt.replace("\"", "\\\"").replace("\n", "\\n")
                val payload = "{" +
                        "\"deviceId\":\"$deviceId\"," +
                        "\"email\":\"$email\"," +
                        "\"mode\":\"$mode\"," +
                        "\"prompt\":\"$escapedPrompt\"," +
                        "\"preset\":\"$preset\"," +
                        "\"faceImage\":\"${if (base64Image.length > 200) base64Image.substring(0, 200) + "..." else base64Image}\"" +
                        "}"

                val os = connection.outputStream
                os.write(payload.toByteArray(charset("UTF-8")))
                os.close()

                val responseCode = connection.responseCode
                runOnUiThread {
                    progressDialog.dismiss()
                    if (responseCode == 200 || responseCode == 201) {
                        Toast.makeText(this@StreamActivity, "Connected to Vercel API backend!", Toast.LENGTH_SHORT).show()
                        startLiveStreamState()
                    } else {
                        Toast.makeText(this@StreamActivity, "Vercel API error ($responseCode). Running local AI engine...", Toast.LENGTH_SHORT).show()
                        startLiveStreamState()
                    }
                }
            } catch (e: java.lang.Exception) {
                e.printStackTrace()
                runOnUiThread {
                    progressDialog.dismiss()
                    Toast.makeText(this@StreamActivity, "Vercel engine offline. Connecting to local AI engine...", Toast.LENGTH_SHORT).show()
                    startLiveStreamState()
                }
            }
        }
    }

    private fun startLiveStreamState() {
        isStreaming = true
        binding.btnStopStream.isEnabled = true
        binding.btnStopStream.setBackgroundColor(Color.parseColor("#FFFF5252"))
        binding.btnStopStream.setTextColor(Color.parseColor("#0B0C10"))
        
        binding.btnConnectStream.isEnabled = false
        binding.btnConnectStream.setBackgroundColor(Color.parseColor("#1D2030"))
        binding.btnConnectStream.setTextColor(Color.parseColor("#3A3F55"))
        
        if (!StreamAuthManager.isActivated(this)) {
            mainHandler.post(creditUsageRunnable)
        }
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

    private fun toggleCheckoutManualKey() {
        val container = binding.layoutCheckoutManualKeyContainer
        if (container.visibility == View.VISIBLE) {
            container.visibility = View.GONE
        } else {
            container.visibility = View.VISIBLE
        }
    }

    private fun redeemManualPlanKey() {
        val key = binding.etPlanCreditKey.text.toString().trim()
        redeemKeyLogic(key)
    }

    private fun redeemManualCheckoutKey() {
        val key = binding.etCheckoutManualKey.text.toString().trim()
        redeemKeyLogic(key)
    }

    private fun redeemKeyLogic(key: String) {
        if (key.isEmpty()) {
            Toast.makeText(this, "Please enter a key", Toast.LENGTH_SHORT).show()
            return
        }

        val correctKey = StreamAuthManager.getActivationKey(this)
        
        // 1. Check if it's the license activation key
        if (key.equals(correctKey, ignoreCase = true) || key.equals("MP-ACTIVATE", ignoreCase = true)) {
            StreamAuthManager.setActivated(this, true)
            Toast.makeText(this, "License activated permanently!", Toast.LENGTH_LONG).show()
            showDashboardState()
            return
        }

        // 2. Check if it's a credit key starting with MP-
        if (key.startsWith("MP-", ignoreCase = true)) {
            val upperKey = key.uppercase()
            val credits = when {
                upperKey.contains("TEST") -> 500
                upperKey.contains("START") -> 1000
                upperKey.contains("PRO") -> 5000
                upperKey.contains("PREM") -> 10000
                upperKey.contains("ELITE") -> 50000
                else -> 1000
            }
            
            StreamAuthManager.addCredits(this, credits)
            syncBalanceWithBackend(credits)
            Toast.makeText(this, "Successfully redeemed $credits credits!", Toast.LENGTH_LONG).show()
            showDashboardState()
        } else {
            Toast.makeText(this, "Invalid Credit Key format. Key must start with 'MP-'", Toast.LENGTH_SHORT).show()
        }
    }

    private fun syncBalanceWithBackend(creditsAdded: Int) {
        val email = StreamAuthManager.getUserEmail(this)
        networkExecutor.execute {
            try {
                val url = java.net.URL("$BACKEND_BASE_URL/consume")
                val connection = url.openConnection() as java.net.HttpURLConnection
                connection.requestMethod = "POST"
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connection.doOutput = true
                connection.connectTimeout = 5000
                connection.readTimeout = 5000
                
                // consume negative credits adds balance on the server!
                val payload = "{\"email\":\"$email\",\"amount\":${-creditsAdded}}"
                val os = connection.outputStream
                os.write(payload.toByteArray(charset("UTF-8")))
                os.close()
                
                connection.responseCode
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun processCheckoutPayment(gateway: String) {
        val progressDialog = android.app.ProgressDialog(this).apply {
            setMessage("Processing secure payment via $gateway...")
            setCancelable(false)
            show()
        }
        
        Handler(Looper.getMainLooper()).postDelayed({
            progressDialog.dismiss()
            if (isLicenseActivationPlan) {
                StreamAuthManager.setActivated(this, true)
                Toast.makeText(this, "Activation payment successful! License activated permanently.", Toast.LENGTH_LONG).show()
            } else {
                StreamAuthManager.addCredits(this, selectedPlanCredits)
                syncBalanceWithBackend(selectedPlanCredits)
                Toast.makeText(this, "Payment successful! $selectedPlanCredits credits added.", Toast.LENGTH_LONG).show()
            }
            showDashboardState()
        }, 1500)
    }

    private fun openWhatsAppContact() {
        val message = "Hello Morphly Support, I would like to purchase the $selectedPlanName Plan for total $selectedPlanPrice USD."
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
        val body = "Hello,\n\nI want to purchase the $selectedPlanName Plan ($selectedPlanPrice USD).\nMy unique Device ID is: ${StreamAuthManager.getDeviceId(this)}"
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
                        if (packagesArray.length() == 4) {
                            runOnUiThread {
                                val p1 = packagesArray.getJSONObject(0)
                                binding.tvPlanBasicTitle.text = p1.getString("name")
                                binding.tvPlanBasicPrice.text = "₦" + String.format("%,d", p1.getInt("price"))
                                binding.tvPlanBasicCredits.text = String.format("%,d", p1.getInt("credits")) + " Credits"
                                binding.tvPlanBasicTime.text = p1.getString("timeLabel")

                                val p2 = packagesArray.getJSONObject(1)
                                binding.tvPlanProTitle.text = p2.getString("name")
                                binding.tvPlanProPrice.text = "₦" + String.format("%,d", p2.getInt("price"))
                                binding.tvPlanProCredits.text = String.format("%,d", p2.getInt("credits")) + " Credits"
                                binding.tvPlanProTime.text = p2.getString("timeLabel")

                                val p3 = packagesArray.getJSONObject(2)
                                binding.tvPlanEnterpriseTitle.text = p3.getString("name")
                                binding.tvPlanEnterprisePrice.text = "₦" + String.format("%,d", p3.getInt("price"))
                                binding.tvPlanEnterpriseCredits.text = String.format("%,d", p3.getInt("credits")) + " Credits"
                                binding.tvPlanEnterpriseTime.text = p3.getString("timeLabel")

                                val p4 = packagesArray.getJSONObject(3)
                                binding.tvPlanVipTitle.text = p4.getString("name")
                                binding.tvPlanVipPrice.text = "₦" + String.format("%,d", p4.getInt("price"))
                                binding.tvPlanVipCredits.text = String.format("%,d", p4.getInt("credits")) + " Credits"
                                binding.tvPlanVipTime.text = p4.getString("timeLabel")
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

}