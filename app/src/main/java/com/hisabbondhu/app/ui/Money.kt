@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.hisabbondhu.app.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hisabbondhu.app.Graph
import com.hisabbondhu.app.auth.AppLock
import com.hisabbondhu.app.auth.Auth
import com.hisabbondhu.app.core.*
import com.hisabbondhu.app.data.*
import com.hisabbondhu.app.export.*
import com.hisabbondhu.app.render.*
import com.hisabbondhu.app.sync.Sync
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ---------------------------------------------------------------------------------------------- expenses
@Composable
fun ExpenseListScreen(repo: Repo, nav: Nav) {
    var q by remember { mutableStateOf("") }
    var cat by remember { mutableStateOf("সব") }
    var span by remember { mutableStateOf("এই মাস") }
    val all by remember(q) { repo.dao.expenses("%" + q.trim() + "%", 3000) }.collectAsState(initial = null)
    Screen("খরচ", nav.back, fab = { ExtendedFloatingActionButton({ nav.go(Route.ExpenseForm(null)) }) { Text("+ খরচ যোগ") } }) {
        val list = all ?: return@Screen Loading()
        val today = java.time.LocalDate.now(Fmt.DHAKA)
        val range = when (span) {
            "আজ" -> DateRanges.of(Preset.TODAY); "এই মাস" -> DateRanges.of(Preset.THIS_MONTH)
            "এই বছর" -> DateRanges.days(today.withDayOfYear(1), today.withDayOfYear(1).plusYears(1).minusDays(1))
            else -> DateRange(0, Long.MAX_VALUE, "সব")
        }
        val live = list.filter { it.status == "ACTIVE" && it.date >= range.from && it.date < range.to && (cat == "সব" || it.category == cat) }
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Field("খোঁজ", q, { q = it })
            Chips(listOf("আজ", "এই মাস", "এই বছর", "সব"), span) { span = it }
            Pick("ক্যাটাগরি", cat, listOf("সব") + Profile.CATEGORIES) { cat = it }
            Text("মোট ($span): " + Fmt.money(live.sumOf { it.amount }), fontWeight = FontWeight.Bold, color = Sem.expense)
        }
        if (live.isEmpty()) Empty("কোনো খরচ নেই")
        else LazyColumn(contentPadding = PaddingValues(bottom = 90.dp)) {
            items(live, key = { it.id }) { e -> Item(e.category, listOf(Fmt.date(e.date), e.method, e.description).filter { it.isNotBlank() }.joinToString(" • "), Fmt.money(e.amount), "", Sem.expense) { nav.go(Route.ExpenseForm(e.id)) } }
        }
    }
}

@Composable
fun ExpenseFormScreen(repo: Repo, id: String?, profile: Profile, nav: Nav) {
    val c = LocalContext.current
    val scope = rememberCoroutineScope()
    val snack = remember { SnackbarHostState() }
    val newId = remember { repo.newId() }
    var old by remember { mutableStateOf<Expense?>(null) }
    var loaded by remember { mutableStateOf(id == null) }
    var cat by remember { mutableStateOf(Profile.CATEGORIES.first()) }; var amount by remember { mutableStateOf("") }
    var date by remember { mutableLongStateOf(now()) }; var method by remember { mutableStateOf(profile.defMethod) }
    var desc by remember { mutableStateOf("") }; var ref by remember { mutableStateOf("") }; var img by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }; var void by remember { mutableStateOf(false) }
    LaunchedEffect(id) {
        if (id != null) repo.dao.expense(id)?.let { e -> old = e; cat = e.category; amount = Fmt.plain(e.amount); date = e.date; method = e.method; desc = e.description; ref = e.ref; img = e.img }
        loaded = true
    }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) scope.launch { Img.encode(c, uri, 700, 70)?.let { img = it } ?: snack.showSnackbar("ছবি পড়া যায়নি") }
    }
    val amt = Fmt.parseMoney(amount)
    Screen(if (id == null) "নতুন খরচ" else "খরচ সম্পাদনা", nav.back, snack) {
        if (!loaded) return@Screen Loading()
        Scroll {
            if (old?.status == "VOID") Text("এই খরচ বাতিল: " + old!!.voidReason, color = MaterialTheme.colorScheme.error)
            Pick("ক্যাটাগরি", cat, Profile.CATEGORIES) { cat = it }
            NumField("টাকা ৳ *", amount, { amount = it }, error = if (amount.isNotBlank() && (amt == null || amt <= 0)) "সঠিক সংখ্যা দিন" else null)
            DateField("তারিখ", date) { date = it }
            Pick("পদ্ধতি", method, profile.methods) { method = it }
            Field("বিবরণ", desc, { desc = it }); Field("রেফারেন্স নম্বর (ঐচ্ছিক)", ref, { ref = it })
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Img.decode(img)?.let { Image(it.asImageBitmap(), null, Modifier.size(72.dp)) }
                OutlinedButton({ pick.launch("image/*") }) { Text(if (img.isBlank()) "রসিদের ছবি" else "ছবি বদলান") }
                if (img.isNotBlank()) TextButton({ img = "" }) { Text("সরান") }
            }
            Button({
                saving = true
                scope.launch {
                    val t = now(); val o = old
                    runCatching { repo.saveExpense(Expense(o?.id ?: newId, cat, amt ?: 0, date, method, desc.trim(), ref.trim(), img, o?.status ?: "ACTIVE", o?.voidReason ?: "", o?.createdAt ?: t, t)) }
                        .onSuccess { nav.back() }.onFailure { snack.showSnackbar(it.message ?: "ব্যর্থ"); saving = false }
                }
            }, enabled = !saving && amt != null && amt > 0 && old?.status != "VOID", modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("সংরক্ষণ করুন") }
            if (old != null && old!!.status == "ACTIVE") OutlinedButton({ void = true }, Modifier.fillMaxWidth()) { Text("খরচ বাতিল করুন", color = MaterialTheme.colorScheme.error) }
        }
    }
    if (void) ReasonDialog("খরচ বাতিল", "রেকর্ড মুছবে না, শুধু হিসাব থেকে বাদ যাবে এবং অডিট লগে থাকবে।", "বাতিল করুন",
        { r -> void = false; scope.launch { repo.voidExpense(old!!, r); nav.back() } }, { void = false })
}

// ---------------------------------------------------------------------------------------------- reports
val reportList = listOf("sales" to "বিক্রয় রিপোর্ট", "purchases" to "ক্রয় রিপোর্ট", "expenses" to "খরচ রিপোর্ট", "pnl" to "লাভ-ক্ষতি রিপোর্ট",
    "custDue" to "কাস্টমার বকেয়া রিপোর্ট", "supDue" to "সাপ্লায়ার পাওনা রিপোর্ট", "stock" to "স্টক রিপোর্ট", "cash" to "ক্যাশ ফ্লো রিপোর্ট")

@Composable
fun ReportsTab(nav: Nav) {
    Screen("রিপোর্ট", null) { LazyColumn { items(reportList) { (k, l) -> Item(l, onClick = { nav.go(Route.Report(k)) }) } } }
}

@Composable
fun ReportScreen(repo: Repo, profile: Profile, type: String, nav: Nav) {
    val c = LocalContext.current
    val scope = rememberCoroutineScope()
    val snack = remember { SnackbarHostState() }
    var preset by remember { mutableStateOf(Preset.THIS_MONTH) }
    var cf by remember { mutableLongStateOf(now()) }; var ct by remember { mutableLongStateOf(now()) }
    val range = remember(preset, cf, ct) { DateRanges.of(preset, customFrom = java.time.Instant.ofEpochMilli(cf).atZone(Fmt.DHAKA).toLocalDate(), customTo = java.time.Instant.ofEpochMilli(ct).atZone(Fmt.DHAKA).toLocalDate()) }
    val ver by repo.version.collectAsState()
    var table by remember { mutableStateOf<ReportTable?>(null) }
    var summaryDoc by remember { mutableStateOf<DocSpec?>(null) }
    LaunchedEffect(type, range, ver) {
        table = null
        val r = Reports(repo.dao)
        table = when (type) {
            "sales" -> r.sales(range); "purchases" -> r.purchases(range); "expenses" -> r.expenses(range); "pnl" -> r.pnl(range)
            "custDue" -> r.dues("C", range, repo.dao.partiesOnce("C")); "supDue" -> r.dues("S", range, repo.dao.partiesOnce("S"))
            "stock" -> r.stock(range); else -> r.cashFlow(range, profile.openingCash)
        }
    }
    Screen(reportList.first { it.first == type }.second, nav.back, snack) {
        Scroll {
            Chips(Preset.values().map { it.label }, preset.label) { l -> preset = Preset.values().first { it.label == l } }
            if (preset == Preset.CUSTOM) { DateField("শুরু", cf) { cf = it }; DateField("শেষ", ct) { ct = it } }
            Text("সময়: " + range.label, fontWeight = FontWeight.SemiBold)
            val t = table ?: return@Scroll Box(Modifier.fillMaxWidth().padding(32.dp)) { CircularProgressIndicator() }
            if (t.summary.isNotEmpty()) Section("সারাংশ") { t.summary.forEach { (k, v) -> Row { Text(k, Modifier.weight(1f)); Text(v.text, fontWeight = FontWeight.SemiBold) } } }
            if (t.rows.isEmpty()) Text("এই সময়ে কোনো তথ্য নেই")
            else Column(Modifier.horizontalScroll(rememberScrollState())) {
                Row { t.headers.forEach { Text(it, Modifier.width(118.dp).padding(4.dp), fontWeight = FontWeight.Bold, fontSize = 13.sp) } }
                HorizontalDivider()
                t.rows.forEach { r -> Row { r.forEach { Text(it.text, Modifier.width(118.dp).padding(4.dp), fontSize = 13.sp) } }; HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)) }
            }
            t.notes.forEach { Text("ℹ " + it, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            val fname = "hisab-$type-" + Fmt.en(range.label).replace(Regex("[^A-Za-z0-9]+"), "-").trim('-').ifBlank { "report" }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button({ val u = Files.cacheFile(c, "$fname.csv", Csv.bytes(t)); if (!Files.shareFiles(c, listOf(u), "text/csv", subject = t.title)) toast(c, "শেয়ার করা যায়নি") }) { Text("CSV শেয়ার") }
                OutlinedButton({
                    runCatching { Files.saveToDownloads(c, "$fname.csv", "text/csv", Csv.bytes(t)) }
                        .onSuccess { toast(c, if (it.isEmpty()) "সংরক্ষণ ব্যর্থ" else "সংরক্ষিত: $it") }.onFailure { toast(c, "সংরক্ষণ ব্যর্থ") }
                }) { Text("CSV সংরক্ষণ") }
                OutlinedButton({
                    summaryDoc = DocSpec(profile.name, emptyList(), Img.decode(profile.logo), t.title, listOf("সময়" to range.label), emptyList(), t.title,
                        listOf(Col("বিবরণ", 3f), Col("মান", 2f, true)), t.summary.map { listOf(it.first, it.second.text) }, emptyList(), t.notes.joinToString("\n"), profile.accent, "পাতা")
                }) { Text("সারাংশের ছবি") }
            }
        }
    }
    summaryDoc?.let { RenderedDoc(it, "রিপোর্ট সারাংশ", it.title + " (" + range.label + ")", "hisab-report-$type", profile.png) { summaryDoc = null } }
}

// ---------------------------------------------------------------------------------------------- profile
@Composable
fun ProfileForm(repo: Repo, p: Profile, first: Boolean, onDone: () -> Unit) {
    val c = LocalContext.current
    val scope = rememberCoroutineScope()
    val snack = remember { SnackbarHostState() }
    var name by remember { mutableStateOf(p.name) }; var owner by remember { mutableStateOf(p.owner.ifBlank { Auth.displayName() }) }
    var phone by remember { mutableStateOf(p.phone) }; var addr by remember { mutableStateOf(p.address) }
    var type by remember { mutableStateOf(p.type) }; var cash by remember { mutableStateOf(if (p.openingCash == 0L) "" else Fmt.plain(p.openingCash)) }
    var logo by remember { mutableStateOf(p.logo) }; var saving by remember { mutableStateOf(false) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) scope.launch { Img.encode(c, uri, 240, 85)?.let { logo = it } ?: snack.showSnackbar("ছবি পড়া যায়নি") }
    }
    val cashV = if (cash.isBlank()) 0L else Fmt.parseMoney(cash)
    Screen(if (first) "আপনার ব্যবসার তথ্য" else "ব্যবসার প্রোফাইল", if (first) null else onDone, snack) {
        Scroll {
            if (first) Text("শুরু করতে ব্যবসার নাম দিন। পরে সেটিংস থেকে বদলাতে পারবেন।")
            Field("ব্যবসার নাম *", name, { name = it }); Field("মালিকের নাম", owner, { owner = it })
            Field("মোবাইল নম্বর", phone, { phone = it }, keyboard = KeyboardType.Phone); Field("দোকানের ঠিকানা", addr, { addr = it })
            Field("ব্যবসার ধরন (যেমন: পাইকারি মুদি)", type, { type = it })
            NumField("প্রাথমিক ক্যাশ ব্যালেন্স ৳ (ঐচ্ছিক)", cash, { cash = it }, error = if (cashV == null) "ভুল" else null)
            Text("মুদ্রা: বাংলাদেশি টাকা (৳)", fontSize = 13.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Img.decode(logo)?.let { Image(it.asImageBitmap(), null, Modifier.size(64.dp)) }
                OutlinedButton({ pick.launch("image/*") }) { Text(if (logo.isBlank()) "লোগো যোগ করুন" else "লোগো বদলান") }
                if (logo.isNotBlank()) TextButton({ logo = "" }) { Text("সরান") }
            }
            Button({
                saving = true
                scope.launch {
                    repo.putSetting("biz.owner", owner.trim()); repo.putSetting("biz.phone", Fmt.en(phone).trim()); repo.putSetting("biz.address", addr.trim())
                    repo.putSetting("biz.type", type.trim()); repo.putSetting("biz.openingCash", (cashV ?: 0).toString()); repo.putSetting("biz.logo", logo)
                    repo.putSetting("biz.name", name.trim())
                    onDone()
                }
            }, enabled = name.isNotBlank() && cashV != null && !saving, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text(if (first) "শুরু করুন" else "সংরক্ষণ করুন") }
        }
    }
}

// ---------------------------------------------------------------------------------------------- settings
private val accents = listOf("সবুজ" to 0xFF0B6E4F, "নীল" to 0xFF1565C0, "বেগুনি" to 0xFF6A1B9A, "লাল" to 0xFFB71C1C, "কমলা" to 0xFFE65100)

@Composable
fun SettingsScreen(repo: Repo, p: Profile, theme: String, setTheme: (String) -> Unit, nav: Nav) {
    val c = LocalContext.current
    val scope = rememberCoroutineScope()
    val snack = remember { SnackbarHostState() }
    var editProfile by remember { mutableStateOf(false) }
    if (editProfile) { ProfileForm(repo, p, false) { editProfile = false }; return }
    val pending by remember { repo.dao.pendingCount() }.collectAsState(initial = 0)
    var methods by remember(p.methods) { mutableStateOf(p.methods.joinToString(", ")) }
    var units by remember(p.units) { mutableStateOf(p.units.joinToString(", ")) }
    var footer by remember(p.footer) { mutableStateOf(p.footer) }
    var lockOn by remember { mutableStateOf(AppLock.enabled(c)) }
    var pinDialog by remember { mutableStateOf(false) }
    var logoutStep by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var restore by remember { mutableStateOf<Backup.Parsed?>(null) }
    var backupDone by remember { mutableStateOf<String?>(null) }
    val notif = remember { mutableStateMapOf<String, Boolean>().apply { Notifier.categories.forEach { put(it.first, Notifier.enabled(c, it.first)) } } }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            val text = withContext(Dispatchers.IO) { runCatching { c.contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.readText() }.getOrNull() }
            if (text == null) snack.showSnackbar("ফাইল পড়া যায়নি") else {
                val (parsed, err) = Backup.parse(text)
                if (parsed == null) snack.showSnackbar(err) else restore = parsed
            }
        }
    }
    fun list(s: String) = s.split(",", "|", "،").map { it.trim() }.filter { it.isNotEmpty() }.distinct()

    Screen("সেটিংস", nav.back, snack) {
        Scroll {
            Section("অ্যাকাউন্ট") {
                Text(Auth.displayName().ifBlank { "—" } + "\n" + Auth.email())
                Text(if (pending == 0) "☁ সব তথ্য ক্লাউডে সিঙ্ক হয়েছে" else "☁ ${Fmt.bn(pending.toString())} টি পরিবর্তন আপলোডের অপেক্ষায়", color = if (pending == 0) Sem.profit else Sem.due)
                OutlinedButton({ Sync.kick(c); toast(c, "সিঙ্ক শুরু হয়েছে (ইন্টারনেট লাগবে)") }) { Text("এখনই সিঙ্ক করুন") }
            }
            Section("ব্যবসা") {
                Text(p.name + (if (p.owner.isNotBlank()) " • " + p.owner else "")); Text(listOf(p.address, Fmt.bn(p.phone)).filter { it.isNotBlank() }.joinToString(" • "), fontSize = 13.sp)
                OutlinedButton({ editProfile = true }) { Text("ব্যবসার প্রোফাইল সম্পাদনা") }
            }
            Section("বিলের ছবি") {
                Text("থিমের রং"); Chips(accents.map { it.first }, accents.firstOrNull { it.second.toInt() == p.accent }?.first ?: "") { n ->
                    scope.launch { repo.putSetting("accent", accents.first { it.first == n }.second.toInt().toString()) } }
                Text("ছবির ফরম্যাট (JPG ছোট সাইজ, PNG বেশি ধারালো)"); Chips(listOf("JPG", "PNG"), if (p.png) "PNG" else "JPG") { scope.launch { repo.putSetting("inv.png", if (it == "PNG") "1" else "0") } }
                Field("বিলের নিচের লেখা (শর্ত/ধন্যবাদ)", footer, { footer = it }, singleLine = false)
                OutlinedButton({ scope.launch { repo.putSetting("inv.footer", footer.trim()); snack.showSnackbar("সংরক্ষিত") } }) { Text("সংরক্ষণ") }
            }
            Section("পেমেন্ট পদ্ধতি ও একক") {
                Field("পেমেন্ট পদ্ধতি (কমা দিয়ে)", methods, { methods = it }, singleLine = false)
                Field("পণ্যের একক (কমা দিয়ে)", units, { units = it }, singleLine = false)
                OutlinedButton({
                    val m = list(methods); val u = list(units)
                    if (m.isEmpty() || u.isEmpty()) toast(c, "খালি রাখা যাবে না") else scope.launch {
                        repo.putSetting("methods", m.joinToString("|")); repo.putSetting("units", u.joinToString("|")); snack.showSnackbar("সংরক্ষিত") }
                }) { Text("সংরক্ষণ") }
                Pick("ডিফল্ট পেমেন্ট পদ্ধতি", p.defMethod, p.methods) { scope.launch { repo.putSetting("defMethod", it) } }
                Text("নোট: ক্যাশ ব্যালেন্স হিসাব হয় \"$CASH\" নামের পদ্ধতি থেকে।", fontSize = 12.sp)
            }
            Section("স্টক") {
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) { Text("নেগেটিভ স্টকে বিক্রি অনুমোদন"); Text("বন্ধ থাকলে স্টকের বেশি বিক্রি আটকে যাবে", fontSize = 12.sp) }
                    Switch(p.allowNegative, { scope.launch { repo.putSetting("allowNegative", if (it) "1" else "0") } })
                }
            }
            Section("থিম") { Chips(listOf("সিস্টেম", "লাইট", "ডার্ক"), when (theme) { "light" -> "লাইট"; "dark" -> "ডার্ক"; else -> "সিস্টেম" }) { setTheme(when (it) { "লাইট" -> "light"; "ডার্ক" -> "dark"; else -> "system" }) } }
            Section("অ্যাপ লক (পিন)") {
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Text("পিন দিয়ে অ্যাপ লক", Modifier.weight(1f))
                    Switch(lockOn, { if (it) pinDialog = true else { AppLock.disable(c); lockOn = false } })
                }
                Text("Google লগইন আলাদা থাকে। পিন ভুলে গেলে লগআউট করে আবার লগইন করুন।", fontSize = 12.sp)
            }
            Section("নোটিফিকেশন") {
                Notifier.categories.forEach { (k, l) ->
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Text(l, Modifier.weight(1f))
                        Switch(notif[k] == true, { v ->
                            if (v && Build.VERSION.SDK_INT >= 33) permLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                            Notifier.setEnabled(c, k, v); notif[k] = v
                        })
                    }
                }
                Text("প্রতিদিন রাত ৮টার দিকে অ্যাপ ফোনেই হিসাব দেখে জানায়। কাস্টমারকে কোনো মেসেজ নিজে থেকে যায় না।", fontSize = 12.sp)
            }
            Section("ব্যাকআপ ও রিস্টোর") {
                Text("ব্যাকআপ ফাইলে আপনার সব কাস্টমার, পাওনা ও লেনদেন থাকে। ফাইলটি নিরাপদ জায়গায় রাখুন, অপরিচিত কাউকে দেবেন না।", fontSize = 12.sp)
                Button({
                    scope.launch {
                        val txt = Backup.export(repo)
                        val name = "hisab-backup-" + java.time.LocalDate.now(Fmt.DHAKA) + ".json"
                        val path = withContext(Dispatchers.IO) { runCatching { Files.saveToDownloads(c, name, "application/json", txt.toByteArray(Charsets.UTF_8)) }.getOrDefault("") }
                        if (path.isNotEmpty()) { Notifier.markBackup(c); backupDone = path } else snack.showSnackbar("ব্যাকআপ সংরক্ষণ করা যায়নি")
                    }
                }, Modifier.fillMaxWidth()) { Text("ব্যাকআপ নিন") }
                OutlinedButton({ open.launch(arrayOf("application/json", "text/plain", "*/*")) }, Modifier.fillMaxWidth()) { Text("ব্যাকআপ থেকে রিস্টোর") }
            }
            Section("আরও") {
                OutlinedButton({ nav.go(Route.Audit) }, Modifier.fillMaxWidth()) { Text("অডিট লগ (সংশোধনের ইতিহাস)") }
                Text("হিসাববন্ধু ১.০.০\nআপনার তথ্য ফোনে ও আপনার নিজের Firebase প্রজেক্টে থাকে।", fontSize = 12.sp)
            }
            Button({ logoutStep = 1 }, Modifier.fillMaxWidth().height(52.dp), colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) { Text("লগআউট") }
        }
    }

    if (pinDialog) PinDialog({ pinDialog = false }) { pin -> AppLock.setPin(c, pin); lockOn = true; pinDialog = false }
    backupDone?.let { Info("ব্যাকআপ সংরক্ষিত", "ফাইল: $it\n\nএতে সংবেদনশীল ব্যবসায়িক তথ্য আছে। নিরাপদে রাখুন।") { backupDone = null } }
    restore?.let { r ->
        val other = r.uid.isNotBlank() && r.uid != repo.uid
        Confirm("রিস্টোর নিশ্চিত করুন",
            "ফাইলের তারিখ: ${Fmt.dateTime(r.exportedAt)}\nকাস্টমার/সাপ্লায়ার ${Fmt.bn(r.counts["parties"].toString())}, পণ্য ${Fmt.bn(r.counts["products"].toString())}, বিল/ক্রয় ${Fmt.bn(r.counts["txns"].toString())}, পেমেন্ট ${Fmt.bn(r.counts["payments"].toString())}, খরচ ${Fmt.bn(r.counts["expenses"].toString())}।\n\n" +
                "যা আগে থেকেই আছে তা দ্বিতীয়বার যোগ হবে না; নতুন রেকর্ড যোগ হবে এবং আগের আর্থিক রেকর্ড বদলাবে না।" +
                if (other) "\n\n⚠ এই ব্যাকআপ অন্য একটি অ্যাকাউন্টের। রিস্টোর করলে তথ্য আপনার বর্তমান অ্যাকাউন্টে যুক্ত হবে।" else "",
            "রিস্টোর করুন", { restore = null; scope.launch { runCatching { Backup.restore(repo, r) }.onSuccess { snack.showSnackbar("${Fmt.bn(it.toString())} টি রেকর্ড যুক্ত/আপডেট হয়েছে") }.onFailure { snack.showSnackbar("রিস্টোর ব্যর্থ, কোনো পরিবর্তন হয়নি") } } }, { restore = null })
    }
    if (logoutStep == 1) Confirm("লগআউট", "লগআউট করলে এই ফোন থেকে আপনার ব্যবসার সংরক্ষিত তথ্য মুছে যাবে (ক্লাউডে থাকবে)। আগে সিঙ্ক চেক করা হবে।", "লগআউট",
        { logoutStep = 0; busy = true; scope.launch { val left = Graph.finalSync(c); busy = false; if (left > 0) logoutStep = 2 else Graph.logout(c) } }, { logoutStep = 0 })
    if (logoutStep == 2) Confirm("সতর্কতা", "এখনো আপলোড হয়নি এমন কিছু পরিবর্তন আছে। এখন লগআউট করলে সেগুলো হারিয়ে যাবে। ইন্টারনেট চালু করে আবার চেষ্টা করুন, অথবা আগে ব্যাকআপ নিন।", "তবুও লগআউট",
        { logoutStep = 0; scope.launch { Graph.logout(c) } }, { logoutStep = 0 })
    if (busy) AlertDialog({}, confirmButton = {}, title = { Text("সিঙ্ক হচ্ছে…") }, text = { LinearProgressIndicator(Modifier.fillMaxWidth()) })
}

@Composable
private fun PinDialog(onClose: () -> Unit, onOk: (String) -> Unit) {
    var a by remember { mutableStateOf("") }; var b by remember { mutableStateOf("") }
    val kb = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.NumberPassword)
    AlertDialog(onDismissRequest = onClose, title = { Text("নতুন পিন") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(a, { if (it.length <= 8 && it.all(Char::isDigit)) a = it }, label = { Text("পিন (৪–৮ সংখ্যা)") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), keyboardOptions = kb)
                OutlinedTextField(b, { if (it.length <= 8 && it.all(Char::isDigit)) b = it }, label = { Text("আবার দিন") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), keyboardOptions = kb)
            }
        },
        confirmButton = { TextButton({ onOk(a) }, enabled = a.length >= 4 && a == b) { Text("চালু করুন") } },
        dismissButton = { TextButton(onClose) { Text("বাতিল") } })
}
