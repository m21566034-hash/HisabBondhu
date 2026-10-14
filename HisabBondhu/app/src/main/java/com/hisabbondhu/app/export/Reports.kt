package com.hisabbondhu.app.export

import com.hisabbondhu.app.core.Calc
import com.hisabbondhu.app.core.DateRange
import com.hisabbondhu.app.core.Fmt
import com.hisabbondhu.app.data.AppDao
import com.hisabbondhu.app.data.Party
import java.time.Instant

/** A display cell with a separate machine-readable value for CSV. */
data class Cell(val text: String, val csv: String = text)
fun m(p: Long) = Cell(Fmt.money(p), Fmt.plain(p))
fun q(milli: Long): Cell {
    val a = Math.abs(milli)
    val s = (if (milli < 0) "-" else "") + (a / 1000) + if (a % 1000 != 0L) "." + (a % 1000).toString().padStart(3, '0').trimEnd('0') else ""
    return Cell(Fmt.qty(milli), s)
}
fun t(s: String) = Cell(s)
fun n(v: Long) = Cell(Fmt.bn(v.toString()), v.toString())

data class ReportTable(
    val title: String, val range: String, val headers: List<String>, val rows: List<List<Cell>>,
    val summary: List<Pair<String, Cell>> = emptyList(), val notes: List<String> = emptyList()
)

const val CASH = "ক্যাশ"

class Reports(private val dao: AppDao) {

    private fun day(ms: Long) = Fmt.date(ms)
    private fun month(ms: Long): String {
        val z = Instant.ofEpochMilli(ms).atZone(Fmt.DHAKA); return Fmt.bn("${z.year}-${z.monthValue.toString().padStart(2, '0')}")
    }

    suspend fun sales(r: DateRange): ReportTable {
        val tx = dao.txnsBetween("SALE", r.from, r.to)
        val rets = dao.retsBetween(r.from, r.to).filter { it.kind == "SALE" }
        val paidByRef = dao.allPayments().groupBy { it.refId }.mapValues { e -> e.value.sumOf { it.amount } }
        val total = tx.sumOf { it.total }
        val disc = tx.sumOf { it.itemDiscount + it.discount }
        val ret = rets.sumOf { it.amount }
        val paid = tx.sumOf { paidByRef[it.id] ?: 0L }
        return ReportTable("বিক্রয় রিপোর্ট", r.label,
            listOf("বিল নম্বর", "তারিখ", "কাস্টমার", "মোট", "ছাড়", "পরিশোধিত", "বকেয়া"),
            tx.map { x -> val p = paidByRef[x.id] ?: 0L
                listOf(t(x.number), t(day(x.date)), t(x.partyName.ifBlank { "খুচরা" }), m(x.total), m(x.itemDiscount + x.discount), m(p), m(x.total - p)) },
            listOf("মোট বিক্রয়" to m(total), "মোট ছাড়" to m(disc), "মোট ফেরত" to m(ret), "নিট বিক্রয়" to m(total - ret),
                "বিলের সংখ্যা" to n(tx.size.toLong()), "গড় বিল" to m(if (tx.isEmpty()) 0 else Calc.divRound(total, tx.size.toLong())),
                "বিলে পরিশোধিত" to m(paid), "বিলের বকেয়া" to m(total - paid)),
            listOf("ফেরত নির্বাচিত সময়ের ফেরত-তারিখ অনুযায়ী ধরা হয়েছে।"))
    }

    suspend fun purchases(r: DateRange): ReportTable {
        val tx = dao.txnsBetween("PURCHASE", r.from, r.to)
        val rets = dao.retsBetween(r.from, r.to).filter { it.kind == "PURCHASE" }
        val pays = dao.paymentsBetween(r.from, r.to).filter { it.kind == "OUT" }
        val paidByRef = dao.allPayments().groupBy { it.refId }.mapValues { e -> e.value.sumOf { it.amount } }
        val total = tx.sumOf { it.total }
        return ReportTable("ক্রয় রিপোর্ট", r.label,
            listOf("ক্রয় নম্বর", "তারিখ", "সাপ্লায়ার", "মোট", "পরিশোধিত", "বকেয়া"),
            tx.map { x -> val p = paidByRef[x.id] ?: 0L
                listOf(t(x.number), t(day(x.date)), t(x.partyName), m(x.total), m(p), m(x.total - p)) },
            listOf("মোট ক্রয়" to m(total), "সাপ্লায়ারকে পরিশোধ (এই সময়ে)" to m(pays.sumOf { it.amount }),
                "ক্রয় ফেরত" to m(rets.sumOf { it.amount }),
                "বকেয়া (এই সময়ের ক্রয়)" to m(tx.sumOf { it.total - (paidByRef[it.id] ?: 0L) })))
    }

    suspend fun expenses(r: DateRange): ReportTable {
        val ex = dao.expensesBetween(r.from, r.to)
        val byCat = ex.groupBy { it.category }.mapValues { e -> e.value.sumOf { it.amount } }
        val byMonth = ex.groupBy { month(it.date) }.mapValues { e -> e.value.sumOf { it.amount } }
        return ReportTable("খরচ রিপোর্ট", r.label,
            listOf("তারিখ", "ক্যাটাগরি", "বিবরণ", "পদ্ধতি", "টাকা"),
            ex.map { listOf(t(day(it.date)), t(it.category), t(it.description), t(it.method), m(it.amount)) },
            listOf("মোট খরচ" to m(ex.sumOf { it.amount })) +
                byCat.entries.sortedByDescending { it.value }.map { "ক্যাটাগরি: ${it.key}" to m(it.value) } +
                byMonth.entries.sortedBy { it.key }.map { "মাস: ${it.key}" to m(it.value) } +
                ex.groupBy { day(it.date) }.map { e -> "দিন: ${e.key}" to m(e.value.sumOf { it.amount }) })
    }

    suspend fun pnl(r: DateRange): ReportTable {
        val tx = dao.txnsBetween("SALE", r.from, r.to)
        val items = dao.itemsBetween("SALE", r.from, r.to)
        val rets = dao.retsBetween(r.from, r.to).filter { it.kind == "SALE" }
        val ex = dao.expensesBetween(r.from, r.to)
        val adj = dao.movesBetween(r.from, r.to).filter { it.type == "DAMAGE" || it.type == "ADJUST" }
            .sumOf { Calc.cost(it.qtyMilli, it.unitCost) }
        val cogs = items.sumOf { Calc.cost(it.qtyMilli, it.costPrice) }
        val p = Calc.pnl(tx.sumOf { it.total }, rets.sumOf { it.amount }, cogs, rets.sumOf { it.cost }, ex.sumOf { it.amount }, adj)
        val notes = mutableListOf(
            "লাভ হিসাব হয়েছে প্রতিটি বিক্রয়ের সময়ের গড় ক্রয়মূল্য (weighted average) দিয়ে। ক্রয় নিজে খরচ নয়; বিক্রিত মালের ক্রয়মূল্য (COGS) হিসেবে আসে।",
            "টাকা আদায় বা সাপ্লায়ারকে পরিশোধ লাভের অংশ নয়; সেগুলো ক্যাশ-ফ্লো রিপোর্টে দেখুন।")
        if (items.any { it.costPrice == 0L }) notes += "সতর্কতা: কিছু বিক্রিত পণ্যের ক্রয়মূল্য ০ ছিল, তাই লাভ আনুমানিক এবং বেশি দেখাতে পারে।"
        return ReportTable("লাভ-ক্ষতি রিপোর্ট", r.label, listOf("বিবরণ", "টাকা"),
            listOf(listOf(t("মোট বিক্রয়"), m(tx.sumOf { it.total })), listOf(t("বিক্রয় ফেরত"), m(-rets.sumOf { it.amount })),
                listOf(t("নিট বিক্রয়"), m(p.netSales)), listOf(t("বিক্রিত মালের ক্রয়মূল্য (COGS)"), m(-p.cogs)),
                listOf(t("মোট লাভ (Gross)"), m(p.grossProfit)), listOf(t("পরিচালন খরচ"), m(-p.expenses)),
                listOf(t("স্টক ক্ষতি/সমন্বয়"), m(p.adjustments)), listOf(t("নিট লাভ/ক্ষতি"), m(p.operatingProfit))),
            listOf("নিট লাভ/ক্ষতি" to m(p.operatingProfit)), notes)
    }

    /** Opening / period / closing for every customer (kind "C") or supplier ("S"). */
    suspend fun dues(kind: String, r: DateRange, parties: List<Party>): ReportTable {
        val tk = if (kind == "C") "SALE" else "PURCHASE"
        val txs = dao.allTxns().filter { it.kind == tk && it.status == "ACTIVE" }.groupBy { it.partyId }
        val pays = dao.allPayments().groupBy { it.partyId }
        val rets = dao.allRets().groupBy { it.partyId }
        var closeTotal = 0L
        val rows = parties.filter { !it.archived || (txs[it.id]?.isNotEmpty() == true) }.map { p ->
            val ts = txs[p.id].orEmpty(); val ps = pays[p.id].orEmpty(); val rs = rets[p.id].orEmpty()
            val open = Calc.balance(p.openingDue, ts.filter { it.date < r.from }.sumOf { it.total },
                ps.filter { it.date < r.from }.sumOf { it.amount }, rs.filter { it.date < r.from }.sumOf { it.amount })
            val inv = ts.filter { it.date in r.from until r.to }.sumOf { it.total }
            val pay = ps.filter { it.date in r.from until r.to }.sumOf { it.amount }
            val ret = rs.filter { it.date in r.from until r.to }.sumOf { it.amount }
            val close = open + inv - pay - ret
            closeTotal += close
            listOf(t(p.name), t(p.phone), m(open), m(inv), m(pay), m(ret), m(close))
        }
        val title = if (kind == "C") "কাস্টমার বকেয়া রিপোর্ট" else "সাপ্লায়ার পাওনা রিপোর্ট"
        return ReportTable(title, r.label,
            listOf("নাম", "মোবাইল", "শুরুর ব্যালেন্স", if (kind == "C") "বিক্রয়" else "ক্রয়", if (kind == "C") "আদায়" else "পরিশোধ", "ফেরত/ক্রেডিট", "শেষ ব্যালেন্স"),
            rows, listOf("মোট শেষ ব্যালেন্স" to m(closeTotal)),
            listOf("ব্যালেন্স ঋণাত্মক (-) মানে অগ্রিম/ক্রেডিট। শুরুর ব্যালেন্স = নির্বাচিত সময়ের শুরুর আগের সব লেনদেন; শেষ ব্যালেন্স = সময়ের শেষে।"))
    }

    suspend fun stock(r: DateRange): ReportTable {
        val moves = dao.allMoves().groupBy { it.productId }
        var value = 0L
        val rows = dao.productRowsOnce().filter { !it.p.archived || moves[it.p.id] != null }.map { row ->
            val ms = moves[row.p.id].orEmpty()
            val open = ms.filter { it.date < r.from }.sumOf { it.qtyMilli }
            val inr = ms.filter { it.date in r.from until r.to }
            val bought = inr.filter { it.type == "PURCHASE" || it.type == "OPENING" }.sumOf { it.qtyMilli }
            val sold = -inr.filter { it.type == "SALE" }.sumOf { it.qtyMilli }
            val ret = inr.filter { it.type == "SALE_RETURN" || it.type == "PURCHASE_RETURN" }.sumOf { it.qtyMilli }
            val adj = inr.filter { it.type == "ADJUST" || it.type == "DAMAGE" || it.type == "CANCEL" }.sumOf { it.qtyMilli }
            val close = ms.filter { it.date < r.to }.sumOf { it.qtyMilli }
            val v = Calc.cost(close, row.p.avgCost); value += v
            listOf(t(row.p.name), q(open), q(bought), q(sold), q(ret), q(adj), q(close), m(v))
        }
        return ReportTable("স্টক রিপোর্ট", r.label,
            listOf("পণ্য", "শুরুর স্টক", "ক্রয়/প্রারম্ভিক", "বিক্রি", "ফেরত", "সমন্বয়/ক্ষতি", "শেষ স্টক", "আনুমানিক মূল্য"),
            rows, listOf("আনুমানিক মোট স্টক মূল্য" to m(value)),
            listOf("মূল্য = শেষ স্টক × বর্তমান গড় ক্রয়মূল্য, তাই এটি আনুমানিক।"))
    }

    suspend fun cashFlow(r: DateRange, openingBalance: Long): ReportTable {
        val pays = dao.paymentsBetween(r.from, r.to)
        val ex = dao.expensesBetween(r.from, r.to)
        val methods = (pays.map { it.method } + ex.map { it.method }).distinct().sorted()
        var cin = 0L; var cout = 0L
        val rows = methods.map { me ->
            val i = pays.filter { it.kind == "IN" && it.method == me }.sumOf { it.amount }
            val o = pays.filter { it.kind == "OUT" && it.method == me }.sumOf { it.amount }
            val e = ex.filter { it.method == me }.sumOf { it.amount }
            cin += i; cout += o + e
            listOf(t(me), m(i), m(o), m(e), m(i - o - e))
        }
        val allPays = dao.allPayments(); val allEx = dao.allExpenses().filter { it.status == "ACTIVE" }
        fun cashNet(before: Long) = allPays.filter { it.method == CASH && it.date < before }.sumOf { if (it.kind == "IN") it.amount else -it.amount } -
            allEx.filter { it.method == CASH && it.date < before }.sumOf { it.amount }
        val open = openingBalance + cashNet(r.from); val close = openingBalance + cashNet(r.to)
        return ReportTable("ক্যাশ ফ্লো রিপোর্ট", r.label, listOf("পদ্ধতি", "প্রাপ্তি", "সাপ্লায়ারকে পরিশোধ", "খরচ", "নিট"),
            rows, listOf("মোট প্রাপ্তি" to m(cin), "মোট পরিশোধ ও খরচ" to m(cout), "নিট নগদ প্রবাহ" to m(cin - cout),
                "ক্যাশ ($CASH) শুরুর ব্যালেন্স" to m(open), "ক্যাশ ($CASH) শেষ ব্যালেন্স" to m(close)),
            listOf("ক্যাশ ব্যালেন্স হিসাব হয় সেটিংসের প্রারম্ভিক ব্যালেন্স ও '$CASH' পদ্ধতির লেনদেন থেকে। প্রারম্ভিক ব্যালেন্স না দিলে এটি শুধু লেনদেনের নিট দেখায়।",
                "ক্যাশ ফ্লো লাভ-ক্ষতি নয়; এটি শুধু টাকা আসা-যাওয়ার হিসাব।"))
    }

    /** Statement for one customer/supplier between two dates. */
    suspend fun statement(p: Party, r: DateRange): ReportTable {
        val tk = if (p.kind == "C") "SALE" else "PURCHASE"
        val ts = dao.allTxns().filter { it.partyId == p.id && it.kind == tk && it.status == "ACTIVE" }
        val ps = dao.allPayments().filter { it.partyId == p.id }
        val rs = dao.allRets().filter { it.partyId == p.id }
        var bal = Calc.balance(p.openingDue, ts.filter { it.date < r.from }.sumOf { it.total },
            ps.filter { it.date < r.from }.sumOf { it.amount }, rs.filter { it.date < r.from }.sumOf { it.amount })
        val opening = bal
        data class E(val d: Long, val what: String, val debit: Long, val credit: Long)
        val ev = (ts.filter { it.date in r.from until r.to }.map { E(it.date, it.number, it.total, 0) } +
            ps.filter { it.date in r.from until r.to }.map { E(it.date, "পেমেন্ট ${it.method}" + if (it.reversalOf.isNotBlank()) " (রিভার্স)" else "", 0, it.amount) } +
            rs.filter { it.date in r.from until r.to }.map { E(it.date, "ফেরত ${it.name}", 0, it.amount) }).sortedBy { it.d }
        val rows = ev.map { e -> bal += e.debit - e.credit
            listOf(t(day(e.d)), t(e.what), m(e.debit), m(e.credit), m(bal)) }
        return ReportTable("হিসাব বিবরণী: ${p.name}", r.label, listOf("তারিখ", "বিবরণ", if (p.kind == "C") "বিল" else "ক্রয়", if (p.kind == "C") "জমা" else "পরিশোধ", "ব্যালেন্স"),
            rows, listOf("শুরুর ব্যালেন্স" to m(opening), "শেষ ব্যালেন্স" to m(bal)))
    }
}

object Csv {
    private fun esc(s: String): String = if (s.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + s.replace("\"", "\"\"") + "\"" else s

    /** UTF-8 with BOM so Excel shows Bangla correctly. Numbers are plain (no ৳, no Bangla digits) so sheets can sum them. */
    fun bytes(t: ReportTable): ByteArray {
        val sb = StringBuilder("﻿")
        sb.append(esc(t.title)).append(',').append(esc(t.range)).append("\r\n")
        sb.append(t.headers.joinToString(",") { esc(it) }).append("\r\n")
        t.rows.forEach { r -> sb.append(r.joinToString(",") { esc(it.csv) }).append("\r\n") }
        if (t.summary.isNotEmpty()) { sb.append("\r\n"); t.summary.forEach { sb.append(esc(it.first)).append(',').append(esc(it.second.csv)).append("\r\n") } }
        t.notes.forEach { sb.append(esc(it)).append("\r\n") }
        return sb.toString().toByteArray(Charsets.UTF_8)
    }
}
