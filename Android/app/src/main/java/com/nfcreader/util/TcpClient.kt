package com.nfcreader.util

import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.Socket
import java.net.SocketException
import java.net.UnknownHostException

/**
 * TCP 客户端工具类
 * 用于 WiFi 模式下作为 TCP Client 连接到 Windows 电脑
 */
class TcpClient(
    private val host: String,
    private val port: Int,
    private val listener: ConnectionListener
) {
    
    private var socket: Socket? = null
    private var reader: BufferedReader? = null
    private var writer: BufferedWriter? = null
    private var isRunning = false
    
    /**
     * 连接监听器接口
     */
    interface ConnectionListener {
        fun onConnected()
        fun onDisconnected()
        fun onError(message: String)
    }
    
    /**
     * 连接到服务器
     */
    fun connect() {
        Thread {
            try {
                socket = Socket(host, port)
                reader = BufferedReader(InputStreamReader(socket!!.getInputStream()))
                writer = BufferedWriter(OutputStreamWriter(socket!!.getOutputStream()))
                
                isRunning = true
                listener.onConnected()
                
                // 监听服务器消息
                listen()
                
            } catch (e: UnknownHostException) {
                listener.onError("无法解析主机地址: ${e.message}")
            } catch (e: SocketException) {
                listener.onError("连接失败: ${e.message}")
            } catch (e: Exception) {
                listener.onError("连接错误: ${e.message}")
            } finally {
                cleanup()
                listener.onDisconnected()
            }
        }.start()
    }
    
    /**
     * 监听服务器消息
     */
    private fun listen() {
        try {
            while (isRunning) {
                val message = reader?.readLine()
                if (message == null) {
                    // 服务器关闭连接
                    break
                }
                // 可以处理服务器响应
            }
        } catch (e: SocketException) {
            // 连接被重置
        } catch (e: Exception) {
            if (isRunning) {
                listener.onError("读取数据错误: ${e.message}")
            }
        }
    }
    
    /**
     * 发送消息
     */
    fun send(message: String) {
        try {
            writer?.write(message)
            writer?.flush()
        } catch (e: Exception) {
            listener.onError("发送数据失败: ${e.message}")
        }
    }
    
    /**
     * 断开连接
     */
    fun disconnect() {
        isRunning = false
        cleanup()
    }
    
    /**
     * 清理资源
     */
    private fun cleanup() {
        try {
            writer?.close()
            reader?.close()
            socket?.close()
        } catch (e: Exception) {
            // 忽略清理错误
        }
        writer = null
        reader = null
        socket = null
    }
    
    /**
     * 检查是否已连接
     */
    fun isConnected(): Boolean {
        return socket?.isConnected == true && !socket!!.isClosed
    }
}
