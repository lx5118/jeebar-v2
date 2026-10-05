package com.lex.jeebar

import android.app.*
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.VideoView
import java.util.Calendar
import java.util.TimeZone

class OverlayService : Service() {

    // ===== EDIT ZONE =====
    // JEE Main 2027 Session 1: 22 Jan 2027, 9:00 AM IST (NTA tentative)
    private val target: Long = Calendar.getInstance(TimeZone.getTimeZone("Asia/Kolkata")).apply {
        clear(); set(2027, Calendar.JANUARY, 22, 9, 0, 0)
    }.timeInMillis

    // Apps that trigger a meme. Games are detected automatically too.
    private val distractions = setOf(
        "com.google.android.youtube",
        "app.revanced.android.youtube",
        "com.reddit.frontpage",
        "com.pinterest",
        "org.mozilla.firefox",
        "org.mozilla.firefox_beta",
        "org.mozilla.fenix",
        "org.mozilla.focus",
        "com.snapchat.android",
        "com.twitter.android",
        "com.facebook.katana",
        "com.netflix.mediaclient"
    )

    // Instagram stays locked unless a notification arrived in the last X minutes
    // (or one is still sitting in the notification shade)
    private val INSTA = "com.instagram.android"
    private val instaGraceMs = 10 * 60 * 1000L

    private val remindEveryMs = 5 * 60 * 1000L   // reminder again if you stay in the app
    private val quickReentryMs = 30 * 1000L      // fresh open within this gap won't re-trigger
    // ======================

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
    private var memeView: FrameLayout? = null
    private val handler = Handler(Looper.getMainLooper())
    private var n = 0L

    // foreground-app tracking
    private var currentPkg: String? = null
    private var lastQuery = 0L
    private var lastShown = 0L
    private var lastMeme = -1

    private val loop = object : Runnable {
        override fun run() {
            tickBar()
            tickWatcher()
            n++
            handler.postDelayed(this, 1000 - System.currentTimeMillis() % 1000)
        }
    }

    private fun tickBar() {
        val left = target - System.currentTimeMillis()
        val sep = if (n % 2 == 0L) ":" else " "
        timeTv?.text = if (left <= 0) "EXAM DAY" else {
            val s = left / 1000
            "%dd %02d$sep%02d$sep%02d".format(s / 86400, (s % 86400) / 3600, (s % 3600) / 60, s % 60)
        }
        if (n % 15 == 0L) quoteTv?.text = quotes[((n / 15) % quotes.size).toInt()]
    }

    // ---------- distraction watcher ----------
    private fun tickWatcher() {
        val usm = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val now = System.currentTimeMillis()
        if (lastQuery == 0L) lastQuery = now - 60_000
        var entered: String? = null
        try {
            val ev = usm.queryEvents(lastQuery, now)
            val e = android.app.usage.UsageEvents.Event()
            while (ev.hasNextEvent()) {
                ev.getNextEvent(e)
                // 1 = resumed / moved to foreground, 2 = paused / moved to background
                if (e.eventType == 1) {
                    if (e.packageName != currentPkg) entered = e.packageName
                    currentPkg = e.packageName
                } else if (e.eventType == 2 && e.packageName == currentPkg) {
                    currentPkg = null
                }
            }
        } catch (_: Exception) { }
        lastQuery = now

        val pkg = currentPkg ?: return
        if (pkg == packageName) return
        if (pkg == INSTA) {
            if (!InstaGate.allowed(instaGraceMs)) {
                if (entered != null) {
                    android.widget.Toast.makeText(
                        this, "Insta locked \uD83D\uDD12 notification aaye tab hi", android.widget.Toast.LENGTH_SHORT
                    ).show()
                }
                goHome()
            }
            return
        }
        if (memeView != null || !isDistraction(pkg)) return

        val sinceLast = now - lastShown
        val freshOpen = entered != null && sinceLast > quickReentryMs
        val stayedLong = sinceLast > remindEveryMs
        if (freshOpen || stayedLong) showMeme()
    }

    private fun isDistraction(pkg: String): Boolean {
        if (pkg in distractions) return true
        return try {
            val ai = packageManager.getApplicationInfo(pkg, 0)
            @Suppress("DEPRECATION")
            ai.category == ApplicationInfo.CATEGORY_GAME ||
                    (ai.flags and ApplicationInfo.FLAG_IS_GAME) != 0
        } catch (_: Exception) { false }
    }

    private fun showMeme() {
        lastShown = System.currentTimeMillis()
        val dp = resources.displayMetrics.density

        // pick a random meme that differs from the last one
        val ids = (1..5).map { resources.getIdentifier("meme$it", "raw", packageName) }
            .filter { it != 0 }
        var video: VideoView? = null
        if (ids.isNotEmpty()) {
            var i: Int
            do { i = ids.indices.random() } while (ids.size > 1 && i == lastMeme)
            lastMeme = i
            video = VideoView(this).apply {
                setVideoURI(Uri.parse("android.resource://$packageName/${ids[i]}"))
                setOnPreparedListener { it.isLooping = true; it.setVolume(1f, 1f) }
                start()
            }
        }

        fun mono(txt: String, size: Float, alpha: Int) = TextView(this).apply {
            text = txt; typeface = Typeface.MONOSPACE; textSize = size
            setTextColor(Color.argb(alpha, 0, 255, 65)); gravity = Gravity.CENTER
            letterSpacing = 0.1f
        }

        fun btn(txt: String, filled: Boolean, onClick: () -> Unit) = Button(this).apply {
            text = txt; typeface = Typeface.MONOSPACE; isAllCaps = false
            setTextColor(if (filled) Color.BLACK else green)
            setBackgroundColor(if (filled) green else Color.argb(60, 0, 255, 65))
            setOnClickListener { onClick() }
        }

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding((16 * dp).toInt(), 0, (16 * dp).toInt(), 0)
            addView(mono("> PADHAI KAR. ABHI.", 16f, 255))
            video?.let {
                addView(it, LinearLayout.LayoutParams(-1, (240 * dp).toInt()).apply {
                    topMargin = (16 * dp).toInt(); bottomMargin = (16 * dp).toInt()
                })
            }
            addView(mono(quotes.random(), 12f, 160))
            addView(btn("padhne chala ✓", true) { dismissMeme(home = true) },
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = (24 * dp).toInt() })
            addView(btn("5 min aur", false) { dismissMeme(home = false) },
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = (8 * dp).toInt() })
        }

        memeView = FrameLayout(this).apply {
            setBackgroundColor(Color.argb(245, 0, 0, 0))
            addView(col, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER))
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            PixelFormat.TRANSLUCENT
        )
        wm.addView(memeView, lp)
    }

    private fun dismissMeme(home: Boolean) {
        memeView?.let { wm.removeView(it) }
        memeView = null
        lastShown = System.currentTimeMillis()
        if (home) goHome()
    }

    private fun goHome() {
        startActivity(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    // ---------- bottom bar ----------
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

        val line = View(this).apply { setBackgroundColor(Color.argb(90, 0, 255, 65)) }
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
        val open = PendingIntent.getActivity(
            this, 1, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val notif = Notification.Builder(this, "jeebar")
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("JEE countdown running")
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else startForeground(1, notif)
    }

    override fun onDestroy() {
        handler.removeCallbacks(loop)
        memeView?.let { wm.removeView(it) }
        memeView = null
        root?.let { wm.removeView(it) }
        root = null
        super.onDestroy()
    }
}
