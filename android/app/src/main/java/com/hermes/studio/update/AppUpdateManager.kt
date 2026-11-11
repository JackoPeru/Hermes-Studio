package com.hermes.studio.update

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.text.format.Formatter
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.hermes.studio.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

class AppUpdateManager(private val appContext: Context) {
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(lightInitialState())
    val state: StateFlow<AppUpdateState> = _state.asStateFlow()
    private val warmupMutex = Mutex()
    private val downloadMutex = Mutex()
    @Volatile private var warmedUp = false
    private val managerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private suspend fun ensureWarmedUp() {
        if (warmedUp) return
        warmupMutex.withLock {
            if (warmedUp) return
            val warmed = withContext(Dispatchers.IO) { createInitialState() }
            _state.update {
                it.copy(
                    currentVersionCode = warmed.currentVersionCode,
                    currentVersionName = warmed.currentVersionName,
                    latestRelease = warmed.latestRelease,
                    availableRelease = warmed.availableRelease,
                    downloadedApkPath = warmed.downloadedApkPath,
                    rollbackApkPath = warmed.rollbackApkPath,
                    rollbackVersionCode = warmed.rollbackVersionCode,
                    statusMessage = warmed.statusMessage
                )
            }
            warmedUp = true
        }
    }

    suspend fun checkForUpdates(force: Boolean = false) {
        ensureWarmedUp()
        if (!force && !state.value.autoCheckEnabled) return
        _state.update { it.copy(isChecking = true, lastError = null, signatureBlocked = false) }
        runCatching { fetchLatestRelease() }.onSuccess { release ->
            val currentVersionCode = currentVersionCode()
            val releaseApk = downloadedApkFile(release).takeIf { it.exists() }
            val retained = retainedVersionCodes(currentVersionCode, release.versionCode.takeIf { it > currentVersionCode && releaseApk != null })
            val signatureError = if (release.versionCode > currentVersionCode) UpdateReleaseSigner.verifyRelease(release) else null
            val previouslyDownloadedCode = state.value.downloadedApkPath?.let(::File)?.takeIf { it.exists() }?.let { file ->
                storedUpdateApks(false).firstOrNull { it.file.absolutePath == file.absolutePath }?.versionCode
            }
            cleanupDownloadedUpdates(retained + listOfNotNull(previouslyDownloadedCode))
            val downloadedApk = downloadedApkFile(release).takeIf { it.exists() }
            val rollbackApk = rollbackApkFor(currentVersionCode)
            if (signatureError == null && release.versionCode > currentVersionCode) {
                prefs.edit().putString(KEY_LAST_RELEASE_JSON, releaseToJson(release).toString()).apply()
            } else if (signatureError == null) {
                prefs.edit().remove(KEY_LAST_RELEASE_JSON).apply()
            }
            val preservedAvailable = if (signatureError != null) {
                state.value.availableRelease?.takeIf { it.versionCode > currentVersionCode }?.takeIf { downloadedApkFile(it).exists() }
            } else null
            val effectiveAvailable = release.takeIf { it.versionCode > currentVersionCode && signatureError == null } ?: preservedAvailable
            _state.update {
                it.copy(
                    currentVersionCode = currentVersionCode,
                    currentVersionName = currentVersionName(),
                    isChecking = false,
                    latestRelease = release,
                    availableRelease = effectiveAvailable,
                    downloadedApkPath = (downloadedApk?.absolutePath ?: effectiveAvailable?.let { downloadedApkFile(it).takeIf(File::exists)?.absolutePath }),
                    rollbackApkPath = rollbackApk?.file?.absolutePath,
                    rollbackVersionCode = rollbackApk?.versionCode,
                    signatureBlocked = signatureError != null,
                    statusMessage = when {
                        signatureError != null -> signatureError
                        release.versionCode > currentVersionCode -> "Disponibile ${release.versionName} (${release.versionCode})."
                        else -> "Sei gia all'ultima versione disponibile."
                    },
                    lastError = null
                )
            }
        }.onFailure { error ->
            val msg = error.message ?: "Errore sconosciuto."
            val informational = msg.startsWith("Feed aggiornamenti") || msg.startsWith("Nessuna release")
            _state.update { it.copy(isChecking = false, statusMessage = if (informational) msg else "Controllo aggiornamenti fallito.", lastError = if (informational) null else msg) }
        }
    }

    suspend fun downloadAvailableUpdate() {
        ensureWarmedUp()
        downloadMutex.withLock { downloadAvailableUpdateLocked() }
    }

    private suspend fun downloadAvailableUpdateLocked() {
        val release = state.value.availableRelease ?: return
        state.value.downloadedApkPath?.let(::File)?.takeIf { it.exists() }?.let { return }
        _state.update { it.copy(isDownloading = true, downloadProgress = 0f, downloadLabel = "", lastError = null, statusMessage = "Download aggiornamento in corso...") }
        runCatching { downloadReleaseApk(release) }.onSuccess { file ->
            val currentVersionCode = currentVersionCode()
            cleanupDownloadedUpdates(setOf(release.versionCode, currentVersionCode))
            val rollbackApk = storedUpdateApks(false).firstOrNull { it.versionCode == currentVersionCode }
            _state.update {
                it.copy(
                    isDownloading = false, downloadProgress = 1f,
                    downloadLabel = Formatter.formatFileSize(appContext, file.length()),
                    downloadedApkPath = file.absolutePath,
                    rollbackApkPath = rollbackApk?.file?.absolutePath,
                    rollbackVersionCode = rollbackApk?.versionCode,
                    statusMessage = "Aggiornamento scaricato. Puoi installarlo senza perdere dati e preferenze.",
                    lastError = null
                )
            }
        }.onFailure { error ->
            _state.update { it.copy(isDownloading = false, downloadProgress = 0f, statusMessage = "Download aggiornamento fallito.", lastError = error.message ?: "Errore sconosciuto.") }
        }
    }

    fun setAutoCheckEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_CHECK_ENABLED, enabled).apply()
        _state.update { it.copy(autoCheckEnabled = enabled) }
        ensureBackgroundChecksConfigured()
    }

    fun ensureBackgroundChecksConfigured() {
        if (state.value.autoCheckEnabled) {
            val request = PeriodicWorkRequestBuilder<UpdateCheckWorker>(UPDATE_CHECK_INTERVAL_HOURS, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(androidx.work.BackoffPolicy.EXPONENTIAL, 30L, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(appContext).enqueueUniquePeriodicWork(UPDATE_WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
        } else {
            WorkManager.getInstance(appContext).cancelUniqueWork(UPDATE_WORK_NAME)
        }
    }

    fun installDownloadedUpdate() {
        managerScope.launch {
            ensureWarmedUp()
            val path = state.value.downloadedApkPath ?: return@launch
            val file = File(path)
            if (!file.exists()) {
                _state.update { it.copy(downloadedApkPath = null, statusMessage = "Il file dell'aggiornamento non e piu disponibile.") }
                return@launch
            }
            if (!ensureUnknownSourcesAllowed("Consenti a Hermes Studio di installare aggiornamenti, poi riprova.")) return@launch
            val release = state.value.availableRelease
            if (release == null) {
                _state.update { it.copy(statusMessage = "Installazione bloccata: tocca Controlla aggiornamenti prima di installare.") }
                return@launch
            }
            downloadMutex.withLock {
                if (!verifyDownloadedFile(release, file)) return@withLock
                verifyApkSignatureMatchesInstalled(file)?.let { err ->
                    _state.update { it.copy(statusMessage = err) }
                    return@withLock
                }
                runCatching { appContext.startActivity(createInstallIntent(file)) }
                    .onFailure { e -> _state.update { it.copy(statusMessage = "Installazione non avviata: ${e.message ?: "errore"}.") } }
            }
        }
    }

    fun installRollbackUpdate() {
        managerScope.launch {
            ensureWarmedUp()
            val path = state.value.rollbackApkPath ?: return@launch
            val file = File(path)
            if (!file.exists()) {
                _state.update { it.copy(statusMessage = "Il file rollback non e piu disponibile.") }
                return@launch
            }
            if (!ensureUnknownSourcesAllowed("Consenti a Hermes Studio di installare APK, poi riprova il rollback.")) return@launch
            val expected = state.value.rollbackVersionCode ?: rollbackApkFor(currentVersionCode())?.versionCode
            if (expected == null || !verifyPendingApkOffline(file, expected)) {
                _state.update { it.copy(statusMessage = "Rollback non verificato (package/versione/firma): installazione bloccata.") }
                return@launch
            }
            if (expected < currentVersionCode()) {
                _state.update { it.copy(statusMessage = "Rollback $expected su ${currentVersionCode()} installata: Android potrebbe bloccare il downgrade, nel caso serve reinstallazione manuale.") }
            }
            runCatching { appContext.startActivity(createInstallIntent(file)) }
                .onFailure { e -> _state.update { it.copy(statusMessage = "Rollback non avviato: ${e.message ?: "errore"}.") } }
        }
    }

    suspend fun runBackgroundSync() {
        if (!state.value.autoCheckEnabled) return
        checkForUpdates(force = true)
        val release = state.value.availableRelease ?: return
        val dl = state.value.downloadedApkPath
        if (dl == null || !File(dl).exists()) downloadAvailableUpdate()
        notifyIfNeeded(release)
    }

    private suspend fun fetchLatestRelease(): AppUpdateRelease = withContext(Dispatchers.IO) {
        val conn = URL("$PUBLIC_UPDATE_FEED_URL?ts=${System.currentTimeMillis()}").openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.setRequestProperty("Accept", "application/json")
        conn.setRequestProperty("User-Agent", "HermesStudio/${currentVersionName()}")
        conn.connectTimeout = 15000; conn.readTimeout = 20000
        try {
            when (conn.responseCode) {
                HttpURLConnection.HTTP_OK -> parseRelease(JSONObject(conn.inputStream.bufferedReader().use { it.readText() }))
                HttpURLConnection.HTTP_NOT_FOUND -> throw IllegalStateException("Feed aggiornamenti pubblico non trovato. Pubblica HermesStudio-updates prima di usare l'auto-update.")
                else -> throw IllegalStateException("Controllo aggiornamenti non riuscito (HTTP ${conn.responseCode}).")
            }
        } finally { conn.disconnect() }
    }

    private fun parseRelease(o: JSONObject): AppUpdateRelease {
        val notes = mutableListOf<String>()
        val arr: JSONArray? = o.optJSONArray("notes")
        if (arr != null) for (i in 0 until arr.length()) notes.add(arr.optString(i))
        return AppUpdateRelease(
            versionCode = o.getLong("versionCode"),
            versionName = o.getString("versionName"),
            tagName = o.optString("tagName", ""),
            apkUrl = o.getString("apkUrl"),
            assetSize = o.optLong("assetSize", 0L),
            notes = notes,
            sha256 = o.optString("sha256", ""),
            signature = o.optString("signature", "")
        )
    }

    private fun releaseToJson(r: AppUpdateRelease): JSONObject = JSONObject()
        .put("versionCode", r.versionCode).put("versionName", r.versionName)
        .put("tagName", r.tagName).put("apkUrl", r.apkUrl)
        .put("assetSize", r.assetSize).put("sha256", r.sha256).put("signature", r.signature)

    private suspend fun downloadReleaseApk(release: AppUpdateRelease): File = withContext(Dispatchers.IO) {
        val target = downloadedApkFile(release)
        target.parentFile?.mkdirs()
        val partial = File(target.absolutePath + ".part")
        if (partial.exists()) partial.delete()
        val conn = URL(release.apkUrl).openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.connectTimeout = 15000; conn.readTimeout = 60000
        try {
            val code = conn.responseCode
            require(code in 200..299) { "Download aggiornamento non riuscito (HTTP $code)." }
            val totalBytes = conn.contentLengthLong.takeIf { it > 0L } ?: release.assetSize.takeIf { it > 0L } ?: 0L
            val expected = release.assetSize.takeIf { it > 0L } ?: totalBytes
            var downloaded = 0L
            conn.inputStream.buffered().use { input ->
                partial.outputStream().buffered().use { output ->
                    val buf = ByteArray(DEFAULT_BUFFER_SIZE)
                    var n = input.read(buf)
                    while (n >= 0) {
                        output.write(buf, 0, n); downloaded += n
                        _state.update { it.copy(isDownloading = true, downloadProgress = if (totalBytes > 0L) (downloaded.toFloat() / totalBytes).coerceIn(0f, 1f) else 0f, downloadLabel = "${Formatter.formatFileSize(appContext, downloaded)} / ${Formatter.formatFileSize(appContext, totalBytes)}", downloadedApkPath = null) }
                        n = input.read(buf)
                    }
                }
            }
            if (target.exists()) target.delete()
            if (!partial.renameTo(target)) {
                runCatching { partial.copyTo(target, overwrite = true); partial.delete() }.getOrElse {
                    runCatching { partial.delete() }
                    throw IllegalStateException("Impossibile finalizzare il file APK scaricato.")
                }
            }
            if (expected > 0L && target.length() != expected) {
                runCatching { target.delete() }
                throw IllegalStateException("Download incompleto: ricevuti ${target.length()} byte, attesi $expected.")
            }
            if (release.sha256.isNotBlank() && !sha256Hex(target).equals(release.sha256, ignoreCase = true)) {
                runCatching { target.delete() }
                throw IllegalStateException("SHA-256 APK non corrispondente: file scartato.")
            }
            target
        } finally { conn.disconnect() }
    }

    private fun sha256Hex(file: File): String {
        val d = java.security.MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buf = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) { val r = input.read(buf); if (r <= 0) break; d.update(buf, 0, r) }
        }
        return d.digest().joinToString("") { "%02x".format(it) }
    }

    private fun notifyIfNeeded(release: AppUpdateRelease) {
        if (release.versionCode <= currentVersionCode()) return
        if (release.versionCode == prefs.getLong(KEY_LAST_NOTIFIED_VERSION, -1L)) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(appContext, android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val manager = appContext.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(NotificationChannel(UPDATE_CHANNEL_ID, "Aggiornamenti Hermes Studio", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Notifiche quando una nuova versione e pronta da installare."
        })
        val content = PendingIntent.getActivity(appContext, 0, createOpenAppIntent(), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notif = NotificationCompat.Builder(appContext, UPDATE_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done).setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentTitle("Nuova versione di Hermes Studio disponibile")
            .setContentText(release.notes.firstOrNull() ?: "${release.versionName} disponibile.")
            .setContentIntent(content).build()
        runCatching { NotificationManagerCompat.from(appContext).notify(UPDATE_NOTIFICATION_ID, notif) }
        prefs.edit().putLong(KEY_LAST_NOTIFIED_VERSION, release.versionCode).apply()
    }

    private fun ensureUnknownSourcesAllowed(rationale: String): Boolean {
        if (appContext.packageManager.canRequestPackageInstalls()) return true
        runCatching {
            appContext.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                data = "package:${appContext.packageName}".toUri(); addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        }
        _state.update { it.copy(statusMessage = rationale) }
        return false
    }

    private fun verifyDownloadedFile(release: AppUpdateRelease, file: File): Boolean {
        if (!file.isFile || file.length() <= 0L) {
            _state.update { it.copy(statusMessage = "Il file dell'aggiornamento non e piu disponibile.") }
            return false
        }
        if (release.assetSize > 0L && file.length() != release.assetSize) {
            runCatching { file.delete() }
            _state.update { it.copy(downloadedApkPath = null, statusMessage = "File aggiornamento alterato (dimensione): riscarica.") }
            return false
        }
        if (release.sha256.isNotBlank() && !sha256Hex(file).equals(release.sha256, ignoreCase = true)) {
            runCatching { file.delete() }
            _state.update { it.copy(downloadedApkPath = null, statusMessage = "SHA-256 APK non corrispondente: file scartato, riscarica.") }
            return false
        }
        return true
    }

    private fun verifyApkSignatureMatchesInstalled(file: File): String? {
        return try {
            val pm = appContext.packageManager
            val archive = pm.getPackageArchiveInfo(file.absolutePath, PackageManager.GET_SIGNING_CERTIFICATES)
                ?: return "Impossibile leggere la firma dell'APK scaricato."
            val archiveSigners = archive.signingInfo?.apkContentsSigners?.map { it.toByteArray().sha256Hex() }.orEmpty()
            if (archiveSigners.isEmpty()) return "APK scaricato senza firma."
            val installed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageInfo(appContext.packageName, PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()))
            } else {
                @Suppress("DEPRECATION") pm.getPackageInfo(appContext.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
            }
            val installedSigners = installed.signingInfo?.apkContentsSigners?.map { it.toByteArray().sha256Hex() }.orEmpty()
            if (archiveSigners.any { it in installedSigners }) null else "APK con firma diversa dallo sviluppatore installato: installazione bloccata."
        } catch (t: Throwable) { "Verifica firma APK fallita: ${t.message ?: "errore"}." }
    }

    private fun ByteArray.sha256Hex(): String {
        val d = java.security.MessageDigest.getInstance("SHA-256")
        return d.digest(this).joinToString("") { "%02x".format(it) }
    }

    private fun createInstallIntent(file: File): Intent {
        val uri = runCatching { FileProvider.getUriForFile(appContext, "${appContext.packageName}.fileprovider", file) }
            .getOrElse { throw IllegalStateException("Provider file mancante.") }
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    private fun createOpenAppIntent(): Intent =
        appContext.packageManager.getLaunchIntentForPackage(appContext.packageName)?.apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        } ?: Intent(appContext, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }

    private fun packageInfo() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        appContext.packageManager.getPackageInfo(appContext.packageName, PackageManager.PackageInfoFlags.of(0))
    } else {
        @Suppress("DEPRECATION") appContext.packageManager.getPackageInfo(appContext.packageName, 0)
    }

    private fun currentVersionCode(): Long = packageInfo().let { if (Build.VERSION.SDK_INT >= 28) it.longVersionCode else @Suppress("DEPRECATION") it.versionCode.toLong() }
    private fun currentVersionName(): String = packageInfo().versionName.orEmpty()
    private fun downloadedApkFile(release: AppUpdateRelease): File =
        File(appContext.getExternalFilesDir("updates") ?: File(appContext.filesDir, "updates").apply { mkdirs() }, "hermes-studio-${release.versionCode}.apk")

    private fun lightInitialState(): AppUpdateState = AppUpdateState(
        currentVersionCode = runCatching { currentVersionCode() }.getOrDefault(0L),
        currentVersionName = runCatching { currentVersionName() }.getOrDefault(""),
        autoCheckEnabled = prefs.getBoolean(KEY_AUTO_CHECK_ENABLED, true),
        statusMessage = "Controlla la presenza di nuove release da GitHub."
    )

    private fun createInitialState(): AppUpdateState {
        val current = currentVersionCode()
        val pending = storedUpdateApks(false).filter { it.versionCode > current }.maxByOrNull { it.versionCode }
        cleanupDownloadedUpdates(retainedVersionCodes(current, pending?.versionCode))
        val rollback = rollbackApkFor(current)
        val pendingFile = pending?.file?.takeIf { it.exists() }
        val cached = pending?.let { readCachedRelease()?.takeIf { c -> c.versionCode == pending.versionCode } }
        val verified = pendingFile != null && pending != null && verifyPendingApkOffline(pendingFile, pending.versionCode) &&
            (cached == null || verifyPendingAgainstCached(cached, pendingFile))
        return AppUpdateState(
            currentVersionCode = current, currentVersionName = currentVersionName(),
            autoCheckEnabled = prefs.getBoolean(KEY_AUTO_CHECK_ENABLED, true),
            latestRelease = cached, availableRelease = cached?.takeIf { verified },
            downloadedApkPath = pendingFile?.takeIf { verified }?.absolutePath,
            rollbackApkPath = rollback?.file?.absolutePath, rollbackVersionCode = rollback?.versionCode,
            statusMessage = when {
                verified && cached != null -> "Aggiornamento ${cached.versionName} pronto da installare (verificato)."
                verified -> "Trovato aggiornamento scaricato (${pending?.versionCode}): tocca Controlla ora per confermare la firma."
                else -> "Controlla la presenza di nuove release da GitHub."
            }
        )
    }

    private fun readCachedRelease(): AppUpdateRelease? {
        val raw = prefs.getString(KEY_LAST_RELEASE_JSON, null)?.takeIf { it.isNotBlank() } ?: return null
        return runCatching { parseRelease(JSONObject(raw)) }.getOrNull()
    }

    private fun verifyPendingApkOffline(file: File, expectedVersionCode: Long): Boolean {
        if (!file.isFile || file.length() <= 0L) return false
        if (verifyApkSignatureMatchesInstalled(file) != null) return false
        val archive = runCatching { appContext.packageManager.getPackageArchiveInfo(file.absolutePath, 0) }.getOrNull() ?: return false
        if (archive.packageName != appContext.packageName) return false
        val v = if (Build.VERSION.SDK_INT >= 28) archive.longVersionCode else @Suppress("DEPRECATION") archive.versionCode.toLong()
        return v == expectedVersionCode
    }

    private fun verifyPendingAgainstCached(cached: AppUpdateRelease, file: File): Boolean {
        if (UpdateReleaseSigner.verifyRelease(cached) != null) return false
        if (cached.assetSize > 0L && file.length() != cached.assetSize) return false
        if (cached.sha256.isNotBlank() && !sha256Hex(file).equals(cached.sha256, ignoreCase = true)) return false
        return true
    }

    private fun cleanupDownloadedUpdates(retained: Set<Long>) {
        storedUpdateApks(true).filterNot { it.versionCode in retained }.forEach { runCatching { it.file.delete() } }
    }

    private fun retainedVersionCodes(current: Long, candidate: Long?): Set<Long> =
        if (candidate != null) setOf(candidate, current)
        else listOfNotNull(current, rollbackApkFor(current)?.versionCode).toSet()

    private fun rollbackApkFor(current: Long) = storedUpdateApks(false).filter { it.versionCode < current }.maxByOrNull { it.versionCode }

    private fun storedUpdateApks(includePartials: Boolean): List<StoredApk> =
        updateDirs().flatMap { d -> d.listFiles()?.asList().orEmpty() }.mapNotNull { file ->
            if (!file.isFile) return@mapNotNull null
            val m = UPDATE_APK_PATTERN.matchEntire(file.name) ?: return@mapNotNull null
            val isPartial = m.groupValues[2].isNotBlank()
            if (isPartial && !includePartials) return@mapNotNull null
            StoredApk(file, m.groupValues[1].toLongOrNull() ?: return@mapNotNull null, isPartial)
        }.sortedWith(compareByDescending<StoredApk> { it.versionCode }.thenBy { it.isPartial })

    private fun updateDirs(): List<File> = listOfNotNull(
        appContext.getExternalFilesDir("updates"), File(appContext.filesDir, "updates")
    ).distinctBy { it.absolutePath }

    private data class StoredApk(val file: File, val versionCode: Long, val isPartial: Boolean)

    class UpdateCheckWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
        override suspend fun doWork(): Result {
            if (runAttemptCount >= 3) return Result.failure()
            return runCatching {
                (applicationContext as? UpdateManagerProvider)?.appUpdateManager()?.runBackgroundSync()
                Result.success()
            }.getOrElse { Result.retry() }
        }
    }

    interface UpdateManagerProvider { fun appUpdateManager(): AppUpdateManager }

    companion object {
        private const val PREFS_NAME = "hermes_update_store"
        private const val KEY_AUTO_CHECK_ENABLED = "auto_check_enabled"
        private const val KEY_LAST_NOTIFIED_VERSION = "last_notified_version"
        private const val KEY_LAST_RELEASE_JSON = "last_release_json"
        const val PUBLIC_UPDATE_FEED_URL = "https://raw.githubusercontent.com/JackoPeru/HermesStudio-updates/main/latest.json"
        private const val UPDATE_WORK_NAME = "hermes_studio_auto_update"
        private const val UPDATE_CHANNEL_ID = "hermes_studio_updates"
        private const val UPDATE_NOTIFICATION_ID = 4201
        private const val UPDATE_CHECK_INTERVAL_HOURS = 6L
        private val UPDATE_APK_PATTERN = Regex("""hermes-studio-(\d+)\.apk(\.part)?""")
    }
}
