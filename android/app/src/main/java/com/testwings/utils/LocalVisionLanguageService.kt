package com.testwings.utils

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers

/**
 * 本地部署的Vision-Language服务实现
 * 使用ONNX Runtime在设备本地运行VL模型
 */
class LocalVisionLanguageService(
    private val context: Context
) : IVisionLanguageService {
    
    private val TAG = "LocalVisionLanguageService"
    
    /**
     * 内部使用的VisionLanguageManager实例
     * 注意：这里使用包装器模式，保留现有实现
     */
    private val manager = VisionLanguageManager(context)
    
    override suspend fun understand(screenshot: Bitmap): ScreenState {
        return withContext(Dispatchers.IO) {
            try {
                manager.understand(screenshot)
            } catch (e: Exception) {
                Log.e(TAG, "本地VL模型推理失败", e)
                // 返回空结果，表示VL不可用
                ScreenState(
                    elements = emptyList(),
                    semanticDescription = "",
                    vlAvailable = false
                )
            }
        }
    }
    
    override fun getLoadState(): LoadState {
        return manager.getLoadState()
    }
    
    override suspend fun loadModel(onProgress: ((Int) -> Unit)?): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                manager.loadModel(onProgress)
            } catch (e: Exception) {
                Log.e(TAG, "本地VL模型加载失败", e)
                false
            }
        }
    }
    
    override fun isModelAvailable(): Boolean {
        return manager.isModelAvailable()
    }
    
    override fun release() {
        manager.release()
    }
    
    override fun getServiceType(): String {
        return "LOCAL"
    }
}
