package com.testwings.utils

import android.content.Context
import android.os.Build
import android.view.accessibility.AccessibilityNodeInfo
import com.testwings.service.TestWingsAccessibilityService
import android.util.Log

/**
 * 设备操作控制器
 * 封装常用的设备操作，提供简洁的 API
 */
object DeviceController {
    
    private const val TAG = "DeviceController"
    
    /**
     * 检查无障碍服务是否已启用
     * @param context 上下文，用于系统API检查（可选，但建议传入以提高准确性）
     */
    fun isAccessibilityServiceEnabled(context: Context? = null): Boolean {
        return TestWingsAccessibilityService.isServiceEnabled(context)
    }
    
    /**
     * 获取服务实例
     */
    private fun getService(): TestWingsAccessibilityService? {
        return TestWingsAccessibilityService.getInstance()
    }
    
    /**
     * 点击指定坐标
     */
    fun click(x: Int, y: Int): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            Log.e(TAG, "点击操作需要 Android 7.0 (API 24) 或更高版本")
            return false
        }
        
        val service = getService() ?: run {
            Log.e(TAG, "无障碍服务未启用，无法执行点击操作")
            return false
        }
        
        Log.d(TAG, "执行点击操作: ($x, $y)")
        val result = service.clickAt(x, y)
        Log.d(TAG, "点击操作结果: $result")
        return result
    }
    
    /**
     * 根据文本点击
     */
    fun clickByText(text: String): Boolean {
        val service = getService() ?: run {
            Log.e(TAG, "无障碍服务未启用，无法执行点击操作")
            return false
        }
        
        val node = service.findNodeByText(text)
        return if (node != null) {
            val result = service.clickNode(node)
            node.recycle()
            result
        } else {
            Log.w(TAG, "未找到文本为 '$text' 的节点")
            false
        }
    }
    
    /**
     * 根据资源 ID 点击
     */
    fun clickByResourceId(resourceId: String): Boolean {
        val service = getService() ?: run {
            Log.e(TAG, "无障碍服务未启用，无法执行点击操作")
            return false
        }
        
        val node = service.findNodeByResourceId(resourceId)
        return if (node != null) {
            val result = service.clickNode(node)
            node.recycle()
            result
        } else {
            Log.w(TAG, "未找到资源 ID 为 '$resourceId' 的节点")
            false
        }
    }
    
    /**
     * 输入文本（根据文本查找输入框）
     */
    fun inputTextByLabel(labelText: String, text: String): Boolean {
        val service = getService() ?: run {
            Log.e(TAG, "无障碍服务未启用，无法执行输入操作")
            return false
        }
        
        // 查找输入框（通常是 EditText）
        val rootNode = service.getRootNode() ?: run {
            Log.e(TAG, "无法获取根节点")
            return false
        }
        
        val inputNode = findInputNodeByLabel(rootNode, labelText)
        rootNode.recycle()
        
        return if (inputNode != null) {
            val result = service.inputText(inputNode, text)
            inputNode.recycle()
            result
        } else {
            Log.w(TAG, "未找到标签为 '$labelText' 的输入框")
            false
        }
    }
    
    /**
     * 根据资源 ID 输入文本
     */
    fun inputTextByResourceId(resourceId: String, text: String): Boolean {
        val service = getService() ?: run {
            Log.e(TAG, "无障碍服务未启用，无法执行输入操作")
            return false
        }
        
        val node = service.findNodeByResourceId(resourceId)
        return if (node != null) {
            val result = service.inputText(node, text)
            node.recycle()
            result
        } else {
            Log.w(TAG, "未找到资源 ID 为 '$resourceId' 的节点")
            false
        }
    }
    
    /**
     * 清空输入框
     */
    fun clearTextByResourceId(resourceId: String): Boolean {
        val service = getService() ?: run {
            Log.e(TAG, "无障碍服务未启用，无法执行清空操作")
            return false
        }
        
        val node = service.findNodeByResourceId(resourceId)
        return if (node != null) {
            val result = service.clearText(node)
            node.recycle()
            result
        } else {
            Log.w(TAG, "未找到资源 ID 为 '$resourceId' 的节点")
            false
        }
    }
    
    /**
     * 向上滑动
     */
    fun swipeUp(distance: Int = 500): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            Log.e(TAG, "滑动操作需要 Android 7.0 (API 24) 或更高版本")
            return false
        }
        
        val service = getService() ?: run {
            Log.e(TAG, "无障碍服务未启用，无法执行滑动操作")
            return false
        }
        
        Log.d(TAG, "执行向上滑动操作，距离: $distance")
        val result = service.swipeUp(distance)
        Log.d(TAG, "向上滑动操作结果: $result")
        return result
    }
    
    /**
     * 向下滑动
     */
    fun swipeDown(distance: Int = 500): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            Log.e(TAG, "滑动操作需要 Android 7.0 (API 24) 或更高版本")
            return false
        }
        
        val service = getService() ?: run {
            Log.e(TAG, "无障碍服务未启用，无法执行滑动操作")
            return false
        }
        
        Log.d(TAG, "执行向下滑动操作，距离: $distance")
        val result = service.swipeDown(distance)
        Log.d(TAG, "向下滑动操作结果: $result")
        return result
    }
    
    /**
     * 向左滑动
     */
    fun swipeLeft(distance: Int = 500): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            Log.e(TAG, "滑动操作需要 Android 7.0 (API 24) 或更高版本")
            return false
        }
        
        val service = getService() ?: run {
            Log.e(TAG, "无障碍服务未启用，无法执行滑动操作")
            return false
        }
        
        Log.d(TAG, "执行向左滑动操作，距离: $distance")
        val result = service.swipeLeft(distance)
        Log.d(TAG, "向左滑动操作结果: $result")
        return result
    }
    
    /**
     * 向右滑动
     */
    fun swipeRight(distance: Int = 500): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            Log.e(TAG, "滑动操作需要 Android 7.0 (API 24) 或更高版本")
            return false
        }
        
        val service = getService() ?: run {
            Log.e(TAG, "无障碍服务未启用，无法执行滑动操作")
            return false
        }
        
        Log.d(TAG, "执行向右滑动操作，距离: $distance")
        val result = service.swipeRight(distance)
        Log.d(TAG, "向右滑动操作结果: $result")
        return result
    }
    
    /**
     * 自定义滑动
     */
    fun swipe(startX: Int, startY: Int, endX: Int, endY: Int, duration: Long = 300): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            Log.e(TAG, "滑动操作需要 Android 7.0 (API 24) 或更高版本")
            return false
        }
        
        val service = getService() ?: run {
            Log.e(TAG, "无障碍服务未启用，无法执行滑动操作")
            return false
        }
        
        return service.swipe(startX, startY, endX, endY, duration)
    }
    
    /**
     * 返回键
     */
    fun pressBack(): Boolean {
        val service = getService() ?: run {
            Log.e(TAG, "无障碍服务未启用，无法执行返回操作")
            return false
        }
        
        return service.pressBack()
    }
    
    /**
     * 主页键
     */
    fun pressHome(): Boolean {
        val service = getService() ?: run {
            Log.e(TAG, "无障碍服务未启用，无法执行主页操作")
            return false
        }
        
        return service.pressHome()
    }
    
    /**
     * 最近任务键
     */
    fun pressRecentApps(): Boolean {
        val service = getService() ?: run {
            Log.e(TAG, "无障碍服务未启用，无法执行最近任务操作")
            return false
        }
        
        return service.pressRecentApps()
    }
    
    /**
     * 查找输入节点（根据标签文本）
     */
    private fun findInputNodeByLabel(
        node: AccessibilityNodeInfo,
        labelText: String
    ): AccessibilityNodeInfo? {
        // 检查当前节点是否是输入框
        if (node.className?.toString()?.contains("EditText", ignoreCase = true) == true) {
            // 检查是否有匹配的标签
            val hint = node.hintText?.toString() ?: ""
            val contentDesc = node.contentDescription?.toString() ?: ""
            val text = node.text?.toString() ?: ""
            
            if (hint.contains(labelText, ignoreCase = true) ||
                contentDesc.contains(labelText, ignoreCase = true) ||
                text.contains(labelText, ignoreCase = true)
            ) {
                return node
            }
        }
        
        // 递归查找子节点
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findInputNodeByLabel(child, labelText)
            if (found != null) {
                return found
            }
            child.recycle()
        }
        
        return null
    }
}

