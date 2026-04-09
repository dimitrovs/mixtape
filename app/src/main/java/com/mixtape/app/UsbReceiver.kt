package com.mixtape.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbManager

/**
 * Receives USB_DEVICE_DETACHED broadcasts to notify the app when
 * USB storage is disconnected.
 */
class UsbReceiver : BroadcastReceiver() {

    var onUsbDetached: (() -> Unit)? = null

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                onUsbDetached?.invoke()
            }
        }
    }
}
