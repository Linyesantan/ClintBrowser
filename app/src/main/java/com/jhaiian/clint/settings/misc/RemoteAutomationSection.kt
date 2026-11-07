package com.jhaiian.clint.settings.misc

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Lan
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.SettingsEthernet
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jhaiian.clint.R
import com.jhaiian.clint.automation.RemoteAutomation
import com.jhaiian.clint.settings.common.RowDivider
import com.jhaiian.clint.settings.common.SettingsRow
import com.jhaiian.clint.settings.common.SettingsSection
import com.jhaiian.clint.settings.common.SettingsSectionLabel
import com.jhaiian.clint.settings.common.dialogSectionBackground
import com.jhaiian.clint.ui.ClintDialog
import com.jhaiian.clint.ui.ClintOutlinedTextField
import com.jhaiian.clint.ui.ClintSwitch
import com.jhaiian.clint.ui.theme.LocalClintColors

class RemoteAutomationUiState(
    enabled: Boolean,
    loopbackOnly: Boolean,
    cdp: Boolean,
    token: String,
    port: Int,
    relayUrl: String
) {
    var enabled by mutableStateOf(enabled)
    var loopbackOnly by mutableStateOf(loopbackOnly)
    var cdp by mutableStateOf(cdp)
    var token by mutableStateOf(token)
    var port by mutableStateOf(port)
    var relayUrl by mutableStateOf(relayUrl)
    var portDialogOpen by mutableStateOf(false)
    var relayDialogOpen by mutableStateOf(false)
}

class RemoteAutomationActions(
    val onToggleEnabled: () -> Unit,
    val onToggleLoopback: () -> Unit,
    val onToggleCdp: () -> Unit,
    val onCopyToken: () -> Unit,
    val onRegenerateToken: () -> Unit,
    val onPortChange: (Int) -> Unit,
    val onRelayChange: (String) -> Unit
)

@Composable
fun RemoteAutomationSection(
    state: RemoteAutomationUiState,
    status: RemoteAutomation.Status,
    lanAddresses: List<String>,
    actions: RemoteAutomationActions
) {
    val colors = LocalClintColors.current
    val context = LocalContext.current
    SettingsSectionLabel(stringResource(R.string.remote_automation_section))
    SettingsSection(colors.cardBackground) {
        SettingsRow(
            icon = Icons.Filled.SmartToy,
            title = stringResource(R.string.remote_automation_title),
            summary = stringResource(R.string.remote_automation_summary),
            colors = colors,
            onClick = actions.onToggleEnabled,
            trailing = { ClintSwitch(checked = state.enabled) }
        )
        if (state.enabled) {
            RowDivider(colors.divider)
            val endpoint = when {
                status.loopbackOnly -> "127.0.0.1:${status.port}"
                lanAddresses.isNotEmpty() -> lanAddresses.joinToString("  ") { "$it:${status.port}" }
                else -> null
            }
            val stateLine = when (status.server) {
                RemoteAutomation.ServerState.RUNNING -> stringResource(
                    R.string.remote_automation_status_running,
                    endpoint ?: stringResource(R.string.remote_automation_status_no_network)
                )
                RemoteAutomation.ServerState.STARTING -> stringResource(R.string.remote_automation_status_starting)
                RemoteAutomation.ServerState.FAILED -> stringResource(R.string.remote_automation_status_failed, status.serverError ?: "")
                RemoteAutomation.ServerState.STOPPED -> stringResource(R.string.remote_automation_status_stopped)
            }
            val hintLine = if (status.server == RemoteAutomation.ServerState.RUNNING && status.loopbackOnly)
                stringResource(R.string.remote_automation_status_loopback_hint) else null
            val requestLine = if (status.commandCount == 0 || status.lastCommand == null) {
                stringResource(R.string.remote_automation_status_no_requests)
            } else {
                val time = android.text.format.DateFormat.getTimeFormat(context).format(java.util.Date(status.lastCommandAt))
                val result = stringResource(if (status.lastCommandOk) R.string.remote_automation_result_ok else R.string.remote_automation_result_failed)
                stringResource(R.string.remote_automation_status_requests, status.commandCount, "${status.lastCommand} · $time", result)
            }
            SettingsRow(
                icon = Icons.Filled.Lan,
                title = stringResource(R.string.remote_automation_status_title),
                summary = listOfNotNull(stateLine, hintLine, requestLine).joinToString("\n"),
                colors = colors,
                onClick = {}
            )
            RowDivider(colors.divider)
            SettingsRow(
                icon = Icons.Filled.VpnKey,
                title = stringResource(R.string.remote_automation_token_title),
                summary = state.token + "\n" + stringResource(R.string.remote_automation_token_hint),
                colors = colors,
                onClick = actions.onCopyToken,
                trailing = {
                    TextButton(onClick = actions.onRegenerateToken) {
                        Text(stringResource(R.string.remote_automation_token_regenerate), color = colors.primary, fontWeight = FontWeight.Medium)
                    }
                }
            )
            RowDivider(colors.divider)
            SettingsRow(
                icon = Icons.Filled.SettingsEthernet,
                title = stringResource(R.string.remote_automation_port_title),
                summary = state.port.toString(),
                colors = colors,
                onClick = { state.portDialogOpen = true }
            )
            RowDivider(colors.divider)
            SettingsRow(
                icon = Icons.Filled.PhoneAndroid,
                title = stringResource(R.string.remote_automation_loopback_title),
                summary = stringResource(R.string.remote_automation_loopback_summary),
                colors = colors,
                onClick = actions.onToggleLoopback,
                trailing = { ClintSwitch(checked = state.loopbackOnly) }
            )
            RowDivider(colors.divider)
            val relaySummary = if (state.relayUrl.isEmpty()) {
                stringResource(R.string.remote_automation_relay_empty)
            } else {
                val relayState = when (status.relay) {
                    RemoteAutomation.RelayState.CONNECTED -> stringResource(R.string.remote_automation_relay_connected)
                    RemoteAutomation.RelayState.CONNECTING -> stringResource(R.string.remote_automation_relay_connecting)
                    RemoteAutomation.RelayState.FAILED -> stringResource(R.string.remote_automation_relay_failed, status.relayError ?: "")
                    RemoteAutomation.RelayState.DISABLED -> ""
                }
                listOf(state.relayUrl, relayState).filter { it.isNotEmpty() }.joinToString("\n")
            }
            SettingsRow(
                icon = Icons.Filled.Cloud,
                title = stringResource(R.string.remote_automation_relay_title),
                summary = relaySummary,
                colors = colors,
                onClick = { state.relayDialogOpen = true }
            )
            RowDivider(colors.divider)
            SettingsRow(
                icon = Icons.Filled.Code,
                title = stringResource(R.string.remote_automation_cdp_title),
                summary = stringResource(R.string.remote_automation_cdp_summary),
                colors = colors,
                onClick = actions.onToggleCdp,
                trailing = { ClintSwitch(checked = state.cdp) }
            )
        }
    }
}

@Composable
fun RemoteAutomationDialogs(
    state: RemoteAutomationUiState,
    hideStatusBar: Boolean,
    hideSystemNavigation: Boolean,
    actions: RemoteAutomationActions
) {
    if (state.portDialogOpen) {
        RemoteAutomationTextDialog(
            title = stringResource(R.string.remote_automation_port_title),
            message = stringResource(R.string.remote_automation_port_dialog_message),
            hint = stringResource(R.string.remote_automation_port_hint),
            initial = state.port.toString(),
            numeric = true,
            validate = { text -> text.toIntOrNull()?.takeIf { it in 1024..65535 } != null },
            errorText = stringResource(R.string.remote_automation_port_dialog_message),
            hideStatusBar = hideStatusBar, hideSystemNavigation = hideSystemNavigation,
            onConfirm = { text ->
                state.portDialogOpen = false
                actions.onPortChange(text.toInt())
            },
            onDismiss = { state.portDialogOpen = false }
        )
    }
    if (state.relayDialogOpen) {
        RemoteAutomationTextDialog(
            title = stringResource(R.string.remote_automation_relay_title),
            message = stringResource(R.string.remote_automation_relay_dialog_message),
            hint = stringResource(R.string.remote_automation_relay_hint),
            initial = state.relayUrl,
            numeric = false,
            validate = { text ->
                val t = text.trim()
                t.isEmpty() || t.startsWith("ws://", ignoreCase = true) || t.startsWith("wss://", ignoreCase = true)
            },
            errorText = stringResource(R.string.remote_automation_relay_invalid),
            hideStatusBar = hideStatusBar, hideSystemNavigation = hideSystemNavigation,
            onConfirm = { text ->
                state.relayDialogOpen = false
                actions.onRelayChange(text.trim())
            },
            onDismiss = { state.relayDialogOpen = false }
        )
    }
}

@Composable
private fun RemoteAutomationTextDialog(
    title: String,
    message: String,
    hint: String,
    initial: String,
    numeric: Boolean,
    validate: (String) -> Boolean,
    errorText: String,
    hideStatusBar: Boolean, hideSystemNavigation: Boolean,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val colors = LocalClintColors.current
    var text by remember { mutableStateOf(initial) }
    var showError by remember { mutableStateOf(false) }
    ClintDialog(
        title = title,
        hideStatusBar = hideStatusBar, hideSystemNavigation = hideSystemNavigation,
        onDismiss = onDismiss,
        footer = {
            Row(Modifier.fillMaxWidth().padding(end = 12.dp, bottom = 8.dp), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.action_cancel), color = colors.primary, fontWeight = FontWeight.Medium)
                }
                TextButton(onClick = { if (validate(text)) onConfirm(text) else showError = true }) {
                    Text(stringResource(R.string.action_ok), color = colors.primary, fontWeight = FontWeight.Medium)
                }
            }
        }
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
            Text(message, color = colors.secondaryText, fontSize = 13.sp, modifier = Modifier.padding(bottom = 8.dp))
            SettingsSection(colors.dialogSectionBackground) {
                Column(Modifier.padding(16.dp)) {
                    ClintOutlinedTextField(
                        value = text,
                        onValueChange = { new ->
                            if (!numeric || new.all { it.isDigit() }) { text = new; showError = false }
                        },
                        label = { Text(hint) },
                        singleLine = true,
                        isError = showError,
                        keyboardOptions = KeyboardOptions(keyboardType = if (numeric) KeyboardType.Number else KeyboardType.Uri),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (showError) {
                        Text(errorText, color = colors.colorError, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
                    }
                }
            }
        }
    }
}
