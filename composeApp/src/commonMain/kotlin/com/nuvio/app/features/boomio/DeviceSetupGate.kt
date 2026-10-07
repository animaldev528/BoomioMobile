package com.nuvio.app.features.boomio

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.ui.nuvio
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.device_setup_description
import nuvio.composeapp.generated.resources.device_setup_skip
import nuvio.composeapp.generated.resources.device_setup_title
import org.jetbrains.compose.resources.stringResource

/**
 * The first-run setup step: pair this device with its server, **before** the sign-in screen.
 *
 * **Why this is not in Settings, where the same machine already lives.** Pairing is not a
 * preference — it is the thing that has to happen before anything else can. A device that has
 * never paired has no address to reach the server on, so it cannot sign in *first*: there is
 * nothing to sign in *to*. Leaving the code in a Settings destination meant a fresh install could
 * only ever reach it by first getting past a login screen that the pairing was a precondition for.
 * That is the loop [BoomioSessionRepository.startLink] was freed from the `Authenticated` check to
 * break, and this is the surface that uses it.
 *
 * ⚠️ **It renders the same machine, not a copy of it.** [UnlinkedCard] owns the states — idle,
 * starting, awaiting approval, failed — and this passes the words that fit a setup step rather
 * than a TV remote. Only the copy differs; a state added there appears here for free.
 *
 * ⚠️ **Skipping is deliberately allowed, and it is not a nicety.** Pairing is a property of *this
 * deployment*, not of Nuvio: someone pointing the app at an ordinary server has nothing to pair
 * with, and a gate with no way past it would lock them out of the app entirely. Skip is the escape
 * hatch that keeps a boomio-specific onboarding step from becoming a requirement for everyone.
 *
 * Success needs no callback: the link finishes by writing a session, the gate observes that
 * session, and it moves on by itself. `onSkip` exists only for the case where there is nothing to
 * wait for.
 */
@Composable
internal fun DeviceSetupGate(
    onSkip: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val linkState by BoomioSessionRepository.linkState.collectAsStateWithLifecycle()

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.nuvio.colors.background)
            // Scrollable because the awaiting-approval state grows — a code, a URI and a hint —
            // and this is the first screen a fresh install shows, so it is also the one most
            // likely to meet a small screen and a large font scale.
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // The card owns the headline and the explainer; passing the setup wording in keeps one
        // copy of each rather than a centred pair above a card that repeats them.
        UnlinkedCard(
            linkState = linkState,
            title = stringResource(Res.string.device_setup_title),
            description = stringResource(Res.string.device_setup_description),
        )

        Spacer(Modifier.height(16.dp))
        TextButton(onClick = onSkip) {
            Text(stringResource(Res.string.device_setup_skip))
        }
    }
}
