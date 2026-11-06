package io.github.aivanyuk.airkast.internal

/**
 * Gives a public value class `equals`, `hashCode` and `toString` without a data class's `copy` and
 * `componentN`, which break callers' binaries whenever a property is added. Poko's compiler plugin
 * reads it (airkast.jvm.library); declaring it here keeps Poko's own annotation artifact out of
 * consumers' dependencies.
 */
@Retention(AnnotationRetention.SOURCE)
@Target(AnnotationTarget.CLASS)
internal annotation class Poko
