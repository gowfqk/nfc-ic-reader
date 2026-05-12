package com.nfcreader.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.nfcreader.service.NfcForegroundService

/**
 * 通知栏「退出」按钮的广播接收器
 * 收到广播后停止前台服务并移除通知
 *
 * 关键：必须先 stopForeground() 再 stopSelf()，
 * 直接 stopService() 对前台服务无效（Android 12+）
 */
class StopServiceReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_STOP = "com.nfcreader.ACTION_STOP_SERVICE"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_STOP) {
            // 直接通过实例停止，先退出前台再 stopSelf
            NfcForegroundService.stopCompletely()
        }
    }
}
