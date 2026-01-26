package com.example.ollama

import java.text.CharacterIterator
import java.text.StringCharacterIterator

// https://stackoverflow.com/questions/3758606/how-to-convert-byte-size-into-human-readable-format-in-java
fun humanReadableByteCountSI(bytes: Long): String {
    if (-1000 < bytes && bytes < 1000) {
        return "$bytes B"
    }
    val ci: CharacterIterator = StringCharacterIterator("kMGTPE")
    var tempBytes = bytes
    while (tempBytes <= -999950 || tempBytes >= 999950) {
        tempBytes /= 1000
        ci.next()
    }
    return String.format("%.1f %cB", tempBytes / 1000.0, ci.current())
}
