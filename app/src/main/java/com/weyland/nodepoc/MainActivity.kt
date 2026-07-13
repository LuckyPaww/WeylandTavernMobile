package com.weyland.nodepoc

import android.Manifest
import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.Socket
import java.net.URL
import java.util.zip.ZipInputStream

class MainActivity : AppCompatActivity() {

    companion object {
        private const val REPO_ZIP_URL =
            "https://codeload.github.com/Shirubaurufu/WeylandTavern/zip/refs/heads/release"
        private const val COMMIT_API_URL =
            "https://api.github.com/repos/Shirubaurufu/WeylandTavern/commits/release"

        private const val TAVERN_URL = "http://127.0.0.1:8000/"
        private const val BAPOS_URL = "file:///android_asset/bapos.html"
        private const val PREFS = "weytav"
    }

    private lateinit var webView: WebView
    private val handler = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences(PREFS, Context.MODE_PRIVATE) }

    private val installDir: File get() = File(filesDir, "weytav")
    private val logFile: File get() = File(filesDir, "tavern.log")
    private var pollerRunning = false

    // Set while a Repair-triggered character redownload has its own
    // temporary server up. The status poller checks this so it doesn't
    // mistake that internal server for a real launch — without this, the
    // poller sees "up" then "down" a few seconds later and reports a false
    // "server crashed" the moment the temporary server shuts down.
    @Volatile private var internalServerBusy = false

    // ==================================================================
    // Lifecycle
    // ==================================================================

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Hide status bar — BapOS handles its own top padding
        @Suppress("DEPRECATION")
        window.setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN
        )

        trimLogFile()

        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            // Assets (file:///android_asset) stay accessible with this off;
            // it only blocks the WebView from reading arbitrary files.
            settings.allowFileAccess = false
            settings.mediaPlaybackRequiresUserGesture = false
            // Without a WebChromeClient, WebView silently swallows JS
            // alert()/confirm() — BapOS' destructive-action confirmations
            // would never appear and the buttons would do nothing.
            webChromeClient = WebChromeClient()
            // The native bridge is powerful (delete install, read logs), so
            // never let this WebView navigate anywhere except our own assets.
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(
                    view: WebView,
                    request: WebResourceRequest,
                ): Boolean {
                    return !request.url.toString().startsWith("file:///android_asset/")
                }
            }
            addJavascriptInterface(WeyTavBridge(), "WeyTavNative")
        }
        setContentView(webView)

        // If the server is already running OR was recently launched (still
        // booting), skip the gate screen and go straight to home.
        if (isServerRunning() || wasServerLaunched()) {
            webView.loadUrl("$BAPOS_URL#home")
            startStatusPoller()
        } else {
            webView.loadUrl(BAPOS_URL)
        }

        // Back does nothing on BapOS — no accidental exits while the server
        // runs. Users shut down via the button. Tavern lives in Chrome.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { /* intentionally nothing */ }
        })
    }

    override fun onDestroy() {
        super.onDestroy()
        // Don't clear the launched flag here — we want it to survive
        // activity recreation (config changes, split-screen, etc.)
    }

    // ==================================================================
    // JavaScript bridge
    // ==================================================================

    inner class WeyTavBridge {

        @JavascriptInterface
        fun getStateJson(): String {
            val state = JSONObject()
            state.put("installed", File(installDir, "SillyTavern/server.js").exists())
            state.put("version", prefs.getString("version", null))
            state.put("soundOn", prefs.getString("sound", "on") == "on")
            state.put("serverRunning", isServerRunning())
            state.put("serverLaunched", wasServerLaunched())
            state.put("everLaunched", prefs.getBoolean("everLaunched", false))
            return state.toString()
        }

        @JavascriptInterface
        fun getDeviceInfo(): String {
            val info = JSONObject()
            try {
                val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
                val mem = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
                val ramGb = mem.totalMem / 1073741824.0
                info.put("manufacturer", Build.MANUFACTURER?.replaceFirstChar { it.uppercase() } ?: "Unknown")
                info.put("model", Build.MODEL ?: "Unknown device")
                info.put("androidVersion", Build.VERSION.RELEASE ?: "?")
                info.put("sdkInt", Build.VERSION.SDK_INT)
                info.put("abi", Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown")
                info.put("ramGb", String.format("%.1f", ramGb))
            } catch (e: Exception) { /* the boot theater just skips what it can't detect */ }
            return info.toString()
        }

        @JavascriptInterface
        fun setSoundPref(value: String) {
            prefs.edit().putString("sound", value).apply()
        }

        @JavascriptInterface
        fun beginInstall() = Thread { performInstall(isUpdate = false) }.start()

        @JavascriptInterface
        fun applyUpdate() = Thread { performInstall(isUpdate = true) }.start()

        @JavascriptInterface
        fun repairInstall(redownloadCharacters: Boolean) = Thread {
            // Unlike Update (which only overlays new files on top of old
            // ones), Repair wipes SillyTavern/ back to a clean slate first —
            // this is the mobile equivalent of the desktop tool's
            // `git reset --hard`. It's the only way to actually clear out
            // stray/corrupted files a bad update left behind, since a zip
            // overlay never deletes anything. SillyTavern/data (chats,
            // characters, settings) is never touched.
            performInstall(
                isUpdate = true,
                forceModules = true,
                deepClean = true,
                redownloadChars = redownloadCharacters,
            )
        }.start()

        @JavascriptInterface
        fun reinstall() = Thread {
            installDir.deleteRecursively()
            prefs.edit().remove("version").apply()
            performInstall(isUpdate = false)
        }.start()

        @JavascriptInterface
        fun deleteInstall() {
            Thread {
                installDir.deleteRecursively()
                prefs.edit().remove("version").remove("launched").apply()
                runOnUiThread { webView.loadUrl(BAPOS_URL) }
            }.start()
        }

        @JavascriptInterface
        fun launchServer() {
            runOnUiThread { doLaunchServer() }
        }

        @JavascriptInterface
        fun openExternalUrl(url: String) {
            runOnUiThread {
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                } catch (e: Exception) {
                    pushServerEvent("status", "Couldn't open a browser. Visit $url manually.")
                }
            }
        }

        @JavascriptInterface
        fun openTavern() {
            runOnUiThread {
                val uri = Uri.parse(TAVERN_URL)
                // Prefer Chrome (battle-tested rendering).
                val chrome = Intent(Intent.ACTION_VIEW, uri).setPackage("com.android.chrome")
                try {
                    startActivity(chrome)
                } catch (e: Exception) {
                    try {
                        startActivity(Intent(Intent.ACTION_VIEW, uri))
                    } catch (e2: Exception) {
                        pushServerEvent("status", "Couldn't find a browser. Visit $TAVERN_URL manually.")
                    }
                }
            }
        }

        @JavascriptInterface
        fun stopApp() {
            runOnUiThread {
                prefs.edit().remove("launched").apply()
                NodeService.stop(this@MainActivity)
                finishAffinity()
                handler.postDelayed({ System.exit(0) }, 400)
            }
        }

        @JavascriptInterface
        fun onHomeReady() {
            if (!isServerRunning() && !wasServerLaunched()) {
                Thread { checkForUpdates() }.start()
            }
            startStatusPoller()
        }

        @JavascriptInterface
        fun getLogs(): String {
            return try {
                if (!logFile.exists()) "(no logs yet — launch the server first)"
                else {
                    val text = logFile.readText()
                    if (text.isBlank()) "(log file exists but is empty)"
                    else {
                        val lines = text.lines()
                        lines.takeLast(400).joinToString("\n")
                    }
                }
            } catch (e: Exception) { "(couldn't read logs: ${e.javaClass.simpleName}: ${e.message})" }
        }

        @JavascriptInterface
        fun copyLogs() {
            runOnUiThread {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("WeyTav logs", getLogs()))
            }
        }

        @JavascriptInterface
        fun requestBatteryExemption() {
            runOnUiThread {
                try {
                    val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                    if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                        @SuppressLint("BatteryLife")
                        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                            .setData(Uri.parse("package:$packageName"))
                        startActivity(intent)
                    } else {
                        pushServerEvent("status", "Battery exemption already granted!")
                    }
                } catch (e: Exception) {
                    pushServerEvent("status", "Couldn't open battery settings: ${e.javaClass.simpleName}")
                }
            }
        }
    }

    // ==================================================================
    // Install / update
    // ==================================================================

    private val dataBackupDir: File get() = File(filesDir, "data-backup-pre-repair")

    private fun performInstall(
        isUpdate: Boolean,
        forceModules: Boolean = false,
        deepClean: Boolean = false,
        redownloadChars: Boolean = false,
    ) {
        // BapOS's "shut down first" gate on the JS side trusts a snapshot of
        // app state that isn't always freshly refreshed. Deep-clean deletes
        // files out from under a running server, so double-check for real
        // here — before spending a ~700MB download on a repair that's about
        // to fail anyway.
        if (deepClean && isServerRunning()) {
            pushInstallEvent(
                JSONObject().put("type", "installError")
                    .put("message", "The server is still running — shut it down first, then repair.")
            )
            return
        }

        // BapOS's Settings screen already hides/disables Repair until the
        // server has come up successfully once, but the button click isn't
        // the only path here — check again server-side. Repairing an
        // install that's never actually run isn't "fixing a broken update,"
        // it's just a confusing reinstall wearing the wrong label.
        if (deepClean && !prefs.getBoolean("everLaunched", false)) {
            pushInstallEvent(
                JSONObject().put("type", "installError")
                    .put("message", "Launch Weyland Tavern successfully at least once before using Repair.")
            )
            return
        }

        try {
            installDir.mkdirs()

            pushInstallEvent(JSONObject().put("type", "downloadStart"))
            val zipFile = File(cacheDir, "weytav-dl.zip")
            downloadFile(REPO_ZIP_URL, zipFile) { bytes ->
                pushInstallEvent(JSONObject().put("type", "downloadProgress").put("bytes", bytes))
            }
            pushInstallEvent(JSONObject().put("type", "downloadDone").put("bytes", zipFile.length()))

            // Download succeeded — safe to start deleting old files now.
            if (deepClean) {
                // Safety net before anything destructive happens: copy
                // data/ (chats, characters, personas, settings) somewhere
                // outside installDir. deepClean below is never supposed to
                // touch data/, but this is cheap insurance against a bug in
                // that exclusion logic — mirrors the desktop repair tool's
                // optional pre-repair backup. Overwrites any backup from a
                // previous repair rather than accumulating them forever.
                pushInstallEvent(JSONObject().put("type", "backupStart"))
                try {
                    val dataDir = File(installDir, "SillyTavern/data")
                    if (dataDir.exists()) {
                        dataBackupDir.deleteRecursively()
                        dataDir.copyRecursively(dataBackupDir, overwrite = true)
                        pushInstallEvent(JSONObject().put("type", "backupDone"))
                    } else {
                        pushInstallEvent(JSONObject().put("type", "backupSkipped"))
                    }
                } catch (e: Exception) {
                    // Non-fatal — proceed with repair anyway, but tell the user.
                    pushInstallEvent(
                        JSONObject().put("type", "backupFailed")
                            .put("message", e.message ?: e.javaClass.simpleName)
                    )
                }

                // Wipe everything under SillyTavern/ except data/, so the
                // fresh extract below lands on a truly clean slate instead
                // of overlaying on cruft. Mobile equivalent of the desktop
                // tool's `git reset --hard`.
                pushInstallEvent(JSONObject().put("type", "cleanStart"))
                val stDir = File(installDir, "SillyTavern")
                stDir.listFiles()?.forEach { child ->
                    if (child.name != "data") child.deleteRecursively()
                }
                pushInstallEvent(JSONObject().put("type", "cleanDone"))
            }

            pushInstallEvent(JSONObject().put("type", "extractStart"))
            extractZip(zipFile, installDir, stripFirstDir = true) { count, name ->
                pushInstallEvent(JSONObject().put("type", "extractProgress").put("files", count).put("name", name))
            }
            val extracted = countMarker
            pushInstallEvent(JSONObject().put("type", "extractDone").put("files", extracted))
            zipFile.delete()

            val nodeModulesDir = File(installDir, "SillyTavern/node_modules")
            if (!isUpdate || forceModules || !nodeModulesDir.exists()) {
                pushInstallEvent(JSONObject().put("type", "modulesStart"))
                extractAssetZip("node_modules.zip", File(installDir, "SillyTavern")) { count ->
                    pushInstallEvent(JSONObject().put("type", "modulesProgress").put("files", count))
                }
                pushInstallEvent(JSONObject().put("type", "modulesDone"))
            }

            val sha = fetchRemoteSha()
            if (sha != null) prefs.edit().putString("version", sha).apply()
            pushInstallEvent(JSONObject().put("type", "versionSaved").put("version", sha ?: "unknown"))

            // Sanity-check the result before declaring victory. Not as
            // rigorous as desktop's git tree comparison (no git here), but
            // catches the failure mode that matters: files that silently
            // didn't land.
            val serverJsOk = File(installDir, "SillyTavern/server.js").exists()
            val moduleCount = File(installDir, "SillyTavern/node_modules").listFiles()?.size ?: 0
            val nodeModulesOk = moduleCount > 20
            if (serverJsOk && nodeModulesOk) {
                pushInstallEvent(JSONObject().put("type", "verifyOk"))
            } else {
                pushInstallEvent(
                    JSONObject().put("type", "verifyFailed")
                        .put("serverJsOk", serverJsOk)
                        .put("moduleCount", moduleCount)
                )
            }

            if (redownloadChars) {
                performCharacterRedownload()
            }

            pushInstallEvent(JSONObject().put("type", "installDone"))
        } catch (e: Exception) {
            pushInstallEvent(
                JSONObject().put("type", "installError")
                    .put("message", "Setup failed: ${e.javaClass.simpleName} — ${e.message}. Check connection and free storage, then Settings → Reinstall.")
            )
        }
    }

    // ==================================================================
    // Character re-download (mirrors desktop repair's optional step)
    // ==================================================================

    private fun performCharacterRedownload() {
        pushInstallEvent(JSONObject().put("type", "charsStart"))

        // A poller thread from an earlier session may still be watching
        // port 8000 in the background. Suppress it for the duration so it
        // doesn't mistake our temporary server for a real launch — and
        // doesn't report a false "crashed" the moment we shut it down again.
        internalServerBusy = true
        try {
            val bootstrap = File(filesDir, "bootstrap.js")
            assets.open("bootstrap.js").use { input -> FileOutputStream(bootstrap).use { input.copyTo(it) } }
            val cacert = File(filesDir, "cacert.pem")
            try {
                assets.open("cacert.pem").use { input -> FileOutputStream(cacert).use { input.copyTo(it) } }
            } catch (e: Exception) { /* TLS just won't verify if this is missing */ }

            NodeService.start(
                this,
                installDir = installDir.absolutePath,
                logFile = logFile.absolutePath,
                bootstrapPath = bootstrap.absolutePath,
            )

            var up = false
            for (i in 0 until 150) {
                if (isServerRunning()) { up = true; break }
                Thread.sleep(2000)
            }

            if (!up) {
                pushInstallEvent(JSONObject().put("type", "charsTimeout"))
            } else {
                redownloadCharactersViaApi()
            }

            NodeService.stop(this)
            // Give the process a moment to actually exit before releasing
            // the guard, so a lingering poller tick doesn't slip through.
            Thread.sleep(1500)
        } finally {
            internalServerBusy = false
        }
    }

    private fun redownloadCharactersViaApi() {
        try {
            val tokenConn = URL("http://127.0.0.1:8000/csrf-token").openConnection() as HttpURLConnection
            tokenConn.connectTimeout = 10000
            tokenConn.readTimeout = 30000
            val cookie = tokenConn.headerFields["Set-Cookie"]?.joinToString("; ") { it.substringBefore(";") } ?: ""
            val token = JSONObject(tokenConn.inputStream.bufferedReader().readText()).getString("token")
            tokenConn.disconnect()

            val manifestConn = URL("http://127.0.0.1:8000/api/weyland/fetch-manifests").openConnection() as HttpURLConnection
            manifestConn.setRequestProperty("X-Csrf-Token", token)
            manifestConn.setRequestProperty("X-User-Handle", "default-user")
            manifestConn.setRequestProperty("X-Rebuild-Manifest", "1")
            if (cookie.isNotEmpty()) manifestConn.setRequestProperty("Cookie", cookie)
            manifestConn.connectTimeout = 10000
            manifestConn.readTimeout = 300000
            val manifests = JSONObject(manifestConn.inputStream.bufferedReader().readText())
            manifestConn.disconnect()

            val characters = manifests.optJSONObject("localManifest")?.optJSONArray("characters")
            val names = mutableListOf<String>()
            if (characters != null) {
                for (i in 0 until characters.length()) {
                    val ch = characters.getJSONObject(i)
                    if (!ch.isNull("version") && ch.has("version")) names.add(ch.getString("name"))
                }
            }

            if (names.isEmpty()) {
                pushInstallEvent(JSONObject().put("type", "charsNone"))
                return
            }
            pushInstallEvent(JSONObject().put("type", "charsFound").put("count", names.size))

            val dlConn = URL("http://127.0.0.1:8000/api/weyland/download").openConnection() as HttpURLConnection
            dlConn.requestMethod = "POST"
            dlConn.doOutput = true
            dlConn.setRequestProperty("X-Csrf-Token", token)
            dlConn.setRequestProperty("X-User-Handle", "default-user")
            dlConn.setRequestProperty("X-Redownload", "true")
            dlConn.setRequestProperty("Content-Type", "application/json")
            if (cookie.isNotEmpty()) dlConn.setRequestProperty("Cookie", cookie)
            dlConn.connectTimeout = 10000
            dlConn.readTimeout = 7200000
            val body = JSONObject().put("characters", org.json.JSONArray(names)).toString()
            dlConn.outputStream.use { it.write(body.toByteArray()) }
            val result = JSONObject(dlConn.inputStream.bufferedReader().readText())
            dlConn.disconnect()

            if (result.optBoolean("success", false)) {
                pushInstallEvent(JSONObject().put("type", "charsDone").put("count", names.size))
            } else {
                pushInstallEvent(JSONObject().put("type", "charsFailed").put("message", "server reported failure"))
            }
        } catch (e: Exception) {
            pushInstallEvent(
                JSONObject().put("type", "charsFailed")
                    .put("message", e.message ?: e.javaClass.simpleName)
            )
        }
    }

    private fun downloadFile(url: String, dest: File, onProgress: (Long) -> Unit) {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.setRequestProperty("User-Agent", "WeylandTavernMobile")
        conn.connectTimeout = 20000
        conn.readTimeout = 60000
        conn.inputStream.use { input ->
            FileOutputStream(dest).use { output ->
                val buf = ByteArray(65536)
                var total = 0L
                var lastReport = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    output.write(buf, 0, n)
                    total += n
                    if (total - lastReport > 1_000_000) {
                        lastReport = total
                        onProgress(total)
                    }
                }
            }
        }
        conn.disconnect()
    }

    private var countMarker = 0

    private fun extractZip(zip: File, target: File, stripFirstDir: Boolean, onProgress: (Int, String) -> Unit) {
        countMarker = 0
        ZipInputStream(BufferedInputStream(zip.inputStream())).use { zis ->
            var entry = zis.nextEntry
            val targetCanonical = target.canonicalPath
            while (entry != null) {
                var name = entry.name
                if (stripFirstDir) {
                    val slash = name.indexOf('/')
                    name = if (slash >= 0) name.substring(slash + 1) else ""
                }
                if (name.isNotEmpty()) {
                    val outFile = File(target, name)
                    // + separator so "…/weytav-evil" can't pass as "…/weytav"
                    if (!outFile.canonicalPath.startsWith(targetCanonical + File.separator)) {
                        entry = zis.nextEntry; continue
                    }
                    if (entry.isDirectory) {
                        outFile.mkdirs()
                    } else {
                        outFile.parentFile?.mkdirs()
                        FileOutputStream(outFile).use { zis.copyTo(it) }
                        countMarker++
                        if (countMarker % 50 == 0) onProgress(countMarker, outFile.name)
                    }
                }
                entry = zis.nextEntry
            }
        }
    }

    private fun extractAssetZip(assetName: String, target: File, onProgress: (Int) -> Unit) {
        var count = 0
        ZipInputStream(BufferedInputStream(assets.open(assetName))).use { zis ->
            var entry = zis.nextEntry
            val targetCanonical = target.canonicalPath
            while (entry != null) {
                val outFile = File(target, entry.name)
                if (outFile.canonicalPath.startsWith(targetCanonical + File.separator)) {
                    if (entry.isDirectory) outFile.mkdirs()
                    else {
                        outFile.parentFile?.mkdirs()
                        FileOutputStream(outFile).use { zis.copyTo(it) }
                        count++
                        if (count % 100 == 0) onProgress(count)
                    }
                }
                entry = zis.nextEntry
            }
        }
    }

    private fun fetchRemoteSha(): String? {
        return try {
            val conn = URL(COMMIT_API_URL).openConnection() as HttpURLConnection
            conn.setRequestProperty("User-Agent", "WeylandTavernMobile")
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            conn.connectTimeout = 10000
            val body = conn.inputStream.bufferedReader().readText()
            conn.disconnect()
            JSONObject(body).getString("sha")
        } catch (e: Exception) { null }
    }

    private fun checkForUpdates() {
        val local = prefs.getString("version", null)
        val remote = fetchRemoteSha() ?: return
        if (local == null) {
            // The SHA fetch failed during install (GitHub rate limit, flaky
            // connection). Adopt the current remote as our version so update
            // detection works from here on. Worst case we miss one update
            // cycle; it self-corrects on the next release.
            prefs.edit().putString("version", remote).apply()
            return
        }
        if (remote != local) {
            pushServerEventObj(JSONObject().put("type", "updateAvailable"))
        }
    }

    // ==================================================================
    // Server lifecycle
    // ==================================================================

    private fun doLaunchServer() {
        if (isServerRunning()) {
            pushServerEvent("status", "Server is already running!")
            return
        }
        if (!File(installDir, "SillyTavern/server.js").exists()) {
            pushServerEvent("status", "Weyland Tavern isn't installed yet!")
            return
        }

        // Notification permission (Android 13+)
        if (Build.VERSION.SDK_INT >= 33 &&
            ActivityCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 42)
        }

        // Mark as launched so activity recreation doesn't lose this state
        prefs.edit().putString("launched", "yes").apply()

        pushServerEventObj(JSONObject().put("type", "starting"))

        // Deploy bootstrap.js fresh each launch
        val bootstrap = File(filesDir, "bootstrap.js")
        assets.open("bootstrap.js").use { input ->
            FileOutputStream(bootstrap).use { input.copyTo(it) }
        }

        // Deploy the CA bundle Node uses for HTTPS (see SSL_CERT_FILE in NodeService)
        try {
            val cacert = File(filesDir, "cacert.pem")
            assets.open("cacert.pem").use { input ->
                FileOutputStream(cacert).use { input.copyTo(it) }
            }
        } catch (e: Exception) { /* TLS self-test in the log will reveal problems */ }

        // Start the server in the separate :node process
        NodeService.start(
            this,
            installDir = installDir.absolutePath,
            logFile = logFile.absolutePath,
            bootstrapPath = bootstrap.absolutePath
        )

        startStatusPoller()
    }

    private fun wasServerLaunched(): Boolean {
        return prefs.getString("launched", null) == "yes"
    }

    private fun startStatusPoller() {
        if (pollerRunning) return
        pollerRunning = true
        Thread {
            var wasUp = false
            var downAfterUp = 0
            while (true) {
                if (internalServerBusy) {
                    // A Repair-triggered temporary server owns port 8000
                    // right now — don't let its start/stop look like a
                    // real launch or a crash.
                    Thread.sleep(2500)
                    continue
                }
                val up = isServerRunning()

                if (up && !wasUp) {
                    wasUp = true
                    downAfterUp = 0
                    // The internalServerBusy guard above means this can only
                    // fire for a real, user-initiated launch — Repair's own
                    // temporary server is invisible to this poller. Safe to
                    // treat "we saw it come up" as "the user has successfully
                    // launched at least once," which gates Repair below.
                    prefs.edit().putBoolean("everLaunched", true).apply()
                    pushServerEventObj(JSONObject().put("type", "up"))
                }
                if (!up && wasUp) {
                    downAfterUp++
                    if (downAfterUp >= 4) {
                        // Server was up then went down consistently = crashed
                        wasUp = false
                        prefs.edit().remove("launched").apply()
                        pushServerEventObj(JSONObject().put("type", "crashed"))
                        break
                    }
                }
                // Still booting (never came up): keep polling
                Thread.sleep(2500)
            }
            pollerRunning = false
        }.start()
    }

    private fun isServerRunning(): Boolean {
        return try { Socket("127.0.0.1", 8000).use { true } }
        catch (e: Exception) { false }
    }

    // ==================================================================
    // Helpers
    // ==================================================================

    private fun trimLogFile() {
        try {
            if (logFile.exists() && logFile.length() > 500_000) {
                val tail = logFile.readLines().takeLast(1000)
                logFile.writeText(tail.joinToString("\n"))
            }
        } catch (e: Exception) {}
    }

    private fun pushInstallEvent(obj: JSONObject) {
        val safe = JSONObject.quote(obj.toString())
        runOnUiThread { webView.evaluateJavascript("installEvent($safe);", null) }
    }

    private fun pushServerEventObj(obj: JSONObject) {
        val safe = JSONObject.quote(obj.toString())
        runOnUiThread { webView.evaluateJavascript("serverEvent($safe);", null) }
    }

    private fun pushServerEvent(type: String, message: String) {
        pushServerEventObj(JSONObject().put("type", type).put("message", message))
    }
}
