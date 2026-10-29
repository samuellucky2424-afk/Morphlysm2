package top.niunaijun.blackboxa.view.main

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.animation.OvershootInterpolator
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModelProvider
import top.niunaijun.blackboxa.R
import top.niunaijun.blackboxa.data.StreamAuthManager
import top.niunaijun.blackboxa.util.InjectionUtil
import top.niunaijun.blackboxa.view.list.ListViewModel

/** Branded splash + loading screen: white background, red Morphly logo/wordmark, red progress indicator. */
class WelcomeActivity : AppCompatActivity() {

    private val handler = Handler(Looper.getMainLooper())
    private var jumped = false
    private var startedAt = 0L

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        jump()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_welcome)
        startedAt = SystemClock.elapsedRealtime()
        animateBrand()
        // Loading phase: warm up the cloned-app list and the login check off the main thread.
        Thread({
            runCatching { /* touch prefs early so the auth check is instant */ StreamAuthManager.isLoggedIn(this) }
            handler.post {
                if (isFinishing) return@post
                runCatching { previewInstalledAppList() }
                val elapsed = SystemClock.elapsedRealtime() - startedAt
                handler.postDelayed({ jump() }, (MIN_SPLASH_MS - elapsed).coerceAtLeast(0L))
            }
        }, "MorphlySplashInit").start()
    }

    private fun animateBrand() {
        val logo = findViewById<View>(R.id.splashLogo)
        val word = findViewById<View>(R.id.splashWordmark)
        val loading = findViewById<View>(R.id.splashLoading)
        logo.scaleX = 0.6f; logo.scaleY = 0.6f; logo.alpha = 0f
        logo.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(520)
            .setInterpolator(OvershootInterpolator(1.6f)).start()
        word.translationY = 24f; word.alpha = 0f
        word.animate().translationY(0f).alpha(1f).setStartDelay(220).setDuration(420).start()
        startPulse(logo, findViewById(R.id.splashGlow))
        loading.animate().alpha(1f).setStartDelay(520).setDuration(300).start()
        findViewById<TextView>(R.id.splashStatus).text =
            if (StreamAuthManager.isLoggedIn(this)) "Signing you in..." else "Getting things ready..."
    }

    private var pulse: android.animation.ValueAnimator? = null

    /** Gentle breathing scale on the M with a soft red glow behind it. */
    private fun startPulse(logo: View, glow: View) {
        pulse = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1100; startDelay = 520
            repeatMode = android.animation.ValueAnimator.REVERSE
            repeatCount = android.animation.ValueAnimator.INFINITE
            interpolator = android.view.animation.AccelerateDecelerateInterpolator()
            addUpdateListener {
                val f = it.animatedValue as Float
                logo.scaleX = 1f + 0.05f * f; logo.scaleY = logo.scaleX
                glow.alpha = 0.35f + 0.65f * f
                glow.scaleX = 0.9f + 0.2f * f; glow.scaleY = glow.scaleX
            }
            start()
        }
    }

    private fun jump() {
        if (jumped || isFinishing) return
        jumped = true
        if (StreamAuthManager.isLoggedIn(this)) {
            MainActivity.start(this)
        } else {
            startActivity(Intent(this, StreamActivity::class.java))
        }
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        finish()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        pulse?.cancel()
        super.onDestroy()
    }

    private fun previewInstalledAppList() {
        val viewModel = ViewModelProvider(this, InjectionUtil.getListFactory()).get(ListViewModel::class.java)
        viewModel.previewInstalledList()
    }

    private companion object {
        const val MIN_SPLASH_MS = 1300L
    }
}
