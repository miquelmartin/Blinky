package org.miquelmartin.blinky

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.database.sqlite.SQLiteException
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.os.Bundle
import android.os.PersistableBundle
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import java.util.concurrent.Executors

class MainActivity : Activity(), SharedPreferences.OnSharedPreferenceChangeListener {
    private lateinit var preferences: SharedPreferences
    private lateinit var tokenButton: ImageButton
    private lateinit var tokenProgress: ProgressBar
    private lateinit var tokenStatus: TextView
    private lateinit var permissionNotice: TextView
    private lateinit var countView: TextView
    private lateinit var historyNotice: TextView
    private lateinit var emptyHistory: TextView
    private lateinit var list: ListView
    private lateinit var adapter: HistoryAdapter
    private val historyWorker = Executors.newSingleThreadExecutor()
    private var firebaseReady = false
    private var tokenLoading = false
    private var permissionCheckedThisOpen = false
    private var permissionRequestInFlight = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        preferences = BlinkyState.preferences(this)
        BlinkyNotifications.createChannel(this)
        createScreen()
        try {
            firebaseReady = FirebaseApp.initializeApp(this) != null
            if (!firebaseReady) showTokenStatus(R.string.firebase_missing)
        } catch (error: IllegalStateException) {
            Log.e(TAG, "Firebase initialization failed (${error.javaClass.simpleName}).")
            showTokenStatus(R.string.firebase_initialization_failed)
        }
        tokenButton.isEnabled = firebaseReady
    }

    override fun onStart() {
        super.onStart()
        permissionCheckedThisOpen = false
        preferences.registerOnSharedPreferenceChangeListener(this)
    }

    override fun onResume() {
        super.onResume()
        loadHistory()
        checkNotificationPermission()
    }

    override fun onStop() {
        preferences.unregisterOnSharedPreferenceChangeListener(this)
        super.onStop()
    }

    override fun onDestroy() {
        historyWorker.shutdown()
        super.onDestroy()
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences, key: String?) {
        if (key == BlinkyState.HISTORY_REVISION || key == BlinkyState.HISTORY_WRITE_FAILED) loadHistory()
    }

    private fun createScreen() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(PocketColors.HEADER)
        }
        val statusInset = View(this)
        val navigationInset = View(this).apply { setBackgroundColor(PocketColors.HISTORY) }
        root.addView(statusInset, LinearLayout.LayoutParams(-1, 0))
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(54), dp(24), dp(24))
        }
        val heading = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        heading.addView(ImageView(this).apply {
            setImageResource(R.drawable.blinky_foreground)
            background = rounded(0xFF293C59.toInt(), 23)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(dp(80), dp(80)))
        heading.addView(label(R.string.app_name, 32f), LinearLayout.LayoutParams(0, -2, 1f).apply {
            leftMargin = dp(12)
        })
        val tokenControl = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        val tokenFrame = FrameLayout(this)
        tokenButton = ImageButton(this).apply {
            setImageResource(R.drawable.ic_copy_token)
            imageTintList = ColorStateList.valueOf(PocketColors.HEADER)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = RippleDrawable(
                ColorStateList.valueOf(0x33202E44), rounded(PocketColors.CORAL, 18), null
            )
            contentDescription = getString(R.string.fetch_copy_token)
            setOnClickListener { fetchAndCopyToken() }
        }
        tokenProgress = ProgressBar(this).apply {
            indeterminateTintList = ColorStateList.valueOf(PocketColors.HEADER)
            visibility = View.GONE
        }
        tokenFrame.addView(tokenButton, FrameLayout.LayoutParams(-1, -1))
        tokenFrame.addView(tokenProgress, FrameLayout.LayoutParams(dp(28), dp(28), Gravity.CENTER))
        tokenControl.addView(tokenFrame, LinearLayout.LayoutParams(dp(54), dp(54)))
        tokenControl.addView(label(R.string.copy_token, 11f, PocketColors.BODY).apply {
            setSingleLine()
            gravity = Gravity.CENTER
            setPadding(0, dp(10), 0, 0)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(-2, -2))
        heading.addView(tokenControl)
        header.addView(heading)
        tokenStatus = label(R.string.token_fetch_failed, 13f, PocketColors.CORAL).apply {
            visibility = View.GONE
            setPadding(0, dp(16), 0, 0)
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        permissionNotice = label(R.string.permission_settings_notice, 13f, PocketColors.BODY).apply {
            visibility = View.GONE
            minimumHeight = dp(48)
            setPadding(0, dp(16), 0, dp(4))
            setOnClickListener { openNotificationSettings() }
        }
        header.addView(tokenStatus)
        header.addView(permissionNotice)
        root.addView(header)

        val history = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(PocketColors.HISTORY, 30).apply {
                val radius = dp(30).toFloat()
                cornerRadii = floatArrayOf(radius, radius, radius, radius, 0f, 0f, 0f, 0f)
            }
        }
        val historyHeading = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(24), dp(24), dp(24), dp(2))
        }
        historyHeading.addView(label(R.string.history_title, 21f), LinearLayout.LayoutParams(0, -2, 1f))
        countView = TextView(this).apply {
            textSize = 11f
            setTextColor(PocketColors.TEXT)
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            background = rounded(PocketColors.BADGE, 13)
            setPadding(dp(8), dp(6), dp(8), dp(6))
            visibility = View.INVISIBLE
        }
        historyHeading.addView(countView, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(8) })
        history.addView(historyHeading)
        historyNotice = label(R.string.history_write_failed, 13f, PocketColors.CORAL).apply {
            setPadding(dp(24), dp(16), dp(24), 0)
            visibility = View.GONE
        }
        history.addView(historyNotice)
        val feed = FrameLayout(this)
        adapter = HistoryAdapter(this)
        list = ListView(this).apply {
            adapter = this@MainActivity.adapter
            divider = null
            isVerticalScrollBarEnabled = true
            setPadding(0, 0, 0, dp(20))
            clipToPadding = false
        }
        emptyHistory = label(R.string.history_loading, 16f, PocketColors.BODY).apply {
            setPadding(dp(24), dp(48), dp(24), dp(24))
            gravity = Gravity.CENTER
            val ghost = getDrawable(R.mipmap.ic_launcher)?.apply { setBounds(0, 0, dp(48), dp(48)) }
            setCompoundDrawables(null, ghost, null, null)
            compoundDrawablePadding = dp(16)
        }
        feed.addView(list, FrameLayout.LayoutParams(-1, -1))
        feed.addView(emptyHistory, FrameLayout.LayoutParams(-1, -2))
        history.addView(feed, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(history, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(navigationInset, LinearLayout.LayoutParams(-1, 0))
        setContentView(root)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.setSystemBarsAppearance(
                0, WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            )
        }
        if (Build.VERSION.SDK_INT >= 29) window.isNavigationBarContrastEnforced = false
        root.setOnApplyWindowInsetsListener { view, insets ->
            val top: Int
            val bottom: Int
            if (Build.VERSION.SDK_INT >= 30) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                top = bars.top
                bottom = bars.bottom
                view.setPadding(bars.left, 0, bars.right, 0)
            } else {
                @Suppress("DEPRECATION")
                top = insets.systemWindowInsetTop
                @Suppress("DEPRECATION")
                bottom = insets.systemWindowInsetBottom
                @Suppress("DEPRECATION")
                view.setPadding(insets.systemWindowInsetLeft, 0, insets.systemWindowInsetRight, 0)
            }
            statusInset.layoutParams = LinearLayout.LayoutParams(-1, top)
            navigationInset.layoutParams = LinearLayout.LayoutParams(-1, bottom)
            insets
        }
        root.requestApplyInsets()
    }

    private fun fetchAndCopyToken() {
        if (!firebaseReady || tokenLoading) return
        tokenLoading = true
        tokenButton.isEnabled = false
        tokenButton.setImageDrawable(null)
        tokenProgress.visibility = View.VISIBLE
        tokenStatus.visibility = View.GONE
        FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
            if (isDestroyed || isFinishing) return@addOnCompleteListener
            tokenLoading = false
            tokenButton.isEnabled = true
            tokenButton.setImageResource(R.drawable.ic_copy_token)
            tokenProgress.visibility = View.GONE
            val token = if (task.isSuccessful) task.result else null
            when (copyFetchedToken(task.isSuccessful, token) { currentToken ->
                val clipboard = getSystemService(ClipboardManager::class.java)
                    ?: throw IllegalStateException("Clipboard unavailable.")
                val clip = ClipData.newPlainText(getString(R.string.token_label), currentToken)
                if (Build.VERSION.SDK_INT >= 33) {
                    clip.description.extras = PersistableBundle().apply {
                        putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
                    }
                }
                clipboard.setPrimaryClip(clip)
            }) {
                TokenCopyResult.COPIED -> {
                    preferences.edit().putString(BlinkyState.TOKEN, token).apply()
                    Toast.makeText(this, R.string.token_copied, Toast.LENGTH_SHORT).show()
                }
                TokenCopyResult.FETCH_FAILED -> {
                    Log.e(TAG, "Current token retrieval failed; cached token was not copied.")
                    showTokenStatus(R.string.token_fetch_failed)
                }
                TokenCopyResult.COPY_FAILED -> {
                    Log.e(TAG, "Current token could not be copied to the clipboard.")
                    showTokenStatus(R.string.token_copy_failed)
                }
            }
        }
    }

    private fun checkNotificationPermission() {
        val modern = Build.VERSION.SDK_INT >= 33
        val granted = !modern || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        val action = permissionAction(
            modern, granted, preferences.getBoolean("permission_requested", false),
            modern && shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS),
            BlinkyNotifications.enabled(this)
        )
        permissionNotice.visibility = if (action == PermissionAction.NONE) View.GONE else View.VISIBLE
        permissionNotice.setText(
            if (action == PermissionAction.REQUEST) R.string.permission_rationale else R.string.permission_settings_notice
        )
        if (permissionCheckedThisOpen || permissionRequestInFlight) return
        permissionCheckedThisOpen = true
        if (action == PermissionAction.REQUEST) {
            permissionRequestInFlight = true
            preferences.edit().putBoolean("permission_requested", true).apply()
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        permissionRequestInFlight = false
        permissionCheckedThisOpen = true
        checkNotificationPermission()
    }

    private fun openNotificationSettings() {
        try {
            startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
        } catch (_: ActivityNotFoundException) {
            showTokenStatus(R.string.settings_open_failed)
        } catch (_: SecurityException) {
            showTokenStatus(R.string.settings_open_failed)
        }
    }

    private fun loadHistory() {
        if (isDestroyed || isFinishing) return
        historyWorker.execute {
            try {
                val messages = NotificationHistory(applicationContext).use { it.read() }
                runOnUiThread {
                    if (isDestroyed || isFinishing) return@runOnUiThread
                    adapter.update(messages)
                    countView.text = resources.getQuantityString(R.plurals.history_count, messages.size, messages.size)
                    countView.visibility = View.VISIBLE
                    list.visibility = if (messages.isEmpty()) View.GONE else View.VISIBLE
                    emptyHistory.visibility = if (messages.isEmpty()) View.VISIBLE else View.GONE
                    emptyHistory.setText(R.string.history_empty)
                    historyNotice.visibility = if (preferences.getBoolean(BlinkyState.HISTORY_WRITE_FAILED, false)) View.VISIBLE else View.GONE
                    historyNotice.setText(R.string.history_write_failed)
                }
            } catch (error: SQLiteException) {
                Log.e(TAG, "History could not be read (${error.javaClass.simpleName}).")
                runOnUiThread {
                    if (isDestroyed || isFinishing) return@runOnUiThread
                    historyNotice.setText(R.string.history_read_failed)
                    historyNotice.visibility = View.VISIBLE
                    emptyHistory.visibility = View.GONE
                }
            }
        }
    }

    private fun showTokenStatus(resource: Int) {
        tokenStatus.setText(resource)
        tokenStatus.visibility = View.VISIBLE
    }

    private fun label(resource: Int, size: Float, color: Int = PocketColors.TEXT) = TextView(this).apply {
        setText(resource)
        textSize = size
        setTextColor(color)
    }

    private fun rounded(color: Int, radius: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val TAG = "BlinkyActivity"
    }
}
