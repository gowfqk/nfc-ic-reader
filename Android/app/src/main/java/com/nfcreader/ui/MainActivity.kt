package com.nfcreader.ui

import android.app.PendingIntent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.net.Uri
import android.content.ServiceConnection
import android.graphics.drawable.GradientDrawable
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.nfcreader.R
import com.nfcreader.service.NfcForegroundService
import com.nfcreader.util.NfcConnectionManager
import com.nfcreader.util.NfcTagHelper
import com.nfcreader.util.TcpClient
import com.nfcreader.util.TcpServer
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup

/**
 * NFC 读卡器主界面
 * 支持 WiFi 和 ADB 两种连接模式
 * 前台使用 Reader Mode，后台通过 NfcBackgroundActivity + Intent Filter
 */
class MainActivity : AppCompatActivity(), NfcAdapter.ReaderCallback {

    // NFC 相关
    private var nfcAdapter: NfcAdapter? = null
    private var nfcStateReceiver: android.content.BroadcastReceiver? = null

    // UI 组件
    private lateinit var nfcStatusIndicator: View
    private lateinit var nfcStatusText: TextView
    private lateinit var connectionStatusIndicator: View
    private lateinit var connectionStatusText: TextView
    private lateinit var connectionModeGroup: RadioGroup
    private lateinit var radioWifi: RadioButton
    private lateinit var radioAdb: RadioButton
    private lateinit var wifiSettingsLayout: LinearLayout
    private lateinit var adbSettingsLayout: LinearLayout
    private lateinit var ipInput: EditText
    private lateinit var portInput: EditText
    private lateinit var adbPortInput: EditText
    private lateinit var adbHintText: TextView
    private lateinit var connectButton: Button
    private lateinit var uidText: TextView
    private lateinit var cardTypeText: TextView
    private lateinit var hintText: TextView
    private lateinit var formatChipGroup: ChipGroup
    private lateinit var copyButton: com.google.android.material.button.MaterialButton
    private lateinit var manualUidInput: com.google.android.material.textfield.TextInputEditText
    private lateinit var applyManualUidButton: com.google.android.material.button.MaterialButton
    private lateinit var inputModeGroup: RadioGroup
    private lateinit var radioHexInput: RadioButton
    private lateinit var radioDecInput: RadioButton
    private lateinit var byteOrderGroup: RadioGroup
    private lateinit var radioNormalOrder: RadioButton
    private lateinit var radioReverseOrder: RadioButton
    private lateinit var setDefaultNfcButton: com.google.android.material.button.MaterialButton

    // 前台服务引用
    private var foregroundService: NfcForegroundService? = null

    // 配置
    private lateinit var prefs: SharedPreferences

    // UID 格式
    private var currentUidHex: String = ""

    companion object {
        private const val PREFS_NAME = "nfc_reader_prefs"
        private const val KEY_CONNECTION_MODE = "connection_mode"
        private const val KEY_SERVER_IP = "server_ip"
        private const val KEY_SERVER_PORT = "server_port"
        private const val KEY_ADB_PORT = "adb_port"
        private const val KEY_UID_FORMAT = "uid_format"
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as NfcForegroundService.LocalBinder
            foregroundService = binder.getService()
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            foregroundService = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initViews()
        initNfc()
        loadSettings()
        setupListeners()

        checkBatteryOptimization()

        val serviceIntent = Intent(this, NfcForegroundService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }

        bindService(
            Intent(this, NfcForegroundService::class.java),
            serviceConnection,
            Context.BIND_AUTO_CREATE
        )
    }

    private fun initViews() {
        nfcStatusIndicator = findViewById(R.id.nfcStatusIndicator)
        nfcStatusText = findViewById(R.id.nfcStatusText)
        connectionStatusIndicator = findViewById(R.id.connectionStatusIndicator)
        connectionStatusText = findViewById(R.id.connectionStatusText)
        connectionModeGroup = findViewById(R.id.connectionModeGroup)
        radioWifi = findViewById(R.id.radioWifi)
        radioAdb = findViewById(R.id.radioAdb)
        wifiSettingsLayout = findViewById(R.id.wifiSettingsLayout)
        adbSettingsLayout = findViewById(R.id.adbSettingsLayout)
        ipInput = findViewById(R.id.ipInput)
        portInput = findViewById(R.id.portInput)
        adbPortInput = findViewById(R.id.adbPortInput)
        adbHintText = findViewById(R.id.adbHintText)
        connectButton = findViewById(R.id.connectButton)
        uidText = findViewById(R.id.uidText)
        cardTypeText = findViewById(R.id.cardTypeText)
        hintText = findViewById(R.id.hintText)
        formatChipGroup = findViewById(R.id.formatChipGroup)
        copyButton = findViewById(R.id.copyButton)
        manualUidInput = findViewById(R.id.manualUidInput)
        applyManualUidButton = findViewById(R.id.applyManualUidButton)
        inputModeGroup = findViewById(R.id.inputModeGroup)
        radioHexInput = findViewById(R.id.radioHexInput)
        radioDecInput = findViewById(R.id.radioDecInput)
        byteOrderGroup = findViewById(R.id.byteOrderGroup)
        radioNormalOrder = findViewById(R.id.radioNormalOrder)
        radioReverseOrder = findViewById(R.id.radioReverseOrder)
        setDefaultNfcButton = findViewById(R.id.setDefaultNfcButton)
    }

    private fun initNfc() {
        nfcAdapter = NfcAdapter.getDefaultAdapter(this)
        refreshNfcStatus()
        registerNfcStateReceiver()
    }

    /**
     * 刷新 NFC 状态显示
     */
    private fun refreshNfcStatus() {
        if (nfcAdapter == null) {
            updateNfcStatus(false, getString(R.string.nfc_not_available))
            return
        }

        if (!nfcAdapter!!.isEnabled) {
            updateNfcStatus(false, getString(R.string.nfc_disabled))
            return
        }

        updateNfcStatus(true, "NFC已启用 (前台Reader Mode)")
    }

    /**
     * 注册 NFC 状态变化广播接收器
     * 监听系统 NFC 开关状态变化
     */
    private fun registerNfcStateReceiver() {
        nfcStateReceiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == NfcAdapter.ACTION_ADAPTER_STATE_CHANGED) {
                    val state = intent.getIntExtra(NfcAdapter.EXTRA_ADAPTER_STATE, NfcAdapter.STATE_OFF)
                    when (state) {
                        NfcAdapter.STATE_ON -> {
                            updateNfcStatus(true, "NFC已启用 (前台Reader Mode)")
                        }
                        NfcAdapter.STATE_OFF -> {
                            updateNfcStatus(false, getString(R.string.nfc_disabled))
                        }
                        NfcAdapter.STATE_TURNING_ON -> {
                            updateNfcStatus(false, "NFC正在开启...")
                        }
                        NfcAdapter.STATE_TURNING_OFF -> {
                            updateNfcStatus(false, "NFC正在关闭...")
                        }
                    }
                }
            }
        }
        val filter = IntentFilter(NfcAdapter.ACTION_ADAPTER_STATE_CHANGED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(nfcStateReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(nfcStateReceiver, filter)
        }
    }

    /**
     * 注销 NFC 状态广播接收器
     */
    private fun unregisterNfcStateReceiver() {
        try {
            nfcStateReceiver?.let { unregisterReceiver(it) }
        } catch (_: IllegalArgumentException) {
            // 可能未注册或已注销
        }
        nfcStateReceiver = null
    }

    /**
     * 检查并请求电池优化白名单
     */
    private fun checkBatteryOptimization() {
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
                Toast.makeText(this, "请关闭电池优化以支持后台读卡", Toast.LENGTH_LONG).show()
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
            }
        }
    }

    /**
     * 打开 NFC 设置，引导用户设为默认 NFC 处理应用
     */
    private fun openDefaultNfcSettings() {
        Toast.makeText(this, "请把本应用设为默认 NFC 处理应用", Toast.LENGTH_LONG).show()
        try {
            startActivity(Intent(Settings.ACTION_NFC_SETTINGS))
        } catch (e: Exception) {
            Toast.makeText(this, "无法打开 NFC 设置，请手动设置", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onTagDiscovered(tag: Tag) {
        processTag(tag)
    }

    private fun loadSettings() {
        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)

        val mode = prefs.getString(KEY_CONNECTION_MODE, "wifi") ?: "wifi"
        val serverIp = prefs.getString(KEY_SERVER_IP, "") ?: ""
        val serverPort = prefs.getString(KEY_SERVER_PORT, "8888") ?: "8888"
        val adbPort = prefs.getString(KEY_ADB_PORT, "8888") ?: "8888"

        if (mode == "adb") {
            radioAdb.isChecked = true
            showAdbSettings()
        } else {
            radioWifi.isChecked = true
            showWifiSettings()
        }

        ipInput.setText(serverIp)
        portInput.setText(serverPort)
        adbPortInput.setText(adbPort)

        NfcConnectionManager.currentFormat = prefs.getString(KEY_UID_FORMAT, "hex_with_space") ?: "hex_with_space"
        restoreFormatChip(NfcConnectionManager.currentFormat)
    }

    private fun saveSettings() {
        val mode = if (radioAdb.isChecked) "adb" else "wifi"
        prefs.edit().apply {
            putString(KEY_CONNECTION_MODE, mode)
            putString(KEY_SERVER_IP, ipInput.text.toString().trim())
            putString(KEY_SERVER_PORT, portInput.text.toString().trim())
            putString(KEY_ADB_PORT, adbPortInput.text.toString().trim())
            apply()
        }
    }

    private fun setupListeners() {
        connectionModeGroup.setOnCheckedChangeListener { _, checkedId ->
            when (checkedId) {
                R.id.radioWifi -> showWifiSettings()
                R.id.radioAdb -> showAdbSettings()
            }
        }

        connectButton.setOnClickListener {
            if (NfcConnectionManager.isConnected) disconnect() else connect()
        }

        formatChipGroup.setOnCheckedStateChangeListener { _, _ ->
            val chipId = formatChipGroup.checkedChipId
            val newFormat = chipIdToFormat(chipId)
            if (newFormat != NfcConnectionManager.currentFormat) {
                NfcConnectionManager.currentFormat = newFormat
                prefs.edit().putString(KEY_UID_FORMAT, newFormat).apply()
                if (currentUidHex.isNotEmpty()) {
                    uidText.text = NfcTagHelper.formatUid(currentUidHex, newFormat)
                }
            }
        }

        copyButton.setOnClickListener {
            if (currentUidHex.isNotEmpty()) {
                val text = NfcTagHelper.formatUid(currentUidHex, NfcConnectionManager.currentFormat)
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("UID", text))
                Toast.makeText(this, "已复制: $text", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "还没有读取到卡号", Toast.LENGTH_SHORT).show()
            }
        }

        applyManualUidButton.setOnClickListener {
            val input = manualUidInput.text.toString().trim()
            if (input.isEmpty()) {
                Toast.makeText(this, "请输入卡号", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val hexUid: String
            val inputMode = if (radioHexInput.isChecked) "HEX" else "十进制"
            val byteOrder = if (radioNormalOrder.isChecked) "正序" else "倒序"

            try {
                if (radioHexInput.isChecked) {
                    val hexPattern = "^[0-9A-Fa-f]{8}$|^[0-9A-Fa-f]{14}$".toRegex()
                    if (!hexPattern.matches(input)) {
                        Toast.makeText(this, "HEX 格式错误（应为 8 或 14 位）", Toast.LENGTH_SHORT).show()
                        return@setOnClickListener
                    }
                    hexUid = if (radioNormalOrder.isChecked) {
                        input.uppercase()
                    } else {
                        input.chunked(2).reversed().joinToString("").uppercase()
                    }
                } else {
                    val decValue = input.toLong()
                    val byteCount = if (decValue <= 0xFFFFFFFFL) 4 else 7
                    val hexLength = byteCount * 2
                    val normalHex = decValue.toString(16).uppercase().padStart(hexLength, '0')
                    hexUid = if (radioNormalOrder.isChecked) {
                        normalHex
                    } else {
                        normalHex.chunked(2).reversed().joinToString("")
                    }
                }
            } catch (e: NumberFormatException) {
                Toast.makeText(this, "$inputMode 格式错误", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            currentUidHex = hexUid
            val formatted = NfcTagHelper.formatUid(hexUid, NfcConnectionManager.currentFormat)
            uidText.text = formatted
            cardTypeText.text = "手动输入($inputMode-$byteOrder)"
            hintText.text = "已手动设置卡号"
            foregroundService?.updateNotification("手动设置: $formatted")
            Toast.makeText(this, "卡号已设置 ($inputMode-$byteOrder)", Toast.LENGTH_SHORT).show()
        }

        inputModeGroup.setOnCheckedChangeListener { _, _ ->
            if (radioHexInput.isChecked) {
                manualUidInput.hint = "输入卡号，如 C0DCAD01"
                manualUidInput.inputType = android.text.InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
            } else {
                manualUidInput.hint = "输入卡号，如 323574305"
                manualUidInput.inputType = android.text.InputType.TYPE_CLASS_NUMBER
            }
            manualUidInput.text?.clear()
        }

        byteOrderGroup.setOnCheckedChangeListener { _, _ ->
            manualUidInput.text?.clear()
        }

        setDefaultNfcButton.setOnClickListener {
            openDefaultNfcSettings()
        }
    }

    private fun showWifiSettings() {
        wifiSettingsLayout.visibility = View.VISIBLE
        adbSettingsLayout.visibility = View.GONE
    }

    private fun showAdbSettings() {
        wifiSettingsLayout.visibility = View.GONE
        adbSettingsLayout.visibility = View.VISIBLE
    }

    private fun updateNfcStatus(enabled: Boolean, message: String) {
        nfcStatusText.text = message
        val color = if (enabled) R.color.status_connected else R.color.status_disconnected
        setIndicatorColor(nfcStatusIndicator, color)
    }

    private fun updateConnectionStatus(connected: Boolean, message: String) {
        NfcConnectionManager.isConnected = connected
        connectionStatusText.text = message
        val color = if (connected) R.color.status_connected else R.color.status_disconnected
        setIndicatorColor(connectionStatusIndicator, color)
        connectButton.text = if (connected) getString(R.string.disconnect) else getString(R.string.connect)
    }

    private fun setIndicatorColor(view: View, colorRes: Int) {
        val drawable = view.background as? GradientDrawable
        drawable?.setColor(ContextCompat.getColor(this, colorRes))
    }

    private fun connect() {
        saveSettings()

        if (radioWifi.isChecked) {
            val ip = ipInput.text.toString().trim()
            val portStr = portInput.text.toString().trim()
            val port = portStr.toIntOrNull()

            if (ip.isEmpty()) {
                ipInput.error = "请输入服务器IP"
                return
            }
            if (port == null || port !in 1..65535) {
                portInput.error = "端口范围 1-65535"
                return
            }

            updateConnectionStatus(false, getString(R.string.status_connecting))

            val client = TcpClient(ip, port, object : TcpClient.ConnectionListener {
                override fun onConnected() {
                    runOnUiThread { updateConnectionStatus(true, "已连接到 $ip:$port") }
                }
                override fun onDisconnected() {
                    runOnUiThread { updateConnectionStatus(false, getString(R.string.connection_lost)) }
                }
                override fun onError(message: String) {
                    runOnUiThread { updateConnectionStatus(false, "错误: $message") }
                }
            })
            client.connect()
            NfcConnectionManager.tcpClient = client
            NfcConnectionManager.isWifiMode = true

        } else {
            val portStr = adbPortInput.text.toString().trim()
            val port = portStr.toIntOrNull()

            if (port == null || port !in 1..65535) {
                adbPortInput.error = "端口范围 1-65535"
                return
            }

            updateConnectionStatus(false, "正在启动服务器...")

            val server = TcpServer(port, object : TcpServer.ConnectionListener {
                override fun onClientConnected(address: String) {
                    runOnUiThread { updateConnectionStatus(true, "客户端已连接 ($address)") }
                }
                override fun onClientDisconnected() {
                    runOnUiThread { updateConnectionStatus(false, "客户端已断开") }
                }
                override fun onError(message: String) {
                    runOnUiThread { updateConnectionStatus(false, "服务器错误: $message") }
                }
            })
            server.start()
            NfcConnectionManager.tcpServer = server
            NfcConnectionManager.isWifiMode = false
        }
    }

    private fun disconnect() {
        NfcConnectionManager.tcpClient?.disconnect()
        NfcConnectionManager.tcpClient = null
        NfcConnectionManager.tcpServer?.stop()
        NfcConnectionManager.tcpServer = null
        updateConnectionStatus(false, getString(R.string.status_disconnected))
    }

    private fun processTag(tag: Tag) {
        val uidHex = NfcTagHelper.getUidHex(tag)
        val cardType = NfcTagHelper.detectCardType(tag)

        currentUidHex = uidHex
        val formattedUid = NfcTagHelper.formatUid(uidHex, NfcConnectionManager.currentFormat)

        runOnUiThread {
            uidText.text = formattedUid
            cardTypeText.text = cardType
            hintText.text = "UID已读取"
            foregroundService?.updateNotification("已读卡: $formattedUid")
        }

        sendUid(uidHex, cardType)
    }

    private fun chipIdToFormat(chipId: Int): String = when (chipId) {
        R.id.chipHexSpace -> "hex_with_space"
        R.id.chipHexNoSpace -> "hex_no_space"
        R.id.chipHexReverse -> "hex_reverse"
        R.id.chipDec -> "decimal"
        R.id.chipDecReverse -> "decimal_reverse"
        R.id.chipWahid -> "wahid"
        else -> "hex_with_space"
    }

    private fun formatToChipId(format: String): Int = when (format) {
        "hex_with_space" -> R.id.chipHexSpace
        "hex_no_space" -> R.id.chipHexNoSpace
        "hex_reverse" -> R.id.chipHexReverse
        "decimal" -> R.id.chipDec
        "decimal_reverse" -> R.id.chipDecReverse
        "wahid" -> R.id.chipWahid
        else -> R.id.chipHexSpace
    }

    private fun restoreFormatChip(format: String) {
        formatChipGroup.check(formatToChipId(format))
    }

    private fun sendUid(uid: String, cardType: String) {
        if (!NfcConnectionManager.isConnected) {
            runOnUiThread { hintText.text = getString(R.string.send_failed) }
            return
        }

        val formattedUid = NfcTagHelper.formatUid(uid, NfcConnectionManager.currentFormat)
        val message = NfcTagHelper.createMessage(formattedUid, uid, NfcConnectionManager.currentFormat, cardType)

        Thread {
            val ok = NfcConnectionManager.send(message)
            runOnUiThread {
                hintText.text = if (ok) getString(R.string.uid_sent) else getString(R.string.send_failed)
            }
        }.start()
    }

    // ── NFC Reader Mode 生命周期（仅前台）──────────────────────────

    override fun onResume() {
        super.onResume()
        // 每次回到前台刷新 NFC 状态（处理从设置页返回的情况）
        refreshNfcStatus()
        if (nfcAdapter?.isEnabled == true) {
            val flags = NfcAdapter.FLAG_READER_NFC_A or
                        NfcAdapter.FLAG_READER_NFC_B or
                        NfcAdapter.FLAG_READER_NFC_F or
                        NfcAdapter.FLAG_READER_NFC_V or
                        NfcAdapter.FLAG_READER_NFC_BARCODE or
                        NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK
            val extras = Bundle().apply {
                putInt(NfcAdapter.EXTRA_READER_PRESENCE_CHECK_DELAY, 300)
            }
            nfcAdapter?.enableReaderMode(this, this, flags, extras)
        }
    }

    override fun onPause() {
        super.onPause()
        // 切后台时 Reader Mode 自动失效，由 NfcBackgroundActivity 的 Intent Filter 接管
    }

    override fun onDestroy() {
        super.onDestroy()
        if (nfcAdapter?.isEnabled == true) {
            nfcAdapter?.disableReaderMode(this)
        }
        disconnect()
        try {
            unbindService(serviceConnection)
        } catch (_: Exception) {}
        unregisterNfcStateReceiver()
        // 停止前台服务，移除常驻通知
        stopService(Intent(this, NfcForegroundService::class.java))
    }
}
