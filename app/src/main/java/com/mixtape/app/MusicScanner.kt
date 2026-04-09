package com.mixtape.app

import android.content.ContentResolver
import android.content.ContentUris
import android.net.Uri
import android.provider.MediaStore

class MusicScanner(private val contentResolver: ContentResolver) {

    companion object {
        private val SUPPORTED_MIME_TYPES = arrayOf(
            "audio/mpeg",       // MP3
            "audio/flac",       // FLAC
            "audio/x-wav",      // WAV
            "audio/wav",        // WAV
            "audio/ogg",        // OGG Vorbis
            "audio/x-ms-wma",   // WMA
            "audio/aac",        // AAC
            "audio/mp4",        // M4A/AAC
            "audio/opus",       // Opus
            "audio/vorbis",     // Vorbis
        )
    }

    fun scanAllMusic(): List<MusicFile> {
        val musicFiles = mutableListOf<MusicFile>()

        val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI

        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.DATA,
            MediaStore.Audio.Media.MIME_TYPE,
            MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.DURATION,
        )

        val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0"
        val sortOrder = "${MediaStore.Audio.Media.TITLE} ASC"

        contentResolver.query(collection, projection, selection, null, sortOrder)?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val albumCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
            val dataCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
            val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.MIME_TYPE)
            val displayNameCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
            val durationCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                val title = cursor.getString(titleCol) ?: "Unknown"
                val artist = cursor.getString(artistCol) ?: "Unknown"
                val album = cursor.getString(albumCol) ?: "Unknown"
                val path = cursor.getString(dataCol) ?: ""
                val mimeType = cursor.getString(mimeCol) ?: ""
                val displayName = cursor.getString(displayNameCol) ?: "unknown.mp3"
                val duration = cursor.getLong(durationCol)

                val contentUri: Uri = ContentUris.withAppendedId(
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id
                )

                if (SUPPORTED_MIME_TYPES.any { mimeType.startsWith(it.substringBefore("/")) } ||
                    isSupportedExtension(path)) {
                    musicFiles.add(
                        MusicFile(
                            id = id,
                            title = title,
                            artist = artist,
                            album = album,
                            uri = contentUri,
                            path = path,
                            mimeType = mimeType,
                            displayName = displayName,
                            duration = duration
                        )
                    )
                }
            }
        }

        return musicFiles
    }

    private fun isSupportedExtension(path: String): Boolean {
        val ext = path.substringAfterLast(".").lowercase()
        return ext in setOf("mp3", "flac", "wav", "ogg", "m4a", "aac", "wma", "opus", "oga")
    }
}
