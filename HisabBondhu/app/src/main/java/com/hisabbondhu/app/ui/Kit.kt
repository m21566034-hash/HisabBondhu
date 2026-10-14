@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.hisabbondhu.app.ui

import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hisabbondhu.app.core.Fmt
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset

object Sem {
    val sale = Color(0xFF0B6E4F); val paid = Color(0xFF1565C0); val expense = Color(0xFFC62828)
    val due = Color(0xFFE65100); val profit = Color(0xFF2E7D32); val neutral = Color(0xFF546E7A)
}

@Composable
fun Screen(
    title: String, onBack: (() -> Unit)?, snack: SnackbarHostState? = null,
    actions: @Composable RowScope.() -> Unit = {}, fab: @Composable () -> Unit = {},
    bottom: @Composable () -> Unit = {}, content: @Composable ColumnScope.() -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(title = { Text(title, fontWeight = FontWeight.SemiBold) },
                navigationIcon = { if (onBack != null) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "পিছনে") } },
                actions = actions)
        },
        floatingActionButton = fab, bottomBar = bottom,
        snackbarHost = { if (snack != null) SnackbarHost(snack) }
    ) { pad -> Column(Modifier.padding(pad).fillMaxSize(), content = content) }
}

@Composable
fun Scroll(content: @Composable ColumnScope.() -> Unit) =
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp).imePadding(), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)

@Composable
fun Field(label: String, value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier.fillMaxWidth(),
          keyboard: KeyboardType = KeyboardType.Text, singleLine: Boolean = true, error: String? = null, readOnly: Boolean = false) {
    OutlinedTextField(value, onChange, modifier, label = { Text(label) }, singleLine = singleLine, readOnly = readOnly,
        keyboardOptions = KeyboardOptions(keyboardType = keyboard), isError = error != null,
        supportingText = { if (error != null) Text(error) })
}

/** Accepts Bangla or English digits; the value is parsed with Fmt.parseMoney / parseQty. */
@Composable
fun NumField(label: String, value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier.fillMaxWidth(), error: String? = null) =
    Field(label, value, { v -> if (v.all { it.isDigit() || it == '.' || it == ',' || it in "০১২৩৪৫৬৭৮৯" }) onChange(v) }, modifier, KeyboardType.Decimal, true, error)

@Composable
fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp))
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
    }
}

@Composable
fun Stat(label: String, value: String, color: Color, modifier: Modifier = Modifier) {
    Card(modifier, colors = CardDefaults.cardColors(containerColor = color.copy(alpha = 0.10f)), border = BorderStroke(1.dp, color.copy(alpha = 0.35f))) {
        Column(Modifier.padding(12.dp)) {
            Text(label, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(4.dp))
            Text(value, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = color)
        }
    }
}

@Composable
fun Item(title: String, sub: String = "", right: String = "", rightSub: String = "", rightColor: Color = Color.Unspecified, onClick: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Medium)
            if (sub.isNotBlank()) Text(sub, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (right.isNotBlank() || rightSub.isNotBlank()) Column(horizontalAlignment = Alignment.End) {
            if (right.isNotBlank()) Text(right, fontWeight = FontWeight.SemiBold, color = rightColor)
            if (rightSub.isNotBlank()) Text(rightSub, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
}

@Composable
fun Empty(text: String) = Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
    Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
fun Loading() = Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }

@Composable
fun Chips(options: List<String>, selected: String, onSel: (String) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { o -> FilterChip(selected = o == selected, onClick = { onSel(o) }, label = { Text(o) }) }
    }
}

@Composable
fun Pick(label: String, value: String, options: List<String>, onSel: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { open = true }, Modifier.fillMaxWidth()) { Text("$label: $value", Modifier.weight(1f)); Text("▾") }
        DropdownMenu(open, { open = false }) { options.forEach { o -> DropdownMenuItem({ Text(o) }, { onSel(o); open = false }) } }
    }
}

@Composable
fun Confirm(title: String, text: String, yes: String = "হ্যাঁ, নিশ্চিত", onYes: () -> Unit, onNo: () -> Unit) =
    AlertDialog(onDismissRequest = onNo, title = { Text(title) }, text = { Text(text) },
        confirmButton = { TextButton(onYes) { Text(yes) } }, dismissButton = { TextButton(onNo) { Text("না") } })

@Composable
fun Info(title: String, text: String, onOk: () -> Unit) =
    AlertDialog(onDismissRequest = onOk, title = { Text(title) }, text = { Text(text) }, confirmButton = { TextButton(onOk) { Text("ঠিক আছে") } })

/** Asks for a reason before a significant correction. */
@Composable
fun ReasonDialog(title: String, text: String, yes: String, onYes: (String) -> Unit, onNo: () -> Unit) {
    var r by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = onNo, title = { Text(title) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { Text(text); Field("কারণ (বাধ্যতামূলক)", r, { r = it }) } },
        confirmButton = { TextButton({ if (r.isNotBlank()) onYes(r.trim()) }, enabled = r.isNotBlank()) { Text(yes) } },
        dismissButton = { TextButton(onNo) { Text("বাতিল") } })
}

/** Date picker returning a timestamp: the chosen day with the current time of day (Bangladesh time). */
@Composable
fun DateField(label: String, millis: Long, onChange: (Long) -> Unit) {
    var open by remember { mutableStateOf(false) }
    OutlinedButton(onClick = { open = true }, Modifier.fillMaxWidth()) { Text("$label: ${Fmt.date(millis)}") }
    if (open) {
        val st = rememberDatePickerState(initialSelectedDateMillis = Instant.ofEpochMilli(millis).atZone(Fmt.DHAKA).toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
        DatePickerDialog(onDismissRequest = { open = false },
            confirmButton = { TextButton({ st.selectedDateMillis?.let { onChange(withNow(pickedDay(it))) }; open = false }) { Text("ঠিক আছে") } },
            dismissButton = { TextButton({ open = false }) { Text("বাতিল") } }) { DatePicker(st) }
    }
}

fun pickedDay(utcMidnight: Long): LocalDate = Instant.ofEpochMilli(utcMidnight).atZone(ZoneOffset.UTC).toLocalDate()
fun withNow(d: LocalDate): Long = d.atTime(LocalTime.now(Fmt.DHAKA)).atZone(Fmt.DHAKA).toInstant().toEpochMilli()
fun now() = System.currentTimeMillis()

fun toast(c: Context, s: String) = android.widget.Toast.makeText(c, s, android.widget.Toast.LENGTH_LONG).show()
