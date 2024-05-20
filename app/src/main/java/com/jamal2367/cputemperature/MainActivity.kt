package com.jamal2367.cputemperature

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast
import android.util.Base64
import com.tananaev.adblib.AdbBase64
import com.tananaev.adblib.AdbConnection
import com.tananaev.adblib.AdbCrypto
import com.tananaev.adblib.AdbStream
import androidx.preference.PreferenceManager
import java.lang.ref.WeakReference
import java.net.Socket


class MainActivity : AccessibilityService(), SharedPreferences.OnSharedPreferenceChangeListener {
    private lateinit var sharedPreferences: SharedPreferences

    private var standardKeyCode: Int = KeyEvent.KEYCODE_BOOKMARK
    private var connection: AdbConnection? = null
    private var stream: AdbStream? = null
    private var myAsyncTask: MyAsyncTask? = null
    private val ipAddress = "0.0.0.0"
    private val selectedCodeKey = "selected_code_key"

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
    }

    override fun onInterrupt() {
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {

        if (event.keyCode == standardKeyCode) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                return true
            } else if (event.action == KeyEvent.ACTION_UP) {
                onKeyCE()
            }
            return true
        }

        return super.onKeyEvent(event)
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d("TAG", "onServiceConnected")

        if (!isUsbDebuggingEnabled()) {
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(this@MainActivity, getString(R.string.enable_usb_debugging_first), Toast.LENGTH_LONG).show()
            }
            openDeveloperSettings()
        }

        updateOverlayKeyButton()
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        if (key == selectedCodeKey) {
            updateOverlayKeyButton()
        }
    }

    private fun updateOverlayKeyButton() {
        sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this)

        val keyCodesArray = resources.getStringArray(R.array.key_codes)
        val selectedKeyCodeString = sharedPreferences.getString(selectedCodeKey, keyCodesArray[0])

        val index = keyCodesArray.indexOf(selectedKeyCodeString)

        sharedPreferences.registerOnSharedPreferenceChangeListener(this)

        standardKeyCode = when (index) {
            0 -> KeyEvent.KEYCODE_BOOKMARK
            1 -> KeyEvent.KEYCODE_GUIDE
            2 -> KeyEvent.KEYCODE_PROG_RED
            3 -> KeyEvent.KEYCODE_PROG_GREEN
            4 -> KeyEvent.KEYCODE_PROG_YELLOW
            5 -> KeyEvent.KEYCODE_PROG_BLUE
            6 -> KeyEvent.KEYCODE_0
            7 -> KeyEvent.KEYCODE_1
            8 -> KeyEvent.KEYCODE_2
            9 -> KeyEvent.KEYCODE_3
            10 -> KeyEvent.KEYCODE_4
            11 -> KeyEvent.KEYCODE_5
            12 -> KeyEvent.KEYCODE_6
            13 -> KeyEvent.KEYCODE_7
            14 -> KeyEvent.KEYCODE_8
            15 -> KeyEvent.KEYCODE_9
            else -> KeyEvent.KEYCODE_BOOKMARK
        }

        sharedPreferences.edit().putString(selectedCodeKey, selectedKeyCodeString).apply()
    }

    private fun onKeyCE() {
        connection = null
        stream = null

        myAsyncTask?.cancel()
        myAsyncTask = MyAsyncTask(this)
        myAsyncTask?.execute(ipAddress)
    }

    fun adbCommander(ip: String?) {
        val socket = Socket(ip, 5555)
        val generateAdbKeyPair = AdbCrypto.generateAdbKeyPair(AndroidBase64())

        try {
            if (stream == null || connection == null) {
                connection = AdbConnection.create(socket, generateAdbKeyPair)
                connection?.connect()
            }

            val androidVersionStream = connection?.open("shell:dumpsys thermalservice | awk -F= '/mValue/{printf \"%.1f\\n\", \$2}' | sed -n '2p'")
            val androidVersionOutputBytes = androidVersionStream?.read()
            var androidVersionMessage: String = androidVersionOutputBytes?.decodeToString() ?: "No output available"

            androidVersionMessage = androidVersionMessage.replace("\n", "")

            Handler(Looper.getMainLooper()).post {
                Toast.makeText(this@MainActivity, getString(R.string.temperature, androidVersionMessage), Toast.LENGTH_LONG).show()
            }
        } catch (e: InterruptedException) {
            e.printStackTrace()
            Thread.currentThread().interrupt()
        }
    }

    private fun openDeveloperSettings() {
        val intent = Intent(Settings.ACTION_SETTINGS)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
        startActivity(intent)
    }

    private fun isUsbDebuggingEnabled(): Boolean {
        return Settings.Global.getInt(contentResolver, Settings.Global.ADB_ENABLED, 0) == 1
    }

    override fun onDestroy() {
        super.onDestroy()
        PreferenceManager.getDefaultSharedPreferences(this)
            .unregisterOnSharedPreferenceChangeListener(this)
    }

    class MyAsyncTask internal constructor(context: MainActivity) {
        private val activityReference: WeakReference<MainActivity> = WeakReference(context)
        private var thread: Thread? = null

        fun execute(ip: String?) {
            thread = Thread {
                val activity = activityReference.get()
                activity?.adbCommander(ip)

                if (Thread.interrupted()) {
                    return@Thread
                }
            }
            thread?.start()
        }

        fun cancel() {
            thread?.interrupt()
        }
    }

    class AndroidBase64 : AdbBase64 {
        override fun encodeToString(bArr: ByteArray): String {
            return Base64.encodeToString(bArr, Base64.NO_WRAP)
        }
    }
}
