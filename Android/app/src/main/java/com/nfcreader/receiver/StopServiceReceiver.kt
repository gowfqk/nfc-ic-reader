package com.nfcreader.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.nfcreader.service.NfcForegroundService

/**
 * 通知栏「退出」按钮的广播接收器
 * 收到广播后停止前台服务并移除通知
 */
class StopServiceReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_STOP = "com.nfcreader.ACTION_STOP_SERVICE"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_STOP) {
            context.stopService(Intent(context, NfcForegroundService::class.java))
        }
    }
}
