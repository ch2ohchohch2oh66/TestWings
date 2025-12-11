package com.testwings.testcase

import android.graphics.Point
import android.util.Log
import com.testwings.utils.DeviceController
import com.testwings.utils.OcrResult
import com.testwings.utils.TextBlock
import com.testwings.utils.SemanticMatcher
import kotlinx.coroutines.runBlocking

/**
 * 元素定位器
 * 结合OCR识别结果和Accessibility Service来定位元素
 * 
 * 智能匹配策略：
 * 1. 先尝试直接匹配（快速、准确）
 * 2. 如果直接匹配失败，使用大模型进行语义匹配
 * 3. 即使OCR识别错误（如"埔获屏幕"），也能匹配用例中的"捕获屏幕"
 */
object ElementLocator {
    
    private const val TAG = "ElementLocator"
    
    /**
     * 定位结果
     */
    data class LocateResult(
        /**
         * 是否定位成功
         */
        val success: Boolean,
        
        /**
         * 定位到的坐标（如果成功）
         */
        val point: Point? = null,
        
        /**
         * 错误信息（如果失败）
         */
        val error: String? = null
    )
    
    /**
     * 根据定位方式定位元素
     * @param locateBy 定位方式
     * @param value 定位值
     * @param ocrResult 当前屏幕的OCR识别结果（可选，用于TEXT定位）
     * @param useSemanticMatch 是否启用语义匹配（默认true）
     * @return 定位结果
     */
    fun locate(
        locateBy: LocateBy,
        value: String,
        ocrResult: OcrResult? = null,
        useSemanticMatch: Boolean = true
    ): LocateResult {
        return when (locateBy) {
            LocateBy.TEXT -> {
                locateByText(value, ocrResult, useSemanticMatch)
            }
            LocateBy.COORDINATE -> {
                locateByCoordinate(value)
            }
            LocateBy.RESOURCE_ID -> {
                locateByResourceId(value)
            }
        }
    }
    
    /**
     * 异步定位（支持协程）
     * 用于需要大模型语义匹配的场景
     */
    suspend fun locateAsync(
        locateBy: LocateBy,
        value: String,
        ocrResult: OcrResult? = null,
        useSemanticMatch: Boolean = true
    ): LocateResult {
        return when (locateBy) {
            LocateBy.TEXT -> {
                locateByTextAsync(value, ocrResult, useSemanticMatch)
            }
            LocateBy.COORDINATE -> {
                locateByCoordinate(value)
            }
            LocateBy.RESOURCE_ID -> {
                locateByResourceId(value)
            }
        }
    }
    
    /**
     * 根据文本定位（使用OCR识别结果）
     * 同步版本：如果启用语义匹配，会使用 runBlocking（可能阻塞）
     */
    private fun locateByText(
        text: String,
        ocrResult: OcrResult?,
        useSemanticMatch: Boolean
    ): LocateResult {
        if (ocrResult == null || !ocrResult.isSuccess) {
            return LocateResult(
                success = false,
                error = "OCR识别结果不可用，无法根据文本定位"
            )
        }
        
        // 如果启用语义匹配，使用异步版本（在 runBlocking 中调用）
        if (useSemanticMatch) {
            return runBlocking {
                locateByTextAsync(text, ocrResult, useSemanticMatch)
            }
        }
        
        // 否则使用简单的直接匹配
        val matchedBlock = ocrResult.textBlocks.find { block ->
            block.text.contains(text, ignoreCase = true)
        }
        
        return if (matchedBlock != null) {
            Log.d(TAG, "根据文本 '$text' 定位成功（直接匹配），位置: (${matchedBlock.centerX}, ${matchedBlock.centerY})")
            LocateResult(
                success = true,
                point = Point(matchedBlock.centerX, matchedBlock.centerY)
            )
        } else {
            Log.w(TAG, "未找到包含文本 '$text' 的元素（直接匹配失败）")
            LocateResult(
                success = false,
                error = "未找到包含文本 '$text' 的元素"
            )
        }
    }
    
    /**
     * 根据文本定位（异步版本，支持语义匹配）
     */
    private suspend fun locateByTextAsync(
        text: String,
        ocrResult: OcrResult?,
        useSemanticMatch: Boolean
    ): LocateResult {
        if (ocrResult == null || !ocrResult.isSuccess) {
            return LocateResult(
                success = false,
                error = "OCR识别结果不可用，无法根据文本定位"
            )
        }
        
        // 使用语义匹配器进行智能匹配
        val matchResult = SemanticMatcher.matchText(text, ocrResult, useSemanticMatch)
        
        return if (matchResult.success && matchResult.matchedBlock != null) {
            val matchTypeStr = when (matchResult.matchType) {
                SemanticMatcher.MatchType.DIRECT -> "直接匹配"
                SemanticMatcher.MatchType.SEMANTIC -> "语义匹配"
            }
            Log.d(TAG, "根据文本 '$text' 定位成功（$matchTypeStr，置信度: ${matchResult.confidence}），位置: (${matchResult.matchedBlock.centerX}, ${matchResult.matchedBlock.centerY})")
            LocateResult(
                success = true,
                point = Point(matchResult.matchedBlock.centerX, matchResult.matchedBlock.centerY)
            )
        } else {
            Log.w(TAG, "未找到匹配文本 '$text' 的元素: ${matchResult.error}")
            LocateResult(
                success = false,
                error = matchResult.error ?: "未找到匹配文本 '$text' 的元素"
            )
        }
    }
    
    /**
     * 根据坐标定位
     * @param value 坐标字符串，格式：x,y 或 x,y,width,height（后两个参数可选）
     */
    private fun locateByCoordinate(value: String): LocateResult {
        return try {
            val parts = value.split(",").map { it.trim().toInt() }
            if (parts.size < 2) {
                return LocateResult(
                    success = false,
                    error = "坐标格式错误，应为 'x,y' 或 'x,y,width,height'"
                )
            }
            
            val x = parts[0]
            val y = parts[1]
            
            // 如果有width和height，计算中心点
            val point = if (parts.size >= 4) {
                val width = parts[2]
                val height = parts[3]
                Point(x + width / 2, y + height / 2)
            } else {
                Point(x, y)
            }
            
            Log.d(TAG, "根据坐标 '$value' 定位成功，位置: (${point.x}, ${point.y})")
            LocateResult(success = true, point = point)
        } catch (e: Exception) {
            Log.e(TAG, "解析坐标失败: $value", e)
            LocateResult(
                success = false,
                error = "坐标格式错误: ${e.message}"
            )
        }
    }
    
    /**
     * 根据资源ID定位（使用Accessibility Service）
     * 注意：这个方法返回的是元素中心点的坐标
     */
    private fun locateByResourceId(resourceId: String): LocateResult {
        // 使用Accessibility Service查找元素
        val service = com.testwings.service.TestWingsAccessibilityService.getInstance()
            ?: return LocateResult(
                success = false,
                error = "无障碍服务未启用"
            )
        
        val node = service.findNodeByResourceId(resourceId)
        return if (node != null) {
            try {
                val rect = android.graphics.Rect()
                node.getBoundsInScreen(rect)
                val point = Point(rect.centerX(), rect.centerY())
                node.recycle()
                
                Log.d(TAG, "根据资源ID '$resourceId' 定位成功，位置: (${point.x}, ${point.y})")
                LocateResult(success = true, point = point)
            } catch (e: Exception) {
                node.recycle()
                Log.e(TAG, "获取元素位置失败", e)
                LocateResult(
                    success = false,
                    error = "获取元素位置失败: ${e.message}"
                )
            }
        } else {
            Log.w(TAG, "未找到资源ID为 '$resourceId' 的元素")
            LocateResult(
                success = false,
                error = "未找到资源ID为 '$resourceId' 的元素"
            )
        }
    }
}

