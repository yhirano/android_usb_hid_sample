package com.github.yhirano.usb_hid_sample.android

import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatToggleButton
import com.github.yhirano.usbhid.UsbHid

class MainActivity : AppCompatActivity() {
    private val led1Button by lazy {
        findViewById<AppCompatToggleButton>(R.id.led1_button)
    }

    private val led2Button by lazy {
        findViewById<AppCompatToggleButton>(R.id.led2_button)
    }

    private val led3Button by lazy {
        findViewById<AppCompatToggleButton>(R.id.led3_button)
    }

    private val led4Button by lazy {
        findViewById<AppCompatToggleButton>(R.id.led4_button)
    }

    private lateinit var usbHid : UsbHid

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        usbHid = UsbHid(applicationContext, 0x1234, 0x0006, object : UsbHid.Listener {
            override fun onRunError(e: Exception) {
                Log.w(TAG, "Occurred USB HID error.", e)
            }

            override fun onNewData(data: ByteArray) {
                Log.i(TAG, "Receive data from USB HID. data=${data.contentToString()}")
                runOnUiThread {
                    Toast
                        .makeText(this@MainActivity, data.contentToString(), Toast.LENGTH_SHORT)
                        .show()
                }
            }
        }).apply {
            openDevice()
        }

        led1Button.setOnCheckedChangeListener { _, _ -> sendLedsCommandToDevice() }
        led2Button.setOnCheckedChangeListener { _, _ -> sendLedsCommandToDevice() }
        led3Button.setOnCheckedChangeListener { _, _ -> sendLedsCommandToDevice() }
        led4Button.setOnCheckedChangeListener { _, _ -> sendLedsCommandToDevice() }
    }

    override fun onDestroy() {
        super.onDestroy()
        usbHid.closeDevice()
    }

    private fun sendLedsCommandToDevice() {
        val command = ByteArray(4)
        command[0] = if (led1Button.isChecked) 1 else 0
        command[1] = if (led2Button.isChecked) 1 else 0
        command[2] = if (led3Button.isChecked) 1 else 0
        command[3] = if (led4Button.isChecked) 1 else 0
        usbHid.write(command)
    }

    companion object {
        private const val TAG = "MainActivity"
    }
}
