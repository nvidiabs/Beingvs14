package com.xc.checker

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        ScanState.hits.value = (Auth.prefs(this).getStringSet("hits", emptySet()) ?: emptySet()).toList().sorted()
        setContent { MaterialTheme(colorScheme = darkColorScheme()) { Surface(Modifier.fillMaxSize()) { App() } } }
    }
}

@Composable
fun App() {
    val ctx: Context = LocalContext.current
    val scope = rememberCoroutineScope()
    val clip = LocalClipboardManager.current
    val uri = LocalUriHandler.current
    val p = remember { Auth.prefs(ctx) }

    var cid by remember { mutableStateOf(p.getString("client", "") ?: "") }
    var status by remember { mutableStateOf(if (Auth.token(ctx) != null) "Token saved" else "Not signed in") }
    var manual by remember { mutableStateOf("") }
    var mode by remember { mutableStateOf("4c") }
    var pattern by remember { mutableStateOf("@???") }
    var names by remember { mutableStateOf("") }
    var delayMs by remember { mutableStateOf("1000") }
    var shuffle by remember { mutableStateOf(true) }

    val running by ScanState.running.collectAsState()
    val log by ScanState.log.collectAsState()
    val hits by ScanState.hits.collectAsState()
    val checked by ScanState.checked.collectAsState()
    val total by ScanState.total.collectAsState()

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).statusBarsPadding().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text("XC Checker", style = MaterialTheme.typography.headlineSmall)

        Text("1 · Account", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(cid, { cid = it.trim() }, label = { Text("Azure app (client) ID") },
            singleLine = true, modifier = Modifier.fillMaxWidth())
        Button(onClick = {
            scope.launch {
                try {
                    status = "Requesting code..."
                    val d = Auth.startDeviceCode(cid)
                    clip.setText(AnnotatedString(d.userCode))
                    status = "Open ${d.url} and enter  ${d.userCode}  (copied)"
                    uri.openUri(d.url)
                    Auth.pollDeviceCode(ctx, cid, d)
                    status = "Signed in - token saved"
                } catch (e: Exception) { status = "Error: ${e.message}" }
            }
        }, enabled = cid.isNotEmpty()) { Text("Sign in with code") }
        Text(status, style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(manual, { manual = it }, label = { Text("...or paste XBL3.0 token") },
            modifier = Modifier.fillMaxWidth())
        OutlinedButton(onClick = { Auth.setManual(ctx, manual); status = "Token saved"; manual = "" },
            enabled = manual.isNotBlank()) { Text("Use pasted token") }

        HorizontalDivider()
        Text("2 · What to check", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(mode == "4c", { mode = "4c"; pattern = "@???" }, { Text("All 4C") })
            FilterChip(mode == "pattern", { mode = "pattern" }, { Text("Custom") })
            FilterChip(mode == "list", { mode = "list" }, { Text("Manual") })
        }
        when (mode) {
            "4c" -> Text("Every 4-char name starting with a letter (~1.2M). Pattern: @???", style = MaterialTheme.typography.bodySmall)
            "pattern" -> {
                OutlinedTextField(pattern, { pattern = it.lowercase() }, label = { Text("Pattern") },
                    supportingText = { Text("@ letter · # digit · ? any · other = literal. e.g. c?n#, @@@@@, x#x#x") },
                    singleLine = true, modifier = Modifier.fillMaxWidth())
            }
            else -> OutlinedTextField(names, { names = it }, label = { Text("One name per line") },
                modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp))
        }
        if (mode != "list") Row {
            Checkbox(shuffle, { shuffle = it }); Text("Random order", Modifier.padding(top = 12.dp))
        }
        OutlinedTextField(delayMs, { delayMs = it.filter(Char::isDigit) }, label = { Text("Delay between checks (ms)") },
            singleLine = true, modifier = Modifier.fillMaxWidth())

        Button(onClick = {
            val i = Intent(ctx, ScanService::class.java)
            if (running) i.action = "stop"
            else i.putExtra("mode", if (mode == "list") "list" else "pattern")
                .putExtra("pattern", pattern).putExtra("list", names)
                .putExtra("delay", delayMs.toLongOrNull() ?: 1000L).putExtra("shuffle", shuffle)
            ContextCompat.startForegroundService(ctx, i)
        }, modifier = Modifier.fillMaxWidth()) { Text(if (running) "Stop" else "Start scan") }

        Text("$log   ($checked / $total)", style = MaterialTheme.typography.bodySmall)

        HorizontalDivider()
        Text("Available (${hits.size})", style = MaterialTheme.typography.titleMedium)
        hits.forEach { Text(it, style = MaterialTheme.typography.bodyLarge) }
    }
}
