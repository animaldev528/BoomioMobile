package io.github.aivanyuk.airkast.session

import io.github.aivanyuk.airkast.RemoteCommand

/**
 * The button a `sendMediaRemoteCommand` message's `value` names, or null for one that is not a
 * playback button, such as the LG CX's BACK (`pbpr`, `pbal`) and volume (`dvlc`). The Samsung
 * HW-Q990B (366.0) sends `plps`, `nitm` and `pitm`; owntone's sender reads `play`, `paus`, `nitm`
 * and `pitm`.
 */
internal fun remoteCommand(value: Any?): RemoteCommand? =
    when (value) {
        "play" -> RemoteCommand.Play
        "paus" -> RemoteCommand.Pause
        "plps" -> RemoteCommand.PlayPause
        "nitm" -> RemoteCommand.Next
        "pitm" -> RemoteCommand.Previous
        else -> null
    }
