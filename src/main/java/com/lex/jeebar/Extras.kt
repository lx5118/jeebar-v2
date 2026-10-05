package com.lex.jeebar

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

// =====================================================================
//  Store: everything the app remembers (study time, PYQs, revision...)
// =====================================================================
class Rev(val id: Long, val topic: String, val start: Long, var stage: Int, var notified: Int)

object Store {
    const val DAY = 86_400_000L
    val REVIEW_DAYS = intArrayOf(1, 3, 7, 21)

    private fun sp(c: Context): SharedPreferences =
        c.applicationContext.getSharedPreferences("jee", Context.MODE_PRIVATE)

    fun getInt(c: Context, k: String, d: Int): Int = sp(c).getInt(k, d)
    fun putInt(c: Context, k: String, v: Int) { sp(c).edit().putInt(k, v).apply() }
    fun getLong(c: Context, k: String): Long = sp(c).getLong(k, 0L)
    fun putLong(c: Context, k: String, v: Long) { sp(c).edit().putLong(k, v).apply() }
    fun getStr(c: Context, k: String): String = sp(c).getString(k, "") ?: ""
    fun putStr(c: Context, k: String, v: String) { sp(c).edit().putString(k, v).apply() }
    fun getBool(c: Context, k: String, d: Boolean): Boolean = sp(c).getBoolean(k, d)
    fun putBool(c: Context, k: String, v: Boolean) { sp(c).edit().putBoolean(k, v).apply() }

    fun dayKey(offset: Int): String {
        val cal = Calendar.getInstance()
        cal.add(Calendar.DAY_OF_YEAR, -offset)
        return SimpleDateFormat("yyyy-MM-dd", Locale.US).format(cal.time)
    }

    // ---------- study timer ----------
    fun studyStart(c: Context): Long = getLong(c, "studyStart")

    fun startStudy(c: Context) {
        if (studyStart(c) == 0L) putLong(c, "studyStart", System.currentTimeMillis())
    }

    fun stopStudy(c: Context) {
        val s = studyStart(c)
        if (s > 0L) {
            val secs = (System.currentTimeMillis() - s) / 1000L
            val k = "study_" + dayKey(0)
            putLong(c, k, getLong(c, k) + secs)
            putLong(c, "studyStart", 0L)
        }
    }

    fun studySecs(c: Context, offset: Int): Long {
        var t = getLong(c, "study_" + dayKey(offset))
        if (offset == 0) {
            val s = studyStart(c)
            if (s > 0L) t += (System.currentTimeMillis() - s) / 1000L
        }
        return t
    }

    // a day counts for the streak when you studied at least 30 minutes
    fun streak(c: Context): Int {
        var n = 0
        var i = if (studySecs(c, 0) >= 1800L) 0 else 1
        while (i < 400 && studySecs(c, i) >= 1800L) {
            n++
            i++
        }
        return n
    }

    // ---------- focus (pomodoro) ----------
    fun focusPhase(c: Context): String {
        val p = getStr(c, "fPhase")
        return if (p.isEmpty()) "none" else p
    }

    fun focusEnd(c: Context): Long = getLong(c, "fEnd")

    fun startFocus(c: Context) {
        val m = getInt(c, "focusMin", 50)
        putStr(c, "fPhase", "focus")
        putLong(c, "fEnd", System.currentTimeMillis() + m * 60_000L)
        startStudy(c)
    }

    fun stopFocus(c: Context) {
        putStr(c, "fPhase", "none")
        putLong(c, "fEnd", 0L)
        stopStudy(c)
    }

    // ---------- PYQ counter ----------
    fun pyq(c: Context, offset: Int): Int = getInt(c, "pyq_" + dayKey(offset), 0)

    fun addPyq(c: Context, n: Int) {
        val k = "pyq_" + dayKey(0)
        putInt(c, k, maxOf(0, getInt(c, k, 0) + n))
    }

    // ---------- per-app usage (for report + YouTube limit) ----------
    fun addUse(c: Context, pkg: String, secs: Long) {
        val k = "use_" + dayKey(0) + "_" + pkg
        putLong(c, k, getLong(c, k) + secs)
        val old = sp(c).getStringSet("usepkgs", null)
        if (old == null || !old.contains(pkg)) {
            val n = HashSet<String>()
            if (old != null) n.addAll(old)
            n.add(pkg)
            sp(c).edit().putStringSet("usepkgs", n).apply()
        }
    }

    fun useSecs(c: Context, pkg: String, offset: Int): Long =
        getLong(c, "use_" + dayKey(offset) + "_" + pkg)

    fun usedPkgs(c: Context): List<String> {
        val s = sp(c).getStringSet("usepkgs", null)
        return if (s == null) ArrayList<String>() else ArrayList<String>(s)
    }

    // ---------- spaced revision ----------
    fun revs(c: Context): MutableList<Rev> {
        val out = ArrayList<Rev>()
        for (line in getStr(c, "revs").split("\n")) {
            val p = line.split("|")
            if (p.size >= 5) {
                try {
                    out.add(Rev(p[0].toLong(), p[1], p[2].toLong(), p[3].toInt(), p[4].toInt()))
                } catch (e: Exception) {
                }
            }
        }
        return out
    }

    fun saveRevs(c: Context, list: List<Rev>) {
        val sb = StringBuilder()
        for (r in list) {
            sb.append(r.id).append("|").append(r.topic).append("|").append(r.start)
                .append("|").append(r.stage).append("|").append(r.notified).append("\n")
        }
        putStr(c, "revs", sb.toString().trim())
    }

    fun addRev(c: Context, topic: String) {
        val t = topic.replace("|", " ").replace("\n", " ").trim()
        if (t.isEmpty()) return
        val l = revs(c)
        val now = System.currentTimeMillis()
        l.add(Rev(now, t, now, 0, -1))
        saveRevs(c, l)
    }

    fun completeRev(c: Context, id: Long) {
        val l = revs(c)
        for (r in l) {
            if (r.id == id && r.stage < REVIEW_DAYS.size) r.stage = r.stage + 1
        }
        saveRevs(c, l)
    }

    fun deleteRev(c: Context, id: Long) {
        val l = revs(c)
        val keep = ArrayList<Rev>()
        for (r in l) {
            if (r.id != id) keep.add(r)
        }
        saveRevs(c, keep)
    }

    fun dueAt(r: Rev): Long = r.start + REVIEW_DAYS[minOf(r.stage, REVIEW_DAYS.size - 1)] * DAY

    fun isDue(r: Rev): Boolean = r.stage < REVIEW_DAYS.size && System.currentTimeMillis() >= dueAt(r)

    // ---------- mock tests ----------
    fun mocks(c: Context): List<Pair<Long, Int>> {
        val out = ArrayList<Pair<Long, Int>>()
        for (line in getStr(c, "mocks").split("\n")) {
            val p = line.split("|")
            if (p.size >= 2) {
                try {
                    out.add(Pair(p[0].toLong(), p[1].toInt()))
                } catch (e: Exception) {
                }
            }
        }
        return out
    }

    fun addMock(c: Context, score: Int) {
        val old = getStr(c, "mocks")
        val line = System.currentTimeMillis().toString() + "|" + score
        putStr(c, "mocks", if (old.isEmpty()) line else old + "\n" + line)
    }

    // ---------- custom memes added from inside the app ----------
    fun memeDir(c: Context): File {
        val d = File(c.filesDir, "memes")
        if (!d.exists()) d.mkdirs()
        return d
    }
}

// =====================================================================
//  Flash cards shown when you unlock the phone (edit / add your own!)
// =====================================================================
object Cards {
    val all: List<Pair<String, String>> = listOf(
        Pair("SN1 vs SN2: rate law?", "SN1: rate = k[RX] (carbocation, racemisation). SN2: rate = k[RX][Nu] (backside attack, inversion)."),
        Pair("Markovnikov's rule?", "In HX addition to an alkene, H goes to the carbon with more H's; X goes to the more substituted carbon."),
        Pair("Peroxide (anti-Markovnikov) effect works with?", "Only HBr, via free-radical mechanism."),
        Pair("Saytzeff rule?", "In elimination, the more substituted (more stable) alkene is the major product."),
        Pair("Reimer-Tiemann reaction?", "Phenol + CHCl3 + NaOH gives salicylaldehyde (ortho-formylation) via dichlorocarbene."),
        Pair("Cannizzaro reaction?", "Aldehyde with no alpha-H + conc. NaOH gives alcohol + carboxylate (disproportionation)."),
        Pair("Aldol condensation needs?", "Aldehyde/ketone with alpha-H + dilute base gives beta-hydroxy carbonyl; heating gives the alpha,beta-unsaturated carbonyl."),
        Pair("Hoffmann bromamide reaction?", "Primary amide + Br2 + NaOH gives primary amine with one carbon less."),
        Pair("Gattermann-Koch reaction?", "Benzene + CO + HCl (AlCl3/CuCl) gives benzaldehyde."),
        Pair("Wolff-Kishner vs Clemmensen?", "Both reduce C=O to CH2. Wolff-Kishner: NH2NH2/KOH, heat (basic). Clemmensen: Zn-Hg/HCl (acidic)."),
        Pair("Hinsberg reagent?", "Benzenesulfonyl chloride; it distinguishes 1, 2 and 3 degree amines."),
        Pair("Carbylamine test?", "Primary amine + CHCl3 + KOH gives a foul-smelling isocyanide."),
        Pair("Huckel rule?", "Aromatic: planar, cyclic, fully conjugated, with (4n+2) pi electrons."),
        Pair("Henderson-Hasselbalch (acidic buffer)?", "pH = pKa + log([salt]/[acid])"),
        Pair("Nernst equation at 298 K?", "E = E0 - (0.0591/n) log Q"),
        Pair("Bohr radius and energy?", "r = 0.529 n^2/Z angstrom;  E = -13.6 Z^2/n^2 eV"),
        Pair("Rydberg formula?", "1/lambda = R Z^2 (1/n1^2 - 1/n2^2), R = 1.097 x 10^7 per metre"),
        Pair("Escape velocity?", "v = sqrt(2GM/R) = sqrt(2gR), about 11.2 km/s for Earth."),
        Pair("Time period of a simple pendulum?", "T = 2 pi sqrt(L/g)"),
        Pair("Lens maker's formula?", "1/f = (n - 1)(1/R1 - 1/R2)"),
        Pair("Integration by parts?", "Integral of u dv = uv - integral of v du (choose u by ILATE)."),
        Pair("Quadratic roots and discriminant?", "x = (-b +- sqrt(b^2 - 4ac)) / 2a; roots are real and distinct if D > 0.")
    )
}

// =====================================================================
//  Full-screen countdown (opened from the dashboard button)
// =====================================================================
class FullScreenActivity : Activity() {

    // Keep same as OverlayService target: 22 Jan 2027, 9:00 AM IST
    private val target: Long = Calendar.getInstance(TimeZone.getTimeZone("Asia/Kolkata")).apply {
        clear()
        set(2027, Calendar.JANUARY, 22, 9, 0, 0)
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
    private lateinit var infoTv: TextView
    private lateinit var quoteTv: TextView
    private var n = 0L

    private val loop = object : Runnable {
        override fun run() {
            val left = target - System.currentTimeMillis()
            if (left <= 0L) {
                daysTv.text = "0"
                timeTv.text = "EXAM DAY"
            } else {
                val s = left / 1000L
                val sep = if (n % 2L == 0L) ":" else " "
                daysTv.text = (s / 86400L).toString()
                timeTv.text = "%02d$sep%02d$sep%02d".format((s % 86400L) / 3600L, (s % 3600L) / 60L, s % 60L)
            }
            val st = Store.studySecs(this@FullScreenActivity, 0)
            infoTv.text = "STUDY %dh%02dm   Q %d/%d".format(
                st / 3600L, (st % 3600L) / 60L,
                Store.pyq(this@FullScreenActivity, 0),
                Store.getInt(this@FullScreenActivity, "pyqTarget", 40)
            )
            if (n % 15L == 0L) quoteTv.text = quotes[((n / 15L) % quotes.size).toInt()]
            n++
            handler.postDelayed(this, 1000L - System.currentTimeMillis() % 1000L)
        }
    }

    private fun tv(size: Float, alpha: Int, spacing: Float, glow: Boolean): TextView {
        val t = TextView(this)
        t.typeface = Typeface.MONOSPACE
        t.setTextColor(Color.argb(alpha, 0, 255, 65))
        t.textSize = size
        t.letterSpacing = spacing
        t.gravity = Gravity.CENTER
        if (glow) t.setShadowLayer(24f, 0f, 0f, green)
        return t
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION

        val label = tv(12f, 150, 0.3f, false)
        label.text = "> T-MINUS  DAYS"
        daysTv = tv(88f, 255, 0.05f, true)
        timeTv = tv(40f, 255, 0.12f, true)
        infoTv = tv(13f, 200, 0.1f, false)
        quoteTv = tv(12f, 140, 0.15f, false)
        val hint = tv(9f, 80, 0.2f, false)
        hint.text = "tap anywhere to close"

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.gravity = Gravity.CENTER
        root.setBackgroundColor(Color.BLACK)
        root.addView(label)
        root.addView(daysTv)
        val l1 = LinearLayout.LayoutParams(-1, -2)
        l1.topMargin = 8
        root.addView(timeTv, l1)
        val l2 = LinearLayout.LayoutParams(-1, -2)
        l2.topMargin = 48
        root.addView(infoTv, l2)
        val l3 = LinearLayout.LayoutParams(-1, -2)
        l3.topMargin = 40
        root.addView(quoteTv, l3)
        val l4 = LinearLayout.LayoutParams(-1, -2)
        l4.topMargin = 140
        root.addView(hint, l4)
        root.setOnClickListener { finish() }
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
