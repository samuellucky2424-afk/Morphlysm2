package top.niunaijun.blackboxa.view.base

import android.view.KeyEvent
import androidx.activity.OnBackPressedCallback
import com.roger.catloadinglibrary.CatLoadingView
import top.niunaijun.blackboxa.R


abstract class LoadingActivity : BaseActivity() {

    private lateinit var loadingView: CatLoadingView
    private var backCallbackAdded = false
    private val consumeBackWhileLoading = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            // The loading dialog is modal. Android 16 does not deliver KEYCODE_BACK
            // to a dialog once predictive back is on, so consume it here too.
        }
    }


    fun showLoading() {
        if (!backCallbackAdded) {
            onBackPressedDispatcher.addCallback(this, consumeBackWhileLoading)
            backCallbackAdded = true
        }
        consumeBackWhileLoading.isEnabled = true
        if (!this::loadingView.isInitialized) {
            loadingView = CatLoadingView()
        }

        if (!loadingView.isAdded) {
            loadingView.setBackgroundColor(R.color.primary)
            loadingView.show(supportFragmentManager, "")
            supportFragmentManager.executePendingTransactions()
            loadingView.setClickCancelAble(false)
            loadingView.dialog?.setOnKeyListener { _, keyCode, _ ->
                if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_ESCAPE) {
                    return@setOnKeyListener true
                }
                false
            }
        }
    }


    fun hideLoading() {
        if (this::loadingView.isInitialized) {
            loadingView.dismiss()
        }
        if (backCallbackAdded) {
            consumeBackWhileLoading.isEnabled = false
        }
    }
}