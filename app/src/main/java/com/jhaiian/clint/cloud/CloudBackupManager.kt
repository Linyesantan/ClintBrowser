package com.jhaiian.clint.cloud

import android.content.Context
import com.jhaiian.clint.backup.BackupCategory
import com.jhaiian.clint.backup.BackupManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class CloudBackupOutcome {
    SUCCESS,
    CLEANUP_PARTIAL,
    TOO_LARGE,
    AUTH_FAILED,
    NETWORK_ERROR,
    SERVER_ERROR,
    NOT_CONFIGURED,
    BACKUP_FAILED
}

data class CloudBackupReport(
    val outcome: CloudBackupOutcome,
    val sizeBytes: Long = 0L,
    val fileName: String? = null,
    val httpCode: Int = 0,
    val detail: String? = null,
    val finishedAt: Long = System.currentTimeMillis()
)

data class CloudBackupStatus(
    val time: Long,
    val outcome: CloudBackupOutcome,
    val sizeBytes: Long,
    val fileName: String?,
    val httpCode: Int,
    val detail: String?
)

object CloudBackupManager {
    const val REMOTE_DIR = "Clint/backups/"
    const val FILE_EXTENSION = "clintbackup"
    val BACKUP_NAME_REGEX = Regex("^clint-backup-(\\d{8})-(\\d{6})\\.clintbackup$")

    private const val KEY_LAST_TIME = "cloud_backup_last_time"
    private const val KEY_LAST_OUTCOME = "cloud_backup_last_outcome"
    private const val KEY_LAST_SIZE = "cloud_backup_last_size"
    private const val KEY_LAST_FILE = "cloud_backup_last_file"
    private const val KEY_LAST_HTTP = "cloud_backup_last_http"
    private const val KEY_LAST_DETAIL = "cloud_backup_last_detail"

    private val runLock = Mutex()

    /** Creates a full backup (every available category, unencrypted, same format the restore screen reads) and uploads it. */
    suspend fun runBackup(context: Context): CloudBackupReport = runLock.withLock {
        withContext(Dispatchers.IO) { runBackupLocked(context.applicationContext) }
    }

    private suspend fun runBackupLocked(context: Context): CloudBackupReport {
        val config = WebDavSettings.load(context)
        if (!config.isComplete) return CloudBackupReport(CloudBackupOutcome.NOT_CONFIGURED)

        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val fileName = "clint-backup-$stamp.$FILE_EXTENSION"
        val workDir = File(context.cacheDir, "cloud_backup").apply { mkdirs() }
        workDir.listFiles()?.forEach { it.delete() }
        val file = File(workDir, fileName)
        try {
            try {
                FileOutputStream(file).use { out ->
                    BackupManager.createBackup(context, BackupCategory.available().toSet(), null, out)
                }
            } catch (e: Exception) {
                return CloudBackupReport(CloudBackupOutcome.BACKUP_FAILED, fileName = fileName, detail = e.message ?: e.javaClass.simpleName)
            }

            val size = file.length()
            val maxBytes = WebDavSettings.maxSizeMb(context).toLong() * 1024L * 1024L
            if (size > maxBytes) {
                return CloudBackupReport(CloudBackupOutcome.TOO_LARGE, sizeBytes = size, fileName = fileName)
            }

            val client = try {
                WebDavClient(config)
            } catch (e: WebDavException) {
                return CloudBackupReport(CloudBackupOutcome.NOT_CONFIGURED, sizeBytes = size, fileName = fileName)
            }

            try {
                client.ensureDirectory(REMOTE_DIR)
                client.upload(REMOTE_DIR + fileName, file)
            } catch (e: WebDavException) {
                return reportFor(e, size, fileName)
            }

            // Retention: keep the newest N backups, delete the rest.
            val keep = WebDavSettings.keepCount(context)
            var cleanupError: WebDavException? = null
            var failedDeletes = 0
            try {
                val backups = client.list(REMOTE_DIR)
                    .filter { !it.isDirectory && BACKUP_NAME_REGEX.matches(it.name) }
                    .sortedByDescending { it.name }
                for (old in backups.drop(keep)) {
                    try {
                        client.delete(old.href)
                    } catch (e: WebDavException) {
                        failedDeletes++
                        cleanupError = e
                    }
                }
            } catch (e: WebDavException) {
                cleanupError = e
                failedDeletes = -1
            }
            val err = cleanupError
            if (err != null) {
                return CloudBackupReport(
                    CloudBackupOutcome.CLEANUP_PARTIAL,
                    sizeBytes = size,
                    fileName = fileName,
                    httpCode = err.httpCode,
                    detail = if (failedDeletes > 0) "DELETE:$failedDeletes" else "LIST"
                )
            }
            return CloudBackupReport(CloudBackupOutcome.SUCCESS, sizeBytes = size, fileName = fileName)
        } finally {
            file.delete()
        }
    }

    fun reportFor(e: WebDavException, size: Long = 0L, fileName: String? = null): CloudBackupReport = when (e.kind) {
        WebDavException.Kind.AUTH -> CloudBackupReport(CloudBackupOutcome.AUTH_FAILED, size, fileName, e.httpCode)
        WebDavException.Kind.NETWORK -> CloudBackupReport(CloudBackupOutcome.NETWORK_ERROR, size, fileName, detail = e.detail)
        WebDavException.Kind.SERVER -> CloudBackupReport(CloudBackupOutcome.SERVER_ERROR, size, fileName, e.httpCode, e.detail)
        WebDavException.Kind.CONFIG -> CloudBackupReport(CloudBackupOutcome.NOT_CONFIGURED, size, fileName)
    }

    fun recordStatus(context: Context, report: CloudBackupReport) {
        WebDavSettings.prefs(context).edit()
            .putLong(KEY_LAST_TIME, report.finishedAt)
            .putString(KEY_LAST_OUTCOME, report.outcome.name)
            .putLong(KEY_LAST_SIZE, report.sizeBytes)
            .putString(KEY_LAST_FILE, report.fileName)
            .putInt(KEY_LAST_HTTP, report.httpCode)
            .putString(KEY_LAST_DETAIL, report.detail)
            .apply()
    }

    fun lastStatus(context: Context): CloudBackupStatus? {
        val p = WebDavSettings.prefs(context)
        val time = p.getLong(KEY_LAST_TIME, 0L)
        if (time == 0L) return null
        val outcome = runCatching { CloudBackupOutcome.valueOf(p.getString(KEY_LAST_OUTCOME, "") ?: "") }.getOrNull() ?: return null
        return CloudBackupStatus(
            time = time,
            outcome = outcome,
            sizeBytes = p.getLong(KEY_LAST_SIZE, 0L),
            fileName = p.getString(KEY_LAST_FILE, null),
            httpCode = p.getInt(KEY_LAST_HTTP, 0),
            detail = p.getString(KEY_LAST_DETAIL, null)
        )
    }

    /** "20261107-120000" from a backup file name, formatted for display; null if the name does not match. */
    fun displayDateFromName(name: String): String? {
        val match = BACKUP_NAME_REGEX.matchEntire(name) ?: return null
        val parsed = runCatching {
            SimpleDateFormat("yyyyMMddHHmmss", Locale.US).parse(match.groupValues[1] + match.groupValues[2])
        }.getOrNull() ?: return null
        return SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(parsed)
    }
}
