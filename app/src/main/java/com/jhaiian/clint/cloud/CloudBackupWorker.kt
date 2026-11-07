package com.jhaiian.clint.cloud

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import java.util.Calendar
import java.util.concurrent.TimeUnit

class CloudBackupWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val scheduled = inputData.getBoolean(KEY_SCHEDULED, false)
        val report = try {
            CloudBackupManager.runBackup(applicationContext)
        } catch (e: Exception) {
            CloudBackupReport(CloudBackupOutcome.BACKUP_FAILED, detail = e.message ?: e.javaClass.simpleName)
        }
        CloudBackupManager.recordStatus(applicationContext, report)
        CloudBackupFeedback.deliver(applicationContext, report)
        if (scheduled && WebDavSettings.isAutoBackupEnabled(applicationContext)) {
            CloudBackupScheduler.scheduleNextDaily(applicationContext, fromWorker = true)
        }
        return Result.success(workDataOf(KEY_OUTCOME to report.outcome.name))
    }

    companion object {
        const val KEY_SCHEDULED = "scheduled"
        const val KEY_OUTCOME = "outcome"
    }
}

object CloudBackupScheduler {
    const val DAILY_WORK = "clint_cloud_backup_daily"
    const val NOW_WORK = "clint_cloud_backup_now"
    const val DAILY_HOUR = 12
    const val DAILY_MINUTE = 0

    /** Applies the saved switch state: schedules the next 12:00 run or cancels it. */
    fun apply(context: Context) {
        if (WebDavSettings.isAutoBackupEnabled(context)) scheduleNextDaily(context, fromWorker = false)
        else WorkManager.getInstance(context).cancelUniqueWork(DAILY_WORK)
    }

    fun scheduleNextDaily(context: Context, fromWorker: Boolean) {
        val now = Calendar.getInstance()
        val next = (now.clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, DAILY_HOUR)
            set(Calendar.MINUTE, DAILY_MINUTE)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (!after(now) || (fromWorker && timeInMillis - now.timeInMillis < TimeUnit.HOURS.toMillis(1))) {
                add(Calendar.DAY_OF_YEAR, 1)
            }
        }
        val delay = next.timeInMillis - now.timeInMillis
        val request = OneTimeWorkRequestBuilder<CloudBackupWorker>()
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInputData(workDataOf(CloudBackupWorker.KEY_SCHEDULED to true))
            .addTag(DAILY_WORK)
            .build()
        // From inside the running daily worker, append instead of replace so we don't cancel ourselves.
        val policy = if (fromWorker) ExistingWorkPolicy.APPEND_OR_REPLACE else ExistingWorkPolicy.REPLACE
        WorkManager.getInstance(context).enqueueUniqueWork(DAILY_WORK, policy, request)
    }

    /** Manual "back up now": runs right away, no network constraint so a missing connection is reported instead of waiting. */
    fun runNow(context: Context) {
        val request = OneTimeWorkRequestBuilder<CloudBackupWorker>()
            .setInputData(workDataOf(CloudBackupWorker.KEY_SCHEDULED to false))
            .addTag(NOW_WORK)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(NOW_WORK, ExistingWorkPolicy.KEEP, request)
    }
}
