package com.hisabbondhu.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.hisabbondhu.app.auth.AppLock
import com.hisabbondhu.app.auth.Auth
import com.hisabbondhu.app.core.Fmt
import com.hisabbondhu.app.export.Files
import com.hisabbondhu.app.sync.Sync
import com.hisabbondhu.app.ui.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Files.cleanup(this)
        setContent { Root() }
    }
}

@Composable
private fun Root() {
    val ctx = LocalContext.current
    val uidState = remember { mutableStateOf(Auth.currentUid()) }
    LaunchedEffect(Unit) { Auth.uid.collect { uidState.value = it } }
    val uid = uidState.value
    var theme by remember { mutableStateOf(ctx.getSharedPreferences("ui", 0).getString("theme", "system") ?: "system") }
    var unlocked by remember { mutableStateOf(!AppLock.enabled(ctx)) }

    // Relock whenever the app goes to the background.
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val o = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_STOP && AppLock.enabled(ctx)) unlocked = false }
        owner.lifecycle.addObserver(o); onDispose { owner.lifecycle.removeObserver(o) }
    }

    HisabTheme(theme) {
        Surface(Modifier.fillMaxSize()) {
            when {
                uid == null -> LoginScreen()
                !unlocked -> LockScreen { unlocked = true }
                else -> {
                    val repo = remember(uid) { Graph.repo(ctx, uid) }
                    LaunchedEffect(uid) { Sync.kick(ctx); Sync.schedulePeriodic(ctx) }
                    AppHost(repo, theme) { t -> theme = t; ctx.getSharedPreferences("ui", 0).edit().putString("theme", t).apply() }
                }
            }
        }
    }
}

@Composable
private fun LoginScreen() {
    val act = LocalContext.current as android.app.Activity
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text("হিসাববন্ধু", fontSize = 40.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(8.dp))
        Text("পাইকারি ব্যবসার সম্পূর্ণ হিসাব, আপনার ফোনে", textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(40.dp))
        Button(onClick = { busy = true; err = null; scope.launch { err = Auth.signIn(act); busy = false } },
            enabled = !busy, modifier = Modifier.fillMaxWidth().height(56.dp)) {
            if (busy) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
            else Text("Google দিয়ে লগইন করুন", fontSize = 17.sp)
        }
        err?.let { Spacer(Modifier.height(16.dp)); Text(it, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center) }
        Spacer(Modifier.height(24.dp))
        Text("একবার লগইন করলে আর বারবার লগইন করতে হবে না। আপনার ব্যবসার তথ্য শুধু আপনার অ্যাকাউন্টেই থাকবে।",
            fontSize = 13.sp, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun LockScreen(onOk: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var pin by remember { mutableStateOf("") }
    var msg by remember { mutableStateOf("") }
    var wait by remember { mutableStateOf(AppLock.waitSeconds(ctx)) }
    var confirm by remember { mutableStateOf(false) }
    LaunchedEffect(wait) { if (wait > 0) { delay(1000); wait = AppLock.waitSeconds(ctx) } }
    Column(Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text("অ্যাপ লক", fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(pin, { if (it.length <= 8 && it.all(Char::isDigit)) pin = it }, label = { Text("পিন দিন") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword))
        Spacer(Modifier.height(12.dp))
        Button(onClick = { if (AppLock.verify(ctx, pin)) onOk() else { msg = "ভুল পিন"; pin = ""; wait = AppLock.waitSeconds(ctx) } },
            enabled = pin.length >= 4 && wait == 0L, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("আনলক") }
        if (wait > 0) Text("অনেকবার ভুল হয়েছে। ${Fmt.bn(wait.toString())} সেকেন্ড অপেক্ষা করুন", color = MaterialTheme.colorScheme.error)
        else if (msg.isNotEmpty()) Text(msg, color = MaterialTheme.colorScheme.error)
        Spacer(Modifier.height(20.dp))
        TextButton({ confirm = true }) { Text("পিন ভুলে গেছেন?") }
        if (confirm) Confirm("পিন রিসেট", "পিন রিসেট করতে লগআউট করে আবার Google দিয়ে লগইন করতে হবে। এই ফোনের যেসব পরিবর্তন এখনো আপলোড হয়নি তা মুছে যেতে পারে।",
            "লগআউট করুন", { confirm = false; scope.launch { Graph.logout(ctx) } }, { confirm = false })
    }
}
