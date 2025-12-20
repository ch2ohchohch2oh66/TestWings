package com.testwings.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.graphics.Path
import android.graphics.PixelFormat
import android.hardware.HardwareBuffer
import android.hardware.display.DisplayManager
import android.media.Image
import android.media.ImageReader
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.annotation.RequiresApi
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * TestWings 无障碍服务
 * 用于获取 UI 元素信息和执行操作（点击、输入、滑动等）
 */
class TestWingsAccessibilityService : AccessibilityService() {
    
    companion object {
        private const val TAG = "TestWingsAccessibility"
        
        // 服务的完整类名，用于系统API检查
        private const val SERVICE_NAME = "com.testwings.service.TestWingsAccessibilityService"
        
        // 单例引用，用于外部调用
        @Volatile
        private var instance: TestWingsAccessibilityService? = null
        
        /**
         * 获取服务实例（如果服务已启动）
         */
        fun getInstance(): TestWingsAccessibilityService? = instance
        
        /**
         * 检查服务是否已启用（使用系统API检查，不依赖服务实例）
         * 这样可以正确检测到服务在系统设置中的启用状态，即使服务实例还未创建
         */
        fun isServiceEnabled(context: Context? = null): Boolean {
            // 如果服务实例存在，说明服务已启动并连接
            if (instance != null) {
                return true
            }
            
            // 如果没有传入context，无法使用系统API检查，返回false
            val ctx = context ?: return false
            
            // 使用系统API检查服务是否在系统设置中被启用
            return try {
                val accessibilityEnabled = Settings.Secure.getInt(
                    ctx.contentResolver,
                    Settings.Secure.ACCESSIBILITY_ENABLED,
                    0
                ) == 1
                
                if (!accessibilityEnabled) {
                    Log.d(TAG, "无障碍服务总开关未启用")
                    return false
                }
                
                val enabledServices = Settings.Secure.getString(
                    ctx.contentResolver,
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
                ) ?: ""
                
                val isEnabled = enabledServices.contains(SERVICE_NAME, ignoreCase = true)
                Log.d(TAG, "无障碍服务在系统设置中${if (isEnabled) "已启用" else "未启用"}")
                isEnabled
            } catch (e: Exception) {
                Log.e(TAG, "检查无障碍服务状态失败", e)
                false
            }
        }
    }
    
    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.d(TAG, "TestWings AccessibilityService 已启动")
    }
    
    override fun onDestroy() {
        super.onDestroy()
        instance = null
        Log.d(TAG, "TestWings AccessibilityService 已停止")
    }
    
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 可以在这里监听 UI 变化事件
        // 暂时不需要处理，后续可以用于监听页面变化
    }
    
    override fun onInterrupt() {
        Log.w(TAG, "AccessibilityService 被中断")
    }
    
    /**
     * 获取根节点（当前屏幕的 UI 树根节点）
     */
    fun getRootNode(): AccessibilityNodeInfo? {
        return rootInActiveWindow
    }
    
    /**
     * 根据文本查找节点
     */
    fun findNodeByText(text: String): AccessibilityNodeInfo? {
        val rootNode = getRootNode() ?: return null
        return findNodeByTextRecursive(rootNode, text)
    }
    
    /**
     * 递归查找节点
     */
    private fun findNodeByTextRecursive(
        node: AccessibilityNodeInfo,
        text: String
    ): AccessibilityNodeInfo? {
        // 检查当前节点
        if (node.text?.toString()?.contains(text, ignoreCase = true) == true) {
            return node
        }
        if (node.contentDescription?.toString()?.contains(text, ignoreCase = true) == true) {
            return node
        }
        
        // 递归查找子节点
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findNodeByTextRecursive(child, text)
            if (found != null) {
                return found
            }
            child.recycle()
        }
        
        return null
    }
    
    /**
     * 根据资源 ID 查找节点
     */
    fun findNodeByResourceId(resourceId: String): AccessibilityNodeInfo? {
        val rootNode = getRootNode() ?: return null
        return findNodeByResourceIdRecursive(rootNode, resourceId)
    }
    
    private fun findNodeByResourceIdRecursive(
        node: AccessibilityNodeInfo,
        resourceId: String
    ): AccessibilityNodeInfo? {
        if (node.viewIdResourceName == resourceId) {
            return node
        }
        
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findNodeByResourceIdRecursive(child, resourceId)
            if (found != null) {
                return found
            }
            child.recycle()
        }
        
        return null
    }
    
    /**
     * 点击节点
     */
    fun clickNode(node: AccessibilityNodeInfo): Boolean {
        return try {
            if (node.isClickable) {
                node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            } else {
                // 如果节点不可点击，尝试点击父节点
                val parent = node.parent
                if (parent != null && parent.isClickable) {
                    val result = parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    parent.recycle()
                    result
                } else {
                    false
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "点击节点失败", e)
            false
        }
    }
    
    /**
     * 根据坐标点击
     */
    @RequiresApi(Build.VERSION_CODES.N)
    fun clickAt(x: Int, y: Int): Boolean {
        val path = Path().apply {
            moveTo(x.toFloat(), y.toFloat())
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 100))
            .build()
        
        return dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                super.onCompleted(gestureDescription)
                Log.d(TAG, "点击完成: ($x, $y)")
            }
            
            override fun onCancelled(gestureDescription: GestureDescription?) {
                super.onCancelled(gestureDescription)
                Log.w(TAG, "点击取消: ($x, $y)")
            }
        }, null)
    }
    
    /**
     * 输入文本
     */
    fun inputText(node: AccessibilityNodeInfo, text: String): Boolean {
        return try {
            val arguments = android.os.Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            }
            node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
        } catch (e: Exception) {
            Log.e(TAG, "输入文本失败", e)
            false
        }
    }
    
    /**
     * 清空输入框
     */
    fun clearText(node: AccessibilityNodeInfo): Boolean {
        return try {
            val arguments = android.os.Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "")
            }
            node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
        } catch (e: Exception) {
            Log.e(TAG, "清空文本失败", e)
            false
        }
    }
    
    /**
     * 滑动
     */
    @RequiresApi(Build.VERSION_CODES.N)
    fun swipe(
        startX: Int, startY: Int,
        endX: Int, endY: Int,
        duration: Long = 300
    ): Boolean {
        val path = Path().apply {
            moveTo(startX.toFloat(), startY.toFloat())
            lineTo(endX.toFloat(), endY.toFloat())
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, duration))
            .build()
        
        return dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                super.onCompleted(gestureDescription)
                Log.d(TAG, "滑动完成: ($startX, $startY) -> ($endX, $endY)")
            }
            
            override fun onCancelled(gestureDescription: GestureDescription?) {
                super.onCancelled(gestureDescription)
                Log.w(TAG, "滑动取消: ($startX, $startY) -> ($endX, $endY)")
            }
        }, null)
    }
    
    /**
     * 向上滑动
     */
    @RequiresApi(Build.VERSION_CODES.N)
    fun swipeUp(distance: Int = 800): Boolean {
        val displayMetrics = resources.displayMetrics
        val centerX = displayMetrics.widthPixels / 2
        // 从屏幕下方开始，向上滑动
        val startY = (displayMetrics.heightPixels * 0.8).toInt()  // 屏幕80%位置
        val endY = (displayMetrics.heightPixels * 0.2).toInt()    // 屏幕20%位置
        Log.d(TAG, "向上滑动: 从 ($centerX, $startY) 到 ($centerX, $endY)")
        return swipe(centerX, startY, centerX, endY, 500)  // 增加持续时间到500ms
    }
    
    /**
     * 向下滑动
     */
    @RequiresApi(Build.VERSION_CODES.N)
    fun swipeDown(distance: Int = 800): Boolean {
        val displayMetrics = resources.displayMetrics
        val centerX = displayMetrics.widthPixels / 2
        // 从屏幕上方开始，向下滑动
        val startY = (displayMetrics.heightPixels * 0.2).toInt()    // 屏幕20%位置
        val endY = (displayMetrics.heightPixels * 0.8).toInt()    // 屏幕80%位置
        Log.d(TAG, "向下滑动: 从 ($centerX, $startY) 到 ($centerX, $endY)")
        return swipe(centerX, startY, centerX, endY, 500)  // 增加持续时间到500ms
    }
    
    /**
     * 向左滑动
     */
    @RequiresApi(Build.VERSION_CODES.N)
    fun swipeLeft(distance: Int = 800): Boolean {
        val displayMetrics = resources.displayMetrics
        val centerY = displayMetrics.heightPixels / 2
        // 从屏幕右侧开始，向左滑动
        val startX = (displayMetrics.widthPixels * 0.8).toInt()    // 屏幕80%位置
        val endX = (displayMetrics.widthPixels * 0.2).toInt()     // 屏幕20%位置
        Log.d(TAG, "向左滑动: 从 ($startX, $centerY) 到 ($endX, $centerY)")
        return swipe(startX, centerY, endX, centerY, 500)  // 增加持续时间到500ms
    }
    
    /**
     * 向右滑动
     */
    @RequiresApi(Build.VERSION_CODES.N)
    fun swipeRight(distance: Int = 800): Boolean {
        val displayMetrics = resources.displayMetrics
        val centerY = displayMetrics.heightPixels / 2
        // 从屏幕左侧开始，向右滑动
        val startX = (displayMetrics.widthPixels * 0.2).toInt()   // 屏幕20%位置
        val endX = (displayMetrics.widthPixels * 0.8).toInt()      // 屏幕80%位置
        Log.d(TAG, "向右滑动: 从 ($startX, $centerY) 到 ($endX, $centerY)")
        return swipe(startX, centerY, endX, centerY, 500)  // 增加持续时间到500ms
    }
    
    /**
     * 返回键
     */
    fun pressBack(): Boolean {
        return performGlobalAction(GLOBAL_ACTION_BACK)
    }
    
    /**
     * 主页键
     */
    fun pressHome(): Boolean {
        return performGlobalAction(GLOBAL_ACTION_HOME)
    }
    
    /**
     * 最近任务键
     */
    fun pressRecentApps(): Boolean {
        return performGlobalAction(GLOBAL_ACTION_RECENTS)
    }
    
    /**
     * 使用 AccessibilityService 捕获屏幕截图
     * 注意：需要 Android Q (API 29) 或更高版本（因为需要使用 Bitmap.wrapHardwareBuffer）
     * 
     * @return Bitmap 截图，如果失败则返回 null
     */
    @RequiresApi(Build.VERSION_CODES.Q)
    fun takeScreenshot(): Bitmap? {
        return try {
            Log.d(TAG, "开始使用 AccessibilityService 捕获屏幕...")
            
            // 使用 CountDownLatch 等待异步回调
            val latch = CountDownLatch(1)
            var resultBitmap: Bitmap? = null
            
            // 调用 AccessibilityService 的 takeScreenshot 方法
            var capturedErrorCode = 0
            takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, 
                object : AccessibilityService.TakeScreenshotCallback {
                    override fun onSuccess(screenshotResult: AccessibilityService.ScreenshotResult) {
                        try {
                            Log.d(TAG, "✅ AccessibilityService 截图成功")
                            
                            // 从 ScreenshotResult 获取 HardwareBuffer 和 ColorSpace
                            val hardwareBuffer = screenshotResult.hardwareBuffer
                            val colorSpace = screenshotResult.colorSpace
                            
                            if (hardwareBuffer != null && colorSpace != null) {
                                try {
                                    // 使用 Bitmap.wrapHardwareBuffer 直接创建 Bitmap
                                    val bitmap = Bitmap.wrapHardwareBuffer(hardwareBuffer, colorSpace)
                                    if (bitmap != null) {
                                        resultBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, false)
                                        bitmap.recycle() // 回收 wrap 创建的 bitmap
                                        Log.d(TAG, "截图 Bitmap 创建成功: ${resultBitmap?.width}x${resultBitmap?.height}")
                                    } else {
                                        Log.e(TAG, "Bitmap.wrapHardwareBuffer 返回 null")
                                        capturedErrorCode = -1
                                    }
                                } finally {
                                    // 关闭 HardwareBuffer 释放资源
                                    hardwareBuffer.close()
                                }
                            } else {
                                Log.e(TAG, "HardwareBuffer 或 ColorSpace 为 null")
                                capturedErrorCode = -1
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "处理截图结果时异常", e)
                            e.printStackTrace()
                            capturedErrorCode = -1
                        } finally {
                            latch.countDown()
                        }
                    }
                    
                    override fun onFailure(failureErrorCode: Int) {
                        Log.e(TAG, "❌ AccessibilityService 截图失败，错误代码: $failureErrorCode")
                        capturedErrorCode = failureErrorCode
                        latch.countDown()
                    }
                }
            )
            
            // 等待截图完成（最多等待 5 秒）
            val success = latch.await(5, TimeUnit.SECONDS)
            if (!success) {
                Log.e(TAG, "❌ AccessibilityService 截图超时（回调未触发）")
                return null
            }
            
            if (capturedErrorCode != 0) {
                Log.e(TAG, "❌ AccessibilityService 截图失败，错误代码: $capturedErrorCode")
                // 错误代码说明：
                // 1 = ERROR_TAKE_SCREENSHOT_NO_ACCESSIBILITY_ACCESS (需要启用无障碍服务)
                // 2 = ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT (截图间隔太短，至少需要1秒)
                // 3 = ERROR_TAKE_SCREENSHOT_INVALID_DISPLAY (无效的显示)
                // 4 = ERROR_TAKE_SCREENSHOT_INVALID_WINDOW (无效的窗口)
                when (capturedErrorCode) {
                    1 -> Log.e(TAG, "错误原因: 需要启用无障碍服务")
                    2 -> Log.e(TAG, "错误原因: 截图间隔太短，需要等待至少1秒")
                    3 -> Log.e(TAG, "错误原因: 无效的显示")
                    4 -> Log.e(TAG, "错误原因: 无效的窗口")
                    else -> Log.e(TAG, "错误原因: 未知错误代码")
                }
                return null
            }
            
            if (resultBitmap == null && capturedErrorCode == 0) {
                Log.e(TAG, "❌ AccessibilityService 截图回调成功，但 Bitmap 为 null")
                return null
            }
            
            resultBitmap
        } catch (e: Exception) {
            Log.e(TAG, "❌ AccessibilityService 截图异常", e)
            e.printStackTrace()
            null
        }
    }
    
    /**
     * 同步版本的截图方法
     * 如果 Android 版本低于 Q (API 29)，返回 null
     */
    fun takeScreenshotSync(): Bitmap? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            takeScreenshot()
        } else {
            Log.w(TAG, "takeScreenshot 需要 Android Q (API 29) 或更高版本，当前版本: ${Build.VERSION.SDK_INT}")
            null
        }
    }
}

