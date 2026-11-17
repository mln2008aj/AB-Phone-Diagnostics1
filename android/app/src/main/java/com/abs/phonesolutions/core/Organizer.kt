package com.abs.phonesolutions.core

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile

/** Reads the text of a document on the phone. Nothing leaves the phone. */
object TextExtract {
    @Volatile private var pdfReady = false
    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }
    private const val LIMIT = 6000

    data class Result(val text: String, val fromImage: Boolean)

    fun extract(ctx: Context, f: File): Result {
        val ext = f.extension.lowercase(Locale.ROOT)
        return try {
            when (ext) {
                "pdf" -> pdf(ctx, f)
                "docx" -> Result(zipText(f, listOf("word/document.xml")), false)
                "xlsx" -> Result(zipText(f, listOf("xl/sharedStrings.xml")), false)
                "pptx" -> Result(zipText(f, (1..4).map { "ppt/slides/slide$it.xml" }), false)
                "odt", "ods", "odp" -> Result(zipText(f, listOf("content.xml")), false)
                "txt", "csv", "rtf" -> Result(readHead(f, 16000), false)
                "doc", "xls", "ppt" -> Result(legacyText(f), false)
                "jpg", "jpeg", "png" -> Result(ocr(InputImage.fromFilePath(ctx, Uri.fromFile(f))), true)
                else -> Result("", false)
            }.let { Result(clean(it.text), it.fromImage) }
        } catch (e: Throwable) {
            // A damaged or password-protected file: leave it Unsorted, never crash.
            Result("", false)
        }
    }

    private fun clean(s: String) = s.replace(Regex("\\s+"), " ").trim().take(LIMIT)

    private fun pdf(ctx: Context, f: File): Result {
        if (!pdfReady) {
            PDFBoxResourceLoader.init(ctx.applicationContext)
            pdfReady = true
        }
        var text = ""
        try {
            PDDocument.load(f).use { doc ->
                val s = PDFTextStripper()
                s.startPage = 1
                s.endPage = minOf(3, doc.numberOfPages)
                text = s.getText(doc)
            }
        } catch (e: Throwable) {
        }
        if (text.trim().length >= 40) return Result(text, false)
        // Probably a scanned letter: read the first page as an image.
        return Result(ocrPdfFirstPage(f), true)
    }

    private fun ocrPdfFirstPage(f: File): String {
        val pfd = ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
        try {
            val r = PdfRenderer(pfd)
            try {
                if (r.pageCount == 0) return ""
                val page = r.openPage(0)
                try {
                    val scale = 1800f / maxOf(page.width, page.height)
                    val w = maxOf(1, (page.width * scale).toInt())
                    val h = maxOf(1, (page.height * scale).toInt())
                    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    bmp.eraseColor(Color.WHITE)
                    page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    val t = ocr(InputImage.fromBitmap(bmp, 0))
                    bmp.recycle()
                    return t
                } finally {
                    page.close()
                }
            } finally {
                r.close()
            }
        } finally {
            pfd.close()
        }
    }

    private fun ocr(img: InputImage): String = try {
        Tasks.await(recognizer.process(img), 30, TimeUnit.SECONDS).text
    } catch (e: Exception) {
        ""
    }

    private fun zipText(f: File, names: List<String>): String {
        val sb = StringBuilder()
        ZipFile(f).use { z ->
            for (n in names) {
                val e = z.getEntry(n) ?: continue
                z.getInputStream(e).bufferedReader().use { r ->
                    val buf = CharArray(300_000)
                    val k = r.read(buf)
                    if (k > 0) sb.append(String(buf, 0, k)).append(' ')
                }
            }
        }
        return sb.toString().replace(Regex("<[^>]+>"), " ").replace("&amp;", "&")
    }

    private fun readHead(f: File, n: Int): String = FileInputStream(f).use { input ->
        val b = ByteArray(n)
        val k = input.read(b)
        if (k > 0) String(b, 0, k) else ""
    }

    /** Old Word/Excel/PowerPoint files: pick out readable text runs. */
    private fun legacyText(f: File): String {
        val b = FileInputStream(f).use { input ->
            val buf = ByteArray(400_000)
            val k = input.read(buf)
            if (k > 0) buf.copyOf(k) else ByteArray(0)
        }
        val sb = StringBuilder()
        var run = StringBuilder()
        for (x in b) {
            val v = x.toInt() and 0xff
            if (v == 0) continue
            if (v in 32..126) run.append(v.toChar()) else {
                if (run.length >= 4) sb.append(run).append(' ')
                run = StringBuilder()
            }
        }
        if (run.length >= 4) sb.append(run)
        return sb.toString()
    }
}

/** Decides a folder from the words in the file name and its text. */
object Classifier {
    const val UNSORTED = "Unsorted"
    const val DUPLICATES = "Duplicates"

    private class Rule(val folder: String, val words: List<Pair<String, Int>>)

    private val rules = listOf(
        Rule("Office letters & circulars", listOf(
            "letter no" to 3, "ref no" to 2, "sub:" to 3, "subject:" to 2, "circular" to 3, "office order" to 3,
            "memorandum" to 3, "yours faithfully" to 3, "yours sincerely" to 2, "copy to" to 2, "for information" to 1,
            "notification" to 2, "dated" to 1, "railway" to 1, "division" to 1, "department" to 1, "minutes of" to 2)),
        Rule("Tenders & contracts", listOf(
            "tender" to 3, "notice inviting" to 3, "nit" to 2, "bid" to 2, "boq" to 3, "bill of quantities" to 3,
            "letter of acceptance" to 3, "loa" to 2, "contract" to 2, "agreement" to 2, "earnest money" to 3, "emd" to 2,
            "work order" to 3, "purchase order" to 3, "quotation" to 2, "ireps" to 3, "e-procurement" to 3, "estimate" to 1)),
        Rule("Bills & receipts", listOf(
            "invoice" to 3, "bill no" to 3, "amount due" to 3, "due date" to 2, "receipt" to 3, "gstin" to 3, "gst" to 1,
            "total amount" to 2, "payment" to 1, "upi" to 2, "transaction id" to 3, "txn" to 2, "paid" to 1, "₹" to 1, "inr" to 1)),
        Rule("Tickets & travel", listOf(
            "pnr" to 3, "ticket" to 2, "boarding" to 2, "train no" to 3, "flight" to 3, "departure" to 2, "arrival" to 1,
            "booking id" to 3, "check-in" to 2, "itinerary" to 3, "irctc" to 3, "passenger" to 2, "e-ticket" to 3, "hotel" to 1)),
        Rule("Manuals & study material", listOf(
            "manual" to 3, "procedure" to 2, "specification" to 3, "chapter" to 2, "section" to 1, "drawing" to 2,
            "training" to 2, "syllabus" to 3, "notes" to 1, "guidelines" to 2, "handbook" to 3, "standard" to 1,
            "rdso" to 2, "maintenance" to 2, "instructions" to 2, "contents" to 1, "introduction" to 1)),
        Rule("Certificates & personal", listOf(
            "certificate" to 3, "certify" to 3, "this is to certify" to 3, "experience" to 1, "aadhaar" to 3,
            "passport" to 3, "marksheet" to 3, "mark sheet" to 3, "degree" to 2, "date of birth" to 2, "resume" to 3,
            "curriculum vitae" to 3, "bio-data" to 3, "biodata" to 3)),
    )

    val FOLDERS: List<String> = rules.map { it.folder }

    private val cache = HashMap<String, Regex>()

    private fun hits(text: String, w: String): Int {
        val re = synchronized(cache) {
            cache.getOrPut(w) {
                if (w.all { it.isLetter() || it == ' ' }) Regex("\\b" + Regex.escape(w) + "\\b") else Regex(Regex.escape(w))
            }
        }
        return re.findAll(text).take(3).count()
    }

    /** Returns (folder, reason shown to the user). */
    fun classify(fileName: String, text: String, fromImage: Boolean): Pair<String, String> {
        val t = text.lowercase(Locale.ROOT)
        val n = fileName.substringBeforeLast('.').lowercase(Locale.ROOT).replace('_', ' ').replace('-', ' ')
        var best = UNSORTED
        var bestScore = 0
        var bestHits = emptyList<String>()
        for (r in rules) {
            var s = 0
            val found = ArrayList<String>()
            for ((w, weight) in r.words) {
                val c = hits(t, w) + 2 * minOf(hits(n, w), 1)
                if (c > 0) {
                    s += c * weight
                    found.add(w)
                }
            }
            if (s > bestScore) {
                bestScore = s
                best = r.folder
                bestHits = found
            }
        }
        val how = if (fromImage) " (text read from the image)" else ""
        return if (bestScore >= 4) {
            best to ("Found " + bestHits.take(4).joinToString(", ") { "\"$it\"" } + how)
        } else {
            UNSORTED to (if (t.isBlank()) "No readable text" else "No clear subject found$how")
        }
    }
}

data class OrgSource(val name: String, val dir: File, val count: Int)
data class Planned(val file: File, val source: String, val folder: String, val reason: String, val selected: Boolean = true)
data class OrgLog(val file: File, val created: Long, val count: Int, val move: Boolean)

object Organizer {
    val target: File get() = File(PhoneFiles.root, "Documents/Organized")
    private val logDir: File get() = File(PhoneFiles.root, "ABPhoneSolutions/organize-logs")
    private val exts = setOf("pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "csv", "rtf", "odt", "ods", "odp", "jpg", "jpeg", "png")
    private const val KEEP_LOG_DAYS = 30L

    private fun candidates(): List<Pair<String, File>> {
        val r = PhoneFiles.root
        return listOf(
            "WhatsApp" to File(r, "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Documents"),
            "WhatsApp" to File(r, "WhatsApp/Media/WhatsApp Documents"),
            "WhatsApp Business" to File(r, "Android/media/com.whatsapp.w4b/WhatsApp Business/Media/WhatsApp Business Documents"),
            "Telegram" to File(r, "Download/Telegram"),
            "Telegram" to File(r, "Telegram/Telegram Documents"),
            "Downloads" to File(r, "Download"),
        )
    }

    private fun filesIn(name: String, dir: File): List<File> {
        val tg = File(PhoneFiles.root, "Download/Telegram").absolutePath
        return dir.walkTopDown()
            .onEnter { d -> !d.name.startsWith(".") && !(name == "Downloads" && d.absolutePath == tg) }
            .filter { it.isFile && !it.name.startsWith(".") && it.extension.lowercase(Locale.ROOT) in exts }
            .toList()
    }

    fun sources(): List<OrgSource> = candidates().filter { it.second.isDirectory }
        .map { OrgSource(it.first, it.second, filesIn(it.first, it.second).size) }

    fun allFiles(): List<Pair<String, File>> {
        val seen = HashSet<String>()
        val out = ArrayList<Pair<String, File>>()
        for ((name, dir) in candidates()) {
            if (!dir.isDirectory) continue
            for (f in filesIn(name, dir)) if (seen.add(f.absolutePath)) out.add(name to f)
        }
        return out
    }

    private fun freeName(dir: File, name: String): File {
        var f = File(dir, name)
        var i = 1
        val base = name.substringBeforeLast('.')
        val ext = if (name.contains('.')) "." + name.substringAfterLast('.') else ""
        while (f.exists()) {
            f = File(dir, "$base ($i)$ext")
            i++
        }
        return f
    }

    private fun moveFile(src: File, dst: File): Boolean {
        dst.parentFile?.mkdirs()
        if (src.renameTo(dst)) return true
        src.copyTo(dst, overwrite = false)
        if (dst.length() == src.length()) {
            src.delete()
            return true
        }
        dst.delete()
        return false
    }

    /** Moves or copies the approved files and keeps a log so it can be undone. */
    fun apply(items: List<Planned>, move: Boolean, onProgress: (Int) -> Unit): Pair<Int, List<String>> {
        val log = JSONArray()
        val failed = ArrayList<String>()
        var done = 0
        for ((i, p) in items.withIndex()) {
            onProgress(i)
            try {
                val dst = freeName(File(target, p.folder), p.file.name)
                dst.parentFile?.mkdirs()
                val ok = if (move) moveFile(p.file, dst) else {
                    p.file.copyTo(dst, overwrite = false)
                    dst.length() == p.file.length()
                }
                if (ok) {
                    log.put(JSONObject().put("from", p.file.absolutePath).put("to", dst.absolutePath).put("move", move))
                    done++
                } else failed.add(p.file.name)
            } catch (e: Exception) {
                failed.add(p.file.name + ": " + (e.message ?: "error"))
            }
        }
        if (done > 0) {
            logDir.mkdirs()
            File(logDir, "organize-${System.currentTimeMillis()}.json")
                .writeText(JSONObject().put("created", System.currentTimeMillis()).put("move", move).put("items", log).toString())
        }
        return done to failed
    }

    fun latestLog(): OrgLog? {
        val f = logDir.listFiles { x -> x.name.endsWith(".json") }?.maxByOrNull { it.lastModified() } ?: return null
        return try {
            val j = JSONObject(f.readText())
            OrgLog(f, j.optLong("created"), j.getJSONArray("items").length(), j.optBoolean("move"))
        } catch (e: Exception) {
            null
        }
    }

    /** Puts every file back where it was (moves) or removes the copies (copies). */
    fun undo(log: OrgLog): Int {
        val items = JSONObject(log.file.readText()).getJSONArray("items")
        var n = 0
        for (k in items.length() - 1 downTo 0) {
            val e = items.getJSONObject(k)
            val from = File(e.getString("from"))
            val to = File(e.getString("to"))
            if (!to.exists()) continue
            try {
                if (e.optBoolean("move")) {
                    if (!from.exists() && moveFile(to, from)) n++
                } else if (to.delete()) n++
            } catch (x: Exception) {
            }
        }
        log.file.delete()
        return n
    }

    fun purgeOldLogs() {
        val limit = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(KEEP_LOG_DAYS)
        logDir.listFiles()?.forEach { if (it.lastModified() < limit) it.delete() }
    }
}
