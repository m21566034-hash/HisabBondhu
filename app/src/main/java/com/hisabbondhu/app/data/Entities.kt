package com.hisabbondhu.app.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import org.json.JSONArray
import org.json.JSONObject

/**
 * Every record has a stable UUID id (idempotent sync) and updatedAt (for last-writer-wins on
 * editable records). Money = paisa, quantity = milli-units. No nulls are stored; "" means none.
 */
interface Doc {
    val id: String
    val coll: String
    val updatedAt: Long
    fun json(): JSONObject
}

private fun j(vararg p: Pair<String, Any>): JSONObject = JSONObject().also { o -> p.forEach { o.put(it.first, it.second) } }

@Entity
data class Party(
    @PrimaryKey override val id: String,
    val kind: String,                 // "C" customer, "S" supplier
    val code: String, val name: String, val phone: String = "", val address: String = "",
    val business: String = "", val openingDue: Long = 0, val note: String = "",
    val archived: Boolean = false, val createdAt: Long, override val updatedAt: Long
) : Doc {
    override val coll get() = if (kind == "C") "customers" else "suppliers"
    override fun json() = j("id" to id, "kind" to kind, "code" to code, "name" to name, "phone" to phone,
        "address" to address, "business" to business, "openingDue" to openingDue, "note" to note,
        "archived" to archived, "createdAt" to createdAt, "updatedAt" to updatedAt)
    companion object {
        fun from(o: JSONObject) = Party(o.getString("id"), o.optString("kind", "C"), o.optString("code"), o.optString("name"),
            o.optString("phone"), o.optString("address"), o.optString("business"), o.optLong("openingDue"),
            o.optString("note"), o.optBoolean("archived"), o.optLong("createdAt"), o.optLong("updatedAt"))
    }
}

@Entity
data class Product(
    @PrimaryKey override val id: String,
    val code: String = "", val barcode: String = "", val name: String, val category: String = "",
    val unit: String = "পিস", val altUnit: String = "", val altFactor: Long = 0,   // e.g. কার্টন = 12 পিস
    val supplierId: String = "", val description: String = "", val img: String = "",
    val buyPrice: Long = 0, val avgCost: Long = 0, val sellPrice: Long = 0, val wholesalePrice: Long = 0,
    val lowMilli: Long = 0, val archived: Boolean = false, val createdAt: Long, override val updatedAt: Long
) : Doc {
    override val coll get() = "products"
    override fun json() = j("id" to id, "code" to code, "barcode" to barcode, "name" to name, "category" to category,
        "unit" to unit, "altUnit" to altUnit, "altFactor" to altFactor, "supplierId" to supplierId,
        "description" to description, "img" to img, "buyPrice" to buyPrice, "avgCost" to avgCost,
        "sellPrice" to sellPrice, "wholesalePrice" to wholesalePrice, "lowMilli" to lowMilli,
        "archived" to archived, "createdAt" to createdAt, "updatedAt" to updatedAt)
    companion object {
        fun from(o: JSONObject) = Product(o.getString("id"), o.optString("code"), o.optString("barcode"), o.optString("name"),
            o.optString("category"), o.optString("unit", "পিস"), o.optString("altUnit"), o.optLong("altFactor"),
            o.optString("supplierId"), o.optString("description"), o.optString("img"), o.optLong("buyPrice"),
            o.optLong("avgCost"), o.optLong("sellPrice"), o.optLong("wholesalePrice"), o.optLong("lowMilli"),
            o.optBoolean("archived"), o.optLong("createdAt"), o.optLong("updatedAt"))
    }
}

/** One table for sales (kind=SALE) and purchases (kind=PURCHASE). Financial fields are never edited. */
@Entity(indices = [Index("kind", "date"), Index("partyId")])
data class Txn(
    @PrimaryKey override val id: String,
    val kind: String, val number: String, val partyId: String = "", val partyName: String = "", val partyPhone: String = "",
    val date: Long, val subtotal: Long, val itemDiscount: Long, val discount: Long, val extra: Long,
    val total: Long, val paid: Long, val method: String, val prevBalance: Long = 0, val note: String = "",
    val img: String = "", val status: String = "ACTIVE", val cancelReason: String = "",
    val createdAt: Long, override val updatedAt: Long
) : Doc {
    override val coll get() = if (kind == "SALE") "sales" else "purchases"
    override fun json() = jsonWith(emptyList())
    fun jsonWith(items: List<TxnItem>): JSONObject = j("id" to id, "kind" to kind, "number" to number, "partyId" to partyId,
        "partyName" to partyName, "partyPhone" to partyPhone, "date" to date, "subtotal" to subtotal,
        "itemDiscount" to itemDiscount, "discount" to discount, "extra" to extra, "total" to total, "paid" to paid,
        "method" to method, "prevBalance" to prevBalance, "note" to note, "img" to img, "status" to status,
        "cancelReason" to cancelReason, "createdAt" to createdAt, "updatedAt" to updatedAt)
        .also { o -> o.put("items", JSONArray().also { a -> items.forEach { a.put(it.json()) } }) }
    companion object {
        fun from(o: JSONObject) = Txn(o.getString("id"), o.optString("kind"), o.optString("number"), o.optString("partyId"),
            o.optString("partyName"), o.optString("partyPhone"), o.optLong("date"), o.optLong("subtotal"),
            o.optLong("itemDiscount"), o.optLong("discount"), o.optLong("extra"), o.optLong("total"), o.optLong("paid"),
            o.optString("method"), o.optLong("prevBalance"), o.optString("note"), o.optString("img"),
            o.optString("status", "ACTIVE"), o.optString("cancelReason"), o.optLong("createdAt"), o.optLong("updatedAt"))
        fun itemsFrom(o: JSONObject): List<TxnItem> {
            val a = o.optJSONArray("items") ?: return emptyList()
            return (0 until a.length()).map { TxnItem.from(a.getJSONObject(it), o.getString("id")) }
        }
    }
}

@Entity(indices = [Index("txnId"), Index("productId")])
data class TxnItem(
    @PrimaryKey val id: String, val txnId: String, val productId: String, val name: String, val unit: String,
    val qtyMilli: Long, val unitPrice: Long,      // price (sale) or landed cost (purchase) per base unit
    val costPrice: Long,                          // historical unit cost at time of sale (for COGS)
    val discount: Long, val lineTotal: Long
) {
    fun json() = j("id" to id, "productId" to productId, "name" to name, "unit" to unit, "qtyMilli" to qtyMilli,
        "unitPrice" to unitPrice, "costPrice" to costPrice, "discount" to discount, "lineTotal" to lineTotal)
    companion object {
        fun from(o: JSONObject, txnId: String) = TxnItem(o.getString("id"), txnId, o.optString("productId"), o.optString("name"),
            o.optString("unit"), o.optLong("qtyMilli"), o.optLong("unitPrice"), o.optLong("costPrice"),
            o.optLong("discount"), o.optLong("lineTotal"))
    }
}

/** Immutable. kind IN = received from customer, OUT = paid to supplier. A correction is a negative reversal row. */
@Entity(indices = [Index("partyId"), Index("refId"), Index("date")])
data class Payment(
    @PrimaryKey override val id: String, val kind: String, val partyId: String, val refId: String = "",
    val amount: Long, val method: String, val date: Long, val note: String = "", val reversalOf: String = "",
    val createdAt: Long, override val updatedAt: Long
) : Doc {
    override val coll get() = "payments"
    override fun json() = j("id" to id, "kind" to kind, "partyId" to partyId, "refId" to refId, "amount" to amount,
        "method" to method, "date" to date, "note" to note, "reversalOf" to reversalOf, "createdAt" to createdAt, "updatedAt" to updatedAt)
    companion object {
        fun from(o: JSONObject) = Payment(o.getString("id"), o.optString("kind"), o.optString("partyId"), o.optString("refId"),
            o.optLong("amount"), o.optString("method"), o.optLong("date"), o.optString("note"), o.optString("reversalOf"),
            o.optLong("createdAt"), o.optLong("updatedAt"))
    }
}

@Entity(indices = [Index("date")])
data class Expense(
    @PrimaryKey override val id: String, val category: String, val amount: Long, val date: Long, val method: String,
    val description: String = "", val ref: String = "", val img: String = "",
    val status: String = "ACTIVE", val voidReason: String = "", val createdAt: Long, override val updatedAt: Long
) : Doc {
    override val coll get() = "expenses"
    override fun json() = j("id" to id, "category" to category, "amount" to amount, "date" to date, "method" to method,
        "description" to description, "ref" to ref, "img" to img, "status" to status, "voidReason" to voidReason,
        "createdAt" to createdAt, "updatedAt" to updatedAt)
    companion object {
        fun from(o: JSONObject) = Expense(o.getString("id"), o.optString("category"), o.optLong("amount"), o.optLong("date"),
            o.optString("method"), o.optString("description"), o.optString("ref"), o.optString("img"),
            o.optString("status", "ACTIVE"), o.optString("voidReason"), o.optLong("createdAt"), o.optLong("updatedAt"))
    }
}

/** Append-only stock ledger. Stock of a product = SUM(qtyMilli). Types: OPENING, PURCHASE, SALE, SALE_RETURN,
 *  PURCHASE_RETURN, CANCEL, DAMAGE, ADJUST. */
@Entity(indices = [Index("productId"), Index("date")])
data class StockMove(
    @PrimaryKey override val id: String, val productId: String, val type: String, val qtyMilli: Long,
    val unitCost: Long, val refId: String = "", val reason: String = "", val date: Long,
    val createdAt: Long, override val updatedAt: Long
) : Doc {
    override val coll get() = "stockMovements"
    override fun json() = j("id" to id, "productId" to productId, "type" to type, "qtyMilli" to qtyMilli, "unitCost" to unitCost,
        "refId" to refId, "reason" to reason, "date" to date, "createdAt" to createdAt, "updatedAt" to updatedAt)
    companion object {
        fun from(o: JSONObject) = StockMove(o.getString("id"), o.optString("productId"), o.optString("type"), o.optLong("qtyMilli"),
            o.optLong("unitCost"), o.optString("refId"), o.optString("reason"), o.optLong("date"), o.optLong("createdAt"), o.optLong("updatedAt"))
    }
}

/** Sales / purchase return line. amount = value credited to the party, cost = inventory cost of the goods. */
@Entity(indices = [Index("txnId"), Index("partyId"), Index("date")])
data class Ret(
    @PrimaryKey override val id: String, val kind: String, val txnId: String, val partyId: String, val productId: String,
    val name: String, val qtyMilli: Long, val amount: Long, val cost: Long, val date: Long, val reason: String = "",
    val createdAt: Long, override val updatedAt: Long
) : Doc {
    override val coll get() = "returns"
    override fun json() = j("id" to id, "kind" to kind, "txnId" to txnId, "partyId" to partyId, "productId" to productId,
        "name" to name, "qtyMilli" to qtyMilli, "amount" to amount, "cost" to cost, "date" to date, "reason" to reason,
        "createdAt" to createdAt, "updatedAt" to updatedAt)
    companion object {
        fun from(o: JSONObject) = Ret(o.getString("id"), o.optString("kind"), o.optString("txnId"), o.optString("partyId"),
            o.optString("productId"), o.optString("name"), o.optLong("qtyMilli"), o.optLong("amount"), o.optLong("cost"),
            o.optLong("date"), o.optString("reason"), o.optLong("createdAt"), o.optLong("updatedAt"))
    }
}

@Entity
data class Audit(
    @PrimaryKey override val id: String, val action: String, val entity: String, val entityId: String,
    val detail: String = "", val date: Long, val createdAt: Long, override val updatedAt: Long
) : Doc {
    override val coll get() = "auditLogs"
    override fun json() = j("id" to id, "action" to action, "entity" to entity, "entityId" to entityId, "detail" to detail,
        "date" to date, "createdAt" to createdAt, "updatedAt" to updatedAt)
    companion object {
        fun from(o: JSONObject) = Audit(o.getString("id"), o.optString("action"), o.optString("entity"), o.optString("entityId"),
            o.optString("detail"), o.optLong("date"), o.optLong("createdAt"), o.optLong("updatedAt"))
    }
}

/** Key/value settings. Keys starting with "local." stay on this device and are never synced. */
@Entity
data class Setting(@PrimaryKey val key: String, val value: String, override val updatedAt: Long) : Doc {
    override val id get() = key
    override val coll get() = "settings"
    override fun json() = j("id" to key, "value" to value, "createdAt" to updatedAt, "updatedAt" to updatedAt)
    companion object { fun from(o: JSONObject) = Setting(o.getString("id"), o.optString("value"), o.optLong("updatedAt")) }
}

/** Pending cloud writes. One row per document (newest wins), so retries are idempotent. */
@Entity
data class Outbox(@PrimaryKey val key: String, val coll: String, val docId: String, val json: String, val ts: Long)

data class PartyBal(val id: String, val bal: Long)
data class ProductRow(@androidx.room.Embedded val p: Product, val stock: Long)
