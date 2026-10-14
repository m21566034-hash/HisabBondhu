@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.hisabbondhu.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hisabbondhu.app.core.*
import com.hisabbondhu.app.data.*
import kotlinx.coroutines.flow.first

sealed class Route {
    data class PartyList(val kind: String) : Route()
    data class PartyDetail(val id: String) : Route()
    data class PartyForm(val kind: String, val id: String?) : Route()
    data class Pay(val partyId: String, val refId: String = "") : Route()
    data class ProductForm(val id: String?) : Route()
    data class Adjust(val id: String) : Route()
    data class NewTxn(val kind: String) : Route()
    data class TxnDetail(val id: String) : Route()
    object Expenses : Route()
    data class ExpenseForm(val id: String?) : Route()
    object Settings : Route()
    object Audit : Route()
    data class Report(val type: String) : Route()
}

class Nav(val go: (Route) -> Unit, val back: () -> Unit, val replace: (Route) -> Unit, val tab: (Int) -> Unit)

@Composable
fun AppHost(repo: Repo, theme: String, setTheme: (String) -> Unit) {
    val settings by repo.settings.collectAsState(initial = null)
    val st = settings ?: return Loading()
    val profile = remember(st) { Profile.from(st) }
    if (!profile.complete) { ProfileForm(repo, profile, first = true, onDone = {}); return }

    var tab by remember { mutableIntStateOf(0) }
    val stack = remember { mutableStateListOf<Route>() }
    val nav = remember { Nav({ stack.add(it) }, { if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex) },
        { if (stack.isNotEmpty()) stack[stack.lastIndex] = it else stack.add(it) }, { tab = it; stack.clear() }) }
    BackHandler(enabled = stack.isNotEmpty() || tab != 0) { if (stack.isNotEmpty()) nav.back() else tab = 0 }

    val top = stack.lastOrNull()
    if (top != null) {
        when (top) {
            is Route.PartyList -> PartyListScreen(repo, top.kind, nav)
            is Route.PartyDetail -> PartyDetailScreen(repo, top.id, profile, nav)
            is Route.PartyForm -> PartyFormScreen(repo, top.kind, top.id, nav)
            is Route.Pay -> PayScreen(repo, top.partyId, top.refId, profile, nav)
            is Route.ProductForm -> ProductFormScreen(repo, top.id, profile, nav)
            is Route.Adjust -> AdjustScreen(repo, top.id, nav)
            is Route.NewTxn -> NewTxnScreen(repo, top.kind, profile, nav)
            is Route.TxnDetail -> TxnDetailScreen(repo, top.id, profile, nav)
            Route.Expenses -> ExpenseListScreen(repo, nav)
            is Route.ExpenseForm -> ExpenseFormScreen(repo, top.id, profile, nav)
            Route.Settings -> SettingsScreen(repo, profile, theme, setTheme, nav)
            Route.Audit -> AuditScreen(repo, nav)
            is Route.Report -> ReportScreen(repo, profile, top.type, nav)
        }
        return
    }

    Scaffold(bottomBar = {
        NavigationBar {
            listOf("🏠" to "হোম", "🧾" to "বিক্রয়", "📦" to "স্টক", "📊" to "রিপোর্ট", "☰" to "মেনু").forEachIndexed { i, (ic, l) ->
                NavigationBarItem(selected = tab == i, onClick = { tab = i }, icon = { Text(ic, fontSize = 20.sp) }, label = { Text(l, fontSize = 12.sp) })
            }
        }
    }) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            when (tab) {
                0 -> HomeTab(repo, nav)
                1 -> SalesTab(repo, nav)
                2 -> StockTab(repo, nav)
                3 -> ReportsTab(nav)
                else -> MenuTab(repo, nav)
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------- dashboard
data class Dash(
    val sales: Long, val collected: Long, val expense: Long, val saleDue: Long, val custDue: Long, val supDue: Long,
    val stockVal: Long, val purchases: Long, val pnl: Calc.Pnl, val low: List<ProductRow>
)

suspend fun loadDash(d: AppDao, range: DateRange): Dash {
    val today = DateRanges.of(Preset.TODAY)
    val ts = d.txnsBetween("SALE", today.from, today.to)
    val ids = ts.map { it.id }.toSet()
    val paidOnToday = d.allPayments().filter { it.refId in ids }.sumOf { it.amount }
    val collected = d.paymentsBetween(today.from, today.to).filter { it.kind == "IN" }.sumOf { it.amount }
    val exp = d.expensesBetween(today.from, today.to).sumOf { it.amount }
    val pur = d.txnsBetween("PURCHASE", today.from, today.to).sumOf { it.total }
    val cust = d.balances("C").first().filter { it.bal > 0 }.sumOf { it.bal }
    val sup = d.balances("S").first().filter { it.bal > 0 }.sumOf { it.bal }
    val prods = d.productRowsOnce().filter { !it.p.archived }
    val stockVal = prods.sumOf { Calc.cost(maxOf(it.stock, 0), it.p.avgCost) }
    val low = prods.filter { it.p.lowMilli > 0 && it.stock <= it.p.lowMilli }
    val sales = d.txnsBetween("SALE", range.from, range.to)
    val items = d.itemsBetween("SALE", range.from, range.to)
    val rets = d.retsBetween(range.from, range.to).filter { it.kind == "SALE" }
    val adj = d.movesBetween(range.from, range.to).filter { it.type == "DAMAGE" || it.type == "ADJUST" }.sumOf { Calc.cost(it.qtyMilli, it.unitCost) }
    val pnl = Calc.pnl(sales.sumOf { it.total }, rets.sumOf { it.amount }, items.sumOf { Calc.cost(it.qtyMilli, it.costPrice) },
        rets.sumOf { it.cost }, d.expensesBetween(range.from, range.to).sumOf { it.amount }, adj)
    return Dash(ts.sumOf { it.total }, collected, exp, ts.sumOf { it.total } - paidOnToday, cust, sup, stockVal, pur, pnl, low)
}

@Composable
fun HomeTab(repo: Repo, nav: Nav) {
    val ver by repo.version.collectAsState()
    var preset by remember { mutableStateOf(Preset.THIS_MONTH) }
    var cf by remember { mutableLongStateOf(now()) }
    var ct by remember { mutableLongStateOf(now()) }
    val range = remember(preset, cf, ct) {
        DateRanges.of(preset, customFrom = java.time.Instant.ofEpochMilli(cf).atZone(Fmt.DHAKA).toLocalDate(), customTo = java.time.Instant.ofEpochMilli(ct).atZone(Fmt.DHAKA).toLocalDate())
    }
    var dash by remember { mutableStateOf<Dash?>(null) }
    LaunchedEffect(ver, range) { dash = loadDash(repo.dao, range) }
    val pend by remember { repo.dao.pendingCount() }.collectAsState(initial = 0)
    val err by com.hisabbondhu.app.Graph.syncError.collectAsState()
    val recentSales by remember("SALE", "%", 5) { repo.dao.txns("SALE", "%", 5) }.collectAsState(initial = emptyList())
    val recentPays by remember(5) { repo.dao.recentPayments(5) }.collectAsState(initial = emptyList())
    val recentExp by remember(5) { repo.dao.recentExpenses(5) }.collectAsState(initial = emptyList())

    Screen("হিসাববন্ধু", null) {
        val d = dash ?: return@Screen Loading()
        Scroll {
            err?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (pend > 0) Text("☁ ${Fmt.bn(pend.toString())} টি পরিবর্তন সিঙ্কের অপেক্ষায় (ফোনে সংরক্ষিত)", fontSize = 13.sp, color = Sem.due)
            Text("আজ", style = MaterialTheme.typography.titleMedium)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Stat("আজকের মোট বিক্রি", Fmt.money(d.sales), Sem.sale, Modifier.weight(1f))
                Stat("আজকের আদায়", Fmt.money(d.collected), Sem.paid, Modifier.weight(1f))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Stat("আজকের খরচ", Fmt.money(d.expense), Sem.expense, Modifier.weight(1f))
                Stat("আজকের বিক্রয়ের বকেয়া", Fmt.money(d.saleDue), Sem.due, Modifier.weight(1f))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Stat("আজকের ক্রয়", Fmt.money(d.purchases), Sem.neutral, Modifier.weight(1f))
                Stat("স্টকের আনুমানিক মূল্য", Fmt.money(d.stockVal), Sem.neutral, Modifier.weight(1f))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Stat("মোট কাস্টমার বকেয়া", Fmt.money(d.custDue), Sem.due, Modifier.weight(1f))
                Stat("মোট সাপ্লায়ার পাওনা", Fmt.money(d.supDue), Sem.expense, Modifier.weight(1f))
            }
            Section("দ্রুত কাজ") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    AssistChip({ nav.go(Route.NewTxn("SALE")) }, { Text("নতুন বিক্রয়") })
                    AssistChip({ nav.go(Route.PartyForm("C", null)) }, { Text("কাস্টমার যোগ") })
                    AssistChip({ nav.go(Route.ProductForm(null)) }, { Text("পণ্য যোগ") })
                    AssistChip({ nav.go(Route.NewTxn("PURCHASE")) }, { Text("ক্রয় যোগ") })
                    AssistChip({ nav.go(Route.ExpenseForm(null)) }, { Text("খরচ যোগ") })
                    AssistChip({ nav.go(Route.PartyList("C")) }, { Text("টাকা আদায়") })
                    AssistChip({ nav.tab(3) }, { Text("রিপোর্ট দেখুন") })
                }
            }
            Section("লাভ-ক্ষতি: ${range.label}") {
                Chips(listOf(Preset.TODAY, Preset.LAST7, Preset.THIS_MONTH, Preset.LAST_MONTH, Preset.LAST1Y).map { it.label }, preset.label) { l -> preset = Preset.values().first { it.label == l } }
                DateField("শুরু", cf) { cf = it; preset = Preset.CUSTOM }
                DateField("শেষ", ct) { ct = it; preset = Preset.CUSTOM }
                Row { Text("নিট বিক্রয়", Modifier.weight(1f)); Text(Fmt.money(d.pnl.netSales)) }
                Row { Text("মোট লাভ", Modifier.weight(1f)); Text(Fmt.money(d.pnl.grossProfit)) }
                Row { Text("খরচ ও সমন্বয়", Modifier.weight(1f)); Text(Fmt.money(d.pnl.expenses - d.pnl.adjustments)) }
                HorizontalDivider()
                Row { Text("নিট লাভ/ক্ষতি", Modifier.weight(1f), fontWeight = FontWeight.Bold)
                    Text(Fmt.money(d.pnl.operatingProfit), fontWeight = FontWeight.Bold, color = if (d.pnl.operatingProfit >= 0) Sem.profit else Sem.expense) }
            }
            if (d.low.isNotEmpty()) Section("কম স্টকের পণ্য") {
                d.low.take(8).forEach { Row { Text(it.p.name, Modifier.weight(1f)); Text(Fmt.qty(it.stock) + " " + it.p.unit, color = Sem.expense) } }
            }
            Section("সাম্প্রতিক বিক্রয়") {
                if (recentSales.isEmpty()) Text("এখনো কোনো বিক্রয় নেই")
                recentSales.forEach { t -> Row(Modifier.fillMaxWidth()) { Text(t.number + " • " + t.partyName.ifBlank { "খুচরা" }, Modifier.weight(1f), fontSize = 14.sp); Text(Fmt.money(t.total), color = Sem.sale) } }
            }
            Section("সাম্প্রতিক পেমেন্ট") {
                if (recentPays.isEmpty()) Text("এখনো কোনো পেমেন্ট নেই")
                recentPays.forEach { p -> Row(Modifier.fillMaxWidth()) { Text(Fmt.date(p.date) + " • " + (if (p.kind == "IN") "আদায়" else "পরিশোধ"), Modifier.weight(1f), fontSize = 14.sp); Text(Fmt.money(p.amount), color = Sem.paid) } }
            }
            Section("সাম্প্রতিক খরচ") {
                if (recentExp.isEmpty()) Text("এখনো কোনো খরচ নেই")
                recentExp.forEach { e -> Row(Modifier.fillMaxWidth()) { Text(e.category + " • " + Fmt.date(e.date), Modifier.weight(1f), fontSize = 14.sp); Text(Fmt.money(e.amount), color = Sem.expense) } }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------- menu
@Composable
fun MenuTab(repo: Repo, nav: Nav) {
    Screen("মেনু", null) {
        LazyColumn {
            val rows = listOf(
                "কাস্টমার" to { nav.go(Route.PartyList("C")) }, "সাপ্লায়ার" to { nav.go(Route.PartyList("S")) },
                "ক্রয়" to { nav.go(Route.NewTxn("PURCHASE")) }, "খরচ" to { nav.go(Route.Expenses) },
                "বকেয়া (কাস্টমার)" to { nav.go(Route.Report("custDue")) }, "পাওনা (সাপ্লায়ার)" to { nav.go(Route.Report("supDue")) },
                "অডিট লগ" to { nav.go(Route.Audit) }, "সেটিংস, ব্যাকআপ ও লগআউট" to { nav.go(Route.Settings) })
            items(rows) { (l, f) -> Item(l, onClick = f) }
        }
    }
}

@Composable
fun AuditScreen(repo: Repo, nav: Nav) {
    val list by remember(200) { repo.dao.audits(200) }.collectAsState(initial = emptyList())
    Screen("অডিট লগ", nav.back) {
        if (list.isEmpty()) Empty("কোনো সংশোধনের রেকর্ড নেই") else LazyColumn {
            items(list) { a -> Item(a.action, a.detail, Fmt.dateTime(a.date)) }
        }
    }
}
