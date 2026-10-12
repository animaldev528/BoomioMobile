package com.nuvio.app.features.boomio

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.auth.AuthRepository
import com.nuvio.app.core.auth.isAnonymous
import com.nuvio.app.core.auth.isLoggedIn
import com.nuvio.app.core.auth.normalizeDeviceLinkCode
import com.nuvio.app.core.ui.NuvioScreen
import com.nuvio.app.core.ui.NuvioScreenHeader
import com.nuvio.app.core.ui.NuvioSurfaceCard
import kotlinx.coroutines.launch
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.compose_settings_page_enroll_tv
import nuvio.composeapp.generated.resources.enroll_tv_code_label
import nuvio.composeapp.generated.resources.enroll_tv_devices_empty
import nuvio.composeapp.generated.resources.enroll_tv_devices_title
import nuvio.composeapp.generated.resources.enroll_tv_enrolling
import nuvio.composeapp.generated.resources.enroll_tv_intro
import nuvio.composeapp.generated.resources.enroll_tv_last_seen
import nuvio.composeapp.generated.resources.enroll_tv_last_seen_never
import nuvio.composeapp.generated.resources.enroll_tv_limit_reached
import nuvio.composeapp.generated.resources.enroll_tv_need_account_description
import nuvio.composeapp.generated.resources.enroll_tv_need_account_title
import nuvio.composeapp.generated.resources.enroll_tv_revoke
import nuvio.composeapp.generated.resources.enroll_tv_revoking
import nuvio.composeapp.generated.resources.enroll_tv_submit
import nuvio.composeapp.generated.resources.enroll_tv_success
import org.jetbrains.compose.resources.stringResource

/**
 * Enrols a TV — its own certificate and tunnel — by approving the code on its setup screen.
 *
 * This is the enrolment half of a pair whose other half is [AddTvScreen]. The two are separate
 * screens with separate labels because they do different things to the same TV: signing in gives it
 * a user session, enrolling gives it a device identity. Both read a 6-character code off the TV, and
 * the codes look alike, so every label here says "setup" where the sign-in screen says "sign in".
 *
 * The phone is the approving party, not a browser: the code is approved against the signed-in Nuvio
 * account, which is why this needs a real account rather than a guest session.
 */
@Composable
fun EnrollTvScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val authState by AuthRepository.state.collectAsStateWithLifecycle()
    // The same app-wide test for "has a real Nuvio account" that AddTvScreen uses: a guest session
    // is AuthState.Authenticated too, and holds no account for a TV to be enrolled onto.
    val signedIn = authState.isLoggedIn && !authState.isAnonymous

    NuvioScreen(modifier = modifier) {
        stickyHeader {
            NuvioScreenHeader(
                title = stringResource(Res.string.compose_settings_page_enroll_tv),
                onBack = onBack,
            )
        }
        item {
            if (signedIn) {
                EnrollBody()
            } else {
                NeedsAccountCard()
            }
        }
    }
}

@Composable
private fun EnrollBody() {
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current

    var code by remember { mutableStateOf("") }
    var submitting by remember { mutableStateOf(false) }
    var revoking by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var noticeIsError by remember { mutableStateOf(false) }
    var devices by remember { mutableStateOf<List<EnrolledTv>>(emptyList()) }
    var limit by remember { mutableStateOf(0) }
    // True once the account is known to be at its cap. It drives the hint above the list, and it is
    // cleared by any answer that is not a refusal at the cap — including a successful removal, which
    // is the whole reason the list is on this screen.
    var atLimit by remember { mutableStateOf(false) }

    val complete = code.length == TvEnrollRepository.CODE_LENGTH

    // Resolved here, not inside `handle`: a local function cannot call a @Composable, and
    // `stringResource` is one.
    val successText = stringResource(Res.string.enroll_tv_success)

    // Named `handle`, not `apply`: a local function called `apply` inside a composable reads as
    // Kotlin's scope function and is a trap for the next person to edit this.
    fun handle(outcome: TvEnrollOutcome, approved: Boolean) {
        when (outcome) {
            is TvEnrollOutcome.Ok -> {
                devices = outcome.devices
                limit = outcome.limit
                atLimit = false
                noticeIsError = false
                // The named device, then the success line — a person with two TVs needs to know
                // which one just went through. A plain refresh has nothing to announce.
                notice = if (approved && outcome.approvedName != null) {
                    "${outcome.approvedName} — $successText"
                } else {
                    null
                }
                if (approved) code = ""
            }
            is TvEnrollOutcome.AtLimit -> {
                devices = outcome.devices
                limit = outcome.limit
                atLimit = true
                noticeIsError = true
                notice = outcome.message
            }
            is TvEnrollOutcome.Failed -> {
                atLimit = false
                noticeIsError = true
                notice = outcome.message
            }
        }
    }

    fun submit() {
        if (submitting || !complete) return
        keyboard?.hide()
        notice = null
        submitting = true
        scope.launch {
            handle(TvEnrollRepository.approve(code), approved = true)
            submitting = false
        }
    }

    fun revoke(tv: EnrolledTv) {
        if (revoking != null) return
        revoking = tv.id
        scope.launch {
            handle(TvEnrollRepository.revoke(tv.id), approved = false)
            revoking = null
        }
    }

    // Load once on entry so the list and the cap are known before anyone types anything: at the cap
    // the person needs to see the list *first* to know what to remove.
    LaunchedEffect(Unit) {
        handle(TvEnrollRepository.load(), approved = false)
    }

    Column(
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        NuvioSurfaceCard {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    stringResource(Res.string.enroll_tv_intro),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = code,
                    // Filtered on the way in, exactly as the sign-in screen does it, so the field
                    // only ever holds what the server's own normalizer would keep.
                    onValueChange = {
                        code = normalizeDeviceLinkCode(it).take(TvEnrollRepository.CODE_LENGTH)
                        notice = null
                    },
                    label = { Text(stringResource(Res.string.enroll_tv_code_label)) },
                    singleLine = true,
                    enabled = !submitting,
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Ascii,
                        imeAction = ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(onDone = { submit() }),
                )
                Button(
                    onClick = { submit() },
                    enabled = complete && !submitting,
                ) {
                    Text(stringResource(Res.string.enroll_tv_submit))
                }
                if (submitting) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        Text(
                            stringResource(Res.string.enroll_tv_enrolling),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
        }

        notice?.let { line ->
            Text(
                line,
                style = MaterialTheme.typography.bodyMedium,
                color = if (noticeIsError) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.primary
                },
            )
        }

        TvListCard(
            devices = devices,
            limit = limit,
            atLimit = atLimit,
            revoking = revoking,
            onRevoke = ::revoke,
        )
    }
}

@Composable
private fun TvListCard(
    devices: List<EnrolledTv>,
    limit: Int,
    atLimit: Boolean,
    revoking: String?,
    onRevoke: (EnrolledTv) -> Unit,
) {
    NuvioSurfaceCard {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                stringResource(Res.string.enroll_tv_devices_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            if (atLimit && limit > 0) {
                Text(
                    stringResource(Res.string.enroll_tv_limit_reached, limit),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (devices.isEmpty()) {
                Text(
                    stringResource(Res.string.enroll_tv_devices_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                return@Column
            }
            devices.forEach { tv ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(tv.name, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            lastSeenLabel(tv.lastSeen),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(
                        onClick = { onRevoke(tv) },
                        enabled = revoking == null,
                    ) {
                        Text(
                            if (revoking == tv.id) {
                                stringResource(Res.string.enroll_tv_revoking)
                            } else {
                                stringResource(Res.string.enroll_tv_revoke)
                            },
                        )
                    }
                }
            }
        }
    }
}

/**
 * bsm hands back an ISO-8601 timestamp. Only the date is shown, and it is taken as a substring
 * rather than parsed: this screen has no date library in commonMain, and inventing a formatter to
 * render "2026-10-12" from a string that already starts with it would be worse than the substring.
 * A TV that has never connected is said in words, because that is a different situation from a date.
 */
@Composable
private fun lastSeenLabel(lastSeen: String?): String {
    val date = lastSeen?.trim()?.substringBefore('T')?.takeIf { it.isNotEmpty() }
    return if (date == null) {
        stringResource(Res.string.enroll_tv_last_seen_never)
    } else {
        stringResource(Res.string.enroll_tv_last_seen, date)
    }
}

@Composable
private fun NeedsAccountCard() {
    NuvioSurfaceCard {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                stringResource(Res.string.enroll_tv_need_account_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                stringResource(Res.string.enroll_tv_need_account_description),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
