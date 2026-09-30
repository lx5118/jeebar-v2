package com.lex.jeebar

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Calendar
import java.util.TimeZone

class OverlayService : Service() {

    // JEE Main 2027 Session 1: 22 Jan 2027, 9:00 AM IST (NTA tentative). Change if needed.
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
    private lateinit var wm: WindowManager
    private var root: LinearLayout? = null
    private var timeTv: TextView? = null
    private var quoteTv: TextView? = null
    private val handler = Handler(Looper.getMainLooper())
    private var n = 0L

    private val loop = object : Runnable {
        override fun run() {
            val left = target - System.currentTimeMillis()
            val sep = if (n % 2 == 0L) ":" else " "
            timeTv?.text = if (left <= 0) "EXAM DAY" else {
                val s = left / 1000
                "%dd %02d$sep%02d$sep%02d".format(
                    s / 86400, (s % 86400) / 3600, (s % 3600) / 60, s % 60
                )
            }
            if (n % 15 == 0L) quoteTv?.text = quotes[((n / 15) % quotes.size).toInt()]
            n++
            handler.postDelayed(this, 1000 - System.currentTimeMillis() % 1000)
        }
    }

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") {
            stopSelf(); return START_NOT_STICKY
        }
        startInForeground()
        if (root == null) addBar()
        handler.removeCallbacks(loop)
        handler.post(loop)
        return START_STICKY
    }

    private fun addBar() {
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val dp = resources.displayMetrics.density

        val line = View(this).apply {
            setBackgroundColor(Color.argb(90, 0, 255, 65))
        }
        timeTv = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            setTextColor(green)
            setShadowLayer(10f, 0f, 0f, green)
            textSize = 15f
            letterSpacing = 0.12f
            gravity = Gravity.CENTER
        }
        quoteTv = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            setTextColor(Color.argb(140, 0, 255, 65))
            textSize = 9f
            letterSpacing = 0.15f
            gravity = Gravity.CENTER
        }
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.argb(210, 0, 0, 0))
            setPadding(0, 0, 0, (6 * dp).toInt())
            addView(line, LinearLayout.LayoutParams(-1, (1 * dp).toInt().coerceAtLeast(1)))
            addView(timeTv, LinearLayout.LayoutParams(-1, -2).apply { topMargin = (5 * dp).toInt() })
            addView(quoteTv, LinearLayout.LayoutParams(-1, -2))
        }

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.BOTTOM }
        wm.addView(root, lp)
    }

    private fun startInForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel("jeebar", "JEE Bar", NotificationManager.IMPORTANCE_MIN)
        )
        val stop = PendingIntent.getService(
            this, 0, Intent(this, OverlayService::class.java).setAction("STOP"),
            PendingIntent.FLAG_IMMUTABLE
        )
        val notif = Notification.Builder(this, "jeebar")
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("JEE countdown running")
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else startForeground(1, notif)
    }

    override fun onDestroy() {
        handler.removeCallbacks(loop)
        root?.let { wm.removeView(it) }
        root = null
        super.onDestroy()
    }
}
