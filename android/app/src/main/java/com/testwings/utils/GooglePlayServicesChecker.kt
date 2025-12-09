package com.testwings.utils

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log

/**
 * Google Play Services 检测工具
 * 用于检查设备是否安装了Google Play Services
 */
object GooglePlayServicesChecker {
    
    private const val TAG = "GooglePlayServicesChecker"
    private const val GOOGLE_PLAY_SERVICES_PACKAGE = "com.google.android.gms"
    
    /**
     * 检查Google Play Services是否已安装
     */
    fun isGooglePlayServicesInstalled(context: Context): Boolean {
        return try {
            val packageManager = context.packageManager
            packageManager.getPackageInfo(GOOGLE_PLAY_SERVICES_PACKAGE, 0)
            Log.d(TAG, "✅ Google Play Services 已安装")
            true
        } catch (e: PackageManager.NameNotFoundException) {
            Log.d(TAG, "❌ Google Play Services 未安装")
            false
        } catch (e: Exception) {
            Log.e(TAG, "检查Google Play Services失败", e)
            false
        }
    }
    
    /**
     * 获取Google Play Services版本信息
     */
    fun getGooglePlayServicesVersion(context: Context): String? {
        return try {
            val packageManager = context.packageManager
            val packageInfo = packageManager.getPackageInfo(GOOGLE_PLAY_SERVICES_PACKAGE, 0)
            val versionName = packageInfo.versionName
            val versionCode = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                packageInfo.longVersionCode.toString()
            } else {
                @Suppress("DEPRECATION")
                packageInfo.versionCode.toString()
            }
            Log.d(TAG, "Google Play Services 版本: $versionName (code: $versionCode)")
            "$versionName ($versionCode)"
        } catch (e: Exception) {
            Log.e(TAG, "获取Google Play Services版本失败", e)
            null
        }
    }
    
    /**
     * 检查Google Play Services类是否可用
     */
    fun isGooglePlayServicesClassAvailable(): Boolean {
        return try {
            Class.forName("com.google.android.gms.common.GooglePlayServicesUtil")
            Log.d(TAG, "✅ Google Play Services 类可用")
            true
        } catch (e: ClassNotFoundException) {
            Log.d(TAG, "❌ Google Play Services 类不可用")
            false
        } catch (e: Exception) {
            Log.e(TAG, "检查Google Play Services类失败", e)
            false
        }
    }
    
    /**
     * 获取完整的Google Play Services状态信息
     */
    fun getStatusInfo(context: Context): String {
        val isInstalled = isGooglePlayServicesInstalled(context)
        val isClassAvailable = isGooglePlayServicesClassAvailable()
        val version = if (isInstalled) getGooglePlayServicesVersion(context) else null
        
        return buildString {
            appendLine("Google Play Services 状态:")
            appendLine("  安装状态: ${if (isInstalled) "✅ 已安装" else "❌ 未安装"}")
            appendLine("  类可用性: ${if (isClassAvailable) "✅ 可用" else "❌ 不可用"}")
            if (version != null) {
                appendLine("  版本信息: $version")
            }
        }
    }
}

