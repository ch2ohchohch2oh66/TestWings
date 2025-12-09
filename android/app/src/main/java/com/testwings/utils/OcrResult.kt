package com.testwings.utils

import android.graphics.Rect

/**
 * OCR识别结果
 */
data class OcrResult(
    /**
     * 识别出的完整文字（所有文字块拼接）
     */
    val fullText: String,
    
    /**
     * 文字块列表
     */
    val textBlocks: List<TextBlock>
) {
    /**
     * 是否识别成功
     */
    val isSuccess: Boolean
        get() = textBlocks.isNotEmpty()
}

/**
 * 文字块（一行或一个区域的文字）
 */
data class TextBlock(
    /**
     * 文字内容
     */
    val text: String,
    
    /**
     * 文字位置（边界框）
     */
    val boundingBox: Rect,
    
    /**
     * 置信度（0.0 - 1.0）
     */
    val confidence: Float = 1.0f
) {
    /**
     * 文字中心点X坐标
     */
    val centerX: Int
        get() = boundingBox.centerX()
    
    /**
     * 文字中心点Y坐标
     */
    val centerY: Int
        get() = boundingBox.centerY()
}

