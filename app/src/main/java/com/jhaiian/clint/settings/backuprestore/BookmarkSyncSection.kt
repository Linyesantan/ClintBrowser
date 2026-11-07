package com.jhaiian.clint.settings.backuprestore

import android.content.SharedPreferences
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.jhaiian.clint.R
import com.jhaiian.clint.cloud.BookmarkSync
import com.jhaiian.clint.cloud.BookmarkSyncOutcome
import com.jhaiian.clint.cloud.BookmarkSyncScheduler
import com.jhaiian.clint.settings.common.RowDivider
import com.jhaiian.clint.settings.common.SettingsRow
import com.jhaiian.clint.settings.common.SettingsSection
import com.jhaiian.clint.settings.common.SettingsSectionLabel
import com.jhaiian.clint.ui.ClintOutlinedTextField
import com.jhaiian.clint.ui.ClintSwitch
import com.jhaiian.clint.ui.theme.LocalClintColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val SyncErrorColor = Color(0xFFE5484D)

/** Two-way bookmark sync with Floccus (XBEL over the WebDAV account configured for cloud backup). */
@Composable
fun BookmarkSyncSection() {
    val colors = LocalClintColors.current
    val context = LocalContext.current

    var enabled by remember { mutableStateOf(BookmarkSync.isEnabled(context)) }
    var pathText by remember { mutableStateOf(BookmarkSync.path(context)) }
    var report by remember { mutableStateOf(BookmarkSync.lastReport(context)) }

    DisposableEffect(Unit) {
        val prefs = com.jhaiian.clint.cloud.WebDavSettings.prefs(context)
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            report = BookmarkSync.lastReport(context)
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    val nowInfos by remember {
        WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow(BookmarkSyncScheduler.NOW_WORK)
    }.collectAsState(initial = emptyList())
    val changeInfos by remember {
        WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow(BookmarkSyncScheduler.CHANGE_WORK)
    }.collectAsState(initial = emptyList())
    val syncing = nowInfos.any { it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED } ||
        changeInfos.any { it.state == WorkInfo.State.RUNNING }

    val resetDoneText = stringResource(R.string.bookmark_sync_reset_done)

    SettingsSectionLabel(stringResource(R.string.bookmark_sync_section_title))
    SettingsSection(colors.cardBackground) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp)) {
            Text(stringResource(R.string.bookmark_sync_hint), color = colors.secondaryText, fontSize = 13.sp, lineHeight = 18.sp)
            ClintOutlinedTextField(
                value = pathText,
                onValueChange = { raw ->
                    pathText = raw
                    BookmarkSync.savePath(context, raw)
                },
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                label = { Text(stringResource(R.string.bookmark_sync_path)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                supportingText = { Text(stringResource(R.string.bookmark_sync_path_hint)) }
            )
            Button(
                onClick = { BookmarkSyncScheduler.runNow(context) },
                enabled = !syncing,
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                colors = ButtonDefaults.buttonColors(containerColor = colors.primary, contentColor = Color.White)
            ) {
                Text(stringResource(if (syncing) R.string.bookmark_sync_running else R.string.bookmark_sync_now))
            }
            if (syncing) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                    color = colors.primary,
                    trackColor = colors.surfaceVariant
                )
            }
        }
        RowDivider(colors.divider)
        SettingsRow(
            icon = Icons.Filled.Sync,
            title = stringResource(R.string.bookmark_sync_auto_title),
            summary = stringResource(R.string.bookmark_sync_auto_summary),
            colors = colors,
            onClick = {
                val value = !enabled
                enabled = value
                BookmarkSync.setEnabled(context, value)
                BookmarkSyncScheduler.apply(context)
            },
            trailing = { ClintSwitch(checked = enabled) }
        )
        RowDivider(colors.divider)
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp)) {
            val current = report
            if (current == null) {
                Text(stringResource(R.string.bookmark_sync_status_none), color = colors.onSurface, fontSize = 14.sp)
            } else {
                val time = remember(current.finishedAt) {
                    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(current.finishedAt))
                }
                val ok = current.outcome == BookmarkSyncOutcome.SUCCESS
                Text(
                    stringResource(
                        R.string.bookmark_sync_status_line,
                        time,
                        stringResource(if (ok) R.string.cloud_backup_result_success else R.string.bookmark_sync_result_failed)
                    ),
                    color = colors.onSurface,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    BookmarkSync.message(context, current),
                    color = if (ok) colors.secondaryText else SyncErrorColor,
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }
        RowDivider(colors.divider)
        SettingsRow(
            icon = Icons.Filled.RestartAlt,
            title = stringResource(R.string.bookmark_sync_reset_title),
            summary = stringResource(R.string.bookmark_sync_reset_summary),
            colors = colors,
            onClick = {
                BookmarkSync.resetBase(context)
                Toast.makeText(context, resetDoneText, Toast.LENGTH_SHORT).show()
            }
        )
    }
}
