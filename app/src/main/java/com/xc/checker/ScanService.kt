package com.xc.checker

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.*

class ScanService : Service() {
    private var job: Job? = null
    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(i: Intent?, f: Int, id: Int): Int {
        if (i?.action == "stop") { stopScan(); return START_NOT_STICKY }
        channels()
        ServiceCompat.startForeground(this, 1, notif("Starting..."), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)

        val mode = i?.getStringExtra("mode") ?: "pattern"
        val pattern = i?.getStringExtra("pattern") ?: "@???"
        val list = i?.getStringExtra("list") ?: ""
        val delayMs = i?.getLongExtra("delay", 1000L) ?: 1000L
        val shuffle = i?.getBooleanExtra("shuffle", true) ?: true

        val seq: Sequence<String>
        val total: Long
        if (mode == "list") {
            val l = list.lines().map { it.trim() }.filter(Checker::valid).distinct()
            seq = l.asSequence(); total = l.size.toLong()
        } else {
            val n = Checker.count(pattern)
            val a = Checker.shuffleMult(n)
            total = n
            seq = (0L until n).asSequence()
                .map { Checker.at(pattern, if (shuffle && n < 10_000_000_000_000L) (it * a + 12345) % n else it) }
                .filter(Checker::valid)
        }
        job?.cancel()
        job = CoroutineScope(Dispatchers.IO).launch { scan(seq, total, delayMs) }
        return START_NOT_STICKY
    }

    private suspend fun scan(names: Sequence<String>, total: Long, delayMs: Long) {
        ScanState.running.value = true
        ScanState.checked.value = 0; ScanState.total.value = total
        var token = Auth.token(this)
        if (token == null) { ScanState.log.value = "Not signed in"; stopScan(); return }
        for (name in names) {
            currentCoroutineContext().ensureActive()
            var done = false
            while (!done) {
                when (val r = Checker.check(name, token!!)) {
                    is Checker.R.Free -> { hit(name); done = true }
                    is Checker.R.Taken -> done = true
                    is Checker.R.Limited -> { ScanState.log.value = "Rate limited, waiting ${r.waitMs / 1000}s"; delay(r.waitMs) }
                    is Checker.R.AuthFail -> {
                        ScanState.log.value = "Refreshing token..."
                        if (Auth.refresh(this)) token = Auth.token(this)
                        else { ScanState.log.value = "Token expired - sign in again"; stopScan(); return }
                    }
                    is Checker.R.Err -> { ScanState.log.value = "Error: ${r.m}"; delay(5000) }
                }
            }
            val c = ScanState.checked.value + 1
            ScanState.checked.value = c
            if (c % 20 == 0L) {
                ScanState.log.value = "Checked $c / $total (last: $name)"
                (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(1, notif("$c / $total"))
            }
            delay(delayMs)
        }
        ScanState.log.value = "Finished"
        stopScan()
    }

    private fun hit(name: String) {
        ScanState.hits.value = (ScanState.hits.value + name).distinct()
        val p = Auth.prefs(this)
        p.edit().putStringSet("hits", (p.getStringSet("hits", emptySet()) ?: emptySet()) + name).apply()
        val n = NotificationCompat.Builder(this, "hits")
            .setSmallIcon(android.R.drawable.star_on)
            .setContentTitle("Available: $name")
            .setContentText("Gamertag $name looks free - go claim it")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
            .build()
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(name.hashCode(), n)
    }

    private fun stopScan() {
        job?.cancel()
        ScanState.running.value = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun channels() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel("scan", "Scan progress", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel("hits", "Available names", NotificationManager.IMPORTANCE_HIGH))
    }

    private fun notif(text: String): Notification =
        NotificationCompat.Builder(this, "scan")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("Xbox checker running")
            .setContentText(text)
            .setOngoing(true).setOnlyAlertOnce(true)
            .addAction(0, "Stop", PendingIntent.getService(this, 0,
                Intent(this, ScanService::class.java).setAction("stop"), PendingIntent.FLAG_IMMUTABLE))
            .build()

    override fun onDestroy() { job?.cancel(); ScanState.running.value = false; super.onDestroy() }
}
