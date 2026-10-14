@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.hisabbondhu.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.hisabbondhu.app.core.*
import com.hisabbondhu.app.data.*
import com.hisabbondhu.app.render.Img
import kotlinx.coroutines.launch

val moveLabel = mapOf("OPENING" to "প্রারম্ভিক স্টক", "PURCHASE" to "ক্রয়", "SALE" to "বিক্রয়", "SALE_RETURN" to "বিক্রয় ফেরত",
    "PURCHASE_RETURN" to "ক্রয় ফেরত", "CANCEL" to "বিল বাতিল", "DAMAGE" to "ক্ষতি/নষ্ট", "ADJUST" to "সমন্বয়")

@Composable
fun StockTab(repo: Repo, nav: Nav) {
    val rows by remember { repo.dao.productRows() }.collectAsState(initial = null)
    var q by remember { mutableStateOf("") }
    var lowOnly by remember { mutableStateOf(false) }
    Screen("স্টক", null, fab = { ExtendedFloatingActionButton({ nav.go(Route.ProductForm(null)) }) { Text("+ নতুন পণ্য") } }) {
        val all = rows ?: return@Screen Loading()
        val live = all.filter { !it.p.archived }
        val value = live.sumOf { Calc.cost(maxOf(it.stock, 0), it.p.avgCost) }
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Field("খোঁজ (নাম, কোড, বারকোড)", q, { q = it })
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(!lowOnly, { lowOnly = false }, { Text("সব") })
                FilterChip(lowOnly, { lowOnly = true }, { Text("কম/শেষ স্টক") })
            }
            Text("আনুমানিক স্টক মূল্য: " + Fmt.money(value) + "  (গড় ক্রয়মূল্যে)", style = MaterialTheme.typography.bodySmall)
        }
        val f = all.filter { r ->
            (q.isBlank() || r.p.name.contains(q, true) || r.p.code.contains(q, true) || r.p.barcode.contains(Fmt.en(q))) &&
                (!lowOnly || (r.stock <= r.p.lowMilli))
        }
        if (f.isEmpty()) Empty(if (all.isEmpty()) "এখনো কোনো পণ্য নেই। নিচের বোতাম দিয়ে যোগ করুন" else "কিছু পাওয়া যায়নি")
        else LazyColumn(contentPadding = PaddingValues(bottom = 90.dp)) {
            items(f, key = { it.p.id }) { r ->
                val out = r.stock <= 0; val low = r.p.lowMilli > 0 && r.stock <= r.p.lowMilli
                Item(r.p.name + if (r.p.archived) " (আর্কাইভ)" else "",
                    "ক্রয় ${Fmt.money(r.p.avgCost)} • বিক্রয় ${Fmt.money(r.p.sellPrice)}" + if (out) " • স্টক শেষ" else if (low) " • কম স্টক" else "",
                    Fmt.qty(r.stock) + " " + r.p.unit, "মূল্য " + Fmt.money(Calc.cost(maxOf(r.stock, 0), r.p.avgCost)),
                    if (out) Sem.expense else if (low) Sem.due else Sem.sale) { nav.go(Route.ProductForm(r.p.id)) }
            }
        }
    }
}

@Composable
fun ProductFormScreen(repo: Repo, id: String?, profile: Profile, nav: Nav) {
    val c = LocalContext.current
    val scope = rememberCoroutineScope()
    val snack = remember { SnackbarHostState() }
    val newId = remember { repo.newId() }
    val suppliers by remember("S") { repo.dao.parties("S") }.collectAsState(initial = emptyList())
    var old by remember { mutableStateOf<Product?>(null) }
    var loaded by remember { mutableStateOf(id == null) }
    var name by remember { mutableStateOf("") }; var code by remember { mutableStateOf("") }; var barcode by remember { mutableStateOf("") }
    var cat by remember { mutableStateOf("") }; var unit by remember { mutableStateOf(profile.units.first()) }
    var altUnit by remember { mutableStateOf("") }; var altFactor by remember { mutableStateOf("") }
    var buy by remember { mutableStateOf("") }; var sell by remember { mutableStateOf("") }; var whole by remember { mutableStateOf("") }
    var low by remember { mutableStateOf("") }; var opening by remember { mutableStateOf("") }
    var supplierId by remember { mutableStateOf("") }; var desc by remember { mutableStateOf("") }; var img by remember { mutableStateOf("") }
    var tried by remember { mutableStateOf(false) }; var saving by remember { mutableStateOf(false) }
    var arch by remember { mutableStateOf(false) }
    var stock by remember { mutableLongStateOf(0L) }

    LaunchedEffect(id) {
        if (id != null) repo.dao.productRow(id)?.let { r ->
            val p = r.p; old = p; stock = r.stock
            name = p.name; code = p.code; barcode = p.barcode; cat = p.category; unit = p.unit; altUnit = p.altUnit
            altFactor = if (p.altFactor == 0L) "" else Fmt.qty(p.altFactor).let { Fmt.en(it) }
            buy = if (p.buyPrice == 0L) "" else Fmt.plain(p.buyPrice); sell = if (p.sellPrice == 0L) "" else Fmt.plain(p.sellPrice)
            whole = if (p.wholesalePrice == 0L) "" else Fmt.plain(p.wholesalePrice)
            low = if (p.lowMilli == 0L) "" else Fmt.en(Fmt.qty(p.lowMilli)); supplierId = p.supplierId; desc = p.description; img = p.img
        }
        loaded = true
    }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) scope.launch { Img.encode(c, uri, 360)?.let { img = it } ?: snack.showSnackbar("ছবি পড়া যায়নি") }
    }
    fun money(s: String): Long? = if (s.isBlank()) 0L else Fmt.parseMoney(s)
    fun qty(s: String): Long? = if (s.isBlank()) 0L else Fmt.parseQty(s)
    val buyV = money(buy); val sellV = money(sell); val wholeV = money(whole); val lowV = qty(low); val openV = qty(opening); val altV = qty(altFactor)
    val bad = name.isBlank() || buyV == null || sellV == null || wholeV == null || lowV == null || openV == null || altV == null ||
        (altUnit.isNotBlank() && altV == 0L)

    Screen(if (id == null) "নতুন পণ্য" else "পণ্য সম্পাদনা", nav.back, snack) {
        if (!loaded) return@Screen Loading()
        Scroll {
            Field("পণ্যের নাম *", name, { name = it }, error = if (tried && name.isBlank()) "নাম দিন" else null)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Field("SKU/কোড", code, { code = it }, Modifier.weight(1f))
                Field("বারকোড", barcode, { barcode = it }, Modifier.weight(1f), KeyboardType.Number)
            }
            Field("ক্যাটাগরি", cat, { cat = it })
            Pick("একক", unit, profile.units) { unit = it }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Field("বড় একক (যেমন কার্টন)", altUnit, { altUnit = it }, Modifier.weight(1f))
                NumField("১ বড় এককে $unit", altFactor, { altFactor = it }, Modifier.weight(1f), error = if (altUnit.isNotBlank() && altV == 0L) "দিন" else null)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NumField("ক্রয়মূল্য ৳", buy, { buy = it }, Modifier.weight(1f), error = if (buyV == null) "ভুল" else null)
                NumField("বিক্রয়মূল্য ৳", sell, { sell = it }, Modifier.weight(1f), error = if (sellV == null) "ভুল" else null)
            }
            NumField("পাইকারি মূল্য ৳ (ঐচ্ছিক)", whole, { whole = it }, error = if (wholeV == null) "ভুল" else null)
            NumField("সর্বনিম্ন স্টক সতর্কতা ($unit)", low, { low = it }, error = if (lowV == null) "ভুল" else null)
            if (id == null) NumField("প্রারম্ভিক স্টক ($unit)", opening, { opening = it }, error = if (openV == null) "ভুল" else null)
            else Text("বর্তমান স্টক: " + Fmt.qty(stock) + " " + unit + " • গড় ক্রয়মূল্য " + Fmt.money(old?.avgCost ?: 0), style = MaterialTheme.typography.bodyMedium)
            Pick("সাপ্লায়ার", suppliers.firstOrNull { it.id == supplierId }?.name ?: "নেই", listOf("নেই") + suppliers.filter { !it.archived }.map { it.name }) { n ->
                supplierId = suppliers.firstOrNull { it.name == n }?.id ?: ""
            }
            Field("বিবরণ (ঐচ্ছিক)", desc, { desc = it }, singleLine = false)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Img.decode(img)?.let { Image(it.asImageBitmap(), null, Modifier.size(72.dp)) }
                OutlinedButton({ pick.launch("image/*") }) { Text(if (img.isBlank()) "ছবি যোগ করুন" else "ছবি বদলান") }
                if (img.isNotBlank()) TextButton({ img = "" }) { Text("সরান") }
            }
            Button({
                tried = true
                if (bad) return@Button
                saving = true
                scope.launch {
                    val t = now(); val o = old
                    runCatching {
                        repo.saveProduct(Product(o?.id ?: newId, code.trim(), Fmt.en(barcode).trim(), name.trim(), cat.trim(), unit, altUnit.trim(),
                            if (altUnit.isBlank()) 0 else altV ?: 0, supplierId, desc.trim(), img, buyV ?: 0, o?.avgCost ?: (buyV ?: 0), sellV ?: 0, wholeV ?: 0,
                            lowV ?: 0, o?.archived ?: false, o?.createdAt ?: t, t), openV ?: 0)
                    }.onSuccess { nav.back() }.onFailure { snack.showSnackbar("সংরক্ষণ করা যায়নি"); saving = false }
                }
            }, enabled = !saving, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("সংরক্ষণ করুন") }
            if (old != null) {
                OutlinedButton({ nav.go(Route.Adjust(old!!.id)) }, Modifier.fillMaxWidth()) { Text("স্টক সমন্বয় / ক্ষতি ও স্টকের ইতিহাস") }
                OutlinedButton({ arch = true }, Modifier.fillMaxWidth()) { Text(if (old!!.archived) "আর্কাইভ থেকে ফেরান" else "পণ্য আর্কাইভ করুন") }
            }
        }
    }
    if (arch) Confirm("নিশ্চিত করুন", "লেনদেনের ইতিহাস অক্ষত থাকবে; শুধু নতুন বিক্রয়ের তালিকা থেকে সরে যাবে।",
        onYes = { arch = false; scope.launch { repo.archiveProduct(old!!, !old!!.archived); nav.back() } }, onNo = { arch = false })
}

@Composable
fun AdjustScreen(repo: Repo, id: String, nav: Nav) {
    val scope = rememberCoroutineScope()
    val snack = remember { SnackbarHostState() }
    val moves by remember(id) { repo.dao.moves(id) }.collectAsState(initial = emptyList())
    var row by remember { mutableStateOf<ProductRow?>(null) }
    val ver by repo.version.collectAsState()
    LaunchedEffect(ver) { row = repo.dao.productRow(id) }
    var mode by remember { mutableStateOf("বাড়ান") }
    var qty by remember { mutableStateOf("") }
    var reason by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf(false) }
    val r = row ?: return Loading()
    val q = Fmt.parseQty(qty)
    Screen("স্টক সমন্বয়: ${r.p.name}", nav.back, snack) {
        Scroll {
            Text("বর্তমান স্টক: " + Fmt.qty(r.stock) + " " + r.p.unit, style = MaterialTheme.typography.titleMedium)
            Chips(listOf("বাড়ান", "কমান", "ক্ষতি/নষ্ট"), mode) { mode = it }
            NumField("পরিমাণ (${r.p.unit})", qty, { qty = it }, error = if (qty.isNotBlank() && (q == null || q <= 0)) "সঠিক সংখ্যা দিন" else null)
            Field("কারণ (বাধ্যতামূলক)", reason, { reason = it })
            Button({ confirm = true }, enabled = q != null && q > 0 && reason.isNotBlank(), modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("সংরক্ষণ করুন") }
            Section("স্টকের ইতিহাস (সর্বশেষ ২০০)") {
                if (moves.isEmpty()) Text("কোনো এন্ট্রি নেই")
                moves.forEach { m ->
                    Row(Modifier.fillMaxWidth()) {
                        Column(Modifier.weight(1f)) {
                            Text(moveLabel[m.type] ?: m.type); Text(Fmt.date(m.date) + if (m.reason.isNotBlank()) " • " + m.reason else "", style = MaterialTheme.typography.bodySmall)
                        }
                        Text((if (m.qtyMilli > 0) "+" else "") + Fmt.qty(m.qtyMilli), color = if (m.qtyMilli < 0) Sem.expense else Sem.sale)
                    }
                }
            }
        }
    }
    if (confirm) Confirm("স্টক পরিবর্তন", "$mode: ${Fmt.qty(q ?: 0)} ${r.p.unit}। এটি অডিট লগে থাকবে।", onYes = {
        confirm = false
        scope.launch {
            val delta = if (mode == "বাড়ান") (q ?: 0) else -(q ?: 0)
            runCatching { repo.adjustStock(id, delta, mode == "ক্ষতি/নষ্ট", reason.trim()) }
                .onSuccess { qty = ""; reason = "" }
                .onFailure { snack.showSnackbar(if (it is StockException) "স্টক অপর্যাপ্ত। আছে ${Fmt.qty(it.available)}" else it.message ?: "ব্যর্থ") }
        }
    }, onNo = { confirm = false })
}
