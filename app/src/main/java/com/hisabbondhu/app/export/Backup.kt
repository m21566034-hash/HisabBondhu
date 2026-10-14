package com.hisabbondhu.app.export

import com.hisabbondhu.app.data.*
import org.json.JSONArray
import org.json.JSONObject

/**
 * Full local backup as one readable JSON file (UTF-8). It contains every business record of THIS account:
 * parties, products, invoices with lines, payments, expenses, stock ledger, returns, audit log and synced settings.
 * Restore merges by stable id, so restoring twice never duplicates a transaction.
 * It does not contain app-lock PINs, sign-in data or this device's local counters.
 */
object Backup {
    const val FORMAT = "hisabbondhu-backup"
    const val VERSION = 1

    class Parsed(val uid: String, val exportedAt: Long, val json: JSONObject, val counts: Map<String, Int>)

    suspend fun export(repo: Repo): String {
        val d = repo.dao
        val root = JSONObject().put("format", FORMAT).put("version", VERSION).put("uid", repo.uid).put("exportedAt", System.currentTimeMillis())
        fun arr(l: List<JSONObject>) = JSONArray().also { a -> l.forEach { a.put(it) } }
        val items = d.allItems().groupBy { it.txnId }
        root.put("parties", arr(d.partiesOnce("C").map { it.json() } + d.partiesOnce("S").map { it.json() }))
        root.put("products", arr(d.productRowsOnce().map { it.p.json() }))
        root.put("txns", arr(d.allTxns().map { it.jsonWith(items[it.id].orEmpty()) }))
        root.put("payments", arr(d.allPayments().map { it.json() }))
        root.put("expenses", arr(d.allExpenses().map { it.json() }))
        root.put("stockMoves", arr(d.allMoves().map { it.json() }))
        root.put("returns", arr(d.allRets().map { it.json() }))
        root.put("audits", arr(d.allAudits().map { it.json() }))
        root.put("settings", arr(d.allSettings().filter { !it.key.startsWith("local.") }.map { it.json() }))
        return root.toString(1)
    }

    /** Validates the whole file before anything is changed. Returns null with a message if it is not usable. */
    fun parse(text: String): Pair<Parsed?, String> {
        val o = try { JSONObject(text.removePrefix("﻿")) } catch (e: Exception) { return null to "ফাইলটি বৈধ ব্যাকআপ নয়" }
        if (o.optString("format") != FORMAT) return null to "এটি হিসাববন্ধুর ব্যাকআপ ফাইল নয়"
        if (o.optInt("version") > VERSION) return null to "ব্যাকআপটি নতুন সংস্করণের; অ্যাপ আপডেট করুন"
        val keys = listOf("parties", "products", "txns", "payments", "expenses", "stockMoves", "returns", "audits", "settings")
        val counts = LinkedHashMap<String, Int>()
        for (k in keys) {
            val a = o.optJSONArray(k) ?: return null to "ফাইলে '$k' অংশ নেই"
            for (i in 0 until a.length()) {
                val r = a.optJSONObject(i) ?: return null to "$k-এর একটি এন্ট্রি নষ্ট"
                if (r.optString("id").isBlank()) return null to "$k-এর একটি এন্ট্রিতে আইডি নেই"
            }
            counts[k] = a.length()
        }
        return Parsed(o.optString("uid"), o.optLong("exportedAt"), o, counts) to ""
    }

    /** Returns how many records were added or updated. Existing financial records are never overwritten. */
    suspend fun restore(repo: Repo, p: Parsed): Int = repo.atomic {
        val d = repo.dao; val o = p.json; var n = 0
        suspend fun go(k: String, f: suspend (JSONObject) -> Boolean) {
            val a = o.getJSONArray(k); for (i in 0 until a.length()) if (f(a.getJSONObject(i))) n++
        }
        go("parties") { j -> val x = Party.from(j); val l = d.party(x.id)
            if (l == null || x.updatedAt > l.updatedAt) { d.upsert(x); repo.enqueue(x); true } else false }
        go("products") { j -> val x = Product.from(j); val l = d.product(x.id)
            if (l == null || x.updatedAt > l.updatedAt) { d.upsert(x); repo.enqueue(x); true } else false }
        go("txns") { j -> val x = Txn.from(j); val l = d.txn(x.id)
            if (l == null || (x.status != l.status && x.updatedAt > l.updatedAt)) {
                val items = Txn.itemsFrom(j)
                d.upsert(x); if (l == null) { d.upsertItems(items) }
                repo.enqueueTxn(x, if (l == null) items else d.items(x.id)); true } else false }
        go("payments") { j -> val x = Payment.from(j); if (d.payment(x.id) == null) { d.upsert(x); repo.enqueue(x); true } else false }
        go("expenses") { j -> val x = Expense.from(j); val l = d.expense(x.id)
            if (l == null || x.updatedAt > l.updatedAt) { d.upsert(x); repo.enqueue(x); true } else false }
        val haveMoves = d.allMoves().map { it.id }.toHashSet()
        go("stockMoves") { j -> val x = StockMove.from(j); if (x.id !in haveMoves) { d.upsert(x); repo.enqueue(x); true } else false }
        val haveRets = d.allRets().map { it.id }.toHashSet()
        go("returns") { j -> val x = Ret.from(j); if (x.id !in haveRets) { d.upsert(x); repo.enqueue(x); true } else false }
        val haveAud = d.allAudits().map { it.id }.toHashSet()
        go("audits") { j -> val x = Audit.from(j); if (x.id !in haveAud) { d.upsert(x); repo.enqueue(x); true } else false }
        go("settings") { j -> val x = Setting.from(j); val l = d.setting(x.key)
            if (l == null || x.updatedAt > l.updatedAt) { d.upsert(x); repo.enqueue(x); true } else false }
        n
    }
}
