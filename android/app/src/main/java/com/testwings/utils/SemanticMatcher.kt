package com.testwings.utils

import android.graphics.Point
import android.util.Log
import com.testwings.utils.OcrResult
import com.testwings.utils.ScreenState
import com.testwings.utils.TextBlock
import com.testwings.utils.UIElement

/**
 * 语义匹配器
 * 使用大模型进行智能文本匹配，解决OCR识别错误的问题
 * 
 * 功能：
 * 1. 智能文本匹配：即使OCR识别错误（如"埔获屏幕"），也能匹配用例中的"捕获屏幕"
 * 2. 语义理解：理解文本的语义，而不仅仅是字面匹配
 * 3. 智能选择：简单场景用OCR直接匹配，复杂场景用大模型语义匹配
 */
object SemanticMatcher {
    
    private const val TAG = "SemanticMatcher"
    
    /**
     * 匹配结果
     */
    data class MatchResult(
        /**
         * 是否匹配成功
         */
        val success: Boolean,
        
        /**
         * 匹配到的文本块（OCR匹配，如果成功）
         */
        val matchedBlock: TextBlock? = null,
        
        /**
         * 匹配到的UI元素（VL匹配，如果成功）
         */
        val matchedElement: UIElement? = null,
        
        /**
         * 匹配置信度（0.0-1.0）
         */
        val confidence: Float = 0.0f,
        
        /**
         * 匹配方式（DIRECT=直接匹配，SEMANTIC=语义匹配，VL=VL模型匹配）
         */
        val matchType: MatchType = MatchType.DIRECT,
        
        /**
         * 错误信息（如果失败）
         */
        val error: String? = null
    )
    
    /**
     * 匹配方式
     */
    enum class MatchType {
        /**
         * 直接匹配：OCR结果与目标文本完全匹配或包含
         */
        DIRECT,
        
        /**
         * 语义匹配：使用大模型进行语义理解匹配（OCR）
         */
        SEMANTIC,
        
        /**
         * VL模型匹配：使用Vision-Language模型进行语义匹配
         */
        VL
    }
    
    /**
     * 智能匹配文本
     * 
     * 策略：
     * 1. 优先使用VL模型进行语义匹配（如果可用）
     * 2. 如果VL不可用，使用OCR + 直接匹配
     * 3. 如果直接匹配失败，使用大模型进行语义匹配
     * 
     * @param targetText 目标文本（来自测试用例）
     * @param screenState 当前屏幕的VL识别结果（优先使用，可选）
     * @param ocrResult OCR识别结果（降级方案，可选）
     * @param useSemanticMatch 是否启用语义匹配（默认true）
     * @return 匹配结果
     */
    suspend fun matchText(
        targetText: String,
        screenState: ScreenState? = null,
        ocrResult: OcrResult? = null,
        useSemanticMatch: Boolean = true
    ): MatchResult {
        // 第一步：优先使用VL模型进行语义匹配（如果可用）
        if (screenState != null && screenState.vlAvailable && screenState.elements.isNotEmpty()) {
            val vlResult = matchTextWithVL(targetText, screenState)
            if (vlResult.success) {
                Log.d(TAG, "VL模型语义匹配成功: '$targetText' -> '${vlResult.matchedElement?.text}' (置信度: ${vlResult.confidence})")
                return vlResult
            }
            Log.d(TAG, "VL模型语义匹配失败，降级到OCR")
        }
        
        // 第二步：降级到OCR匹配
        return matchTextWithOCR(targetText, ocrResult, useSemanticMatch)
    }
    
    /**
     * 使用OCR进行文本匹配（原有逻辑）
     */
    private suspend fun matchTextWithOCR(
        targetText: String,
        ocrResult: OcrResult?,
        useSemanticMatch: Boolean
    ): MatchResult {
        if (ocrResult == null || !ocrResult.isSuccess) {
            return MatchResult(
                success = false,
                error = "OCR识别结果不可用"
            )
        }
        
        // 第一步：尝试直接匹配（快速、准确）
        val directMatch = tryDirectMatch(targetText, ocrResult)
        if (directMatch.success) {
            Log.d(TAG, "直接匹配成功: '$targetText' -> '${directMatch.matchedBlock?.text}'")
            return directMatch
        }
        
        // 第二步：如果直接匹配失败且启用语义匹配，使用大模型进行语义匹配
        if (useSemanticMatch) {
            Log.d(TAG, "直接匹配失败，尝试语义匹配: '$targetText'")
            return trySemanticMatch(targetText, ocrResult)
        }
        
        // 直接匹配失败且未启用语义匹配
        return MatchResult(
            success = false,
            error = "未找到匹配的文本，且未启用语义匹配"
        )
    }
    
    /**
     * 尝试直接匹配
     * 使用简单的字符串包含匹配
     */
    private fun tryDirectMatch(
        targetText: String,
        ocrResult: OcrResult
    ): MatchResult {
        // 在OCR结果中查找完全匹配或包含的文本块
        val matchedBlock = ocrResult.textBlocks.find { block ->
            // 完全匹配（忽略大小写）
            block.text.equals(targetText, ignoreCase = true) ||
            // 包含匹配（忽略大小写）
            block.text.contains(targetText, ignoreCase = true) ||
            // 反向包含（目标文本包含OCR结果）
            targetText.contains(block.text, ignoreCase = true)
        }
        
        return if (matchedBlock != null) {
            // 计算置信度：完全匹配=1.0，包含匹配=0.8
            val confidence = if (matchedBlock.text.equals(targetText, ignoreCase = true)) {
                1.0f
            } else {
                0.8f
            }
            
            MatchResult(
                success = true,
                matchedBlock = matchedBlock,
                confidence = confidence,
                matchType = MatchType.DIRECT
            )
        } else {
            MatchResult(
                success = false,
                matchType = MatchType.DIRECT
            )
        }
    }
    
    /**
     * 尝试语义匹配
     * 使用大模型进行语义理解匹配
     * 
     * 注意：当前为占位实现，需要集成大模型（LLM或Vision-Language模型）
     */
    private suspend fun trySemanticMatch(
        targetText: String,
        ocrResult: OcrResult
    ): MatchResult {
        // TODO: 集成大模型进行语义匹配
        // 方案1：使用本地LLM（Qwen-1.8B/7B）
        // 方案2：使用Vision-Language模型（Qwen-VL）
        // 方案3：使用云端API（GPT-4V、Claude等）
        
        Log.w(TAG, "语义匹配功能尚未实现，需要集成大模型")
        Log.i(TAG, "目标文本: '$targetText'")
        Log.i(TAG, "OCR结果: ${ocrResult.textBlocks.map { it.text }}")
        
        // 临时实现：使用简单的相似度算法（编辑距离）
        // 这是一个fallback方案，实际应该使用大模型
        val bestMatch = findBestMatchBySimilarity(targetText, ocrResult)
        
        return if (bestMatch != null && bestMatch.second > 0.6f) {
            // 相似度 > 0.6，认为匹配成功
            Log.d(TAG, "相似度匹配成功: '$targetText' -> '${bestMatch.first.text}' (相似度: ${bestMatch.second})")
            MatchResult(
                success = true,
                matchedBlock = bestMatch.first,
                confidence = bestMatch.second,
                matchType = MatchType.SEMANTIC
            )
        } else {
            MatchResult(
                success = false,
                error = "语义匹配失败：未找到语义相似的文本"
            )
        }
    }
    
    /**
     * 使用编辑距离计算相似度（临时方案）
     * 实际应该使用大模型进行语义匹配
     */
    private fun findBestMatchBySimilarity(
        targetText: String,
        ocrResult: OcrResult
    ): Pair<TextBlock, Float>? {
        var bestMatch: TextBlock? = null
        var bestSimilarity = 0.0f
        
        for (block in ocrResult.textBlocks) {
            val similarity = calculateSimilarity(targetText, block.text)
            if (similarity > bestSimilarity) {
                bestSimilarity = similarity
                bestMatch = block
            }
        }
        
        return if (bestMatch != null) {
            Pair(bestMatch, bestSimilarity)
        } else {
            null
        }
    }
    
    /**
     * 计算两个文本的相似度（使用编辑距离）
     * 返回0.0-1.0之间的值，1.0表示完全相同
     */
    private fun calculateSimilarity(text1: String, text2: String): Float {
        val distance = levenshteinDistance(text1.lowercase(), text2.lowercase())
        val maxLength = maxOf(text1.length, text2.length)
        return if (maxLength == 0) {
            1.0f
        } else {
            1.0f - (distance.toFloat() / maxLength)
        }
    }
    
    /**
     * 计算编辑距离（Levenshtein距离）
     */
    private fun levenshteinDistance(s1: String, s2: String): Int {
        val m = s1.length
        val n = s2.length
        val dp = Array(m + 1) { IntArray(n + 1) }
        
        for (i in 0..m) {
            dp[i][0] = i
        }
        for (j in 0..n) {
            dp[0][j] = j
        }
        
        for (i in 1..m) {
            for (j in 1..n) {
                val cost = if (s1[i - 1] == s2[j - 1]) 0 else 1
                dp[i][j] = minOf(
                    dp[i - 1][j] + 1,      // 删除
                    dp[i][j - 1] + 1,      // 插入
                    dp[i - 1][j - 1] + cost // 替换
                )
            }
        }
        
        return dp[m][n]
    }
    
    /**
     * 使用VL模型进行语义匹配
     * 
     * 策略：
     * 1. 先尝试直接文本匹配（快速、准确）
     * 2. 如果直接匹配失败，使用相似度算法进行语义匹配
     * 
     * @param targetText 目标文本
     * @param screenState VL模型识别的屏幕状态
     * @return 匹配结果
     */
    private fun matchTextWithVL(
        targetText: String,
        screenState: ScreenState
    ): MatchResult {
        // 第一步：尝试直接文本匹配
        val directMatch = screenState.findElementByText(targetText, useSemanticMatch = false)
        if (directMatch != null) {
            return MatchResult(
                success = true,
                matchedElement = directMatch,
                confidence = directMatch.confidence,
                matchType = MatchType.VL
            )
        }
        
        // 第二步：使用相似度算法进行语义匹配
        var bestMatch: UIElement? = null
        var bestSimilarity = 0.0f
        
        for (element in screenState.elements) {
            if (element.text.isNotEmpty()) {
                val similarity = calculateSimilarity(targetText, element.text)
                if (similarity > bestSimilarity) {
                    bestSimilarity = similarity
                    bestMatch = element
                }
            }
        }
        
        // 如果相似度 > 0.6，认为匹配成功
        if (bestMatch != null && bestSimilarity > 0.6f) {
            // 综合置信度：相似度 * 元素置信度
            val combinedConfidence = bestSimilarity * bestMatch.confidence
            return MatchResult(
                success = true,
                matchedElement = bestMatch,
                confidence = combinedConfidence,
                matchType = MatchType.VL
            )
        }
        
        return MatchResult(
            success = false,
            error = "VL模型未找到语义相似的元素"
        )
    }
    
    /**
     * 使用大模型进行语义匹配（待实现）
     * 
     * 这个方法是未来集成大模型后的实际实现
     * 
     * @param targetText 目标文本
     * @param ocrTexts OCR识别的文本列表
     * @return 匹配结果列表，按相似度排序
     */
    suspend fun semanticMatchWithLLM(
        targetText: String,
        ocrTexts: List<String>
    ): List<Pair<String, Float>> {
        // TODO: 集成大模型
        // 方案1：使用本地LLM（Qwen-1.8B/7B）
        // 方案2：使用Vision-Language模型（Qwen-VL）
        // 方案3：使用云端API（GPT-4V、Claude等）
        
        // 示例Prompt：
        // """
        // 你是一个文本匹配专家。请判断以下文本列表中，哪些文本与目标文本语义相似。
        // 
        // 目标文本：捕获屏幕
        // OCR识别结果：["埔获屏幕", "捕获屏幕", "点击中心", "刷新"]
        // 
        // 请返回JSON格式的匹配结果，包含每个文本的相似度分数（0.0-1.0）。
        // """
        
        Log.w(TAG, "大模型语义匹配功能尚未实现")
        return emptyList()
    }
}

