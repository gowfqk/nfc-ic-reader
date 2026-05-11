package com.nfcreader.util

/**
 * NFC 连接状态共享管理器
 * 供 MainActivity 和 NfcBackgroundActivity 共同访问 TCP 连接和格式设置
 */
object NfcConnectionManager {

    @Volatile
    var tcpClient: TcpClient? = null

    @Volatile
    var tcpServer: TcpServer? = null

    @Volatile
    var isConnected: Boolean = false

    @Volatile
    var isWifiMode: Boolean = true

    @Volatile
    var currentFormat: String = "hex_with_space"

    /**
     * 通过当前 TCP 连接发送数据
     * @return true 发送成功（或至少已提交），false 发送失败
     */
    fun send(message: String): Boolean {
        return try {
            if (isWifiMode) {
                tcpClient?.send(message) ?: return false
            } else {
                tcpServer?.broadcast(message) ?: return false
            }
            true
        } catch (e: Exception) {
            false
        }
    }
}
