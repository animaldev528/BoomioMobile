package io.github.aivanyuk.airkast

/**
 * What a receiver asks the user for before it lets this sender in. [Airkast.connect] passes it to
 * its prompt, so the app can ask for the right one: a PIN on a number pad, a password as text.
 */
public enum class Secret {
    /** A code the receiver shows on its screen while it pairs ([Compatibility.NeedsPin]). */
    Pin,

    /** A password set in the receiver's AirPlay settings ([Compatibility.NeedsPassword]). */
    Password,
}
