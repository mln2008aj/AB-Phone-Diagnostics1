package com.abs.phonesolutions.ui

import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.abs.phonesolutions.AppViewModel
import com.abs.phonesolutions.core.Perm
import com.abs.phonesolutions.core.fmtBytes

enum class Tab(val label: String, val glyph: String) {
    HOME("Home", "⌂"), BACKUP("Backup", "⇪"), ORGANIZE("Organize", "▤"), CLEAN("Clean", "✦")
}

@Composable
fun AppRoot(vm: AppViewModel) {
    ABTheme {
        var tab by rememberSaveable { mutableStateOf(Tab.HOME) }
        BackHandler(enabled = tab != Tab.HOME) { tab = Tab.HOME }
        Scaffold(
            containerColor = Paper,
            bottomBar = {
                NavigationBar(containerColor = Card) {
                    Tab.values().forEach { t ->
                        NavigationBarItem(
                            selected = tab == t, onClick = { tab = t },
                            icon = { Text(t.glyph, fontSize = 20.sp) },
                            label = { Text(t.label, fontWeight = FontWeight.Bold) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = Accent, selectedTextColor = Accent, indicatorColor = Soft,
                                unselectedIconColor = Ink, unselectedTextColor = Ink,
                            ),
                        )
                    }
                }
            },
        ) { pad ->
            Box(Modifier.padding(pad).fillMaxSize()) {
                if (!vm.hasFiles) PermissionScreen(vm)
                else when (tab) {
                    Tab.HOME -> HomeScreen(vm) { tab = it }
                    Tab.BACKUP -> BackupScreen(vm)
                    Tab.ORGANIZE -> OrganizeScreen(vm)
                    Tab.CLEAN -> CleanScreen(vm)
                }
            }
        }
    }
}

@Composable
fun PermissionScreen(vm: AppViewModel) {
    val ctx = LocalContext.current
    val legacy = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { vm.refreshPermissions() }
    Screen {
        Brand()
        Title("One permission first")
        Body("To back up, organize and clean your files, the app needs Android's \"All files access\". This is what it does with it:")
        Panel {
            Body("• Reads your documents, photos, videos and audio to copy them to your PC.")
            Body("• Reads WhatsApp and Telegram documents on the phone to sort them by subject. Nothing is uploaded anywhere.")
            Body("• Finds outdated app backups, such as old WhatsApp chat copies.")
            Body("• Moves or deletes a file only after you see the list and tap to approve.")
        }
        Body("You can withdraw this at any time in Android Settings › Apps › AB's Phone Solutions.", Muted, 13)
        Primary("Allow file access") {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                try {
                    ctx.startActivity(Perm.filesSettingsIntent(ctx))
                } catch (e: Exception) {
                    ctx.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                }
            } else legacy.launch(Perm.legacyPermissions)
        }
    }
}

@Composable
fun HomeScreen(vm: AppViewModel, go: (Tab) -> Unit) {
    val s = vm.status
    Screen {
        Brand()
        Eyebrow(Build.MANUFACTURER + " " + Build.MODEL + " · Android " + Build.VERSION.RELEASE)
        Title("Keep your phone safe, clean and fast")
        if (s != null) {
            Panel {
                Eyebrow("Your phone now")
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column {
                        Text("Storage", fontSize = 13.sp, color = Muted)
                        Text("${fmtBytes(s.usedBytes)} of ${fmtBytes(s.totalBytes)}", fontSize = 17.sp, fontWeight = FontWeight.ExtraBold)
                    }
                    Column {
                        Text("Battery", fontSize = 13.sp, color = Muted)
                        Text(
                            (if (s.batteryPct >= 0) "${s.batteryPct}%" else "–") + " · " + String.format("%.0f °C", s.batteryTempC),
                            fontSize = 17.sp, fontWeight = FontWeight.ExtraBold,
                        )
                    }
                }
                Progress(if (s.totalBytes > 0) s.usedBytes.toFloat() / s.totalBytes else 0f)
                val full = s.totalBytes > 0 && s.usedBytes.toDouble() / s.totalBytes > 0.9
                Body(
                    (if (full) "Storage is over 90% full, which slows the phone down. Back up to PC and clear old app backups. " else "") +
                        "Battery health: ${s.batteryHealth}" + (if (s.charging) " · charging" else "") +
                        (if (s.batteryTempC >= 40f) ". It is warm; let it cool before a long transfer." else "."),
                    Muted, 13,
                )
            }
        }
        Eyebrow("Protect your data")
        ActionCard(
            "Back up phone to PC",
            if (vm.paired) "Linked to ${vm.pcName} · ${if (vm.via == "usb") "USB-C cable" else "Wi-Fi"}" else "USB-C cable or Wi-Fi. Nothing is removed until the PC copy is checked",
            dark = true,
        ) { go(Tab.BACKUP) }
        ActionCard("Organize chat documents", "Sort WhatsApp and Telegram documents into folders by subject") { go(Tab.ORGANIZE) }
        ActionCard("Clear old app backups", "Old WhatsApp chat copies, saved installers, leftover transfer folders") { go(Tab.CLEAN) }
        Panel {
            Eyebrow("Good to know")
            Body("Every move or delete waits for your approval, and most can be undone.", size = 14)
            Body("Android protects some app folders (for example Telegram's cache). For those the app shows you where to clear them instead.", Muted, 13)
            Body("Restoring a phone that won't start will come in the PC program in phase 2.", Muted, 13)
        }
    }
}

@Composable
fun SmallLabel(text: String, color: Color = Muted) {
    Text(text, fontSize = 12.sp, color = color, modifier = Modifier.padding(top = 2.dp))
}
