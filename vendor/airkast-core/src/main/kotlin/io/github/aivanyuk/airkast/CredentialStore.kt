package io.github.aivanyuk.airkast

import io.github.aivanyuk.airkast.session.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.Properties
import java.util.concurrent.ConcurrentHashMap

/**
 * Where an [Airkast] keeps the [Credentials] that pairing with a PIN or password leaves, one per
 * receiver, so the user types it once. They hold the sender's private key for that receiver, and
 * the receiver's password after pairing with one, so an app with a place for secrets implements
 * this over it. Calls come from any thread.
 *
 * The built-in stores keep a receiver's credentials under its [Receiver.id]: its `deviceid`, which
 * stays when its address changes, or for one typed in by hand, its host and port.
 */
public interface CredentialStore {
    public suspend fun get(receiver: Receiver): Credentials?

    public suspend fun put(
        receiver: Receiver,
        credentials: Credentials,
    )

    public suspend fun remove(receiver: Receiver)

    public companion object {
        /**
         * Keeps credentials while the store lives, so a receiver asks for its PIN or password again
         * in the next process.
         */
        public fun inMemory(): CredentialStore = MemoryCredentialStore()

        /**
         * Keeps credentials in [file], readable by its owner only, and in memory. A file it cannot
         * read or write leaves them in memory for this process. `Airkast(context)` keeps them
         * sealed instead, with `file(sealer, logger, file)` and a key in the Android Keystore.
         */
        public fun file(file: File): CredentialStore = FileCredentialStore({ file })

        /**
         * As `file(File)`, for a file that takes I/O to find, such as a directory a platform
         * creates when asked for it. The store calls [file] once, on [Dispatchers.IO], the first
         * time it is used, so building it touches no disk. A [file] that throws leaves
         * credentials in memory for this process.
         */
        public fun file(file: () -> File): CredentialStore = FileCredentialStore(file)

        /**
         * As `file(() -> File)`, with each pairing sealed by [sealer] before it reaches the file, so
         * the file never holds a private key or a password in the clear. A pairing in the clear,
         * as `file` writes it, still reads: the first read seals it and rewrites the file.
         * `Airkast(context)` keeps one sealed with a key in the Android Keystore.
         *
         * The sealer's failures never reach the caller. A pairing that no longer opens, as after
         * its key was lost, reads as none, so the receiver asks for its PIN or password again. One
         * that cannot be sealed stays in memory for this process, and so does every pairing after
         * the sealer finds it has no key. Each goes to [logger] at [Airkast.Logger.Level.Warn],
         * under the tag `credentials`.
         */
        public fun file(
            sealer: Sealer,
            logger: Airkast.Logger? = null,
            file: () -> File,
        ): CredentialStore = FileCredentialStore(file, sealer, Log(logger, "credentials"))
    }

    /**
     * Seals the pairings that `CredentialStore.file(sealer, …)` writes, and opens what it reads,
     * with a key kept outside the file. The store calls it on an I/O thread, one call at a time.
     */
    public interface Sealer {
        /**
         * [value], sealed. An [IOException] says there is no key to seal with, and the store keeps
         * every pairing after it in memory. Any other exception fails this one value.
         */
        public fun seal(value: ByteArray): ByteArray

        /**
         * What [seal] made [sealed] from. An [IOException] says there is no key, as for [seal]. Any
         * other exception, such as one for a key that no longer matches, reads as no pairing.
         */
        public fun open(sealed: ByteArray): ByteArray
    }
}

private class MemoryCredentialStore : CredentialStore {
    private val entries = ConcurrentHashMap<String, Credentials>()

    override suspend fun get(receiver: Receiver): Credentials? = entries[receiver.id]

    override suspend fun put(
        receiver: Receiver,
        credentials: Credentials,
    ) {
        entries[receiver.id] = credentials
    }

    override suspend fun remove(receiver: Receiver) {
        entries.remove(receiver.id)
    }
}

/**
 * One `key=credentials` line per receiver, in a properties file that each change replaces whole.
 * With a [sealer], a line holds the credentials sealed, in hex, which never reads as credentials
 * in the clear. The file is found on the first call, under [lock], so the constructor does no I/O.
 */
private class FileCredentialStore(
    locate: () -> File,
    private val sealer: CredentialStore.Sealer? = null,
    private val log: Log = Log.NONE,
) : CredentialStore {
    private val lock = Any()
    private var cache: Map<String, String>? = null

    /** Pairings that could not be sealed, kept for this process in place of the file's. */
    private val unsealed = HashMap<String, Credentials>()

    /** Set once the sealer has no key, after which nothing is sealed or opened. */
    private var keyless = false

    private val file: File? by lazy(LazyThreadSafetyMode.NONE) {
        try {
            locate()
        } catch (_: Exception) {
            null
        }
    }

    override suspend fun get(receiver: Receiver): Credentials? =
        withContext(Dispatchers.IO) {
            synchronized(lock) { unsealed[receiver.id] ?: entries()[receiver.id]?.let(::open) }
        }

    override suspend fun put(
        receiver: Receiver,
        credentials: Credentials,
    ) = withContext(Dispatchers.IO) {
        synchronized(lock) {
            entries()
            val line = seal(credentials)
            if (line == null) {
                unsealed[receiver.id] = credentials
            } else {
                unsealed.remove(receiver.id)
                update { it[receiver.id] = line }
            }
        }
    }

    override suspend fun remove(receiver: Receiver) =
        withContext(Dispatchers.IO) {
            synchronized(lock) {
                unsealed.remove(receiver.id)
                update { it.remove(receiver.id) }
            }
        }

    private fun entries(): Map<String, String> =
        synchronized(lock) { cache ?: sealInTheClear(read()).also { cache = it } }

    private fun update(change: (MutableMap<String, String>) -> Unit) =
        synchronized(lock) {
            val entries = entries().toMutableMap().also(change)
            cache = entries
            runCatching { write(entries) }
            Unit
        }

    /** The line that keeps [credentials], or null when they could not be sealed. */
    private fun seal(credentials: Credentials): String? {
        val sealer = sealer ?: return credentials.encoded
        if (keyless) return null
        return try {
            sealer.seal(credentials.encoded.toByteArray(Charsets.UTF_8)).hex()
        } catch (e: IOException) {
            lostTheKey(e)
            null
        } catch (e: Exception) {
            log.warn(e) { "a pairing could not be sealed, so it stays in memory until the process ends" }
            null
        }
    }

    private fun open(line: String): Credentials? {
        Credentials.decode(line)?.let { return it }
        val sealer = sealer ?: return null
        val sealed = line.unhex() ?: return null
        if (keyless) return null
        return try {
            Credentials.decode(String(sealer.open(sealed), Charsets.UTF_8))
        } catch (e: IOException) {
            lostTheKey(e)
            null
        } catch (e: Exception) {
            log.warn(e) { "a pairing no longer opens, so the receiver will ask for its PIN or password again" }
            null
        }
    }

    private fun lostTheKey(e: IOException) {
        keyless = true
        log.warn(e) { "there is no key to seal pairings with, so new ones stay in memory until the process ends" }
    }

    /**
     * [entries] with each line in the clear sealed, as `file` and airkast 0.5.0 wrote them, and
     * the file rewritten when any was. A line that cannot be sealed stays as it was.
     */
    private fun sealInTheClear(entries: Map<String, String>): Map<String, String> {
        if (sealer == null) return entries
        val sealed = entries.mapValues { (_, line) -> Credentials.decode(line)?.let(::seal) ?: line }
        if (sealed != entries && runCatching { write(sealed) }.isSuccess) {
            log.debug { "sealed the pairings an earlier version kept in the clear" }
        }
        return sealed
    }

    private fun read(): Map<String, String> =
        try {
            val file = file
            if (file == null || !file.exists()) {
                emptyMap()
            } else {
                val properties = Properties().apply { file.inputStream().use(::load) }
                properties.stringPropertyNames().associateWith(properties::getProperty)
            }
        } catch (_: IOException) {
            emptyMap()
        } catch (_: IllegalArgumentException) {
            // A malformed \u escape: the file is not one this store wrote.
            emptyMap()
        }

    private fun write(entries: Map<String, String>) {
        val file = file ?: return
        file.absoluteFile.parentFile?.mkdirs()
        val temp = File(file.absoluteFile.parentFile, "${file.name}.tmp")
        temp.delete()
        temp.createNewFile()
        temp.ownerOnly()
        val properties = Properties().apply { entries.forEach { (key, value) -> setProperty(key, value) } }
        temp.outputStream().use { properties.store(it, "airkast pairings: they hold private keys") }
        // On Windows a rename does not replace a file.
        if (!temp.renameTo(file) && !(file.delete() && temp.renameTo(file))) {
            throw IOException("Cannot replace $file")
        }
    }
}

/**
 * Makes this readable and writable by its owner alone, before a key reaches it. `File` sets a
 * permission for the owner without taking it from anyone else, so each is cleared for everybody
 * first: with owner-only calls alone, a file the umask made `rw-r--r--` stays that way.
 * `Files.setPosixFilePermissions` does it in one call, but Android has it only from API 26.
 */
private fun File.ownerOnly() {
    setReadable(false, false)
    setReadable(true, true)
    setWritable(false, false)
    setWritable(true, true)
}
