package com.github.yhirano.usbhid

import android.hardware.usb.*
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
        connection.close()
    }


    fun read(dest: ByteArray, timeout: Int): Int {
        val length: Int
        try {
            connection.claimInterface(usbInterface, true)
            length = connection.bulkTransfer(readEndpoint, dest, dest.size, timeout)
        } finally {
            connection.releaseInterface(usbInterface)
        }
        return length
    }

    /**
     * @exception IOException Failed to write data to USB.
     */
    fun write(data: ByteArray, timeout: Int) {
        try {
            connection.claimInterface(usbInterface, true)

            val length = connection.bulkTransfer(writeEndpoint, data, data.size, timeout)
            if (length <= 0) {
                throw IOException("Failed to write data to USB. status=$length")
            }
        } finally {
            connection.releaseInterface(usbInterface)
        }
    }

    companion object {
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
                        return Port(device, connection, usbInterface, readEndpoint, writeEndpoint)
                    }
                }
            }
            return null
        }
    }
}