package top.niunaijun.blackboxa.util

import android.app.Activity
import android.app.Application
import android.os.Build
import android.os.Bundle
import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlin.math.max

/**
 * Android 16 (API 36) disables the edge-to-edge opt-out for apps that target
 * API 36, so system bars draw over content. Pad Morphly's own screens by the
 * status, navigation, cutout, and IME insets. Cloned apps run in proxy
 * activities outside this package and keep their own layout.
 */
object Android16Compat {
    private const val HOST_PACKAGE_PREFIX = "top.niunaijun.blackboxa."

    fun install(application: Application) {
        application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityPostCreated(activity: Activity, savedInstanceState: Bundle?) {
                applyInsets(activity)
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityStarted(activity: Activity) {}
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }

    private fun applyInsets(activity: Activity) {
        if (Build.VERSION.SDK_INT < 35) return
        if (!activity.javaClass.name.startsWith(HOST_PACKAGE_PREFIX)) return
        val content = activity.findViewById<View>(android.R.id.content) ?: return

        WindowCompat.setDecorFitsSystemWindows(activity.window, false)
        val controller = WindowInsetsControllerCompat(activity.window, content)
        // Window background is white, so the transparent system bars need dark icons.
        controller.isAppearanceLightStatusBars = true
        controller.isAppearanceLightNavigationBars = true

        ViewCompat.setOnApplyWindowInsetsListener(content) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            view.setPadding(bars.left, bars.top, bars.right, max(bars.bottom, ime.bottom))
            WindowInsetsCompat.CONSUMED
        }
        ViewCompat.requestApplyInsets(content)
    }
}
