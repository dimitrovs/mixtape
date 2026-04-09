package com.mixtape.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.net.Uri
import android.os.Binder
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.*

class ConversionService : Service() {

    companion object {
        const val CHANNEL_ID = "mixtape_transfer"
        const val NOTIFICATION_ID = 1
        const val ACTION_STOP = "com.mixtape.app.STOP"
    }

    inner class LocalBinder : Binder() {
        val service: ConversionService get() = this@ConversionService
    }

    private val binder = LocalBinder()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    var onProgress: ((current: Int, total: Int, fileName: String, status: String) -> Unit)? = null
    var onComplete: ((successful: Int, failed: Int, skipped: Int) -> Unit)? = null
    var onError: ((message: String) -> Unit)? = null
    var isRunning = false
        private set

    private var job: Job? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopTransfer()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    fun startTransfer(musicFiles: List<MusicFile>, destinationUri: Uri) {
        if (isRunning) return
        isRunning = true

        startForeground(NOTIFICATION_ID, buildNotification("Starting transfer...", 0, 0))

        job = scope.launch {
            var successful = 0
            var failed = 0
            var skipped = 0
            val converter = AudioConverter(this@ConversionService)
            val destDir = DocumentFile.fromTreeUri(this@ConversionService, destinationUri)

            if (destDir == null || !destDir.canWrite()) {
                withContext(Dispatchers.Main) {
                    onError?.invoke("Cannot write to selected USB storage")
                }
                stopSelf()
                return@launch
            }

            // Create Mixtape subfolder
            val mixtapeDir = destDir.findFile("Mixtape")
                ?: destDir.createDirectory("Mixtape")

            if (mixtapeDir == null) {
                withContext(Dispatchers.Main) {
                    onError?.invoke("Failed to create Mixtape folder on USB")
                }
                stopSelf()
                return@launch
            }

            val total = musicFiles.size

            for ((index, file) in musicFiles.withIndex()) {
                if (!isActive) break

                val outputName = file.outputFileName

                // Skip if already exists on destination
                val existing = mixtapeDir.findFile(outputName)
                if (existing != null && existing.length() > 0) {
                    skipped++
                    val status = "Skipped (already exists)"
                    withContext(Dispatchers.Main) {
                        onProgress?.invoke(index + 1, total, file.displayName, status)
                    }
                    updateNotification("${index + 1}/$total: $outputName", index + 1, total)
                    continue
                }

                try {
                    if (file.isAlreadyMp3) {
                        // Direct copy for MP3 files
                        val status = getString(R.string.already_mp3)
                        withContext(Dispatchers.Main) {
                            onProgress?.invoke(index + 1, total, file.displayName, status)
                        }
                        copyFile(file.uri, mixtapeDir, outputName)
                        successful++
                    } else {
                        // Convert to MP3
                        val status = getString(R.string.converting_file)
                        withContext(Dispatchers.Main) {
                            onProgress?.invoke(index + 1, total, file.displayName, status)
                        }

                        val outFile = mixtapeDir.createFile("audio/mpeg", outputName)
                        if (outFile != null) {
                            contentResolver.openOutputStream(outFile.uri)?.use { os ->
                                when (val result = converter.convertToMp3(file.uri, os)) {
                                    is AudioConverter.ConversionResult.Success -> successful++
                                    is AudioConverter.ConversionResult.Error -> {
                                        failed++
                                        // Clean up failed file
                                        outFile.delete()
                                        withContext(Dispatchers.Main) {
                                            onProgress?.invoke(index + 1, total, file.displayName,
                                                "Error: ${result.message}")
                                        }
                                    }
                                }
                            } ?: run {
                                failed++
                            }
                        } else {
                            failed++
                        }
                    }
                } catch (e: Exception) {
                    failed++
                    withContext(Dispatchers.Main) {
                        onProgress?.invoke(index + 1, total, file.displayName,
                            "Error: ${e.message}")
                    }
                }

                updateNotification("${index + 1}/$total: $outputName", index + 1, total)
            }

            withContext(Dispatchers.Main) {
                onComplete?.invoke(successful, failed, skipped)
            }

            updateNotification(
                "Transfer complete! $successful copied, $failed failed, $skipped skipped",
                total, total
            )

            isRunning = false
            stopForeground(STOP_FOREGROUND_DETACH)
            stopSelf()
        }
    }

    fun stopTransfer() {
        job?.cancel()
        isRunning = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun copyFile(sourceUri: Uri, destDir: DocumentFile, fileName: String) {
        val destFile = destDir.createFile("audio/mpeg", fileName) ?: return
        contentResolver.openInputStream(sourceUri)?.use { input ->
            contentResolver.openOutputStream(destFile.uri)?.use { output ->
                input.copyTo(output, bufferSize = 8192)
            }
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.notification_channel_description)
        }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(channel)
    }

    private fun buildNotification(text: String, progress: Int, max: Int): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent, PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, ConversionService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 1, stopIntent, PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setContentIntent(pendingIntent)
            .setProgress(max, progress, max == 0)
            .addAction(0, getString(R.string.cancel), stopPendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String, progress: Int, max: Int) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, buildNotification(text, progress, max))
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
