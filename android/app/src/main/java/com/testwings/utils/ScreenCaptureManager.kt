package com.testwings.utils

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.util.Log
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts

/**
 * 屏幕捕获管理器
 * 负责管理 MediaProjection 权限和实例
 * 
 * 设计目标：
 * 1. 在应用启动时或测试开始前，确保屏幕捕获权限已授权
 * 2. 在同一个应用会话中，复用 MediaProjection 实例，避免重复授权
 * 3. 提供清晰的权限状态和错误提示
 */
class ScreenCaptureManager(private val context: Context) {
    
    companion object {
        private const val TAG = "ScreenCaptureManager"
    }
    
    private var mediaProjectionManager: MediaProjectionManager? = null
    private var mediaProjection: MediaProjection? = null
    private var permissionLauncher: ActivityResultLauncher<Intent>? = null
    
    /**
     * 初始化管理器
     */
    fun initialize(launcher: ActivityResultLauncher<Intent>) {
        mediaProjectionManager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as? MediaProjectionManager
        permissionLauncher = launcher
    }
    
    /**
     * 检查是否有有效的 MediaProjection 实例
     */
    fun hasValidMediaProjection(): Boolean {
        return mediaProjection != null
    }
    
    /**
     * 获取 MediaProjection 实例
     * 如果没有，返回 null（需要先请求权限）
     */
    fun getMediaProjection(): MediaProjection? {
        return mediaProjection
    }
    
    /**
     * 请求屏幕捕获权限
     * 如果已经有有效的实例，直接返回 true
     */
    fun requestPermission(): Boolean {
        if (hasValidMediaProjection()) {
            Log.d(TAG, "已有有效的 MediaProjection 实例，无需重新授权")
            return true
        }
        
        val manager = mediaProjectionManager ?: run {
            Log.e(TAG, "MediaProjectionManager 未初始化")
            return false
        }
        
        val launcher = permissionLauncher ?: run {
            Log.e(TAG, "PermissionLauncher 未初始化")
            return false
        }
        
        Log.d(TAG, "请求屏幕捕获权限")
        val intent = manager.createScreenCaptureIntent()
        launcher.launch(intent)
        return false  // 权限请求是异步的，返回 false 表示需要等待授权结果
    }
    
    /**
     * 处理权限授权结果
     * 在 ActivityResultCallback 中调用
     */
    fun onPermissionResult(resultCode: Int, data: Intent?): Boolean {
        if (resultCode != android.app.Activity.RESULT_OK || data == null) {
            Log.w(TAG, "屏幕捕获权限被拒绝")
            return false
        }
        
        val manager = mediaProjectionManager ?: run {
            Log.e(TAG, "MediaProjectionManager 未初始化")
            return false
        }
        
        try {
            mediaProjection = manager.getMediaProjection(resultCode, data)
            Log.d(TAG, "屏幕捕获权限已授权，MediaProjection 实例已创建")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "创建 MediaProjection 实例失败", e)
            return false
        }
    }
    
    /**
     * 清理 MediaProjection 实例
     * 注意：这会停止屏幕捕获，下次需要重新授权
     */
    fun release() {
        mediaProjection?.stop()
        mediaProjection = null
        Log.d(TAG, "MediaProjection 实例已释放")
    }
    
    /**
     * 检查 MediaProjection 是否仍然有效
     * 如果无效，清理实例
     */
    fun validateAndCleanup(): Boolean {
        if (mediaProjection == null) {
            return false
        }
        
        // 尝试检查 MediaProjection 是否仍然有效
        // 注意：MediaProjection 没有直接的方法检查有效性
        // 实际使用中，如果失效会在使用时抛出异常
        return true
    }
}

