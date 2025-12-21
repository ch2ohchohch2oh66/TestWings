package com.testwings.utils

import android.graphics.Bitmap

/**
 * Vision-Language服务统一接口
 * 支持本地部署和API方式两种实现
 */
interface IVisionLanguageService {
    /**
     * 理解屏幕内容（VL模型推理）
     * @param screenshot 屏幕截图
     * @return 屏幕状态（包含所有UI元素和语义描述）
     */
    suspend fun understand(screenshot: Bitmap): ScreenState
    
    /**
     * 获取当前加载状态
     * @return 加载状态
     */
    fun getLoadState(): LoadState
    
    /**
     * 加载模型
     * @param onProgress 加载进度回调（可选，0-100）
     * @return 是否加载成功
     */
    suspend fun loadModel(onProgress: ((Int) -> Unit)? = null): Boolean
    
    /**
     * 检查模型是否可用
     * @return 是否可用
     */
    fun isModelAvailable(): Boolean
    
    /**
     * 释放资源
     */
    fun release()
    
    /**
     * 获取服务类型（用于日志和调试）
     * @return 服务类型名称
     */
    fun getServiceType(): String
}
