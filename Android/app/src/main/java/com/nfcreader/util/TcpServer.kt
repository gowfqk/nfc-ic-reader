package com.nfcreader.util

import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException

/**
 * TCP 服务器工具类
 * 用于 ADB 模式下作为 TCP Server 监听连接
 */
class TcpServer(
    private val port: Int,
    private val listener: ConnectionListener
) {
    
    private var serverSocket: ServerSocket? = null
    private var clientSocket: Socket? = null
    private var reader: BufferedReader? = null
    private var writer: BufferedWriter? = null
    private var isRunning = false
    
    /**
     * 连接监听器接口
     */
    interface ConnectionListener {
        fun onClientConnected(address: String)
        fun onClientDisconnected()
        fun onError(message: String)
    }
    
    /**
     * 启动服务器
     */
    fun start() {
        Thread {
            try {
                serverSocket = ServerSocket(port)
                isRunning = true
                
                // 监听客户端连接
                while (isRunning) {
                    try {
                        clientSocket = serverSocket?.accept()
                        clientSocket?.let { socket ->
                            reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                            writer = BufferedWriter(OutputStreamWriter(socket.getOutputStream()))
                            
                            listener.onClientConnected(socket.inetAddress.hostAddress ?: "unknown")
                            
                            // 监听客户端消息
                            listenClient()
                        }
                    } catch (e: SocketException) {
                        if (isRunning) {
                            listener.onError("接受连接失败: ${e.message}")
                        }
                    }
                }
                
            } catch (e: Exception) {
                listener.onError("服务器错误: ${e.message}")
            } finally {
                cleanup()
            }
        }.start()
    }
    
    /**
     * 监听客户端消息
     */
    private fun listenClient() {
        try {
            while (isRunning) {
                val message = reader?.readLine()
                if (message == null) {
                    // 客户端关闭连接
                    break
                }
                // 可以处理客户端消息
            }
        } catch (e: SocketException) {
            // 连接被重置
        } catch (e: Exception) {
            if (isRunning) {
                listener.onError("读取数据错误: ${e.message}")
            }
        } finally {
            cleanup()
            listener.onClientDisconnected()
        }
    }
    
    /**
     * 发送消息给客户端
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
     * 广播消息给所有客户端
     */
    fun broadcast(message: String) {
        send(message)
    }
    
    /**
     * 停止服务器
     */
    fun stop() {
        isRunning = false
        try {
            serverSocket?.close()
        } catch (e: Exception) {
            // 忽略关闭错误
        }
        cleanup()
    }
    
    /**
     * 清理资源
     */
    private fun cleanup() {
        try {
            writer?.close()
            reader?.close()
            clientSocket?.close()
        } catch (e: Exception) {
            // 忽略清理错误
        }
        writer = null
        reader = null
        clientSocket = null
    }
    
    /**
     * 检查是否有客户端连接
     */
    fun hasClient(): Boolean {
        return clientSocket?.isConnected == true && !clientSocket!!.isClosed
    }
}
