package com.nuvio.app.core.device

import android.content.Context
import android.hardware.display.DisplayManager
import android.media.MediaCodecInfo
import android.media.MediaCodecInfo.CodecProfileLevel
import android.media.MediaCodecList
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.view.Display
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Assembles a `device-caps/1` report for bsm's fleet view.
 *
 * A port of the TV client's `com.nuvio.tv.core.device.DeviceCapabilityProbe` — the probing logic is
 * deliberately identical, because the two clients must agree about what a capability *means* for
 * bsm to render one fleet view from both. What differs is only the framing: a phone's "sink" is its
 * own panel rather than an HDMI display, and this tree passes the app/network blocks in rather than
 * reading them from a TV `BuildConfig`.
 *
 * Three surfaces are probed, then intersected:
 *  - **device**: `Build.MODEL` plus, where the platform exposes them, SoC and market name.
 *  - **decode**: `MediaCodecList` — which of AVC/HEVC/AV1/VP9/DV decode in hardware, their
 *    resolution ceiling, and the HDR profile flags the HEVC/DV decoders advertise.
 *  - **display**: `DisplayManager` current mode + advertised modes + HDR capabilities.
 *
 * Read as a self-describing report, never as an answer key: a null codec means *absent*, and an
 * empty profile list means *unadvertised*, which is not the same as unsupported.
 *
 * ⚠️ Call off the main thread — `MediaCodecList` enumeration and `VideoCapabilities` queries are
 * blocking platform calls.
 */
internal object DeviceCapabilityProbe {

    private const val TAG = "DeviceCapabilityProbe"
    private const val MIME_AVC = "video/avc"
    private const val MIME_HEVC = "video/hevc"
    private const val MIME_AV1 = "video/av01"
    private const val MIME_VP9 = "video/x-vnd.on2.vp9"
    private const val MIME_DV = "video/dolby-vision"

    /** build.prop key some vendor builds set to the retail name. */
    private const val PROP_MARKET_NAME = "ro.product.marketname"

    /** Salt for [hardwareFingerprint]. Version-tagged so the scheme can rotate. */
    private const val FP_SALT = "boomio-devcap-fp-v1|"

    /** The Android 2.2 build that handed every device the SAME ANDROID_ID. */
    private const val LEGACY_ANDROID_ID_COLLISION = "9774d56d682e549c"

    private val ISO_UTC = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
        timeZone = java.util.TimeZone.getTimeZone("UTC")
    }

    /** ISO-8601 UTC instant, the envelope's `reportedAt`. */
    fun nowIsoUtc(): String = synchronized(ISO_UTC) { ISO_UTC.format(Date()) }

    /**
     * Best-effort read of a build.prop property through the hidden `SystemProperties` API
     * (reflection). `runCatching` absorbs hidden-API policy differences across Android versions — a
     * build that refuses the call yields null, never a crash.
     */
    private fun readSystemProperty(name: String): String? = runCatching {
        val get = Class.forName("android.os.SystemProperties").getMethod("get", String::class.java)
        (get.invoke(null, name) as? String)?.takeIf { it.isNotBlank() }
    }.getOrNull()

    /**
     * A per-device fingerprint that survives a reinstall, so clearing the app and re-enrolling
     * rejoins the existing bsm row instead of duplicating it.
     *
     * ⚠️ `ANDROID_ID` deliberately, and NOT the usual suspects:
     *  - `Build.FINGERPRINT` / `Build.MODEL` are BUILD strings — identical on every unit of the same
     *    model + firmware, so they name a model, not a device.
     *  - `Build.SERIAL` reads "unknown" to an unprivileged app on Android 10+; a MAC needs restricted
     *    permissions and is randomised or stubbed on modern Android.
     *  - `ANDROID_ID` is scoped to (app signing key, user, device), which is what makes it survive an
     *    uninstall/reinstall OF THIS APP — the path that otherwise mints one fleet row per install.
     *
     * SHA-256'd with a version-tagged salt: bsm only compares it for equality, so the raw
     * hardware-linked value never reaches the fleet view. Returns "" when the platform will not
     * supply one, and bsm then falls back to the install id.
     */
    @Suppress("HardwareIds")
    private fun hardwareFingerprint(context: Context): String = try {
        val raw = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
        if (raw.isNullOrBlank() || raw == LEGACY_ANDROID_ID_COLLISION) {
            ""
        } else {
            MessageDigest.getInstance("SHA-256")
                .digest((FP_SALT + raw).toByteArray(Charsets.UTF_8))
                .joinToString("") { b -> (b.toInt() and 0xff).toString(16).padStart(2, '0') }
                .take(32)
        }
    } catch (t: Throwable) {
        Log.w(TAG, "ANDROID_ID unavailable — a reinstall will not rejoin its existing row", t)
        ""
    }

    /**
     * [hardwareFingerprint], for callers outside a capability report — the pairing request.
     *
     * ⚠️ **It has to be this same value.** bsm looks the device's row up by fingerprint *first*
     * when a device enrols, so a pairing request that carried anything else (an install id, say)
     * could never match the row this device's capability reports write to — and every reinstall
     * then minted one more row for the same box. That is the duplicate-rows defect this closes.
     *
     * Returns "" when the platform will not supply an ANDROID_ID; callers omit the field then,
     * which leaves bsm falling back to the install id exactly as it did before.
     */
    fun fingerprint(context: Context): String = hardwareFingerprint(context)

    fun buildReport(
        context: Context,
        installId: String,
        app: AppInfo,
        network: NetworkCapabilities?,
    ): DeviceCapabilityReport {
        val sink = probeSink(context)
        val decode = probeDecode()
        return DeviceCapabilityReport(
            schema = "device-caps/1",
            reportedAt = nowIsoUtc(),
            device = DeviceInfo(
                installId = installId,
                hwFingerprint = hardwareFingerprint(context),
                manufacturer = Build.MANUFACTURER,
                model = Build.MODEL,
                // Build.SOC_* only exists as a real field on API 31+; the SDK gate keeps the
                // getstatic (and its NoSuchFieldError on older devices) from ever executing.
                socManufacturer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    Build.SOC_MANUFACTURER.ifBlank { null }
                } else {
                    null
                },
                socModel = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    Build.SOC_MODEL.ifBlank { null }
                } else {
                    null
                },
                marketName = readSystemProperty(PROP_MARKET_NAME),
                sdkInt = Build.VERSION.SDK_INT,
                androidRelease = Build.VERSION.RELEASE,
                abis = Build.SUPPORTED_ABIS.toList(),
            ),
            app = app,
            display = DisplayCapabilities(
                sinkWidth = sink.width,
                sinkHeight = sink.height,
                maxRefreshHz = sink.refreshHz,
                maxSupportedRefreshHz = sink.maxSupportedRefreshHz,
                wideColorGamut = sink.wideColorGamut,
                sinkHdrTypes = sink.hdrTypes,
                hdrCapsKnown = sink.hdrCapsKnown,
            ),
            decode = decode,
            effective = computeEffective(sink, decode),
            network = network,
        )
    }

    // ---- Display ----

    private class SinkSnapshot(
        val width: Int,
        val height: Int,
        val refreshHz: Int,
        /** Highest refresh across the display's full advertised mode set, vs [refreshHz] = current mode. */
        val maxSupportedRefreshHz: Int,
        /** Null below API 26 / when the platform doesn't say. */
        val wideColorGamut: Boolean?,
        val hdrTypes: List<String>,
        val hdrCapsKnown: Boolean,
    )

    private fun probeSink(context: Context): SinkSnapshot = runCatching {
        val dm = context.getSystemService(DisplayManager::class.java)
        val display = dm?.getDisplay(Display.DEFAULT_DISPLAY)
        if (display == null) {
            SinkSnapshot(0, 0, 0, 0, null, emptyList(), false)
        } else {
            val mode = display.mode
            val maxSupportedRefreshHz = runCatching {
                var best = mode.refreshRate
                for (m in display.supportedModes) {
                    if (m.refreshRate > best) best = m.refreshRate
                }
                best.roundToInt()
            }.getOrElse { mode.refreshRate.roundToInt() }
            val wideColorGamut =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) display.isWideColorGamut else null
            val hdrTypes: IntArray? = runCatching { display.hdrCapabilities?.supportedHdrTypes }.getOrNull()
            val names = hdrTypes
                ?.toList()
                ?.mapNotNull { hdrTypeToken(it) }
                // Fixed report order: dv, hdr10, hdr10plus, hlg.
                ?.distinct()
                ?.sortedBy { HDR_ORDER.indexOf(it) }
                ?: emptyList()
            SinkSnapshot(
                width = mode.physicalWidth,
                height = mode.physicalHeight,
                refreshHz = mode.refreshRate.roundToInt(),
                maxSupportedRefreshHz = maxSupportedRefreshHz,
                wideColorGamut = wideColorGamut,
                hdrTypes = names,
                hdrCapsKnown = hdrTypes != null,
            )
        }
    }.getOrElse { e ->
        Log.w(TAG, "Display probe failed; reporting unknown display", e)
        SinkSnapshot(0, 0, 0, 0, null, emptyList(), false)
    }

    private val HDR_ORDER = listOf("dv", "hdr10", "hdr10plus", "hlg")

    private fun hdrTypeToken(type: Int): String? = when (type) {
        Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION -> "dv"
        Display.HdrCapabilities.HDR_TYPE_HDR10 -> "hdr10"
        Display.HdrCapabilities.HDR_TYPE_HDR10_PLUS -> "hdr10plus"
        Display.HdrCapabilities.HDR_TYPE_HLG -> "hlg"
        else -> null
    }

    // ---- Decode (MediaCodecList) ----

    /** Accumulator for one codec family across every matching decoder. */
    private class CodecScan {
        var hw = false
        var sw = false
        var maxWidth = 0
        var maxHeight = 0
        val hdrProfiles = linkedSetOf<String>()

        fun toDto() = CodecCapability(
            hw = hw,
            sw = sw,
            maxWidth = maxWidth,
            maxHeight = maxHeight,
            hdrProfiles = hdrProfiles.toList(),
        )
    }

    private class DvScan {
        var decoderPresent = false
        val profiles = linkedSetOf<String>()
    }

    private fun probeDecode(): DecodeCapabilities {
        // All-null DTO on enumeration failure (never throws outward; each step is guarded) —
        // null codec = unknown rather than a plausible "present but unsupported".
        val list = try {
            MediaCodecList(MediaCodecList.REGULAR_CODECS)
        } catch (e: Throwable) {
            Log.w(TAG, "MediaCodecList unavailable", e)
            return DecodeCapabilities()
        }

        val avc = CodecScan()
        val hevc = CodecScan()
        val av1 = CodecScan()
        val vp9 = CodecScan()
        val dv = DvScan()

        for (info in list.codecInfos) {
            if (info.isEncoder) continue
            for (mime in info.supportedTypes) {
                val isHevc = mime.equals(MIME_HEVC, ignoreCase = true)
                val isDv = mime.equals(MIME_DV, ignoreCase = true)
                val scan = when {
                    isHevc -> hevc
                    mime.equals(MIME_AVC, ignoreCase = true) -> avc
                    mime.equals(MIME_AV1, ignoreCase = true) -> av1
                    mime.equals(MIME_VP9, ignoreCase = true) -> vp9
                    else -> null
                }
                if (scan == null && !isDv) continue

                val caps = runCatching { info.getCapabilitiesForType(mime) }.getOrNull() ?: continue
                val isHw = isHardwareAccelerated(info)
                val isSw = isSoftwareOnly(info)

                if (isDv) {
                    // Only a hardware DV decoder makes DV usable; a software one isn't worth
                    // reporting as "present" (a 4K DV stream would never survive sw decode).
                    if (isHw) {
                        dv.decoderPresent = true
                        for (pl in caps.profileLevels.orEmpty()) {
                            dvToken(pl.profile)?.let { dv.profiles.add(it) }
                        }
                    }
                } else if (scan != null) {
                    if (isHw) scan.hw = true
                    if (isSw) scan.sw = true
                    // Resolution ceiling comes from hardware decoders only — a software codec is
                    // never the reason we'd cap a stream.
                    if (isHw) {
                        observeCeiling(scan, caps)
                        if (isHevc) {
                            for (pl in caps.profileLevels.orEmpty()) {
                                hevcToken(pl.profile)?.let { hevc.hdrProfiles.add(it) }
                            }
                        }
                    }
                }
            }
        }

        return DecodeCapabilities(
            avc = avc.toDto().takeIf { it.hw || it.sw },
            hevc = hevc.toDto().takeIf { it.hw || it.sw },
            av1 = av1.toDto().takeIf { it.hw || it.sw },
            vp9 = vp9.toDto().takeIf { it.hw || it.sw },
            dolbyVision = if (dv.decoderPresent || dv.profiles.isNotEmpty()) {
                DolbyVisionCapability(profiles = dv.profiles.toList(), decoderPresent = dv.decoderPresent)
            } else {
                null
            },
        )
    }

    /** Raises the widest/tallest single dimension any hardware decoder advertises. */
    private fun observeCeiling(scan: CodecScan, caps: MediaCodecInfo.CodecCapabilities) {
        val vc = caps.videoCapabilities ?: return
        runCatching {
            val w = vc.supportedWidths.upper
            if (w in 1..20000 && w > scan.maxWidth) scan.maxWidth = w
        }
        runCatching {
            val h = vc.supportedHeights.upper
            if (h in 1..20000 && h > scan.maxHeight) scan.maxHeight = h
        }
    }

    /** API 29+ uses platform flags; below that we infer from the component-name family. */
    private fun isHardwareAccelerated(info: MediaCodecInfo): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            info.isHardwareAccelerated
        } else {
            !info.name.startsWith("OMX.google.", ignoreCase = true)
        }

    private fun isSoftwareOnly(info: MediaCodecInfo): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            info.isSoftwareOnly
        } else {
            info.name.startsWith("OMX.google.", ignoreCase = true)
        }

    /** HEVC HDR profile flags a decoder advertises. Constants are inlined at compile. */
    private fun hevcToken(profile: Int): String? = when (profile) {
        CodecProfileLevel.HEVCProfileMain10 -> "main10"
        CodecProfileLevel.HEVCProfileMain10HDR10 -> "hdr10"
        CodecProfileLevel.HEVCProfileMain10HDR10Plus -> "hdr10plus"
        else -> null
    }

    /** DV profile constants (API 24+). Tokens describe the profile family, not a bitstream. */
    private fun dvToken(profile: Int): String? = when (profile) {
        CodecProfileLevel.DolbyVisionProfileDvheDtb -> "dvhe.07.06" // P7
        CodecProfileLevel.DolbyVisionProfileDvheStn -> "dvhe.05.06" // P5
        CodecProfileLevel.DolbyVisionProfileDvheSt -> "dvhe.08.06"  // P8
        else -> null
    }

    // ---- Effective (decode ∩ display) ----

    /** The height tiers the media plane speaks, tallest first. */
    private val DELIVERABLE_TIERS = intArrayOf(4320, 2160, 1440, 1080, 720, 480)

    private fun tier(height: Int): Int = DELIVERABLE_TIERS.firstOrNull { height >= it } ?: 0

    /** A tier back in bsf's own vocabulary — `maxres` reads "1080p"/"2160p", never "WxH". */
    private fun resToken(tier: Int): String = if (tier > 0) "${tier}p" else ""

    /**
     * The height tier this one hardware decoder can actually deliver, or 0 when it advertises no
     * usable range or has no hardware decoder at all.
     *
     * ⚠️ **The rectangle, not the two maxima.** `maxWidth` and `maxHeight` are independent upper
     * bounds — "the widest frame" and "the tallest frame" — so the largest 16:9 frame the pair
     * guarantees is `min(maxHeight, maxWidth * 9 / 16)` tall. Taking the two maxima separately is how
     * a probe reports a combination no single decoder advertised.
     */
    private fun decoderTier(cap: CodecCapability?): Int {
        if (cap == null || !cap.hw) return 0
        if (cap.maxWidth <= 0 || cap.maxHeight <= 0) return 0
        return tier(minOf(cap.maxHeight, cap.maxWidth * 9 / 16))
    }

    private fun computeEffective(
        sink: SinkSnapshot,
        decode: DecodeCapabilities,
    ): EffectiveCapabilities {
        // Per-codec decode ceilings, and only from codecs with a real hardware decoder — a software
        // decoder cannot present a stream, so it must never raise this ceiling.
        val byCodec = linkedMapOf<String, Int>()
        for ((family, cap) in listOf(
            "avc" to decode.avc,
            "hevc" to decode.hevc,
            "av1" to decode.av1,
            "vp9" to decode.vp9,
        )) {
            val t = decoderTier(cap)
            if (t > 0) byCodec[family] = t
        }
        val bestDecoderTier = byCodec.values.maxOrNull() ?: 0
        val weakestDecoderTier = byCodec.values.minOrNull() ?: 0

        // ⚠️ The media-plane ceiling is the DECODE ceiling, deliberately NOT clamped to the display.
        // A screen limits what the device can *show*, not what it can *decode*, so clamping here
        // would strip the only available release of a title that exists in 4K. What must not be sent
        // is a resolution no decoder advertises.
        val decodeMaxResolution = resToken(bestDecoderTier)
        val decodeMaxResByCodec = buildString {
            for ((family, t) in byCodec) {
                if (isNotEmpty()) append(',')
                append(family).append(':').append(resToken(t))
            }
            // The catch-all bsf's gate reserves for a stream whose codec word it cannot read
            // (`other`). Conservative on purpose: an unidentifiable 4K release goes to the weakest
            // decoder this device has, because that is the only one we can promise it to.
            if (weakestDecoderTier > 0) {
                if (isNotEmpty()) append(',')
                append("other:").append(resToken(weakestDecoderTier))
            }
        }

        // The report's own field keeps its "WxH" contract and its decode ∩ display meaning — bsm's
        // fleet view reads it — computed from one decoder's rectangle clamped to the display's mode.
        val sinkTier = tier(sink.height)
        val capTier = when {
            bestDecoderTier > 0 && sinkTier > 0 -> minOf(sinkTier, bestDecoderTier)
            else -> maxOf(sinkTier, bestDecoderTier)
        }
        val maxResolution = if (capTier > 0) "${capTier * 16 / 9}x$capTier" else ""

        val hevc = decode.hevc
        // HEVC Main10 is the base layer for HDR10/HLG. When the decoder advertises no profiles we
        // assume a HW HEVC decoder handles 10-bit (some platforms just don't list them); when it
        // does, require main10/hdr10.
        val hevcHandlesMain10 = hevc?.hw == true &&
            (hevc.hdrProfiles.isEmpty() || hevc.hdrProfiles.any { it == "main10" || it == "hdr10" })

        val usable = mutableListOf<String>()
        if (sink.hdrCapsKnown) {
            for (token in HDR_ORDER) {
                if (token !in sink.hdrTypes) continue
                val ok = when (token) {
                    "dv" -> decode.dolbyVision?.decoderPresent == true
                    "hdr10" -> hevcHandlesMain10
                    "hdr10plus" -> hevc?.hw == true && "hdr10plus" in hevc.hdrProfiles
                    "hlg" -> hevc?.hw == true
                    else -> false
                }
                if (ok) usable.add(token)
            }
        }

        return EffectiveCapabilities(
            maxResolution = maxResolution,
            hdrUsable = usable,
            decodeMaxResolution = decodeMaxResolution,
            decodeMaxResByCodec = decodeMaxResByCodec,
        )
    }
}
