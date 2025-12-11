package com.testwings.utils

import android.graphics.Bitmap
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Canvas
import android.util.Log
import kotlin.math.max
import kotlin.math.min

/**
 * 图像预处理工具类
 * 用于提升OCR识别准确率，通用高可靠性方案
 */
object ImagePreprocessor {
    
    private const val TAG = "ImagePreprocessor"
    
    /**
     * 预处理配置
     */
    data class PreprocessConfig(
        /** 是否启用对比度增强 */
        val enableContrastEnhancement: Boolean = true,
        /** 是否启用锐化 */
        val enableSharpening: Boolean = true,
        /** 是否启用二值化 */
        val enableBinarization: Boolean = false, // 默认关闭，保留颜色信息
        /** 是否转换为灰度图 */
        val convertToGrayscale: Boolean = false, // 默认关闭，保留颜色信息
        /** 是否使用自适应阈值（Otsu算法） */
        val useAdaptiveThreshold: Boolean = true, // 如果启用二值化，使用自适应阈值
        /** 对比度增强强度 (1.0-3.0，默认1.3) */
        val contrastStrength: Float = 1.3f,
        /** 锐化强度 (0.0-2.0，默认0.4) */
        val sharpeningStrength: Float = 0.4f,
        /** 二值化阈值 (0-255，默认128，仅在useAdaptiveThreshold=false时使用) */
        val binarizationThreshold: Int = 128
    )
    
    /**
     * 默认配置（通用高可靠性方案）
     * 使用适度的图像增强，保留颜色信息，适用于各种场景
     * 
     * 原理：
     * 1. 对比度增强：提升文字与背景的区分度，对OCR识别通常有帮助
     * 2. 锐化处理：增强文字边缘清晰度，提升识别准确率
     * 3. 保留颜色信息：ML Kit可能利用颜色信息提升识别率
     * 
     * 这些预处理是OCR领域的通用做法，通常能提升识别准确率
     */
    val defaultConfig = PreprocessConfig(
        enableContrastEnhancement = true,
        enableSharpening = true,
        enableBinarization = false, // 不二值化，保留颜色信息
        convertToGrayscale = false, // 保留颜色信息
        useAdaptiveThreshold = true,
        contrastStrength = 1.4f, // 适度的对比度增强（1.3-1.5是OCR常用范围）
        sharpeningStrength = 0.5f // 适度的锐化（0.4-0.6是OCR常用范围）
    )
    
    /**
     * 激进配置（如果默认配置效果不佳，可以尝试）
     * 使用更强的图像增强，包括二值化
     */
    val aggressiveConfig = PreprocessConfig(
        enableContrastEnhancement = true,
        enableSharpening = true,
        enableBinarization = true, // 启用二值化
        convertToGrayscale = true, // 先灰度化
        useAdaptiveThreshold = true, // 使用自适应阈值
        contrastStrength = 1.5f, // 更强的对比度增强
        sharpeningStrength = 0.5f
    )
    
    /**
     * 预处理图像
     * @param bitmap 原始图像
     * @param config 预处理配置
     * @return 预处理后的图像
     */
    fun preprocess(bitmap: Bitmap, config: PreprocessConfig = defaultConfig): Bitmap {
        var processed = bitmap
        
        try {
            Log.d(TAG, "开始图像预处理，原始尺寸: ${bitmap.width}x${bitmap.height}")
            
            // 处理顺序：灰度化 -> 对比度增强 -> 锐化 -> 二值化（黑白化）
            // 这个顺序可以最大化OCR识别准确率
            
            // 1. 转换为灰度图（如果需要）
            if (config.convertToGrayscale) {
                processed = convertToGrayscale(processed)
                Log.d(TAG, "已转换为灰度图")
            }
            
            // 2. 对比度增强（在二值化前增强对比度，有助于区分文字和背景）
            if (config.enableContrastEnhancement) {
                processed = enhanceContrast(processed, config.contrastStrength)
                Log.d(TAG, "已增强对比度，强度: ${config.contrastStrength}")
            }
            
            // 3. 锐化处理（在二值化前锐化，增强文字边缘）
            if (config.enableSharpening) {
                processed = sharpen(processed, config.sharpeningStrength)
                Log.d(TAG, "已锐化处理，强度: ${config.sharpeningStrength}")
            }
            
            // 4. 二值化处理（如果需要，使用自适应阈值）
            if (config.enableBinarization) {
                if (config.useAdaptiveThreshold) {
                    // 使用Otsu自适应阈值算法
                    val threshold = calculateOtsuThreshold(processed)
                    processed = binarize(processed, threshold)
                    Log.d(TAG, "已二值化处理（自适应阈值），阈值: $threshold")
                } else {
                    processed = binarize(processed, config.binarizationThreshold)
                    Log.d(TAG, "已二值化处理，阈值: ${config.binarizationThreshold}")
                }
            }
            
            Log.d(TAG, "图像预处理完成")
            return processed
        } catch (e: Exception) {
            Log.e(TAG, "图像预处理失败", e)
            // 如果预处理失败，返回原始图像
            return bitmap
        }
    }
    
    /**
     * 转换为灰度图
     */
    private fun convertToGrayscale(bitmap: Bitmap): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        val grayBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(grayBitmap)
        val paint = Paint()
        
        val colorMatrix = ColorMatrix()
        colorMatrix.setSaturation(0f) // 0表示完全灰度化
        paint.colorFilter = ColorMatrixColorFilter(colorMatrix)
        canvas.drawBitmap(bitmap, 0f, 0f, paint)
        
        return grayBitmap
    }
    
    /**
     * 增强对比度
     * 使用ColorMatrix调整对比度
     */
    private fun enhanceContrast(bitmap: Bitmap, strength: Float): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        val enhancedBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(enhancedBitmap)
        val paint = Paint()
        
        // 创建对比度调整矩阵
        // 对比度公式: (pixel - 128) * contrast + 128
        val contrast = strength.coerceIn(1.0f, 3.0f)
        val scale = contrast
        val translate = (-128f * scale + 128f)
        
        val colorMatrix = ColorMatrix(floatArrayOf(
            scale, 0f, 0f, 0f, translate,
            0f, scale, 0f, 0f, translate,
            0f, 0f, scale, 0f, translate,
            0f, 0f, 0f, 1f, 0f
        ))
        
        paint.colorFilter = ColorMatrixColorFilter(colorMatrix)
        canvas.drawBitmap(bitmap, 0f, 0f, paint)
        
        return enhancedBitmap
    }
    
    /**
     * 锐化处理
     * 使用Unsharp Mask算法
     */
    private fun sharpen(bitmap: Bitmap, strength: Float): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        val sharpenedBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        
        // 简单的锐化核（3x3）
        // 0  -1   0
        // -1  5  -1
        // 0  -1   0
        val kernel = floatArrayOf(
            0f, -1f, 0f,
            -1f, 5f, -1f,
            0f, -1f, 0f
        )
        
        // 应用强度调整
        for (i in kernel.indices) {
            if (kernel[i] != 0f) {
                kernel[i] *= strength
            }
        }
        // 中心值需要调整以保持亮度
        kernel[4] = 1f + 4f * strength
        
        applyConvolution(bitmap, sharpenedBitmap, kernel, 3)
        
        return sharpenedBitmap
    }
    
    /**
     * 应用卷积核
     */
    private fun applyConvolution(
        source: Bitmap,
        target: Bitmap,
        kernel: FloatArray,
        kernelSize: Int
    ) {
        val width = source.width
        val height = source.height
        val pixels = IntArray(width * height)
        source.getPixels(pixels, 0, width, 0, 0, width, height)
        val resultPixels = IntArray(width * height)
        
        val halfKernel = kernelSize / 2
        
        for (y in 0 until height) {
            for (x in 0 until width) {
                var r = 0f
                var g = 0f
                var b = 0f
                
                for (ky in 0 until kernelSize) {
                    for (kx in 0 until kernelSize) {
                        val px = (x + kx - halfKernel).coerceIn(0, width - 1)
                        val py = (y + ky - halfKernel).coerceIn(0, height - 1)
                        val pixel = pixels[py * width + px]
                        val kernelValue = kernel[ky * kernelSize + kx]
                        
                        r += (pixel shr 16 and 0xFF) * kernelValue
                        g += (pixel shr 8 and 0xFF) * kernelValue
                        b += (pixel and 0xFF) * kernelValue
                    }
                }
                
                val newR = r.coerceIn(0f, 255f).toInt()
                val newG = g.coerceIn(0f, 255f).toInt()
                val newB = b.coerceIn(0f, 255f).toInt()
                val alpha = pixels[y * width + x] shr 24 and 0xFF
                
                resultPixels[y * width + x] = (alpha shl 24) or (newR shl 16) or (newG shl 8) or newB
            }
        }
        
        target.setPixels(resultPixels, 0, width, 0, 0, width, height)
    }
    
    /**
     * 二值化处理
     * 将图像转换为黑白图像
     */
    private fun binarize(bitmap: Bitmap, threshold: Int): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        val binarizedBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        
        for (i in pixels.indices) {
            val pixel = pixels[i]
            val r = pixel shr 16 and 0xFF
            val g = pixel shr 8 and 0xFF
            val b = pixel and 0xFF
            // 计算灰度值
            val gray = (0.299 * r + 0.587 * g + 0.114 * b).toInt()
            
            // 二值化
            val newValue = if (gray > threshold) 255 else 0
            val alpha = pixel shr 24 and 0xFF
            
            pixels[i] = (alpha shl 24) or (newValue shl 16) or (newValue shl 8) or newValue
        }
        
        binarizedBitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        return binarizedBitmap
    }
    
    /**
     * 计算Otsu自适应阈值
     * Otsu算法可以自动找到最佳的二值化阈值，适用于各种图像
     */
    private fun calculateOtsuThreshold(bitmap: Bitmap): Int {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        
        // 计算灰度直方图
        val histogram = IntArray(256)
        for (pixel in pixels) {
            val r = pixel shr 16 and 0xFF
            val g = pixel shr 8 and 0xFF
            val b = pixel and 0xFF
            val gray = (0.299 * r + 0.587 * g + 0.114 * b).toInt()
            histogram[gray]++
        }
        
        // Otsu算法：找到使类间方差最大的阈值
        val totalPixels = width * height
        var sum = 0
        for (i in 0 until 256) {
            sum += i * histogram[i]
        }
        
        var sumB = 0
        var wB = 0
        var wF: Int
        var mB: Double
        var mF: Double
        var max = 0.0
        var threshold = 0
        
        for (t in 0 until 256) {
            wB += histogram[t]
            if (wB == 0) continue
            
            wF = totalPixels - wB
            if (wF == 0) break
            
            sumB += t * histogram[t]
            mB = sumB.toDouble() / wB
            mF = (sum - sumB).toDouble() / wF
            
            val between = wB.toDouble() * wF * (mB - mF) * (mB - mF)
            if (between > max) {
                max = between
                threshold = t
            }
        }
        
        Log.d(TAG, "Otsu算法计算出的最佳阈值: $threshold")
        return threshold
    }
    
    /**
     * 自适应对比度增强（CLAHE简化版）
     * 针对局部区域进行对比度调整
     */
    fun enhanceContrastAdaptive(bitmap: Bitmap, clipLimit: Float = 2.0f): Bitmap {
        // 简化实现：使用全局对比度增强
        // 完整CLAHE实现较复杂，这里使用简化版本
        return enhanceContrast(bitmap, 1.3f)
    }
}

