package com.nfcreader.xposed

import android.app.AndroidAppHelper
import android.content.Intent
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Build
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * LSPosed/Xposed 模块：拦截 NFC 标签分发，强制转发给 NFC Reader 应用
 *
 * 作用：在系统 NFC 服务检测到标签时，Hook 分发流程，
 *       把标签数据转发给 com.nfcreader，实现真正的后台读卡。
 */
class NfcHookModule : IXposedHookLoadPackage {

    companion object {
        private const val TAG = "NfcReaderHook"
        private const val TARGET_PACKAGE = "com.nfcreader"
        private const val TARGET_ACTIVITY = "com.nfcreader.ui.NfcBackgroundActivity"
        private const val EXTRA_UID = "com.nfcreader.EXTRA_UID"
        private const val EXTRA_CARD_TYPE = "com.nfcreader.EXTRA_CARD_TYPE"
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        // 只处理 NFC 相关系统包
        if (!isNfcPackage(lpparam.packageName)) {
            return
        }

        XposedBridge.log("[$TAG] Hooking NFC package: ${lpparam.packageName}")

        // 尝试多种 Hook 点，适配不同 ROM
        hookScreenState(lpparam)          // 关键：解除息屏限制
        hookNfcDispatcher(lpparam)
        hookNfcService(lpparam)
        hookNfcTagDispatch(lpparam)
    }

    /**
     * Hook 点 0：解除息屏限制
     * 核心思路：Hook 系统 NFC 服务中的屏幕状态检查，让 NFC 在息屏时继续工作
     */
    private fun hookScreenState(lpparam: XC_LoadPackage.LoadPackageParam) {
        val nfcServiceClazz = XposedHelpers.findClassIfExists(
            "com.android.nfc.NfcService",
            lpparam.classLoader
        ) ?: return

        // Hook 1: isScreenOn() / isScreenLocked() — 直接返回屏幕开启状态
        hookMethodIfExists(nfcServiceClazz, "isScreenOn") { param ->
            XposedBridge.log("[$TAG] NfcService.isScreenOn() hooked -> return true")
            param.result = true
        }

        hookMethodIfExists(nfcServiceClazz, "isSecureScreenLocked") { param ->
            XposedBridge.log("[$TAG] NfcService.isSecureScreenLocked() hooked -> return false")
            param.result = false
        }

        // Hook 2: applyRouting() — 在应用路由前强制设置屏幕状态为 ON
        hookMethodIfExists(nfcServiceClazz, "applyRouting", Boolean::class.java) { param ->
            XposedBridge.log("[$TAG] NfcService.applyRouting() intercepted")
            // 尝试修改内部 mScreenState 为 SCREEN_STATE_ON
            try {
                val nfcService = param.thisObject
                // AOSP 中 SCREEN_STATE_ON = 1
                XposedHelpers.setIntField(nfcService, "mScreenState", 1)
                XposedBridge.log("[$TAG] Forced mScreenState = SCREEN_STATE_ON")
            } catch (e: Exception) {
                // 字段名可能不同，忽略
            }
        }

        // Hook 3: 修改 mScreenState 字段的读取（部分 ROM 直接读字段）
        try {
            XposedHelpers.findAndHookMethod(
                nfcServiceClazz,
                "onScreenStateChanged",
                Int::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        // 拦截屏幕状态变化，强制保持 SCREEN_STATE_ON
                        param.args[0] = 1 // SCREEN_STATE_ON
                        XposedBridge.log("[$TAG] onScreenStateChanged intercepted -> forced ON")
                    }
                }
            )
            XposedBridge.log("[$TAG] Hooked NfcService.onScreenStateChanged")
        } catch (e: Exception) {
            // 方法不存在，忽略
        }

        // Hook 4: NfcDiscoveryParameters — 让息屏时的发现参数和亮屏一样
        val discoveryClazz = XposedHelpers.findClassIfExists(
            "com.android.nfc.NfcDiscoveryParameters",
            lpparam.classLoader
        )
        discoveryClazz?.let {
            hookMethodIfExists(it, "shouldEnableDiscovery") { param ->
                XposedBridge.log("[$TAG] NfcDiscoveryParameters.shouldEnableDiscovery() -> true")
                param.result = true
            }
        }

        // Hook 5: DeviceHost / NativeNfcManager — 底层芯片接口
        val deviceHostClazz = XposedHelpers.findClassIfExists(
            "com.android.nfc.DeviceHost",
            lpparam.classLoader
        )
        deviceHostClazz?.let {
            hookMethodIfExists(it, "enableDiscovery", Object::class.java, Boolean::class.java) { param ->
                XposedBridge.log("[$TAG] DeviceHost.enableDiscovery() intercepted")
            }
        }

        XposedBridge.log("[$TAG] Screen-state hooks installed")
    }

    /**
     * 判断是否为 NFC 系统包
     */
    private fun isNfcPackage(pkg: String): Boolean {
        val nfcPackages = listOf(
            "com.android.nfc",
            "com.samsung.android.nfc",
            "com.miui.nfc",
            "com.huawei.nfc",
            "com.coloros.nfc",
            "com.oplus.nfc",
            "com.vivo.nfc",
            "com.meizu.nfc",
            "com.google.android.nfc"
        )
        return nfcPackages.any { pkg == it || pkg.startsWith(it) }
    }

    /**
     * Hook 点 1：NfcDispatcher.dispatchTag(Tag)
     * AOSP 标准实现，大部分 ROM 基于此修改
     */
    private fun hookNfcDispatcher(lpparam: XC_LoadPackage.LoadPackageParam) {
        val clazz = XposedHelpers.findClassIfExists(
            "com.android.nfc.NfcDispatcher",
            lpparam.classLoader
        ) ?: return

        // Hook dispatchTag(Tag)
        XposedHelpers.findAndHookMethod(
            clazz,
            "dispatchTag",
            Tag::class.java,
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val tag = param.args[0] as? Tag ?: return
                    val uid = tag.id?.toHex() ?: return

                    XposedBridge.log("[$TAG] NfcDispatcher.dispatchTag intercepted, UID: $uid")

                    // 转发给我们的 App
                    forwardToOurApp(tag, uid)
                }
            }
        )

        XposedBridge.log("[$TAG] Hooked NfcDispatcher.dispatchTag")
    }

    /**
     * Hook 点 2：NfcService 相关方法
     * 部分 OEM 把分发逻辑放在 NfcService 里
     */
    private fun hookNfcService(lpparam: XC_LoadPackage.LoadPackageParam) {
        val clazz = XposedHelpers.findClassIfExists(
            "com.android.nfc.NfcService",
            lpparam.classLoader
        ) ?: return

        // 尝试 hook findAndDispatch
        hookMethodIfExists(clazz, "findAndDispatch", Tag::class.java) { param ->
            val tag = param.args[0] as? Tag ?: return@hookMethodIfExists
            val uid = tag.id?.toHex() ?: return@hookMethodIfExists
            XposedBridge.log("[$TAG] NfcService.findAndDispatch intercepted, UID: $uid")
            forwardToOurApp(tag, uid)
        }

        // 尝试 hook onRemoteEndpointDiscovered (NXP 芯片常用)
        hookMethodIfExists(clazz, "onRemoteEndpointDiscovered", Object::class.java) { param ->
            XposedBridge.log("[$TAG] NfcService.onRemoteEndpointDiscovered called")
        }

        // 尝试 hook sendMessage (部分 ROM 通过 Message 分发)
        hookMethodIfExists(clazz, "sendMessage", Int::class.java, Object::class.java) { param ->
            val what = param.args[0] as? Int
            if (what == 1 || what == 2) { // 通常是 TAG_DISCOVERED 消息
                XposedBridge.log("[$TAG] NfcService.sendMessage(TAG_DISCOVERED) intercepted")
            }
        }
    }

    /**
     * Hook 点 3：更底层的 Tag 分发
     * 有些 ROM 在 NfcTag 或 NativeNfcManager 里处理
     */
    private fun hookNfcTagDispatch(lpparam: XC_LoadPackage.LoadPackageParam) {
        // Hook NativeNfcManager.notifyNdefMessageListeners (如果有)
        val nativeMgr = XposedHelpers.findClassIfExists(
            "com.android.nfc.dhimpl.NativeNfcManager",
            lpparam.classLoader
        )
        nativeMgr?.let {
            hookMethodIfExists(it, "notifyNdefMessageListeners") { param ->
                XposedBridge.log("[$TAG] NativeNfcManager.notifyNdefMessageListeners called")
            }
        }

        // Hook NativeNfcTag (底层 Tag 对象创建)
        val nativeTag = XposedHelpers.findClassIfExists(
            "com.android.nfc.dhimpl.NativeNfcTag",
            lpparam.classLoader
        ) ?: XposedHelpers.findClassIfExists(
            "com.android.nfc.NativeNfcTag",
            lpparam.classLoader
        )

        nativeTag?.let {
            hookMethodIfExists(it, "getUid") { param ->
                val uid = param.result as? ByteArray
                uid?.let {
                    val uidHex = it.toHex()
                    XposedBridge.log("[$TAG] NativeNfcTag.getUid: $uidHex")
                }
            }
        }
    }

    /**
     * 通用 Hook 辅助：如果方法存在就 Hook
     */
    private fun hookMethodIfExists(
        clazz: Class<*>,
        methodName: String,
        vararg parameterTypes: Class<*>,
        hook: (XC_MethodHook.MethodHookParam) -> Unit
    ) {
        try {
            XposedHelpers.findAndHookMethod(
                clazz,
                methodName,
                *parameterTypes,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        hook(param)
                    }
                }
            )
            XposedBridge.log("[$TAG] Hooked ${clazz.simpleName}.$methodName")
        } catch (e: Exception) {
            // 方法不存在，忽略
        }
    }

    /**
     * 将 NFC 标签数据转发给 NFC Reader 应用
     */
    private fun forwardToOurApp(tag: Tag, uid: String) {
        try {
            val context = AndroidAppHelper.currentApplication()
            if (context == null) {
                XposedBridge.log("[$TAG] Cannot get application context")
                return
            }

            // 检测卡类型
            val cardType = detectCardType(tag)

            val intent = Intent().apply {
                setClassName(TARGET_PACKAGE, TARGET_ACTIVITY)
                action = NfcAdapter.ACTION_TAG_DISCOVERED

                // 传递 Tag 对象（跨进程可能部分失效，作为兼容）
                putExtra(NfcAdapter.EXTRA_TAG, tag)
                putExtra(NfcAdapter.EXTRA_ID, tag.id)

                // 同时传递解析后的数据（确保可用）
                putExtra(EXTRA_UID, uid)
                putExtra(EXTRA_CARD_TYPE, cardType)

                // 新任务栈 + 不显示在最近任务 + 清除顶部
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
            }

            context.startActivity(intent)
            XposedBridge.log("[$TAG] Forwarded to $TARGET_PACKAGE, UID=$uid, Type=$cardType")

        } catch (e: Exception) {
            XposedBridge.log("[$TAG] Failed to forward: ${e.message}")
        }
    }

    /**
     * 简单的卡类型检测
     */
    private fun detectCardType(tag: Tag): String {
        val techList = tag.techList
        return when {
            techList.contains("android.nfc.tech.MifareClassic") -> {
                try {
                    val mc = android.nfc.tech.MifareClassic.get(tag)
                    when (mc?.type) {
                        android.nfc.tech.MifareClassic.TYPE_CLASSIC -> "MIFARE Classic"
                        android.nfc.tech.MifareClassic.TYPE_PLUS -> "MIFARE Plus"
                        android.nfc.tech.MifareClassic.TYPE_PRO -> "MIFARE Pro"
                        else -> "MIFARE Classic"
                    }
                } catch (e: Exception) {
                    "MIFARE Classic"
                }
            }
            techList.contains("android.nfc.tech.MifareUltralight") -> "MIFARE Ultralight"
            techList.contains("android.nfc.tech.NfcA") -> "NFC-A (ISO 14443-3A)"
            techList.contains("android.nfc.tech.NfcB") -> "NFC-B (ISO 14443-3B)"
            techList.contains("android.nfc.tech.NfcF") -> "NFC-F (FeliCa)"
            techList.contains("android.nfc.tech.NfcV") -> "NFC-V (ISO 15693)"
            techList.contains("android.nfc.tech.IsoDep") -> "ISO-DEP (ISO 14443-4)"
            techList.contains("android.nfc.tech.Ndef") -> "NDEF"
            else -> "Unknown (${techList.joinToString(", ") { it.substringAfterLast(".") }})"
        }
    }

    /**
     * ByteArray 转 Hex 字符串
     */
    private fun ByteArray.toHex(): String {
        return joinToString("") { "%02X".format(it) }
    }
}
