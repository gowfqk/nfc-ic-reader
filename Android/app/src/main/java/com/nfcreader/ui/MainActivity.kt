package com.nfcreader.ui

import android.app.PendingIntent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.ServiceConnection
import android.graphics.drawable.GradientDrawable
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.nfcreader.R
import com.nfcreader.service.NfcForegroundService
import com.nfcreader.util.TcpClient
import com.nfcreader.util.TcpServer
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.nfcreader.service.NfcForegroundService
import org.json.JSONObject

/**
 * NFC 读卡器主界面
 * 支持 WiFi 和 ADB 两种连接模式
 */
class MainActivity : AppCompatActivity() {

    // NFC 相关
    private var nfcAdapter: NfcAdapter? = null
    private var pendingIntent: PendingIntent? = null
    private var intentFilters: Array<IntentFilter>? = null

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

    // 网络连接组件
    private var tcpClient: TcpClient? = null
    private var tcpServer: TcpServer? = null

    // 前台服务引用
    private var foregroundService: NfcForegroundService? = null

    // 配置
    private lateinit var prefs: SharedPreferences
    @Volatile private var isConnected = false

    // UID 格式
    private var currentUidHex: String = ""  // 保存原始 HEX，用于切换格式时重新格式化
    private var currentFormat: String = "hex_with_space"

    companion object {
        private const val PREFS_NAME = "nfc_reader_prefs"
        private const val KEY_CONNECTION_MODE = "connection_mode"
        private const val KEY_SERVER_IP = "server_ip"
        private const val KEY_SERVER_PORT = "server_port"
        private const val KEY_ADB_PORT = "adb_port"
        private const val KEY_UID_FORMAT = "uid_format"
    }

    // 前台服务绑定
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

        // 启动前台服务（保持后台 NFC 可用）
        val serviceIntent = Intent(this, NfcForegroundService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }

        handleIntent(intent)

        // 绑定前台服务
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
    }

    private fun initNfc() {
        nfcAdapter = NfcAdapter.getDefaultAdapter(this)

        if (nfcAdapter == null) {
            updateNfcStatus(false, getString(R.string.nfc_not_available))
            return
        }

        if (!nfcAdapter!!.isEnabled) {
            updateNfcStatus(false, getString(R.string.nfc_disabled))
            return
        }

        updateNfcStatus(true, "NFC已启用")

        // PendingIntent for foreground dispatch
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, javaClass).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            flags
        )

        // Intent filters
        val ndefFilter = IntentFilter(NfcAdapter.ACTION_NDEF_DISCOVERED).apply {
            try { addDataType("*/*") } catch (_: IntentFilter.MalformedMimeTypeException) {}
        }
        val tagFilter = IntentFilter(NfcAdapter.ACTION_TAG_DISCOVERED)
        val techFilter = IntentFilter(NfcAdapter.ACTION_TECH_DISCOVERED)
        intentFilters = arrayOf(ndefFilter, tagFilter, techFilter)
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

        // 恢复 UID 格式选择
        currentFormat = prefs.getString(KEY_UID_FORMAT, "hex_with_space") ?: "hex_with_space"
        restoreFormatChip(currentFormat)
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
            if (isConnected) disconnect() else connect()
        }

        // UID 格式切换
        formatChipGroup.setOnCheckedStateChangeListener { _, _ ->
            val chipId = formatChipGroup.checkedChipId
            val newFormat = chipIdToFormat(chipId)
            if (newFormat != currentFormat) {
                currentFormat = newFormat
                prefs.edit().putString(KEY_UID_FORMAT, currentFormat).apply()
                // 重新格式化显示
                if (currentUidHex.isNotEmpty()) {
                    uidText.text = formatUid(currentUidHex, currentFormat)
                }
            }
        }

        // 复制卡号
        copyButton.setOnClickListener {
            if (currentUidHex.isNotEmpty()) {
                val text = formatUid(currentUidHex, currentFormat)
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("UID", text))
                Toast.makeText(this, "已复制: $text", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "还没有读取到卡号", Toast.LENGTH_SHORT).show()
            }
        }

        // 手动应用卡号
        applyManualUidButton.setOnClickListener {
            val input = manualUidInput.text.toString().trim().uppercase()
            if (input.isEmpty()) {
                Toast.makeText(this, "请输入卡号", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            // 验证是否为有效 HEX（4字节=8位，7字节=14位）
            val hexPattern = "^[0-9A-F]{8}$|^[0-9A-F]{14}$".toRegex()
            if (!hexPattern.matches(input)) {
                Toast.makeText(this, "卡号格式不正确（应为8位或14位HEX）", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            // 应用手动输入的卡号
            currentUidHex = input
            val formatted = formatUid(input, currentFormat)
            uidText.text = formatted
            cardTypeText.text = "手动输入"
            hintText.text = "已手动设置卡号"
            foregroundService?.updateNotification("手动设置: $formatted")
            Toast.makeText(this, "卡号已设置", Toast.LENGTH_SHORT).show()
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
        isConnected = connected
        connectionStatusText.text = message
        val color = if (connected) R.color.status_connected else R.color.status_disconnected
        setIndicatorColor(connectionStatusIndicator, color)
        connectButton.text = if (connected) getString(R.string.disconnect) else getString(R.string.connect)
    }

    private fun setIndicatorColor(view: View, colorRes: Int) {
        val drawable = view.background as? GradientDrawable
        drawable?.setColor(ContextCompat.getColor(this, colorRes))
    }

    /**
     * 连接服务器或启动服务
     */
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

            tcpClient = TcpClient(ip, port, object : TcpClient.ConnectionListener {
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
            tcpClient?.connect()

        } else {
            val portStr = adbPortInput.text.toString().trim()
            val port = portStr.toIntOrNull()

            if (port == null || port !in 1..65535) {
                adbPortInput.error = "端口范围 1-65535"
                return
            }

            updateConnectionStatus(false, "正在启动服务器...")

            tcpServer = TcpServer(port, object : TcpServer.ConnectionListener {
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
            tcpServer?.start()
        }
    }

    private fun disconnect() {
        tcpClient?.disconnect()
        tcpClient = null
        tcpServer?.stop()
        tcpServer = null
        updateConnectionStatus(false, getString(R.string.status_disconnected))
    }

    private fun handleIntent(intent: Intent?) {
        if (intent == null) return
        when (intent.action) {
            NfcAdapter.ACTION_TAG_DISCOVERED,
            NfcAdapter.ACTION_TECH_DISCOVERED,
            NfcAdapter.ACTION_NDEF_DISCOVERED -> {
                val tag: Tag? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(NfcAdapter.EXTRA_TAG, Tag::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(NfcAdapter.EXTRA_TAG)
                }
                tag?.let { processTag(it) }
            }
        }
    }

    private fun processTag(tag: Tag) {
        val uid = tag.id
        val uidHex = uid.toHexString().uppercase()
        val cardType = detectCardType(tag)

        currentUidHex = uidHex
        val formattedUid = formatUid(uidHex, currentFormat)

        runOnUiThread {
            uidText.text = formattedUid
            cardTypeText.text = cardType
            hintText.text = "UID已读取"

            // 更新通知显示
            foregroundService?.updateNotification("已读卡: $formattedUid")
        }

        sendUid(uidHex, cardType)
    }

    private fun detectCardType(tag: Tag): String {
        val techList = tag.techList
        return when {
            techList.any { it == "android.nfc.tech.MifareClassic" } -> "Mifare Classic"
            techList.any { it == "android.nfc.tech.MifareUltralight" } -> "Mifare Ultralight"
            techList.any { it == "android.nfc.tech.NfcA" } -> "NFC-A"
            techList.any { it == "android.nfc.tech.NfcB" } -> "NFC-B"
            techList.any { it == "android.nfc.tech.NfcF" } -> "NFC-F"
            techList.any { it == "android.nfc.tech.NfcV" } -> "NFC-V"
            techList.any { it == "android.nfc.tech.IsoDep" } -> "ISO-DEP"
            techList.any { it == "android.nfc.tech.Ndef" } -> "NDEF"
            else -> "Unknown"
        }
    }

    private fun ByteArray.toHexString(): String = joinToString("") { "%02X".format(it) }

    /** 将 HEX UID 按指定格式输出 */
    private fun formatUid(hex: String, format: String): String {
        return when (format) {
            "hex_with_space" -> hex.chunked(2).joinToString(" ")
            "hex_no_space" -> hex
            "hex_reverse" -> hex.chunked(2).reversed().joinToString("")
            "decimal" -> {
                val width = if (hex.length <= 8) 10 else 17
                hex.toLong(16).toString().padStart(width, '0')
            }
            "decimal_reverse" -> {
                val reversed = hex.chunked(2).reversed().joinToString("")
                val width = if (hex.length <= 8) 10 else 17
                reversed.toLong(16).toString().padStart(width, '0')
            }
            "wahid" -> {
                // WAHID = 倒序十进制
                val reversed = hex.chunked(2).reversed().joinToString("")
                val width = if (hex.length <= 8) 10 else 17
                reversed.toLong(16).toString().padStart(width, '0')
            }
            else -> hex.chunked(2).joinToString(" ")
        }
    }

    /** Chip ID -> 格式 key */
    private fun chipIdToFormat(chipId: Int): String = when (chipId) {
        R.id.chipHexSpace -> "hex_with_space"
        R.id.chipHexNoSpace -> "hex_no_space"
        R.id.chipHexReverse -> "hex_reverse"
        R.id.chipDec -> "decimal"
        R.id.chipDecReverse -> "decimal_reverse"
        R.id.chipWahid -> "wahid"
        else -> "hex_with_space"
    }

    /** 格式 key -> Chip ID */
    private fun formatToChipId(format: String): Int = when (format) {
        "hex_with_space" -> R.id.chipHexSpace
        "hex_no_space" -> R.id.chipHexNoSpace
        "hex_reverse" -> R.id.chipHexReverse
        "decimal" -> R.id.chipDec
        "decimal_reverse" -> R.id.chipDecReverse
        "wahid" -> R.id.chipWahid
        else -> R.id.chipHexSpace
    }

    /** 恢复上次的格式选择 */
    private fun restoreFormatChip(format: String) {
        formatChipGroup.check(formatToChipId(format))
    }

    private fun sendUid(uid: String, cardType: String) {
        if (!isConnected) {
            runOnUiThread { hintText.text = getString(R.string.send_failed) }
            return
        }

        val json = JSONObject().apply {
            put("uid", uid)
            put("type", cardType.lowercase().replace(" ", "_"))
            put("timestamp", System.currentTimeMillis() / 1000)
        }
        val message = json.toString() + "\n"

        // 网络操作必须在子线程，否则 Android 抛 NetworkOnMainThreadException
        Thread {
            try {
                if (radioWifi.isChecked) {
                    tcpClient?.send(message)
                } else {
                    tcpServer?.broadcast(message)
                }
                runOnUiThread { hintText.text = getString(R.string.uid_sent) }
            } catch (e: Exception) {
                runOnUiThread { hintText.text = "发送失败: ${e.message}" }
            }
        }.start()
    }

    // ── NFC 前台调度生命周期 ──────────────────────────

    override fun onResume() {
        super.onResume()
        // 进入前台时启用前台调度，拦截 NFC Intent
        nfcAdapter?.enableForegroundDispatch(this, pendingIntent, intentFilters, null)
    }

    override fun onPause() {
        super.onPause()
        // 离开前台时禁用前台调度，恢复系统默认 NFC 分发
        nfcAdapter?.disableForegroundDispatch(this)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        disconnect()
        unbindService(serviceConnection)
    }
}
