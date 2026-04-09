package com.mixtape.app

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.mixtape.app.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var destinationUri: Uri? = null
    private var musicFiles: List<MusicFile> = emptyList()
    private var conversionService: ConversionService? = null
    private var serviceBound = false

    private val usbReceiver = UsbReceiver()

    // Permission request
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val granted = permissions.entries.all { it.value }
        if (granted) {
            scanMusic()
        } else {
            binding.statusText.text = getString(R.string.permission_required)
        }
    }

    // SAF directory picker for USB storage
    private val storagePickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        uri?.let {
            // Persist permission across reboots
            contentResolver.takePersistableUriPermission(
                it,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            destinationUri = it
            binding.statusText.text = "USB storage selected. Ready to transfer."
            binding.startButton.isEnabled = musicFiles.isNotEmpty()
        }
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val localBinder = binder as ConversionService.LocalBinder
            conversionService = localBinder.service
            serviceBound = true
            setupServiceCallbacks()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            conversionService = null
            serviceBound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupUI()
        registerUsbReceiver()
        checkPermissionsAndScan()

        // Handle USB attach intent that launched us
        if (intent?.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) {
            handleUsbAttached()
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (intent?.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) {
            handleUsbAttached()
        }
    }

    private fun setupUI() {
        binding.selectStorageButton.setOnClickListener {
            storagePickerLauncher.launch(null)
        }

        binding.startButton.setOnClickListener {
            startTransfer()
        }
    }

    private fun registerUsbReceiver() {
        usbReceiver.onUsbDetached = {
            runOnUiThread {
                if (conversionService?.isRunning == true) {
                    conversionService?.stopTransfer()
                }
                binding.statusText.text = "USB storage disconnected."
                binding.startButton.isEnabled = false
                binding.progressSection.visibility = View.GONE
            }
        }
        val filter = IntentFilter(UsbManager.ACTION_USB_DEVICE_DETACHED)
        registerReceiver(usbReceiver, filter, RECEIVER_NOT_EXPORTED)
    }

    private fun handleUsbAttached() {
        binding.statusText.text = "USB storage detected! Select the USB drive to begin."
        appendLog("USB device attached. Tap 'Select USB Storage' to choose the drive.")
    }

    private fun checkPermissionsAndScan() {
        val permissions = mutableListOf<String>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.READ_MEDIA_AUDIO)
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
        }

        if (permissions.isNotEmpty()) {
            permissionLauncher.launch(permissions.toTypedArray())
        } else {
            scanMusic()
        }
    }

    private fun scanMusic() {
        binding.statusText.text = getString(R.string.scanning_music)

        Thread {
            val scanner = MusicScanner(contentResolver)
            musicFiles = scanner.scanAllMusic()

            runOnUiThread {
                if (musicFiles.isEmpty()) {
                    binding.statusText.text = getString(R.string.no_music_found)
                    binding.fileCountText.visibility = View.GONE
                } else {
                    binding.statusText.text = getString(R.string.files_found, musicFiles.size)
                    binding.fileCountText.visibility = View.VISIBLE

                    val mp3Count = musicFiles.count { it.isAlreadyMp3 }
                    val convertCount = musicFiles.size - mp3Count
                    binding.fileCountText.text =
                        "$mp3Count MP3 files (direct copy) + $convertCount files to convert"

                    binding.startButton.isEnabled = destinationUri != null
                }
            }
        }.start()
    }

    private fun startTransfer() {
        val destUri = destinationUri ?: return
        if (musicFiles.isEmpty()) return

        // Bind to service
        val serviceIntent = Intent(this, ConversionService::class.java)
        startForegroundService(serviceIntent)
        bindService(serviceIntent, serviceConnection, BIND_AUTO_CREATE)

        // Show progress UI
        binding.progressSection.visibility = View.VISIBLE
        binding.progressBar.max = musicFiles.size
        binding.progressBar.progress = 0
        binding.startButton.isEnabled = false
        binding.selectStorageButton.isEnabled = false
        binding.statusText.text = getString(R.string.converting)
        binding.logText.text = ""

        // Start transfer once service is bound
        // We wait briefly for the service to bind, then start
        binding.root.postDelayed({
            conversionService?.startTransfer(musicFiles, destUri)
        }, 500)
    }

    private fun setupServiceCallbacks() {
        conversionService?.onProgress = { current, total, fileName, status ->
            runOnUiThread {
                binding.progressBar.progress = current
                binding.progressText.text = getString(R.string.progress_format, current, total)
                binding.currentFileText.text = "$status: $fileName"
                appendLog("[$current/$total] $status: $fileName")

                // Auto-scroll to bottom
                binding.logScrollView.post {
                    binding.logScrollView.fullScroll(View.FOCUS_DOWN)
                }
            }
        }

        conversionService?.onComplete = { successful, failed, skipped ->
            runOnUiThread {
                binding.statusText.text = getString(R.string.complete)
                binding.currentFileText.text = ""
                binding.progressText.text =
                    "Done: $successful copied, $failed failed, $skipped skipped"
                binding.startButton.isEnabled = false
                binding.selectStorageButton.isEnabled = true

                appendLog("\n--- Transfer Complete ---")
                appendLog("Successful: $successful")
                appendLog("Failed: $failed")
                appendLog("Skipped: $skipped")
            }
        }

        conversionService?.onError = { message ->
            runOnUiThread {
                binding.statusText.text = "Error: $message"
                binding.startButton.isEnabled = true
                binding.selectStorageButton.isEnabled = true
                binding.progressSection.visibility = View.GONE
                appendLog("ERROR: $message")
            }
        }
    }

    private fun appendLog(text: String) {
        val current = binding.logText.text?.toString() ?: ""
        binding.logText.text = if (current.isEmpty()) text else "$current\n$text"
    }

    override fun onDestroy() {
        if (serviceBound) {
            unbindService(serviceConnection)
            serviceBound = false
        }
        unregisterReceiver(usbReceiver)
        super.onDestroy()
    }
}
