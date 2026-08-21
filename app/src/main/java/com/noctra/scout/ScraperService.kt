package com.noctra.scout

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import java.text.NumberFormat

/**
 * Foreground service that walks the name space and logs each answer.
 *
 * Everything that matters is written to SQLite in small batches, and the resume cursor is
 * saved with each batch, so killing the app (or the OS killing it) loses at most one batch.
 */
class ScraperService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var worker: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private lateinit var prefs: Prefs
    private lateinit var db: Db

    private val buffer = ArrayList<Entry>(BATCH_SIZE)
    private var lastNotify = 0L
    private var windowStart = 0L
    private var windowCount = 0
    private var rate = 0

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        db = Db.get(this)
        createChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopEverything("Stopped")
            return START_NOT_STICKY
        }
        startForeground(NOTIF_ID, buildNotification("Starting…", ""))
        if (worker?.isActive != true) {
            prefs.running = true
            acquireWakeLock()
            worker = scope.launch { runLoop() }
        }
        return START_STICKY
    }

    private suspend fun runLoop() {
        val space = prefs.nameSpace()
        val client = DiscordClient(prefs.token)
        val (checked, available) = db.counts()

        var foundThisRun = 0
        var skipped = 0
        var rejects = 0
        var backoff = 2_000L
        // Auto-pacing state: paceMs never drops below the configured delay, and climbs
        // whenever Discord pushes back, so the loop settles on the fastest rate it allows.
        var paceMs = prefs.delayMs.toLong()
        var okStreak = 0
        var rateLimits = 0
        windowStart = System.currentTimeMillis()
        windowCount = 0

        ScraperState.update {
            it.copy(
                running = true,
                checked = checked,
                available = available,
                cursor = prefs.cursor,
                total = space.total,
                paceMs = paceMs.toInt(),
                rateLimits = 0,
                status = runStatus(client)
            )
        }

        while (scope.isActive) {
            val cursor = prefs.cursor
            if (cursor >= space.total) {
                flush()
                stopEverything("Entire name space checked")
                return
            }

            val stopAfter = prefs.stopAfter
            if (stopAfter > 0 && foundThisRun >= stopAfter) {
                flush()
                stopEverything("Stopped after $foundThisRun found")
                return
            }

            val name = space.nameAt(cursor)

            // After a character-set change the cursor restarts, so fast-forward over
            // anything already in the log instead of asking Discord about it again.
            if (db.contains(name)) {
                prefs.cursor = cursor + 1
                skipped++
                if (skipped % 500 == 0) {
                    ScraperState.update { it.copy(cursor = cursor, status = "Skipping already-checked names") }
                    updateNotification()
                    yield()
                }
                continue
            }
            skipped = 0

            ScraperState.update { it.copy(current = name, cursor = cursor) }

            when (val result = client.check(name)) {
                is CheckResult.Ok -> {
                    backoff = 2_000L
                    rejects = 0
                    val free = !result.taken
                    buffer.add(Entry(name, free, System.currentTimeMillis()))
                    prefs.cursor = cursor + 1
                    countTick()
                    okStreak++
                    if (prefs.autoPace && okStreak >= SPEED_UP_AFTER) {
                        okStreak = 0
                        paceMs = (paceMs * 19 / 20).coerceAtLeast(prefs.delayMs.toLong())
                    }
                    ScraperState.update {
                        it.copy(
                            checked = it.checked + 1,
                            available = if (free) it.available + 1 else it.available,
                            ratePerMin = rate,
                            paceMs = paceMs.toInt(),
                            endpoint = client.activeEndpoint,
                            status = runStatus(client)
                        )
                    }
                    if (free) {
                        foundThisRun++
                        flush()
                        ScraperState.notifyFound(name)
                        postFoundNotification(name)
                    } else if (buffer.size >= BATCH_SIZE) {
                        flush()
                    }
                    updateNotification()
                    delay(pace(paceMs))
                }

                is CheckResult.Invalid -> {
                    // A handful of names really are reserved, but a long run of rejections means
                    // the request shape is wrong — stop rather than log the whole space as taken.
                    rejects++
                    if (rejects >= MAX_CONSECUTIVE_REJECTS) {
                        flush()
                        stopEverything("Discord rejected $rejects names in a row: ${result.reason}")
                        return
                    }
                    // Discord will not hand out this name at all — record it as taken and move on.
                    buffer.add(Entry(name, false, System.currentTimeMillis()))
                    prefs.cursor = cursor + 1
                    countTick()
                    ScraperState.update { it.copy(checked = it.checked + 1, ratePerMin = rate) }
                    if (buffer.size >= BATCH_SIZE) flush()
                    updateNotification()
                    delay(pace(paceMs))
                }

                is CheckResult.RateLimited -> {
                    flush()
                    rateLimits++
                    okStreak = 0
                    if (prefs.autoPace) {
                        val floor = prefs.delayMs.toLong()
                        // The user's own delay wins if they set one above the pacing ceiling.
                        val ceiling = maxOf(MAX_PACE_MS, floor)
                        paceMs = (paceMs * 3 / 2).coerceIn(floor, ceiling)
                    }
                    val waitMs = result.retryAfterMs
                    val note = if (result.shared) {
                        " · public endpoint is a shared pool, add a token in Settings"
                    } else {
                        ""
                    }
                    ScraperState.update {
                        it.copy(
                            status = "Rate limited — waiting ${waitMs / 1000}s$note",
                            paceMs = paceMs.toInt(),
                            rateLimits = rateLimits
                        )
                    }
                    updateNotification(force = true)
                    delay(waitMs)
                }

                is CheckResult.Failure -> {
                    flush()
                    if (result.fatal) {
                        stopEverything(result.message)
                        return
                    }
                    ScraperState.update { it.copy(status = "Retrying — ${result.message}") }
                    updateNotification(force = true)
                    delay(backoff)
                    backoff = (backoff * 2).coerceAtMost(60_000L)
                }
            }
        }
    }

    /** Says which endpoint the run settled on, including when a bad token was skipped past. */
    private fun runStatus(client: DiscordClient): String = when {
        client.skippedAuth -> "Running — token rejected, using the public check"
        prefs.token.isNotBlank() -> "Running (token)"
        else -> "Running"
    }

    /** The configured wait plus a little jitter, so requests never land on a fixed cadence. */
    private fun pace(paceMs: Long): Long = paceMs + (0..250).random()

    private fun countTick() {
        windowCount++
        val now = System.currentTimeMillis()
        val elapsed = now - windowStart
        if (elapsed >= 30_000L) {
            rate = (windowCount * 60_000L / elapsed).toInt()
            windowStart = now
            windowCount = 0
        }
    }

    private fun flush() {
        if (buffer.isEmpty()) return
        db.insertBatch(buffer)
        buffer.clear()
        ScraperState.notifyDataChanged()
    }

    private fun stopEverything(reason: String) {
        worker?.cancel()
        worker = null
        flush()
        prefs.running = false
        ScraperState.update { it.copy(running = false, status = reason, current = "") }
        releaseWakeLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        flush()
        prefs.running = false
        ScraperState.update { it.copy(running = false, current = "") }
        releaseWakeLock()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ---- notifications -------------------------------------------------------------------

    private fun createChannels() {
        val nm = getSystemService(NotificationManager::class.java)
        val progress = NotificationChannel(
            CHANNEL_PROGRESS, "Scanning", NotificationManager.IMPORTANCE_LOW
        ).apply {
            setShowBadge(false)
            enableVibration(false)
        }
        val hits = NotificationChannel(
            CHANNEL_FOUND, "Available names", NotificationManager.IMPORTANCE_HIGH
        )
        nm.createNotificationChannel(progress)
        nm.createNotificationChannel(hits)
    }

    private fun contentIntent(): PendingIntent = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun buildNotification(title: String, text: String): Notification {
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, ScraperService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_PROGRESS)
            .setSmallIcon(R.drawable.ic_stat_moon)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(contentIntent())
            .setOngoing(true)
            .setSilent(true)
            .setColor(0xFF3C4856.toInt())
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(0, "Stop", stop)
            .build()
    }

    private fun updateNotification(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastNotify < 1_000L) return
        lastNotify = now
        val s = ScraperState.stats.value
        val nf = NumberFormat.getIntegerInstance()
        val title = "${nf.format(s.available)} available · ${nf.format(s.checked)} checked"
        val text = if (s.status.startsWith("Running")) "Trying ${s.current}" else s.status
        getSystemService(NotificationManager::class.java)
            .notify(NOTIF_ID, buildNotification(title, text))
    }

    private fun postFoundNotification(name: String) {
        val n = NotificationCompat.Builder(this, CHANNEL_FOUND)
            .setSmallIcon(R.drawable.ic_stat_moon)
            .setContentTitle("$name is available")
            .setContentText("Tap to open Noctra")
            .setContentIntent(contentIntent())
            .setAutoCancel(true)
            .setColor(0xFF3C4856.toInt())
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        getSystemService(NotificationManager::class.java)
            .notify(FOUND_NOTIF_BASE + (name.hashCode() and 0xFFFF), n)
    }

    // ---- wake lock -----------------------------------------------------------------------

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(PowerManager::class.java)
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "noctra:scan").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    companion object {
        const val ACTION_STOP = "com.noctra.scout.STOP"
        private const val CHANNEL_PROGRESS = "scan"
        private const val CHANNEL_FOUND = "found"
        private const val NOTIF_ID = 1001
        private const val FOUND_NOTIF_BASE = 2000
        private const val BATCH_SIZE = 20
        private const val MAX_CONSECUTIVE_REJECTS = 25
        // A 429 costs about a minute of backoff, so probing for a faster pace is only worth it
        // after a long clean streak, and in small steps. Speeding up eagerly just buys another
        // stall: measured against the live endpoint, backoff ate 420 of 472 seconds.
        private const val SPEED_UP_AFTER = 200
        private const val MAX_PACE_MS = 30_000L

        fun start(context: Context) {
            val i = Intent(context, ScraperService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(i)
            } else {
                context.startService(i)
            }
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, ScraperService::class.java).setAction(ACTION_STOP)
            )
        }
    }
}
