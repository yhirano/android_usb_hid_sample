package com.github.yhirano.usbhid

import android.util.Log
import java.io.IOException
import java.nio.ByteBuffer

class ReadManager(private val port: Port, var listener: Listener? = null) : Runnable {
    interface Listener {
        fun onNewData(data: ByteArray)

        fun onRunError(e: Exception)
    }

    enum class State {
        STOPPED, RUNNING, STOPPING
    }

    var timeout: Int = 30

    private var state = State.STOPPED

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

                work()

                if (state != State.RUNNING) {
                    break
                }
                Thread.sleep(10)
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

    private fun work() {
        try {
            val length = port.read(readBuffer.array(), timeout)
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
    }

    companion object {
        private const val TAG = "UsbHid/ReadManager"
    }
}