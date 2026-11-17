package com.abs.phonesolutions.ui

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.abs.phonesolutions.AppViewModel
import com.abs.phonesolutions.CPhase
import com.abs.phonesolutions.core.Cleaner
import com.abs.phonesolutions.core.Hold
import com.abs.phonesolutions.core.Verdict
import com.abs.phonesolutions.core.fmtBytes

@Composable
fun CleanScreen(vm: AppViewModel) {
    var ask by remember { mutableStateOf(false) }
    var deleteHold by remember { mutableStateOf<Hold?>(null) }
    val ctx = LocalContext.current

    Screen {
        Brand()
        Eyebrow("App backups only · your documents are never touched")
        Title(
            when (vm.cPhase) {
                CPhase.IDLE -> "Find app backups you no longer need"
                CPhase.SCANNING -> "Checking app backups…"
                CPhase.REVIEW -> if (vm.cleanItems.isEmpty()) "Nothing to clear" else "${fmtBytes(vm.cleanChosen.sumOf { it.bytes })} selected"
                CPhase.WORKING -> "Working…"
                CPhase.DONE -> "Done"
            },
        )
        when (vm.cPhase) {
            CPhase.IDLE, CPhase.DONE -> {
                vm.cleanResult?.let { Panel { Body(it) } }
                Body(
                    "Apps keep backups of chats and other data. Old copies pile up when a newer backup replaces them, and installer files stay behind after apps are installed. " +
                        "The app checks each one's date, whether a newer copy exists and whether the app is installed.",
                    Muted, 14,
                )
                Primary(if (vm.cPhase == CPhase.DONE) "Check again" else "Check app backups") { vm.scanClean() }
            }
            CPhase.SCANNING -> Panel { Progress(0.5f); Body("Looking at WhatsApp backups, saved installers and transfer folders…", Muted, 14) }
            CPhase.REVIEW -> {
                if (vm.cleanItems.isEmpty()) Body("No outdated app backups found that Android lets the app reach.", Muted, 14)
                vm.cleanItems.forEach { item ->
                    Panel {
                        val keep = item.verdict == Verdict.KEEP
                        CheckRow(
                            checked = !keep && vm.cleanSel[item.id] == true,
                            title = item.title + " · " + fmtBytes(item.bytes),
                            sub = item.app + " · " + item.detail,
                            enabled = !keep,
                        ) { vm.toggleClean(item.id) }
                        Body(item.why, Ink, 13)
                        Tag(
                            when (item.verdict) {
                                Verdict.SAFE -> "Safe to clear"
                                Verdict.ASK -> "Your call"
                                Verdict.KEEP -> "Kept"
                            },
                            strong = item.verdict == Verdict.ASK,
                        )
                    }
                }
                val chosen = vm.cleanChosen
                Primary(if (chosen.isEmpty()) "Pick backups to clear" else "Clear ${fmtBytes(chosen.sumOf { it.bytes })}", enabled = chosen.isNotEmpty()) { ask = true }
                Secondary("Cancel") { vm.resetClean() }
            }
            CPhase.WORKING -> Panel { Progress(0.6f); Body("Please keep the app open…", Muted, 14) }
        }

        if (vm.holds.isNotEmpty()) {
            Eyebrow("Recently cleared")
            vm.holds.forEach { h ->
                Panel {
                    Text("${fmtBytes(h.bytes)} · ${h.count} items", fontSize = 16.sp, fontWeight = FontWeight.ExtraBold)
                    Body("Cleared ${Cleaner.date(h.created)}. Deleted for good on ${Cleaner.deleteDate(h)}.", Muted, 13)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) { Secondary("Restore") { vm.restoreHold(h) } }
                        Column(Modifier.weight(1f)) { Secondary("Delete now") { deleteHold = h } }
                    }
                }
            }
        }

        Panel {
            Eyebrow("Protected by Android")
            Body("Telegram's cache and most apps' private data can't be opened by other apps. Clear them from inside the app or in Android's settings.", size = 14)
            val tg = ctx.packageManager.getLaunchIntentForPackage("org.telegram.messenger")
            if (tg != null) Secondary("Open Telegram (Settings › Data and Storage › Storage Usage)") { ctx.startActivity(tg) }
            Secondary("Open Android storage settings") {
                try {
                    ctx.startActivity(Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS))
                } catch (e: Exception) {
                    ctx.startActivity(Intent(Settings.ACTION_SETTINGS))
                }
            }
        }
    }

    if (ask) {
        val chosen = vm.cleanChosen
        val total = fmtBytes(chosen.sumOf { it.bytes })
        HoldDialog(total, chosen.size, onHold = { ask = false; vm.applyClean(true) }, onDelete = { ask = false; vm.applyClean(false) }, onCancel = { ask = false })
    }
    deleteHold?.let { h ->
        Confirm(
            "Delete ${fmtBytes(h.bytes)} for good?", "These old backups can't be restored after this.", "Delete",
            onConfirm = { deleteHold = null; vm.deleteHold(h) }, onDismiss = { deleteHold = null },
        )
    }
}

@Composable
private fun HoldDialog(total: String, n: Int, onHold: () -> Unit, onDelete: () -> Unit, onCancel: () -> Unit) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Clear $total of old backups?", fontWeight = FontWeight.ExtraBold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("$n items. The latest backup of every app is kept.", fontSize = 15.sp)
                Text("Safest: set them aside for 7 days so you can restore them. Space is freed when they're deleted after 7 days, or when you tap Delete now.", fontSize = 14.sp, color = Muted)
            }
        },
        confirmButton = {
            Column(horizontalAlignment = Alignment.End) {
                androidx.compose.material3.TextButton(onClick = onHold) { Text("Set aside for 7 days", color = Accent, fontWeight = FontWeight.Bold) }
                androidx.compose.material3.TextButton(onClick = onDelete) { Text("Delete now", color = SoftInk) }
                androidx.compose.material3.TextButton(onClick = onCancel) { Text("Cancel", color = Ink) }
            }
        },
        containerColor = Card,
    )
}
