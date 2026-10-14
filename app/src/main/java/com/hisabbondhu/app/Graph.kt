package com.hisabbondhu.app

import android.content.Context
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import com.google.firebase.auth.FirebaseAuth
import com.hisabbondhu.app.auth.AppLock
import com.hisabbondhu.app.data.AppDb
import com.hisabbondhu.app.data.Repo
import com.hisabbondhu.app.export.Notifier
import com.hisabbondhu.app.sync.Sync
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withTimeoutOrNull

/** Holds the one open database. It is always keyed by the signed-in Firebase UID. */
object Graph {
    val syncError = MutableStateFlow<String?>(null)
    @Volatile private var current: Repo? = null

    fun repoOrNull(ctx: Context): Repo? {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return null
        return repo(ctx, uid)
    }

    @Synchronized
    fun repo(ctx: Context, uid: String): Repo {
        current?.takeIf { it.uid == uid }?.let { return it }
        current?.db?.close()
        val app = ctx.applicationContext
        val r = Repo(AppDb.open(app, uid), uid) { Sync.kick(app) }
        current = r
        return r
    }

    /** Tries one last sync, returns how many changes are still not uploaded. */
    suspend fun finalSync(ctx: Context): Int {
        val r = repoOrNull(ctx) ?: return 0
        withTimeoutOrNull(60_000) { Sync.run(r) }
        return r.pendingCount()
    }

    /**
     * Explicit logout: stops sync, removes this account's local cache and app lock, signs out of Firebase.
     * The caller must already have shown the confirmation (and the unsynced-changes warning).
     */
    suspend fun logout(ctx: Context) {
        val app = ctx.applicationContext
        val uid = FirebaseAuth.getInstance().currentUser?.uid
        Sync.cancelAll(app)
        Notifier.cancelAll(app)
        synchronized(this) { current?.db?.close(); current = null }
        if (uid != null) AppDb.delete(app, uid)
        AppLock.clear(app)
        syncError.value = null
        FirebaseAuth.getInstance().signOut()
        runCatching { CredentialManager.create(app).clearCredentialState(ClearCredentialStateRequest()) }
    }
}
