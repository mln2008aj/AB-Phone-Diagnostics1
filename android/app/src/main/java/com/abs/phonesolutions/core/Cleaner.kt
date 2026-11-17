package com.abs.phonesolutions.core

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

enum class Verdict { SAFE, ASK, KEEP }

data class CleanItem(
    val id: String, val title: String, val app: String, val detail: String, val why: String,
    val files: List<File>, val bytes: Long, val verdict: Verdict,
)

data class Hold(val dir: File, val created: Long, val count: Int, val bytes: Long)

/**
 * Finds outdated APP backups (never the user's documents or photos) that Android lets
 * this app see, explains each one, and only touches what the user selects.
 */
object Cleaner {
    const val HOLD_DAYS = 7L
    val holdRoot: File get() = File(PhoneFiles.root, "ABPhoneSolutions/.cleared")
    private val day = SimpleDateFormat("d MMM yyyy", Locale.getDefault())

    fun scan(ctx: Context): List<CleanItem> = whatsapp() + installers(ctx) + migrationFolders()

    // ---- WhatsApp keeps a daily copy of the chat database for about a week; only the newest is needed.
    private fun whatsapp(): List<CleanItem> {
        val r = PhoneFiles.root
        val dirs = listOf(
            "WhatsApp" to File(r, "Android/media/com.whatsapp/WhatsApp/Databases"),
            "WhatsApp" to File(r, "WhatsApp/Databases"),
            "WhatsApp Business" to File(r, "Android/media/com.whatsapp.w4b/WhatsApp Business/Databases"),
            "WhatsApp Business" to File(r, "WhatsApp Business/Databases"),
        )
        val latestRe = Regex("msgstore\\.db\\.crypt\\d+")
        val datedRe = Regex("msgstore-(\\d{4}-\\d{2}-\\d{2})\\.\\d+\\.db\\.crypt\\d+")
        val out = ArrayList<CleanItem>()
        for ((label, dir) in dirs) {
            val files = dir.listFiles()?.filter { it.isFile } ?: continue
            val latest = files.filter { latestRe.matches(it.name) }
            val dated = files.filter { datedRe.matches(it.name) }.sortedByDescending { it.name }
            val keep = if (latest.isNotEmpty()) latest else dated.take(1)
            val old = dated.filter { it !in keep }
            if (keep.isNotEmpty()) {
                out.add(CleanItem("keep:" + dir.path, "Latest $label chat backup", label,
                    "Made " + day.format(Date(keep.maxOf { it.lastModified() })),
                    "Your newest chat backup. Needed to restore chats on a new phone.",
                    keep, keep.sumOf { it.length() }, Verdict.KEEP))
            }
            if (old.isNotEmpty()) {
                val dates = old.mapNotNull { datedRe.find(it.name)?.groupValues?.get(1) }.sorted()
                out.add(CleanItem("old:" + dir.path, "Old $label chat backups", label,
                    "${old.size} older copies" + (if (dates.isNotEmpty()) ", ${dates.first()} to ${dates.last()}" else ""),
                    "Replaced by your newest chat backup, which is kept.",
                    old, old.sumOf { it.length() }, Verdict.SAFE))
            }
        }
        return out
    }

    // ---- Saved installer files (.apk)
    @Suppress("DEPRECATION")
    private fun installers(ctx: Context): List<CleanItem> {
        val r = PhoneFiles.root
        val apks = ArrayList<File>()
        r.listFiles()?.filter { it.isFile && it.extension.equals("apk", true) }?.let { apks.addAll(it) }
        for (sub in listOf("Download", "Documents", "Bluetooth", "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Documents")) {
            File(r, sub).walkTopDown().maxDepth(4).onEnter { !it.name.startsWith(".") }
                .filter { it.isFile && it.extension.equals("apk", true) }.forEach { apks.add(it) }
        }
        val pm = ctx.packageManager
        val safe = ArrayList<File>()
        val out = ArrayList<CleanItem>()
        for (f in apks.distinctBy { it.absolutePath }) {
            val info = try { pm.getPackageArchiveInfo(f.path, 0) } catch (e: Exception) { null } ?: continue
            val pkg = info.packageName ?: continue
            val apkVer = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
            val installedVer: Long? = try {
                val p = pm.getPackageInfo(pkg, 0)
                if (Build.VERSION.SDK_INT >= 28) p.longVersionCode else p.versionCode.toLong()
            } catch (e: PackageManager.NameNotFoundException) {
                null
            }
            val label = try {
                info.applicationInfo?.let { ai ->
                    ai.sourceDir = f.path
                    ai.publicSourceDir = f.path
                    pm.getApplicationLabel(ai).toString()
                } ?: f.name
            } catch (e: Exception) {
                f.name
            }
            if (installedVer != null && installedVer >= apkVer) {
                safe.add(f)
            } else {
                out.add(CleanItem("apk:" + f.path, "Installer: $label", f.name, fmtBytes(f.length()) + " · " + day.format(Date(f.lastModified())),
                    if (installedVer == null) "This app is not installed. Keep it only if you plan to install it."
                    else "Newer than the installed version. Keep it if you want to install this update.",
                    listOf(f), f.length(), Verdict.ASK))
            }
        }
        if (safe.isNotEmpty()) {
            out.add(0, CleanItem("apks:installed", "Saved app installers", "${safe.size} files",
                "In Download and other folders",
                "These apps are already installed in the same or a newer version.",
                safe, safe.sumOf { it.length() }, Verdict.SAFE))
        }
        return out
    }

    // ---- Folders left behind by phone-transfer and backup tools
    private fun migrationFolders(): List<CleanItem> {
        val r = PhoneFiles.root
        val names = listOf("SmartSwitch", "Samsung/SmartSwitch", "MIUI/backup", "Huawei/Backup", "HuaweiBackup",
            "TitaniumBackup", "ColorOS/Backup", "Backup/Phone Clone", "CloneData", "Xender/.backup")
        val out = ArrayList<CleanItem>()
        for (n in names) {
            val d = File(r, n)
            if (!d.isDirectory) continue
            val files = d.walkTopDown().filter { it.isFile }.toList()
            val bytes = files.sumOf { it.length() }
            if (bytes == 0L) continue
            val newest = files.maxOf { it.lastModified() }
            val old = System.currentTimeMillis() - newest > TimeUnit.DAYS.toMillis(180)
            out.add(CleanItem("dir:" + d.path, "Old phone transfer or backup folder", n,
                "Last changed " + day.format(Date(newest)) + " · ${files.size} files",
                if (old) "Not changed for over 6 months. Usually left over from moving to this phone. Check that this phone has everything first."
                else "Recently changed. Keep it unless you are sure you don't need it.",
                listOf(d), bytes, Verdict.ASK))
        }
        return out
    }

    // ---- Clearing, with a 7-day hold so mistakes can be undone

    /** Moves the chosen backups into a holding folder. They are deleted for good after 7 days. */
    fun hold(items: List<CleanItem>): Pair<Int, Long> {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val dir = File(holdRoot, stamp)
        dir.mkdirs()
        val entries = JSONArray()
        var count = 0
        var bytes = 0L
        for (item in items) for (f in item.files) {
            val dst = File(dir, f.relativeTo(PhoneFiles.root).path)
            dst.parentFile?.mkdirs()
            val size = PhoneFiles.sizeOf(f)
            if (f.renameTo(dst)) {
                entries.put(JSONObject().put("from", f.absolutePath).put("to", dst.absolutePath))
                count++
                bytes += size
            }
        }
        File(dir, "manifest.json").writeText(JSONObject().put("created", System.currentTimeMillis()).put("bytes", bytes).put("items", entries).toString())
        return count to bytes
    }

    fun deleteNow(items: List<CleanItem>): Pair<Int, Long> {
        var count = 0
        var bytes = 0L
        for (item in items) for (f in item.files) {
            val size = PhoneFiles.sizeOf(f)
            if (if (f.isDirectory) f.deleteRecursively() else f.delete()) {
                count++
                bytes += size
            }
        }
        return count to bytes
    }

    fun holds(): List<Hold> = holdRoot.listFiles()?.filter { it.isDirectory }?.mapNotNull { d ->
        try {
            val j = JSONObject(File(d, "manifest.json").readText())
            Hold(d, j.optLong("created"), j.getJSONArray("items").length(), j.optLong("bytes"))
        } catch (e: Exception) {
            null
        }
    }?.sortedByDescending { it.created } ?: emptyList()

    /** Puts everything in this hold back where it was. */
    fun restore(h: Hold): Int {
        val items = JSONObject(File(h.dir, "manifest.json").readText()).getJSONArray("items")
        var n = 0
        for (k in 0 until items.length()) {
            val e = items.getJSONObject(k)
            val from = File(e.getString("from"))
            val to = File(e.getString("to"))
            if (to.exists() && !from.exists()) {
                from.parentFile?.mkdirs()
                if (to.renameTo(from)) n++
            }
        }
        h.dir.deleteRecursively()
        return n
    }

    fun deleteHold(h: Hold) {
        h.dir.deleteRecursively()
    }

    /** Runs when the app opens: removes holds older than 7 days, as agreed when they were cleared. */
    fun purgeOld() {
        val limit = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(HOLD_DAYS)
        holds().filter { it.created in 1 until limit }.forEach { it.dir.deleteRecursively() }
    }

    fun deleteDate(h: Hold): String = day.format(Date(h.created + TimeUnit.DAYS.toMillis(HOLD_DAYS)))
    fun date(ms: Long): String = day.format(Date(ms))
}
