package com.nfcreader.service

import android.nfc.cardemulation.HostApduService
import android.os.Bundle

/**
 * NFC 卡模拟服务
 * 
 * 这个空服务的目的是让 App 可以被设置为默认 NFC 支付应用
 * 成为默认支付应用后，App 拥有更高的 NFC 优先级，可以在后台/锁屏时读卡
 * 
 * 注意：这个服务本身不处理任何卡模拟逻辑，只是为了获得系统的 NFC 优先级
 */
class NfcCardEmulationService : HostApduService() {
    
    companion object {
        // 任意一个 AID，只需要系统识别为合法支付应用即可
        const val AID = "F000000102030405060708"
    }
    
    override fun processCommandApdu(commandApdu: ByteArray, extras: Bundle?): ByteArray? {
        // 不需要处理任何 APDU 命令，只需要让系统知道我们在运行即可
        return null
    }
    
    override fun onDeactivated(reason: Int) {
        // 不需要处理
    }
}
