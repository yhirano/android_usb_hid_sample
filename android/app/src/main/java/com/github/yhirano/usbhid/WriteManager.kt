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
                port.write(data, timeout)
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

    companion object {
        private const val TAG = "UsbHid/WriteManager"
    }
}