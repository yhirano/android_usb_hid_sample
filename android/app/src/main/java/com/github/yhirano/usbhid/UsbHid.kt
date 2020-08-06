package com.github.yhirano.usbhid

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.util.Log
import java.util.concurrent.Executors

class UsbHid constructor(
    context: Context,
    private val vendorId: Int,
    private val productId: Int
) {
    enum class State {
        Uninitialized,
        PermissionRequesting,
        FailedInitialize,
        Working
    }

    interface Listener {
        /**
         * Receive new data.
         */
        fun onNewData(data: ByteArray)

        /**
         * Occurred error.
         *
         * @param e Occurred exception.
         */
        fun onRunError(e: Exception)

        /**
         * Changed state.
         *
         * @param state state
         */
        fun onStateChanged(state: State)
    }

    var listener: Listener? = null

    /**
     * Default number of retries on write error.
     * If it is less than or equal to 0, no retries.
     */
    var defaultRetry: Int = 0
        set(value) {
            field = value
            writeManager?.defaultRetry = value
        }

    var state = State.Uninitialized
        private set(value) {
            val changed = field != value

            field = value

            if (changed) {
                listener?.onStateChanged(field)
            }
        }

    private val context = context.applicationContext

    private val usbManager: UsbManager by lazy {
        context.getSystemService(Context.USB_SERVICE) as UsbManager
    }

    private var device: UsbDevice? = null

    private var port: Port? = null

    private var readManager: ReadManager? = null
    private var writeManager: WriteManager? = null

    private var isRegisterUsbPermissionReceiver = false

    fun openDevice(): State {
        val usbAttachIntentFilter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        context.registerReceiver(usbAttachReceiver, usbAttachIntentFilter)

        state = connect()
        return state
    }

    fun closeDevice() {
        state = disconnect()
        try {
            context.unregisterReceiver(usbAttachReceiver)
        } catch (e: IllegalArgumentException) {
            Log.i(TAG, "Ignore IllegalArgumentException because usbAttachReceiver isn't attached.", e)
        }
        if (isRegisterUsbPermissionReceiver) {
            context.unregisterReceiver(usbPermissionReceiver)
            isRegisterUsbPermissionReceiver = false
        }
    }

    /**
     * @param retry Number of retries at data write error. If null, the number of times specified in [defaultRetry].
     */
    fun write(data: ByteArray, retry: Int? = null) {
        writeManager?.writeAsync(data, retry)
            ?: Log.w(TAG, "Failed to write data to USB HID because UsbHid class isn't open.")
    }

    private fun connect(): State {
        val deviceList = usbManager.deviceList
        val foundDevices = deviceList.values.filter {
            it.vendorId == vendorId && it.productId == productId
        }
        if (foundDevices.isEmpty()) {
            Log.d(TAG, "Not found devices.")
            return State.FailedInitialize
        }

        Log.d(TAG, "Found ${foundDevices.size} device(s).")
        foundDevices.forEachIndexed { i, device ->
            Log.d(TAG, "  ${i + 1}: DeviceName=\"${device.deviceName}\", ManufacturerName=\"${device.manufacturerName}\", ProductName=\"${device.productName}\", VendorId=${String.format("0x%04X", device.vendorId)}, ProductId=${String.format("0x%04X", device.productId)}")
        }

        val device = foundDevices[0]
        this.device = device
        Log.d(TAG, "Connect to \"${device.deviceName}\".")

        val connection = usbManager.openDevice(device)
        return if (connection == null) {
            val usbSerialPermissionIntent =
                PendingIntent.getBroadcast(context, 0, Intent(ACTION_USB_PERMISSION), 0)
            val usbPermissionIntentFilter = IntentFilter().apply {
                addAction(ACTION_USB_PERMISSION)
            }
            context.registerReceiver(usbPermissionReceiver, usbPermissionIntentFilter)
            isRegisterUsbPermissionReceiver = true
            usbManager.requestPermission(device, usbSerialPermissionIntent)
            State.PermissionRequesting
        } else {
            initPort(device, connection)
        }
    }

    private fun disconnect(): State {
        device = null
        port?.close()
        port = null
        stopIoManager()
        return State.Uninitialized
    }

    private fun initPort(device: UsbDevice, connection: UsbDeviceConnection): State {
        port = Port.create(device, connection)
        return if (port != null) {
            startIoManager()
            State.Working
        } else {
            Log.d(TAG, "Couldn't initialize port. Device has no read and write endpoint. port=\"${port}\"")
            State.FailedInitialize
        }
    }

    private fun startIoManager() {
        val port = port
        if (port == null) {
            Log.w(TAG, "Has no USB device. Maybe not initialize yet.")
            return
        }
        val readManager = ReadManager(
            port,
            object : ReadManager.Listener {
                override fun onNewData(data: ByteArray) {
                    listener?.onNewData(data)
                }

                override fun onRunError(e: Exception) {
                    listener?.onRunError(e)
                }
            })
        val writeManager = WriteManager(port, object : WriteManager.Listener {
            override fun onRunError(e: Exception) {
                listener?.onRunError(e)
            }
        }).apply {
            defaultRetry = this@UsbHid.defaultRetry
        }
        this.readManager = readManager
        this.writeManager = writeManager
        Executors.newSingleThreadExecutor().submit(readManager)
        Executors.newSingleThreadExecutor().submit(writeManager)
    }

    private fun stopIoManager() {
        readManager?.stop()
            ?: Log.w(TAG, "Has no data reading thread. Maybe not initialize yet.")
        readManager = null
        writeManager?.stop()
            ?: Log.w(TAG, "Has no data writing thread. Maybe not initialize yet.")
        writeManager = null
    }

    private val usbPermissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_USB_PERMISSION) {
                synchronized(this) {
                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                        val device = device
                        if (device == null) {
                            Log.w(TAG, "Failed to connect device.")
                            state = State.FailedInitialize
                            return
                        }

                        val connection = usbManager.openDevice(device)
                        if (connection == null) {
                            Log.w(TAG, "Failed to connect device.")
                            state = State.FailedInitialize
                            return
                        }

                        state = initPort(device, connection)
                        return
                    } else {
                        Log.i(
                            TAG,
                            "Couldn't obtain connecting permission to \"${device?.deviceName}\"."
                        )
                        state = State.FailedInitialize
                    }
                }
            }
        }
    }

    private val usbAttachReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    Log.d(TAG, "USB Device attached.")
                    when (state) {
                        State.Uninitialized, State.FailedInitialize -> {
                            connect()
                        }
                        State.PermissionRequesting, State.Working -> {
                            // Ignore
                        }
                    }
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    Log.d(TAG, "USB Device detached.")
                    when (state) {
                        State.Working -> {
                            state = disconnect()
                        }
                        State.Uninitialized, State.FailedInitialize, State.PermissionRequesting -> {
                            // Ignore
                        }
                    }
                }
            }
        }
    }

    companion object {
        private const val TAG = "UsbHid"

        private const val ACTION_USB_PERMISSION = "com.github.yhirano.usbhid.USB_PERMISSION"
    }
}