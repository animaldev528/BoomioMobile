package com.nuvio.app.core.overlay

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.nuvio
import kotlinx.coroutines.launch
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.overlay_endpoint_found
import nuvio.composeapp.generated.resources.overlay_endpoint_needs_manual
import nuvio.composeapp.generated.resources.overlay_endpoint_searching
import nuvio.composeapp.generated.resources.overlay_endpoint_unavailable
import nuvio.composeapp.generated.resources.overlay_manual_hint
import nuvio.composeapp.generated.resources.overlay_manual_label
import nuvio.composeapp.generated.resources.overlay_manual_submit
import org.jetbrains.compose.resources.stringResource

/**
 * One line describing what the endpoint ladder found, or nothing at all — and, when the
 * ladder is out of rungs, the field that answers it.
 *
 * **Why this exists next to [LocalServerStatusRow] rather than inside it.** The two answer
 * different questions and can disagree. That row says *where boomio traffic is going*; this
 * one says *how the app went looking for the tunnel*. A device whose ladder has exhausted
 * every rung has no server to report at all — `LocalServerState` is `Idle`, the other row
 * renders nothing — so folding this into it would make the one state that needs an action
 * the one state that is invisible.
 *
 * ⚠️ **[OverlayEndpointStatus.NeedsManual] renders unconditionally, and the rest do not.**
 * That asymmetry is architecture §10.7, not a style choice: the design has **no LAN
 * fallback**, so a discovery failure must fail *visibly* and carry the action the user can
 * take. Hiding it behind a "show diagnostics" flag would leave a user who is off the LAN,
 * with a dead tunnel and working Wi-Fi, looking at an app that simply does not connect and
 * says nothing about why. Every other state is either progress or noise, so those stay
 * behind [showUnavailable].
 *
 * ⚠️ **The field is rendered by the same branch that renders the sentence, and that is the
 * point.** [OverlayEndpointStatus.NeedsManual] is the only state with an action attached, and
 * until now the row *named* that action and offered nothing to perform it with — a dead end
 * that read as a diagnosis. Tying the two together means a state cannot arrive saying "type
 * an address" with no address to type into. [OverlayEndpointStatus.Found] shows no field
 * because there is nothing to ask for; a typed address that worked has already been adopted.
 */
@Composable
internal fun OverlayEndpointStatusRow(
    modifier: Modifier = Modifier,
    showUnavailable: Boolean = false,
) {
    val current by OverlayEndpointState.status.collectAsState()
    val tokens = MaterialTheme.nuvio

    val text: String? = when (val status = current) {
        // Not started, or the seam is switched off — the same silence the LAN row keeps.
        OverlayEndpointStatus.Idle -> null

        OverlayEndpointStatus.Searching ->
            if (showUnavailable) stringResource(Res.string.overlay_endpoint_searching) else null

        // ⚠️ Silent here on purpose. A rung having found an endpoint is not the same as the
        // app using it — the tunnel still has to come up and answer — and when it does,
        // [LocalServerStatusRow] already reports the address the traffic is actually taking.
        // Saying it twice would give the user two lines that can contradict each other.
        is OverlayEndpointStatus.Found ->
            if (showUnavailable) stringResource(Res.string.overlay_endpoint_found, status.endpoint.authority) else null

        // See the doc: never gated.
        is OverlayEndpointStatus.NeedsManual ->
            stringResource(Res.string.overlay_endpoint_needs_manual, status.reason)

        is OverlayEndpointStatus.Unavailable ->
            if (showUnavailable) stringResource(Res.string.overlay_endpoint_unavailable, status.reason) else null
    }

    if (text == null) return

    Column(modifier = modifier) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            // Amber for the actionable failure, muted for everything else — so the one line the
            // user can *do* something about does not look like the three they cannot.
            color = if (current is OverlayEndpointStatus.NeedsManual) tokens.colors.warning else tokens.colors.textMuted,
        )

        if (current is OverlayEndpointStatus.NeedsManual) {
            Spacer(Modifier.height(8.dp))
            ManualEndpointField()
        }
    }
}

/**
 * Rung 3's input: a server address a person types.
 *
 * ⚠️ **One field, not two — the server's public key is deliberately not asked for.** It is a
 * base64 X25519 key published by the mDNS advert and the DuckDNS TXT record, and a person
 * cannot read it off anything they own. Rung 3 already falls back to the key this process
 * holds from enrollment, and when even that is absent the row's own reason says so. A key
 * input would be a field nobody could fill in correctly, which is worse than no field.
 *
 * ⚠️ **The draft outlives the status it was typed under.** Submitting republishes through the
 * ladder — `Searching`, then `Found` or a fresh `NeedsManual` — so this composable is
 * recomposed and possibly left and re-entered while the user is still looking at it. It is
 * `rememberSaveable` for that reason, and it is deliberately *not* cleared on submit: a
 * rejected address is one the user wants to correct, and clearing the box would make them
 * retype it from memory to find their own typo.
 */
@Composable
private fun ManualEndpointField() {
    var draft by rememberSaveable { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val trimmed = draft.trim()

    // ⚠️ The handler is registered in `MainActivity.onCreate`, ahead of the first composition,
    // so in the app this is never null — `NeedsManual` can only be published by the ladder that
    // also registers it. It is read rather than observed because a plain `var` has no snapshot
    // invalidation and none is wanted: there is one ladder per process and it exists for the
    // whole of it. A null handler is a test or a non-Android composition, and a disabled button
    // says so rather than failing silently on tap.
    val wired = OverlayEndpointState.manualSubmit != null
    val enabled = wired && trimmed.isNotEmpty()

    fun submit() {
        if (!enabled) return
        scope.launch { OverlayEndpointState.submitManual(trimmed) }
    }

    OutlinedTextField(
        value = draft,
        onValueChange = { draft = it },
        enabled = wired,
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        label = { Text(stringResource(Res.string.overlay_manual_label)) },
        placeholder = { Text(stringResource(Res.string.overlay_manual_hint)) },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
        keyboardActions = KeyboardActions(onGo = { submit() }),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.75f),
            unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.42f),
            focusedContainerColor = MaterialTheme.colorScheme.surface,
            unfocusedContainerColor = MaterialTheme.colorScheme.surface,
            disabledContainerColor = MaterialTheme.colorScheme.surface,
        ),
    )

    Spacer(Modifier.height(8.dp))

    Row(modifier = Modifier.fillMaxWidth()) {
        Button(onClick = { submit() }, enabled = enabled) {
            Text(stringResource(Res.string.overlay_manual_submit))
        }
    }
}
