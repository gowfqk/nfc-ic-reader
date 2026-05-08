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
    @Volatile private var isRunning = false
    private val lock = Any()
    
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
                println("[TcpClient] 尝试连接 $host:$port")
                val sock = Socket(host, port)
                val r = BufferedReader(InputStreamReader(sock.getInputStream()))
                val w = BufferedWriter(OutputStreamWriter(sock.getOutputStream()))
                
                synchronized(lock) {
                    socket = sock
                    reader = r
                    writer = w
                    isRunning = true
                }
                
                println("[TcpClient] 连接成功，socket=${sock.isConnected}")
                listener.onConnected()
                
                // 监听服务器消息（阻塞）
                listen(r)
                
            } catch (e: UnknownHostException) {
                println("[TcpClient] 无法解析主机: ${e.message}")
                listener.onError("无法解析主机地址: ${e.message}")
            } catch (e: SocketException) {
                println("[TcpClient] 连接异常: ${e.message}")
                listener.onError("连接失败: ${e.message}")
            } catch (e: Exception) {
                println("[TcpClient] 未知异常: ${e.message}")
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
    private fun listen(r: BufferedReader) {
        try {
            while (isRunning) {
                val message = r.readLine()
                if (message == null) {
                    println("[TcpClient] 服务器关闭连接")
                    break
                }
            }
        } catch (e: SocketException) {
            println("[TcpClient] 连接中断: ${e.message}")
        } catch (e: Exception) {
            if (isRunning) {
                println("[TcpClient] 读取错误: ${e.message}")
                listener.onError("读取数据错误: ${e.message}")
            }
        }
    }
    
    /**
     * 发送消息（线程安全）
     */
    fun send(message: String) {
        val w: BufferedWriter?
        synchronized(lock) {
            if (!isRunning) {
                println("[TcpClient] 发送失败: 未连接")
                listener.onError("发送数据失败: 未连接")
                return
            }
            w = writer
        }
        if (w == null) {
            println("[TcpClient] 发送失败: writer 为 null")
            listener.onError("发送数据失败: writer 为 null")
            return
        }
        try {
            println("[TcpClient] 发送数据: ${message.trim()}")
            synchronized(lock) {
                w.write(message)
                w.flush()
            }
            println("[TcpClient] 发送成功")
        } catch (e: Exception) {
            println("[TcpClient] 发送失败: ${e.javaClass.simpleName}: ${e.message}")
            listener.onError("发送数据失败: ${e.message ?: e.javaClass.simpleName}")
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
        synchronized(lock) {
            try {
                writer?.close()
            } catch (_: Exception) {}
            try {
                reader?.close()
            } catch (_: Exception) {}
            try {
                socket?.close()
            } catch (_: Exception) {}
            writer = null
            reader = null
            socket = null
        }
    }
    
    /**
     * 检查是否已连接
     */
    fun isConnected(): Boolean {
        synchronized(lock) {
            return socket?.isConnected == true && socket?.isClosed == false && isRunning
        }
    }
}
