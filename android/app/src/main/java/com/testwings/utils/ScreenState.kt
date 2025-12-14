package com.testwings.utils

import android.graphics.Point
import android.graphics.Rect

/**
 * 屏幕状态（VL模型识别的结果）
 * 包含屏幕上的所有UI元素和语义描述
 */
data class ScreenState(
    /**
     * 屏幕上的所有UI元素列表
     */
    val elements: List<UIElement>,
    
    /**
     * 屏幕的语义描述（VL模型生成的文本描述）
     */
    val semanticDescription: String = "",
    
    /**
     * VL模型是否可用
     */
    val vlAvailable: Boolean = true,
    
    /**
     * OCR降级结果（如果VL不可用时使用）
     */
    val ocrResult: OcrResult? = null,
    
    /**
     * 识别时间戳（毫秒）
     */
    val timestamp: Long = System.currentTimeMillis()
) {
    /**
     * 是否识别成功
     */
    val isSuccess: Boolean
        get() = elements.isNotEmpty() || ocrResult?.isSuccess == true
    
    /**
     * 根据文本查找元素
     */
    fun findElementByText(text: String, useSemanticMatch: Boolean = false): UIElement? {
        return elements.find { element ->
            when {
                // 直接匹配
                element.text.equals(text, ignoreCase = true) -> true
                element.text.contains(text, ignoreCase = true) -> true
                text.contains(element.text, ignoreCase = true) -> true
                // 语义匹配（如果启用，需要LLM支持，这里先返回false）
                useSemanticMatch -> false // TODO: 实现语义匹配
                else -> false
            }
        }
    }
    
    /**
     * 根据类型查找元素
     */
    fun findElementsByType(type: UIElementType): List<UIElement> {
        return elements.filter { it.type == type }
    }
}

/**
 * UI元素类型
 */
enum class UIElementType {
    /**
     * 按钮
     */
    BUTTON,
    
    /**
     * 输入框
     */
    INPUT,
    
    /**
     * 文本
     */
    TEXT,
    
    /**
     * 图片/图标
     */
    IMAGE,
    
    /**
     * 其他元素
     */
    OTHER
}

/**
 * UI元素（VL模型识别的单个元素）
 */
data class UIElement(
    /**
     * 元素类型
     */
    val type: UIElementType,
    
    /**
     * 元素文本内容（如果有）
     */
    val text: String = "",
    
    /**
     * 元素位置（边界框）
     */
    val bounds: Rect,
    
    /**
     * 元素中心点坐标（用于点击操作）
     */
    val center: Point,
    
    /**
     * 识别置信度（0.0 - 1.0）
     */
    val confidence: Float = 1.0f,
    
    /**
     * 元素的语义描述（VL模型生成）
     */
    val semanticDescription: String = "",
    
    /**
     * 元素的额外属性（颜色、大小等）
     */
    val attributes: Map<String, Any> = emptyMap()
) {
    /**
     * 元素中心点X坐标
     */
    val centerX: Int
        get() = center.x
    
    /**
     * 元素中心点Y坐标
     */
    val centerY: Int
        get() = center.y
    
    /**
     * 元素宽度
     */
    val width: Int
        get() = bounds.width()
    
    /**
     * 元素高度
     */
    val height: Int
        get() = bounds.height()
}
