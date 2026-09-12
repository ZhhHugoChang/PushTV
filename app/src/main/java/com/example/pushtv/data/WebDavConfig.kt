package com.example.pushtv.data

enum class WebDavStatus {
    UNCONFIGURED, // 未配置
    ONLINE,       // 已配置且在线
    OFFLINE       // 已配置但离线/错误
}

data class WebDavConfig(
    val serverUrl: String = "",
    val username: String = "",
    val password: String = "",
    val remoteDir: String = "/PushTV/Backups/"
) {
    val isConfigured: Boolean
        get() = serverUrl.isNotBlank()
}

data class WebDavRemoteFile(
    val name: String,
    val size: Long = 0L,
    val lastModified: Long = 0L
)

enum class AppBackupStatus {
    NOT_BACKED_UP,      // 未备份
    BACKED_UP,          // 已备份（版本一致）
    VERSION_MISMATCH    // 云端存在但版本不一致
}

data class BackupAppItem(
    val name: String,
    val packageName: String,
    val icon: Any?,
    val versionName: String,
    val apkFileLength: Long,
    val sourceDir: String,
    val isSelected: Boolean = false,
    val backupStatus: AppBackupStatus = AppBackupStatus.NOT_BACKED_UP,
    val remoteFileName: String? = null,
    val remoteVersion: String? = null,
    val localApkFile: java.io.File? = null
)

enum class CloudAppInstallStatus {
    NOT_INSTALLED,      // 本机未安装
    INSTALLED_SAME,     // 本机已安装（同版本）
    INSTALLED_DIFFERENT // 本机已安装其他版本
}

data class CloudApkItem(
    val fileName: String,
    val appName: String,
    val versionName: String?,
    val size: Long,
    val installStatus: CloudAppInstallStatus = CloudAppInstallStatus.NOT_INSTALLED,
    val installedVersion: String? = null,
    val localApkFile: java.io.File? = null,
    val installedPackageName: String? = null,
    val icon: Any? = null,
    val isSelected: Boolean = false
)
