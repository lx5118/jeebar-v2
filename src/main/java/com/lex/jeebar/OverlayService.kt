package com.lex.jeebar

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
import android.widget.Toast
import android.widget.VideoView
import java.util.Calendar
import java.util.TimeZone

class OverlayService : Service() {

    // ===================== EDIT ZONE =====================
    // JEE Main 2027 Session 1: 22 Jan 2027, 9:00 AM IST (NTA tentative)
    private val target: Long = Calendar.getInstance(TimeZone.getTimeZone("Asia/Kolkata")).apply {
        clear()
        set(2027, Calendar.JANUARY, 22, 9, 0, 0)
    }.timeInMillis

    // Apps that trigger a meme / locks. Games are detected automatically too.
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
    private val ytPkgs = setOf("com.google.android.youtube", "app.revanced.android.youtube")
    private val INSTA = "com.instagram.android"
    private val instaGraceMs = 10 * 60 * 1000L   // Insta stays open this long after a notification
    private val remindEveryMs = 5 * 60 * 1000L   // meme again if you stay inside the app
    private val quickReentryMs = 30 * 1000L      // re-open within this gap: no new meme
    private val flashGapMs = 10 * 60 * 1000L     // flash card at most once per 10 min
    // =====================================================

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
    private var infoTv: TextView? = null
    private var quoteTv: TextView? = null
    private var memeView: FrameLayout? = null
    private var flashView: FrameLayout? = null
    private val handler = Handler(Looper.getMainLooper())
    private var n = 0L

    private var currentPkg: String? = null
    private var lastQuery = 0L
    private var lastShown = 0L
    private var lastFlash = 0L
    private var lastMeme = -1
    private var receiverOn = false
    private val gameCache = HashMap<String, Boolean>()

    private val loop = object : Runnable {
        override fun run() {
            val now = System.currentTimeMillis()
            try { tickBar(now) } catch (e: Exception) { }
            try { tickFocus(now) } catch (e: Exception) { }
            try { tickWatcher(now) } catch (e: Exception) { }
            try { if (n % 60L == 0L) tickReminders(now) } catch (e: Exception) { }
            n++
            handler.postDelayed(this, 1000L - System.currentTimeMillis() % 1000L)
        }
    }

    private val unlockReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (!Store.getBool(this@OverlayService, "flashOn", true)) return
            val now = System.currentTimeMillis()
            if (now - lastFlash < flashGapMs) return
            if (memeView != null || flashView != null) return
            if (!canDraw()) return
            showFlash()
        }
    }

    private fun canDraw(): Boolean = android.provider.Settings.canDrawOverlays(this)

    // ===================== bottom bar =====================
    private fun milestone(days: Long): String? {
        if (days <= 7L) return "last week. sab kuch jhok de."
        if (days <= 30L) return "30 din se kam. all in."
        if (days <= 50L) return "50 din se kam. pace badhao."
        if (days <= 100L) return "100 din se kam. ab serious."
        return null
    }

    private fun tickBar(now: Long) {
        val left = target - now
        val sep = if (n % 2L == 0L) ":" else " "
        timeTv?.text = if (left <= 0L) "EXAM DAY" else {
            val s = left / 1000L
            "%dd %02d$sep%02d$sep%02d".format(s / 86400L, (s % 86400L) / 3600L, (s % 3600L) / 60L, s % 60L)
        }

        val sb = StringBuilder()
        val phase = Store.focusPhase(this)
        if (phase != "none") {
            val r = maxOf(0L, (Store.focusEnd(this) - now) / 1000L)
            sb.append(if (phase == "focus") "FOCUS " else "BREAK ")
            sb.append("%02d:%02d".format(r / 60L, r % 60L))
            sb.append("  ")
        }
        val st = Store.studySecs(this, 0)
        sb.append("STUDY ").append("%dh%02dm".format(st / 3600L, (st % 3600L) / 60L))
        sb.append("  Q ").append(Store.pyq(this, 0)).append("/").append(Store.getInt(this, "pyqTarget", 40))
        val sk = Store.streak(this)
        if (sk > 0) sb.append("  \uD83D\uDD25").append(sk)
        infoTv?.text = sb.toString()

        if (n % 15L == 0L) {
            val days = if (left > 0L) left / 86400000L else 0L
            val m = milestone(days)
            quoteTv?.text = if (m != null && (n / 15L) % 3L == 0L) m else quotes[((n / 15L) % quotes.size).toInt()]
        }
    }

    // ===================== pomodoro =====================
    private fun tickFocus(now: Long) {
        val phase = Store.focusPhase(this)
        if (phase == "none") return
        if (now < Store.focusEnd(this)) return
        if (phase == "focus") {
            Store.stopStudy(this)
            val b = Store.getInt(this, "breakMin", 10)
            Store.putStr(this, "fPhase", "break")
            Store.putLong(this, "fEnd", now + b * 60_000L)
            alert(3001, "Focus complete \u2705", "Break time: $b min. Paani pi, stretch kar.")
        } else {
            Store.putStr(this, "fPhase", "none")
            Store.putLong(this, "fEnd", 0L)
            alert(3002, "Break over", "Agla focus session shuru karo.")
        }
    }

    // ===================== reminders =====================
    private fun tickReminders(now: Long) {
        val revs = Store.revs(this)
        var changed = false
        for (r in revs) {
            if (Store.isDue(r) && r.notified < r.stage) {
                alert(1000 + (r.id % 100000L).toInt(), "Revise: " + r.topic, "review " + (r.stage + 1) + " of 4 due")
                r.notified = r.stage
                changed = true
            }
        }
        if (changed) Store.saveRevs(this, revs)

        val cal = Calendar.getInstance()
        if (cal.get(Calendar.DAY_OF_WEEK) == Calendar.SUNDAY && cal.get(Calendar.HOUR_OF_DAY) >= 9) {
            val today = Store.dayKey(0)
            if (Store.getStr(this, "mockRemind") != today) {
                var last = 0L
                for (m in Store.mocks(this)) {
                    if (m.first > last) last = m.first
                }
                if (now - last > 6L * Store.DAY) {
                    alert(2000, "Weekly mock test", "Is hafte ka mock diya? Score log karo.")
                }
                Store.putStr(this, "mockRemind", today)
            }
        }
    }

    private fun alert(id: Int, title: String, text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        val open = PendingIntent.getActivity(
            this, 2, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val nb = Notification.Builder(this, "jeealerts")
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .setAutoCancel(true)
        nm.notify(id, nb.build())
    }

    // ===================== app watcher =====================
    private fun tickWatcher(now: Long) {
        val usm = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        if (lastQuery == 0L) lastQuery = now - 60_000L
        var entered: String? = null
        try {
            val ev = usm.queryEvents(lastQuery, now)
            val e = UsageEvents.Event()
            while (ev.hasNextEvent()) {
                ev.getNextEvent(e)
                // 1 = resumed / foreground, 2 = paused / background
                if (e.eventType == 1) {
                    if (e.packageName != currentPkg) entered = e.packageName
                    currentPkg = e.packageName
                } else if (e.eventType == 2 && e.packageName == currentPkg) {
                    currentPkg = null
                }
            }
        } catch (ex: Exception) {
        }
        lastQuery = now

        val pkg = currentPkg
        if (pkg == null || pkg == packageName) return
        val hard = isHardTarget(pkg)
        if (hard) Store.addUse(this, pkg, 1L)

        // 1) hard locks: focus, night, schedule, YouTube limit
        if (hard) {
            val reason = blockReason(pkg)
            if (reason != null) {
                if (entered == pkg) Toast.makeText(this, reason, Toast.LENGTH_SHORT).show()
                goHome()
                return
            }
        }

        // 2) Instagram opens only after a notification
        if (pkg == INSTA) {
            if (!InstaGate.allowed(instaGraceMs)) {
                if (entered == pkg) {
                    Toast.makeText(this, "Insta locked \uD83D\uDD12 notification aaye tab hi", Toast.LENGTH_SHORT).show()
                }
                goHome()
            }
            return
        }

        // 3) meme for the rest of the distractions
        if (memeView != null || flashView != null || !isDistraction(pkg)) return
        val sinceLast = now - lastShown
        val freshOpen = entered == pkg && sinceLast > quickReentryMs
        val stayedLong = sinceLast > remindEveryMs
        if (freshOpen || stayedLong) showMeme()
    }

    private fun blockReason(pkg: String): String? {
        if (Store.focusPhase(this) == "focus") return "Focus mode \uD83D\uDD12 padhai chalu hai"
        val h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        if (Store.getBool(this, "nightOn", true) && (h >= 23 || h < 5)) {
            return "Night lock \uD83D\uDE34 so jao"
        }
        if (Store.getBool(this, "lockOn", false)) {
            val s = Store.getInt(this, "lockStart", 18)
            val e = Store.getInt(this, "lockEnd", 22)
            val inside = if (s <= e) (h >= s && h < e) else (h >= s || h < e)
            if (inside) return "Study lock \uD83D\uDD12 $s:00 - $e:00"
        }
        if (pkg in ytPkgs) {
            val lim = Store.getInt(this, "ytLimit", 30)
            if (lim > 0 && Store.useSecs(this, pkg, 0) >= lim * 60L) {
                return "YouTube limit khatam \uD83D\uDD12"
            }
        }
        return null
    }

    private fun isGame(pkg: String): Boolean {
        val cached = gameCache[pkg]
        if (cached != null) return cached
        var result = false
        try {
            val ai = packageManager.getApplicationInfo(pkg, 0)
            @Suppress("DEPRECATION")
            val byFlag = (ai.flags and ApplicationInfo.FLAG_IS_GAME) != 0
            result = ai.category == ApplicationInfo.CATEGORY_GAME || byFlag
        } catch (e: Exception) {
        }
        gameCache[pkg] = result
        return result
    }

    private fun isDistraction(pkg: String): Boolean = pkg in distractions || isGame(pkg)
    private fun isHardTarget(pkg: String): Boolean = pkg == INSTA || isDistraction(pkg)

    private fun goHome() {
        val i = Intent(Intent.ACTION_MAIN)
        i.addCategory(Intent.CATEGORY_HOME)
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(i)
    }

    // ===================== overlays =====================
    private fun mono(s: String, size: Float, alpha: Int): TextView {
        val t = TextView(this)
        t.text = s
        t.typeface = Typeface.MONOSPACE
        t.textSize = size
        t.setTextColor(Color.argb(alpha, 0, 255, 65))
        t.gravity = Gravity.CENTER
        t.letterSpacing = 0.1f
        return t
    }

    private fun obtn(s: String, filled: Boolean, onClick: () -> Unit): Button {
        val b = Button(this)
        b.text = s
        b.typeface = Typeface.MONOSPACE
        b.isAllCaps = false
        b.setTextColor(if (filled) Color.BLACK else green)
        b.setBackgroundColor(if (filled) green else Color.argb(60, 0, 255, 65))
        b.setOnClickListener { onClick() }
        return b
    }

    private fun addFull(v: View) {
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            PixelFormat.TRANSLUCENT
        )
        wm.addView(v, lp)
    }

    private fun memeSources(): List<String> {
        val out = ArrayList<String>()
        for (i in 1..9) {
            val id = resources.getIdentifier("meme$i", "raw", packageName)
            if (id != 0) out.add("android.resource://" + packageName + "/" + id)
        }
        val files = Store.memeDir(this).listFiles()
        if (files != null) {
            for (f in files) out.add(Uri.fromFile(f).toString())
        }
        return out
    }

    private fun makeVideo(uri: String): VideoView {
        val vv = VideoView(this)
        vv.setVideoURI(Uri.parse(uri))
        vv.setOnPreparedListener { mp ->
            mp.isLooping = true
            mp.setVolume(1f, 1f)
        }
        vv.start()
        return vv
    }

    private fun showMeme() {
        lastShown = System.currentTimeMillis()
        val dp = resources.displayMetrics.density

        val src = memeSources()
        var video: VideoView? = null
        if (src.isNotEmpty()) {
            var i = src.indices.random()
            if (src.size > 1 && i == lastMeme) i = (i + 1) % src.size
            lastMeme = i
            video = makeVideo(src[i])
        }

        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.gravity = Gravity.CENTER
        col.setPadding((16 * dp).toInt(), 0, (16 * dp).toInt(), 0)
        col.addView(mono("> PADHAI KAR. ABHI.", 16f, 255))
        if (video != null) {
            val vlp = LinearLayout.LayoutParams(-1, (240 * dp).toInt())
            vlp.topMargin = (16 * dp).toInt()
            vlp.bottomMargin = (16 * dp).toInt()
            col.addView(video, vlp)
        }
        col.addView(mono(quotes.random(), 12f, 160))
        val b1 = LinearLayout.LayoutParams(-1, -2)
        b1.topMargin = (24 * dp).toInt()
        col.addView(obtn("padhne chala \u2713", true) { dismissMeme(true) }, b1)
        val b2 = LinearLayout.LayoutParams(-1, -2)
        b2.topMargin = (8 * dp).toInt()
        col.addView(obtn("5 min aur", false) { dismissMeme(false) }, b2)

        val frame = FrameLayout(this)
        frame.setBackgroundColor(Color.argb(245, 0, 0, 0))
        frame.addView(col, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER))
        memeView = frame
        addFull(frame)
    }

    private fun dismissMeme(home: Boolean) {
        val v = memeView
        if (v != null) wm.removeView(v)
        memeView = null
        lastShown = System.currentTimeMillis()
        if (home) goHome()
    }

    private fun showFlash() {
        lastFlash = System.currentTimeMillis()
        val dp = resources.displayMetrics.density
        val card = Cards.all.random()

        val q = mono("Q: " + card.first, 16f, 255)
        val a = mono("A: " + card.second, 13f, 210)
        a.visibility = View.GONE

        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.gravity = Gravity.CENTER
        col.setPadding((20 * dp).toInt(), 0, (20 * dp).toInt(), 0)
        col.addView(mono("> FLASH CARD", 12f, 140))
        val qlp = LinearLayout.LayoutParams(-1, -2)
        qlp.topMargin = (20 * dp).toInt()
        col.addView(q, qlp)
        val alp = LinearLayout.LayoutParams(-1, -2)
        alp.topMargin = (16 * dp).toInt()
        col.addView(a, alp)

        val result = LinearLayout(this)
        result.orientation = LinearLayout.HORIZONTAL
        result.visibility = View.GONE
        val knew = obtn("knew it \u2713", true) {
            Store.putInt(this, "flashKnew", Store.getInt(this, "flashKnew", 0) + 1)
            closeFlash()
        }
        val missed = obtn("missed \u2717", false) {
            Store.putInt(this, "flashMissed", Store.getInt(this, "flashMissed", 0) + 1)
            closeFlash()
        }
        result.addView(knew, LinearLayout.LayoutParams(0, -2, 1f))
        result.addView(missed, LinearLayout.LayoutParams(0, -2, 1f))

        val show = obtn("answer dikhao", false) { }
        show.setOnClickListener {
            a.visibility = View.VISIBLE
            show.visibility = View.GONE
            result.visibility = View.VISIBLE
        }
        val slp = LinearLayout.LayoutParams(-1, -2)
        slp.topMargin = (28 * dp).toInt()
        col.addView(show, slp)
        val rlp = LinearLayout.LayoutParams(-1, -2)
        rlp.topMargin = (28 * dp).toInt()
        col.addView(result, rlp)

        val frame = FrameLayout(this)
        frame.setBackgroundColor(Color.argb(245, 0, 0, 0))
        frame.addView(col, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER))
        flashView = frame
        addFull(frame)
    }

    private fun closeFlash() {
        val v = flashView
        if (v != null) wm.removeView(v)
        flashView = null
    }

    // ===================== service plumbing =====================
    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") {
            stopSelf()
            return START_NOT_STICKY
        }
        startInForeground()
        if (root == null) addBar()
        if (!receiverOn) {
            val f = IntentFilter(Intent.ACTION_USER_PRESENT)
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(unlockReceiver, f, Context.RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(unlockReceiver, f)
            }
            receiverOn = true
        }
        handler.removeCallbacks(loop)
        handler.post(loop)
        return START_STICKY
    }

    private fun addBar() {
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val dp = resources.displayMetrics.density

        val line = View(this)
        line.setBackgroundColor(Color.argb(90, 0, 255, 65))

        val t = TextView(this)
        t.typeface = Typeface.MONOSPACE
        t.setTextColor(green)
        t.setShadowLayer(10f, 0f, 0f, green)
        t.textSize = 15f
        t.letterSpacing = 0.12
