package com.hisabbondhu.app.sync

import android.content.Context
import androidx.room.withTransaction
import androidx.work.*
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.*
import com.hisabbondhu.app.data.*
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Room is the source of truth. Writes are queued in the Outbox and pushed with set() on stable ids
 * (idempotent: a retry just writes the same document again). Pulls read only documents changed since
 * the last pull (updatedAt > cursor), which keeps Firestore reads low on the free Spark plan.
 * No realtime listeners and no polling: sync runs after writes, on app start, and every 6 hours.
 */
object Sync {
    private const val COLLS = "customers,suppliers,products,sales,purchases,payments,expenses,stockMovements,returns,auditLogs,settings"
    val collections = COLLS.split(",")

    fun kick(ctx: Context) {
        val req = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build()
        WorkManager.getInstance(ctx).enqueueUniqueWork("sync", ExistingWorkPolicy.APPEND_OR_REPLACE, req)
    }

    fun schedulePeriodic(ctx: Context) {
        val req = PeriodicWorkRequestBuilder<SyncWorker>(6, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build()
        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork("sync-periodic", ExistingPeriodicWorkPolicy.KEEP, req)
    }

    /** Cancels queued/periodic sync for the account that is logging out. */
    fun cancelAll(ctx: Context) {
        val wm = WorkManager.getInstance(ctx)
        wm.cancelUniqueWork("sync"); wm.cancelUniqueWork("sync-periodic")
    }

    private fun toValue(v: Any?): Any? = when (v) {
        is JSONObject -> toMap(v)
        is JSONArray -> (0 until v.length()).map { toValue(v.get(it)) }
        JSONObject.NULL -> null
        else -> v
    }
    private fun toMap(o: JSONObject): Map<String, Any?> = o.keys().asSequence().associateWith { toValue(o.get(it)) }

    private fun toJson(v: Any?): Any? = when (v) {
        is Map<*, *> -> JSONObject().also { o -> v.forEach { (k, x) -> o.put(k.toString(), toJson(x)) } }
        is List<*> -> JSONArray().also { a -> v.forEach { a.put(toJson(it)) } }
        else -> v
    }

    sealed class Result { object Ok : Result(); data class Retry(val why: String) : Result(); object AuthProblem : Result() }

    suspend fun run(repo: Repo): Result {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return Result.AuthProblem
        if (uid != repo.uid) return Result.AuthProblem            // never sync one account's cache into another
        val fs = FirebaseFirestore.getInstance()
        val root = fs.collection("users").document(uid)
        return try {
            push(repo, root, uid)
            pull(repo, root)
            Result.Ok
        } catch (e: FirebaseFirestoreException) {
            when (e.code) {
                FirebaseFirestoreException.Code.PERMISSION_DENIED, FirebaseFirestoreException.Code.UNAUTHENTICATED -> Result.AuthProblem
                else -> Result.Retry(e.code.name)               // includes RESOURCE_EXHAUSTED (quota) and UNAVAILABLE
            }
        } catch (e: Exception) { Result.Retry(e.javaClass.simpleName) }
    }

    private suspend fun push(repo: Repo, root: DocumentReference, uid: String) {
        val dao = repo.dao
        root.set(mapOf("schema" to 1, "ownerUid" to uid), SetOptions.merge()).awaitT()
        while (true) {
            val batchRows = dao.pending(400)
            if (batchRows.isEmpty()) return
            val b = FirebaseFirestore.getInstance().batch()
            batchRows.forEach { r ->
                val m = HashMap(toMap(JSONObject(r.json))); m["ownerUid"] = uid
                b.set(root.collection(r.coll).document(r.docId), m)
            }
            b.commit().awaitT()
            dao.clearOutbox(batchRows.map { it.key }, batchRows.maxOf { it.ts })
        }
    }

    private suspend fun <T> com.google.android.gms.tasks.Task<T>.awaitT(): T = withTimeout(45_000) { await() }

    private suspend fun pull(repo: Repo, root: DocumentReference) {
        val dao = repo.dao
        for (c in collections) {
            var cursor = dao.setting("local.pull.$c")?.value?.toLongOrNull() ?: 0L
            while (true) {
                val snap = root.collection(c).whereGreaterThanOrEqualTo("updatedAt", cursor).orderBy("updatedAt").limit(300).get().awaitT()
                if (snap.isEmpty) break
                val docs = snap.documents.mapNotNull { d -> d.data?.let { toJson(it) as JSONObject } }
                repo.db.withTransaction { docs.forEach { applyRemote(repo, c, it) } }
                val next = docs.maxOf { it.optLong("updatedAt") }
                dao.upsert(Setting("local.pull.$c", next.toString(), System.currentTimeMillis()))
                // >= is used so documents sharing the cursor millisecond are never skipped; re-applying is idempotent
                if (snap.size() < 300 || next == cursor) break
                cursor = next
            }
        }
    }

    /** Remote wins only if it is newer than the local copy and the local copy has no unsent changes. */
    private suspend fun applyRemote(repo: Repo, c: String, o: JSONObject) {
        val dao = repo.dao
        val id = o.optString("id"); if (id.isEmpty()) return
        val pending = dao.isPending("$c/$id") != null
        when (c) {
            "customers", "suppliers" -> { val x = Party.from(o); val l = dao.party(id); if (!pending && (l == null || x.updatedAt > l.updatedAt)) dao.upsert(x) }
            "products" -> { val x = Product.from(o); val l = dao.product(id); if (!pending && (l == null || x.updatedAt > l.updatedAt)) dao.upsert(x) }
            "sales", "purchases" -> {
                val x = Txn.from(o); val l = dao.txn(id)
                if (!pending && (l == null || x.updatedAt > l.updatedAt)) {
                    dao.upsert(x); dao.deleteItems(id); dao.upsertItems(Txn.itemsFrom(o))
                }
            }
            "payments" -> if (dao.payment(id) == null) dao.upsert(Payment.from(o))
            "expenses" -> { val x = Expense.from(o); val l = dao.expense(id); if (!pending && (l == null || x.updatedAt > l.updatedAt)) dao.upsert(x) }
            "stockMovements" -> dao.upsert(StockMove.from(o))      // append-only: same id = same content
            "returns" -> dao.upsert(Ret.from(o))
            "auditLogs" -> dao.upsert(Audit.from(o))
            "settings" -> { val x = Setting.from(o); val l = dao.setting(id); if (!pending && !id.startsWith("local.") && (l == null || x.updatedAt > l.updatedAt)) dao.upsert(x) }
        }
    }
}

class SyncWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val repo = com.hisabbondhu.app.Graph.repoOrNull(applicationContext) ?: return Result.success()
        return when (Sync.run(repo)) {
            is Sync.Result.Ok -> { com.hisabbondhu.app.Graph.syncError.value = null; Result.success() }
            is Sync.Result.Retry -> Result.retry()
            Sync.Result.AuthProblem -> {
                // Keep all local data. The user is asked to sign in again; nothing is cleared.
                com.hisabbondhu.app.Graph.syncError.value = "সেশন যাচাই করা যায়নি। আবার লগইন করুন"
                Result.success()
            }
        }
    }
}
