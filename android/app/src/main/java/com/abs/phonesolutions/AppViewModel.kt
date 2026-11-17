package com.abs.phonesolutions

import android.app.Application
import android.media.MediaScannerConnection
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.abs.phonesolutions.core.Category
import com.abs.phonesolutions.core.Classifier
import com.abs.phonesolutions.core.CleanItem
import com.abs.phonesolutions.core.Cleaner
import com.abs.phonesolutions.core.FoundPc
import com.abs.phonesolutions.core.Hold
import com.abs.phonesolutions.core.OrgLog
import com.abs.phonesolutions.core.OrgSource
import com.abs.phonesolutions.core.Organizer
import com.abs.phonesolutions.core.PcLink
import com.abs.phonesolutions.core.Perm
import com.abs.phonesolutions.core.PhoneFile
import com.abs.phonesolutions.core.PhoneFiles
import com.abs.phonesolutions.core.PhoneStatus
import com.abs.phonesolutions.core.Planned
import com.abs.phonesolutions.core.TextExtract
import com.abs.phonesolutions.core.UploadResult
import com.abs.phonesolutions.core.Verdict
import com.abs.phonesolutions.core.fmtBytes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

enum class BPhase { IDLE, SCANNING, CHECKING, SENDING, VERIFYING, REVIEW_DELETE, DELETING, DONE }
enum class OPhase { IDLE, READING, REVIEW, APPLYING, DONE }
enum class CPhase { IDLE, SCANNING, REVIEW, WORKING, DONE }

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val ctx: Application get() = getApplication()

    // ------------------------------------------------------------------ general
    var hasFiles by mutableStateOf(false)
        private set
    var status by mutableStateOf<PhoneStatus?>(null)
        private set

    fun refreshPermissions() {
        hasFiles = Perm.hasFiles(ctx)
        viewModelScope.launch(Dispatchers.IO) {
            val s = try { PhoneFiles.status(ctx) } catch (e: Exception) { null }
            if (hasFiles) {
                try {
                    Cleaner.purgeOld()
                    Organizer.purgeOldLogs()
                } catch (e: Exception) {
                }
                val h = try { Cleaner.holds() } catch (e: Exception) { emptyList() }
                val log = try { Organizer.latestLog() } catch (e: Exception) { null }
                withContext(Dispatchers.Main) {
                    holds = h
                    lastOrgLog = log
                }
            }
            withContext(Dispatchers.Main) { status = s }
        }
        if (paired) checkReachable()
    }

    private fun scanMedia(paths: List<String>) {
        if (paths.isEmpty()) return
        try {
            MediaScannerConnection.scanFile(ctx, paths.take(5000).toTypedArray(), null, null)
        } catch (e: Exception) {
        }
    }

    // ------------------------------------------------------------------ PC link
    val link = PcLink(app)
    var paired by mutableStateOf(link.token.isNotEmpty())
        private set
    var pcName by mutableStateOf(link.pcName)
        private set
    var via by mutableStateOf(link.via)
        private set
    var reachable by mutableStateOf<Boolean?>(null)
        private set
    var linkBusy by mutableStateOf(false)
        private set
    var linkMsg by mutableStateOf<String?>(null)
    var found by mutableStateOf<List<FoundPc>>(emptyList())
        private set
    var usbSeen by mutableStateOf<String?>(null)
        private set

    fun checkReachable() {
        viewModelScope.launch(Dispatchers.IO) {
            val ok = link.reachable()
            withContext(Dispatchers.Main) { reachable = ok }
        }
    }

    fun searchWifi() {
        linkBusy = true
        linkMsg = null
        viewModelScope.launch(Dispatchers.IO) {
            val list = link.discover()
            withContext(Dispatchers.Main) {
                found = list
                linkBusy = false
                if (list.isEmpty()) linkMsg = "No PC found. Check that the PC program is open and both are on the same Wi-Fi, or type the PC's address."
            }
        }
    }

    fun checkUsb() {
        linkBusy = true
        linkMsg = null
        viewModelScope.launch(Dispatchers.IO) {
            val name = link.probe(PcLink.USB_HOST)
            withContext(Dispatchers.Main) {
                usbSeen = name
                linkBusy = false
                if (name == null) linkMsg = "No PC on the cable yet. Check USB debugging is on, the cable is plugged in, and you tapped Allow."
            }
        }
    }

    fun pair(host: String, code: String, newVia: String) {
        linkBusy = true
        linkMsg = null
        viewModelScope.launch(Dispatchers.IO) {
            val err = link.pair(host.trim(), code, newVia)
            withContext(Dispatchers.Main) {
                linkBusy = false
                if (err == null) {
                    paired = true
                    pcName = link.pcName
                    via = link.via
                    reachable = true
                    found = emptyList()
                    usbSeen = null
                } else linkMsg = err
            }
        }
    }

    /** Same PC, other connection. The pairing stays valid. */
    fun switchVia(newVia: String) {
        linkBusy = true
        linkMsg = null
        viewModelScope.launch(Dispatchers.IO) {
            var host: String? = null
            if (newVia == "usb") {
                if (link.probe(PcLink.USB_HOST) != null) host = PcLink.USB_HOST
            } else {
                val saved = link.wifiHost
                if (saved.isNotEmpty() && link.probe(saved) != null) host = saved
                else host = link.discover().firstOrNull { it.name == link.pcName }?.host
            }
            withContext(Dispatchers.Main) {
                linkBusy = false
                if (host != null) {
                    link.host = host
                    link.via = newVia
                    if (newVia == "wifi") link.wifiHost = host
                    via = newVia
                    reachable = true
                } else {
                    linkMsg = if (newVia == "usb") "The PC is not reachable over the cable. Plug in the cable, turn on USB debugging and tap Allow."
                    else "${link.pcName} was not found on this Wi-Fi."
                }
            }
        }
    }

    fun forgetPc() {
        link.forget()
        paired = false
        pcName = ""
        reachable = null
    }

    // ------------------------------------------------------------------ backup to PC
    val selected = mutableStateMapOf(Category.DOCUMENTS to true, Category.PHOTOS to true, Category.VIDEOS to false, Category.AUDIO to false)
    var moveMode by mutableStateOf(false)
    var bPhase by mutableStateOf(BPhase.IDLE)
        private set
    var scanned by mutableStateOf(0)
        private set
    var totalFiles by mutableStateOf(0)
        private set
    var totalBytes by mutableStateOf(0L)
        private set
    var doneFiles by mutableStateOf(0)
        private set
    var doneBytes by mutableStateOf(0L)
        private set
    var rateMBs by mutableStateOf(0.0)
        private set
    var skippedCount by mutableStateOf(0)
        private set
    var failures by mutableStateOf<List<String>>(emptyList())
        private set
    var toDelete by mutableStateOf<List<PhoneFile>>(emptyList())
        private set
    var backupResult by mutableStateOf<String?>(null)
        private set
    private var backupJob: Job? = null

    val busyBackup: Boolean get() = bPhase in setOf(BPhase.SCANNING, BPhase.CHECKING, BPhase.SENDING, BPhase.VERIFYING, BPhase.DELETING)

    fun startBackup() {
        if (busyBackup) return
        val cats = selected.filterValues { it }.keys
        if (cats.isEmpty()) return
        val move = moveMode
        failures = emptyList()
        toDelete = emptyList()
        backupResult = null
        doneFiles = 0
        doneBytes = 0
        skippedCount = 0
        rateMBs = 0.0
        bPhase = BPhase.SCANNING
        backupJob = viewModelScope.launch {
            var ticker: Job? = null
            try {
                val all = withContext(Dispatchers.IO) { PhoneFiles.scan(cats) { n -> scanned = n } }
                bPhase = BPhase.CHECKING
                val needIdx = withContext(Dispatchers.IO) { link.check(all) }
                val needSet = needIdx.toHashSet()
                val need = needIdx.map { all[it] }
                val already = all.filterIndexed { i, _ -> i !in needSet }
                skippedCount = already.size
                totalFiles = need.size
                totalBytes = need.sumOf { it.size }
                bPhase = BPhase.SENDING

                val verified = Collections.synchronizedList(ArrayList<PhoneFile>())
                val failed = Collections.synchronizedList(ArrayList<String>())
                val bytes = AtomicLong(0)
                val files = AtomicInteger(0)
                val started = System.currentTimeMillis()
                ticker = launch {
                    while (isActive) {
                        doneBytes = bytes.get()
                        doneFiles = files.get()
                        val secs = (System.currentTimeMillis() - started) / 1000.0
                        if (secs > 0.5) rateMBs = bytes.get() / secs / 1e6
                        delay(300)
                    }
                }
                withContext(Dispatchers.IO) {
                    link.session("start", need.size, totalBytes, if (move) "move" else "copy")
                    val queue = Channel<PhoneFile>(Channel.UNLIMITED)
                    need.forEach { queue.trySend(it) }
                    queue.close()
                    coroutineScope {
                        repeat(4) {
                            launch {
                                for (f in queue) {
                                    ensureActive()
                                    var result = UploadResult(false, "not sent")
                                    var attempt = 0
                                    while (attempt < 3 && !result.ok) {
                                        ensureActive()
                                        if (attempt > 0) delay(1500L * attempt)
                                        var sentThisTry = 0L
                                        result = link.upload(f) { n -> sentThisTry += n; bytes.addAndGet(n) }
                                        if (!result.ok) bytes.addAndGet(-sentThisTry)
                                        if (result.error == "The PC disk is full") break
                                        attempt++
                                    }
                                    if (result.ok) {
                                        verified.add(f)
                                        files.incrementAndGet()
                                    } else failed.add(f.file.name + ": " + (result.error ?: "failed"))
                                }
                            }
                        }
                    }
                    link.session("end")
                }
                ticker?.cancel()
                doneBytes = bytes.get()
                doneFiles = files.get()

                if (move && already.isNotEmpty()) {
                    // Files that were already on the PC: compare both copies before offering to remove them.
                    bPhase = BPhase.VERIFYING
                    val q = Channel<PhoneFile>(Channel.UNLIMITED)
                    already.forEach { q.trySend(it) }
                    q.close()
                    withContext(Dispatchers.IO) {
                        coroutineScope { repeat(3) { launch { for (f in q) { ensureActive(); if (link.verify(f)) verified.add(f) } } } }
                    }
                }
                failures = failed.toList()
                if (move && verified.isNotEmpty()) {
                    toDelete = verified.toList()
                    bPhase = BPhase.REVIEW_DELETE
                } else {
                    backupResult = summary(files.get(), bytes.get(), failed.size, already.size, 0, 0L)
                    bPhase = BPhase.DONE
                }
            } catch (e: CancellationException) {
                viewModelScope.launch(Dispatchers.IO) { link.session("end") }
                backupResult = "Stopped. Files already sent are safe on the PC. Nothing was removed from the phone."
                bPhase = BPhase.DONE
            } catch (e: Exception) {
                backupResult = (e.message ?: "Something went wrong") + " Nothing was removed from the phone."
                bPhase = BPhase.DONE
            } finally {
                ticker?.cancel()
            }
        }
    }

    private fun summary(sent: Int, bytes: Long, failed: Int, skipped: Int, removed: Int, freed: Long): String {
        val sb = StringBuilder("$sent files (${fmtBytes(bytes)}) copied to ${link.pcName} and checked.")
        if (skipped > 0) sb.append(" $skipped were already on the PC.")
        if (removed > 0) sb.append(" $removed removed from the phone, ${fmtBytes(freed)} freed.")
        if (failed > 0) sb.append(" $failed could not be sent and stay on the phone.")
        return sb.toString()
    }

    fun cancelBackup() {
        backupJob?.cancel()
    }

    /** The user approved removing the verified files from the phone. */
    fun approveDelete() {
        val list = toDelete
        bPhase = BPhase.DELETING
        viewModelScope.launch {
            val (removed, freed, paths) = withContext(Dispatchers.IO) {
                var n = 0
                var b = 0L
                val p = ArrayList<String>()
                for (f in list) {
                    // Last safety check: the file must still be exactly what the PC confirmed.
                    if (f.file.length() == f.size && f.file.lastModified() == f.mtime && f.file.delete()) {
                        n++
                        b += f.size
                        p.add(f.file.absolutePath)
                    }
                }
                Triple(n, b, p)
            }
            scanMedia(paths)
            backupResult = summary(doneFiles, doneBytes, failures.size, skippedCount, removed, freed)
            toDelete = emptyList()
            bPhase = BPhase.DONE
            refreshPermissions()
        }
    }

    fun declineDelete() {
        backupResult = summary(doneFiles, doneBytes, failures.size, skippedCount, 0, 0L) + " You chose to keep all files on the phone."
        toDelete = emptyList()
        bPhase = BPhase.DONE
    }

    fun resetBackup() {
        if (!busyBackup) bPhase = BPhase.IDLE
    }

    // ------------------------------------------------------------------ organize chat documents
    var oPhase by mutableStateOf(OPhase.IDLE)
        private set
    var orgSources by mutableStateOf<List<OrgSource>>(emptyList())
        private set
    val plan = mutableStateListOf<Planned>()
    var orgIndex by mutableStateOf(0)
        private set
    var orgTotal by mutableStateOf(0)
        private set
    var orgCurrent by mutableStateOf("")
        private set
    var orgResult by mutableStateOf<String?>(null)
        private set
    var lastOrgLog by mutableStateOf<OrgLog?>(null)
        private set
    private var orgJob: Job? = null

    fun loadSources() {
        viewModelScope.launch(Dispatchers.IO) {
            val s = Organizer.sources()
            withContext(Dispatchers.Main) { orgSources = s }
        }
    }

    fun startOrganize() {
        if (oPhase == OPhase.READING) return
        plan.clear()
        orgResult = null
        oPhase = OPhase.READING
        orgJob = viewModelScope.launch {
            try {
                val files = withContext(Dispatchers.IO) { Organizer.allFiles() }
                orgTotal = files.size
                val seen = HashMap<String, File>()
                val out = ArrayList<Planned>()
                for ((i, pair) in files.withIndex()) {
                    val (source, f) = pair
                    orgIndex = i
                    orgCurrent = f.name
                    val p = withContext(Dispatchers.IO) {
                        val sha = try { PhoneFiles.sha256(f) } catch (e: Exception) { "" }
                        val first = if (sha.isNotEmpty()) seen[sha] else null
                        if (first != null) {
                            Planned(f, source, Classifier.DUPLICATES, "Same file as ${first.name}")
                        } else {
                            if (sha.isNotEmpty()) seen[sha] = f
                            val t = TextExtract.extract(ctx, f)
                            val (folder, why) = Classifier.classify(f.name, t.text, t.fromImage)
                            // Unsorted files stay where they are unless the user picks a folder for them.
                            Planned(f, source, folder, why, selected = folder != Classifier.UNSORTED)
                        }
                    }
                    out.add(p)
                }
                plan.addAll(out)
                orgIndex = files.size
                oPhase = OPhase.REVIEW
            } catch (e: CancellationException) {
                oPhase = OPhase.IDLE
            } catch (e: Exception) {
                orgResult = e.message ?: "Could not read the documents"
                oPhase = OPhase.IDLE
            }
        }
    }

    fun cancelOrganize() {
        orgJob?.cancel()
    }

    fun togglePlanned(i: Int) {
        val p = plan[i]
        plan[i] = p.copy(selected = !p.selected)
    }

    fun setFolder(i: Int, folder: String) {
        val p = plan[i]
        plan[i] = p.copy(folder = folder, selected = true, reason = "You chose this folder")
    }

    fun applyOrganize(move: Boolean) {
        val chosen = plan.filter { it.selected && it.folder != Classifier.UNSORTED }
        if (chosen.isEmpty()) return
        oPhase = OPhase.APPLYING
        orgTotal = chosen.size
        viewModelScope.launch {
            val (done, failed) = withContext(Dispatchers.IO) { Organizer.apply(chosen, move) { i -> orgIndex = i } }
            val log = withContext(Dispatchers.IO) { Organizer.latestLog() }
            scanMedia(chosen.map { it.file.absolutePath } + listOf(Organizer.target.absolutePath))
            lastOrgLog = log
            val folders = chosen.map { it.folder }.distinct().size
            orgResult = "$done files ${if (move) "moved" else "copied"} into $folders folders in Documents › Organized." +
                (if (failed.isNotEmpty()) " ${failed.size} could not be ${if (move) "moved" else "copied"} and were left as they were." else "") +
                " You can undo this for 30 days."
            oPhase = OPhase.DONE
        }
    }

    fun undoOrganize() {
        val log = lastOrgLog ?: return
        oPhase = OPhase.APPLYING
        viewModelScope.launch {
            val n = withContext(Dispatchers.IO) { Organizer.undo(log) }
            lastOrgLog = withContext(Dispatchers.IO) { Organizer.latestLog() }
            orgResult = "Undone: $n files put back as they were."
            plan.clear()
            oPhase = OPhase.DONE
            loadSources()
        }
    }

    fun resetOrganize() {
        oPhase = OPhase.IDLE
        loadSources()
    }

    // ------------------------------------------------------------------ clear old app backups
    var cPhase by mutableStateOf(CPhase.IDLE)
        private set
    var cleanItems by mutableStateOf<List<CleanItem>>(emptyList())
        private set
    val cleanSel = mutableStateMapOf<String, Boolean>()
    var cleanResult by mutableStateOf<String?>(null)
        private set
    var holds by mutableStateOf<List<Hold>>(emptyList())
        private set

    fun scanClean() {
        cPhase = CPhase.SCANNING
        cleanResult = null
        viewModelScope.launch {
            val items = withContext(Dispatchers.IO) { try { Cleaner.scan(ctx) } catch (e: Exception) { emptyList() } }
            cleanSel.clear()
            // Only items that are clearly safe start ticked. "Your call" items wait for the user.
            items.forEach { if (it.verdict != Verdict.KEEP) cleanSel[it.id] = it.verdict == Verdict.SAFE }
            cleanItems = items
            cPhase = CPhase.REVIEW
        }
    }

    fun toggleClean(id: String) {
        cleanSel[id] = !(cleanSel[id] ?: false)
    }

    val cleanChosen: List<CleanItem> get() = cleanItems.filter { it.verdict != Verdict.KEEP && cleanSel[it.id] == true }

    fun applyClean(holdFirst: Boolean) {
        val chosen = cleanChosen
        if (chosen.isEmpty()) return
        cPhase = CPhase.WORKING
        viewModelScope.launch {
            val (n, bytes) = withContext(Dispatchers.IO) { if (holdFirst) Cleaner.hold(chosen) else Cleaner.deleteNow(chosen) }
            holds = withContext(Dispatchers.IO) { Cleaner.holds() }
            cleanResult = if (holdFirst) {
                "${fmtBytes(bytes)} of old backups set aside. They are deleted for good in 7 days, and you can restore them until then. Use \"Delete now\" below to free the space today."
            } else "${fmtBytes(bytes)} freed. The latest backup of every app was kept."
            cPhase = CPhase.DONE
            refreshPermissions()
        }
    }

    fun restoreHold(h: Hold) {
        viewModelScope.launch {
            val n = withContext(Dispatchers.IO) { Cleaner.restore(h) }
            holds = withContext(Dispatchers.IO) { Cleaner.holds() }
            cleanResult = "$n backups put back where they were."
        }
    }

    fun deleteHold(h: Hold) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { Cleaner.deleteHold(h) }
            holds = withContext(Dispatchers.IO) { Cleaner.holds() }
            cleanResult = "${fmtBytes(h.bytes)} freed."
            refreshPermissions()
        }
    }

    fun resetClean() {
        cPhase = CPhase.IDLE
    }
}
