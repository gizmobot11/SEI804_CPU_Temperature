package com.jamal2367.cputemperature

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Base64
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast
import androidx.preference.PreferenceManager
import com.jamal2367.cputemperature.MainActivity.ThreadUtil.runOnUiThread
import com.tananaev.adblib.AdbBase64
import com.tananaev.adblib.AdbConnection
import com.tananaev.adblib.AdbCrypto
import com.tananaev.adblib.AdbStream
import java.io.File
import java.lang.ref.WeakReference
import java.net.Socket
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale


class MainActivity : AccessibilityService(), SharedPreferences.OnSharedPreferenceChangeListener {
    private lateinit var sharedPreferences: SharedPreferences

    private var standardKeyCode: Int = KeyEvent.KEYCODE_BOOKMARK
    private var connection: AdbConnection? = null
    private var stream: AdbStream? = null
    private var myAsyncTask: MyAsyncTask? = null
    private val ipAddress = "0.0.0.0"
    private val selectedCodeKey = "selected_code_key"
    private var requestKey= "request_key"
    private var celFahrKey= "celsius_fahrenheit_key"
    private val publicKeyName: String = "public.key"
    private val privateKeyName: String = "private.key"

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
            runOnUiThread {
                Toast.makeText(this, getString(R.string.enable_usb_debugging_first), Toast.LENGTH_LONG).show()
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
        val crypto = readCryptoConfig(filesDir) ?: writeNewCryptoConfig(filesDir)

        if (crypto == null) {
            runOnUiThread {
                Toast.makeText(this, "Failed to generate/load RSA key pair", Toast.LENGTH_LONG).show()
            }
            return
        }

        try {
            val isRequest = sharedPreferences.getBoolean(requestKey, false)
            val isCelFahr = sharedPreferences.getBoolean(celFahrKey, false)

            if (stream == null || connection == null) {
                connection = AdbConnection.create(socket, crypto)
                connection?.connect()
            }

            if (isRequest) {
                if (isCelFahr) {
                    hardwarePropertiesFahrenheit()
                } else {
                    hardwarePropertiesCelsius()
                }
            } else {
                if (isCelFahr) {
                    thermalServiceFahrenheit()
                } else {
                    thermalServiceCelsius()
                }
            }
        } catch (e: InterruptedException) {
            e.printStackTrace()
            Thread.currentThread().interrupt()
        }
    }

    private fun thermalServiceCelsius() {
        val thermalServiceStream = connection?.open("shell:dumpsys thermalservice | awk -F= '/mValue/{printf \"%.1f\\n\", \$2}' | sed -n '2p'")
        val thermalServiceOutputBytes = thermalServiceStream?.read()
        var thermalServiceMessage: String = thermalServiceOutputBytes?.decodeToString() ?: "No output available"

        thermalServiceMessage = thermalServiceMessage.replace("\n", "")

        runOnUiThread {
            Toast.makeText(this, getString(R.string.cpu_temperature_celsius, thermalServiceMessage), Toast.LENGTH_LONG).show()
        }
    }

    private fun hardwarePropertiesCelsius() {
        val hardwarePropertiesStream = connection?.open("shell:dumpsys hardware_properties | grep \"CPU temperatures\" | cut -d \"[\" -f2 | cut -d \"]\" -f1")
        val hardwarePropertiesOutputBytes = hardwarePropertiesStream?.read()
        var hardwarePropertiesMessage: String = hardwarePropertiesOutputBytes?.decodeToString() ?: "No output available"

        val formattedTemperatures = hardwarePropertiesMessage.split(". ")
            .mapNotNull { it.toDoubleOrNull() }
            .joinToString(", ") { "%.1f".format(it).replace(",", ".") }

        hardwarePropertiesMessage = formattedTemperatures.replace("\n", "")

        runOnUiThread {
            Toast.makeText(this, getString(R.string.cpu_temperature_celsius, hardwarePropertiesMessage), Toast.LENGTH_LONG).show()
        }
    }

    private fun thermalServiceFahrenheit() {
        val thermalServiceStream = connection?.open("shell:dumpsys thermalservice | awk -F= '/mValue/{printf \"%.1f\\n\", \$2}' | sed -n '2p'")
        val thermalServiceOutputBytes = thermalServiceStream?.read()
        var thermalServiceMessage: String = thermalServiceOutputBytes?.decodeToString() ?: "No output available"

        thermalServiceMessage = thermalServiceMessage.replace("\n", "")

        val decimalFormat = DecimalFormat("0.0", DecimalFormatSymbols(Locale.ENGLISH))
        val celsiusTemperature = thermalServiceMessage.toDoubleOrNull() ?: 0.0
        val fahrenheitTemperature = celsiusTemperature * 9/5 + 32

        runOnUiThread {
            Toast.makeText(this, getString(R.string.cpu_temperature_fahrenheit, decimalFormat.format(fahrenheitTemperature)), Toast.LENGTH_LONG).show()
        }
    }

    private fun hardwarePropertiesFahrenheit() {
        val hardwarePropertiesStream = connection?.open("shell:dumpsys hardware_properties | grep \"CPU temperatures\" | cut -d \"[\" -f2 | cut -d \"]\" -f1")
        val hardwarePropertiesOutputBytes = hardwarePropertiesStream?.read()
        var hardwarePropertiesMessage: String = hardwarePropertiesOutputBytes?.decodeToString() ?: "No output available"

        hardwarePropertiesMessage = hardwarePropertiesMessage.replace("\n", "")

        val decimalFormat = DecimalFormat("0.0", DecimalFormatSymbols(Locale.ENGLISH))
        val celsiusTemperature = hardwarePropertiesMessage.toDoubleOrNull() ?: 0.0
        val fahrenheitTemperature = celsiusTemperature * 9/5 + 32

        runOnUiThread {
            Toast.makeText(this, getString(R.string.cpu_temperature_fahrenheit, decimalFormat.format(fahrenheitTemperature)), Toast.LENGTH_LONG).show()
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

    private fun readCryptoConfig(dataDir: File?): AdbCrypto? {
        val pubKey = File(dataDir, publicKeyName)
        val privKey = File(dataDir, privateKeyName)

        var crypto: AdbCrypto? = null
        if (pubKey.exists() && privKey.exists()) {
            crypto = try {
                AdbCrypto.loadAdbKeyPair(AndroidBase64(), privKey, pubKey)
            } catch (e: Exception) {
                null
            }
        }

        return crypto
    }

    private fun writeNewCryptoConfig(dataDir: File?): AdbCrypto? {
        val pubKey = File(dataDir, publicKeyName)
        val privKey = File(dataDir, privateKeyName)

        var crypto: AdbCrypto?

        try {
            crypto = AdbCrypto.generateAdbKeyPair(AndroidBase64())
            crypto.saveAdbKeyPair(privKey, pubKey)
        } catch (e: Exception) {
            crypto = null
        }

        return crypto
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

    object ThreadUtil {
        private val handler = Handler(Looper.getMainLooper())

        fun runOnUiThread(action: () -> Unit) {
            if (Looper.myLooper() != Looper.getMainLooper()) {
                handler.post(action)
            } else {
                action.invoke()
            }
        }
    }
}
