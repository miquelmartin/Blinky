package org.miquelmartin.blinky

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.PersistableBundle
import android.provider.Settings
import android.util.Log
import android.view.View
import android.view.WindowInsets
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging

class MainActivity : Activity(), SharedPreferences.OnSharedPreferenceChangeListener {
    private lateinit var tokenView: TextView
    private lateinit var statusView: TextView
    private lateinit var permissionView: TextView
    private lateinit var lastMessageView: TextView
    private lateinit var copyButton: Button
    private lateinit var reloadButton: Button
    private lateinit var permissionButton: Button
    private lateinit var preferences: SharedPreferences
    private var firebaseReady = false
    private var loading = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        preferences = BlinkyState.preferences(this)
        BlinkyNotifications.createChannel(this)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(24), dp(24), dp(24))
        }
        val scroll = ScrollView(this).apply { addView(content) }
        scroll.setOnApplyWindowInsetsListener { view, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            } else {
                @Suppress("DEPRECATION")
                view.setPadding(
                    insets.systemWindowInsetLeft, insets.systemWindowInsetTop,
                    insets.systemWindowInsetRight, insets.systemWindowInsetBottom
                )
            }
            insets
        }
        setContentView(scroll)
        scroll.requestApplyInsets()

        text(content, R.string.app_name).textSize = 28f
        text(content, R.string.intro)
        permissionView = text(content, R.string.notifications_disabled)
        permissionButton = button(content, R.string.enable_notifications) { enableNotifications() }
        text(content, R.string.token_label)
        tokenView = text(content, R.string.token_empty).apply { setTextIsSelectable(true) }
        statusView = text(content, R.string.token_loading).apply {
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        copyButton = button(content, R.string.copy_token) {
            preferences.getString(BlinkyState.TOKEN, null)?.takeIf { it.isNotBlank() }?.let { token ->
                val clip = ClipData.newPlainText(getString(R.string.token_label), token)
                if (Build.VERSION.SDK_INT >= 33) {
                    clip.description.extras = PersistableBundle().apply {
                        putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
                    }
                }
                getSystemService(ClipboardManager::class.java)
                    .setPrimaryClip(clip)
                Toast.makeText(this, R.string.token_copied, Toast.LENGTH_SHORT).show()
            }
        }
        reloadButton = button(content, R.string.reload_token) { loadToken() }
        lastMessageView = text(content, R.string.latest_message_none)
        text(content, R.string.delivery_note)

        try {
            firebaseReady = FirebaseApp.initializeApp(this) != null
            if (!firebaseReady) statusView.setText(R.string.firebase_missing)
        } catch (error: IllegalStateException) {
            Log.e(TAG, "Firebase initialization failed.", error)
            statusView.text = getString(R.string.firebase_failed, error.message ?: error.javaClass.simpleName)
        }
        renderState()
    }

    override fun onStart() {
        super.onStart()
        preferences.registerOnSharedPreferenceChangeListener(this)
    }

    override fun onResume() {
        super.onResume()
        renderState()
        if (firebaseReady) loadToken()
    }

    override fun onStop() {
        preferences.unregisterOnSharedPreferenceChangeListener(this)
        super.onStop()
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences, key: String?) {
        renderState()
        if (key == BlinkyState.TOKEN && firebaseReady) statusView.setText(R.string.token_ready)
    }

    private fun loadToken() {
        if (!firebaseReady || loading) return
        loading = true
        reloadButton.isEnabled = false
        statusView.setText(R.string.token_loading)
        FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
            loading = false
            if (isDestroyed || isFinishing) return@addOnCompleteListener
            reloadButton.isEnabled = true
            val token = if (task.isSuccessful) task.result else null
            if (!token.isNullOrBlank()) {
                preferences.edit().putString(BlinkyState.TOKEN, token).apply()
                statusView.setText(R.string.token_ready)
                renderState()
            } else {
                Log.e(TAG, "FCM token retrieval failed.", task.exception)
                val detail = task.exception?.localizedMessage
                    ?: getString(if (task.isSuccessful) R.string.token_empty_response else R.string.token_failed_unknown)
                statusView.text = getString(R.string.token_failed, detail)
            }
        }
    }

    private fun renderState() {
        val token = preferences.getString(BlinkyState.TOKEN, null)
        tokenView.text = token?.takeIf { it.isNotBlank() } ?: getString(R.string.token_empty)
        copyButton.isEnabled = firebaseReady && !token.isNullOrBlank()
        reloadButton.isEnabled = firebaseReady && !loading
        val enabled = BlinkyNotifications.enabled(this)
        permissionView.setText(if (enabled) R.string.notifications_enabled else R.string.notifications_disabled)
        permissionButton.setText(
            if (Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) R.string.enable_notifications else R.string.notification_settings
        )
        val message = preferences.getString(BlinkyState.LAST_MESSAGE, null)
        lastMessageView.text = if (message == null) {
            getString(R.string.latest_message_none)
        } else {
            val resource = when (preferences.getString(BlinkyState.LAST_DISPLAY, null)) {
                BlinkyState.SUPPRESSED -> R.string.latest_message_suppressed
                BlinkyState.FAILED -> R.string.latest_message_failed
                else -> R.string.latest_message
            }
            getString(resource, message)
        }
    }

    private fun enableNotifications() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
            (!preferences.getBoolean("permission_requested", false) ||
                shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS))
        ) {
            preferences.edit().putBoolean("permission_requested", true).apply()
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        } else {
            startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        renderState()
    }

    private fun text(parent: LinearLayout, resource: Int): TextView =
        TextView(this).apply {
            setText(resource)
            textSize = 16f
            setPadding(0, dp(8), 0, dp(8))
            parent.addView(this)
        }

    private fun button(parent: LinearLayout, resource: Int, action: () -> Unit): Button =
        Button(this).apply {
            setText(resource)
            setOnClickListener { action() }
            parent.addView(this)
        }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val TAG = "BlinkyActivity"
    }
}
