package com.kinetica.keyboard.ime

/**
 * What a word trace needs to know about the keyboard's state, handed to
 * [TraceRecorder] as reads rather than values because each is read when a word
 * ends. Only the developer build reads it; the release stub ignores it.
 */
class TraceInfo(
    val language: () -> String,
    val alternate: () -> String?,
    val britishSpelling: () -> Boolean,
    /** Learned counts, learned pairs or user words were loaded into the decode. */
    val personal: () -> Boolean,
    /** The active wordlist is a user-installed replacement, not the bundled one. */
    val dictOverride: () -> Boolean,
    /** Nothing may be recorded: a password, incognito or other private field. */
    val suppressed: () -> Boolean,
)
