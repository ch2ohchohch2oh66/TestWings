package com.testwings.utils

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

/**
 * API方式的Vision-Language服务实现
 * 通过HTTP API调用云端或局域网的VL模型服务
 */
class ApiVisionLanguageService(
    private val context: Context,
    private val config: ApiVisionLanguageServiceConfig
) : IVisionLanguageService {
    
    private val TAG = "ApiVisionLanguageService"
    
    /**
     * 当前加载状态（API方式通常不需要加载，但保持接口一致性）
     */
    private var loadState: LoadState = LoadState.NOT_LOADED
    
    override suspend fun understand(screenshot: Bitmap): ScreenState {
        return withContext(Dispatchers.IO) {
            try {
                // 1. 压缩图像
                val compressedImage = compressImage(screenshot)
                
                // 2. 转换为Base64
                val imageBase64 = bitmapToBase64(compressedImage)
                
                // 3. 构建请求
                val requestBody = buildRequestBody(imageBase64)
                
                // 4. 发送请求（带重试）
                val response = sendRequestWithRetry(requestBody)
                
                // 5. 解析响应
                parseResponse(response)
            } catch (e: Exception) {
                Log.e(TAG, "API VL模型推理失败", e)
                ScreenState(
                    elements = emptyList(),
                    semanticDescription = "",
                    vlAvailable = false
                )
            }
        }
    }
    
    override fun getLoadState(): LoadState {
        // API方式通常不需要加载，但为了接口一致性，返回LOADED
        return if (config.endpoint.isNotEmpty()) {
            LoadState.LOADED
        } else {
            LoadState.NOT_LOADED
        }
    }
    
    override suspend fun loadModel(onProgress: ((Int) -> Unit)?): Boolean {
        // API方式不需要加载模型，但可以测试连接
        return withContext(Dispatchers.IO) {
            try {
                onProgress?.invoke(50)
                // 简单的连接测试（可选）
                // 这里可以发送一个测试请求验证API是否可用
                onProgress?.invoke(100)
                loadState = LoadState.LOADED
                true
            } catch (e: Exception) {
                Log.e(TAG, "API连接测试失败", e)
                loadState = LoadState.FAILED
                false
            }
        }
    }
    
    override fun isModelAvailable(): Boolean {
        // API方式检查端点是否配置
        return config.endpoint.isNotEmpty()
    }
    
    override fun release() {
        // API方式不需要释放资源
        loadState = LoadState.NOT_LOADED
    }
    
    override fun getServiceType(): String {
        return "API"
    }
    
    /**
     * 压缩图像
     */
    private fun compressImage(bitmap: Bitmap): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        
        // 如果图像尺寸超过最大尺寸，进行缩放
        if (width > config.maxImageSize || height > config.maxImageSize) {
            val scale = config.maxImageSize.toFloat() / maxOf(width, height)
            val newWidth = (width * scale).toInt()
            val newHeight = (height * scale).toInt()
            return Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
        }
        
        return bitmap
    }
    
    /**
     * 将Bitmap转换为Base64字符串
     */
    private fun bitmapToBase64(bitmap: Bitmap): String {
        val outputStream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, (config.imageQuality * 100).toInt(), outputStream)
        val byteArray = outputStream.toByteArray()
        return Base64.encodeToString(byteArray, Base64.NO_WRAP)
    }
    
    /**
     * 构建请求体
     */
    private fun buildRequestBody(imageBase64: String): JSONObject {
        return JSONObject().apply {
            put("image", imageBase64)
            // 可以根据API要求添加其他参数
            // put("prompt", "请识别屏幕上的所有UI元素")
        }
    }
    
    /**
     * 发送请求（带重试机制）
     */
    private suspend fun sendRequestWithRetry(requestBody: JSONObject): String {
        var lastException: Exception? = null
        
        for (attempt in 1..config.maxRetries) {
            try {
                val connection = URL(config.endpoint).openConnection() as HttpURLConnection
                connection.requestMethod = "POST"
                connection.setRequestProperty("Content-Type", "application/json")
                connection.connectTimeout = config.timeoutMs.toInt()
                connection.readTimeout = config.timeoutMs.toInt()
                
                // 设置认证头
                when (config.authType) {
                    AuthType.API_KEY -> {
                        config.apiKey?.let {
                            connection.setRequestProperty("X-API-Key", it)
                        }
                    }
                    AuthType.BEARER_TOKEN -> {
                        config.bearerToken?.let {
                            connection.setRequestProperty("Authorization", "Bearer $it")
                        }
                    }
                    AuthType.NONE -> {
                        // 无认证
                    }
                }
                
                // 设置自定义请求头
                config.customHeaders.forEach { (key, value) ->
                    connection.setRequestProperty(key, value)
                }
                
                connection.doOutput = true
                connection.outputStream.use { output ->
                    output.write(requestBody.toString().toByteArray(Charsets.UTF_8))
                }
                
                val responseCode = connection.responseCode
                if (responseCode == HttpURLConnection.HTTP_OK) {
                    val response = connection.inputStream.bufferedReader().use { it.readText() }
                    return response
                } else {
                    val errorResponse = connection.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                    throw Exception("API请求失败: HTTP $responseCode - $errorResponse")
                }
            } catch (e: Exception) {
                lastException = e
                Log.w(TAG, "API请求失败 (尝试 $attempt/${config.maxRetries}): ${e.message}")
                if (attempt < config.maxRetries) {
                    delay(config.retryDelayMs)
                }
            }
        }
        
        throw lastException ?: Exception("API请求失败: 未知错误")
    }
    
    /**
     * 解析API响应
     * 支持多种常见的API响应格式：
     * 1. 标准格式：{ "elements": [...], "semantic_description": "..." }
     * 2. 简化格式：{ "ui_elements": [...], "description": "..." }
     * 3. 嵌套格式：{ "data": { "elements": [...], "description": "..." } }
     */
    private fun parseResponse(response: String): ScreenState {
        try {
            val json = JSONObject(response)
            
            // 尝试从不同路径获取数据
            val dataJson = json.optJSONObject("data") ?: json
            val resultJson = json.optJSONObject("result") ?: dataJson
            
            // 获取语义描述（支持多种字段名）
            val semanticDescription = resultJson.optString("semantic_description", "")
                .takeIf { it.isNotEmpty() }
                ?: resultJson.optString("description", "")
                .takeIf { it.isNotEmpty() }
                ?: resultJson.optString("summary", "")
                .takeIf { it.isNotEmpty() }
                ?: ""
            
            // 获取元素列表（支持多种字段名）
            val elementsJson = resultJson.optJSONArray("elements")
                ?: resultJson.optJSONArray("ui_elements")
                ?: resultJson.optJSONArray("items")
                ?: json.optJSONArray("elements")
                ?: json.optJSONArray("ui_elements")
            
            val elements = mutableListOf<UIElement>()
            
            if (elementsJson != null) {
                for (i in 0 until elementsJson.length()) {
                    try {
                        val elementJson = elementsJson.getJSONObject(i)
                        val element = parseUIElement(elementJson)
                        if (element != null) {
                            elements.add(element)
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "解析元素[$i]失败，跳过", e)
                    }
                }
            }
            
            Log.d(TAG, "API响应解析完成: elements=${elements.size}, descriptionLength=${semanticDescription.length}")
            
            return ScreenState(
                elements = elements,
                semanticDescription = semanticDescription,
                vlAvailable = true
            )
        } catch (e: Exception) {
            Log.e(TAG, "解析API响应失败", e)
            throw e
        }
    }
    
    /**
     * 解析单个UI元素
     * 支持多种格式：
     * 1. 标准格式：{ "type": "BUTTON", "text": "...", "bounds": {...}, "center": {...} }
     * 2. 坐标格式：{ "type": "BUTTON", "text": "...", "x": 100, "y": 200, "width": 50, "height": 30 }
     * 3. 边界框格式：{ "type": "BUTTON", "text": "...", "left": 100, "top": 200, "right": 150, "bottom": 230 }
     */
    private fun parseUIElement(elementJson: JSONObject): UIElement? {
        try {
            // 解析类型
            val typeStr = elementJson.optString("type", "OTHER").uppercase()
            val type = try {
                UIElementType.valueOf(typeStr)
            } catch (e: Exception) {
                // 尝试映射常见类型
                when (typeStr) {
                    "BUTTON", "BTN" -> UIElementType.BUTTON
                    "INPUT", "TEXT_FIELD", "EDIT_TEXT" -> UIElementType.INPUT
                    "TEXT", "LABEL" -> UIElementType.TEXT
                    "IMAGE", "ICON", "IMG" -> UIElementType.IMAGE
                    else -> UIElementType.OTHER
                }
            }
            
            // 解析文本
            val text = elementJson.optString("text", "")
                .takeIf { it.isNotEmpty() }
                ?: elementJson.optString("label", "")
                .takeIf { it.isNotEmpty() }
                ?: elementJson.optString("content", "")
            
            // 解析边界框和中心点
            val bounds: android.graphics.Rect
            val center: android.graphics.Point
            
            // 尝试从bounds对象解析
            val boundsJson = elementJson.optJSONObject("bounds")
            if (boundsJson != null) {
                val left = boundsJson.optInt("left", 0)
                val top = boundsJson.optInt("top", 0)
                val right = boundsJson.optInt("right", 0)
                val bottom = boundsJson.optInt("bottom", 0)
                bounds = android.graphics.Rect(left, top, right, bottom)
                center = android.graphics.Point(
                    bounds.centerX(),
                    bounds.centerY()
                )
            }
            // 尝试从center和size解析
            else if (elementJson.has("center") && elementJson.has("width") && elementJson.has("height")) {
                val centerJson = elementJson.getJSONObject("center")
                val centerX = centerJson.optInt("x", 0)
                val centerY = centerJson.optInt("y", 0)
                val width = elementJson.optInt("width", 0)
                val height = elementJson.optInt("height", 0)
                center = android.graphics.Point(centerX, centerY)
                bounds = android.graphics.Rect(
                    centerX - width / 2,
                    centerY - height / 2,
                    centerX + width / 2,
                    centerY + height / 2
                )
            }
            // 尝试从x, y, width, height解析
            else if (elementJson.has("x") && elementJson.has("y") && elementJson.has("width") && elementJson.has("height")) {
                val x = elementJson.optInt("x", 0)
                val y = elementJson.optInt("y", 0)
                val width = elementJson.optInt("width", 0)
                val height = elementJson.optInt("height", 0)
                bounds = android.graphics.Rect(x, y, x + width, y + height)
                center = android.graphics.Point(bounds.centerX(), bounds.centerY())
            }
            // 尝试从left, top, right, bottom解析
            else if (elementJson.has("left") && elementJson.has("top") && elementJson.has("right") && elementJson.has("bottom")) {
                val left = elementJson.optInt("left", 0)
                val top = elementJson.optInt("top", 0)
                val right = elementJson.optInt("right", 0)
                val bottom = elementJson.optInt("bottom", 0)
                bounds = android.graphics.Rect(left, top, right, bottom)
                center = android.graphics.Point(bounds.centerX(), bounds.centerY())
            }
            // 如果都没有，返回null（无效元素）
            else {
                Log.w(TAG, "无法解析元素边界框，缺少必要字段")
                return null
            }
            
            // 解析置信度
            val confidence = elementJson.optDouble("confidence", 1.0).toFloat()
                .coerceIn(0.0f, 1.0f)
            
            // 解析语义描述
            val semanticDescription = elementJson.optString("semantic_description", "")
                .takeIf { it.isNotEmpty() }
                ?: elementJson.optString("description", "")
            
            // 解析额外属性
            val attributes = mutableMapOf<String, Any>()
            elementJson.keys().forEach { key ->
                if (key !in listOf("type", "text", "label", "content", "bounds", "center", 
                        "x", "y", "width", "height", "left", "top", "right", "bottom",
                        "confidence", "semantic_description", "description")) {
                    try {
                        val value = elementJson.get(key)
                        attributes[key] = value
                    } catch (e: Exception) {
                        // 忽略无法解析的属性
                    }
                }
            }
            
            return UIElement(
                type = type,
                text = text,
                bounds = bounds,
                center = center,
                confidence = confidence,
                semanticDescription = semanticDescription,
                attributes = attributes
            )
        } catch (e: Exception) {
            Log.e(TAG, "解析UI元素失败", e)
            return null
        }
    }
}
