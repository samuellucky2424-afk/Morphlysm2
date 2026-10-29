package top.niunaijun.blackboxa.view.main

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import top.niunaijun.blackbox.BlackBoxCore


class ShortcutActivity:AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val pkg = intent.getStringExtra("pkg")
        val userID = intent.getIntExtra("userId",0)
        // Only launch apps that are actually installed inside Morphly; ignore anything else other apps send.
        val valid = !pkg.isNullOrBlank() && pkg.length <= 255 && userID in 0..999 &&
            runCatching { BlackBoxCore.get().isInstalled(pkg, userID) }.getOrDefault(false)
        if (!valid) { finish(); return }

        lifecycleScope.launch {
            BlackBoxCore.get().launchApk(pkg,userID)
            finish()
        }
    }
}