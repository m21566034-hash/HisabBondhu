package com.hisabbondhu.app.render

import com.hisabbondhu.app.core.Fmt
import com.hisabbondhu.app.data.*
import com.hisabbondhu.app.export.ReportTable

/** Builds the content of every image document from SAVED records only (never from current product prices). */
object Docs {
    private fun biz(p: Profile) = listOfNotNull(
        p.address.takeIf { it.isNotBlank() }, p.phone.takeIf { it.isNotBlank() }?.let { "মোবাইল: ${Fmt.bn(it)}" })

    fun invoice(p: Profile, t: Txn, items: List<TxnItem>, balanceNow: Long, rets: List<Ret> = emptyList()): DocSpec {
        val sale = t.kind == "SALE"
        val due = t.total - t.paid
        val partyLabel = if (sale) "কাস্টমার" else "সাপ্লায়ার"
        val rows = items.mapIndexed { i, it ->
            listOf(Fmt.bn((i + 1).toString()), it.name + if (it.discount > 0) "\n(ছাড় ${Fmt.money(it.discount)})" else "",
                Fmt.qty(it.qtyMilli) + " " + it.unit, Fmt.money(it.unitPrice), Fmt.money(it.lineTotal))
        }
        val sum = ArrayList<SumLine>()
        sum += SumLine("সাবটোটাল", Fmt.money(t.subtotal))
        if (t.itemDiscount + t.discount > 0) sum += SumLine("মোট ছাড়", "-" + Fmt.money(t.itemDiscount + t.discount))
        if (t.extra > 0) sum += SumLine("অতিরিক্ত চার্জ", Fmt.money(t.extra))
        sum += SumLine(if (sale) "মোট বিল" else "মোট ক্রয়", Fmt.money(t.total), 1)
        sum += SumLine("এই বিলে পরিশোধ (${t.method})", Fmt.money(t.paid))
        sum += SumLine("এই বিলের বকেয়া", Fmt.money(due), 1)
        if (t.partyId.isNotBlank()) {
            sum += SumLine("পূর্বের বকেয়া", Fmt.money(t.prevBalance))
            sum += SumLine("আপনার মোট বকেয়া", Fmt.money(balanceNow).let { if (balanceNow < 0) "অগ্রিম " + Fmt.money(-balanceNow) else it }, 2)
        }
        if (t.status != "ACTIVE") sum += SumLine("অবস্থা", "বাতিল", 2)
        val meta = listOf(
            "বিল নম্বর" to t.number, "তারিখ" to Fmt.dateTime(t.date))
        val meta2 = listOf(partyLabel to t.partyName.ifBlank { "খুচরা কাস্টমার" }) +
            (if (t.partyPhone.isNotBlank()) listOf("মোবাইল" to Fmt.bn(t.partyPhone)) else emptyList())
        val foot = listOf(t.note.takeIf { it.isNotBlank() }?.let { "নোট: $it" }, p.footer.takeIf { it.isNotBlank() }).filterNotNull().joinToString("\n")
        return DocSpec(p.name, biz(p), Img.decode(p.logo), if (sale) "ইনভয়েস / বিল" else "ক্রয় বিল",
            meta, meta2, "${t.number} • ${t.partyName.ifBlank { "খুচরা কাস্টমার" }}",
            listOf(Col("#", 0.7f), Col("পণ্য", 3.6f), Col("পরিমাণ", 1.7f, true), Col("দর", 1.6f, true), Col("মোট", 1.8f, true)),
            rows, sum, foot, p.accent)
    }

    fun receipt(p: Profile, pay: Payment, partyName: String, partyPhone: String, balanceAfter: Long, supplier: Boolean): DocSpec {
        val sum = listOf(
            SumLine(if (supplier) "পরিশোধিত টাকা" else "গৃহীত টাকা", Fmt.money(pay.amount), 2),
            SumLine("পদ্ধতি", pay.method),
            SumLine(if (balanceAfter < 0) "অগ্রিম/ক্রেডিট" else "বর্তমান বকেয়া", Fmt.money(Math.abs(balanceAfter)), 1))
        return DocSpec(p.name, biz(p), Img.decode(p.logo), if (supplier) "পেমেন্ট ভাউচার" else "টাকা প্রাপ্তির রসিদ",
            listOf("রসিদ" to pay.id.takeLast(8).uppercase(), "তারিখ" to Fmt.dateTime(pay.date)),
            listOf((if (supplier) "সাপ্লায়ার" else "কাস্টমার") to partyName) + (if (partyPhone.isNotBlank()) listOf("মোবাইল" to Fmt.bn(partyPhone)) else emptyList()),
            "রসিদ • $partyName", listOf(Col("বিবরণ", 3f), Col("টাকা", 1.5f, true)),
            listOf(listOf(pay.note.ifBlank { if (supplier) "সাপ্লায়ারকে পরিশোধ" else "কাস্টমারের জমা" }, Fmt.money(pay.amount))),
            sum, p.footer, p.accent, "রসিদ")
    }

    fun statement(p: Profile, r: ReportTable, partyName: String, phone: String): DocSpec =
        DocSpec(p.name, biz(p), Img.decode(p.logo), "হিসাব বিবরণী",
            listOf("পার্টি" to partyName) + (if (phone.isNotBlank()) listOf("মোবাইল" to Fmt.bn(phone)) else emptyList()),
            listOf("সময়" to r.range), "বিবরণী • $partyName",
            listOf(Col("তারিখ", 2.2f), Col(r.headers[1], 2.4f), Col(r.headers[2], 1.5f, true), Col(r.headers[3], 1.5f, true), Col("ব্যালেন্স", 1.7f, true)),
            r.rows.map { row -> row.map { it.text } },
            r.summary.mapIndexed { i, s -> SumLine(s.first, s.second.text, if (i == r.summary.lastIndex) 2 else 0) },
            p.footer, p.accent, "পাতা")

    /** The Bangla message that goes with the shared invoice image. All figures come from the saved invoice. */
    fun shareText(p: Profile, t: Txn, balanceNow: Long): String {
        val b = StringBuilder("আসসালামু আলাইকুম। আপনার বিলের ছবি সংযুক্ত করা হলো।\n\n")
        b.append("বিল নম্বর: ${t.number}\n")
        if (t.partyName.isNotBlank()) b.append("নাম: ${t.partyName}\n")
        b.append("মোট বিল: ${Fmt.money(t.total)}\n")
        b.append("এই বিলে পরিশোধিত: ${Fmt.money(t.paid)}\n")
        b.append("এই বিলের বকেয়া: ${Fmt.money(t.total - t.paid)}\n")
        if (t.partyId.isNotBlank()) b.append(if (balanceNow < 0) "আপনার অগ্রিম জমা: ${Fmt.money(-balanceNow)}\n" else "আপনার মোট বকেয়া: ${Fmt.money(balanceNow)}\n")
        b.append("\nধন্যবাদান্তে,\n${p.name}")
        return b.toString()
    }
}
