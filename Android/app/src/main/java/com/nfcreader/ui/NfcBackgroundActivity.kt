package com.nfcreader.ui

import android.content.Intent
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import com.nfcreader.service.NfcForegroundService
import com.nfcreader.util.NfcConnectionManager
import com.nfcreader.util.NfcTagHelper

/**
 * 透明 Activity：后台 NFC Intent 接收器
 *
 * 当 App 在后台时，系统通过 Manifest Intent Filter 把 NFC 标签事件分发到此 Activity。
 * 处理完标签后立即 finish()，用户几乎感知不到。
 *
 * 关键前提：
 * - 用户已将本 App 设为默认 NFC 处理应用
 * - NfcForegroundService 正在运行（保持 TCP 连接）
 */
class NfcBackgroundActivity : AppCompatActivity() {

    companion object {
        // LSPosed 模块传递的额外数据 Key
        private const val EXTRA_UID = "com.nfcreader.EXTRA_UID"
        private const val EXTRA_CARD_TYPE = "com.nfcreader.EXTRA_CARD_TYPE"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 让 Activity 完全不可见、不可触摸
        window.addFlags(
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
        )

        handleNfcIntent(intent)
        finish()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleNfcIntent(intent)
        finish()
    }

    private fun handleNfcIntent(intent: Intent?) {
        if (intent == null) return

        // 优先处理 LSPosed 模块直接传入的 UID（跨进程 Tag 对象可能失效）
        val hookedUid = intent.getStringExtra(EXTRA_UID)
        val hookedCardType = intent.getStringExtra(EXTRA_CARD_TYPE)

        if (hookedUid != null) {
            // LSPosed Hook 路径：直接拿到了解析好的 UID
            processTagData(hookedUid, hookedCardType ?: "Unknown")
            return
        }

        // 标准 Intent Filter 路径：从 Tag 对象解析
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

    /**
     * 标准路径：从 Tag 对象提取数据
     */
    private fun processTag(tag: Tag) {
        val uidHex = NfcTagHelper.getUidHex(tag)
        val cardType = NfcTagHelper.detectCardType(tag)
        processTagData(uidHex, cardType)
    }

    /**
     * 统一处理标签数据（标准路径和 Hook 路径共用）
     */
    private fun processTagData(uidHex: String, cardType: String) {
        val formattedUid = NfcTagHelper.formatUid(uidHex, NfcConnectionManager.currentFormat)

        // 通过 TCP 发送
        if (NfcConnectionManager.isConnected) {
            val message = NfcTagHelper.createMessage(
                formattedUid, uidHex, NfcConnectionManager.currentFormat, cardType
            )
            Thread {
                NfcConnectionManager.send(message)
            }.start()
        }

        // 更新前台服务通知
        NfcForegroundService.updateNotificationStatic(this, "后台读卡: $formattedUid")
    }
}
