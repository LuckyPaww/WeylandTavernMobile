package com.weyland.nodepoc

import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import java.io.File
import kotlin.system.exitProcess

/**
 * Runs in its own process (android:process=":node" in the manifest).
 *
 * Launches a REAL Node.js executable (Termux-sourced, full ICU) as a child
 * process. The runtime ships inside the APK as fake native libraries
 * (libnode_bin.so + its lib*.so dependencies); Android extracts them to
 * nativeLibraryDir at install time, which is the one app-owned location
 * modern Android still allows exec() from.
 *
 * No JNI, no nodejs-mobile, no libnode embedding.
 */
class NodeService : Service() {

    companion object {
        private const val CHANNEL_ID = "weytav_server"
        private const val NOTIF_ID = 1

        const val ACTION_START = "com.weyland.ACTION_START_SERVER"
        const val ACTION_STOP = "com.weyland.ACTION_STOP_SERVER"

        const val EXTRA_INSTALL_DIR = "install_dir"
        const val EXTRA_LOG_FILE = "log_file"
        const val EXTRA_BOOTSTRAP_PATH = "bootstrap_path"

        fun start(ctx: Context, installDir: String, logFile: String, bootstrapPath: String) {
            val intent = Intent(ctx, NodeService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_INSTALL_DIR, installDir)
                putExtra(EXTRA_LOG_FILE, logFile)
                putExtra(EXTRA_BOOTSTRAP_PATH, bootstrapPath)
            }
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(intent)
            else ctx.startService(intent)
        }

        fun stop(ctx: Context) {
            val intent = Intent(ctx, NodeService::class.java).apply {
                action = ACTION_STOP
            }
            ctx.startService(intent)
        }
    }

    private var nodeProcess: Process? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        when (intent.action) {
            ACTION_STOP -> {
                nodeProcess?.destroy()
                nodeProcess = null
                stopSelf()
                exitProcess(0)
            }
            ACTION_START -> {
                createChannel()
                val notification = buildNotification()

                if (Build.VERSION.SDK_INT >= 34) {
                    // specialUse, not dataSync: Android 15 gives dataSync FGS a
                    // ~6h/day budget and then kills it — mid-chat, silently.
                    startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
                } else {
                    startForeground(NOTIF_ID, notification)
                }

                if (nodeProcess == null) {
                    val installDir = intent.getStringExtra(EXTRA_INSTALL_DIR) ?: run {
                        stopSelf(); return START_NOT_STICKY
                    }
                    val logFile = intent.getStringExtra(EXTRA_LOG_FILE) ?: run {
                        stopSelf(); return START_NOT_STICKY
                    }
                    val bootstrapPath = intent.getStringExtra(EXTRA_BOOTSTRAP_PATH) ?: run {
                        stopSelf(); return START_NOT_STICKY
                    }
                    launchNode(installDir, logFile, bootstrapPath)
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun launchNode(installDir: String, logFile: String, bootstrapPath: String) {
        val libDir = applicationInfo.nativeLibraryDir
        val nodeBin = File(libDir, "libnode_bin.so")
        val logF = File(logFile)
        logF.parentFile?.mkdirs()

        // Node's os.tmpdir() honours $TMPDIR; give it a real one.
        val tmpDir = File(cacheDir, "tmp").apply { mkdirs() }

        // Adaptive heap: quarter of device RAM, clamped 1024..2048 MB
        val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mem = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val heapMb = (mem.totalMem / 4 / 1048576).coerceIn(1024, 2048)

        try {
            logF.appendText("\n===== Service starting ${java.util.Date()} =====\n")
            logF.appendText("[SERVICE] nodeBin=$nodeBin exists=${nodeBin.exists()}\n")
            logF.appendText("[SERVICE] installDir=$installDir\n")
            logF.appendText("[SERVICE] server.js exists=${File(installDir, "SillyTavern/server.js").exists()}\n")
            logF.appendText("[SERVICE] heapMb=$heapMb\n")
        } catch (e: Exception) { /* best effort */ }

        val pb = ProcessBuilder(
            nodeBin.absolutePath,
            "--max-old-space-size=$heapMb",
            bootstrapPath
        )
        pb.directory(File(installDir))
        // Everything the child prints — including pre-JS failures like linker
        // errors — lands in the log file BapOS already knows how to display.
        pb.redirectErrorStream(true)
        pb.redirectOutput(ProcessBuilder.Redirect.appendTo(logF))

        val env = pb.environment()
        env["LD_LIBRARY_PATH"] = libDir
        env["WT_ROOT"] = installDir
        env["WT_LOG"] = logFile
        env["TMPDIR"] = tmpDir.absolutePath
        env["HOME"] = filesDir.absolutePath
        env["NODE_ENV"] = "production"
        // Termux's Node prefers an OpenSSL CA file at a Termux-only path.
        // Point it at the bundle MainActivity deploys from assets instead.
        val certFile = File(filesDir, "cacert.pem")
        if (certFile.exists()) env["SSL_CERT_FILE"] = certFile.absolutePath

        try {
            val proc = pb.start()
            nodeProcess = proc

            // Watchdog: log the exit code when (if) Node dies. The MainActivity
            // poller notices the port going dark and updates BapOS.
            Thread {
                val code = try { proc.waitFor() } catch (e: InterruptedException) { -1 }
                try {
                    logF.appendText("[SERVICE] node exited with code $code\n")
                } catch (e: Exception) {}
                if (nodeProcess === proc) nodeProcess = null
                stopSelf()
            }.start()
        } catch (e: Exception) {
            try {
                logF.appendText("[SERVICE] failed to launch node: ${e.javaClass.simpleName}: ${e.message}\n")
            } catch (e2: Exception) {}
            stopSelf()
        }
    }

    override fun onDestroy() {
        nodeProcess?.destroy()
        nodeProcess = null
        super.onDestroy()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Weyland Tavern Server",
                NotificationManager.IMPORTANCE_LOW
            )
            channel.description = "Keeps the Tavern server running in the background"
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getForegroundService(
            this, 1,
            Intent(this, NodeService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )

        val builder = if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION") Notification.Builder(this)
        }

        return builder
            .setContentTitle("Weyland Tavern is running, nya~")
            .setContentText("The server stays up while this notification is here.")
            .setSmallIcon(android.R.drawable.stat_notify_sync_noanim)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .addAction(
                Notification.Action.Builder(null, "Stop Server", stopIntent).build()
            )
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
