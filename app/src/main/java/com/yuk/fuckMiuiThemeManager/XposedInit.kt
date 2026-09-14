package com.yuk.fuckMiuiThemeManager

import com.github.kyuubiran.ezxhelper.ClassUtils
import com.github.kyuubiran.ezxhelper.finders.FieldFinder
import com.github.kyuubiran.ezxhelper.finders.MethodFinder
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import io.luckypray.dexkit.DexKitBridge
import io.luckypray.dexkit.builder.BatchFindArgs
import miui.drm.DrmManager
import miui.drm.ThemeReceiver
import java.io.File

/**
 * 模块入口。
 *
 * 作用域（见 res/values/array.xml）：
 * - com.android.thememanager 主题壁纸
 * - com.miui.personalassistant 智能助理（背屏/桌面小组件）
 * - android 系统框架
 * - com.miui.home 桌面
 *
 * 每个分支都独立 try/catch：某个宿主版本对不上只会让该分支失效，不会连累其它分支。
 *
 * 注意：类/方法名来自 HyperOS / MIUI 14 的主题管理器，宿主改版后
 * MethodFinder 会抛 NoSuchElementException，被对应分支的 runCatching 吞掉，
 * 此时需要用 DexKit 重新定位（见 hookObfuscatedByDexKit）。
 */
class XposedInit : IXposedHookLoadPackage {

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        LogHelper.d(lpparam.packageName)

        when (lpparam.packageName) {
            "android" -> hookSystemFramework()
            "com.miui.personalassistant" -> hookPersonalAssistant(lpparam)
            "com.android.thememanager" -> hookThemeManager(lpparam)
            "com.miui.home" -> hookHome(lpparam)
        }
    }

    // ---------------------------------------------------------------- android
    // 系统框架：只在 validateTheme 校验期间临时放行 DrmManager.isLegal，
    // 校验结束立刻摘钩，避免影响系统其它 DRM 逻辑。
    private fun hookSystemFramework() {
        runCatching {
            var unhooks: List<XC_MethodHook.Unhook> = emptyList()

            MethodFinder.fromClass(ThemeReceiver::class.java)
                .filterByName("validateTheme")
                .toList()
                .forEach { validate ->
                    XposedBridge.hookMethod(validate, object : XC_MethodHook() {

                        override fun beforeHookedMethod(param: MethodHookParam) {
                            LogHelper.d("FTM: ThemeReceiver.validateTheme BEFORE")
                            unhooks = MethodFinder.fromClass(DrmManager::class.java)
                                .filterByName("isLegal")
                                .returnConstantAll(DrmManager.DrmResult.DRM_SUCCESS)
                        }

                        override fun afterHookedMethod(param: MethodHookParam) {
                            LogHelper.d("FTM: ThemeReceiver.validateTheme AFTER")
                            unhooks.forEach { hook -> hook.unhook() }
                            unhooks = emptyList()
                        }
                    })
                }
        }.onFailure { LogHelper.ex(it) }
    }

    // ------------------------------------------------- com.miui.personalassistant
    // 智能助理：付费小组件（Maml）相关判断全部改常量；
    // shouldCheckMamlBoughtState / isTargetPositionMamlPayAndDownloading 需要区分背屏，
    // 单独走 RearScreenMamlSkip（调用栈带 rearscreen 时放行真实逻辑）。
    private fun hookPersonalAssistant(lpparam: XC_LoadPackage.LoadPackageParam) {
        runCatching {
            val vmClass =
                lpparam.loadClass("com.miui.personalassistant.picker.business.detail.PickerDetailViewModel")
            val respClass =
                lpparam.loadClass("com.miui.personalassistant.picker.business.detail.bean.PickerDetailResponse")
            val wrapperClass =
                lpparam.loadClass("com.miui.personalassistant.picker.business.detail.bean.PickerDetailResponseWrapper")

            MethodFinder.fromClass(lpparam.loadClass("com.miui.maml.widget.edit.MamlutilKt"))
                .filterByName("themeManagerSupportPaidWidget")
                .returnConstantAll(false)

            MethodFinder.fromClass(vmClass).filterByName("isCanDirectAddMaMl").returnConstantAll(true)

            MethodFinder.fromClass(
                lpparam.loadClass("com.miui.personalassistant.picker.business.detail.utils.PickerDetailDownloadManager\$Companion")
            ).filterByName("isCanDownload").returnConstantAll(true)

            MethodFinder.fromClass(
                lpparam.loadClass("com.miui.personalassistant.picker.business.detail.utils.PickerDetailUtil")
            ).filterByName("isCanAutoDownloadMaMl").returnConstantAll(true)

            MethodFinder.fromClass(respClass).filterByName("isPay").returnConstantAll(false)
            MethodFinder.fromClass(respClass).filterByName("isBought").returnConstantAll(true)
            MethodFinder.fromClass(wrapperClass).filterByName("isPay").returnConstantAll(false)
            MethodFinder.fromClass(wrapperClass).filterByName("isBought").returnConstantAll(true)

            MethodFinder.fromClass(vmClass)
                .filterByName("checkIsIndependentProcessWidgetForPosition")
                .returnConstantAll(true)

            MethodFinder.fromClass(vmClass)
                .filterByName("shouldCheckMamlBoughtState")
                .hookWith(RearScreenMamlSkip())

            MethodFinder.fromClass(vmClass)
                .filterByName("isTargetPositionMamlPayAndDownloading")
                .hookWith(RearScreenMamlSkip())
        }.onFailure { LogHelper.ex(it) }
    }

    // ------------------------------------------------- com.android.thememanager
    private fun hookThemeManager(lpparam: XC_LoadPackage.LoadPackageParam) {
        // 1. 详情转 Resource 时把 bought 置 true；背屏主题除外（强置会导致应用失败）
        runCatching {
            MethodFinder.fromClass(
                lpparam.loadClass("com.android.thememanager.detail.theme.model.OnlineResourceDetail")
            ).filterByName("toResource").afterAll { param ->
                val obj = param.thisObject
                val category = XposedHelpers.getObjectField(obj, "category") as? String
                if (category == null || !category.contains("rear", ignoreCase = true)) {
                    LogHelper.d("FTM: OnlineResourceDetail.toResource AFTER (force bought=true)")
                    XposedHelpers.setObjectField(obj, "bought", true)
                } else {
                    LogHelper.d("FTM: OnlineResourceDetail.toResource SKIP force bought (rear screen)")
                    RearScreenState.markRear()
                }
            }
        }.onFailure { LogHelper.ex(it) }

        // 2. 折扣价显示：把第二个 int 参数置 0，去掉付费角标
        runCatching {
            MethodFinder.fromClass(
                lpparam.loadClass("com.android.thememanager.basemodule.views.DiscountPriceView")
            )
                .filterByParamCount(2)
                .filterByParamTypes(java.lang.Integer.TYPE, java.lang.Integer.TYPE)
                .filterByReturnType(java.lang.Void.TYPE)
                .beforeAll { param ->
                    LogHelper.d("FTM: DiscountPriceView BEFORE")
                    param.args[1] = 0
                }
        }.onFailure { LogHelper.ex(it) }

        runCatching {
            MethodFinder.fromClass(lpparam.loadClass("com.miui.maml.widget.edit.MamlutilKt"))
                .filterByName("themeManagerSupportPaidWidget")
                .returnConstantAll(false)
        }.onFailure { LogHelper.ex(it) }

        // 3. 被混淆的方法：交给 DexKit 按字符串特征定位
        runCatching { hookObfuscatedByDexKit(lpparam) }.onFailure { LogHelper.ex(it) }
    }

    /**
     * 主题管理器里两个关键点已被混淆，方法名每次发版都变，
     * 这里用 DexKit 按「方法体内出现的字符串」反查：
     *
     * - DrmResult：DRM 校验结果的封装处，强置 DRM_SUCCESS 即破解
     * - LargeIcon：大图标（large_icons）授权校验，提前造一个空的 .mra 让它认为已授权
     */
    private fun hookObfuscatedByDexKit(lpparam: XC_LoadPackage.LoadPackageParam) {
        System.loadLibrary("dexkit")

        val bridge = DexKitBridge.create(lpparam.appInfo.sourceDir)
        if (bridge == null || !bridge.isValid) {
            LogHelper.e("FTM: DexKit 初始化失败")
            return
        }

        bridge.use {
            val found = it.batchFindMethodsUsingStrings(
                BatchFindArgs.builder()
                    .queryMap(
                        mapOf(
                            "DrmResult" to setOf(
                                "theme",
                                "ThemeManagerTag",
                                "/system",
                                "check rights isLegal:"
                            ),
                            "LargeIcon" to setOf(
                                "apply failed",
                                "/data/system/theme/large_icons/",
                                "default_large_icon_product_id",
                                "largeicons",
                                "relativePackageList is empty"
                            )
                        )
                    )
                    .build()
            )

            // 3.1 DRM 校验结果：背屏窗口内放行真实校验
            found["DrmResult"]?.firstOrNull()
                ?.getMethodInstance(lpparam.classLoader)
                ?.let { method ->
                    XposedBridge.hookMethod(method, object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            if (RearScreenState.isRearActive()) {
                                LogHelper.d("FTM: DrmResult SKIP (rear window) -> real DRM")
                            } else {
                                LogHelper.d("FTM: DrmResult AFTER (forced)")
                                param.result = DrmManager.DrmResult.DRM_SUCCESS
                            }
                        }
                    })
                } ?: LogHelper.e("FTM: DexKit 未定位到 DrmResult 方法")

            // 3.2 大图标授权：预置空的授权文件
            found["LargeIcon"]?.firstOrNull()
                ?.getMethodInstance(lpparam.classLoader)
                ?.let { method ->
                    XposedBridge.hookMethod(method, object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            LogHelper.d("FTM: large_icons BEFORE")
                            runCatching { prepareLargeIconRights(lpparam, param.thisObject) }
                                .onFailure { t -> LogHelper.ex(t) }
                        }
                    })
                } ?: LogHelper.e("FTM: DexKit 未定位到 LargeIcon 方法")
        }
    }

    /**
     * 在 .data/rights/theme/ 下造一个空的 {productId}-largeicons.mra，
     * 让大图标的授权检查直接通过。
     */
    private fun prepareLargeIconRights(
        lpparam: XC_LoadPackage.LoadPackageParam,
        thisObject: Any
    ) {
        val resourceClass = Class.forName(
            "com.android.thememanager.basemodule.resource.model.Resource",
            false,
            lpparam.classLoader
        )
        val field = FieldFinder.fromClass(thisObject.javaClass)
            .filterByType(resourceClass)
            .first()

        val resource = XposedHelpers.getObjectField(thisObject, field.name)
        val productId = resource?.let { res -> XposedHelpers.callMethod(res, "getProductId") }

        val file = File(
            "/storage/emulated/0/Android/data/com.android.thememanager/files/MIUI/theme/.data/rights/theme",
            "$productId-largeicons.mra"
        )
        file.parentFile?.let { parent ->
            if (!parent.exists()) parent.mkdirs()
        }
        file.createNewFile()
    }

    // ------------------------------------------------------------- com.miui.home
    private fun hookHome(lpparam: XC_LoadPackage.LoadPackageParam) {
        runCatching {
            MethodFinder.fromClass(lpparam.loadClass("com.miui.maml.widget.edit.MamlutilKt"))
                .filterByName("themeManagerSupportPaidWidget")
                .returnConstantAll(false)

            MethodFinder.fromClass(lpparam.loadClass("com.miui.home.launcher.gadget.MaMlPendingHostView"))
                .filterByName("isCanAutoStartDownload")
                .returnConstantAll(true)
        }.onFailure { LogHelper.ex(it) }
    }

    // ------------------------------------------------------------------ helpers

    private fun XC_LoadPackage.LoadPackageParam.loadClass(name: String): Class<*> =
        ClassUtils.loadClass(name, classLoader)

    /** 所有匹配方法：调用前执行 block */
    private fun MethodFinder.beforeAll(
        block: (XC_MethodHook.MethodHookParam) -> Unit
    ): List<XC_MethodHook.Unhook> = toList().map { method ->
        XposedBridge.hookMethod(method, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) = block(param)
        })
    }

    /** 所有匹配方法：调用后执行 block */
    private fun MethodFinder.afterAll(
        block: (XC_MethodHook.MethodHookParam) -> Unit
    ): List<XC_MethodHook.Unhook> = toList().map { method ->
        XposedBridge.hookMethod(method, object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) = block(param)
        })
    }

    /** 所有匹配方法：直接返回常量，原方法体不执行 */
    private fun MethodFinder.returnConstantAll(value: Any?): List<XC_MethodHook.Unhook> =
        beforeAll { param -> param.result = value }

    /** 所有匹配方法：挂一个自定义钩子（用于需要区分调用来源的场景） */
    private fun MethodFinder.hookWith(hook: XC_MethodHook): List<XC_MethodHook.Unhook> =
        toList().map { method -> XposedBridge.hookMethod(method, hook) }
}
