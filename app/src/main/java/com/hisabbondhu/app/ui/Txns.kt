@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.hisabbondhu.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hisabbondhu.app.core.*
import com.hisabbondhu.app.data.*
import com.hisabbondhu.app.render.Docs
import kotlinx.coroutines.launch

// ---------------------------------------------------------------------------------------------- sales list
@Composable
fun SalesTab(repo: Repo, nav: Nav) {
    var q by remember { mutableStateOf("") }
    val list by repo.dao.txns("SALE", "%" + Fmt.en(q).trim() + "%", 150).collectAsState(initial = null)
    val paid by remember { repo.dao.settings() }.collectAsState(initial = emptyList()) // refresh trigger only
    var paidBy by remember { mutableStateOf<Map<String, Long>>(emptyMap()) }
    val ver by repo.version.collectAsState()
    LaunchedEffect(ver) { paidBy = repo.dao.allPayments().groupBy { it.refId }.mapValues { e -> e.value.sumOf { it.amount } } }
    Screen("বিক্রয়", null, fab = { ExtendedFloatingActionButton({ nav.go(Route.NewTxn("SALE")) }) { Text("+ নতুন বিক্রয়") } }) {
        Field("খোঁজ (বিল নম্বর, কাস্টমার, মোবাইল)", q, { q = it }, Modifier.fillMaxWidth().padding(12.dp))
        val l = list ?: return@Screen Loading()
        if (l.isEmpty()) Empty(if (q.isBlank()) "এখনো কোনো বিক্রয় নেই" else "কিছু পাওয়া যায়নি")
        else LazyColumn(contentPadding = PaddingValues(bottom = 90.dp)) {
            items(l, key = { it.id }) { t ->
                val due = t.total - (paidBy[t.id] ?: t.paid)
                Item(t.number + if (t.status != "ACTIVE") " (বাতিল)" else "", t.partyName.ifBlank { "খুচরা" } + " • " + Fmt.dateTime(t.date),
                    Fmt.money(t.total), if (t.status == "ACTIVE" && due > 0) "বকেয়া " + Fmt.money(due) else "",
                    if (t.status != "ACTIVE") Sem.neutral else Sem.sale) { nav.go(Route.TxnDetail(t.id)) }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------- create sale / purchase
data class LineUi(val productId: String, val name: String, val unit: String, val qty: String, val price: String, val disc: String,
                  val retail: Long, val wholesale: Long)

@Composable
fun NewTxnScreen(repo: Repo, kind: String, profile: Profile, nav: Nav) {
    val sale = kind == "SALE"
    val scope = rememberCoroutineScope()
    val snack = remember { SnackbarHostState() }
    val txnId = remember { repo.newId() }
    val parties by remember(sale) { repo.dao.parties(if (sale) "C" else "S") }.collectAsState(initial = emptyList())
    val products by remember { repo.dao.productRows() }.collectAsState(initial = emptyList())
    val stockOf = remember(products) { products.associate { it.p.id to it.stock } }
    var party by remember { mutableStateOf<Party?>(null) }
    val bal by remember(party?.id) { party?.id?.let { repo.dao.balanceFlow(it) } ?: kotlinx.coroutines.flow.flowOf<Long?>(0L) }.collectAsState(initial = 0L)
    val lines = remember { mutableStateListOf<LineUi>() }
    var invDisc by remember { mutableStateOf("") }; var extra by remember { mutableStateOf("") }
    var paid by remember { mutableStateOf("") }; var paidTouched by remember { mutableStateOf(false) }
    var method by remember { mutableStateOf(profile.defMethod) }
    var date by remember { mutableLongStateOf(now()) }; var note by remember { mutableStateOf("") }
    var pickParty by remember { mutableStateOf(false) }; var pickProd by remember { mutableStateOf(false) }
    var quickParty by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var saved by remember { mutableStateOf<Triple<Txn, List<TxnItem>, Long>?>(null) }

    // ---- parse & compute (all money in paisa)
    val parsed = lines.map { Triple(Fmt.parseQty(it.qty), Fmt.parseMoney(it.price), if (it.disc.isBlank()) 0L else Fmt.parseMoney(it.disc)) }
    val linesOk = lines.isNotEmpty() && parsed.all { (q, p, d) -> q != null && q > 0 && p != null && d != null }
    val calc = if (linesOk) lines.indices.map { i -> Calc.Line(parsed[i].first!!, parsed[i].second!!, parsed[i].third!!) } else emptyList()
    val invDiscV = if (invDisc.isBlank()) 0L else Fmt.parseMoney(invDisc)
    val extraV = if (extra.isBlank()) 0L else Fmt.parseMoney(extra)
    val totals = if (linesOk && invDiscV != null && extraV != null) Calc.totals(calc, invDiscV, extraV) else null
    val total = totals?.total ?: 0L
    val paidV = if (!paidTouched) total else if (paid.isBlank()) 0L else Fmt.parseMoney(paid)
    val paidErr = when { paidV == null -> "সঠিক সংখ্যা দিন"; paidV > total -> "মোট বিলের বেশি হতে পারে না"; else -> null }
    val due = total - (paidV ?: 0)
    val lineErr = !linesOk && lines.isNotEmpty()
    val canSave = totals != null && paidV != null && paidErr == null && !saving &&
        (party != null || (sale && due == 0L))

    fun save() {
        saving = true
        scope.launch {
            val dl = lines.mapIndexed { i, l -> DraftLine(l.productId, l.name, l.unit, parsed[i].first!!, parsed[i].second!!, parsed[i].third!!) }
            runCatching {
                repo.saveTxn(txnId, kind, party?.id ?: "", party?.name ?: "", party?.phone ?: "", dl, invDiscV ?: 0, extraV ?: 0, paidV ?: 0, method, note.trim(), date)
            }.onSuccess { t -> saved = Triple(t, repo.dao.items(t.id), repo.balanceOf(t.partyId)) }
                .onFailure {
                    saving = false
                    snack.showSnackbar(if (it is StockException) "স্টক অপর্যাপ্ত: ${it.product} (আছে ${Fmt.qty(it.available)})" else it.message ?: "সংরক্ষণ করা যায়নি")
                }
        }
    }

    Screen(if (sale) "নতুন বিক্রয়" else "নতুন ক্রয়", nav.back, snack) {
        Scroll {
            Section(if (sale) "কাস্টমার" else "সাপ্লায়ার") {
                OutlinedButton({ pickParty = true }, Modifier.fillMaxWidth()) {
                    Text(party?.name ?: if (sale) "খুচরা (নগদ) কাস্টমার — নির্বাচন করতে চাপুন" else "সাপ্লায়ার নির্বাচন করুন", Modifier.weight(1f)); Text("▾")
                }
                if (party != null) {
                    Text("বর্তমান " + balText(party!!.kind, bal ?: 0), color = Sem.due)
                    TextButton({ party = null }) { Text("খুচরা কাস্টমার করুন") }
                }
            }
            Section("পণ্য") {
                if (lines.isEmpty()) Text("কোনো পণ্য যোগ করা হয়নি")
                lines.forEachIndexed { i, l ->
                    val q = parsed[i].first
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            Text(l.name, Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                            TextButton({ lines.removeAt(i) }) { Text("সরান", color = MaterialTheme.colorScheme.error) }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            NumField("পরিমাণ (${l.unit})", l.qty, { lines[i] = l.copy(qty = it) }, Modifier.weight(1f), error = if (q == null || q <= 0) "দিন" else null)
                            NumField(if (sale) "দর ৳" else "ক্রয়মূল্য ৳", l.price, { lines[i] = l.copy(price = it) }, Modifier.weight(1f), error = if (parsed[i].second == null) "ভুল" else null)
                            NumField("ছাড় ৳", l.disc, { lines[i] = l.copy(disc = it) }, Modifier.weight(0.8f), error = if (parsed[i].third == null) "ভুল" else null)
                        }
                        if (sale && l.wholesale > 0) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            AssistChip({ lines[i] = l.copy(price = Fmt.plain(l.retail)) }, { Text("খুচরা " + Fmt.money(l.retail)) })
                            AssistChip({ lines[i] = l.copy(price = Fmt.plain(l.wholesale)) }, { Text("পাইকারি " + Fmt.money(l.wholesale)) })
                        }
                        if (sale && q != null && q > (stockOf[l.productId] ?: 0)) Text("⚠ স্টকে আছে মাত্র " + Fmt.qty(stockOf[l.productId] ?: 0) + " " + l.unit, color = Sem.due, fontSize = 13.sp)
                        if (q != null && parsed[i].second != null) Text("লাইন মোট: " + Fmt.money(Calc.lineNet(calc.getOrNull(i) ?: Calc.Line(q, parsed[i].second!!))), fontSize = 13.sp)
                    }
                    HorizontalDivider()
                }
                Button({ pickProd = true }, Modifier.fillMaxWidth()) { Text("+ পণ্য যোগ করুন") }
            }
            Section("হিসাব") {
                NumField("বিলের উপর ছাড় ৳", invDisc, { invDisc = it }, error = if (invDiscV == null) "ভুল" else null)
                NumField("অতিরিক্ত চার্জ ৳", extra, { extra = it }, error = if (extraV == null) "ভুল" else null)
                if (totals != null) {
                    Row { Text("সাবটোটাল", Modifier.weight(1f)); Text(Fmt.money(totals.subtotal)) }
                    if (totals.itemDiscount + totals.invoiceDiscount > 0) Row { Text("মোট ছাড়", Modifier.weight(1f)); Text("-" + Fmt.money(totals.itemDiscount + totals.invoiceDiscount)) }
                    if (totals.extra > 0) Row { Text("অতিরিক্ত চার্জ", Modifier.weight(1f)); Text(Fmt.money(totals.extra)) }
                    HorizontalDivider()
                    Row { Text("মোট", Modifier.weight(1f), fontWeight = FontWeight.Bold, fontSize = 18.sp); Text(Fmt.money(total), fontWeight = FontWeight.Bold, fontSize = 18.sp, color = Sem.sale) }
                } else if (lineErr) Text("পণ্যের তথ্য ঠিক করুন", color = MaterialTheme.colorScheme.error)
                NumField(if (sale) "এখন পরিশোধ ৳" else "এখন পরিশোধ করছি ৳", if (paidTouched) paid else Fmt.plain(total), { paid = it; paidTouched = true }, error = paidErr)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AssistChip({ paidTouched = true; paid = Fmt.plain(total) }, { Text("পুরো টাকা") })
                    AssistChip({ paidTouched = true; paid = "0" }, { Text("সম্পূর্ণ বাকি") })
                }
                Row { Text("এই বিলের বকেয়া", Modifier.weight(1f)); Text(Fmt.money(due), color = if (due > 0) Sem.due else Sem.paid, fontWeight = FontWeight.SemiBold) }
                if (party != null) Row { Text("লেনদেনের পর মোট " + if (sale) "বকেয়া" else "পাওনা", Modifier.weight(1f)); Text(Fmt.money((bal ?: 0) + due)) }
                Pick("পেমেন্ট পদ্ধতি", method, profile.methods) { method = it }
                DateField("তারিখ", date) { date = it }
                Field("নোট (ঐচ্ছিক)", note, { note = it })
                if (party == null && due > 0) Text(if (sale) "বাকিতে বিক্রি করতে কাস্টমার নির্বাচন করুন।" else "সাপ্লায়ার নির্বাচন করুন।", color = Sem.due)
            }
            Button({ save() }, enabled = canSave, modifier = Modifier.fillMaxWidth().height(56.dp)) { Text(if (sale) "বিল সংরক্ষণ করুন" else "ক্রয় সংরক্ষণ করুন", fontSize = 17.sp) }
        }
    }

    if (pickParty) PartyPicker(parties.filter { !it.archived }, if (sale) "কাস্টমার" else "সাপ্লায়ার", { pickParty = false },
        { party = it; pickParty = false }, { pickParty = false; quickParty = true })
    if (quickParty) QuickParty(repo, if (sale) "C" else "S", { quickParty = false }) { party = it; quickParty = false }
    if (pickProd) ProductPicker(products.filter { !it.p.archived }, sale, { pickProd = false }) { r ->
        pickProd = false
        val price = if (sale) r.p.sellPrice else r.p.buyPrice
        lines.add(LineUi(r.p.id, r.p.name, r.p.unit, "1", Fmt.plain(price), "", r.p.sellPrice, r.p.wholesalePrice))
    }
    saved?.let { (t, items, balNow) ->
        RenderedDoc(Docs.invoice(profile, t, items, balNow), if (sale) "বিল সংরক্ষিত হয়েছে" else "ক্রয় সংরক্ষিত হয়েছে", Docs.shareText(profile, t, balNow),
            "hisab-" + t.number, profile.png) { nav.replace(Route.TxnDetail(t.id)) }
    }
}

@Composable
private fun PartyPicker(list: List<Party>, label: String, onClose: () -> Unit, onPick: (Party) -> Unit, onNew: () -> Unit) {
    var q by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = onClose, title = { Text("$label নির্বাচন") }, confirmButton = { TextButton(onNew) { Text("+ নতুন") } },
        dismissButton = { TextButton(onClose) { Text("বাতিল") } },
        text = {
            Column {
                Field("খোঁজ", q, { q = it })
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    items(list.filter { q.isBlank() || it.name.contains(q, true) || it.phone.contains(Fmt.en(q)) }) { p ->
                        Item(p.name, Fmt.bn(p.phone)) { onPick(p) }
                    }
                }
            }
        })
}

@Composable
private fun QuickParty(repo: Repo, kind: String, onClose: () -> Unit, onDone: (Party) -> Unit) {
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }; var phone by remember { mutableStateOf("") }
    val id = remember { repo.newId() }
    AlertDialog(onDismissRequest = onClose, title = { Text(if (kind == "C") "নতুন কাস্টমার" else "নতুন সাপ্লায়ার") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { Field("নাম *", name, { name = it }); Field("মোবাইল", phone, { phone = it }, keyboard = androidx.compose.ui.text.input.KeyboardType.Phone) } },
        confirmButton = {
            TextButton({
                scope.launch { val t = now(); onDone(repo.saveParty(Party(id, kind, "", name.trim(), Fmt.en(phone).trim(), createdAt = t, updatedAt = t))) }
            }, enabled = name.isNotBlank()) { Text("সংরক্ষণ") }
        }, dismissButton = { TextButton(onClose) { Text("বাতিল") } })
}

@Composable
private fun ProductPicker(list: List<ProductRow>, sale: Boolean, onClose: () -> Unit, onPick: (ProductRow) -> Unit) {
    var q by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = onClose, title = { Text("পণ্য নির্বাচন") }, confirmButton = {}, dismissButton = { TextButton(onClose) { Text("বন্ধ") } },
        text = {
            Column {
                Field("খোঁজ (নাম, কোড, বারকোড)", q, { q = it })
                if (list.isEmpty()) Text("আগে স্টক ট্যাব থেকে পণ্য যোগ করুন")
                LazyColumn(Modifier.heightIn(max = 380.dp)) {
                    items(list.filter { q.isBlank() || it.p.name.contains(q, true) || it.p.code.contains(q, true) || it.p.barcode.contains(Fmt.en(q)) }) { r ->
                        Item(r.p.name, "স্টক " + Fmt.qty(r.stock) + " " + r.p.unit, Fmt.money(if (sale) r.p.sellPrice else r.p.buyPrice)) { onPick(r) }
                    }
                }
            }
        })
}

// ---------------------------------------------------------------------------------------------- detail
@Composable
fun TxnDetailScreen(repo: Repo, id: String, profile: Profile, nav: Nav) {
    val scope = rememberCoroutineScope()
    val snack = remember { SnackbarHostState() }
    val t by remember(id) { repo.dao.txnFlow(id) }.collectAsState(initial = null)
    val items by remember(id) { repo.dao.itemsFlow(id) }.collectAsState(initial = emptyList())
    val rets by remember(id) { repo.dao.retsOfTxnFlow(id) }.collectAsState(initial = emptyList())
    val paidNow by remember(id) { repo.dao.paidOnRef(id) }.collectAsState(initial = 0L)
    val bal by remember(t?.partyId) { t?.partyId?.takeIf { it.isNotBlank() }?.let { repo.dao.balanceFlow(it) } ?: kotlinx.coroutines.flow.flowOf<Long?>(0L) }.collectAsState(initial = 0L)
    var show by remember { mutableStateOf(false) }
    var cancel by remember { mutableStateOf(false) }
    var ret by remember { mutableStateOf(false) }
    val x = t ?: return Loading()
    val sale = x.kind == "SALE"
    val shown = x.copy(paid = paidNow)
    val due = x.total - paidNow

    Screen(x.number, nav.back, snack) {
        Scroll {
            if (x.status != "ACTIVE") Text("এই বিল বাতিল করা হয়েছে: " + x.cancelReason, color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
            Text((if (sale) "কাস্টমার: " else "সাপ্লায়ার: ") + x.partyName.ifBlank { "খুচরা" } + (if (x.partyPhone.isNotBlank()) " • " + Fmt.bn(x.partyPhone) else "") + "\n" + Fmt.dateTime(x.date))
            Section("পণ্য") {
                items.forEach { i ->
                    Row { Column(Modifier.weight(1f)) { Text(i.name); Text(Fmt.qty(i.qtyMilli) + " " + i.unit + " × " + Fmt.money(i.unitPrice) + if (i.discount > 0) " (ছাড় " + Fmt.money(i.discount) + ")" else "", fontSize = 13.sp) }; Text(Fmt.money(i.lineTotal)) }
                }
            }
            Section("হিসাব") {
                Row { Text("সাবটোটাল", Modifier.weight(1f)); Text(Fmt.money(x.subtotal)) }
                if (x.itemDiscount + x.discount > 0) Row { Text("মোট ছাড়", Modifier.weight(1f)); Text("-" + Fmt.money(x.itemDiscount + x.discount)) }
                if (x.extra > 0) Row { Text("অতিরিক্ত চার্জ", Modifier.weight(1f)); Text(Fmt.money(x.extra)) }
                Row { Text("মোট", Modifier.weight(1f), fontWeight = FontWeight.Bold); Text(Fmt.money(x.total), fontWeight = FontWeight.Bold) }
                Row { Text("এ পর্যন্ত পরিশোধ", Modifier.weight(1f)); Text(Fmt.money(paidNow), color = Sem.paid) }
                Row { Text("এই বিলের বকেয়া", Modifier.weight(1f)); Text(Fmt.money(due), color = if (due > 0) Sem.due else Sem.paid, fontWeight = FontWeight.Bold) }
                if (x.partyId.isNotBlank()) Row { Text("পার্টির মোট " + (if ((bal ?: 0) < 0) "অগ্রিম" else if (sale) "বকেয়া" else "পাওনা"), Modifier.weight(1f)); Text(Fmt.money(Math.abs(bal ?: 0))) }
                if (x.note.isNotBlank()) Text("নোট: " + x.note)
            }
            if (rets.isNotEmpty()) Section("ফেরত") { rets.forEach { r -> Row { Text(r.name + " × " + Fmt.qty(r.qtyMilli), Modifier.weight(1f)); Text(Fmt.money(r.amount)) } } }
            Button({ show = true }, Modifier.fillMaxWidth().height(52.dp)) { Text("বিলের ছবি দেখুন / WhatsApp-এ পাঠান") }
            if (x.status == "ACTIVE") {
                if (x.partyId.isNotBlank() && due > 0) OutlinedButton({ nav.go(Route.Pay(x.partyId, x.id)) }, Modifier.fillMaxWidth()) { Text(if (sale) "টাকা আদায়" else "টাকা পরিশোধ") }
                OutlinedButton({ ret = true }, Modifier.fillMaxWidth()) { Text(if (sale) "বিক্রয় ফেরত" else "ক্রয় ফেরত") }
                OutlinedButton({ cancel = true }, Modifier.fillMaxWidth()) { Text("বিল বাতিল করুন", color = MaterialTheme.colorScheme.error) }
            }
        }
    }
    if (show) RenderedDoc(Docs.invoice(profile, shown, items, bal ?: 0, rets), "বিলের ছবি", Docs.shareText(profile, shown, bal ?: 0), "hisab-" + x.number, profile.png) { show = false }
    if (cancel) ReasonDialog("বিল বাতিল", "স্টক ফেরত যাবে এবং বিলটি আর বিক্রয়ে গণ্য হবে না। রেকর্ড মুছবে না। যেকোনো আগের পেমেন্ট অগ্রিম হিসেবে থাকবে।", "বাতিল করুন",
        { r -> cancel = false; scope.launch { runCatching { repo.cancelTxn(id, r) }.onFailure { snack.showSnackbar(if (it is StockException) "স্টক অপর্যাপ্ত: ${it.product}" else it.message ?: "ব্যর্থ") } } }, { cancel = false })
    if (ret) ReturnDialog(items, rets, { ret = false }) { qty, reason ->
        ret = false
        scope.launch { runCatching { repo.returnItems(id, qty, reason) }.onFailure { snack.showSnackbar(if (it is StockException) "স্টক অপর্যাপ্ত: ${it.product}" else it.message ?: "ব্যর্থ") } }
    }
}

@Composable
private fun ReturnDialog(items: List<TxnItem>, rets: List<Ret>, onClose: () -> Unit, onOk: (Map<String, Long>, String) -> Unit) {
    val inputs = remember { mutableStateMapOf<String, String>() }
    var reason by remember { mutableStateOf("") }
    val parsed = items.associate { it.id to (if (inputs[it.id].isNullOrBlank()) 0L else Fmt.parseQty(inputs[it.id]!!)) }
    val remaining = items.associate { it.id to it.qtyMilli - rets.filter { r -> r.productId == it.productId }.sumOf { r -> r.qtyMilli } }
    val ok = parsed.values.all { it != null } && parsed.values.any { (it ?: 0) > 0 } && items.all { (parsed[it.id] ?: 0) <= (remaining[it.id] ?: 0) } && reason.isNotBlank()
    AlertDialog(onDismissRequest = onClose, title = { Text("ফেরত") },
        text = {
            Column(Modifier.heightIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items.forEach { i ->
                    NumField(i.name + " (ফেরতযোগ্য " + Fmt.qty(remaining[i.id] ?: 0) + " " + i.unit + ")", inputs[i.id] ?: "", { inputs[i.id] = it },
                        error = if ((parsed[i.id] ?: 0) > (remaining[i.id] ?: 0) || parsed[i.id] == null) "বেশি/ভুল" else null)
                }
                Field("কারণ (বাধ্যতামূলক)", reason, { reason = it })
            }
        },
        confirmButton = { TextButton({ onOk(parsed.mapValues { it.value ?: 0 }.filter { it.value > 0 }, reason.trim()) }, enabled = ok) { Text("ফেরত নিন") } },
        dismissButton = { TextButton(onClose) { Text("বাতিল") } })
}
