package com.hisabbondhu.app.data

import androidx.room.withTransaction
import com.hisabbondhu.app.core.Calc
import com.hisabbondhu.app.core.Fmt
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.Instant
import java.util.UUID

class StockException(val product: String, val available: Long) : Exception("স্টক অপর্যাপ্ত: $product")
class BizException(msg: String) : Exception(msg)

data class DraftLine(
    val productId: String, val name: String, val unit: String,
    val qtyMilli: Long, val unitPrice: Long, val discount: Long = 0
)

/** All business writes happen here, each inside one Room transaction, and each queues its documents for cloud sync. */
class Repo(val db: AppDb, val uid: String, private val kick: () -> Unit) {
    val dao = db.dao()

    fun newId(): String = UUID.randomUUID().toString()
    private fun now() = System.currentTimeMillis()

    private suspend fun out(d: Doc) {
        if (d is Setting && d.key.startsWith("local.")) return
        dao.put(Outbox("${d.coll}/${d.id}", d.coll, d.id, d.json().toString(), now()))
    }

    private suspend fun outTxn(t: Txn, items: List<TxnItem>) =
        dao.put(Outbox("${t.coll}/${t.id}", t.coll, t.id, t.jsonWith(items).toString(), now()))

    private suspend fun audit(action: String, entity: String, entityId: String, detail: String) {
        val t = now()
        val a = Audit(newId(), action, entity, entityId, detail, t, t, t)
        dao.upsert(a); out(a)
    }

    /** Bumped after every committed write so summary screens can refresh. */
    val version = kotlinx.coroutines.flow.MutableStateFlow(0L)

    private suspend fun <T> tx(block: suspend () -> T): T = db.withTransaction { block() }.also { version.value++; kick() }

    // ------------------------------------------------------------------ settings
    val settings: Flow<Map<String, String>> = dao.settings().map { l -> l.associate { it.key to it.value } }
    suspend fun setting(k: String, def: String = ""): String = dao.setting(k)?.value ?: def
    suspend fun putSetting(k: String, v: String) {
        val s = Setting(k, v, now()); dao.upsert(s); out(s); version.value++; if (!k.startsWith("local.")) kick()
    }

    suspend fun allowNegative() = setting("allowNegative") == "1"

    private suspend fun deviceCode(): String {
        dao.setting("local.dev")?.let { return it.value }
        val chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        val c = (1..4).map { chars.random() }.joinToString("")
        dao.upsert(Setting("local.dev", c, now())); return c
    }

    /** INV-261013-A7K2-0001 : date + per-install code + per-install counter. Collision-free across devices. */
    private suspend fun nextNumber(kind: String, date: Long): String {
        val key = "local.seq.$kind"
        val n = (dao.setting(key)?.value?.toLongOrNull() ?: 0L) + 1
        dao.upsert(Setting(key, n.toString(), now()))
        val z = Instant.ofEpochMilli(date).atZone(Fmt.DHAKA)
        val ymd = "%02d%02d%02d".format(z.year % 100, z.monthValue, z.dayOfMonth)
        return (if (kind == "SALE") "INV" else "PUR") + "-$ymd-${deviceCode()}-" + n.toString().padStart(4, '0')
    }

    private suspend fun nextCode(kind: String): String {
        val key = "local.code.$kind"
        val n = (dao.setting(key)?.value?.toLongOrNull() ?: 0L) + 1
        dao.upsert(Setting(key, n.toString(), now()))
        return (if (kind == "C") "C" else "S") + "-" + deviceCode() + "-" + n.toString().padStart(3, '0')
    }

    // ------------------------------------------------------------------ parties
    suspend fun saveParty(p: Party): Party = tx {
        val t = now()
        val code = if (p.code.isBlank()) nextCode(p.kind) else p.code
        val x = p.copy(code = code, updatedAt = t)
        dao.upsert(x); out(x); x
    }
    suspend fun archiveParty(p: Party, archived: Boolean) { saveParty(p.copy(archived = archived)) }

    // ------------------------------------------------------------------ products / stock
    suspend fun saveProduct(p: Product, openingMilli: Long = 0): Product = tx {
        val t = now()
        val isNew = dao.product(p.id) == null
        val x = p.copy(updatedAt = t, avgCost = if (isNew) p.buyPrice else p.avgCost)
        dao.upsert(x); out(x)
        if (isNew && openingMilli != 0L) {
            val m = StockMove(newId(), x.id, "OPENING", openingMilli, x.buyPrice, "", "প্রারম্ভিক স্টক", t, t, t)
            dao.upsert(m); out(m)
        }
        x
    }

    /** Manual correction or damage. delta is signed (negative = remove). A reason is mandatory. */
    suspend fun adjustStock(productId: String, deltaMilli: Long, damage: Boolean, reason: String) = tx {
        if (reason.isBlank()) throw BizException("কারণ লিখুন")
        if (deltaMilli == 0L) throw BizException("পরিমাণ শূন্য হতে পারে না")
        val p = dao.product(productId) ?: throw BizException("পণ্য পাওয়া যায়নি")
        if (deltaMilli < 0 && !allowNegative() && dao.stockOf(productId) + deltaMilli < 0)
            throw StockException(p.name, dao.stockOf(productId))
        val t = now()
        val m = StockMove(newId(), productId, if (damage) "DAMAGE" else "ADJUST", deltaMilli, p.avgCost, "", reason, t, t, t)
        dao.upsert(m); out(m)
        audit(if (damage) "DAMAGE" else "ADJUST", "Product", productId, "${Fmt.qty(deltaMilli)} — $reason")
    }

    suspend fun archiveProduct(p: Product, archived: Boolean) = tx {
        val x = p.copy(archived = archived, updatedAt = now()); dao.upsert(x); out(x)
    }

    // ------------------------------------------------------------------ balances
    suspend fun balanceOf(partyId: String): Long = if (partyId.isBlank()) 0 else dao.balance(partyId) ?: 0

    // ------------------------------------------------------------------ sale / purchase
    /**
     * Saves a sale or purchase atomically: invoice, lines, stock ledger, product cost, payment.
     * [id] is generated once per form, so a double tap or a retry returns the already-saved record.
     */
    suspend fun saveTxn(
        id: String, kind: String, partyId: String, partyName: String, partyPhone: String,
        lines: List<DraftLine>, invoiceDiscount: Long, extra: Long, paid: Long, method: String,
        note: String, date: Long, img: String = ""
    ): Txn = tx {
        dao.txn(id)?.let { return@tx it }
        if (lines.isEmpty()) throw BizException("কমপক্ষে একটি পণ্য যোগ করুন")
        if (lines.any { it.qtyMilli <= 0 }) throw BizException("পরিমাণ শূন্যের বেশি হতে হবে")
        val calcLines = lines.map { Calc.Line(it.qtyMilli, it.unitPrice, it.discount) }
        val tot = Calc.totals(calcLines, invoiceDiscount, extra)
        if (paid < 0 || paid > tot.total) throw BizException("পরিশোধ মোট বিলের বেশি হতে পারে না")
        if (partyId.isBlank() && (kind == "PURCHASE" || paid != tot.total))
            throw BizException(if (kind == "PURCHASE") "সাপ্লায়ার নির্বাচন করুন" else "খুচরা বিক্রয়ে পুরো টাকা নিতে হবে, নাহলে কাস্টমার নির্বাচন করুন")
        val t = now()
        val sale = kind == "SALE"
        val prev = balanceOf(partyId)

        if (sale && !allowNegative()) {
            lines.groupBy { it.productId }.forEach { (pid, ls) ->
                val need = ls.sumOf { it.qtyMilli }; val have = dao.stockOf(pid)
                if (have < need) throw StockException(ls.first().name, have)
            }
        }
        val landed = if (sale) emptyList() else Calc.landedUnitCosts(calcLines, invoiceDiscount, extra)
        val items = ArrayList<TxnItem>()
        lines.forEachIndexed { i, l ->
            val prod = dao.product(l.productId) ?: throw BizException("পণ্য পাওয়া যায়নি: ${l.name}")
            val stock = dao.stockOf(l.productId)
            if (sale) {
                items += TxnItem(newId(), id, l.productId, l.name, l.unit, l.qtyMilli, l.unitPrice, prod.avgCost, l.discount,
                    Calc.lineNet(calcLines[i]))
                val m = StockMove(newId(), l.productId, "SALE", -l.qtyMilli, prod.avgCost, id, "", date, t, t)
                dao.upsert(m); out(m)
            } else {
                val unitCost = landed[i]
                items += TxnItem(newId(), id, l.productId, l.name, l.unit, l.qtyMilli, l.unitPrice, unitCost, l.discount,
                    Calc.lineNet(calcLines[i]))
                val avg = Calc.weightedAvg(stock, prod.avgCost, l.qtyMilli, unitCost)
                val np = prod.copy(avgCost = avg, buyPrice = l.unitPrice, updatedAt = t)
                dao.upsert(np); out(np)
                val m = StockMove(newId(), l.productId, "PURCHASE", l.qtyMilli, unitCost, id, "", date, t, t)
                dao.upsert(m); out(m)
            }
        }
        val txn = Txn(id, kind, nextNumber(kind, date), partyId, partyName, partyPhone, date, tot.subtotal, tot.itemDiscount,
            tot.invoiceDiscount, tot.extra, tot.total, paid, method, prev, note, img, "ACTIVE", "", t, t)
        dao.upsert(txn); dao.upsertItems(items); outTxn(txn, items)
        if (paid > 0 && partyId.isNotBlank()) {
            val p = Payment(newId(), if (sale) "IN" else "OUT", partyId, id, paid, method, date, "", "", t, t)
            dao.upsert(p); out(p)
        }
        txn
    }

    // ------------------------------------------------------------------ payments
    /** Records money from a customer (IN) or to a supplier (OUT). Over-payment must be explicitly confirmed by the caller. */
    suspend fun addPayment(id: String, kind: String, partyId: String, refId: String, amount: Long, method: String,
                           date: Long, note: String): Payment = tx {
        dao.payment(id)?.let { return@tx it }
        if (amount <= 0) throw BizException("টাকার পরিমাণ শূন্যের বেশি হতে হবে")
        if (partyId.isBlank()) throw BizException("পার্টি নির্বাচন করুন")
        val t = now()
        val p = Payment(id, kind, partyId, refId, amount, method, date, note, "", t, t)
        dao.upsert(p); out(p); p
    }

    /** Corrections never delete: a negative row linked to the original is added. */
    suspend fun reversePayment(paymentId: String, reason: String) = tx {
        val orig = dao.payment(paymentId) ?: throw BizException("পেমেন্ট পাওয়া যায়নি")
        if (orig.reversalOf.isNotBlank() || orig.amount <= 0) throw BizException("এটি রিভার্স করা যাবে না")
        if (dao.reversalOf(paymentId) != null) throw BizException("এই পেমেন্ট আগেই রিভার্স করা হয়েছে")
        if (reason.isBlank()) throw BizException("কারণ লিখুন")
        val t = now()
        val r = Payment(newId(), orig.kind, orig.partyId, orig.refId, -orig.amount, orig.method, t, "রিভার্স: $reason", orig.id, t, t)
        dao.upsert(r); out(r)
        audit("PAYMENT_REVERSAL", "Payment", paymentId, reason)
    }

    // ------------------------------------------------------------------ cancel / return
    suspend fun cancelTxn(txnId: String, reason: String) = tx {
        val tx0 = dao.txn(txnId) ?: throw BizException("বিল পাওয়া যায়নি")
        if (tx0.status != "ACTIVE") throw BizException("বিলটি আগেই বাতিল হয়েছে")
        if (reason.isBlank()) throw BizException("বাতিলের কারণ লিখুন")
        if (dao.retsOfTxn(txnId).isNotEmpty()) throw BizException("এই বিলে ফেরত আছে, আগে বাতিল করা যাবে না")
        val items = dao.items(txnId)
        val t = now()
        val sale = tx0.kind == "SALE"
        items.forEach { it ->
            if (!sale && !allowNegative() && dao.stockOf(it.productId) < it.qtyMilli)
                throw StockException(it.name, dao.stockOf(it.productId))
            val m = StockMove(newId(), it.productId, "CANCEL", if (sale) it.qtyMilli else -it.qtyMilli, it.costPrice, txnId,
                "বাতিল: ${tx0.number}", t, t, t)
            dao.upsert(m); out(m)
        }
        val x = tx0.copy(status = "CANCELLED", cancelReason = reason, updatedAt = t)
        dao.upsert(x); outTxn(x, items)
        audit("CANCEL", tx0.coll, txnId, "${tx0.number} — $reason")
        // Payments are left untouched: any amount already paid shows as an explicit advance/credit.
    }

    /** qty map: itemId -> milli quantity to return. Over-returning is blocked. */
    suspend fun returnItems(txnId: String, qty: Map<String, Long>, reason: String) = tx {
        val tx0 = dao.txn(txnId) ?: throw BizException("বিল পাওয়া যায়নি")
        if (tx0.status != "ACTIVE") throw BizException("বাতিল বিলে ফেরত নেওয়া যায় না")
        if (reason.isBlank()) throw BizException("ফেরতের কারণ লিখুন")
        val items = dao.items(txnId).associateBy { it.id }
        val prior = dao.retsOfTxn(txnId)
        val netBase = tx0.subtotal - tx0.itemDiscount
        val t = now()
        val sale = tx0.kind == "SALE"
        var any = false
        qty.forEach { (itemId, q) ->
            if (q <= 0) return@forEach
            val it = items[itemId] ?: return@forEach
            val done = prior.filter { r -> r.productId == it.productId }.sumOf { r -> r.qtyMilli }
            if (done + q > it.qtyMilli) throw BizException("${it.name}: ফেরতের পরিমাণ বিলের পরিমাণের বেশি")
            if (!sale && !allowNegative() && dao.stockOf(it.productId) < q) throw StockException(it.name, dao.stockOf(it.productId))
            val amount = Calc.returnAmount(it.lineTotal, it.qtyMilli, q, tx0.discount, netBase)
            val cost = Calc.cost(q, it.costPrice)
            val r = Ret(newId(), tx0.kind, txnId, tx0.partyId, it.productId, it.name, q, amount, cost, t, reason, t, t)
            dao.upsert(r); out(r)
            val m = StockMove(newId(), it.productId, if (sale) "SALE_RETURN" else "PURCHASE_RETURN", if (sale) q else -q, it.costPrice,
                txnId, reason, t, t, t)
            dao.upsert(m); out(m)
            any = true
        }
        if (!any) throw BizException("ফেরতের পরিমাণ দিন")
        audit("RETURN", tx0.coll, txnId, "${tx0.number} — $reason")
    }

    // ------------------------------------------------------------------ expenses
    suspend fun saveExpense(e: Expense): Expense = tx {
        if (e.amount <= 0) throw BizException("খরচের পরিমাণ শূন্যের বেশি হতে হবে")
        val old = dao.expense(e.id)
        val x = e.copy(updatedAt = now())
        if (old != null) audit("EXPENSE_EDIT", "Expense", e.id, "আগে: ${Fmt.plain(old.amount)} ${old.category} ${old.description}")
        dao.upsert(x); out(x); x
    }

    suspend fun voidExpense(e: Expense, reason: String) = tx {
        if (reason.isBlank()) throw BizException("কারণ লিখুন")
        val x = e.copy(status = "VOID", voidReason = reason, updatedAt = now())
        dao.upsert(x); out(x)
        audit("EXPENSE_VOID", "Expense", e.id, "${Fmt.plain(e.amount)} — $reason")
    }

    // ------------------------------------------------------------------ restore support
    suspend fun enqueue(d: Doc) = out(d)
    suspend fun enqueueTxn(t: Txn, items: List<TxnItem>) = outTxn(t, items)
    suspend fun <T> atomic(block: suspend () -> T): T = tx(block)

    // ------------------------------------------------------------------ misc
    suspend fun pendingCount() = dao.pendingCountOnce()
}
