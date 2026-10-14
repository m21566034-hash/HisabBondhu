package com.hisabbondhu.app

import android.app.Application
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreSettings
import com.google.firebase.firestore.MemoryCacheSettings
import com.hisabbondhu.app.export.Notifier

class HisabApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Room is the offline store. Keeping Firestore's own disk cache off avoids a second copy of
        // every record (smaller storage use) and avoids stale reads after logout.
        FirebaseFirestore.getInstance().firestoreSettings = FirebaseFirestoreSettings.Builder()
            .setLocalCacheSettings(MemoryCacheSettings.newBuilder().build()).build()
        Notifier.createChannels(this)
    }
}
