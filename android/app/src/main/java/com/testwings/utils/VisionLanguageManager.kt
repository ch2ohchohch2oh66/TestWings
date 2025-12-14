package com.testwings.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.os.Environment
import android.util.Log
import ai.onnxruntime.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.LongBuffer
import kotlin.math.ceil

/**
 * Vision-Language模型管理器
 * 负责VL模型的加载、推理和管理
 */
class VisionLanguageManager(private val context: Context) {
    
    private val TAG = "VisionLanguageManager"
    
    /**
     * 模型加载状态
     */
    enum class LoadState {
        /**
         * 未加载
         */
        NOT_LOADED,
        
        /**
         * 加载中
         */
        LOADING,
        
        /**
         * 已加载
         */
        LOADED,
        
        /**
         * 加载失败
         */
        FAILED
    }
    
    /**
     * 当前加载状态
     */
    private var loadState: LoadState = LoadState.NOT_LOADED
    
    /**
     * ONNX Runtime环境
     */
    private var ortEnv: OrtEnvironment? = null
    
    /**
     * ONNX Runtime会话（解码器模型）
     */
    private var ortSession: OrtSession? = null
    
    /**
     * ONNX Runtime会话（视觉编码器模型）
     */
    private var visionEncoderSession: OrtSession? = null
    
    /**
     * Tokenizer（待实现，可能需要使用Hugging Face的tokenizer库或自定义实现）
     */
    private var tokenizer: Any? = null
    
    /**
     * 模型文件目录（外部存储）
     */
    private val modelsDir: File by lazy {
        val externalFilesDir = context.getExternalFilesDir(null)
            ?: context.filesDir // 降级到内部存储
        File(externalFilesDir, "models/vl").apply {
            if (!exists()) {
                mkdirs()
            }
        }
    }
    
    /**
     * 解码器模型文件路径（外部存储）
     * 通过文件后缀自动发现 .onnx 模型文件，按优先级查找：
     * 1. decoder_model_merged_q4f16.onnx (Q4F16量化版本，推荐) ⭐
     * 2. decoder_model_merged.onnx (未量化版本)
     * 3. decoder_model_merged_int8.onnx (INT8量化版本，不推荐，可能不支持ConvInteger)
     * 4. 其他任何包含 "decoder" 的 .onnx 文件（兼容性）
     */
    private val decoderModelFile: File
        get() {
            // 按优先级查找解码器模型文件
            // 注意：INT8量化版本使用ConvInteger操作符，ONNX Runtime Android不支持
            val priorityFiles = listOf(
                "decoder_model_merged_q4f16.onnx",     // Q4F16量化（推荐，兼容性最好）⭐
                "decoder_model_merged.onnx",           // 未量化版本
                "decoder_model_merged_int8.onnx"        // INT8量化（不推荐，可能不支持）
            )
            
            // 先按优先级查找
            for (fileName in priorityFiles) {
                val file = File(modelsDir, fileName)
                if (file.exists() && file.length() > 0) {
                    // 检查是否是支持的模型格式
                    if (fileName.contains("int8", ignoreCase = true) && !fileName.contains("q4f16", ignoreCase = true)) {
                        Log.e(TAG, "❌ 检测到INT8量化模型: $fileName")
                        Log.e(TAG, "❌ ONNX Runtime Android不支持ConvInteger操作符，此模型无法使用")
                        Log.e(TAG, "❌ 请使用Q4F16量化版本（decoder_model_merged_q4f16.onnx）")
                        // 继续查找，不返回INT8版本
                        continue
                    }
                    // 验证文件大小是否合理（Q4F16解码器约829MB，未量化版本更大）
                    val fileSizeMB = file.length() / 1024L / 1024L
                    if (fileName.contains("q4f16", ignoreCase = true)) {
                        if (fileSizeMB < 500 || fileSizeMB > 1500) {
                            Log.w(TAG, "⚠️ Q4F16解码器模型文件大小异常: ${fileSizeMB}MB（预期约829MB）")
                        }
                    }
                    Log.d(TAG, "✅ 找到解码器模型: $fileName (${fileSizeMB}MB)")
                    return file
                }
            }
            
            // 如果优先级文件都不存在，查找任何包含 "decoder" 的 .onnx 文件
            modelsDir.listFiles()?.forEach { file ->
                if (file.isFile && file.name.endsWith(".onnx", ignoreCase = true) && 
                    file.name.contains("decoder", ignoreCase = true) && file.length() > 0) {
                    Log.d(TAG, "发现解码器模型文件: ${file.name}")
                    // 检查是否是INT8版本
                    if (file.name.contains("int8", ignoreCase = true)) {
                        Log.w(TAG, "⚠️ 检测到INT8量化模型，ONNX Runtime Android可能不支持ConvInteger操作符")
                        Log.w(TAG, "⚠️ 建议使用Q4F16量化版本（decoder_model_merged_q4f16.onnx）")
                    }
                    return file
                }
            }
            
            // 如果都没有，返回默认文件名（用于错误提示）
            return File(modelsDir, "decoder_model_merged_q4f16.onnx")
        }
    
    /**
     * 视觉编码器模型文件路径（外部存储）
     * 按优先级查找：
     * 1. vision_encoder_q4f16.onnx (Q4F16量化版本，推荐) ⭐
     * 2. vision_encoder.onnx (未量化版本)
     * 3. vision_encoder_int8.onnx (INT8量化版本，不推荐，ONNX Runtime Android不支持ConvInteger)
     */
    private val visionEncoderModelFile: File
        get() {
            // 按优先级查找视觉编码器模型文件
            // 注意：INT8量化版本使用ConvInteger操作符，ONNX Runtime Android不支持
            val priorityFiles = listOf(
                "vision_encoder_q4f16.onnx",     // Q4F16量化（推荐，兼容性最好）⭐
                "vision_encoder.onnx",            // 未量化版本
                "vision_encoder_int8.onnx"        // INT8量化（不推荐，可能不支持）
            )
            
            // 先按优先级查找
            for (fileName in priorityFiles) {
                val file = File(modelsDir, fileName)
                if (file.exists() && file.length() > 0) {
                    // 检查是否是支持的模型格式
                    if (fileName.contains("int8", ignoreCase = true) && !fileName.contains("q4f16", ignoreCase = true)) {
                        Log.e(TAG, "❌ 检测到INT8量化模型: $fileName")
                        Log.e(TAG, "❌ ONNX Runtime Android不支持ConvInteger操作符，此模型无法使用")
                        Log.e(TAG, "❌ 请使用Q4F16量化版本（vision_encoder_q4f16.onnx）")
                        // 继续查找，不返回INT8版本
                        continue
                    }
                    // 验证文件大小是否合理（Q4F16视觉编码器约1.27GB，未量化版本更大）
                    val fileSizeMB = file.length() / 1024L / 1024L
                    if (fileName.contains("q4f16", ignoreCase = true)) {
                        if (fileSizeMB < 1000 || fileSizeMB > 2000) {
                            Log.w(TAG, "⚠️ Q4F16视觉编码器模型文件大小异常: ${fileSizeMB}MB（预期约1270MB）")
                        }
                    }
                    Log.d(TAG, "✅ 找到视觉编码器模型: $fileName (${fileSizeMB}MB)")
                    return file
                }
            }
            
            // 如果优先级文件都不存在，查找任何包含 "vision" 的 .onnx 文件
            modelsDir.listFiles()?.forEach { file ->
                if (file.isFile && file.name.endsWith(".onnx", ignoreCase = true) && 
                    file.name.contains("vision", ignoreCase = true) && file.length() > 0) {
                    // 检查是否是INT8版本（不支持）
                    if (file.name.contains("int8", ignoreCase = true) && !file.name.contains("q4f16", ignoreCase = true)) {
                        Log.e(TAG, "❌ 发现INT8量化模型: ${file.name}，ONNX Runtime Android不支持，跳过")
                        return@forEach // 跳过INT8版本
                    }
                    val fileSizeMB = file.length() / 1024L / 1024L
                    Log.d(TAG, "✅ 发现视觉编码器模型文件: ${file.name} (${fileSizeMB}MB)")
                    return file
                }
            }
            
            // 如果都没有，返回默认文件名（用于错误提示）
            return File(modelsDir, "vision_encoder_q4f16.onnx")
        }
    
    /**
     * Tokenizer文件路径（外部存储）
     */
    private val tokenizerFile: File
        get() = File(modelsDir, "tokenizer.json")
    
    /**
     * 配置文件路径（外部存储）
     */
    private val configFile: File
        get() = File(modelsDir, "config.json")
    
    /**
     * 预处理器配置文件路径（外部存储）
     */
    private val preprocessorConfigFile: File
        get() = File(modelsDir, "preprocessor_config.json")
    
    /**
     * 获取解码器模型文件路径（用于显示给用户）
     */
    fun getDecoderModelFilePath(): String {
        return decoderModelFile.absolutePath
    }
    
    /**
     * 获取视觉编码器模型文件路径（用于显示给用户）
     */
    fun getVisionEncoderModelFilePath(): String {
        return visionEncoderModelFile.absolutePath
    }
    
    /**
     * 检查模型文件是否存在
     */
    fun isModelAvailable(): Boolean {
        // 只调用一次 getter，避免重复查找和日志
        val decoderModel = decoderModelFile
        val visionEncoderModel = visionEncoderModelFile
        val decoderExists = decoderModel.exists() && decoderModel.length() > 0
        val visionEncoderExists = visionEncoderModel.exists() && visionEncoderModel.length() > 0
        val configExists = configFile.exists() && configFile.length() > 0
        
        // 检查是否是INT8版本（不支持）
        val decoderIsInt8 = decoderModel.name.contains("int8", ignoreCase = true) && 
                           !decoderModel.name.contains("q4f16", ignoreCase = true)
        val visionEncoderIsInt8 = visionEncoderModel.name.contains("int8", ignoreCase = true) && 
                                  !visionEncoderModel.name.contains("q4f16", ignoreCase = true)
        
        if (decoderExists) {
            if (decoderIsInt8) {
                Log.e(TAG, "❌ 解码器模型文件是INT8版本，不支持: ${decoderModel.absolutePath}")
                Log.e(TAG, "❌ 请使用Q4F16版本（decoder_model_merged_q4f16.onnx）")
                return false // INT8版本不支持，返回false
            }
            Log.d(TAG, "✅ 解码器模型文件存在: ${decoderModel.absolutePath}")
        } else {
            Log.d(TAG, "解码器模型文件不存在: ${decoderModel.absolutePath}")
        }
        
        if (visionEncoderExists) {
            if (visionEncoderIsInt8) {
                Log.e(TAG, "❌ 视觉编码器模型文件是INT8版本，不支持: ${visionEncoderModel.absolutePath}")
                Log.e(TAG, "❌ 请使用Q4F16版本（vision_encoder_q4f16.onnx）")
                return false // INT8版本不支持，返回false
            }
            Log.d(TAG, "✅ 视觉编码器模型文件存在: ${visionEncoderModel.absolutePath}")
        } else {
            Log.d(TAG, "视觉编码器模型文件不存在: ${visionEncoderModel.absolutePath}")
        }
        
        if (configExists) {
            Log.d(TAG, "配置文件存在: ${configFile.absolutePath}")
        } else {
            Log.d(TAG, "配置文件不存在: ${configFile.absolutePath}")
        }
        
        // 解码器模型、视觉编码器模型和配置文件都是必需的
        // 且不能是INT8版本（不支持）
        return decoderExists && !decoderIsInt8 && 
               visionEncoderExists && !visionEncoderIsInt8 && 
               configExists
    }
    
    /**
     * 检查模型是否已加载
     */
    fun isLoaded(): Boolean {
        return loadState == LoadState.LOADED
    }
    
    /**
     * 获取当前加载状态
     */
    fun getLoadState(): LoadState {
        return loadState
    }
    
    /**
     * 加载VL模型
     * @param onProgress 加载进度回调（可选）
     */
    suspend fun loadModel(onProgress: ((Int) -> Unit)? = null): Boolean = withContext(Dispatchers.IO) {
        if (loadState == LoadState.LOADED) {
            Log.d(TAG, "模型已加载，跳过重复加载")
            return@withContext true
        }
        
        if (loadState == LoadState.LOADING) {
            Log.w(TAG, "模型正在加载中，请勿重复加载")
            return@withContext false
        }
        
        if (!isModelAvailable()) {
            Log.e(TAG, "模型文件不存在，无法加载")
            loadState = LoadState.FAILED
            return@withContext false
        }
        
        loadState = LoadState.LOADING
        onProgress?.invoke(0)
        
        return@withContext try {
            val decoderPath = decoderModelFile.absolutePath
            val visionEncoderPath = visionEncoderModelFile.absolutePath
            Log.d(TAG, "开始加载VL模型")
            Log.d(TAG, "  解码器: $decoderPath")
            Log.d(TAG, "  视觉编码器: $visionEncoderPath")
            
            // 1. 初始化ONNX Runtime环境
            if (ortEnv == null) {
                ortEnv = OrtEnvironment.getEnvironment()
                Log.d(TAG, "ONNX Runtime环境初始化成功")
            }
            
            onProgress?.invoke(10)
            
            // 2. 加载视觉编码器模型
            Log.d(TAG, "准备加载视觉编码器模型，大小: ${visionEncoderModelFile.length() / 1024L / 1024L}MB")
            val visionEncoderSessionOptions = OrtSession.SessionOptions()
            visionEncoderSession = ortEnv!!.createSession(visionEncoderModelFile.absolutePath, visionEncoderSessionOptions)
            Log.d(TAG, "视觉编码器会话创建成功")
            
            // 打印视觉编码器模型结构信息
            Log.d(TAG, "========== 视觉编码器模型结构 ==========")
            Log.d(TAG, "输入节点数量: ${visionEncoderSession?.inputNames?.size ?: 0}")
            visionEncoderSession?.inputNames?.forEachIndexed { index, inputName ->
                val inputInfo = visionEncoderSession!!.inputInfo[inputName]
                val tensorInfo = inputInfo?.info as? TensorInfo
                Log.d(TAG, "输入[$index]: $inputName")
                if (tensorInfo != null) {
                    Log.d(TAG, "  - 形状: ${tensorInfo.shape.contentToString()}")
                    Log.d(TAG, "  - 数据类型: ${tensorInfo.type}")
                }
            }
            Log.d(TAG, "输出节点数量: ${visionEncoderSession?.outputNames?.size ?: 0}")
            visionEncoderSession?.outputNames?.forEachIndexed { index, outputName ->
                val outputInfo = visionEncoderSession!!.outputInfo[outputName]
                val tensorInfo = outputInfo?.info as? TensorInfo
                Log.d(TAG, "输出[$index]: $outputName")
                if (tensorInfo != null) {
                    Log.d(TAG, "  - 形状: ${tensorInfo.shape.contentToString()}")
                    Log.d(TAG, "  - 数据类型: ${tensorInfo.type}")
                }
            }
            Log.d(TAG, "========================================")
            
            onProgress?.invoke(30)
            
            // 3. 加载解码器模型
            Log.d(TAG, "准备加载解码器模型，大小: ${decoderModelFile.length() / 1024L / 1024L}MB")
            val decoderSessionOptions = OrtSession.SessionOptions()
            ortSession = ortEnv!!.createSession(decoderModelFile.absolutePath, decoderSessionOptions)
            Log.d(TAG, "解码器会话创建成功")
            
            // 打印解码器模型输入输出信息（用于调试和了解模型结构）
            Log.d(TAG, "========== 解码器模型结构 ==========")
            Log.d(TAG, "输入节点数量: ${ortSession?.inputNames?.size ?: 0}")
            ortSession?.inputNames?.forEachIndexed { index, inputName ->
                val inputInfo = ortSession!!.inputInfo[inputName]
                val tensorInfo = inputInfo?.info as? TensorInfo
                Log.d(TAG, "输入[$index]: $inputName")
                if (tensorInfo != null) {
                    Log.d(TAG, "  - 形状: ${tensorInfo.shape.contentToString()}")
                    Log.d(TAG, "  - 数据类型: ${tensorInfo.type}")
                }
            }
            Log.d(TAG, "输出节点数量: ${ortSession?.outputNames?.size ?: 0}")
            ortSession?.outputNames?.forEachIndexed { index, outputName ->
                val outputInfo = ortSession!!.outputInfo[outputName]
                val tensorInfo = outputInfo?.info as? TensorInfo
                Log.d(TAG, "输出[$index]: $outputName")
                if (tensorInfo != null) {
                    Log.d(TAG, "  - 形状: ${tensorInfo.shape.contentToString()}")
                    Log.d(TAG, "  - 数据类型: ${tensorInfo.type}")
                }
            }
            Log.d(TAG, "====================================")
            
            onProgress?.invoke(70)
            
            // 4. 加载tokenizer（TODO：需要实现tokenizer加载）
            // tokenizer = loadTokenizer(tokenizerFile)
            Log.d(TAG, "Tokenizer加载待实现")
            
            onProgress?.invoke(100)
            
            loadState = LoadState.LOADED
            Log.d(TAG, "VL模型加载成功（解码器 + 视觉编码器）")
            true
        } catch (e: Exception) {
            Log.e(TAG, "VL模型加载失败", e)
            loadState = LoadState.FAILED
            // 清理资源
            ortSession?.close()
            ortSession = null
            visionEncoderSession?.close()
            visionEncoderSession = null
            false
        }
    }
    
    /**
     * 卸载模型（释放内存）
     */
    suspend fun unloadModel() = withContext(Dispatchers.IO) {
        if (loadState != LoadState.LOADED) {
            Log.d(TAG, "模型未加载，无需卸载")
            return@withContext
        }
        
        try {
            Log.d(TAG, "卸载VL模型")
            
            // 1. 关闭解码器会话
            ortSession?.close()
            ortSession = null
            
            // 2. 关闭视觉编码器会话
            visionEncoderSession?.close()
            visionEncoderSession = null
            
            // 3. 释放环境资源（OrtEnvironment是单例，通常不需要手动释放）
            // ortEnv = null
            
            tokenizer = null
            
            loadState = LoadState.NOT_LOADED
            Log.d(TAG, "VL模型卸载成功")
        } catch (e: Exception) {
            Log.e(TAG, "VL模型卸载失败", e)
        }
    }
    
    /**
     * 理解屏幕内容（VL模型推理）
     * @param screenshot 屏幕截图
     * @return 屏幕状态（包含所有UI元素和语义描述）
     */
    suspend fun understand(screenshot: Bitmap): ScreenState = withContext(Dispatchers.IO) {
        if (loadState != LoadState.LOADED) {
            Log.w(TAG, "模型未加载，无法进行推理。尝试自动加载...")
            val loaded = loadModel()
            if (!loaded) {
                Log.e(TAG, "模型加载失败，返回空结果")
                return@withContext ScreenState(
                    elements = emptyList(),
                    semanticDescription = "",
                    vlAvailable = false
                )
            }
        }
        
        return@withContext try {
            Log.d(TAG, "开始VL模型推理，图片尺寸: ${screenshot.width}x${screenshot.height}")
            
            // 1. 图像预处理（调整到960x960，归一化）
            val preprocessedImage = preprocessImage(screenshot)
            Log.d(TAG, "图像预处理完成，尺寸: ${preprocessedImage.width}x${preprocessedImage.height}")
            
            // 2. 视觉编码器推理（图像 → 图像嵌入向量）
            val imageEmbedsShape = try {
                val tensor = runVisionEncoder(preprocessedImage)
                // 通过 info 获取 TensorInfo，然后访问 shape
                val tensorInfo = tensor.info as? TensorInfo
                val shape = tensorInfo?.shape?.contentToString() ?: "未知"
                // 关闭张量，避免内存泄漏（后续完整实现时，需要保留张量用于后续处理）
                tensor.close()
                Log.d(TAG, "视觉编码器推理完成，嵌入向量形状: $shape")
                shape
            } catch (e: Exception) {
                Log.e(TAG, "视觉编码器推理失败", e)
                throw e
            }
            
            // 3. 文本 tokenization（TODO：需要实现 tokenizer）
            val prompt = "<|vision_start|>Describe all UI elements in this screenshot, including their types, text content, and bounding box coordinates in JSON format.<|vision_end|>"
            Log.w(TAG, "文本 tokenization 待实现，使用占位符")
            // TODO: 实现 tokenizer
            // val tokenIds = tokenize(prompt)
            // val textEmbeds = getTextEmbeddings(tokenIds)
            
            // 4. 合并图像和文本嵌入为 inputs_embeds（TODO：需要实现）
            // val inputsEmbeds = mergeEmbeddings(imageEmbeds, textEmbeds)
            
            // 5. Decoder 推理（inputs_embeds → logits）（TODO：需要实现）
            // val logits = runDecoder(inputsEmbeds)
            
            // 6. 解码 logits 为文本（TODO：需要实现）
            // val outputText = decodeLogits(logits)
            
            // 7. 解析 JSON 输出为 ScreenState（TODO：需要实现）
            // val screenState = parseOutput(outputText)
            
            // 临时实现：返回空结果（等待完整推理流程实现）
            ScreenState(
                elements = emptyList(),
                semanticDescription = "VL模型推理功能部分实现（视觉编码器已实现，输出形状: $imageEmbedsShape）",
                vlAvailable = true
            )
        } catch (e: Exception) {
            Log.e(TAG, "VL模型推理失败", e)
            ScreenState(
                elements = emptyList(),
                semanticDescription = "",
                vlAvailable = false
            )
        }
    }
    
    /**
     * 图像预处理（调整到960x960，归一化）
     * @param bitmap 原始图像
     * @return 预处理后的图像（960x960，RGB格式）
     */
    private fun preprocessImage(bitmap: Bitmap): Bitmap {
        val targetSize = 960
        val width = bitmap.width
        val height = bitmap.height
        
        // 计算缩放比例（保持宽高比）
        val scale = minOf(
            targetSize.toFloat() / width,
            targetSize.toFloat() / height
        )
        
        val scaledWidth = (width * scale).toInt()
        val scaledHeight = (height * scale).toInt()
        
        // 缩放图像
        val scaledBitmap = Bitmap.createScaledBitmap(bitmap, scaledWidth, scaledHeight, true)
        
        // 创建960x960的画布，用黑色填充
        val outputBitmap = Bitmap.createBitmap(targetSize, targetSize, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(outputBitmap)
        canvas.drawColor(Color.BLACK)
        
        // 将缩放后的图像居中绘制
        val left = (targetSize - scaledWidth) / 2
        val top = (targetSize - scaledHeight) / 2
        canvas.drawBitmap(scaledBitmap, left.toFloat(), top.toFloat(), null)
        
        // 回收临时bitmap
        if (scaledBitmap != bitmap && !scaledBitmap.isRecycled) {
            scaledBitmap.recycle()
        }
        
        return outputBitmap
    }
    
    /**
     * 运行视觉编码器推理（图像 → 图像嵌入向量）
     * @param bitmap 预处理后的图像（960x960，RGB）
     * @return 图像嵌入向量（OnnxTensor）
     */
    private fun runVisionEncoder(bitmap: Bitmap): OnnxTensor {
        if (visionEncoderSession == null) {
            throw IllegalStateException("视觉编码器未加载")
        }
        
        // Qwen2-VL 视觉编码器参数
        val patchSize = 14 // patch大小（固定值）
        val channels = 3 // RGB通道数
        val temporalPatchSize = 1 // 时间维度patch大小（单张图像为1）
        
        val width = bitmap.width
        val height = bitmap.height
        
        // 1. 计算grid_thw（网格信息）
        // grid_h = ceil(height / patch_size), grid_w = ceil(width / patch_size)
        val gridH = ceil(height.toDouble() / patchSize).toLong()
        val gridW = ceil(width.toDouble() / patchSize).toLong()
        val gridT = 1L // 单张图像，时间维度为1
        val gridThw = longArrayOf(gridT, gridH, gridW)
        val numPatches = (gridT * gridH * gridW).toInt()
        
        // 2. 计算patch数据大小
        // pixel_values形状: (num_patches, channels * temporal_patch_size * patch_size * patch_size)
        val patchDataSize = channels * temporalPatchSize * patchSize * patchSize // 3 * 1 * 14 * 14 = 588
        
        Log.d(TAG, "图像尺寸: ${width}x${height}, grid_thw: [${gridT}, ${gridH}, ${gridW}], patches数量: $numPatches")
        Log.d(TAG, "patch数据大小: ${patchDataSize} (channels=$channels, temporal=$temporalPatchSize, patchSize=$patchSize)")
        Log.d(TAG, "pixel_values形状: [$numPatches, $patchDataSize]")
        
        // 3. 将图像转换为patch格式
        val pixelValues = FloatArray(numPatches * patchDataSize)
        
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        
        // 将图像分割为patches，每个patch大小为14x14
        for (patchY in 0 until gridH.toInt()) {
            for (patchX in 0 until gridW.toInt()) {
                val patchIndex = (patchY * gridW.toInt() + patchX)
                val patchBaseIndex = patchIndex * patchDataSize
                
                // 提取当前patch的像素（14x14）
                for (py in 0 until patchSize) {
                    for (px in 0 until patchSize) {
                        val imgX = patchX * patchSize + px
                        val imgY = patchY * patchSize + py
                        
                        // 边界检查（如果图像尺寸不是patchSize的整数倍）
                        if (imgX < width && imgY < height) {
                            val pixel = pixels[imgY * width + imgX]
                            val r = ((pixel shr 16) and 0xFF) / 255.0f
                            val g = ((pixel shr 8) and 0xFF) / 255.0f
                            val b = (pixel and 0xFF) / 255.0f
                            
                            // 在patch内的位置索引（按CHW格式：channel, height, width）
                            val patchPixelIndex = py * patchSize + px
                            pixelValues[patchBaseIndex + 0 * patchSize * patchSize + patchPixelIndex] = r
                            pixelValues[patchBaseIndex + 1 * patchSize * patchSize + patchPixelIndex] = g
                            pixelValues[patchBaseIndex + 2 * patchSize * patchSize + patchPixelIndex] = b
                        } else {
                            // 边界外的像素填充为0（黑色）
                            val patchPixelIndex = py * patchSize + px
                            pixelValues[patchBaseIndex + 0 * patchSize * patchSize + patchPixelIndex] = 0.0f
                            pixelValues[patchBaseIndex + 1 * patchSize * patchSize + patchPixelIndex] = 0.0f
                            pixelValues[patchBaseIndex + 2 * patchSize * patchSize + patchPixelIndex] = 0.0f
                        }
                    }
                }
            }
        }
        
        // 4. 创建输入张量
        // pixel_values: (num_patches, patch_data_size) = (num_patches, 588)
        val pixelValuesShape = longArrayOf(numPatches.toLong(), patchDataSize.toLong())
        val pixelValuesTensor = OnnxTensor.createTensor(
            ortEnv!!, 
            FloatBuffer.wrap(pixelValues), 
            pixelValuesShape
        )
        
        // grid_thw: (1, 3) = (batch_size, [grid_t, grid_h, grid_w])
        val gridThwShape = longArrayOf(1, 3)
        val gridThwArray = longArrayOf(gridT, gridH, gridW)
        val gridThwTensor = OnnxTensor.createTensor(
            ortEnv!!,
            LongBuffer.wrap(gridThwArray),
            gridThwShape
        )
        
        // 5. 准备输入（根据实际模型输入节点名称）
        val inputs = mutableMapOf<String, OnnxTensor>()
        visionEncoderSession!!.inputNames.forEach { inputName ->
            when (inputName) {
                "pixel_values" -> inputs[inputName] = pixelValuesTensor
                "grid_thw" -> inputs[inputName] = gridThwTensor
                else -> {
                    Log.w(TAG, "未知的输入节点名称: $inputName")
                }
            }
        }
        
        if (inputs.size != visionEncoderSession!!.inputNames.size) {
            throw IllegalStateException(
                "输入节点数量不匹配: 期望${visionEncoderSession!!.inputNames.size}个，实际${inputs.size}个。"
            )
        }
        
        // 6. 运行推理
        val outputs = visionEncoderSession!!.run(inputs)
        
        // 7. 获取输出（图像嵌入向量）
        val outputTensor = outputs.first().value as OnnxTensor
        val tensorInfo = outputTensor.info as? TensorInfo
        val outputShape = tensorInfo?.shape?.contentToString() ?: "未知"
        val outputType = tensorInfo?.type?.toString() ?: "未知"
        
        // 验证输出形状是否符合预期
        val expectedShape = "[-1, 1536]"
        val shapeMatches = outputShape.contains("1536") || outputShape == expectedShape
        
        Log.d(TAG, "视觉编码器输出形状: $outputShape")
        Log.d(TAG, "视觉编码器输出类型: $outputType")
        if (shapeMatches) {
            Log.d(TAG, "✅ 输出形状验证通过（包含1536维特征向量）")
        } else {
            Log.w(TAG, "⚠️ 输出形状与预期不符（预期包含1536维，实际: $outputShape）")
        }
        
        // 提取实际的输出数据维度（用于调试）
        val actualShape = tensorInfo?.shape
        if (actualShape != null && actualShape.size >= 2) {
            val batchSize = if (actualShape[0] > 0) actualShape[0] else "动态"
            val featureDim = if (actualShape[1] > 0) actualShape[1] else "动态"
            Log.d(TAG, "输出维度详情: batch_size=$batchSize, feature_dim=$featureDim")
        }
        
        // 8. 清理输入张量
        pixelValuesTensor.close()
        gridThwTensor.close()
        
        // 9. 清理其他outputs（但保留outputTensor）
        outputs.forEach { if (it.value != outputTensor) (it.value as? OnnxTensor)?.close() }
        
        // 返回输出张量（调用者负责关闭）
        return outputTensor
    }
    
    /**
     * 释放资源
     */
    fun release() {
        if (loadState == LoadState.LOADED) {
            // 在后台协程中卸载模型
            CoroutineScope(Dispatchers.IO).launch {
                unloadModel()
            }
        }
    }
}
