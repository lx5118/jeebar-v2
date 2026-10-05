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
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class MainActivity : Activity() {

    // Keep same as OverlayService target: 22 Jan 2027, 9:00 AM IST
    private val target: Long = Calendar.getInstance(TimeZone.getTimeZone("Asia/Kolkata")).apply {
        clear()
        set(2027, Calendar.JANUARY, 22, 9, 0, 0)
    }.timeInMillis

    private val green = Color.parseColor("#00FF41")
    private val handler = Handler(Looper.getMainLooper())
    private val pickMeme = 77

    private lateinit var scroll: ScrollView
    private lateinit var content: LinearLayout
    private var daysTv: TextView? = null
    private var clockTv: TextView? = null
    private var focusTv: TextView? = null
    private var studyTv: TextView? = null

    // ---------- small view helpers ----------
    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun txt(s: String, size: Float, alpha: Int, glow: Boolean): TextView {
        val t = TextView(this)
        t.text = s
        t.typeface = Typeface.MONOSPACE
        t.textSize = size
        t.setTextColor(Color.argb(alpha, 0, 255, 65))
        if (glow) t.setShadowLayer(14f, 0f, 0f, green)
        t.setPadding(0, dp(3), 0, dp(3))
        return t
    }

    private fun head(s: String): TextView {
        val t = txt("> $s", 12f, 150, false)
        t.letterSpacing = 0.2f
        t.setPadding(0, dp(24), 0, dp(4))
        return t
    }

    private fun btn(s: String, onClick: () -> Unit): Button {
        val b = Button(this)
        b.text = s
        b.typeface = Typeface.MONOSPACE
        b.isAllCaps = false
        b.textSize = 12f
        b.setTextColor(Color.BLACK)
        b.setBackgroundColor(green)
        b.setOnClickListener { onClick() }
        return b
    }

    private fun add(v: View) {
        content.addView(v, LinearLayout.LayoutParams(-1, -2))
    }

    private fun row(vararg vs: View): LinearLayout {
        val r = LinearLayout(this)
        r.orientation = LinearLayout.HORIZONTAL
        r.gravity = Gravity.CENTER_VERTICAL
        for (v in vs) {
            val lp = LinearLayout.LayoutParams(0, -2, 1f)
            lp.setMargins(dp(2), dp(2), dp(2), dp(2))
            r.addView(v, lp)
        }
        return r
    }

    private fun input(hint: String, numeric: Boolean): EditText {
        val e = EditText(this)
        e.hint = hint
        e.typeface = Typeface.MONOSPACE
        e.textSize = 13f
        e.setTextColor(green)
        e.setHintTextColor(Color.argb(90, 0, 255, 65))
        e.inputType = if (numeric) InputType.TYPE_CLASS_NUMBER else InputType.TYPE_CLASS_TEXT
        return e
    }

    private fun stepper(label: String, key: String, def: Int, step: Int, min: Int, max: Int, unit: String) {
        val ctx = this
        val v = txt("", 13f, 255, false)
        fun refresh() {
            v.text = label + ": " + Store.getInt(ctx, key, def) + unit
        }
        refresh()
        val minus = btn("-") {
            Store.putInt(ctx, key, maxOf(min, Store.getInt(ctx, key, def) - step))
            refresh()
        }
        val plus = btn("+") {
            Store.putInt(ctx, key, minOf(max, Store.getInt(ctx, key, def) + step))
            refresh()
        }
        val r = LinearLayout(this)
        r.orientation = LinearLayout.HORIZONTAL
        r.gravity = Gravity.CENTER_VERTICAL
        r.addView(v, LinearLayout.LayoutParams(0, -2, 1f))
        r.addView(minus, LinearLayout.LayoutParams(dp(56), -2))
        r.addView(plus, LinearLayout.LayoutParams(dp(56), -2))
        add(r)
    }

    private fun toggle(label: String, key: String, def: Boolean) {
        val ctx = this
        val b = btn("") { }
        fun refresh() {
            b.text = label + ": " + (if (Store.getBool(ctx, key, def)) "ON" else "OFF")
        }
        refresh()
        b.setOnClickListener {
            Store.putBool(ctx, key, !Store.getBool(ctx, key, def))
            refresh()
        }
        add(b)
    }

    private fun permRow(name: String, ok: Boolean, open: () -> Unit) {
        val t = txt(name + ": " + (if (ok) "OK" else "MISSING"), 12f, if (ok) 255 else 170, false)
        if (ok) add(t) else add(row(t, btn("allow") { open() }))
    }

    // ---------- permissions ----------
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

    // ---------- live ticking bits ----------
    private fun updateLive() {
        val now = System.currentTimeMillis()
        val left = target - now
        if (left <= 0L) {
            daysTv?.text = "0"
            clockTv?.text = "EXAM DAY"
        } else {
            val s = left / 1000L
            daysTv?.text = (s / 86400L).toString() + " days"
            clockTv?.text = "%02d:%02d:%02d".format((s % 86400L) / 3600L, (s % 3600L) / 60L, s % 60L)
        }
        val phase = Store.focusPhase(this)
        if (phase == "none") {
            focusTv?.text = "idle"
        } else {
            val r = maxOf(0L, (Store.focusEnd(this) - now) / 1000L)
            focusTv?.text = (if (phase == "focus") "FOCUS  " else "BREAK  ") + "%02d:%02d left".format(r / 60L, r % 60L)
        }
        val st = Store.studySecs(this, 0)
        val running = if (Store.studyStart(this) > 0L) "  (running)" else ""
        studyTv?.text = "today %dh%02dm   streak %d day(s)%s".format(
            st / 3600L, (st % 3600L) / 60L, Store.streak(this), running
        )
    }

    private val ticker = object : Runnable {
        override fun run() {
            updateLive()
            handler.postDelayed(this, 1000L)
        }
    }

    // ---------- whole screen ----------
    private fun render() {
        val ctx = this
        val keepY = scroll.scrollY
        content.removeAllViews()

        // header
        val d = txt("", 54f, 255, true)
        d.gravity = Gravity.CENTER
        daysTv = d
        add(d)
        val c = txt("", 22f, 255, true)
        c.gravity = Gravity.CENTER
        clockTv = c
        add(c)
        add(btn("full screen countdown") { startActivity(Intent(ctx, FullScreenActivity::class.java)) })

        // focus
        add(head("FOCUS MODE (pomodoro)"))
        val f = txt("", 16f, 255, true)
        focusTv = f
        add(f)
        add(row(
            btn("start focus") { Store.startFocus(ctx); updateLive() },
            btn("stop") { Store.stopFocus(ctx); updateLive() }
        ))
        stepper("focus length", "focusMin", 50, 5, 10, 120, " min")
        stepper("break length", "breakMin", 10, 5, 5, 30, " min")
        add(txt("focus ke dauran distraction apps poori tarah block", 10f, 120, false))

        // study tracker
        add(head("STUDY TRACKER"))
        val sv = txt("", 14f, 255, false)
        studyTv = sv
        add(sv)
        add(row(
            btn("start study") { Store.startStudy(ctx); updateLive() },
            btn("stop") { Store.stopStudy(ctx); updateLive() }
        ))
        add(txt("30 min+ ka din streak mein ginta hai", 10f, 120, false))

        // PYQ counter
        add(head("PYQ COUNTER"))
        val pv = txt("", 22f, 255, true)
        fun pyqRefresh() {
            pv.text = Store.pyq(ctx, 0).toString() + " / " + Store.getInt(ctx, "pyqTarget", 40)
        }
        pyqRefresh()
        add(pv)
        add(row(
            btn("+1") { Store.addPyq(ctx, 1); pyqRefresh() },
            btn("+5") { Store.addPyq(ctx, 5); pyqRefresh() },
            btn("-1") { Store.addPyq(ctx, -1); pyqRefresh() }
        ))
        stepper("daily target", "pyqTarget", 40, 5, 5, 200, "")

        // revision
        add(head("REVISION (1-3-7-21 din)"))
        val topic = input("topic, e.g. Aldehydes mechanisms", false)
        add(topic)
        add(btn("add topic") {
            Store.addRev(ctx, topic.text.toString())
            render()
        })
        val revs = Store.revs(this)
        if (revs.isEmpty()) add(txt("abhi koi topic nahi", 11f, 120, false))
        val now = System.currentTimeMillis()
        for (r in revs) {
            val finished = r.stage >= Store.REVIEW_DAYS.size
            val due = Store.isDue(r)
            val status: String
            if (finished) {
                status = "[complete]"
            } else if (due) {
                status = "[DUE: review " + (r.stage + 1) + "/4]"
            } else {
                val days = (Store.dueAt(r) - now + Store.DAY - 1L) / Store.DAY
                status = "[next in " + days + "d]"
            }
            val label = txt(r.topic + "  " + status, 12f, if (due) 255 else 150, false)
            val rid = r.id
            val line = LinearLayout(this)
            line.orientation = LinearLayout.HORIZONTAL
            line.gravity = Gravity.CENTER_VERTICAL
            line.addView(label, LinearLayout.LayoutParams(0, -2, 1f))
            if (due) {
                line.addView(btn("done") { Store.completeRev(ctx, rid); render() }, LinearLayout.LayoutParams(dp(70), -2))
            }
            line.addView(btn("x") { Store.deleteRev(ctx, rid); render() }, LinearLayout.LayoutParams(dp(48), -2))
            add(line)
        }

        // mock tests
        add(head("MOCK TESTS (weekly)"))
        val mockIn = input("score, e.g. 142", true)
        add(mockIn)
        add(btn("log score") {
            val v = mockIn.text.toString().trim().toIntOrNull()
            if (v != null) {
                Store.addMock(ctx, v)
                render()
            }
        })
        val mocks = Store.mocks(this)
        if (mocks.isEmpty()) add(txt("abhi koi score nahi", 11f, 120, false))
        val from = maxOf(0, mocks.size - 5)
        for (i in from until mocks.size) {
            val dateStr = SimpleDateFormat("dd MMM", Locale.US).format(Date(mocks[i].first))
            var trend = ""
            if (i > 0) {
                val diff = mocks[i].second - mocks[i - 1].second
                trend = if (diff > 0) "  (+" + diff + ")" else if (diff < 0) "  (" + diff + ")" else "  (=)"
            }
            add(txt(dateStr + "   " + mocks[i].second + trend, 12f, 220, false))
        }

        // 7 day report
        add(head("LAST 7 DAYS"))
        var total = 0L
        for (i in 6 downTo 0) {
            val s = Store.studySecs(this, i)
            total += s
            val cal = Calendar.getInstance()
            cal.add(Calendar.DAY_OF_YEAR, -i)
            val dn = SimpleDateFormat("EEE", Locale.US).format(cal.time)
            val blocks = minOf(16, (s / 1800L).toInt())
            add(txt("%s  %dh%02dm  %s".format(dn, s / 3600L, (s % 3600L) / 60L, "\u2588".repeat(blocks)), 12f, 220, false))
        }
        add(txt("total %dh%02dm   avg %.1f h/day".format(total / 3600L, (total % 3600L) / 60L, total / 3600.0 / 7.0), 12f, 255, false))

        val usage = ArrayList<Pair<String, Long>>()
        for (p in Store.usedPkgs(this)) {
            var sum = 0L
            for (i in 0..6) sum += Store.useSecs(this, p, i)
            if (sum > 0L) usage.add(Pair(p, sum))
        }
        usage.sortByDescending { it.second }
        add(txt("distraction time (7 days):", 11f, 150, false))
        if (usage.isEmpty()) add(txt("-", 12f, 120, false))
        for (i in 0 until minOf(5, usage.size)) {
            val p = usage[i].first
            var name = p
            try {
                name = packageManager.getApplicationLabel(packageManager.getApplicationInfo(p, 0)).toString()
            } catch (e: Exception) {
            }
            add(txt("%s  %d min".format(name, usage[i].second / 60L), 12f, 220, false))
        }

        // locks
        add(head("LOCKS & EXTRAS"))
        toggle("scheduled lock", "lockOn", false)
        stepper("lock start hour", "lockStart", 18, 1, 0, 23, ":00")
        stepper("lock end hour", "lockEnd", 22, 1, 0, 23, ":00")
        toggle("night lock (11pm-5am)", "nightOn", true)
        stepper("YouTube daily limit (0 = off)", "ytLimit", 30, 5, 0, 240, " min")
        toggle("flash card on unlock", "flashOn", true)
        add(txt("locks sirf distraction apps/games pe lagte hain, calls aur alarm hamesha chalte hain", 10f, 120, false))

        // memes
        add(head("MEMES"))
        val files = Store.memeDir(this).listFiles()
        add(txt("tumhare add kiye memes: " + (if (files == null) 0 else files.size), 12f, 200, false))
        add(row(
            btn("add meme video") {
                val i = Intent(Intent.ACTION_OPEN_DOCUMENT)
                i.addCategory(Intent.CATEGORY_OPENABLE)
                i.type = "video/*"
                startActivityForResult(i, pickMeme)
            },
            btn("clear added") {
                val fs = Store.memeDir(ctx).listFiles()
                if (fs != null) for (x in fs) x.delete()
                render()
            }
        ))

        // permissions
        add(head("PERMISSIONS & BAR"))
        permRow("overlay", Settings.canDrawOverlays(this)) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
        permRow("usage access", hasUsageAccess()) {
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        }
        permRow("notification access (Insta lock)", hasNotifAccess()) {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
        add(row(
            btn("start bar") { startBar() },
            btn("stop bar") { stopService(Intent(ctx, OverlayService::class.java)) }
        ))

        updateLive()
        scroll.post { scroll.scrollTo(0, keepY) }
    }

    private fun startBar() {
        if (Settings.canDrawOverlays(this)) {
            startForegroundService(Intent(this, OverlayService::class.java))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        @Suppress("DEPRECATION")
        window.statusBarColor = Color.BLACK

        content = LinearLayout(this)
        content.orientation = LinearLayout.VERTICAL
        content.setPadding(dp(16), dp(24), dp(16), dp(40))
        scroll = ScrollView(this)
        scroll.setBackgroundColor(Color.BLACK)
        scroll.addView(content)
        setContentView(scroll)
    }

    override fun onResume() {
        super.onResume()
        startBar()
        render()
        handler.removeCallbacks(ticker)
        handler.post(ticker)
    }

    override fun onPause() {
        handler.removeCallbacks(ticker)
        super.onPause()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == pickMeme && resultCode == RESULT_OK && data != null) {
            val uri = data.data
            val ctx = this
            if (uri != null) {
                Thread {
                    try {
                        val ins = ctx.contentResolver.openInputStream(uri)
                        if (ins != null) {
                            val out = File(Store.memeDir(ctx), "m_" + System.currentTimeMillis() + ".mp4")
                            ins.use { input -> out.outputStream().use { o -> input.copyTo(o) } }
                        }
                    } catch (e: Exception) {
                    }
                    ctx.runOnUiThread { render() }
                }.start()
            }
        }
    }
}
