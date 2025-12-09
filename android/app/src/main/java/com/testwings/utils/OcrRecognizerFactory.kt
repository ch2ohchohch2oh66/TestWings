package com.testwings.utils

import android.content.Context
import android.util.Log

/**
 * OCR识别器工厂
 * 自动检测设备并选择合适的OCR实现
 */
object OcrRecognizerFactory {
    
    private const val TAG = "OcrRecognizerFactory"
    
    /**
     * 检测Google Play Services是否可用
     */
    private fun isGooglePlayServicesAvailable(context: Context): Boolean {
        return try {
            // 方法1: 尝试加载Google Play Services的类
            Class.forName("com.google.android.gms.common.GooglePlayServicesUtil")
            
            // 方法2: 检查是否有Google Play Services的包
            val packageManager = context.packageManager
            try {
                packageManager.getPackageInfo("com.google.android.gms", 0)
                Log.d(TAG, "Google Play Services 可用")
                true
            } catch (e: android.content.pm.PackageManager.NameNotFoundException) {
                Log.d(TAG, "Google Play Services 未安装")
                false
            }
        } catch (e: ClassNotFoundException) {
            Log.d(TAG, "Google Play Services 不可用（未找到类）")
            false
        } catch (e: Exception) {
            Log.d(TAG, "检测 Google Play Services 失败: ${e.message}")
            false
        }
    }
    
    /**
     * 检测是否为HarmonyOS设备
     */
    private fun isHarmonyOS(): Boolean {
        return try {
            // HarmonyOS 通常会在系统属性中标识
            val systemProperty = System.getProperty("ro.build.version.sdk")
            // 或者检查是否有HarmonyOS特有的类
            Class.forName("ohos.system.SystemProperties")
            true
        } catch (e: ClassNotFoundException) {
            // 检查系统属性
            val manufacturer = android.os.Build.MANUFACTURER.lowercase()
            val brand = android.os.Build.BRAND.lowercase()
            // 华为设备可能是HarmonyOS
            manufacturer.contains("huawei") || brand.contains("huawei")
        } catch (e: Exception) {
            false
        }
    }
    
    /**
     * 创建OCR识别器实例
     * 自动选择最合适的实现：
     * 1. 如果Google Play Services可用，使用ML Kit（性能好，集成简单）
     * 2. 如果是HarmonyOS或Google Play Services不可用，仍然尝试使用ML Kit
     * 
     * **重要发现（已验证）**：
     * - ML Kit Text Recognition 在 HarmonyOS 4.2 上可以正常工作
     * - 即使没有完整的 Google Play Services，ML Kit 的 Text Recognition 功能仍然可用
     * - 这可能是因为 ML Kit Text Recognition 不完全依赖 Google Play Store
     * - 测试环境：HarmonyOS 4.2，无 Google Play Services，ML Kit 识别成功
     * 
     * **备选方案**：
     * - 如果 ML Kit 在某些设备上无法工作，可以考虑集成 PaddleOCR 作为备选
     */
    fun create(context: Context): IOcrRecognizer {
        val isHarmony = isHarmonyOS()
        val hasGooglePlay = isGooglePlayServicesAvailable(context)
        
        Log.d(TAG, "设备信息: HarmonyOS=$isHarmony, GooglePlayServices=$hasGooglePlay")
        
        return when {
            // 有 Google Play Services，使用 ML Kit
            hasGooglePlay -> {
                Log.i(TAG, "使用 ML Kit（Google Play Services 可用）")
                MlKitOcrRecognizer(context)
            }
            // HarmonyOS 或没有 Google Play Services
            // 已验证：ML Kit Text Recognition 在 HarmonyOS 4.2 上可以正常工作
            isHarmony || !hasGooglePlay -> {
                Log.i(TAG, "检测到HarmonyOS或无Google Play Services")
                Log.i(TAG, "使用 ML Kit（已验证在HarmonyOS 4.2上可用）")
                // 已验证：ML Kit 在 HarmonyOS 4.2 上可以正常工作
                MlKitOcrRecognizer(context)
                // 备选方案（如果ML Kit在某些设备上无法工作，可以启用）：
                // PaddleOcrRecognizer(context)
            }
            // 默认使用 ML Kit
            else -> {
                Log.i(TAG, "使用 ML Kit（默认）")
                MlKitOcrRecognizer(context)
            }
        }
    }
}

