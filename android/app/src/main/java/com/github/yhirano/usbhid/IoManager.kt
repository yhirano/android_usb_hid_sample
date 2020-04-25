package com.github.yhirano.usbhid

import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.util.*

class IoManager(private val port: Port, var listener: Listener? = null) : Runnable {
    interface Listener {
        fun onNewData(data: ByteArray)

        fun onRunError(e: Exception)
    }

    enum class State {
        STOPPED, RUNNING, STOPPING
    }

    private enum class WorkResult {
        WRITE_DATA_QUEUE_IS_EMPTY,
        WRITE_DATA_QUEUE_IS_NOT_EMPTY,
        CAUSE_WRITE_ERROR
    }

    var writeDataAllTogether = false

    var readTimeout: Int = 5
    var writeTimeout: Int = 5

    private var state = State.STOPPED

    private val writeDataQueue = LinkedList<ByteArray>()
    private val readBuffer = ByteBuffer.allocate(4096)

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
                if (workResult == WorkResult.WRITE_DATA_QUEUE_IS_EMPTY) {
                    Thread.sleep(1)
                }
            }
        } catch (e: java.lang.Exception) {
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
        try {
            val length = port.read(readBuffer.array(), readTimeout)
            if (length > 0) {
                val data = ByteArray(length)
                readBuffer.get(data, 0, length)
                listener?.onNewData(data)
            }
        } catch (e: IOException) {
            Log.w(TAG, "Occurred exception when USB reading. exception=\"${e.message}\"", e)
            listener?.onRunError(e)
        } finally {
            readBuffer.clear()
        }

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
                port.write(data, writeTimeout)
                WorkResult.WRITE_DATA_QUEUE_IS_NOT_EMPTY
            } else {
                WorkResult.WRITE_DATA_QUEUE_IS_EMPTY
            }
        } catch (e: IOException) {
            Log.w(TAG, "Occurred exception when USB writing. exception=\"${e.message}\"", e)
            listener?.onRunError(e)
            WorkResult.CAUSE_WRITE_ERROR
        }
    }

    companion object {
        private const val TAG = "UsbHid"
    }
}