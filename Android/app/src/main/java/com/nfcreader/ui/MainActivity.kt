package com.nfcreader.ui

import android.app.PendingIntent
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.graphics.drawable.GradientDrawable
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.nfcreader.R
import com.nfcreader.service.NfcForegroundService
import com.nfcreader.util.TcpClient
import com.nfcreader.util.TcpServer
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

    // 网络连接组件
    private var tcpClient: TcpClient? = null
    private var tcpServer: TcpServer? = null

    // 配置
    private lateinit var prefs: SharedPreferences
    @Volatile private var isConnected = false

    companion object {
        private const val PREFS_NAME = "nfc_reader_prefs"
        private const val KEY_CONNECTION_MODE = "connection_mode"
        private const val KEY_SERVER_IP = "server_ip"
        private const val KEY_SERVER_PORT = "server_port"
        private const val KEY_ADB_PORT = "adb_port"
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

        runOnUiThread {
            uidText.text = formatUidWithSpaces(uidHex)
            cardTypeText.text = cardType
            hintText.text = "UID已读取"
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

    private fun formatUidWithSpaces(hex: String): String = hex.chunked(2).joinToString(" ")

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
    }
}
