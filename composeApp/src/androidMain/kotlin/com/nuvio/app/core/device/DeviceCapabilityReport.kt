package com.nuvio.app.core.device

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Payload for `POST {bsm}/api/prov/devices/capability-report` — the `device-caps/1` envelope.
 *
 * ⚠️ **These names are a wire contract, not a local DTO.** bsm stores the body verbatim under
 * `devices.capabilities` and both the dashboard and `services/device-caps.js` read it by these
 * exact keys, so renaming a field here renames it in the fleet view. The shape is shared with the
 * TV client (`com.nuvio.tv.data.remote.dto.DeviceCapabilityReportDto`) on purpose: one envelope,
 * two reporters.
 *
 * The keys each layer actually reads, so a refactor knows what it would break:
 *  - `device.installId` — the row key (bsm's `POST /devices/capability-report` 400s without it).
 *  - `device.hwFingerprint` — what makes a reinstall rejoin its existing row instead of minting one.
 *  - `decode.{avc,hevc,av1,vp9}.hw` — `device-caps.js hwCodecList` ⇒ the legacy `allowed_codecs`
 *    mirror. Only `hw == true` counts; a missing block reads as "reported nothing".
 *  - `effective.maxResolution` — `resolutionLabel()` ⇒ the legacy `max_resolution` mirror. "WxH",
 *    e.g. `1920x1080`; the dashboard renders the label it maps to.
 *  - `display.sinkHdrTypes` + `effective.hdrUsable` + `decode.dolbyVision.decoderPresent` — unioned
 *    into the discovered HDR set.
 *  - `network.estimated_bandwidth_mbps` — the dashboard's "Mbps" chip.
 */
@Serializable
data class DeviceCapabilityReport(
    val schema: String = "device-caps/1",
    /** ISO-8601 UTC instant the report was assembled, e.g. `2026-09-03T10:15:30Z`. */
    val reportedAt: String = "",
    val device: DeviceInfo = DeviceInfo(),
    val app: AppInfo = AppInfo(),
    val display: DisplayCapabilities = DisplayCapabilities(),
    val decode: DecodeCapabilities = DecodeCapabilities(),
    val effective: EffectiveCapabilities = EffectiveCapabilities(),
    /** Optional — omitted entirely until there is a measured value or an active network. */
    val network: NetworkCapabilities? = null,
)

/** Who this is, in bsm's fleet view. */
@Serializable
data class DeviceInfo(
    /**
     * Install-scoped id — the app's [com.nuvio.app.core.sync.SyncClientIdentity] client id
     * (`nuvio-mobile-<32>`).
     *
     * ⚠️ This is deliberately the **same string the overlay enrollment used as this device's
     * `device_id`**, and that is the whole point of reporting it: bsf identifies a device from its
     * *certificate*, and the edge's cert-index maps that certificate back to exactly this id. A row
     * keyed on anything else would appear on the Devices page and then never bind to the phone's
     * playback, because bsf's caps lookup would miss it.
     */
    val installId: String = "",
    /**
     * Stable per-device identity, so a reinstall rejoins its existing row instead of minting a new
     * one. A SHA-256 digest of `ANDROID_ID` (salted), never the raw value: bsm only ever compares it
     * for equality, so the hardware-linked id itself never reaches the fleet view.
     *
     * Survives a data clear, an uninstall/reinstall and an app update; does NOT survive a factory
     * reset. Empty means "this device cannot be fingerprinted" and bsm falls back to [installId].
     */
    val hwFingerprint: String = "",
    val manufacturer: String = "",
    val model: String = "",
    /** Silicon vendor, e.g. "Qualcomm". Null below API 31 (`Build.SOC_*` is not exposed earlier). */
    val socManufacturer: String? = null,
    /** Silicon part, e.g. "SM8450". Same null caveat as [socManufacturer]. */
    val socModel: String? = null,
    /** Retail/marketing name when the build advertises one. Best-effort and often null. */
    val marketName: String? = null,
    val sdkInt: Int = 0,
    val androidRelease: String = "",
    val abis: List<String> = emptyList(),
)

/** Which build produced this report. */
@Serializable
data class AppInfo(
    val applicationId: String = "",
    val versionName: String = "",
    val versionCode: Int = 0,
    val flavor: String = "",
    val debug: Boolean = false,
)

/**
 * What this device currently renders to. On a phone this is the panel (its own screen) rather than
 * an HDMI sink, but the fields and their meaning are unchanged — `sinkWidth/Height` is the current
 * display mode and `maxSupportedRefreshHz` is the panel's ceiling.
 */
@Serializable
data class DisplayCapabilities(
    /** Current display-mode resolution. 0 when unknown. */
    val sinkWidth: Int = 0,
    val sinkHeight: Int = 0,
    /** Refresh rate of the *current* display mode. */
    val maxRefreshHz: Int = 0,
    /** Highest refresh across every mode the display advertises. 0 when unknown. */
    val maxSupportedRefreshHz: Int = 0,
    /** True when the platform reports a wide-colour-gamut path. Null below API 26 or when unknown. */
    val wideColorGamut: Boolean? = null,
    /** Tokens in fixed order: "dv", "hdr10", "hdr10plus", "hlg". */
    val sinkHdrTypes: List<String> = emptyList(),
    /** False when the platform returned null — treat [sinkHdrTypes] as unverified. */
    val hdrCapsKnown: Boolean = false,
)

/** Per-codec hardware/software decode story. A codec key is null only when no decoder at all exists. */
@Serializable
data class DecodeCapabilities(
    val avc: CodecCapability? = null,
    val hevc: CodecCapability? = null,
    val av1: CodecCapability? = null,
    val vp9: CodecCapability? = null,
    val dolbyVision: DolbyVisionCapability? = null,
)

@Serializable
data class CodecCapability(
    val hw: Boolean = false,
    val sw: Boolean = false,
    /**
     * Widest/tallest single dimension any hardware decoder advertises (an upper ceiling, not a
     * guaranteed combination). 0 when the platform exposes no capability range.
     */
    val maxWidth: Int = 0,
    val maxHeight: Int = 0,
    /** HEVC-only today: "main10", "hdr10", "hdr10plus". */
    val hdrProfiles: List<String> = emptyList(),
)

@Serializable
data class DolbyVisionCapability(
    /** Advertised DV profiles as "dvhe.07.06" (P7), "dvhe.08.06" (P8), "dvhe.05.06" (P5). */
    val profiles: List<String> = emptyList(),
    /**
     * True when a *hardware* DV decoder exists, even if it advertises no profile. Software DV
     * decoders are deliberately not counted — they could never present a real DV stream.
     */
    val decoderPresent: Boolean = false,
)

/** decode ∩ display — the ceiling that matters for sending streams to this device. */
@Serializable
data class EffectiveCapabilities(
    /** "WxH" the display runs at, capped to the widest hardware decode ceiling; "" when unknown. */
    val maxResolution: String = "",
    /** HDR tokens the display advertises AND the box can decode, fixed order: dv, hdr10, hdr10plus, hlg. */
    val hdrUsable: List<String> = emptyList(),
    /**
     * The **decode** ceiling in the media plane's own vocabulary ("1080p"/"2160p"); "" when no
     * hardware decoder advertises a range.
     *
     * ⚠️ Deliberately **not** clamped to the display, unlike [maxResolution]. A screen limits what
     * the device can *show*, not what it can *decode*: a 1080p panel whose decoders take 2160 plays
     * a 4K release downscaled, and clamping here would strip the only release of a title that exists
     * in 4K. What must never be sent is a resolution no decoder advertises.
     */
    val decodeMaxResolution: String = "",
    /**
     * Per-codec decode ceilings as `avc:1080p,hevc:2160p,other:1080p` — the wire form of bsf's
     * `codecCapsMaxRes` query parameter. The scalar above cannot express the case that actually
     * happens (a 4K panel whose HEVC decoder takes 2160 while its AVC decoder stops at 1080).
     * `other` is bsf's reserved catch-all, set to the weakest decoder.
     */
    val decodeMaxResByCodec: String = "",
)

/**
 * The active link this device reports through.
 *
 * ⚠️ `estimated_bandwidth_mbps` is a **measured** value and is deliberately NOT the same lane as
 * `devices.bandwidth_cap_mbps` (operator policy): `services/device-caps.js` resolves limits from the
 * override and the legacy column only, so a measurement can never silently become a cap. The other
 * fields are filled only when the OS exposes them cheaply, and the whole object is omitted when
 * there is neither a measurement nor an active network.
 */
@Serializable
data class NetworkCapabilities(
    /** Transport token: "wifi", "ethernet", "cellular", "vpn", or "other". */
    val type: String? = null,
    /** Last download measurement, Mbps, 1 decimal. Null before the first successful measure. */
    @SerialName("estimated_bandwidth_mbps")
    val estimatedBandwidthMbps: Double? = null,
    /** Wi-Fi band in GHz (2.4/5/6). Null off Wi-Fi or when the OS hides it. */
    @SerialName("frequency_ghz")
    val frequencyGhz: Double? = null,
    /** Wi-Fi RSSI in dBm. Null off Wi-Fi. */
    @SerialName("signal_strength_dbm")
    val signalStrengthDbm: Int? = null,
    /** True when the active network is metered. Null when unknown. */
    @SerialName("is_metered")
    val isMetered: Boolean? = null,
)
