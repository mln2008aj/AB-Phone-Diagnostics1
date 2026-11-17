package com.abs.phonesolutions.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.abs.phonesolutions.AppViewModel
import com.abs.phonesolutions.BPhase
import com.abs.phonesolutions.core.Category
import com.abs.phonesolutions.core.PcLink
import com.abs.phonesolutions.core.fmtBytes
import java.util.Locale

@Composable
fun BackupScreen(vm: AppViewModel) {
    // Keep the screen on while files are moving, so Android doesn't pause the transfer.
    val view = LocalView.current
    val busy = vm.busyBackup
    DisposableEffect(busy) {
        view.keepScreenOn = busy
        onDispose { view.keepScreenOn = false }
    }
    LaunchedEffect(Unit) { if (vm.paired) vm.checkReachable() }

    Screen {
        Brand()
        Eyebrow("Back up to PC")
        Title(if (vm.paired) "Send files to ${vm.pcName}" else "Connect to your PC")
        if (!vm.paired) ConnectPanel(vm) else LinkedPanel(vm)
        if (vm.paired) BackupPanel(vm)
    }
}

@Composable
private fun ConnectPanel(vm: AppViewModel) {
    var method by remember { mutableStateOf("usb") }
    var host by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }

    Body("First, open the AB's Phone Solutions program on your PC. It shows a 6-digit pairing code.", Muted, 14)
    Choice(method == "usb", "USB-C cable · fastest", "Most reliable for large backups. Needs USB debugging turned on once.") { method = "usb"; vm.linkMsg = null }
    Choice(method == "wifi", "Same Wi-Fi", "No cable. Phone and PC on the same home or office network.") { method = "wifi"; vm.linkMsg = null }

    if (method == "usb") {
        Panel {
            Eyebrow("Set up the cable once")
            Body("1. On the phone: Settings › About phone › tap Build number 7 times to turn on Developer options.", size = 14)
            Body("2. Settings › System › Developer options › turn on USB debugging.", size = 14)
            Body("3. Plug the phone into the PC and tap Allow when the phone asks.", size = 14)
            Body("The PC program then links itself. Use a USB 3 (USB-C) cable for full speed.", Muted, 13)
            Secondary(if (vm.linkBusy) "Checking…" else "Check the cable connection", enabled = !vm.linkBusy) { vm.checkUsb() }
            vm.usbSeen?.let { Body("Found $it on the cable.", size = 14) }
        }
        if (vm.usbSeen != null) PairCode(code, { code = it }, vm.linkBusy) { vm.pair(PcLink.USB_HOST, code, "usb") }
    } else {
        Panel {
            Secondary(if (vm.linkBusy) "Looking…" else "Find my PC on this Wi-Fi", enabled = !vm.linkBusy) { vm.searchWifi() }
            vm.found.forEach { pc ->
                Choice(host == pc.host, pc.name, pc.host) { host = pc.host }
            }
            OutlinedTextField(
                value = host, onValueChange = { host = it.trim() }, singleLine = true,
                label = { Text("Or type the PC's address (shown in the PC program)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (host.isNotEmpty()) PairCode(code, { code = it }, vm.linkBusy) { vm.pair(host, code, "wifi") }
    }
    vm.linkMsg?.let { Note(it) }
}

@Composable
private fun PairCode(code: String, onChange: (String) -> Unit, busy: Boolean, onPair: () -> Unit) {
    Panel {
        OutlinedTextField(
            value = code, onValueChange = { v -> onChange(v.filter { it.isDigit() }.take(6)) }, singleLine = true,
            label = { Text("6-digit code from the PC") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            modifier = Modifier.fillMaxWidth(),
        )
        Primary(if (busy) "Pairing…" else "Pair with PC", enabled = code.length == 6 && !busy, onClick = onPair)
    }
}

@Composable
private fun LinkedPanel(vm: AppViewModel) {
    var forget by remember { mutableStateOf(false) }
    Panel {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column(Modifier.weight(1f)) {
                Text(vm.pcName, fontSize = 17.sp, fontWeight = FontWeight.ExtraBold)
                Text(
                    (if (vm.via == "usb") "USB-C cable" else "Wi-Fi") + " · " + when (vm.reachable) {
                        true -> "connected"
                        false -> "not reachable"
                        null -> "checking…"
                    },
                    fontSize = 13.sp, color = if (vm.reachable == false) SoftInk else Muted,
                )
            }
        }
        if (vm.reachable == false) {
            Body(
                if (vm.via == "usb") "Plug in the cable and make sure the PC program is open."
                else "Make sure the PC program is open and both are on the same Wi-Fi.",
                Muted, 13,
            )
        }
        if (!vm.busyBackup) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f)) { Secondary("Check again", enabled = !vm.linkBusy) { vm.checkReachable() } }
                Column(Modifier.weight(1f)) {
                    Secondary(if (vm.via == "usb") "Use Wi-Fi" else "Use cable", enabled = !vm.linkBusy) {
                        vm.switchVia(if (vm.via == "usb") "wifi" else "usb")
                    }
                }
            }
            Secondary("Forget this PC") { forget = true }
        }
        vm.linkMsg?.let { Note(it) }
    }
    if (forget) {
        Confirm(
            "Forget ${vm.pcName}?", "This phone will need the pairing code again to send files. Backups already on the PC are not touched.",
            "Forget", onConfirm = { forget = false; vm.forgetPc() }, onDismiss = { forget = false },
        )
    }
}

@Composable
private fun BackupPanel(vm: AppViewModel) {
    var askMove by remember { mutableStateOf(false) }
    when (vm.bPhase) {
        BPhase.IDLE, BPhase.DONE -> {
            vm.backupResult?.let { Panel { Eyebrow("Last backup"); Body(it) } }
            if (vm.failures.isNotEmpty()) Panel {
                Eyebrow("Not sent (still on the phone)")
                vm.failures.take(6).forEach { Body(it, Muted, 13) }
                if (vm.failures.size > 6) Body("and ${vm.failures.size - 6} more", Muted, 13)
            }
            Panel {
                Eyebrow("What to send")
                val subs = mapOf(
                    Category.DOCUMENTS to "PDF, Word, Excel, slides, text and zip files, including WhatsApp documents",
                    Category.PHOTOS to "Camera, screenshots, WhatsApp and other app pictures",
                    Category.VIDEOS to "Camera and app videos",
                    Category.AUDIO to "Music, voice notes and recordings",
                )
                Category.values().forEach { c ->
                    CheckRow(vm.selected[c] == true, c.label, subs[c] ?: "") { vm.selected[c] = !(vm.selected[c] ?: false) }
                }
                Body("System files and app data are never included. Files already on the PC are skipped.", Muted, 13)
            }
            Panel {
                Eyebrow("After sending")
                Choice(!vm.moveMode, "Copy", "Keep everything on the phone too.") { vm.moveMode = false }
                Choice(vm.moveMode, "Move", "Free up space. You'll see the list and approve before anything is removed, and only files the PC has confirmed are offered.") { vm.moveMode = true }
            }
            val any = vm.selected.values.any { it }
            Primary(
                if (vm.moveMode) "Back up, then review what to remove" else "Back up now",
                enabled = any && vm.reachable == true,
            ) { if (vm.moveMode) askMove = true else vm.startBackup() }
            if (vm.reachable != true) Body("Connect to the PC to start.", Muted, 13)
        }
        BPhase.SCANNING -> Panel {
            Eyebrow("Step 1 of 3 · Finding your files")
            Body("${vm.scanned} files found so far…")
            Secondary("Stop") { vm.cancelBackup() }
        }
        BPhase.CHECKING -> Panel {
            Eyebrow("Step 2 of 3 · Checking what the PC already has")
            Body("Comparing ${vm.scanned} files with ${vm.pcName}…")
        }
        BPhase.SENDING, BPhase.VERIFYING -> Panel {
            Eyebrow(if (vm.bPhase == BPhase.SENDING) "Step 3 of 3 · Sending" else "Checking files that were already on the PC")
            val frac = if (vm.totalBytes > 0) vm.doneBytes.toFloat() / vm.totalBytes else 1f
            Progress(frac)
            Text("${vm.doneFiles} of ${vm.totalFiles} files · ${fmtBytes(vm.doneBytes)} of ${fmtBytes(vm.totalBytes)}", fontSize = 15.sp, fontWeight = FontWeight.Bold)
            val left = if (vm.rateMBs > 0.1) ((vm.totalBytes - vm.doneBytes) / (vm.rateMBs * 1e6)).toLong() else -1L
            Body(
                String.format(Locale.US, "%.0f MB/s", vm.rateMBs) + (if (left >= 0) " · about " + (if (left >= 90) "${left / 60} min" else "$left s") + " left" else "") +
                    (if (vm.skippedCount > 0) " · ${vm.skippedCount} already on PC" else ""),
                Muted, 13,
            )
            Body("Each file is checked on the PC (SHA-256) before it counts as saved. Keep the app open.", Muted, 13)
            Secondary("Stop") { vm.cancelBackup() }
        }
        BPhase.REVIEW_DELETE -> {
            val bytes = vm.toDelete.sumOf { it.size }
            Panel {
                Eyebrow("Your approval is needed")
                Text("Remove ${vm.toDelete.size} files (${fmtBytes(bytes)}) from this phone?", fontSize = 19.sp, fontWeight = FontWeight.ExtraBold)
                Body("Every one of these is saved on ${vm.pcName} and the PC copy matched the phone copy exactly.", size = 14)
                Body("Once removed, they are only on the PC. Keep the PC backup safe.", SoftInk, 14)
                vm.toDelete.take(8).forEach { Body("• " + it.rel.substringAfter('/'), Muted, 12) }
                if (vm.toDelete.size > 8) Body("and ${vm.toDelete.size - 8} more", Muted, 12)
                if (vm.failures.isNotEmpty()) Body("${vm.failures.size} files could not be sent; they are not in this list and stay on the phone.", Muted, 13)
            }
            Primary("Remove from phone") { vm.approveDelete() }
            Secondary("Keep them on the phone") { vm.declineDelete() }
        }
        BPhase.DELETING -> Panel { Eyebrow("Removing approved files"); Body("Freeing space…") }
    }
    if (askMove) {
        Confirm(
            "Move files to the PC?",
            "Files are copied and checked first. Then you'll see exactly which files can be removed from the phone, and nothing is removed until you approve.",
            "Start", onConfirm = { askMove = false; vm.startBackup() }, onDismiss = { askMove = false },
        )
    }
}
