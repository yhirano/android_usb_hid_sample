package com.github.yhirano.usbhid

import android.hardware.usb.*
import android.util.Log
import java.io.IOException

class Port private constructor(
    private val device: UsbDevice,
    private val connection: UsbDeviceConnection,
    private val usbInterface: UsbInterface,
    private val readEndpoint: UsbEndpoint,
    private val writeEndpoint: UsbEndpoint
) {
    override fun toString(): String {
        return "Port(device=$device)"
    }

    fun close() {
        connection.releaseInterface(usbInterface)
        connection.close()
    }


    fun read(dest: ByteArray, timeout: Int): Int {
        return connection.bulkTransfer(readEndpoint, dest, dest.size, timeout)
    }

    /**
     * @exception IOException Failed to write data to USB.
     */
    fun write(data: ByteArray, timeout: Int) {
        val length = connection.bulkTransfer(writeEndpoint, data, data.size, timeout)
        if (length <= 0) {
            throw IOException("Failed to write data to USB. status=$length")
        }
    }

    companion object {
        private const val TAG = "UsbHid/Port"

        fun create(device: UsbDevice, connection: UsbDeviceConnection): Port? {
            val interfaceCount = device.interfaceCount
            for (ii in 0 until interfaceCount) {
                val usbInterface = device.getInterface(ii)
                if (usbInterface.interfaceClass != UsbConstants.USB_CLASS_HID) {
                    continue
                }

                var readEndpoint: UsbEndpoint? = null
                var writeEndpoint: UsbEndpoint? = null

                val endpointCount = usbInterface.endpointCount
                for (ei in 0 until endpointCount) {
                    val endPoint = usbInterface.getEndpoint(ei)

                    if (endPoint.direction == UsbConstants.USB_DIR_IN &&
                        (endPoint.type == UsbConstants.USB_ENDPOINT_XFER_BULK || endPoint.type == UsbConstants.USB_ENDPOINT_XFER_INT)
                    ) {
                        readEndpoint = endPoint
                    } else if (endPoint.direction == UsbConstants.USB_DIR_OUT &&
                        (endPoint.type == UsbConstants.USB_ENDPOINT_XFER_BULK || endPoint.type == UsbConstants.USB_ENDPOINT_XFER_INT)
                    ) {
                        writeEndpoint = endPoint
                    }

                    if (readEndpoint != null && writeEndpoint != null) {
                        val connected = connection.claimInterface(usbInterface, true)
                        if (!connected) {
                            Log.w(TAG, "Failed to connect to USB device. Interface could not be claimed.")
                            connection.close()
                            return null
                        }

                        return Port(
                            device,
                            connection,
                            usbInterface,
                            readEndpoint,
                            writeEndpoint
                        )
                    }
                }
            }
            return null
        }
    }
}