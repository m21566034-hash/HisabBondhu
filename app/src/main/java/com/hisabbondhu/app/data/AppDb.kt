package com.hisabbondhu.app.data

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow

const val ROWS = "SELECT p.*, COALESCE((SELECT SUM(m.qtyMilli) FROM StockMove m WHERE m.productId=p.id),0) AS stock FROM Product p"

@Dao
interface AppDao {
    // ---- parties
    @Query("SELECT * FROM Party WHERE kind=:k ORDER BY archived, name COLLATE NOCASE")
    fun parties(k: String): Flow<List<Party>>
    @Query("SELECT * FROM Party WHERE kind=:k") suspend fun partiesOnce(k: String): List<Party>
    @Query("SELECT * FROM Party WHERE id=:id") suspend fun party(id: String): Party?
    @Query("SELECT * FROM Party WHERE id=:id") fun partyFlow(id: String): Flow<Party?>
    @Upsert suspend fun upsert(p: Party)
    @Query("SELECT COUNT(*) FROM Party WHERE kind=:k") suspend fun partyCount(k: String): Int
    @Query("SELECT * FROM Party WHERE kind=:k AND archived=0 AND id<>:exceptId AND (LOWER(name)=LOWER(:name) OR (:phone<>'' AND phone=:phone))")
    suspend fun dupes(k: String, name: String, phone: String, exceptId: String): List<Party>

    @Query("""SELECT p.id AS id, p.openingDue
        + COALESCE((SELECT SUM(total) FROM Txn t WHERE t.partyId=p.id AND t.status='ACTIVE'),0)
        - COALESCE((SELECT SUM(amount) FROM Payment y WHERE y.partyId=p.id),0)
        - COALESCE((SELECT SUM(amount) FROM Ret r WHERE r.partyId=p.id),0) AS bal FROM Party p WHERE p.kind=:k""")
    fun balances(k: String): Flow<List<PartyBal>>
    @Query("""SELECT p.openingDue
        + COALESCE((SELECT SUM(total) FROM Txn t WHERE t.partyId=p.id AND t.status='ACTIVE'),0)
        - COALESCE((SELECT SUM(amount) FROM Payment y WHERE y.partyId=p.id),0)
        - COALESCE((SELECT SUM(amount) FROM Ret r WHERE r.partyId=p.id),0) FROM Party p WHERE p.id=:id""")
    suspend fun balance(id: String): Long?
    @Query("""SELECT p.openingDue
        + COALESCE((SELECT SUM(total) FROM Txn t WHERE t.partyId=p.id AND t.status='ACTIVE'),0)
        - COALESCE((SELECT SUM(amount) FROM Payment y WHERE y.partyId=p.id),0)
        - COALESCE((SELECT SUM(amount) FROM Ret r WHERE r.partyId=p.id),0) FROM Party p WHERE p.id=:id""")
    fun balanceFlow(id: String): Flow<Long?>

    // ---- products & stock
    @Query("$ROWS ORDER BY p.archived, p.name COLLATE NOCASE") fun productRows(): Flow<List<ProductRow>>
    @Query("$ROWS ORDER BY p.name COLLATE NOCASE") suspend fun productRowsOnce(): List<ProductRow>
    @Query("$ROWS WHERE p.id=:id") suspend fun productRow(id: String): ProductRow?
    @Query("SELECT * FROM Product WHERE id=:id") suspend fun product(id: String): Product?
    @Upsert suspend fun upsert(p: Product)
    @Query("SELECT COALESCE(SUM(qtyMilli),0) FROM StockMove WHERE productId=:id") suspend fun stockOf(id: String): Long
    @Upsert suspend fun upsert(m: StockMove)
    @Query("SELECT * FROM StockMove WHERE productId=:id ORDER BY date DESC, createdAt DESC LIMIT 200") fun moves(id: String): Flow<List<StockMove>>
    @Query("SELECT * FROM StockMove WHERE date>=:from ORDER BY date") suspend fun movesFrom(from: Long): List<StockMove>
    @Query("SELECT * FROM StockMove WHERE date>=:from AND date<:to ORDER BY date") suspend fun movesBetween(from: Long, to: Long): List<StockMove>

    // ---- transactions
    @Query("SELECT * FROM Txn WHERE id=:id") suspend fun txn(id: String): Txn?
    @Query("SELECT * FROM Txn WHERE id=:id") fun txnFlow(id: String): Flow<Txn?>
    @Upsert suspend fun upsert(t: Txn)
    @Query("DELETE FROM TxnItem WHERE txnId=:id") suspend fun deleteItems(id: String)
    @Upsert suspend fun upsertItems(items: List<TxnItem>)
    @Query("SELECT * FROM TxnItem WHERE txnId=:id") suspend fun items(id: String): List<TxnItem>
    @Query("SELECT * FROM TxnItem WHERE txnId=:id") fun itemsFlow(id: String): Flow<List<TxnItem>>
    @Query("SELECT * FROM Txn WHERE kind=:k AND (number LIKE :q OR partyName LIKE :q OR partyPhone LIKE :q) ORDER BY date DESC, createdAt DESC LIMIT :n")
    fun txns(k: String, q: String, n: Int): Flow<List<Txn>>
    @Query("SELECT * FROM Txn WHERE partyId=:p ORDER BY date DESC") fun txnsOfParty(p: String): Flow<List<Txn>>
    @Query("SELECT * FROM Txn WHERE kind=:k AND status='ACTIVE' AND date>=:f AND date<:t ORDER BY date") suspend fun txnsBetween(k: String, f: Long, t: Long): List<Txn>
    @Query("SELECT i.* FROM TxnItem i JOIN Txn t ON i.txnId=t.id WHERE t.kind=:k AND t.status='ACTIVE' AND t.date>=:f AND t.date<:t")
    suspend fun itemsBetween(k: String, f: Long, t: Long): List<TxnItem>
    @Query("SELECT * FROM Txn ORDER BY date") suspend fun allTxns(): List<Txn>
    @Query("SELECT * FROM TxnItem") suspend fun allItems(): List<TxnItem>

    // ---- payments
    @Upsert suspend fun upsert(p: Payment)
    @Query("SELECT * FROM Payment WHERE id=:id") suspend fun payment(id: String): Payment?
    @Query("SELECT * FROM Payment WHERE reversalOf=:id LIMIT 1") suspend fun reversalOf(id: String): Payment?
    @Query("SELECT * FROM Payment WHERE partyId=:p ORDER BY date DESC, createdAt DESC") fun paymentsOfParty(p: String): Flow<List<Payment>>
    @Query("SELECT * FROM Payment WHERE refId=:r") suspend fun paymentsOfRef(r: String): List<Payment>
    @Query("SELECT COALESCE(SUM(amount),0) FROM Payment WHERE refId=:r") fun paidOnRef(r: String): Flow<Long>
    @Query("SELECT * FROM Payment ORDER BY date DESC, createdAt DESC LIMIT :n") fun recentPayments(n: Int): Flow<List<Payment>>
    @Query("SELECT * FROM Payment WHERE date>=:f AND date<:t") suspend fun paymentsBetween(f: Long, t: Long): List<Payment>
    @Query("SELECT * FROM Payment") suspend fun allPayments(): List<Payment>
    @Query("SELECT COALESCE(SUM(amount),0) FROM Payment WHERE kind='IN' AND date<:before") suspend fun cashInBefore(before: Long): Long

    // ---- expenses
    @Upsert suspend fun upsert(e: Expense)
    @Query("SELECT * FROM Expense WHERE id=:id") suspend fun expense(id: String): Expense?
    @Query("SELECT * FROM Expense WHERE (category LIKE :q OR description LIKE :q OR ref LIKE :q) ORDER BY date DESC, createdAt DESC LIMIT :n")
    fun expenses(q: String, n: Int): Flow<List<Expense>>
    @Query("SELECT * FROM Expense WHERE status='ACTIVE' AND date>=:f AND date<:t ORDER BY date") suspend fun expensesBetween(f: Long, t: Long): List<Expense>
    @Query("SELECT * FROM Expense WHERE status='ACTIVE' ORDER BY date DESC LIMIT :n") fun recentExpenses(n: Int): Flow<List<Expense>>
    @Query("SELECT * FROM Expense") suspend fun allExpenses(): List<Expense>

    // ---- returns, audit
    @Upsert suspend fun upsert(r: Ret)
    @Query("SELECT * FROM Ret WHERE txnId=:id") suspend fun retsOfTxn(id: String): List<Ret>
    @Query("SELECT * FROM Ret WHERE txnId=:id") fun retsOfTxnFlow(id: String): Flow<List<Ret>>
    @Query("SELECT * FROM Ret WHERE date>=:f AND date<:t") suspend fun retsBetween(f: Long, t: Long): List<Ret>
    @Query("SELECT * FROM Ret") suspend fun allRets(): List<Ret>
    @Upsert suspend fun upsert(a: Audit)
    @Query("SELECT * FROM Audit ORDER BY date DESC LIMIT :n") fun audits(n: Int): Flow<List<Audit>>
    @Query("SELECT * FROM Audit") suspend fun allAudits(): List<Audit>
    @Query("SELECT * FROM StockMove") suspend fun allMoves(): List<StockMove>

    // ---- settings
    @Upsert suspend fun upsert(s: Setting)
    @Query("SELECT * FROM Setting WHERE `key`=:k") suspend fun setting(k: String): Setting?
    @Query("SELECT * FROM Setting") fun settings(): Flow<List<Setting>>
    @Query("SELECT * FROM Setting") suspend fun allSettings(): List<Setting>

    // ---- outbox
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun put(o: Outbox)
    @Query("SELECT * FROM Outbox ORDER BY ts LIMIT :n") suspend fun pending(n: Int): List<Outbox>
    @Query("DELETE FROM Outbox WHERE `key` IN (:keys) AND ts<=:maxTs") suspend fun clearOutbox(keys: List<String>, maxTs: Long)
    @Query("SELECT COUNT(*) FROM Outbox") fun pendingCount(): Flow<Int>
    @Query("SELECT COUNT(*) FROM Outbox") suspend fun pendingCountOnce(): Int
    @Query("SELECT docId FROM Outbox") fun pendingIds(): Flow<List<String>>
    @Query("SELECT 1 FROM Outbox WHERE `key`=:k") suspend fun isPending(k: String): Int?
}

@Database(
    entities = [Party::class, Product::class, Txn::class, TxnItem::class, Payment::class, Expense::class,
        StockMove::class, Ret::class, Audit::class, Setting::class, Outbox::class],
    version = 1, exportSchema = false
)
abstract class AppDb : RoomDatabase() {
    abstract fun dao(): AppDao

    companion object {
        /** One database file per Firebase UID so accounts can never see each other's cache. */
        fun name(uid: String) = "hisab_$uid.db"
        fun open(ctx: Context, uid: String): AppDb =
            Room.databaseBuilder(ctx.applicationContext, AppDb::class.java, name(uid)).build()
        fun delete(ctx: Context, uid: String) { ctx.applicationContext.deleteDatabase(name(uid)) }
    }
}
