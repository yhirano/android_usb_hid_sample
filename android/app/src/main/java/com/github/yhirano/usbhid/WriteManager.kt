package com.github.yhirano.usbhid

import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.*

class WriteManager(private val port: Port, var listener: Listener? = null) : Runnable {
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

    var writeDataAllTogether = false

    var timeout: Int = 10

    /**
     * Retry send data count.
     * If it is less than or equal to 0, no retries.
     */
    var retry: Int = 0

    private var state = State.STOPPED

    private val writeDataQueue = LinkedList<ByteArray>()

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
                if (workResult == WorkResult.QUEUE_IS_EMPTY) {
                    Thread.sleep(1)
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

    fun writeAsync(data: ByteArray) {
        synchronized(writeDataQueue) {
            writeDataQueue.add(data)
        }
    }

    private fun work(): WorkResult {
        return try {
            val data = if (writeDataAllTogether) {
                synchronized(writeDataQueue) {
                    val outStream = ByteArrayOutputStream()
                    while (true) {
                        val data = writeDataQueue.poll() ?: break
                        outStream.write(data)
                    }
                    return@synchronized if (outStream.size() > 0) {
                        outStream.toByteArray()
                    } else {
                        null
                    }
                }
            } else {
                synchronized(writeDataQueue) {
                    writeDataQueue.poll()
                }
            }

            if (data != null) {
                write(data, timeout, retry)
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
     * @param retry Number of retries; if this number is less than or equal to 0, no retries.
     * @exception IOException When data is not written to USB after the specified number of retries.
     */
    private fun write(data: ByteArray, timeout: Int, retry: Int) {
        try {
            port.write(data, timeout)
        } catch (e: IOException) {
            if (retry > 0) {
                Log.d(
                    TAG,
                    "Retry send data because occurred exception when USB writing. data=${data.contentToHexString()}, retry=$retry, exception=\"${e.message}\""
                )
                write(data, timeout, retry - 1)
            } else {
                Log.w(
                    TAG,
                    "Failed to retry send data because occurred exception when USB writing. data=${data.contentToHexString()} exception=\"${e.message}\""
                )
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