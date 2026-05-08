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
    @Volatile private var isRunning = false
    private val lock = Any()
    
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
                println("[TcpServer] 启动服务器，端口: $port")
                serverSocket = ServerSocket(port)
                isRunning = true
                println("[TcpServer] 服务器已启动，等待连接...")
                
                while (isRunning) {
                    try {
                        val client = serverSocket?.accept() ?: break
                        println("[TcpServer] 客户端已连接: ${client.inetAddress.hostAddress}")
                        
                        val r = BufferedReader(InputStreamReader(client.getInputStream()))
                        val w = BufferedWriter(OutputStreamWriter(client.getOutputStream()))
                        
                        synchronized(lock) {
                            // 关闭旧连接
                            try { clientSocket?.close() } catch (_: Exception) {}
                            try { reader?.close() } catch (_: Exception) {}
                            try { writer?.close() } catch (_: Exception) {}
                            
                            clientSocket = client
                            reader = r
                            writer = w
                        }
                        
                        listener.onClientConnected(client.inetAddress.hostAddress ?: "unknown")
                        
                        // 监听客户端消息（阻塞）
                        listenClient(r)
                        
                    } catch (e: SocketException) {
                        if (isRunning) {
                            println("[TcpServer] 接受连接失败: ${e.message}")
                            listener.onError("接受连接失败: ${e.message}")
                        }
                    }
                }
                
            } catch (e: Exception) {
                println("[TcpServer] 服务器错误: ${e.message}")
                listener.onError("服务器错误: ${e.message}")
            } finally {
                cleanup()
            }
        }.start()
    }
    
    /**
     * 监听客户端消息
     */
    private fun listenClient(r: BufferedReader) {
        try {
            while (isRunning) {
                val message = r.readLine()
                if (message == null) {
                    println("[TcpServer] 客户端断开连接")
                    break
                }
            }
        } catch (e: SocketException) {
            println("[TcpServer] 连接中断: ${e.message}")
        } catch (e: Exception) {
            if (isRunning) {
                println("[TcpServer] 读取错误: ${e.message}")
                listener.onError("读取数据错误: ${e.message}")
            }
        } finally {
            synchronized(lock) {
                try { clientSocket?.close() } catch (_: Exception) {}
                clientSocket = null
                writer = null
                reader = null
            }
            listener.onClientDisconnected()
        }
    }
    
    /**
     * 发送消息给客户端（线程安全）
     */
    fun send(message: String) {
        val w: BufferedWriter?
        synchronized(lock) {
            w = writer
        }
        if (w == null) {
            println("[TcpServer] 发送失败: 无客户端连接")
            listener.onError("发送数据失败: 无客户端连接")
            return
        }
        try {
            println("[TcpServer] 发送数据: ${message.trim()}")
            synchronized(lock) {
                w.write(message)
                w.flush()
            }
            println("[TcpServer] 发送成功")
        } catch (e: Exception) {
            println("[TcpServer] 发送失败: ${e.javaClass.simpleName}: ${e.message}")
            listener.onError("发送数据失败: ${e.message ?: e.javaClass.simpleName}")
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
        synchronized(lock) {
            try { writer?.close() } catch (_: Exception) {}
            try { reader?.close() } catch (_: Exception) {}
            try { clientSocket?.close() } catch (_: Exception) {}
            try { serverSocket?.close() } catch (_: Exception) {}
            writer = null
            reader = null
            clientSocket = null
            serverSocket = null
        }
    }
    
    /**
     * 检查是否有客户端连接
     */
    fun hasClient(): Boolean {
        synchronized(lock) {
            return clientSocket?.isConnected == true && clientSocket?.isClosed == false
        }
    }
}
