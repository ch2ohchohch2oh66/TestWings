package com.testwings.utils

import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * OCR识别器接口
 */
interface IOcrRecognizer {
    /**
     * 识别图片中的文字
     * @param bitmap 要识别的图片
     * @return OCR识别结果
     */
    suspend fun recognize(bitmap: Bitmap): OcrResult
    
    /**
     * 是否可用（是否已初始化）
     */
    fun isAvailable(): Boolean
}

/**
 * ML Kit OCR识别器实现（需要Google Play Services）
 */
class MlKitOcrRecognizer(private val context: android.content.Context) : IOcrRecognizer {
    
    private val TAG = "OcrRecognizer"
    
    // ML Kit 中文文本识别器
    private val recognizer = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
    
    override fun isAvailable(): Boolean {
        return try {
            // ML Kit 通常可用，除非设备不支持
            true
        } catch (e: Exception) {
            Log.e(TAG, "OCR识别器不可用", e)
            false
        }
    }
    
    override suspend fun recognize(bitmap: Bitmap): OcrResult = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "开始OCR识别（ML Kit），图片尺寸: ${bitmap.width}x${bitmap.height}")
            
            // 将Bitmap转换为InputImage
            val image = InputImage.fromBitmap(bitmap, 0)
            
            // 使用suspendCancellableCoroutine将回调转换为协程
            suspendCancellableCoroutine { continuation ->
                recognizer.process(image)
                    .addOnSuccessListener { visionText ->
                        try {
                            val textBlocks = mutableListOf<TextBlock>()
                            val fullTextBuilder = StringBuilder()
                            
                            // 遍历所有文本块
                            for (block in visionText.textBlocks) {
                                val blockText = block.text
                                val blockRect = block.boundingBox ?: Rect()
                                
                                // 添加文本块
                                textBlocks.add(
                                    TextBlock(
                                        text = blockText,
                                        boundingBox = blockRect,
                                        confidence = 1.0f // ML Kit不提供置信度，使用默认值
                                    )
                                )
                                
                                // 添加到完整文本
                                if (fullTextBuilder.isNotEmpty()) {
                                    fullTextBuilder.append("\n")
                                }
                                fullTextBuilder.append(blockText)
                            }
                            
                            val result = OcrResult(
                                fullText = fullTextBuilder.toString(),
                                textBlocks = textBlocks
                            )
                            
                            Log.d(TAG, "OCR识别成功，识别到 ${textBlocks.size} 个文本块，总文字: ${result.fullText.length} 字符")
                            continuation.resume(result)
                        } catch (e: Exception) {
                            Log.e(TAG, "处理OCR结果失败", e)
                            continuation.resume(
                                OcrResult(
                                    fullText = "",
                                    textBlocks = emptyList()
                                )
                            )
                        }
                    }
                    .addOnFailureListener { e ->
                        Log.e(TAG, "ML Kit OCR识别失败", e)
                        Log.e(TAG, "失败原因: ${e.message}")
                        // 如果是缺少Google Play Services的错误，给出明确提示
                        val errorMessage = e.message ?: ""
                        if (errorMessage.contains("Google Play Services") || 
                            errorMessage.contains("Google Play Store") ||
                            errorMessage.contains("com.google.android.gms")) {
                            Log.w(TAG, "❌ ML Kit需要Google Play Services，当前设备不支持")
                            Log.w(TAG, "建议：集成PaddleOCR以实现离线OCR识别")
                        }
                        continuation.resume(
                            OcrResult(
                                fullText = "",
                                textBlocks = emptyList()
                            )
                        )
                    }
            }
        } catch (e: Exception) {
            Log.e(TAG, "OCR识别异常", e)
            OcrResult(
                fullText = "",
                textBlocks = emptyList()
            )
        }
    }
    
}

/**
 * PaddleOCR识别器实现（完全离线，适用于HarmonyOS）
 * 
 * 注意：当前为占位实现，需要集成PaddleOCR库
 * 集成步骤：
 * 1. 添加PaddleOCR依赖到build.gradle.kts
 * 2. 下载PaddleOCR模型文件到assets目录
 * 3. 实现实际的OCR识别逻辑
 */
class PaddleOcrRecognizer(private val context: android.content.Context) : IOcrRecognizer {
    
    private val TAG = "PaddleOcrRecognizer"
    
    override fun isAvailable(): Boolean {
        // TODO: 检查PaddleOCR是否已初始化
        // 当前返回false，表示尚未集成
        return false
    }
    
    override suspend fun recognize(bitmap: android.graphics.Bitmap): OcrResult = 
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                Log.w(TAG, "PaddleOCR尚未集成，返回空结果")
                Log.i(TAG, "如需使用PaddleOCR，请参考以下步骤：")
                Log.i(TAG, "1. 添加PaddleOCR依赖到build.gradle.kts")
                Log.i(TAG, "2. 下载PaddleOCR模型文件")
                Log.i(TAG, "3. 实现OCR识别逻辑")
                
                OcrResult(
                    fullText = "",
                    textBlocks = emptyList()
                )
            } catch (e: Exception) {
                Log.e(TAG, "PaddleOCR识别失败", e)
                OcrResult(
                    fullText = "",
                    textBlocks = emptyList()
                )
            }
        }
}

