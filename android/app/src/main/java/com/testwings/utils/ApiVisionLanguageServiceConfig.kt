package com.testwings.utils

/**
 * API Vision-Language服务配置
 */
data class ApiVisionLanguageServiceConfig(
    /**
     * API端点URL
     */
    val endpoint: String,
    
    /**
     * 认证方式
     */
    val authType: AuthType = AuthType.API_KEY,
    
    /**
     * API密钥（用于API_KEY认证）
     */
    val apiKey: String? = null,
    
    /**
     * Bearer Token（用于BEARER_TOKEN认证）
     */
    val bearerToken: String? = null,
    
    /**
     * 请求超时时间（毫秒），默认30秒
     */
    val timeoutMs: Long = 30000,
    
    /**
     * 最大重试次数，默认3次
     */
    val maxRetries: Int = 3,
    
    /**
     * 重试延迟（毫秒），默认1秒
     */
    val retryDelayMs: Long = 1000,
    
    /**
     * 图像压缩质量（0.0-1.0），默认0.8
     */
    val imageQuality: Float = 0.8f,
    
    /**
     * 最大图像尺寸（像素），超过此尺寸将自动压缩，默认1920x1080
     */
    val maxImageSize: Int = 1920,
    
    /**
     * 自定义请求头
     */
    val customHeaders: Map<String, String> = emptyMap()
)

/**
 * 认证方式
 */
enum class AuthType {
    /**
     * API Key认证（通过Header或Query参数）
     */
    API_KEY,
    
    /**
     * Bearer Token认证
     */
    BEARER_TOKEN,
    
    /**
     * 无认证
     */
    NONE
}
