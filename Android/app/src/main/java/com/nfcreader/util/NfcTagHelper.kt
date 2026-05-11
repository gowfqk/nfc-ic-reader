package com.nfcreader.util

import android.nfc.Tag
import org.json.JSONObject

/**
 * NFC 标签处理工具
 * 统一 Tag 处理逻辑，供 MainActivity 和 NfcBackgroundActivity 共用
 */
object NfcTagHelper {

    fun getUidHex(tag: Tag): String {
        return tag.id.toHexString().uppercase()
    }

    fun detectCardType(tag: Tag): String {
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

    fun formatUid(hex: String, format: String): String {
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
                val reversed = hex.chunked(2).reversed().joinToString("")
                val width = if (hex.length <= 8) 10 else 17
                reversed.toLong(16).toString().padStart(width, '0')
            }
            else -> hex.chunked(2).joinToString(" ")
        }
    }

    fun createMessage(formattedUid: String, rawUid: String, format: String, cardType: String): String {
        val json = JSONObject().apply {
            put("uid", formattedUid)
            put("rawUid", rawUid)
            put("format", format)
            put("type", cardType.lowercase().replace(" ", "_"))
            put("timestamp", System.currentTimeMillis() / 1000)
        }
        return json.toString() + "\n"
    }

    private fun ByteArray.toHexString(): String = joinToString("") { "%02X".format(it) }
}
