package com.hermes.studio.update

data class AppUpdateRelease(
    val versionCode: Long,
    val versionName: String,
    val tagName: String = "",
    val apkUrl: String,
    val assetSize: Long = 0L,
    val notes: List<String> = emptyList(),
    val sha256: String = "",
    val signature: String = ""
)

data class AppUpdateState(
    val currentVersionCode: Long = 0L,
    val currentVersionName: String = "",
    val autoCheckEnabled: Boolean = true,
    val isChecking: Boolean = false,
    val isDownloading: Boolean = false,
    val downloadProgress: Float = 0f,
    val downloadLabel: String = "",
    val latestRelease: AppUpdateRelease? = null,
    val availableRelease: AppUpdateRelease? = null,
    val downloadedApkPath: String? = null,
    val rollbackApkPath: String? = null,
    val rollbackVersionCode: Long? = null,
    val signatureBlocked: Boolean = false,
    val statusMessage: String = "Controllo aggiornamenti non ancora eseguito.",
    val lastError: String? = null
)
