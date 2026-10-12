package com.nuvio.app.features.boomio

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.ui.NuvioScreen
import com.nuvio.app.core.ui.NuvioScreenHeader
import com.nuvio.app.core.ui.NuvioSurfaceCard
import com.nuvio.app.core.ui.PlatformBackHandler
import kotlinx.coroutines.launch
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.admin_account_add_title
import nuvio.composeapp.generated.resources.admin_account_summary
import nuvio.composeapp.generated.resources.admin_account_title
import nuvio.composeapp.generated.resources.admin_accounts_empty
import nuvio.composeapp.generated.resources.admin_accounts_title
import nuvio.composeapp.generated.resources.admin_bandwidth_invalid
import nuvio.composeapp.generated.resources.admin_bandwidth_max
import nuvio.composeapp.generated.resources.admin_bandwidth_min
import nuvio.composeapp.generated.resources.admin_bandwidth_note
import nuvio.composeapp.generated.resources.admin_bandwidth_title
import nuvio.composeapp.generated.resources.admin_create
import nuvio.composeapp.generated.resources.admin_creating
import nuvio.composeapp.generated.resources.admin_delete
import nuvio.composeapp.generated.resources.admin_delete_confirm_label
import nuvio.composeapp.generated.resources.admin_delete_title
import nuvio.composeapp.generated.resources.admin_delete_warning
import nuvio.composeapp.generated.resources.admin_deleting
import nuvio.composeapp.generated.resources.admin_device_limit_invalid
import nuvio.composeapp.generated.resources.admin_device_limit_label
import nuvio.composeapp.generated.resources.admin_device_limit_unset
import nuvio.composeapp.generated.resources.admin_device_title
import nuvio.composeapp.generated.resources.admin_devices_empty
import nuvio.composeapp.generated.resources.admin_devices_title
import nuvio.composeapp.generated.resources.admin_email_label
import nuvio.composeapp.generated.resources.admin_email_unknown
import nuvio.composeapp.generated.resources.admin_last_seen
import nuvio.composeapp.generated.resources.admin_last_seen_never
import nuvio.composeapp.generated.resources.admin_limits_audio
import nuvio.composeapp.generated.resources.admin_limits_bandwidth
import nuvio.composeapp.generated.resources.admin_limits_bandwidth_invalid
import nuvio.composeapp.generated.resources.admin_limits_clear
import nuvio.composeapp.generated.resources.admin_limits_clear_note
import nuvio.composeapp.generated.resources.admin_limits_codecs
import nuvio.composeapp.generated.resources.admin_limits_hdr
import nuvio.composeapp.generated.resources.admin_limits_resolution
import nuvio.composeapp.generated.resources.admin_limits_save
import nuvio.composeapp.generated.resources.admin_limits_set_by_you
import nuvio.composeapp.generated.resources.admin_limits_title
import nuvio.composeapp.generated.resources.admin_loading
import nuvio.composeapp.generated.resources.admin_not_admin_description
import nuvio.composeapp.generated.resources.admin_not_admin_title
import nuvio.composeapp.generated.resources.admin_open
import nuvio.composeapp.generated.resources.admin_password_label
import nuvio.composeapp.generated.resources.admin_restore
import nuvio.composeapp.generated.resources.admin_restoring
import nuvio.composeapp.generated.resources.admin_revoke
import nuvio.composeapp.generated.resources.admin_revoked_badge
import nuvio.composeapp.generated.resources.admin_revoking
import nuvio.composeapp.generated.resources.admin_revoke_confirm_hint
import nuvio.composeapp.generated.resources.admin_save
import nuvio.composeapp.generated.resources.admin_saving
import nuvio.composeapp.generated.resources.compose_settings_page_admin
import org.jetbrains.compose.resources.stringResource

/**
 * The administrator surface: every account on the box, and everything hanging off one.
 *
 * One ROUTE with in-screen depth, not three routes. The three levels are a drill-down — accounts,
 * one account, one device — and each level is meaningless without the one above it, so three
 * destinations would triple the navigation wiring (a route class, a serializer registration, an
 * `entry<>` block, a title, four pass-throughs in `SettingsScreen`) to buy nothing a back handler
 * does not already give. [PlatformBackHandler] unwinds the levels, so Android's back button behaves
 * the way the arrows look.
 *
 * ⚠️ THIS SCREEN IS NOT THE AUTHORITY. It reads [BsmAdminRepository.isAdmin] to decide whether to
 * render the not-an-administrator card, but every call it makes is re-checked by bsm against the
 * profile the request claims. A build that got the flag wrong would show cards and then collect 403s.
 */
@Composable
fun AdminScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isAdmin by BsmAdminRepository.isAdmin.collectAsStateWithLifecycle()
    var view by remember { mutableStateOf<AdminView>(AdminView.Accounts) }

    // ⚠️ `enabled` on the ROOT only. At the root there is nothing to unwind, so back must fall
    // through to the navigation host and leave the screen — consuming it there would trap the person
    // on a screen whose header arrow still works.
    PlatformBackHandler(enabled = view != AdminView.Accounts) {
        view = when (val current = view) {
            is AdminView.Device -> AdminView.Account(current.accountId)
            is AdminView.Account -> AdminView.Accounts
            AdminView.Accounts -> AdminView.Accounts
        }
    }

    NuvioScreen(modifier = modifier) {
        stickyHeader {
            NuvioScreenHeader(
                title = stringResource(
                    when (view) {
                        AdminView.Accounts -> Res.string.compose_settings_page_admin
                        is AdminView.Account -> Res.string.admin_account_title
                        is AdminView.Device -> Res.string.admin_device_title
                    }
                ),
                onBack = {
                    when (val current = view) {
                        is AdminView.Device -> view = AdminView.Account(current.accountId)
                        is AdminView.Account -> view = AdminView.Accounts
                        AdminView.Accounts -> onBack()
                    }
                },
            )
        }
        item {
            // `null` is "not asked yet", which is a third state from "no": it must not be rendered
            // as the not-an-administrator card, or a session that is merely still loading would be
            // told it has no rights.
            when (isAdmin) {
                true -> when (val current = view) {
                    AdminView.Accounts -> AccountsView(
                        onOpenAccount = { view = AdminView.Account(it) },
                    )
                    is AdminView.Account -> AccountView(
                        accountId = current.id,
                        onOpenDevice = { view = AdminView.Device(current.id, it) },
                        onDeleted = { view = AdminView.Accounts },
                    )
                    is AdminView.Device -> DeviceView(
                        ref = current.ref,
                        onAccountChanged = { view = AdminView.Account(current.accountId) },
                    )
                }
                false -> NotAdminCard()
                null -> LoadingCard()
            }
        }
    }
}

/**
 * Where the screen is, as a value rather than as navigation state.
 *
 * `Account` carries its own id so a device can be handed back to its account, and `Device` carries
 * BOTH: the ref it was opened with, and the account it belongs to, so unwinding from a device does
 * not need the account re-fetched to know where "back" goes.
 */
private sealed interface AdminView {
    data object Accounts : AdminView
    data class Account(val id: String) : AdminView
    data class Device(val accountId: String, val ref: String) : AdminView
}

// ── accounts ───────────────────────────────────────────────────────────────────

@Composable
private fun AccountsView(onOpenAccount: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    var accounts by remember { mutableStateOf<List<AdminAccount>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    var noticeIsError by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }

    var newEmail by remember { mutableStateOf("") }
    var newPassword by remember { mutableStateOf("") }

    // ⚠️ `LaunchedEffect(Unit)` and not a cache: leaving a level DISPOSES its subtree, so returning
    // to it re-runs this and re-reads from the server. That is why nothing here invalidates anything
    // after a write elsewhere — the next visit is the refresh.
    LaunchedEffect(Unit) {
        when (val result = BsmAdminRepository.loadAccounts()) {
            is AdminResult.Ok -> accounts = result.value
            is AdminResult.Failed -> {
                notice = result.message
                noticeIsError = true
            }
        }
        loaded = true
    }

    fun create() {
        if (busy) return
        busy = true
        notice = null
        scope.launch {
            when (val result = BsmAdminRepository.createAccount(newEmail, newPassword)) {
                is AdminResult.Ok -> {
                    newEmail = ""
                    newPassword = ""
                    noticeIsError = false
                    when (val refreshed = BsmAdminRepository.loadAccounts()) {
                        is AdminResult.Ok -> accounts = refreshed.value
                        is AdminResult.Failed -> {
                            notice = refreshed.message
                            noticeIsError = true
                        }
                    }
                }
                is AdminResult.Failed -> {
                    notice = result.message
                    noticeIsError = true
                }
            }
            busy = false
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
        NoticeLine(notice, noticeIsError)

        if (!loaded) {
            LoadingLine()
            return@Column
        }

        NuvioSurfaceCard {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    stringResource(Res.string.admin_accounts_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                if (accounts.isEmpty()) {
                    Text(
                        stringResource(Res.string.admin_accounts_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    return@Column
                }
                accounts.forEach { account ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(account.name.ifBlank { account.id }, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                // A null email is "GoTrue did not answer", not "no email" — the
                                // household is local and can be listed through a Nuvio outage.
                                account.email ?: stringResource(Res.string.admin_email_unknown),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                stringResource(
                                    Res.string.admin_account_summary,
                                    account.userCount,
                                    account.deviceCount,
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        TextButton(onClick = { onOpenAccount(account.id) }, enabled = !busy) {
                            Text(stringResource(Res.string.admin_open))
                        }
                    }
                }
            }
        }

        NuvioSurfaceCard {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    stringResource(Res.string.admin_account_add_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                OutlinedTextField(
                    value = newEmail,
                    onValueChange = { newEmail = it; notice = null },
                    label = { Text(stringResource(Res.string.admin_email_label)) },
                    singleLine = true,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                )
                OutlinedTextField(
                    value = newPassword,
                    onValueChange = { newPassword = it; notice = null },
                    label = { Text(stringResource(Res.string.admin_password_label)) },
                    singleLine = true,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                )
                Button(
                    onClick = { create() },
                    enabled = !busy && newEmail.isNotBlank() && newPassword.isNotBlank(),
                ) {
                    Text(
                        if (busy) stringResource(Res.string.admin_creating)
                        else stringResource(Res.string.admin_create),
                    )
                }
            }
        }
    }
}

// ── one account ────────────────────────────────────────────────────────────────

@Composable
private fun AccountView(
    accountId: String,
    onOpenDevice: (String) -> Unit,
    onDeleted: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var detail by remember { mutableStateOf<AdminAccountDetail?>(null) }
    var loaded by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    var noticeIsError by remember { mutableStateOf(false) }

    var limitText by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    var confirmEmail by remember { mutableStateOf("") }

    // Resolved once, here, rather than inside `saveLimit` — a `stringResource` call is @Composable
    // and cannot be made from a plain local function's body.
    val invalidLimit = stringResource(Res.string.admin_device_limit_invalid)

    LaunchedEffect(Unit) {
        when (val result = BsmAdminRepository.loadAccount(accountId)) {
            is AdminResult.Ok -> {
                detail = result.value
                limitText = result.value.account.maxDevices?.toString().orEmpty()
            }
            is AdminResult.Failed -> {
                notice = result.message
                noticeIsError = true
            }
        }
        loaded = true
    }

    fun saveLimit() {
        if (busy) return
        val parsed = limitText.trim().toIntOrNull()
        if (parsed == null) {
            notice = invalidLimit
            noticeIsError = true
            return
        }
        busy = true
        notice = null
        scope.launch {
            when (val result = BsmAdminRepository.setDeviceLimit(accountId, parsed)) {
                is AdminResult.Ok -> {
                    noticeIsError = false
                    // Re-read rather than patch locally: the server clamps and normalises, so its
                    // answer is the one to show.
                    when (val refreshed = BsmAdminRepository.loadAccount(accountId)) {
                        is AdminResult.Ok -> detail = refreshed.value
                        is AdminResult.Failed -> Unit
                    }
                }
                is AdminResult.Failed -> {
                    notice = result.message
                    noticeIsError = true
                }
            }
            busy = false
        }
    }

    fun delete() {
        if (busy) return
        busy = true
        notice = null
        scope.launch {
            val result = BsmAdminRepository.deleteAccount(accountId, confirmEmail)
            when (result) {
                is AdminResult.Ok -> onDeleted()
                is AdminResult.Failed -> {
                    // ⚠️ Re-read on EVERY outcome, including a failure. The delete can exceed the
                    // client's read timeout, so a reported failure may describe an account that is
                    // already gone — and re-reading is the only way to tell the person the truth.
                    when (val refreshed = BsmAdminRepository.loadAccount(accountId)) {
                        is AdminResult.Ok -> detail = refreshed.value
                        is AdminResult.Failed -> detail = null
                    }
                    notice = result.message
                    noticeIsError = true
                }
            }
            busy = false
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
        NoticeLine(notice, noticeIsError)

        val current = detail
        if (current == null) {
            // Nothing to render: either still loading, or the load failed and the notice above is
            // the whole story.
            if (!loaded) LoadingLine()
            return@Column
        }

        Text(current.account.name.ifBlank { current.account.id }, style = MaterialTheme.typography.titleMedium)
        current.account.email?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        NuvioSurfaceCard {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    stringResource(Res.string.admin_device_limit_label),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                if (current.account.maxDevices == null) {
                    Text(
                        stringResource(Res.string.admin_device_limit_unset),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                OutlinedTextField(
                    value = limitText,
                    onValueChange = { limitText = it; notice = null },
                    singleLine = true,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                Button(onClick = { saveLimit() }, enabled = !busy) {
                    Text(if (busy) stringResource(Res.string.admin_saving) else stringResource(Res.string.admin_save))
                }
            }
        }

        current.users.forEach { profile ->
            ProfileBandwidthCard(profile = profile, onSaved = { })
        }

        DevicesCard(devices = current.devices, onOpenDevice = onOpenDevice)

        DeleteAccountCard(
            accountId = accountId,
            confirmEmail = confirmEmail,
            onConfirmEmailChange = { confirmEmail = it; notice = null },
            busy = busy,
            onDelete = { delete() },
        )
    }
}

@Composable
private fun ProfileBandwidthCard(profile: AdminProfile, onSaved: () -> Unit) {
    val scope = rememberCoroutineScope()
    var minText by remember(profile.id) { mutableStateOf(profile.minBandwidthMbps?.let(::trimNumber).orEmpty()) }
    var maxText by remember(profile.id) { mutableStateOf(profile.maxBandwidthMbps?.let(::trimNumber).orEmpty()) }
    var busy by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    var noticeIsError by remember { mutableStateOf(false) }

    // Hoisted for the same reason as in AccountView: `save` is a plain local function.
    val invalidBandwidth = stringResource(Res.string.admin_bandwidth_invalid)

    fun save() {
        if (busy) return
        val min = minText.trim().takeIf { it.isNotEmpty() }?.toDoubleOrNull()
        val max = maxText.trim().takeIf { it.isNotEmpty() }?.toDoubleOrNull()
        if ((minText.isNotBlank() && min == null) || (maxText.isNotBlank() && max == null)) {
            notice = invalidBandwidth
            noticeIsError = true
            return
        }
        busy = true
        notice = null
        scope.launch {
            when (val result = BsmAdminRepository.setBandwidth(profile.id, min, max)) {
                is AdminResult.Ok -> {
                    noticeIsError = false
                    notice = null
                    onSaved()
                }
                is AdminResult.Failed -> {
                    notice = result.message
                    noticeIsError = true
                }
            }
            busy = false
        }
    }

    NuvioSurfaceCard {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                profile.displayName ?: profile.username,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            // ⚠️ The UI must not imply this shapes playback. Only a DEVICE's own cap reaches a
            // filter; nothing in bsm reads these two columns.
            Text(
                stringResource(Res.string.admin_bandwidth_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                stringResource(Res.string.admin_bandwidth_title),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = minText,
                    onValueChange = { minText = it; notice = null },
                    label = { Text(stringResource(Res.string.admin_bandwidth_min)) },
                    singleLine = true,
                    enabled = !busy,
                    modifier = Modifier.weight(1f),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                OutlinedTextField(
                    value = maxText,
                    onValueChange = { maxText = it; notice = null },
                    label = { Text(stringResource(Res.string.admin_bandwidth_max)) },
                    singleLine = true,
                    enabled = !busy,
                    modifier = Modifier.weight(1f),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
            }
            NoticeLine(notice, noticeIsError)
            Button(onClick = { save() }, enabled = !busy) {
                Text(if (busy) stringResource(Res.string.admin_saving) else stringResource(Res.string.admin_save))
            }
        }
    }
}

@Composable
private fun DevicesCard(devices: List<AdminDevice>, onOpenDevice: (String) -> Unit) {
    NuvioSurfaceCard {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                stringResource(Res.string.admin_devices_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            if (devices.isEmpty()) {
                Text(
                    stringResource(Res.string.admin_devices_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                return@Column
            }
            devices.forEach { device ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            device.name,
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (device.revoked) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                        )
                        Text(
                            if (device.revoked) stringResource(Res.string.admin_revoked_badge)
                            else lastSeenLabel(device.lastSeen),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(onClick = { onOpenDevice(device.deviceId) }) {
                        Text(stringResource(Res.string.admin_open))
                    }
                }
            }
        }
    }
}

/**
 * The one verb with no undo.
 *
 * A card rather than a dialog on purpose: it needs an explanation sentence and a text field, and a
 * dialog that long is worse than a card in a list. The button stays disabled until the typed email
 * matches, which is a guard against a mis-tap — NOT the guard, which is server-side.
 */
@Composable
private fun DeleteAccountCard(
    accountId: String,
    confirmEmail: String,
    onConfirmEmailChange: (String) -> Unit,
    busy: Boolean,
    onDelete: () -> Unit,
) {
    NuvioSurfaceCard {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                stringResource(Res.string.admin_delete_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.error,
            )
            // The warning must state in WORDS that a snapshot is taken first, because that is the
            // difference between "this is reckless" and "this is recoverable by hand on the box".
            Text(
                stringResource(Res.string.admin_delete_warning),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = confirmEmail,
                onValueChange = onConfirmEmailChange,
                label = { Text(stringResource(Res.string.admin_delete_confirm_label)) },
                singleLine = true,
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
            )
            Button(
                onClick = onDelete,
                enabled = !busy && confirmEmail.isNotBlank(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                ),
            ) {
                Text(
                    if (busy) stringResource(Res.string.admin_deleting)
                    else stringResource(Res.string.admin_delete),
                )
            }
        }
    }
}

// ── one device ─────────────────────────────────────────────────────────────────

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DeviceView(ref: String, onAccountChanged: () -> Unit) {
    val scope = rememberCoroutineScope()
    var detail by remember { mutableStateOf<AdminDeviceDetail?>(null) }
    var loaded by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    var noticeIsError by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }

    // The editor's whole state. Seeded from the server on load and re-seeded after every write,
    // because the server canonicalises (a pasted "3840x2160" becomes "4k") and its normal form is
    // the one to show.
    var resolution by remember { mutableStateOf<String?>(null) }
    var codecs by remember { mutableStateOf<Set<String>>(emptySet()) }
    var hdr by remember { mutableStateOf<Set<String>>(emptySet()) }
    var audio by remember { mutableStateOf<Set<String>>(emptySet()) }
    var bandwidth by remember { mutableStateOf("") }

    var confirmName by remember { mutableStateOf("") }

    // Hoisted for the same reason as in AccountView: `save` is a plain local function.
    val invalidBandwidth = stringResource(Res.string.admin_limits_bandwidth_invalid)

    fun seed(d: AdminDeviceDetail) {
        detail = d
        resolution = d.device.limits.maxResolution
        codecs = d.device.limits.videoCodecs.toSet()
        hdr = d.device.limits.hdr.toSet()
        audio = d.device.limits.audioChannels.toSet()
        bandwidth = d.device.limits.bandwidthCapMbps?.let(::trimNumber).orEmpty()
    }

    LaunchedEffect(Unit) {
        when (val result = BsmAdminRepository.loadDevice(ref)) {
            is AdminResult.Ok -> seed(result.value)
            is AdminResult.Failed -> {
                notice = result.message
                noticeIsError = true
            }
        }
        loaded = true
    }

    fun save(clear: Boolean) {
        if (busy) return
        val cap = bandwidth.trim().takeIf { it.isNotEmpty() }?.toDoubleOrNull()
        if (!clear && bandwidth.isNotBlank() && cap == null) {
            notice = invalidBandwidth
            noticeIsError = true
            return
        }
        busy = true
        notice = null
        val patch = if (clear) {
            // Every field null: hand all of them back to what the device reported.
            AdminPolicyPatch(
                maxResolution = null, videoCodecs = null, hdr = null,
                audioChannels = null, bandwidthCapMbps = null,
            )
        } else {
            // ⚠️ An EMPTY selection is sent as null, not as an empty list. An empty array is a stored
            // override that owns the field while constraining nothing, which reads downstream as "the
            // operator set this" — so it would show as set-by-you and never fall back.
            AdminPolicyPatch(
                maxResolution = resolution,
                videoCodecs = codecs.takeIf { it.isNotEmpty() }?.toList()?.sorted(),
                hdr = hdr.takeIf { it.isNotEmpty() }?.toList()?.sorted(),
                audioChannels = audio.takeIf { it.isNotEmpty() }?.toList()?.sorted(),
                bandwidthCapMbps = cap,
            )
        }
        scope.launch {
            when (val result = BsmAdminRepository.savePolicy(ref, patch)) {
                is AdminResult.Ok -> {
                    seed(result.value)
                    noticeIsError = false
                    onAccountChanged()
                }
                is AdminResult.Failed -> {
                    notice = result.message
                    noticeIsError = true
                }
            }
            busy = false
        }
    }

    fun setRevoked(revoke: Boolean) {
        if (busy) return
        busy = true
        notice = null
        scope.launch {
            val result = if (revoke) {
                BsmAdminRepository.revoke(ref, confirmName)
            } else {
                BsmAdminRepository.unrevoke(ref)
            }
            when (result) {
                is AdminResult.Ok -> {
                    // Re-read rather than trust the echo: only a fresh read carries the device's
                    // current `revoked` flag and the ceilings as the server now resolves them.
                    when (val refreshed = BsmAdminRepository.loadDevice(ref)) {
                        is AdminResult.Ok -> seed(refreshed.value)
                        is AdminResult.Failed -> Unit
                    }
                    confirmName = ""
                    noticeIsError = false
                    onAccountChanged()
                }
                is AdminResult.Failed -> {
                    notice = result.message
                    noticeIsError = true
                }
            }
            busy = false
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
        NoticeLine(notice, noticeIsError)

        val current = detail
        if (current == null) {
            if (!loaded) LoadingLine()
            return@Column
        }

        Text(current.device.name, style = MaterialTheme.typography.titleMedium)
        Text(
            if (current.device.revoked) stringResource(Res.string.admin_revoked_badge)
            else lastSeenLabel(current.device.lastSeen),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        NuvioSurfaceCard {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    stringResource(Res.string.admin_limits_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    stringResource(Res.string.admin_limits_clear_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                ChipSection(
                    label = stringResource(Res.string.admin_limits_resolution),
                    options = current.vocabulary.maxResolution,
                    selected = setOfNotNull(resolution),
                    owned = current.device.limits.overridden.contains("max_resolution"),
                    enabled = !busy,
                    onToggle = { value ->
                        // Single-select with a way back to the device's own answer: tapping the
                        // chosen one again hands the field back rather than leaving it stuck.
                        resolution = if (resolution == value) null else value
                        notice = null
                    },
                )
                ChipSection(
                    label = stringResource(Res.string.admin_limits_codecs),
                    options = current.vocabulary.videoCodecs,
                    selected = codecs,
                    owned = current.device.limits.overridden.contains("video_codecs"),
                    enabled = !busy,
                    onToggle = { value -> codecs = codecs.toggle(value); notice = null },
                )
                ChipSection(
                    label = stringResource(Res.string.admin_limits_hdr),
                    options = current.vocabulary.hdr,
                    selected = hdr,
                    owned = current.device.limits.overridden.contains("hdr"),
                    enabled = !busy,
                    onToggle = { value -> hdr = hdr.toggle(value); notice = null },
                )
                ChipSection(
                    label = stringResource(Res.string.admin_limits_audio),
                    options = current.vocabulary.audioChannels,
                    selected = audio,
                    owned = current.device.limits.overridden.contains("audio_channels"),
                    enabled = !busy,
                    onToggle = { value -> audio = audio.toggle(value); notice = null },
                )

                OutlinedTextField(
                    value = bandwidth,
                    onValueChange = { bandwidth = it; notice = null },
                    label = { Text(stringResource(Res.string.admin_limits_bandwidth)) },
                    singleLine = true,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { save(clear = false) }, enabled = !busy) {
                        Text(if (busy) stringResource(Res.string.admin_saving) else stringResource(Res.string.admin_limits_save))
                    }
                    TextButton(onClick = { save(clear = true) }, enabled = !busy) {
                        Text(stringResource(Res.string.admin_limits_clear))
                    }
                }
            }
        }

        NuvioSurfaceCard {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (current.device.revoked) {
                    Button(onClick = { setRevoked(false) }, enabled = !busy) {
                        Text(if (busy) stringResource(Res.string.admin_restoring) else stringResource(Res.string.admin_restore))
                    }
                } else {
                    // The device's name, typed — the server re-checks the same equality, since
                    // revoking the wrong TV here is invisible until someone tries to watch.
                    Text(
                        stringResource(Res.string.admin_revoke_confirm_hint, current.device.name),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = confirmName,
                        onValueChange = { confirmName = it; notice = null },
                        singleLine = true,
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Button(
                        onClick = { setRevoked(true) },
                        enabled = !busy && confirmName.trim().equals(current.device.name, ignoreCase = true),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                            contentColor = MaterialTheme.colorScheme.onError,
                        ),
                    ) {
                        Text(if (busy) stringResource(Res.string.admin_revoking) else stringResource(Res.string.admin_revoke))
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChipSection(
    label: String,
    options: List<String>,
    selected: Set<String>,
    owned: Boolean,
    enabled: Boolean,
    onToggle: (String) -> Unit,
) {
    if (options.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            // Marks a field the operator owns, which is the one thing the editor cannot infer from
            // the values: a ceiling the device reported and a ceiling someone set look identical.
            if (owned) {
                Text(
                    stringResource(Res.string.admin_limits_set_by_you),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { option ->
                FilterChip(
                    selected = option in selected,
                    onClick = { if (enabled) onToggle(option) },
                    enabled = enabled,
                    label = { Text(option) },
                )
            }
        }
    }
}

// ── shared bits ────────────────────────────────────────────────────────────────

@Composable
private fun NoticeLine(notice: String?, isError: Boolean) {
    notice?.let { line ->
        Text(
            line,
            style = MaterialTheme.typography.bodyMedium,
            color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun LoadingLine() {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
        Text(stringResource(Res.string.admin_loading), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun LoadingCard() {
    NuvioSurfaceCard { LoadingLine() }
}

@Composable
private fun NotAdminCard() {
    NuvioSurfaceCard {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                stringResource(Res.string.admin_not_admin_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                stringResource(Res.string.admin_not_admin_description),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun Set<String>.toggle(value: String): Set<String> =
    if (value in this) this - value else this + value

/** `25.0` reads as `25`; the server stores a number and the field is a text box. */
private fun trimNumber(value: Double): String =
    if (value % 1.0 == 0.0) value.toLong().toString() else value.toString()

/**
 * bsm hands back an ISO-8601 timestamp; only the date is shown, taken as a substring rather than
 * parsed. This screen has no date library in commonMain, and inventing a formatter to render
 * "2026-10-12" from a string that already starts with it would be worse than the substring. A device
 * that has never connected is said in words, because that is a different situation from a date.
 */
@Composable
private fun lastSeenLabel(lastSeen: String?): String {
    val date = lastSeen?.trim()?.substringBefore('T')?.takeIf { it.isNotEmpty() }
    return if (date == null) {
        stringResource(Res.string.admin_last_seen_never)
    } else {
        stringResource(Res.string.admin_last_seen, date)
    }
}
