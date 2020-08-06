package com.github.yhirano.usbhid

import android.util.Log
import java.io.IOException
import java.util.*

internal class WriteManager(private val port: Port, var listener: Listener? = null) : Runnable {
    interface Listener {
        fun onRunError(e: Exception)
    }

    enum class State {
        STOPPED, RUNNING, STOPPING
    }

    private enum class WorkResult {
        QUEUE_IS_EMPTY,
        WROTE_DATA,
        CAUSE_WRITE_ERROR,
    }

    private class WriteData(val data: ByteArray, val retry: Int?)

    /**
     * USB data write timeout. in milliseconds, 0 is infinite.
    */
    @Suppress("unused")
    var timeout: Int = 30

    /**
     * If there is data to write next, write the data without the thread to sleep.
     * Not recommended for all devices.
     */
    @Suppress("unused")
    var writeDataImmediatelyIfExists = false

    /**
     * Default number of retries on write error.
     * If it is less than or equal to 0, no retries.
     */
    var defaultRetry: Int = 0

    /**
     * Sleeping time before retry bacause write data error.
     */
    @Suppress("unused")
    var sleepMillisSecBeforeRetry: Long = 10

    private var state = State.STOPPED

    private val writeDataQueue = LinkedList<WriteData>()

    override fun run() {
        synchronized(state) {
            check(state == State.STOPPED) { "Already running" }
            state = State.RUNNING
        }

        try {
            while (true) {
                if (state != State.RUNNING) {
                    break
                }

                val workResult = work()

                if (state != State.RUNNING) {
                    break
                }
                if (workResult == WorkResult.QUEUE_IS_EMPTY || !writeDataImmediatelyIfExists) {
                    Thread.sleep(10)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Occurred exception. exception=\"${e.message}\"", e)
            listener?.onRunError(e)
        } finally {
            synchronized(state) {
                state = State.STOPPED
            }
        }
    }

    fun stop() {
        synchronized(state) {
            if (state == State.RUNNING) {
                state = State.STOPPING
            }
        }
    }

    /**
     * @param retry Number of retries at data write error. If null, the number of times specified in [defaultRetry].
     */
    fun writeAsync(data: ByteArray, retry: Int? = null) {
        synchronized(writeDataQueue) {
            writeDataQueue.add(WriteData(data, retry))
        }
    }

    private fun work(): WorkResult {
        return try {
            val writeData = synchronized(writeDataQueue) {
                writeDataQueue.poll()
            }

            if (writeData != null) {
                val data = writeData.data
                val retry = writeData.retry ?: defaultRetry
                write(data, timeout, retry, sleepMillisSecBeforeRetry)
                WorkResult.WROTE_DATA
            } else {
                WorkResult.QUEUE_IS_EMPTY
            }
        } catch (e: IOException) {
            Log.w(TAG, "Occurred exception when USB writing. exception=\"${e.message}\"", e)
            listener?.onRunError(e)
            WorkResult.CAUSE_WRITE_ERROR
        }
    }

    /**
     * Write data to USB device.
     *
     * @param data Writing data
     * @param timeout USB data write timeout. in milliseconds, 0 is infinite.
     * @param retry Number of retries; if this number is less than or equal to 0, no retries.
     * @param sleepMillisSecBeforeRetry Sleeping time before retry.
     * @exception IOException When data is not written to USB after the specified number of retries.
     */
    private fun write(data: ByteArray, timeout: Int, retry: Int, sleepMillisSecBeforeRetry: Long) {
        try {
            port.write(data, timeout)
        } catch (e: IOException) {
            if (retry > 0) {
                Log.d(TAG, "Retry send data because occurred exception when USB writing. data=${data.contentToHexString()}, retry=$retry, exception=\"${e.message}\"")
                if (sleepMillisSecBeforeRetry > 0) {
                    Thread.sleep(sleepMillisSecBeforeRetry)
                }
                write(data, timeout, retry - 1, sleepMillisSecBeforeRetry)
            } else {
                Log.w(TAG, "Failed to retry send data because occurred exception when USB writing. data=${data.contentToHexString()} exception=\"${e.message}\"")
                throw e
            }
        }
    }

    companion object {
        private const val TAG = "UsbHid/WriteManager"

        private fun ByteArray?.contentToHexString(): String {
            if (this == null) return "null"
            val iMax = this.size - 1
            if (iMax == -1) return "[]"

            val b = StringBuilder()
            b.append('[')
            var i = 0
            while (true) {
                b.append(String.format("0x%02X", this[i]))
                if (i == iMax) return b.append(']').toString()
                b.append(", ")
                ++i
            }
        }
    }
}