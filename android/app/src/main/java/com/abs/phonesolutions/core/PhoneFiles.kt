package com.abs.phonesolutions.core

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.provider.Settings
import androidx.core.content.ContextCompat
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import java.util.Locale

/** The single storage permission the app needs, and how to ask for it. */
object Perm {
    fun hasFiles(ctx: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(ctx, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }

    fun filesSettingsIntent(ctx: Context): Intent =
        Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:" + ctx.packageName))

    val legacyPermissions = arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE)
}

enum class Category(val label: String, val exts: Set<String>) {
    DOCUMENTS("Documents", setOf("pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "csv", "rtf", "odt", "ods", "odp", "zip", "rar", "7z")),
    PHOTOS("Photos", setOf("jpg", "jpeg", "png", "heic", "heif", "webp", "gif", "bmp", "dng")),
    VIDEOS("Videos", setOf("mp4", "mkv", "mov", "3gp", "webm", "avi", "m4v")),
    AUDIO("Audio", setOf("mp3", "m4a", "aac", "wav", "ogg", "opus", "flac", "amr", "3ga"));

    companion object {
        fun of(name: String): Category? {
            val e = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
            if (e.isEmpty()) return null
            return values().firstOrNull { e in it.exts }
        }
    }
}

data class PhoneFile(val file: File, val rel: String, val size: Long, val mtime: Long, val category: Category)

data class PhoneStatus(
    val usedBytes: Long, val totalBytes: Long,
    val batteryPct: Int, val batteryTempC: Float, val batteryHealth: String, val charging: Boolean,
)

object PhoneFiles {
    val root: File get() = Environment.getExternalStorageDirectory()

    /** Folders that hold system or app-private data, never user files. */
    private val skipTop = setOf("Android", "ABPhoneSolutions", "LOST.DIR")
    /** App backup folders (handled by the cleanup screen, not by backup). */
    private val skipNames = setOf("Databases", "Backups", "cache", "Cache", ".thumbnails")

    /** Finds the user's own files of the chosen kinds. System files and app data are skipped. */
    fun scan(cats: Set<Category>, onCount: (Int) -> Unit = {}): List<PhoneFile> {
        val out = ArrayList<PhoneFile>()
        val r = root
        val starts = ArrayList<File>()
        r.listFiles()?.forEach { if (!(it.isDirectory && it.name in skipTop)) starts.add(it) }
        // WhatsApp and some other apps keep the user's media in Android/media
        File(r, "Android/media").listFiles()?.forEach { starts.add(it) }
        for (s in starts) {
            s.walkTopDown()
                .onEnter { d -> !d.name.startsWith(".") && d.name !in skipNames }
                .forEach { f ->
                    if (f.isFile && !f.name.startsWith(".") && !f.name.endsWith(".abps-part")) {
                        val c = Category.of(f.name)
                        if (c != null && c in cats) {
                            out.add(PhoneFile(f, c.label + "/" + f.relativeTo(r).invariantSeparatorsPath, f.length(), f.lastModified(), c))
                            if (out.size % 250 == 0) onCount(out.size)
                        }
                    }
                }
        }
        onCount(out.size)
        return out
    }

    fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        FileInputStream(f).use { input ->
            val buf = ByteArray(256 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return hex(md.digest())
    }

    fun hex(b: ByteArray): String {
        val chars = "0123456789abcdef"
        val sb = StringBuilder(b.size * 2)
        for (x in b) {
            val v = x.toInt() and 0xff
            sb.append(chars[v shr 4]).append(chars[v and 0x0f])
        }
        return sb.toString()
    }

    fun sizeOf(f: File): Long = if (f.isFile) f.length() else f.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    fun status(ctx: Context): PhoneStatus {
        val st = StatFs(root.path)
        val total = st.totalBytes
        val used = total - st.availableBytes
        val b = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = b?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = b?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val temp = (b?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10f
        val plugged = (b?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
        val health = when (b?.getIntExtra(BatteryManager.EXTRA_HEALTH, 0)) {
            BatteryManager.BATTERY_HEALTH_GOOD -> "Good"
            BatteryManager.BATTERY_HEALTH_OVERHEAT -> "Overheating"
            BatteryManager.BATTERY_HEALTH_DEAD -> "Worn out"
            BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "Over voltage"
            BatteryManager.BATTERY_HEALTH_COLD -> "Too cold"
            else -> "Unknown"
        }
        val pct = if (level >= 0 && scale > 0) level * 100 / scale else -1
        return PhoneStatus(used, total, pct, temp, health, plugged)
    }
}

fun fmtBytes(b: Long): String {
    val v = b.toDouble()
    return when {
        v >= 1e9 -> String.format(Locale.US, "%.1f GB", v / 1e9)
        v >= 1e6 -> String.format(Locale.US, "%.0f MB", v / 1e6)
        v >= 1e3 -> String.format(Locale.US, "%.0f KB", v / 1e3)
        else -> "$b B"
    }
}
