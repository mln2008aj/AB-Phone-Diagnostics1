package com.abs.phonesolutions.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.abs.phonesolutions.AppViewModel
import com.abs.phonesolutions.OPhase
import com.abs.phonesolutions.core.Classifier
import com.abs.phonesolutions.core.Cleaner

@Composable
fun OrganizeScreen(vm: AppViewModel) {
    LaunchedEffect(Unit) { vm.loadSources() }
    var confirm by remember { mutableStateOf<Boolean?>(null) } // true = move, false = copy
    var askUndo by remember { mutableStateOf(false) }

    Screen {
        Brand()
        Eyebrow("WhatsApp & Telegram documents")
        Title(
            when (vm.oPhase) {
                OPhase.IDLE -> "Sort shared documents by subject"
                OPhase.READING -> "Reading documents…"
                OPhase.REVIEW -> "Check the suggested folders"
                OPhase.APPLYING -> "Working…"
                OPhase.DONE -> "Done"
            },
        )
        when (vm.oPhase) {
            OPhase.IDLE -> {
                vm.orgResult?.let { Note(it) }
                Panel {
                    Eyebrow("Folders to read")
                    if (vm.orgSources.isEmpty()) Body("No WhatsApp, Telegram or Download document folders found yet.", Muted, 14)
                    vm.orgSources.forEach { s ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(s.name, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                                Text(s.dir.path.substringAfter("/0/"), fontSize = 12.sp, color = Muted)
                            }
                            Text("${s.count} files", fontSize = 14.sp)
                        }
                    }
                }
                Body("Each PDF, Word, Excel, slide deck and photo of a document is read on the phone: its title, subject line and key words. Nothing is uploaded.", Muted, 13)
                Note("Newer Telegram versions keep files in a protected folder that Android doesn't let other apps open. In Telegram, use \"Save to Downloads\" on files you want organized.")
                val total = vm.orgSources.sumOf { it.count }
                Primary("Read $total documents", enabled = total > 0) { vm.startOrganize() }
                vm.lastOrgLog?.let { log ->
                    Secondary("Undo the last organize (${log.count} files, ${Cleaner.date(log.created)})") { askUndo = true }
                }
            }
            OPhase.READING -> Panel {
                Progress(if (vm.orgTotal > 0) vm.orgIndex.toFloat() / vm.orgTotal else 0f)
                Body("${vm.orgIndex} of ${vm.orgTotal} · ${vm.orgCurrent}", size = 14)
                Secondary("Stop") { vm.cancelOrganize() }
            }
            OPhase.REVIEW -> ReviewPlan(vm, onMove = { confirm = true }, onCopy = { confirm = false })
            OPhase.APPLYING -> Panel {
                Progress(if (vm.orgTotal > 0) vm.orgIndex.toFloat() / vm.orgTotal else 0f)
                Body("Please keep the app open…", Muted, 14)
            }
            OPhase.DONE -> {
                vm.orgResult?.let { Panel { Eyebrow("Saved in Documents › Organized"); Body(it) } }
                vm.lastOrgLog?.let { Secondary("Undo") { askUndo = true } }
                Primary("Done") { vm.resetOrganize() }
            }
        }
    }

    confirm?.let { move ->
        val n = vm.plan.count { it.selected && it.folder != Classifier.UNSORTED }
        Confirm(
            if (move) "Move $n files into folders?" else "Copy $n files into folders?",
            if (move) "They go to Documents › Organized. Moved files will no longer open from inside the WhatsApp or Telegram chat, but you can always open them from the folder. Nothing is deleted, and you can undo this for 30 days."
            else "Copies go to Documents › Organized and the originals stay in the chats. This uses extra storage. You can undo this for 30 days.",
            if (move) "Move" else "Copy",
            onConfirm = { confirm = null; vm.applyOrganize(move) }, onDismiss = { confirm = null },
        )
    }
    if (askUndo) {
        Confirm(
            "Undo the last organize?", "Moved files go back to where they were. Copies made by the app are removed.",
            "Undo", onConfirm = { askUndo = false; vm.undoOrganize() }, onDismiss = { askUndo = false },
        )
    }
}

@Composable
private fun ReviewPlan(vm: AppViewModel, onMove: () -> Unit, onCopy: () -> Unit) {
    var open by remember { mutableStateOf<String?>(Classifier.FOLDERS.firstOrNull()) }
    val folders = Classifier.FOLDERS + listOf(Classifier.DUPLICATES, Classifier.UNSORTED)
    Body("Tap a folder to see why each file went there. Untick a file to leave it where it is, or pick another folder for it.", Muted, 14)
    folders.forEach { folder ->
        val idx = vm.plan.indices.filter { vm.plan[it].folder == folder }
        if (idx.isEmpty()) return@forEach
        val chosen = idx.count { vm.plan[it].selected }
        Panel {
            Row(
                Modifier.fillMaxWidth().clickable { open = if (open == folder) null else folder },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(folder, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold)
                    Text(
                        when (folder) {
                            Classifier.UNSORTED -> "Not clear enough. These stay where they are unless you pick a folder."
                            Classifier.DUPLICATES -> "The same file shared more than once. Set aside, never deleted."
                            else -> "$chosen of ${idx.size} selected"
                        },
                        fontSize = 12.sp, color = Muted,
                    )
                }
                Text("${idx.size}  " + if (open == folder) "−" else "+", fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
            if (open == folder) {
                idx.take(80).forEach { i -> PlannedRow(vm, i) }
                if (idx.size > 80) Body("and ${idx.size - 80} more (they follow the same choice)", Muted, 12)
            }
        }
    }
    val n = vm.plan.count { it.selected && it.folder != Classifier.UNSORTED }
    Primary("Move $n files into folders", enabled = n > 0, onClick = onMove)
    Secondary("Copy instead, keep originals in chats", enabled = n > 0, onClick = onCopy)
    Secondary("Cancel") { vm.resetOrganize() }
}

@Composable
private fun PlannedRow(vm: AppViewModel, i: Int) {
    val p = vm.plan[i]
    var menu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().background(Paper).padding(end = 8.dp), verticalAlignment = Alignment.Top) {
        Checkbox(
            checked = p.selected && p.folder != Classifier.UNSORTED,
            enabled = p.folder != Classifier.UNSORTED,
            onCheckedChange = { vm.togglePlanned(i) },
            colors = CheckboxDefaults.colors(checkedColor = Ink),
        )
        Column(Modifier.weight(1f).padding(top = 8.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(p.file.name, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Tag(p.source)
                Text(p.reason, fontSize = 12.sp, color = Muted)
            }
            Box {
                Text("Folder: ${p.folder} ▾", fontSize = 13.sp, color = Accent, fontWeight = FontWeight.Bold,
                    modifier = Modifier.clickable { menu = true }.padding(vertical = 4.dp))
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    (Classifier.FOLDERS + Classifier.DUPLICATES).forEach { f ->
                        DropdownMenuItem(text = { Text(f) }, onClick = { menu = false; vm.setFolder(i, f) })
                    }
                }
            }
        }
    }
}
