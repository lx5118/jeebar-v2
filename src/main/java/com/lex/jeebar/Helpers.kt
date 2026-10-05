package com.lex.jeebar

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.widget.VideoView
import java.util.Calendar

// Motivation lines + milestone messages for the bar
object Texts {
    val quotes = listOf(
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

    fun milestone(days: Long): String? {
        if (days <= 7L) return "last week. sab kuch jhok de."
        if (days <= 30L) return "30 din se kam. all in."
        if (days <= 50L) return "50 din se kam. pace badhao."
        if (days <= 100L) return "100 din se kam. ab serious."
        return null
    }
}

// Locks: focus, night, scheduled, YouTube limit
object Rules {
    val ytPkgs = setOf("com.google.android.youtube", "app.revanced.android.youtube")
    private val gameCache = HashMap<String, Boolean>()

    fun isGame(c: Context, pkg: String): Boolean {
        val cached = gameCache[pkg]
        if (cached != null) return cached
        var result = false
        try {
            val ai = c.packageManager.getApplicationInfo(pkg, 0)
            @Suppress("DEPRECATION")
            val byFlag = (ai.flags and ApplicationInfo.FLAG_IS_GAME) != 0
            result = ai.category == ApplicationInfo.CATEGORY_GAME || byFlag
        } catch (e: Exception) {
        }
        gameCache[pkg] = result
        return result
    }

    fun blockReason(c: Context, pkg: String): String? {
        if (Store.focusPhase(c) == "focus") return "Focus mode \uD83D\uDD12 padhai chalu hai"
        val h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        if (Store.getBool(c, "nightOn", true) && (h >= 23 || h < 5)) {
            return "Night lock \uD83D\uDE34 so jao"
        }
        if (Store.getBool(c, "lockOn", false)) {
            val s = Store.getInt(c, "lockStart", 18)
            val e = Store.getInt(c, "lockEnd", 22)
            val inside = if (s <= e) (h >= s && h < e) else (h >= s || h < e)
            if (inside) return "Study lock \uD83D\uDD12 $s:00 - $e:00"
        }
        if (pkg in ytPkgs) {
            val lim = Store.getInt(c, "ytLimit", 30)
            if (lim > 0 && Store.useSecs(c, pkg, 0) >= lim * 60L) {
                return "YouTube limit khatam \uD83D\uDD12"
            }
        }
        return null
    }
}

// Notifications: revision due, weekly mock, focus/break alerts
object Reminders {
    fun alert(c: Context, id: Int, title: String, text: String) {
        val nm = c.getSystemService(NotificationManager::class.java)
        val open = PendingIntent.getActivity(
            c, 2, Intent(c, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val nb = Notification.Builder(c, "jeealerts")
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .setAutoCancel(true)
        nm.notify(id, nb.build())
    }

    fun check(c: Context, now: Long) {
        val revs = Store.revs(c)
        var changed = false
        for (r in revs) {
            if (Store.isDue(r) && r.notified < r.stage) {
                alert(c, 1000 + (r.id % 100000L).toInt(), "Revise: " + r.topic, "review " + (r.stage + 1) + " of 4 due")
                r.notified = r.stage
                changed = true
            }
        }
        if (changed) Store.saveRevs(c, revs)

        val cal = Calendar.getInstance()
        if (cal.get(Calendar.DAY_OF_WEEK) == Calendar.SUNDAY && cal.get(Calendar.HOUR_OF_DAY) >= 9) {
            val today = Store.dayKey(0)
            if (Store.getStr(c, "mockRemind") != today) {
                var last = 0L
                for (m in Store.mocks(c)) {
                    if (m.first > last) last = m.first
                }
                if (now - last > 6L * Store.DAY) {
                    alert(c, 2000, "Weekly mock test", "Is hafte ka mock diya? Score log karo.")
                }
                Store.putStr(c, "mockRemind", today)
            }
        }
    }
}

// Meme videos: built-in (res/raw/memeN.mp4) + the ones added from the dashboard
object Media {
    fun sources(c: Context): List<String> {
        val out = ArrayList<String>()
        for (i in 1..9) {
            val id = c.resources.getIdentifier("meme$i", "raw", c.packageName)
            if (id != 0) out.add("android.resource://" + c.packageName + "/" + id)
        }
        val files = Store.memeDir(c).listFiles()
        if (files != null) {
            for (f in files) out.add(Uri.fromFile(f).toString())
        }
        return out
    }

    fun video(c: Context, uri: String): VideoView {
        val vv = VideoView(c)
        vv.setVideoURI(Uri.parse(uri))
        vv.setOnPreparedListener { mp ->
            mp.isLooping = true
            mp.setVolume(1f, 1f)
        }
        vv.start()
        return vv
    }
}
