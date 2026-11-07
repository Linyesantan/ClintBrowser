package com.jhaiian.clint.settings.backuprestore

import android.Manifest
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.text.format.Formatter
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.jhaiian.clint.R
import com.jhaiian.clint.cloud.CloudBackupFeedback
import com.jhaiian.clint.cloud.CloudBackupManager
import com.jhaiian.clint.cloud.CloudBackupOutcome
import com.jhaiian.clint.cloud.CloudBackupScheduler
import com.jhaiian.clint.cloud.WebDavClient
import com.jhaiian.clint.cloud.WebDavEntry
import com.jhaiian.clint.cloud.WebDavException
import com.jhaiian.clint.cloud.WebDavSettings
import com.jhaiian.clint.settings.common.RowDivider
import com.jhaiian.clint.settings.common.SettingsRow
import com.jhaiian.clint.settings.common.SettingsSection
import com.jhaiian.clint.settings.common.SettingsSectionLabel
import com.jhaiian.clint.ui.ClintDialog
import com.jhaiian.clint.ui.ClintOutlinedPasswordField
import com.jhaiian.clint.ui.ClintOutlinedTextField
import com.jhaiian.clint.ui.ClintSwitch
import com.jhaiian.clint.ui.theme.LocalClintColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val ErrorTextColor = Color(0xFFE5484D)

/** Human readable text for any error thrown while talking to the WebDAV server. */
fun cloudErrorMessage(context: Context, error: Throwable): String = when (error) {
    is WebDavException -> if (error.kind == WebDavException.Kind.CONFIG) {
        context.getString(R.string.cloud_backup_test_invalid_url)
    } else {
        val report = CloudBackupManager.reportFor(error)
        CloudBackupFeedback.message(context, report.outcome, 0L, report.httpCode, report.detail)
    }
    else -> context.getString(R.string.cloud_backup_msg_network_error, error.message ?: error.javaClass.simpleName)
}

@Composable
fun CloudBackupSection(onRestoreFromCloud: () -> Unit) {
    val colors = LocalClintColors.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val initial = remember { WebDavSettings.load(context) }

    var serverUrl by remember { mutableStateOf(initial.serverUrl) }
    var username by remember { mutableStateOf(initial.username) }
    var password by remember { mutableStateOf(initial.password) }
    var passwordVisible by remember { mutableStateOf(false) }
    var autoEnabled by remember { mutableStateOf(WebDavSettings.isAutoBackupEnabled(context)) }
    var keepText by remember { mutableStateOf(WebDavSettings.keepCount(context).toString()) }
    var maxSizeText by remember { mutableStateOf(WebDavSettings.maxSizeMb(context).toString()) }
    var testing by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
    var status by remember { mutableStateOf(CloudBackupManager.lastStatus(context)) }

    DisposableEffect(Unit) {
        val prefs = WebDavSettings.prefs(context)
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            status = CloudBackupManager.lastStatus(context)
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    val workInfos by remember {
        WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow(CloudBackupScheduler.NOW_WORK)
    }.collectAsState(initial = emptyList())
    val backingUp = workInfos.any { it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED }

    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    fun ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    val keepValue = keepText.toIntOrNull()
    val keepInvalid = keepValue == null || keepValue !in WebDavSettings.MIN_KEEP_COUNT..WebDavSettings.MAX_KEEP_COUNT
    val maxSizeValue = maxSizeText.toIntOrNull()
    val maxSizeInvalid = maxSizeValue == null || maxSizeValue !in WebDavSettings.MIN_MAX_SIZE_MB..WebDavSettings.MAX_MAX_SIZE_MB

    val testSuccessText = stringResource(R.string.cloud_backup_test_success)
    val notConfiguredText = stringResource(R.string.cloud_backup_msg_not_configured)

    fun testConnection() {
        val config = WebDavSettings.Config(serverUrl.trim(), username.trim(), password)
        if (!config.isComplete) {
            testResult = false to notConfiguredText
            Toast.makeText(context, notConfiguredText, Toast.LENGTH_SHORT).show()
            return
        }
        testing = true
        testResult = null
        scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { WebDavClient(config).testConnection() } }
            testing = false
            val outcome = result.fold(
                onSuccess = { true to testSuccessText },
                onFailure = { false to cloudErrorMessage(context, it) }
            )
            testResult = outcome
            Toast.makeText(context, outcome.second, Toast.LENGTH_LONG).show()
        }
    }

    SettingsSectionLabel(stringResource(R.string.cloud_backup_section_title))
    SettingsSection(colors.cardBackground) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp)) {
            Text(stringResource(R.string.cloud_backup_webdav_hint), color = colors.secondaryText, fontSize = 13.sp, lineHeight = 18.sp)
            ClintOutlinedTextField(
                value = serverUrl,
                onValueChange = { serverUrl = it; WebDavSettings.saveServerUrl(context, it); testResult = null },
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                label = { Text(stringResource(R.string.cloud_backup_server_url)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)
            )
            ClintOutlinedTextField(
                value = username,
                onValueChange = { username = it; WebDavSettings.saveUsername(context, it); testResult = null },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                label = { Text(stringResource(R.string.cloud_backup_username)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii)
            )
            ClintOutlinedPasswordField(
                value = password,
                onValueChange = { password = it; WebDavSettings.savePassword(context, it); testResult = null },
                passwordVisible = passwordVisible,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                label = { Text(stringResource(R.string.cloud_backup_password)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                trailingIcon = {
                    IconButton(onClick = { passwordVisible = !passwordVisible }) {
                        Icon(
                            imageVector = if (passwordVisible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                            contentDescription = stringResource(if (passwordVisible) R.string.backup_password_hide else R.string.backup_password_show),
                            tint = colors.iconTint
                        )
                    }
                }
            )
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(
                    onClick = { testConnection() },
                    enabled = !testing,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.primary)
                ) {
                    Text(stringResource(if (testing) R.string.cloud_backup_testing else R.string.cloud_backup_test_connection))
                }
                Button(
                    onClick = {
                        ensureNotificationPermission()
                        CloudBackupScheduler.runNow(context)
                    },
                    enabled = !backingUp,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = colors.primary, contentColor = Color.White)
                ) {
                    Text(stringResource(if (backingUp) R.string.cloud_backup_running else R.string.cloud_backup_now))
                }
            }
            if (backingUp) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                    color = colors.primary,
                    trackColor = colors.surfaceVariant
                )
            }
            testResult?.let { (ok, text) ->
                Text(
                    text,
                    color = if (ok) colors.primary else ErrorTextColor,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        }
        RowDivider(colors.divider)
        SettingsRow(
            icon = Icons.Filled.Schedule,
            title = stringResource(R.string.cloud_backup_auto_title),
            summary = stringResource(R.string.cloud_backup_auto_summary),
            colors = colors,
            onClick = {
                val enabled = !autoEnabled
                autoEnabled = enabled
                WebDavSettings.setAutoBackupEnabled(context, enabled)
                CloudBackupScheduler.apply(context)
                if (enabled) ensureNotificationPermission()
            },
            trailing = { ClintSwitch(checked = autoEnabled) }
        )
        RowDivider(colors.divider)
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
            ClintOutlinedTextField(
                value = keepText,
                onValueChange = { raw ->
                    val digits = raw.filter { it.isDigit() }.take(3)
                    keepText = digits
                    digits.toIntOrNull()?.takeIf { it in WebDavSettings.MIN_KEEP_COUNT..WebDavSettings.MAX_KEEP_COUNT }
                        ?.let { WebDavSettings.setKeepCount(context, it) }
                },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.cloud_backup_keep_count)) },
                isError = keepInvalid,
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                supportingText = {
                    Text(stringResource(if (keepInvalid) R.string.cloud_backup_keep_count_error else R.string.cloud_backup_keep_count_hint))
                }
            )
            ClintOutlinedTextField(
                value = maxSizeText,
                onValueChange = { raw ->
                    val digits = raw.filter { it.isDigit() }.take(4)
                    maxSizeText = digits
                    digits.toIntOrNull()?.takeIf { it in WebDavSettings.MIN_MAX_SIZE_MB..WebDavSettings.MAX_MAX_SIZE_MB }
                        ?.let { WebDavSettings.setMaxSizeMb(context, it) }
                },
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                label = { Text(stringResource(R.string.cloud_backup_max_size)) },
                isError = maxSizeInvalid,
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                supportingText = {
                    Text(stringResource(if (maxSizeInvalid) R.string.cloud_backup_max_size_error else R.string.cloud_backup_max_size_hint))
                }
            )
        }
        RowDivider(colors.divider)
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp)) {
            val current = status
            if (current == null) {
                Text(stringResource(R.string.cloud_backup_status_none), color = colors.onSurface, fontSize = 14.sp)
            } else {
                val time = remember(current.time) {
                    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(current.time))
                }
                val size = if (current.sizeBytes > 0) Formatter.formatShortFileSize(context, current.sizeBytes)
                else stringResource(R.string.cloud_backup_size_unknown)
                Text(
                    stringResource(R.string.cloud_backup_status_line, time, CloudBackupFeedback.outcomeLabel(context, current.outcome), size),
                    color = colors.onSurface,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium
                )
                if (current.outcome != CloudBackupOutcome.SUCCESS) {
                    Text(
                        stringResource(
                            R.string.cloud_backup_last_error,
                            CloudBackupFeedback.message(context, current.outcome, current.sizeBytes, current.httpCode, current.detail)
                        ),
                        color = ErrorTextColor,
                        fontSize = 13.sp,
                        lineHeight = 18.sp,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
        }
        RowDivider(colors.divider)
        SettingsRow(
            icon = Icons.Filled.CloudDownload,
            title = stringResource(R.string.cloud_backup_restore_title),
            summary = stringResource(R.string.cloud_backup_restore_summary),
            colors = colors,
            onClick = onRestoreFromCloud
        )
    }
}

sealed class CloudRestoreListState {
    object Loading : CloudRestoreListState()
    data class Loaded(val entries: List<WebDavEntry>) : CloudRestoreListState()
    data class Failed(val message: String) : CloudRestoreListState()
}

@Composable
fun CloudRestoreDialog(
    state: CloudRestoreListState,
    hideStatusBar: Boolean,
    hideSystemNavigation: Boolean,
    onPick: (WebDavEntry) -> Unit,
    onDismiss: () -> Unit
) {
    val colors = LocalClintColors.current
    val context = LocalContext.current
    ClintDialog(
        title = stringResource(R.string.cloud_backup_restore_title),
        hideStatusBar = hideStatusBar,
        hideSystemNavigation = hideSystemNavigation,
        onDismiss = onDismiss
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
            when (state) {
                is CloudRestoreListState.Loading -> {
                    Text(stringResource(R.string.cloud_backup_restore_loading), color = colors.secondaryText, fontSize = 14.sp, modifier = Modifier.padding(8.dp))
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth().padding(8.dp),
                        color = colors.primary,
                        trackColor = colors.surfaceVariant
                    )
                }
                is CloudRestoreListState.Failed -> {
                    Text(stringResource(R.string.cloud_backup_restore_list_failed), color = colors.onSurface, fontSize = 14.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(8.dp))
                    Text(state.message, color = ErrorTextColor, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 8.dp))
                }
                is CloudRestoreListState.Loaded -> {
                    if (state.entries.isEmpty()) {
                        Text(stringResource(R.string.cloud_backup_restore_empty), color = colors.secondaryText, fontSize = 14.sp, modifier = Modifier.padding(8.dp))
                    }
                    state.entries.forEachIndexed { index, entry ->
                        if (index > 0) RowDivider(colors.divider)
                        Column(
                            Modifier.fillMaxWidth().clickable { onPick(entry) }.padding(horizontal = 8.dp, vertical = 12.dp)
                        ) {
                            Text(
                                CloudBackupManager.displayDateFromName(entry.name) ?: entry.name,
                                color = colors.onSurface,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                Formatter.formatShortFileSize(context, entry.size) + " · " + entry.name,
                                color = colors.secondaryText,
                                fontSize = 12.sp
                            )
                        }
                    }
                }
            }
        }
    }
}
