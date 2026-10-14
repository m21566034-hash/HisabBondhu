package com.hisabbondhu.app.auth

import android.content.Context
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import com.hisabbondhu.app.R
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await

object Auth {
    /** Emits the signed-in UID (or null). Firebase restores the session by itself after restarts. */
    val uid: Flow<String?> = callbackFlow {
        val a = FirebaseAuth.getInstance()
        val l = FirebaseAuth.AuthStateListener { trySend(it.currentUser?.uid) }
        a.addAuthStateListener(l)
        awaitClose { a.removeAuthStateListener(l) }
    }

    fun currentUid(): String? = FirebaseAuth.getInstance().currentUser?.uid
    fun displayName(): String = FirebaseAuth.getInstance().currentUser?.displayName.orEmpty()
    fun email(): String = FirebaseAuth.getInstance().currentUser?.email.orEmpty()

    /** Returns null on success, otherwise a Bangla error message. The Google token is never stored by the app. */
    suspend fun signIn(activity: android.app.Activity): String? {
        return try {
            val opt = GetGoogleIdOption.Builder()
                .setFilterByAuthorizedAccounts(false)
                .setServerClientId(activity.getString(R.string.default_web_client_id))
                .build()
            val req = GetCredentialRequest.Builder().addCredentialOption(opt).build()
            val cred = CredentialManager.create(activity).getCredential(activity, req).credential
            if (cred is CustomCredential && cred.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                val g = GoogleIdTokenCredential.createFrom(cred.data)
                FirebaseAuth.getInstance().signInWithCredential(GoogleAuthProvider.getCredential(g.idToken, null)).await()
                null
            } else "লগইন করা যায়নি। আবার চেষ্টা করুন"
        } catch (e: GetCredentialCancellationException) {
            "লগইন বাতিল করা হয়েছে"
        } catch (e: NoCredentialException) {
            "ফোনে কোনো Google অ্যাকাউন্ট পাওয়া যায়নি, অথবা Firebase-এ SHA-1 যোগ করা হয়নি"
        } catch (e: GetCredentialException) {
            "Google লগইন ব্যর্থ হয়েছে (${e.type.substringAfterLast('.')})। ইন্টারনেট ও SHA-1 সেটআপ দেখুন"
        } catch (e: Exception) {
            "লগইন ব্যর্থ হয়েছে। ইন্টারনেট সংযোগ দেখে আবার চেষ্টা করুন"
        }
    }
}
