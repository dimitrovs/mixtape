package com.mixtape.app

import android.net.Uri

data class MusicFile(
    val id: Long,
    val title: String,
    val artist: String,
    val album: String,
    val uri: Uri,
    val path: String,
    val mimeType: String,
    val displayName: String,
    val duration: Long
) {
    val isAlreadyMp3: Boolean
        get() = mimeType == "audio/mpeg" || path.endsWith(".mp3", ignoreCase = true)

    val outputFileName: String
        get() {
            val baseName = displayName.substringBeforeLast(".")
            return "$baseName.mp3"
        }
}
