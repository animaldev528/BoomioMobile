package com.nuvio.app.features.boomio

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.overlay.TvAdvertAvailability
import com.nuvio.app.core.overlay.TvAdvertResult
import com.nuvio.app.core.overlay.TvServerAdvert
import com.nuvio.app.core.ui.NuvioSurfaceCard
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.tv_advert_already_on_lan
import nuvio.composeapp.generated.resources.tv_advert_failed
import nuvio.composeapp.generated.resources.tv_advert_intro
import nuvio.composeapp.generated.resources.tv_advert_not_configured
import nuvio.composeapp.generated.resources.tv_advert_sharing
import nuvio.composeapp.generated.resources.tv_advert_start
import nuvio.composeapp.generated.resources.tv_advert_stop
import nuvio.composeapp.generated.resources.tv_advert_title
import org.jetbrains.compose.resources.stringResource

/** How long a share lasts before it turns itself off. */
private const val SHARE_WINDOW_MS = 5 * 60 * 1000L

/**
 * Offers to make this phone publish the server it is set up on, for a TV that cannot find it.
 *
 * ⚠️ **The advert is tied to this card's lifetime.** Leaving the screen calls
 * [TvServerAdvert.stop] from [DisposableEffect], and the five-minute timer is a second bound in
 * case the screen is simply left open. Nothing is persisted and nothing runs in the background:
 * a phone must not broadcast the home server's public address and keys on every Wi-Fi it joins.
 *
 * ⚠️ **This is not authorization.** The TV still shows its code and the admin still approves it
 * through the flow these very screens already perform. The advert only gets an otherwise
 * unreachable TV to the screen that shows the code.
 */
@Composable
internal fun TvServerAdvertCard(modifier: Modifier = Modifier) {
    val availability = remember { TvServerAdvert.availability() }
    // iOS reports Unsupported: there is no affordance to show, and an explanation would only
    // invite a question the platform cannot answer in this build.
    if (availability == TvAdvertAvailability.Unsupported) return

    val scope = rememberCoroutineScope()
    var result by remember { mutableStateOf<TvAdvertResult?>(null) }
    val sharing = result is TvAdvertResult.Started

    // The advert must never outlive the screen, whatever route the person takes away from it.
    DisposableEffect(Unit) {
        onDispose { TvServerAdvert.stop() }
    }

    LaunchedEffect(sharing) {
        if (!sharing) return@LaunchedEffect
        delay(SHARE_WINDOW_MS)
        TvServerAdvert.stop()
        result = null
    }

    val notice = when (result) {
        is TvAdvertResult.Started -> Res.string.tv_advert_sharing
        is TvAdvertResult.Failed -> Res.string.tv_advert_failed
        is TvAdvertResult.NotConfigured -> Res.string.tv_advert_not_configured
        is TvAdvertResult.AlreadyOnServerLan -> Res.string.tv_advert_already_on_lan
        else -> when (availability) {
            TvAdvertAvailability.NotConfigured -> Res.string.tv_advert_not_configured
            TvAdvertAvailability.AlreadyOnServerLan -> Res.string.tv_advert_already_on_lan
            else -> Res.string.tv_advert_intro
        }
    }

    NuvioSurfaceCard(modifier = modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = stringResource(Res.string.tv_advert_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = stringResource(notice),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            when {
                sharing -> TextButton(onClick = {
                    TvServerAdvert.stop()
                    result = null
                }) {
                    Text(stringResource(Res.string.tv_advert_stop))
                }

                availability == TvAdvertAvailability.Ready -> Button(onClick = {
                    scope.launch { result = TvServerAdvert.start() }
                }) {
                    Text(stringResource(Res.string.tv_advert_start))
                }
            }
        }
    }
}
