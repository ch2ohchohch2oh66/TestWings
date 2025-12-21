package com.testwings.utils

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Vision-Language服务统一管理器
 * 支持本地部署和API方式两种实现，支持运行时切换
 */
class VisionLanguageServiceManager(
    private val context: Context,
    private var deploymentType: DeploymentType = DeploymentType.AUTO
) {
    
    private val TAG = "VisionLanguageServiceManager"
    
    /**
     * 当前使用的服务实例
     */
    private var currentService: IVisionLanguageService? = null
    
    /**
     * 本地服务实例（懒加载）
     */
    private val localService: LocalVisionLanguageService by lazy {
        LocalVisionLanguageService(context)
    }
    
    /**
     * API服务实例（懒加载，需要配置）
     */
    private var apiService: ApiVisionLanguageService? = null
    
    /**
     * API服务配置
     */
    private var apiConfig: ApiVisionLanguageServiceConfig? = null
    
    /**
     * 部署类型
     */
    enum class DeploymentType {
        /**
         * 自动选择（优先本地，失败时降级到API）
         */
        AUTO,
        
        /**
         * 仅本地部署
         */
        LOCAL,
        
        /**
         * 仅API方式（云端）
         */
        API_CLOUD,
        
        /**
         * 仅API方式（局域网）
         */
        API_LOCAL
    }
    
    /**
     * 初始化服务
     */
    suspend fun initialize() {
        withContext(Dispatchers.IO) {
            when (deploymentType) {
                DeploymentType.AUTO -> {
                    // 自动选择：优先本地，如果本地不可用则使用API
                    if (localService.isModelAvailable()) {
                        Log.d(TAG, "✅ 本地VL模型可用，使用本地部署")
                        currentService = localService
                    } else if (apiConfig != null) {
                        Log.d(TAG, "⚠️ 本地VL模型不可用，降级到API方式")
                        ensureApiService()
                        currentService = apiService
                    } else {
                        Log.w(TAG, "⚠️ 本地VL模型不可用，且未配置API服务")
                        currentService = null
                    }
                }
                DeploymentType.LOCAL -> {
                    Log.d(TAG, "使用本地部署方式")
                    currentService = localService
                }
                DeploymentType.API_CLOUD, DeploymentType.API_LOCAL -> {
                    Log.d(TAG, "使用API部署方式: $deploymentType")
                    ensureApiService()
                    currentService = apiService
                }
            }
        }
    }
    
    /**
     * 配置API服务
     */
    fun configureApiService(config: ApiVisionLanguageServiceConfig) {
        apiConfig = config
        apiService = null // 重置，下次使用时重新创建
        Log.d(TAG, "API服务配置已更新: ${config.endpoint}")
    }
    
    /**
     * 设置部署类型
     */
    fun setDeploymentType(type: DeploymentType) {
        if (deploymentType != type) {
            deploymentType = type
            currentService = null // 重置当前服务，下次使用时重新初始化
            Log.d(TAG, "部署类型已更新: $type")
        }
    }
    
    /**
     * 获取当前部署类型
     */
    fun getDeploymentType(): DeploymentType {
        return deploymentType
    }
    
    /**
     * 确保API服务已创建
     */
    private fun ensureApiService() {
        if (apiService == null && apiConfig != null) {
            apiService = ApiVisionLanguageService(context, apiConfig!!)
        }
    }
    
    /**
     * 理解屏幕内容（VL模型推理）
     * 支持自动降级：如果当前服务失败，自动尝试其他可用服务
     */
    suspend fun understand(screenshot: Bitmap): ScreenState {
        return withContext(Dispatchers.IO) {
            // 如果服务未初始化，先初始化
            if (currentService == null) {
                initialize()
            }
            
            // 尝试使用当前服务
            val service = currentService
            if (service != null) {
                try {
                    val result = service.understand(screenshot)
                    if (result.vlAvailable) {
                        return@withContext result
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "当前服务推理失败: ${service.getServiceType()}", e)
                }
            }
            
            // 如果当前服务失败且是AUTO模式，尝试降级
            if (deploymentType == DeploymentType.AUTO) {
                // 如果当前是本地服务，尝试降级到API
                if (service == localService && apiConfig != null) {
                    Log.d(TAG, "本地服务失败，尝试降级到API服务")
                    ensureApiService()
                    apiService?.let { api ->
                        try {
                            val result = api.understand(screenshot)
                            if (result.vlAvailable) {
                                currentService = api // 切换到API服务
                                return@withContext result
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "API服务也失败", e)
                        }
                    }
                }
                // 如果当前是API服务，尝试降级到本地
                else if (service == apiService && localService.isModelAvailable()) {
                    Log.d(TAG, "API服务失败，尝试降级到本地服务")
                    try {
                        val result = localService.understand(screenshot)
                        if (result.vlAvailable) {
                            currentService = localService // 切换到本地服务
                            return@withContext result
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "本地服务也失败", e)
                    }
                }
            }
            
            // 所有服务都失败，返回空结果
            Log.e(TAG, "所有VL服务都失败，返回空结果")
            ScreenState(
                elements = emptyList(),
                semanticDescription = "",
                vlAvailable = false
            )
        }
    }
    
    /**
     * 获取当前加载状态
     */
    fun getLoadState(): LoadState {
        return currentService?.getLoadState() ?: LoadState.NOT_LOADED
    }
    
    /**
     * 加载模型
     */
    suspend fun loadModel(onProgress: ((Int) -> Unit)? = null): Boolean {
        return withContext(Dispatchers.IO) {
            if (currentService == null) {
                initialize()
            }
            currentService?.loadModel(onProgress) ?: false
        }
    }
    
    /**
     * 检查模型是否可用
     */
    fun isModelAvailable(): Boolean {
        return when (deploymentType) {
            DeploymentType.LOCAL -> localService.isModelAvailable()
            DeploymentType.API_CLOUD, DeploymentType.API_LOCAL -> {
                apiConfig != null && apiConfig!!.endpoint.isNotEmpty()
            }
            DeploymentType.AUTO -> {
                localService.isModelAvailable() || (apiConfig != null && apiConfig!!.endpoint.isNotEmpty())
            }
        }
    }
    
    /**
     * 释放资源
     */
    fun release() {
        currentService?.release()
        currentService = null
    }
    
    /**
     * 获取当前使用的服务类型
     */
    fun getCurrentServiceType(): String {
        return currentService?.getServiceType() ?: "NONE"
    }
}
