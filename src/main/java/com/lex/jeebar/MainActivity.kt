package com.lex.jeebar

import android.Manifest
import android.app.Activity
import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Calendar
import java.util.TimeZone

class MainActivity : Activity() {

    // Keep this same as OverlayService target: 22 Jan 2027, 9:00 AM IST
    private val target: Long = Calendar.getInstance(TimeZone.getTimeZone("Asia/Kolkata")).apply {
        clear(); set(2027, Calendar.JANUARY, 22, 9, 0, 0)
    }.timeInMillis

    private val quotes = listOf(
        "aaj ka PYQ, kal ka rank",
        "phone band. organic on.",
        "discipline > motivation",
        "IIT wait kar raha hai",
        "ek aur mechanism, ek aur mark",
        "NCERT line by line",
        "consistency banati hai topper",
        "abhi dard, baad mein rank",
        "tu kar sakta hai. baith ja."
    )
package com.lex.jeebar

import android.Manifest
import android.app.Activity
import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Calendar
import java.util.TimeZone

class MainActivity : Activity() {

    // Keep this same as OverlayService target: 22 Jan 2027, 9:00 AM IST
    private val target: Long = Calendar.getInstance(TimeZone.getTimeZone("Asia/Kolkata")).apply {
        clear(); set(2027, Calendar.JANUARY, 22, 9, 0, 0)
    }.timeInMillis

    private val quotes = listOf(
        "aaj ka PYQ, kal ka rank",
        "phone band. organic on.",
        "discipline > motivation",
        "IIT wait kar raha hai",
        "ek aur mechanism, ek aur mark",
        "NCERT line by line",
        "consistency banati hai topper",
        "abhi dard, baad mein rank",
        "tu kar sakta hai. baith ja."
    )

    private val green = Color.parseColor("#00FF41")
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var daysTv: TextView
    private lateinit var timeTv: TextView
    private lateinit var quoteTv: TextView
    private var n = 0L

    private val loop = object : Runnable {
        override fun run() {
            val left = target - System.currentTimeMillis()
            if (left <= 0) {
                daysTv.text = "0"
                timeTv.text = "EXAM DAY"
            } else {
                val s = left / 1000
                val sep = if (n % 2 == 0L) ":" else " "
                daysTv.text = "${s / 86400}"
                timeTv.text = "%02d$sep%02d$sep%02d".format((s % 86400) / 3600, (s % 3600) / 60, s % 60)
            }
            if (n % 15 == 0L) quoteTv.text = quotes[((n / 15) % quotes.size).toInt()]
            n++
            handler.postDelayed(this, 1000 - System.currentTimeMillis() % 1000)
        }
    }

    private fun tv(size: Float, alpha: Int, spacing: Float, glow: Boolean = false) = TextView(this).apply {
        typeface = Typeface.MONOSPACE
        setTextColor(Color.argb(alpha, 0, 255, 65))
        textSize = size
        letterSpacing = spacing
        gravity = Gravity.CENTER
        if (glow) setShadowLayer(24f, 0f, 0f, green)
    }

    private fun hasUsageAccess(): Boolean {
        val ops = getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = ops.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), packageName
        )
        return mode == AppOpsManager.MODE_ALLOWED
    }

    private fun hasNotifAccess(): Boolean {
        val enabled = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
        return enabled != null && enabled.contains(packageName)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        if (!Settings.canDrawOverlays(this)) {
            // 1) Allow "Display over other apps", then open the app again
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
        } else if (!hasUsageAccess()) {
            // 2) Allow "Usage access" for JEE Bar, then open the app again
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        } else if (!hasNotifAccess()) {
            // 3) Allow "Notification access" for JEE Bar (needed for the Instagram lock)
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        } else {
            startForegroundService(Intent(this, OverlayService::class.java))
        }

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION

        val label = tv(12f, 150, 0.3f).apply { text = "> T-MINUS  DAYS" }
        daysTv = tv(88f, 255, 0.05f, glow = true)
        timeTv = tv(40f, 255, 0.12f, glow = true)
        quoteTv = tv(12f, 140, 0.15f)
        val hint = tv(9f, 80, 0.2f).apply { text = "tap anywhere to close" }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.BLACK)
            addView(label)
            addView(daysTv)
            addView(timeTv, LinearLayout.LayoutParams(-1, -2).apply { topMargin = 8 })
            addView(quoteTv, LinearLayout.LayoutParams(-1, -2).apply { topMargin = 72 })
            addView(hint, LinearLayout.LayoutParams(-1, -2).apply { topMargin = 160 })
            setOnClickListener { finish() }
        }
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        handler.removeCallbacks(loop)
        handler.post(loop)
    }

    override fun onPause() {
        handler.removeCallbacks(loop)
        super.onPause()
    }
}


    private val green = Color.parseColor("#00FF41")
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var daysTv: TextView
    private lateinit var timeTv: TextView
    private lateinit var quoteTv: TextView
    private var n = 0L

    private val loop = object : Runnable {
        override fun run() {
            val left = target - System.currentTimeMillis()
            if (left <= 0) {
                daysTv.text = "0"
                timeTv.text = "EXAM DAY"
            } else {
                val s = left / 1000
                val sep = if (n % 2 == 0L) ":" else " "
                daysTv.text = "${s / 86400}"
                timeTv.text = "%02d$sep%02d$sep%02d".format((s % 86400) / 3600, (s % 3600) / 60, s % 60)
            }
            if (n % 15 == 0L) quoteTv.text = quotes[((n / 15) % quotes.size).toInt()]
            n++
            handler.postDelayed(this, 1000 - System.currentTimeMillis() % 1000)
        }
    }

    private fun tv(size: Float, alpha: Int, spacing: Float, glow: Boolean = false) = TextView(this).apply {
        typeface = Typeface.MONOSPACE
        setTextColor(Color.argb(alpha, 0, 255, 65))
        textSize = size
        letterSpacing = spacing
        gravity = Gravity.CENTER
        if (glow) setShadowLayer(24f, 0f, 0f, green)
    }

    private fun hasUsageAccess(): Boolean {
        val ops = getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = ops.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), packageName
        )
        return mode == AppOpsManager.MODE_ALLOWED
    }

    private fun hasNotifAccess(): Boolean {
        val enabled = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
        return enabled != null && enabled.contains(packageName)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        if (!Settings.canDrawOverlays(this)) {
            // 1) Allow "Display over other apps", then open the app again
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
        } else if (!hasUsageAccess()) {
            // 2) Allow "Usage access" for JEE Bar, then open the app again
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        } else if (!hasNotifAccess()) {
            // 3) Allow "Notification access" for JEE Bar (needed for the Instagram lock)
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        } else {
            startForegroundService(Intent(this, OverlayService::class.java))
        }

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION

        val label = tv(12f, 150, 0.3f).apply { text = "> T-MINUS  DAYS" }
        daysTv = tv(88f, 255, 0.05f, glow = true)
        timeTv = tv(40f, 255, 0.12f, glow = true)
        quoteTv = tv(12f, 140, 0.15f)
        val hint = tv(9f, 80, 0.2f).apply { text = "tap anywhere to close" }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.BLACK)
            addView(label)
            addView(daysTv)
            addView(timeTv, LinearLayout.LayoutParams(-1, -2).apply { topMargin = 8 })
            addView(quoteTv, LinearLayout.LayoutParams(-1, -2).apply { topMargin = 72 })
            addView(hint, LinearLayout.LayoutParams(-1, -2).apply { topMargin = 160 })
            setOnClickListener { finish() }
        }
        setContentView(root)
    }
