package io.github.aivanyuk.airkast.crypto

import java.math.BigInteger
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * The client half of SRP-6a as HomeKit pairing uses it: the RFC 5054 3072-bit group, generator 5,
 * SHA-512. The hashing matches srptools, which pyatv pairs with.
 */
internal class SrpClient(
    private val username: String,
    private val password: String,
    private val privateKey: BigInteger = BigInteger(256, SecureRandom()),
) {
    val publicKey: ByteArray = unsigned(G.modPow(privateKey, N))

    /** K, set by [respond]. */
    lateinit var sessionKey: ByteArray
        private set

    private lateinit var proof: ByteArray

    /** Returns the client proof M1 for the receiver's [salt] and public key B. */
    fun respond(
        salt: ByteArray,
        serverPublic: ByteArray,
    ): ByteArray {
        val b = BigInteger(1, serverPublic)
        require(b.mod(N) != BigInteger.ZERO) { "Receiver sent an invalid public key" }
        val u = BigInteger(1, sha512(pad(publicKey), pad(serverPublic)))
        require(u != BigInteger.ZERO) { "Receiver sent an invalid public key" }
        val k = BigInteger(1, sha512(unsigned(N), pad(unsigned(G))))
        val x = BigInteger(1, sha512(salt, sha512("$username:$password".toByteArray())))
        val base = b.subtract(k.multiply(G.modPow(x, N))).mod(N)
        val s = base.modPow(privateKey.add(u.multiply(x)), N)
        sessionKey = sha512(unsigned(s))
        val hn = sha512(unsigned(N))
        val hg = sha512(unsigned(G))
        val hng = ByteArray(hn.size) { (hn[it].toInt() xor hg[it].toInt()).toByte() }
        proof =
            sha512(
                unsigned(BigInteger(1, hng)),
                sha512(username.toByteArray()),
                salt,
                publicKey,
                unsigned(b),
                sessionKey,
            )
        return proof
    }

    /** Whether the receiver's M2 proves it knows the same session key. */
    fun verifyServer(serverProof: ByteArray): Boolean =
        MessageDigest.isEqual(sha512(publicKey, proof, sessionKey), serverProof)

    private fun pad(bytes: ByteArray): ByteArray =
        if (bytes.size >= N_LENGTH) bytes else ByteArray(N_LENGTH - bytes.size) + bytes

    internal companion object {
        val N =
            BigInteger(
                "FFFFFFFFFFFFFFFFC90FDAA22168C234C4C6628B80DC1CD129024E088A67CC74020BBEA63B139B22514A08798E3404DD" +
                    "EF9519B3CD3A431B302B0A6DF25F14374FE1356D6D51C245E485B576625E7EC6F44C42E9A637ED6B0BFF5CB6F406B7ED" +
                    "EE386BFB5A899FA5AE9F24117C4B1FE649286651ECE45B3DC2007CB8A163BF0598DA48361C55D39A69163FA8FD24CF5F" +
                    "83655D23DCA3AD961C62F356208552BB9ED529077096966D670C354E4ABC9804F1746C08CA18217C32905E462E36CE3B" +
                    "E39E772C180E86039B2783A2EC07A28FB5C55DF06F4C52C9DE2BCBF6955817183995497CEA956AE515D2261898FA0510" +
                    "15728E5A8AAAC42DAD33170D04507A33A85521ABDF1CBA64ECFB850458DBEF0A8AEA71575D060C7DB3970F85A6E1E4C7" +
                    "ABF5AE8CDB0933D71E8C94E04A25619DCEE3D2261AD2EE6BF12FFA06D98A0864D87602733EC86A64521F2B18177B200C" +
                    "BBE117577A615D6C770988C0BAD946E208E24FA074E5AB3143DB5BFCE0FD108E4B82D120A93AD2CAFFFFFFFFFFFFFFFF",
                16,
            )
        val G: BigInteger = BigInteger.valueOf(5)
        val N_LENGTH = unsigned(N).size

        fun unsigned(value: BigInteger): ByteArray {
            val bytes = value.toByteArray()
            return if (bytes.size > 1 && bytes[0] == 0.toByte()) bytes.copyOfRange(1, bytes.size) else bytes
        }

        fun sha512(vararg parts: ByteArray): ByteArray =
            MessageDigest.getInstance("SHA-512").run {
                parts.forEach(::update)
                digest()
            }
    }
}
