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
import androidx.compose.runtime.Composable
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
import nuvio.composeapp.generated.resources.add_tv_code_label
import nuvio.composeapp.generated.resources.add_tv_intro
import nuvio.composeapp.generated.resources.add_tv_need_account_description
import nuvio.composeapp.generated.resources.add_tv_need_account_title
import nuvio.composeapp.generated.resources.add_tv_signing_in
import nuvio.composeapp.generated.resources.add_tv_submit
import nuvio.composeapp.generated.resources.add_tv_success
import nuvio.composeapp.generated.resources.compose_settings_page_add_tv
import org.jetbrains.compose.resources.stringResource

/**
 * Signs a TV in by approving the code it is displaying.
 *
 * The phone is the approving party here, not the browser: `approve_tv_login_session`
 * is keyed off `auth.uid()`, so this works from anywhere the app can reach the
 * backend — no LAN, no QR, no WebView.
 */
@Composable
fun AddTvScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val authState by AuthRepository.state.collectAsStateWithLifecycle()
    // The app-wide test for "has a real Nuvio account" — a guest session is
    // AuthState.Authenticated too, and cannot approve a code.
    val signedIn = authState.isLoggedIn && !authState.isAnonymous

    NuvioScreen(modifier = modifier) {
        stickyHeader {
            NuvioScreenHeader(
                title = stringResource(Res.string.compose_settings_page_add_tv),
                onBack = onBack,
            )
        }
        item {
            if (signedIn) {
                EnterCodeCard()
            } else {
                NeedsAccountCard()
            }
        }
    }
}

@Composable
private fun EnterCodeCard() {
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current

    var code by remember { mutableStateOf("") }
    var submitting by remember { mutableStateOf(false) }
    var refusal by remember { mutableStateOf<String?>(null) }
    var approved by remember { mutableStateOf(false) }

    val complete = code.length == TvLoginApprovalRepository.CODE_LENGTH

    fun submit() {
        if (submitting || !complete) return
        keyboard?.hide()
        refusal = null
        approved = false
        submitting = true
        scope.launch {
            when (val result = TvLoginApprovalRepository.approve(code)) {
                is TvLoginApprovalResult.Approved -> {
                    approved = true
                    code = ""
                }
                is TvLoginApprovalResult.Refused -> refusal = result.message
            }
            submitting = false
        }
    }

    NuvioSurfaceCard {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                stringResource(Res.string.add_tv_intro),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = code,
                // Filtering on the way in means the field only ever holds what
                // the server accepts, so there is no separate "clean this up"
                // step at submit time. Mirrors the server's regexp_replace.
                onValueChange = {
                    code = normalizeDeviceLinkCode(it).take(TvLoginApprovalRepository.CODE_LENGTH)
                    refusal = null
                },
                label = { Text(stringResource(Res.string.add_tv_code_label)) },
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
                Text(stringResource(Res.string.add_tv_submit))
            }
            when {
                submitting -> Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                    )
                    Text(
                        stringResource(Res.string.add_tv_signing_in),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                approved -> Text(
                    stringResource(Res.string.add_tv_success),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                refusal != null -> Text(
                    refusal.orEmpty(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

/**
 * A guest on this device cannot approve anything: the code is bound to a Nuvio
 * account, and [AuthRepository.signInAnonymously] only ever minted a local UUID.
 * Saying so is the honest end of the flow — the app has no in-place "upgrade
 * from guest" path, so the instruction is to sign out and sign in.
 */
@Composable
private fun NeedsAccountCard() {
    NuvioSurfaceCard {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                stringResource(Res.string.add_tv_need_account_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                stringResource(Res.string.add_tv_need_account_description),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
