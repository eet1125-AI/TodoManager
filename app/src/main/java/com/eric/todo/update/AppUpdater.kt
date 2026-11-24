package com.eric.todo.update

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * One-button updates. Every build of this app is published (by GitHub) to the public
 * "armsco-updates" repository, which holds only finished app files. When the app opens,
 * it checks there; if a newer version exists it asks "Update now?", downloads it and
 * hands it to Android's installer (one more tap on Android's own Update screen).
 */
object AppUpdater {

    private const val APP_ID = "todo"
    private const val BASE = "https://github.com/eet1125-AI/armsco-updates/releases/download/$APP_ID/"
    private const val PREFS = "app_updater"

    private data class Info(val code: Long, val name: String, val notes: String, val apk: String)

    /** Call from MainActivity.onCreate. Quiet if offline or up to date; asks at most once a day per version. */
    fun checkOnStart(activity: Activity) = check(activity, manual = false)

    /** "Check for updates" button: always reports the result. */
    fun checkNow(activity: Activity) = check(activity, manual = true)

    private fun check(activity: Activity, manual: Boolean) {
        val prefs = activity.getSharedPreferences(PREFS, Activity.MODE_PRIVATE)
        Thread {
            val info = runCatching { fetchInfo() }.getOrNull()
            val current = currentCode(activity)
            activity.runOnUiThread {
                if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                when {
                    info == null -> if (manual) message(activity, "Couldn't check for updates", "Check your internet connection and try again.")
                    info.code <= current -> if (manual) message(activity, "You're up to date", "You have the newest version (${versionName(activity)}).")
                    else -> {
                        val snoozedUntil = prefs.getLong("snooze_${info.code}", 0)
                        if (!manual && System.currentTimeMillis() < snoozedUntil) return@runOnUiThread
                        AlertDialog.Builder(activity)
                            .setTitle("Update available")
                            .setMessage(
                                "Version ${info.name} is ready (you have ${versionName(activity)})." +
                                    (if (info.notes.isNotBlank()) "\n\n${info.notes}" else "") +
                                    "\n\nYour saved work stays on this device."
                            )
                            .setPositiveButton("Update now") { _, _ -> download(activity, info) }
                            .setNegativeButton("Later") { _, _ ->
                                prefs.edit().putLong("snooze_${info.code}", System.currentTimeMillis() + 24 * 3600_000L).apply()
                            }
                            .show()
                    }
                }
            }
        }.start()
    }

    private fun fetchInfo(): Info {
        val o = JSONObject(httpGet(BASE + "version.json").decodeToString())
        return Info(
            code = o.getLong("versionCode"),
            name = o.optString("versionName"),
            notes = o.optString("notes"),
            apk = o.getString("apk"),
        )
    }

    private fun download(activity: Activity, info: Info) {
        val bar = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100; isIndeterminate = true }
        val label = TextView(activity).apply { text = "Starting…"; gravity = Gravity.CENTER }
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (24 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, 0)
            addView(bar); addView(label)
        }
        val cancel = java.util.concurrent.atomic.AtomicBoolean(false)
        val dialog = AlertDialog.Builder(activity)
            .setTitle("Downloading update")
            .setView(box)
            .setCancelable(false)
            .setNegativeButton("Cancel") { _, _ -> cancel.set(true) }
            .show()

        Thread {
            val dir = File(activity.cacheDir, "updates").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
            val out = File(dir, info.apk)
            val ok = runCatching {
                openFollowingRedirects(BASE + info.apk).let { conn ->
                    val total = conn.contentLengthLong
                    conn.inputStream.use { input ->
                        out.outputStream().use { output ->
                            val buf = ByteArray(64 * 1024)
                            var done = 0L
                            while (!cancel.get()) {
                                val n = input.read(buf)
                                if (n < 0) break
                                output.write(buf, 0, n)
                                done += n
                                if (total > 0) activity.runOnUiThread {
                                    bar.isIndeterminate = false
                                    bar.progress = (done * 100 / total).toInt()
                                    label.text = "${done / 1_000_000} of ${total / 1_000_000 + 1} MB"
                                }
                            }
                        }
                    }
                }
                !cancel.get() && out.length() > 0
            }.getOrDefault(false)
            activity.runOnUiThread {
                runCatching { dialog.dismiss() }
                when {
                    cancel.get() -> out.delete()
                    !ok -> message(activity, "Download failed", "Check your internet connection and try again.")
                    else -> install(activity, out)
                }
            }
        }.start()
    }

    private fun install(activity: Activity, apk: File) {
        if (!activity.packageManager.canRequestPackageInstalls()) {
            AlertDialog.Builder(activity)
                .setTitle("One-time permission")
                .setMessage("Android needs your OK for this app to install its own updates.\n\nTap Open settings, turn on \"Allow from this source\", come back, and tap Update now again.")
                .setPositiveButton("Open settings") { _, _ ->
                    activity.startActivity(
                        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}"))
                    )
                }
                .setNegativeButton("Cancel", null)
                .show()
            return
        }
        val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.fileprovider", apk)
        activity.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    // ---------------------------------------------------------------------

    private fun currentCode(activity: Activity): Long =
        activity.packageManager.getPackageInfo(activity.packageName, 0).longVersionCode

    private fun versionName(activity: Activity): String =
        activity.packageManager.getPackageInfo(activity.packageName, 0).versionName ?: "?"

    private fun message(activity: Activity, title: String, text: String) {
        AlertDialog.Builder(activity).setTitle(title).setMessage(text).setPositiveButton("OK", null).show()
    }

    private fun httpGet(url: String): ByteArray = openFollowingRedirects(url).inputStream.use { it.readBytes() }

    /** GitHub sends downloads to another address; follow it (and add a cache-buster so the newest file is read). */
    private fun openFollowingRedirects(start: String): HttpURLConnection {
        var url = if ('?' in start) "$start&t=${System.currentTimeMillis()}" else "$start?t=${System.currentTimeMillis()}"
        repeat(6) {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 15_000
                readTimeout = 60_000
                setRequestProperty("User-Agent", "armsco-app-updater")
            }
            val code = conn.responseCode
            if (code in 300..399) {
                url = conn.getHeaderField("Location") ?: error("Bad redirect")
                conn.disconnect()
            } else if (code in 200..299) {
                return conn
            } else {
                conn.disconnect()
                error("HTTP $code")
            }
        }
        error("Too many redirects")
    }
}
