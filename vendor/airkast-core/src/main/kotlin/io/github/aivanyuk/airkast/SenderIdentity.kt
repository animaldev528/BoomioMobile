package io.github.aivanyuk.airkast

import io.github.aivanyuk.airkast.internal.Poko
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * What the sender tells a receiver about itself in SETUP. The defaults are the values receivers
 * are known to accept; [deviceId] is a random, locally administered MAC address, never a real one.
 *
 * A Mac or an Apple TV asks its user before it lets in a [deviceId] it has not seen, so an app
 * keeps one across launches: `Airkast(context)` derives it from the device and the app
 * ([deviceIdFrom]), and a desktop app passes a seed of its own.
 */
@Poko
public class SenderIdentity(
    public val name: String = "airkast",
    public val deviceId: String = randomDeviceId(),
    public val model: String = "iPhone14,3",
    public val osName: String = "iPhone OS",
    public val osVersion: String = "16.5",
    public val osBuildVersion: String = "20F66",
    public val sourceVersion: String = "690.7.1",
    public val userAgent: String = "AirPlay/550.10",
) {
    public companion object {
        public fun randomDeviceId(): String = mac(ByteArray(6).also(SecureRandom()::nextBytes))

        /**
         * A locally administered MAC address that is always the same for [seed] and tells nothing
         * about it: the first six bytes of its SHA-256. Seed it with something that stays as long
         * as the app's install, such as an ID the app keeps.
         */
        public fun deviceIdFrom(seed: String): String =
            mac(MessageDigest.getInstance("SHA-256").digest(seed.toByteArray(Charsets.UTF_8)).copyOf(6))

        /** [bytes] as a MAC address, locally administered and unicast. */
        private fun mac(bytes: ByteArray): String {
            bytes[0] = ((bytes[0].toInt() and 0xfc) or 0x02).toByte()
            return bytes.joinToString(":") { "%02X".format(it) }
        }
    }
}
