package com.abs.phonesolutions.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

val Accent = Color(0xFFC42A10)
val Ink = Color(0xFF1A1918)
val Paper = Color(0xFFF4F1EE)
val Card = Color(0xFFFFFFFF)
val Muted = Color(0xFF5C5752)
val Line = Color(0xFFD8D2CC)
val Soft = Color(0xFFFBE3DD)
val SoftInk = Color(0xFF8A1D0A)

@Composable
fun ABTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = Accent, onPrimary = Color.White, secondary = Ink, onSecondary = Color.White,
            background = Paper, onBackground = Ink, surface = Card, onSurface = Ink,
            surfaceVariant = Paper, onSurfaceVariant = Muted, outline = Line,
        ),
        content = content,
    )
}

@Composable
fun Screen(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxSize().background(Paper).verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        content = content,
    )
}

@Composable
fun Eyebrow(text: String, color: Color = Muted) {
    Text(text.uppercase(), fontSize = 11.sp, letterSpacing = 1.2.sp, color = color, fontWeight = FontWeight.Medium)
}

@Composable
fun Title(text: String) {
    Text(text, fontSize = 30.sp, lineHeight = 33.sp, fontWeight = FontWeight.ExtraBold, color = Ink)
}

@Composable
fun Body(text: String, color: Color = Ink, size: Int = 15) {
    Text(text, fontSize = size.sp, lineHeight = (size * 1.45).sp, color = color)
}

@Composable
fun Panel(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier.fillMaxWidth().background(Card).border(1.dp, Line).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        content = content,
    )
}

@Composable
fun Note(text: String) {
    Box(Modifier.fillMaxWidth().background(Soft).padding(12.dp)) {
        Text(text, fontSize = 14.sp, lineHeight = 20.sp, color = SoftInk)
    }
}

@Composable
fun ActionCard(title: String, sub: String, dark: Boolean = false, onClick: () -> Unit) {
    val bg = if (dark) Ink else Card
    val fg = if (dark) Color.White else Ink
    Row(
        Modifier.fillMaxWidth().background(bg).border(1.dp, if (dark) Ink else Line).clickable(onClick = onClick).padding(18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, fontSize = 19.sp, fontWeight = FontWeight.ExtraBold, color = fg)
            Text(sub, fontSize = 13.sp, lineHeight = 18.sp, color = if (dark) Color(0xFFE8E2DC) else Muted)
        }
        Spacer(Modifier.width(10.dp))
        Text("→", fontSize = 22.sp, color = fg)
    }
}

@Composable
fun Primary(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    Button(
        onClick = onClick, enabled = enabled, shape = RectangleShape,
        colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Color.White, disabledContainerColor = Line, disabledContentColor = Muted),
        modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp),
    ) { Text(text, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold) }
}

@Composable
fun Secondary(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick, enabled = enabled, shape = RectangleShape,
        colors = ButtonDefaults.outlinedButtonColors(contentColor = Ink),
        modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp),
    ) { Text(text, fontSize = 15.sp, fontWeight = FontWeight.Bold) }
}

@Composable
fun Progress(fraction: Float) {
    LinearProgressIndicator(
        progress = { fraction.coerceIn(0f, 1f) },
        modifier = Modifier.fillMaxWidth(), color = Accent, trackColor = Line,
    )
}

@Composable
fun CheckRow(checked: Boolean, title: String, sub: String, enabled: Boolean = true, onToggle: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onToggle).padding(vertical = 6.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Checkbox(
            checked = checked, onCheckedChange = { onToggle() }, enabled = enabled,
            colors = CheckboxDefaults.colors(checkedColor = Ink, uncheckedColor = Ink),
        )
        Column(Modifier.weight(1f).padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Ink)
            if (sub.isNotEmpty()) Text(sub, fontSize = 13.sp, lineHeight = 18.sp, color = Muted)
        }
    }
}

@Composable
fun Choice(selected: Boolean, title: String, sub: String, onClick: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().background(if (selected) Ink else Card).border(2.dp, if (selected) Ink else Line)
            .clickable(onClick = onClick).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(title, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold, color = if (selected) Color.White else Ink)
        Text(sub, fontSize = 13.sp, lineHeight = 18.sp, color = if (selected) Color(0xFFE8E2DC) else Muted)
    }
}

@Composable
fun Tag(text: String, strong: Boolean = false) {
    Text(
        text, fontSize = 11.sp, color = if (strong) SoftInk else Ink,
        modifier = Modifier.background(if (strong) Soft else Color(0xFFEAE5E0)).padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

@Composable
fun Confirm(title: String, text: String, confirm: String, dismiss: String = "Cancel", onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, fontWeight = FontWeight.ExtraBold) },
        text = { Text(text, fontSize = 15.sp, lineHeight = 21.sp) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirm, color = Accent, fontWeight = FontWeight.Bold) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(dismiss, color = Ink) } },
        containerColor = Card,
    )
}

@Composable
fun Brand() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(30.dp).background(Accent), contentAlignment = Alignment.Center) {
            Text("AB", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.ExtraBold)
        }
        Spacer(Modifier.width(8.dp))
        Text("AB's Phone Solutions", fontSize = 16.sp, fontWeight = FontWeight.ExtraBold, color = Ink)
    }
}
