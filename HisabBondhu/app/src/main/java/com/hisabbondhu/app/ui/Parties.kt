@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.hisabbondhu.app.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.hisabbondhu.app.core.*
import com.hisabbondhu.app.data.*
import com.hisabbondhu.app.export.Files
import com.hisabbondhu.app.export.Reports
import com.hisabbondhu.app.render.DocRenderer
import com.hisabbondhu.app.render.DocSpec
import com.hisabbondhu.app.render.Docs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

fun balText(kind: String, b: Long) = when {
    b < 0 -> "অগ্রিম " + Fmt.money(-b)
    kind == "C" -> "বকেয়া " + Fmt.money(b)
    else -> "পাওনা " + Fmt.money(b)
}

/** Preview, save, share and WhatsApp actions for rendered image documents. */
@Composable
fun DocActions(title: String, bitmaps: List<Bitmap>, shareText: String, baseName: String, png: Boolean, onClose: () -> Unit) {
    val c = LocalContext.current
    AlertDialog(onDismissRequest = onClose, title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                bitmaps.forEach { b ->
                    Image(b.asImageBitmap(), null, Modifier.fillMaxWidth().aspectRatio(b.width.toFloat() / b.height), contentScale = ContentScale.FillWidth)
                }
                Text("WhatsApp খুলবে। ছবি ও বার্তা দেখে আপনি নিজে পাঠাতে হবে। অ্যাপ জানে না বার্তা পৌঁছাল কিনা।", style = MaterialTheme.typography.bodySmall)
                Button({
                    val uris = bitmaps.mapIndexed { i, b -> Files.cacheBitmap(c, "$baseName-${i + 1}", b, png) }
                    val r = Files.shareToWhatsApp(c, uris, shareText)
                    if (r.isEmpty()) toast(c, "WhatsApp পাওয়া যায়নি। অন্য অ্যাপ দিয়ে শেয়ার করুন")
                }, Modifier.fillMaxWidth()) { Text("WhatsApp-এ বিলের ছবি পাঠান") }
                OutlinedButton({
                    val uris = bitmaps.mapIndexed { i, b -> Files.cacheBitmap(c, "$baseName-${i + 1}", b, png) }
                    if (!Files.shareFiles(c, uris, "image/*", shareText)) toast(c, "শেয়ার করা যায়নি")
                }, Modifier.fillMaxWidth()) { Text("ছবি শেয়ার করুন") }
                OutlinedButton({
                    runCatching { bitmaps.forEachIndexed { i, b -> Files.saveImage(c, "$baseName-${i + 1}", b, png) } }
                        .onSuccess { toast(c, "গ্যালারিতে সংরক্ষিত: Pictures/HisabBondhu") }
                        .onFailure { toast(c, "সংরক্ষণ করা যায়নি") }
                }, Modifier.fillMaxWidth()) { Text("গ্যালারিতে সংরক্ষণ করুন") }
            }
        },
        confirmButton = { TextButton(onClose) { Text("বন্ধ করুন") } })
}

/** Renders off the main thread, then shows [DocActions]. */
@Composable
fun RenderedDoc(spec: DocSpec, title: String, shareText: String, baseName: String, png: Boolean, onClose: () -> Unit) {
    var bmps by remember(spec) { mutableStateOf<List<Bitmap>?>(null) }
    LaunchedEffect(spec) { bmps = withContext(Dispatchers.Default) { DocRenderer.render(spec) } }
    val b = bmps
    if (b == null) AlertDialog(onDismissRequest = onClose, confirmButton = {}, title = { Text("ছবি তৈরি হচ্ছে…") }, text = { LinearProgressIndicator(Modifier.fillMaxWidth()) })
    else DocActions(title, b, shareText, baseName, png, onClose)
}

// ---------------------------------------------------------------------------------------------- list
@Composable
fun PartyListScreen(repo: Repo, kind: String, nav: Nav) {
    val parties by remember(kind) { repo.dao.parties(kind) }.collectAsState(initial = null)
    val bals by remember(kind) { repo.dao.balances(kind) }.collectAsState(initial = emptyList())
    var q by remember { mutableStateOf("") }
    val bm = bals.associate { it.id to it.bal }
    val title = if (kind == "C") "কাস্টমার" else "সাপ্লায়ার"
    Screen(title, nav.back, fab = { ExtendedFloatingActionButton({ nav.go(Route.PartyForm(kind, null)) }) { Text("+ নতুন $title") } }) {
        Field("খোঁজ (নাম, মোবাইল, আইডি)", q, { q = it }, Modifier.fillMaxWidth().padding(12.dp))
        val list = parties ?: return@Screen Loading()
        val f = list.filter { q.isBlank() || it.name.contains(q, true) || it.phone.contains(Fmt.en(q)) || it.code.contains(q, true) }
        if (f.isEmpty()) Empty(if (list.isEmpty()) "এখনো কোনো $title নেই। নিচের বোতাম দিয়ে যোগ করুন" else "কিছু পাওয়া যায়নি")
        else LazyColumn(contentPadding = PaddingValues(bottom = 90.dp)) {
            items(f, key = { it.id }) { p ->
                val b = bm[p.id] ?: 0
                Item(p.name + if (p.archived) " (আর্কাইভ)" else "", listOf(p.code, Fmt.bn(p.phone)).filter { it.isNotBlank() }.joinToString(" • "),
                    balText(kind, b), "", if (b > 0) (if (kind == "C") Sem.due else Sem.expense) else Sem.paid) { nav.go(Route.PartyDetail(p.id)) }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------- form
@Composable
fun PartyFormScreen(repo: Repo, kind: String, id: String?, nav: Nav) {
    val scope = rememberCoroutineScope()
    val snack = remember { SnackbarHostState() }
    var old by remember { mutableStateOf<Party?>(null) }
    var name by remember { mutableStateOf("") }; var phone by remember { mutableStateOf("") }
    var addr by remember { mutableStateOf("") }; var biz by remember { mutableStateOf("") }
    var open by remember { mutableStateOf("") }; var note by remember { mutableStateOf("") }
    var loaded by remember { mutableStateOf(id == null) }
    var dup by remember { mutableStateOf<List<Party>>(emptyList()) }
    var saving by remember { mutableStateOf(false) }
    var tried by remember { mutableStateOf(false) }
    val newId = remember { repo.newId() }
    LaunchedEffect(id) {
        if (id != null) repo.dao.party(id)?.let { p ->
            old = p; name = p.name; phone = p.phone; addr = p.address; biz = p.business
            open = if (p.openingDue == 0L) "" else Fmt.plain(p.openingDue); note = p.note
        }
        loaded = true
    }
    val label = if (kind == "C") "কাস্টমার" else "সাপ্লায়ার"
    val phoneErr = if (tried && phone.isNotBlank() && Fmt.en(phone).filter { it.isDigit() }.length != 11) "১১ ডিজিটের মোবাইল নম্বর দিন" else null
    val openVal = if (open.isBlank()) 0L else Fmt.parseMoney(open)

    fun doSave() {
        saving = true
        scope.launch {
            val t = now(); val o = old
            runCatching {
                repo.saveParty(Party(o?.id ?: newId, kind, o?.code ?: "", name.trim(), Fmt.en(phone).trim(), addr.trim(), biz.trim(), openVal ?: 0, note.trim(),
                    o?.archived ?: false, o?.createdAt ?: t, t))
            }.onSuccess { nav.back() }.onFailure { snack.showSnackbar("সংরক্ষণ করা যায়নি"); saving = false }
        }
    }
    Screen(if (id == null) "নতুন $label" else "$label সম্পাদনা", nav.back, snack) {
        if (!loaded) return@Screen Loading()
        Scroll {
            Field("নাম *", name, { name = it }, error = if (tried && name.isBlank()) "নাম দিন" else null)
            Field("মোবাইল নম্বর", phone, { phone = it }, keyboard = androidx.compose.ui.text.input.KeyboardType.Phone, error = phoneErr)
            Field("ঠিকানা", addr, { addr = it })
            Field("ব্যবসার নাম (ঐচ্ছিক)", biz, { biz = it })
            NumField(if (kind == "C") "প্রাথমিক বকেয়া (৳)" else "প্রাথমিক পাওনা (৳)", open, { open = it },
                error = if (openVal == null) "সঠিক সংখ্যা দিন" else null)
            Field("নোট (ঐচ্ছিক)", note, { note = it }, singleLine = false)
            Button({
                tried = true
                if (name.isBlank() || phoneErr != null || openVal == null) return@Button
                scope.launch {
                    val d = repo.dao.dupes(kind, name.trim(), Fmt.en(phone).trim(), old?.id ?: "")
                    if (d.isNotEmpty()) dup = d else doSave()
                }
            }, enabled = !saving, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("সংরক্ষণ করুন") }
        }
        if (dup.isNotEmpty()) Confirm("সম্ভাব্য ডুপ্লিকেট", "একই নাম বা মোবাইল নম্বরে আগে থেকেই আছে: " + dup.joinToString { it.name } + "। তবুও সংরক্ষণ করবেন?",
            "তবুও সংরক্ষণ", { dup = emptyList(); doSave() }, { dup = emptyList() })
    }
}

// ---------------------------------------------------------------------------------------------- detail
@Composable
fun PartyDetailScreen(repo: Repo, id: String, profile: Profile, nav: Nav) {
    val c = LocalContext.current
    val scope = rememberCoroutineScope()
    val snack = remember { SnackbarHostState() }
    val p by remember(id) { repo.dao.partyFlow(id) }.collectAsState(initial = null)
    val bal by remember(id) { repo.dao.balanceFlow(id) }.collectAsState(initial = 0L)
    val txns by remember(id) { repo.dao.txnsOfParty(id) }.collectAsState(initial = emptyList())
    val pays by remember(id) { repo.dao.paymentsOfParty(id) }.collectAsState(initial = emptyList())
    var stmt by remember { mutableStateOf(false) }
    var doc by remember { mutableStateOf<Triple<DocSpec, String, String>?>(null) }
    var rev by remember { mutableStateOf<Payment?>(null) }
    var arch by remember { mutableStateOf(false) }
    val party = p ?: return Loading()
    val cust = party.kind == "C"

    Screen(party.name, nav.back, snack, actions = { TextButton({ nav.go(Route.PartyForm(party.kind, party.id)) }) { Text("সম্পাদনা") } }) {
        Scroll {
            Stat(if ((bal ?: 0L) < 0) "অগ্রিম / ক্রেডিট" else if (cust) "বর্তমান বকেয়া" else "বর্তমান পাওনা", Fmt.money(Math.abs(bal ?: 0)),
                if ((bal ?: 0) > 0) (if (cust) Sem.due else Sem.expense) else Sem.paid, Modifier.fillMaxWidth())
            Text(listOf(party.code, Fmt.bn(party.phone), party.address, party.business).filter { it.isNotBlank() }.joinToString("\n"))
            val sold = txns.filter { it.status == "ACTIVE" }.sumOf { it.total }
            val paid = pays.sumOf { it.amount }
            Text((if (cust) "মোট বিক্রয়: " else "মোট ক্রয়: ") + Fmt.money(sold) + "   " + (if (cust) "মোট আদায়: " else "মোট পরিশোধ: ") + Fmt.money(paid) +
                "\nপ্রাথমিক " + (if (cust) "বকেয়া: " else "পাওনা: ") + Fmt.money(party.openingDue))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button({ nav.go(Route.Pay(party.id)) }) { Text(if (cust) "টাকা আদায়" else "টাকা পরিশোধ") }
                if (cust) OutlinedButton({ nav.go(Route.NewTxn("SALE")) }) { Text("নতুন বিক্রয়") }
                OutlinedButton({ stmt = true }) { Text("হিসাব বিবরণী") }
                OutlinedButton({ arch = true }) { Text(if (party.archived) "আর্কাইভ থেকে ফেরান" else "আর্কাইভ") }
            }
            Section(if (cust) "বিলসমূহ" else "ক্রয়সমূহ") {
                if (txns.isEmpty()) Text("কোনো লেনদেন নেই")
                txns.forEach { t -> TextButton({ nav.go(Route.TxnDetail(t.id)) }, Modifier.fillMaxWidth()) {
                    Column(Modifier.weight(1f)) { Text(t.number + if (t.status != "ACTIVE") " (বাতিল)" else ""); Text(Fmt.date(t.date), style = MaterialTheme.typography.bodySmall) }
                    Text(Fmt.money(t.total)) } }
            }
            Section("পেমেন্ট ইতিহাস") {
                if (pays.isEmpty()) Text("কোনো পেমেন্ট নেই")
                pays.forEach { y ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(Fmt.date(y.date) + " • " + y.method + if (y.reversalOf.isNotBlank()) " (রিভার্স)" else "")
                            if (y.note.isNotBlank()) Text(y.note, style = MaterialTheme.typography.bodySmall)
                        }
                        Text(Fmt.money(y.amount), color = if (y.amount < 0) Sem.expense else Sem.paid)
                        if (y.amount > 0 && y.reversalOf.isBlank()) {
                            TextButton({ rev = y }) { Text("ভুল?") }
                            TextButton({
                                doc = Triple(Docs.receipt(profile, y, party.name, party.phone, bal ?: 0, !cust), "রসিদ", y.id)
                            }) { Text("রসিদ") }
                        }
                    }
                }
            }
        }
    }
    if (stmt) StatementDialog(onClose = { stmt = false }) { range ->
        stmt = false
        scope.launch {
            val t = Reports(repo.dao).statement(party, range)
            doc = Triple(Docs.statement(profile, t, party.name, party.phone), "হিসাব বিবরণী", "stmt-" + party.id.take(6))
        }
    }
    doc?.let { (spec, title, key) ->
        val text = if (title == "রসিদ") "${profile.name} থেকে রসিদ। ধন্যবাদ।" else "${profile.name}: ${party.name}-এর হিসাব বিবরণী (${spec.metaRight.firstOrNull()?.second.orEmpty()})"
        RenderedDoc(spec, title, text, "hisab-$key", profile.png) { doc = null }
    }
    rev?.let { y -> ReasonDialog("পেমেন্ট রিভার্স", "${Fmt.money(y.amount)} পেমেন্টটি মুছবে না; একটি রিভার্স এন্ট্রি যোগ হবে।", "রিভার্স করুন",
        { r -> rev = null; scope.launch { runCatching { repo.reversePayment(y.id, r) }.onFailure { snack.showSnackbar(it.message ?: "ব্যর্থ") } } }, { rev = null }) }
    if (arch) Confirm(if (party.archived) "আর্কাইভ থেকে ফেরান" else "আর্কাইভ", if (party.archived) "আবার তালিকায় দেখানো হবে।" else "লেনদেনের ইতিহাস থাকবে, শুধু নতুন তালিকা থেকে লুকানো হবে। মুছে ফেলা হবে না।",
        onYes = { arch = false; scope.launch { repo.archiveParty(party, !party.archived); nav.back() } }, onNo = { arch = false })
}

@Composable
fun StatementDialog(onClose: () -> Unit, onPick: (DateRange) -> Unit) {
    var preset by remember { mutableStateOf(Preset.LAST30) }
    var cf by remember { mutableLongStateOf(now()) }
    var ct by remember { mutableLongStateOf(now()) }
    AlertDialog(onDismissRequest = onClose, title = { Text("সময় নির্বাচন") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Chips(Preset.values().map { it.label }, preset.label) { l -> preset = Preset.values().first { it.label == l } }
                if (preset == Preset.CUSTOM) { DateField("শুরু", cf) { cf = it }; DateField("শেষ", ct) { ct = it } }
            }
        },
        confirmButton = {
            TextButton({
                onPick(DateRanges.of(preset, customFrom = java.time.Instant.ofEpochMilli(cf).atZone(Fmt.DHAKA).toLocalDate(),
                    customTo = java.time.Instant.ofEpochMilli(ct).atZone(Fmt.DHAKA).toLocalDate()))
            }) { Text("ছবি তৈরি করুন") }
        }, dismissButton = { TextButton(onClose) { Text("বাতিল") } })
}

// ---------------------------------------------------------------------------------------------- payment
@Composable
fun PayScreen(repo: Repo, partyId: String, refId: String, profile: Profile, nav: Nav) {
    val scope = rememberCoroutineScope()
    val snack = remember { SnackbarHostState() }
    val payId = remember { repo.newId() }
    val party by remember(partyId) { repo.dao.partyFlow(partyId) }.collectAsState(initial = null)
    val bal by remember(partyId) { repo.dao.balanceFlow(partyId) }.collectAsState(initial = 0L)
    val txns by remember(partyId) { repo.dao.txnsOfParty(partyId) }.collectAsState(initial = emptyList())
    var amount by remember { mutableStateOf("") }
    var method by remember { mutableStateOf(profile.defMethod) }
    var date by remember { mutableLongStateOf(now()) }
    var note by remember { mutableStateOf("") }
    var ref by remember { mutableStateOf(refId) }
    var over by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var done by remember { mutableStateOf<Payment?>(null) }
    val pr = party ?: return Loading()
    val cust = pr.kind == "C"
    val amt = Fmt.parseMoney(amount)
    var paidByRef by remember { mutableStateOf<Map<String, Long>>(emptyMap()) }
    val ver by repo.version.collectAsState()
    LaunchedEffect(ver, txns) { paidByRef = repo.dao.allPayments().groupBy { it.refId }.mapValues { e -> e.value.sumOf { it.amount } } }
    val open = txns.filter { it.status == "ACTIVE" && it.total - (paidByRef[it.id] ?: 0) > 0 }
    val dueOfRef = open.firstOrNull { it.id == ref }?.let { it.total - (paidByRef[it.id] ?: 0) }

    fun save() {
        saving = true
        scope.launch {
            runCatching { repo.addPayment(payId, if (cust) "IN" else "OUT", partyId, ref, amt ?: 0, method, date, note.trim()) }
                .onSuccess { done = it }.onFailure { snack.showSnackbar(it.message ?: "ব্যর্থ"); saving = false }
        }
    }
    Screen(if (cust) "টাকা আদায়" else "সাপ্লায়ারকে পরিশোধ", nav.back, snack) {
        Scroll {
            Text(pr.name, style = MaterialTheme.typography.titleMedium)
            Text(balText(pr.kind, bal ?: 0))
            NumField("টাকার পরিমাণ (৳)", amount, { amount = it }, error = if (amount.isNotBlank() && (amt == null || amt <= 0)) "সঠিক সংখ্যা দিন" else null)
            if (open.isNotEmpty()) Pick("নির্দিষ্ট বিল", open.firstOrNull { it.id == ref }?.let { it.number } ?: "সাধারণ জমা", listOf("সাধারণ জমা") + open.map { it.number }) { n ->
                ref = open.firstOrNull { it.number == n }?.id ?: ""
            }
            dueOfRef?.let { Text("এই বিলের বকেয়া: " + Fmt.money(it)) }
            Pick("পদ্ধতি", method, profile.methods) { method = it }
            DateField("তারিখ", date) { date = it }
            Field("নোট (ঐচ্ছিক)", note, { note = it })
            Button({
                val a = amt ?: return@Button
                val limit = dueOfRef ?: (bal ?: 0).coerceAtLeast(0)
                if (a > limit) over = true else save()
            }, enabled = !saving && amt != null && amt > 0, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("সংরক্ষণ করুন") }
        }
    }
    if (over) Confirm("পরিমাণ বেশি", "টাকা বকেয়ার চেয়ে বেশি। অতিরিক্ত অংশ অগ্রিম/ক্রেডিট হিসেবে জমা থাকবে। নিশ্চিত?", "হ্যাঁ, অগ্রিম হিসেবে রাখুন", { over = false; save() }, { over = false })
    done?.let { y ->
        val b = bal ?: 0
        RenderedDoc(Docs.receipt(profile, y, pr.name, pr.phone, b, !cust), "পেমেন্ট সংরক্ষিত হয়েছে", "${profile.name} থেকে রসিদ। ধন্যবাদ।", "hisab-${y.id.take(6)}", profile.png) { nav.back() }
    }
}
