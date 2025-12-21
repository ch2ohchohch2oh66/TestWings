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
import org.json.JSONObject

/**
 * Vision-Language模型管理器
 * 负责VL模型的加载、推理和管理
 */
class VisionLanguageManager(private val context: Context) {
    
    private val TAG = "VisionLanguageManager"
    
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
     * ONNX Runtime会话（文本嵌入模型）
     */
    private var embedTokensSession: OrtSession? = null
    
    /**
     * Tokenizer
     */
    private var tokenizer: QwenTokenizer? = null
    
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
     * 文本嵌入模型文件路径（外部存储）
     * 按优先级查找：
     * 1. embed_tokens_q4f16.onnx (Q4F16量化版本，推荐) ⭐
     * 2. embed_tokens.onnx (未量化版本)
     * 3. embed_tokens_int8.onnx (INT8量化版本，不推荐，ONNX Runtime Android不支持ConvInteger)
     */
    private val embedTokensModelFile: File
        get() {
            // 按优先级查找文本嵌入模型文件
            // 注意：INT8量化版本使用ConvInteger操作符，ONNX Runtime Android不支持
            val priorityFiles = listOf(
                "embed_tokens_q4f16.onnx",     // Q4F16量化（推荐，兼容性最好）⭐
                "embed_tokens.onnx",            // 未量化版本
                "embed_tokens_int8.onnx"        // INT8量化（不推荐，可能不支持）
            )
            
            // 先按优先级查找
            for (fileName in priorityFiles) {
                val file = File(modelsDir, fileName)
                if (file.exists() && file.length() > 0) {
                    // 检查是否是支持的模型格式
                    if (fileName.contains("int8", ignoreCase = true) && !fileName.contains("q4f16", ignoreCase = true)) {
                        Log.e(TAG, "❌ 检测到INT8量化模型: $fileName")
                        Log.e(TAG, "❌ ONNX Runtime Android不支持ConvInteger操作符，此模型无法使用")
                        Log.e(TAG, "❌ 请使用Q4F16量化版本（embed_tokens_q4f16.onnx）")
                        // 继续查找，不返回INT8版本
                        continue
                    }
                    // 验证文件大小是否合理（Q4F16文本嵌入约467MB，未量化版本约933MB）
                    val fileSizeMB = file.length() / 1024L / 1024L
                    if (fileName.contains("q4f16", ignoreCase = true)) {
                        if (fileSizeMB < 300 || fileSizeMB > 700) {
                            Log.w(TAG, "⚠️ Q4F16文本嵌入模型文件大小异常: ${fileSizeMB}MB（预期约467MB）")
                        }
                    }
                    Log.d(TAG, "✅ 找到文本嵌入模型: $fileName (${fileSizeMB}MB)")
                    return file
                }
            }
            
            // 如果优先级文件都不存在，查找任何包含 "embed" 的 .onnx 文件
            modelsDir.listFiles()?.forEach { file ->
                if (file.isFile && file.name.endsWith(".onnx", ignoreCase = true) && 
                    file.name.contains("embed", ignoreCase = true) && file.length() > 0) {
                    // 检查是否是INT8版本（不支持）
                    if (file.name.contains("int8", ignoreCase = true) && !file.name.contains("q4f16", ignoreCase = true)) {
                        Log.e(TAG, "❌ 发现INT8量化模型: ${file.name}，ONNX Runtime Android不支持，跳过")
                        return@forEach // 跳过INT8版本
                    }
                    val fileSizeMB = file.length() / 1024L / 1024L
                    Log.d(TAG, "✅ 发现文本嵌入模型文件: ${file.name} (${fileSizeMB}MB)")
                    return file
                }
            }
            
            // 如果都没有，返回默认文件名（用于错误提示）
            return File(modelsDir, "embed_tokens_q4f16.onnx")
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
     * 模型配置参数（从config.json加载）
     */
    private data class ModelConfig(
        val hiddenSize: Int = 1536,
        val numAttentionHeads: Int? = null,
        val numKeyValueHeads: Int? = null,
        val numHiddenLayers: Int? = null,
        val maxPositionEmbeddings: Int? = null,
        val vocabSize: Int? = null,
        val ropeTheta: Double? = null
    )
    
    private var modelConfig: ModelConfig? = null
    
    /**
     * 加载模型配置（从config.json读取）
     */
    private fun loadModelConfig(): ModelConfig? {
        return try {
            if (!configFile.exists()) {
                Log.w(TAG, "⚠️ config.json不存在，无法读取模型配置")
                return null
            }
            
            val configJson = JSONObject(configFile.readText())
            val config = ModelConfig(
                hiddenSize = configJson.optInt("hidden_size", 1536),
                numAttentionHeads = configJson.optInt("num_attention_heads").takeIf { it > 0 },
                numKeyValueHeads = configJson.optInt("num_key_value_heads").takeIf { it > 0 },
                numHiddenLayers = configJson.optInt("num_hidden_layers").takeIf { it > 0 },
                maxPositionEmbeddings = configJson.optInt("max_position_embeddings").takeIf { it > 0 },
                vocabSize = configJson.optInt("vocab_size").takeIf { it > 0 },
                ropeTheta = configJson.optDouble("rope_theta").takeIf { it > 0.0 }
            )
            
            Log.d(TAG, "========== 模型配置（从config.json读取）==========")
            Log.d(TAG, "hidden_size: ${config.hiddenSize}")
            config.numAttentionHeads?.let { Log.d(TAG, "num_attention_heads: $it") }
            config.numKeyValueHeads?.let { Log.d(TAG, "num_key_value_heads: $it") }
            config.numHiddenLayers?.let { Log.d(TAG, "num_hidden_layers: $it") }
            config.maxPositionEmbeddings?.let { Log.d(TAG, "max_position_embeddings: $it") }
            config.vocabSize?.let { Log.d(TAG, "vocab_size: $it") }
            config.ropeTheta?.let { Log.d(TAG, "rope_theta: $it") }
            Log.d(TAG, "=================================================")
            
            // 检查关键参数
            if (config.numKeyValueHeads != null && config.numAttentionHeads != null) {
                if (config.numKeyValueHeads != config.numAttentionHeads) {
                    Log.w(TAG, "⚠️ 注意：num_key_value_heads(${config.numKeyValueHeads}) != num_attention_heads(${config.numAttentionHeads})")
                    Log.w(TAG, "⚠️ 这可能会影响past_key_values的形状！")
                }
            }
            
            config
        } catch (e: Exception) {
            Log.e(TAG, "❌ 读取config.json失败: ${e.message}", e)
            null
        }
    }
    
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
     * 获取文本嵌入模型文件路径（用于显示给用户）
     */
    fun getEmbedTokensModelFilePath(): String {
        return embedTokensModelFile.absolutePath
    }
    
    /**
     * 检查模型文件是否存在
     */
    fun isModelAvailable(): Boolean {
        // 只调用一次 getter，避免重复查找和日志
        val decoderModel = decoderModelFile
        val visionEncoderModel = visionEncoderModelFile
        val embedTokensModel = embedTokensModelFile
        val decoderExists = decoderModel.exists() && decoderModel.length() > 0
        val visionEncoderExists = visionEncoderModel.exists() && visionEncoderModel.length() > 0
        val embedTokensExists = embedTokensModel.exists() && embedTokensModel.length() > 0
        val configExists = configFile.exists() && configFile.length() > 0
        
        // 检查是否是INT8版本（不支持）
        val decoderIsInt8 = decoderModel.name.contains("int8", ignoreCase = true) && 
                           !decoderModel.name.contains("q4f16", ignoreCase = true)
        val visionEncoderIsInt8 = visionEncoderModel.name.contains("int8", ignoreCase = true) && 
                                  !visionEncoderModel.name.contains("q4f16", ignoreCase = true)
        val embedTokensIsInt8 = embedTokensModel.name.contains("int8", ignoreCase = true) && 
                                !embedTokensModel.name.contains("q4f16", ignoreCase = true)
        
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
        
        if (embedTokensExists) {
            if (embedTokensIsInt8) {
                Log.e(TAG, "❌ 文本嵌入模型文件是INT8版本，不支持: ${embedTokensModel.absolutePath}")
                Log.e(TAG, "❌ 请使用Q4F16版本（embed_tokens_q4f16.onnx）")
                return false // INT8版本不支持，返回false
            }
            Log.d(TAG, "✅ 文本嵌入模型文件存在: ${embedTokensModel.absolutePath}")
        } else {
            Log.d(TAG, "文本嵌入模型文件不存在: ${embedTokensModel.absolutePath}")
        }
        
        if (configExists) {
            Log.d(TAG, "配置文件存在: ${configFile.absolutePath}")
        } else {
            Log.d(TAG, "配置文件不存在: ${configFile.absolutePath}")
        }
        
        // 解码器模型、视觉编码器模型、文本嵌入模型和配置文件都是必需的
        // 且不能是INT8版本（不支持）
        return decoderExists && !decoderIsInt8 && 
               visionEncoderExists && !visionEncoderIsInt8 && 
               embedTokensExists && !embedTokensIsInt8 &&
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
            val embedTokensPath = embedTokensModelFile.absolutePath
            Log.d(TAG, "开始加载VL模型")
            Log.d(TAG, "  解码器: $decoderPath")
            Log.d(TAG, "  视觉编码器: $visionEncoderPath")
            Log.d(TAG, "  文本嵌入模型: $embedTokensPath")
            
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
            
            onProgress?.invoke(60)
            
            // 4. 加载文本嵌入模型
            Log.d(TAG, "准备加载文本嵌入模型，大小: ${embedTokensModelFile.length() / 1024L / 1024L}MB")
            val embedTokensSessionOptions = OrtSession.SessionOptions()
            embedTokensSession = ortEnv!!.createSession(embedTokensModelFile.absolutePath, embedTokensSessionOptions)
            Log.d(TAG, "文本嵌入模型会话创建成功")
            
            // 打印文本嵌入模型结构信息
            Log.d(TAG, "========== 文本嵌入模型结构 ==========")
            Log.d(TAG, "输入节点数量: ${embedTokensSession?.inputNames?.size ?: 0}")
            embedTokensSession?.inputNames?.forEachIndexed { index, inputName ->
                val inputInfo = embedTokensSession!!.inputInfo[inputName]
                val tensorInfo = inputInfo?.info as? TensorInfo
                Log.d(TAG, "输入[$index]: $inputName")
                if (tensorInfo != null) {
                    Log.d(TAG, "  - 形状: ${tensorInfo.shape.contentToString()}")
                    Log.d(TAG, "  - 数据类型: ${tensorInfo.type}")
                }
            }
            Log.d(TAG, "输出节点数量: ${embedTokensSession?.outputNames?.size ?: 0}")
            embedTokensSession?.outputNames?.forEachIndexed { index, outputName ->
                val outputInfo = embedTokensSession!!.outputInfo[outputName]
                val tensorInfo = outputInfo?.info as? TensorInfo
                Log.d(TAG, "输出[$index]: $outputName")
                if (tensorInfo != null) {
                    Log.d(TAG, "  - 形状: ${tensorInfo.shape.contentToString()}")
                    Log.d(TAG, "  - 数据类型: ${tensorInfo.type}")
                }
            }
            Log.d(TAG, "=======================================")
            
            onProgress?.invoke(70)
            
            // 5. 加载模型配置（从config.json读取）
            modelConfig = loadModelConfig()
            if (modelConfig == null) {
                Log.w(TAG, "⚠️ 模型配置加载失败，将使用默认/硬编码值")
            }
            
            onProgress?.invoke(80)
            
            // 6. 加载tokenizer
            if (tokenizerFile.exists()) {
                Log.d(TAG, "开始加载Tokenizer: ${tokenizerFile.absolutePath}")
                tokenizer = QwenTokenizer(context)
                val tokenizerLoaded = tokenizer?.load() ?: false
                if (!tokenizerLoaded) {
                    Log.w(TAG, "⚠️ Tokenizer加载失败，文本tokenization将不可用")
                } else {
                    Log.d(TAG, "✅ Tokenizer加载成功")
                }
            } else {
                Log.w(TAG, "⚠️ Tokenizer文件不存在: ${tokenizerFile.absolutePath}，文本tokenization将不可用")
            }
            
            onProgress?.invoke(100)
            
            loadState = LoadState.LOADED
            Log.d(TAG, "VL模型加载成功（解码器 + 视觉编码器 + 文本嵌入模型）")
            true
        } catch (e: Exception) {
            Log.e(TAG, "VL模型加载失败", e)
            loadState = LoadState.FAILED
            // 清理资源
            ortSession?.close()
            ortSession = null
            visionEncoderSession?.close()
            visionEncoderSession = null
            embedTokensSession?.close()
            embedTokensSession = null
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
            
            // 3. 关闭文本嵌入模型会话
            embedTokensSession?.close()
            embedTokensSession = null
            
            // 4. 释放环境资源（OrtEnvironment是单例，通常不需要手动释放）
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
        
        val totalStartTime = System.currentTimeMillis()
        return@withContext try {
            Log.d(TAG, "========== 开始VL模型推理 ==========")
            Log.d(TAG, "图片尺寸: ${screenshot.width}x${screenshot.height}")
            
            // 1. 图像预处理（调整到960x960，归一化）
            val preprocessStartTime = System.currentTimeMillis()
            val preprocessedImage = preprocessImage(screenshot)
            val preprocessTime = System.currentTimeMillis() - preprocessStartTime
            Log.d(TAG, "✅ 图像预处理完成，尺寸: ${preprocessedImage.width}x${preprocessedImage.height}，耗时: ${preprocessTime}ms")
            
            // 2. 视觉编码器推理（图像 → 图像嵌入向量）
            val imageEmbedsTensor = try {
                Log.d(TAG, "开始视觉编码器推理...")
                val tensor = runVisionEncoder(preprocessedImage)
                val tensorInfo = tensor.info as? TensorInfo
                val shape = tensorInfo?.shape?.contentToString() ?: "未知"
                Log.d(TAG, "✅ 视觉编码器推理完成，嵌入向量形状: $shape")
                tensor
            } catch (e: Exception) {
                Log.e(TAG, "❌ 视觉编码器推理失败", e)
                e.printStackTrace()
                throw e
            }
            
            // 获取图像嵌入的实际数据（保留张量用于后续合并）
            val imageEmbedsInfo = imageEmbedsTensor.info as? TensorInfo
            val imageEmbedsShape = imageEmbedsInfo?.shape ?: longArrayOf(0, 0)
            val imageEmbedCount = if (imageEmbedsShape.size >= 1 && imageEmbedsShape[0] > 0) {
                imageEmbedsShape[0].toInt()
            } else {
                // 从张量形状中计算
                0
            }
            
            // 3. 文本 tokenization
            // 根据Qwen2-VL官方实现，正确的格式应该是：
            // <|vision_start|><|image_pad|>...<|image_pad|><|vision_end|>Describe...
            // image_pad的数量应该等于图像特征的数量
            // 使用之前计算的imageEmbedCount
            val imageFeatureCount = if (imageEmbedCount > 0) imageEmbedCount else 576
            Log.d(TAG, "图像特征数量: $imageFeatureCount")
            
            // 构建包含image_pad token的prompt
            // image_token_id = 151655 (根据官方config.json)
            val imageTokenId = 151655
            val imagePadTokens = List(imageFeatureCount) { imageTokenId }
            
            // 构建完整的token序列：vision_start + image_pads + vision_end + text
            // 正确的格式应该是：<|vision_start|><|image_pad|>...<|image_pad|><|vision_end|>text
            val basePrompt = "Describe all UI elements in this screenshot, including their types, text content, and bounding box coordinates in JSON format."
            
            // 首先编码文本部分（不包含vision特殊token）
            val textTokenIds = if (tokenizer != null && tokenizer!!.isLoaded) {
                tokenizer!!.encode(basePrompt)
            } else {
                emptyList()
            }
            
            val tokenIds = if (tokenizer != null && tokenizer!!.isLoaded) {
                try {
                    // 获取vision特殊token ID
                    val visionStartId = tokenizer!!.getVisionStartTokenId()
                    val visionEndId = tokenizer!!.getVisionEndTokenId()
                    
                    // 根据Qwen2-VL官方文档，正确的输入格式应该是：
                    // <|im_start|>user\n<|vision_start|><|image_pad|>...<|vision_end|>\nYour question<|im_end|>
                    // 注意：Qwen2-VL不使用单独的BOS token，而是使用<|im_start|>user来标记对话开始
                    // <|image_pad|>是特殊token ID (151655)，不能通过字符串编码，需要直接使用token ID
                    val fullTokenIds = mutableListOf<Int>()
                    
                    // 1. 添加 <|im_start|>user\n
                    // 根据官方文档，<|im_start|>user\n应该被编码为多个token
                    val imStartUserTokens = tokenizer!!.encode("<|im_start|>user\n")
                    fullTokenIds.addAll(imStartUserTokens)
                    Log.d(TAG, "添加<|im_start|>user\\n，token数量: ${imStartUserTokens.size}")
                    
                    // 2. 添加 <|vision_start|>
                    if (visionStartId != null) {
                        fullTokenIds.add(visionStartId)
                        Log.d(TAG, "添加vision_start token (ID=$visionStartId)")
                    } else {
                        val visionStartTokens = tokenizer!!.encode("<|vision_start|>")
                        fullTokenIds.addAll(visionStartTokens)
                        Log.d(TAG, "编码vision_start字符串，token数量: ${visionStartTokens.size}")
                    }
                    
                    // 3. 添加image_pad tokens（使用已知的token ID 151655，不能通过字符串编码）
                    fullTokenIds.addAll(imagePadTokens)
                    Log.d(TAG, "添加${imagePadTokens.size}个image_pad tokens (ID=${imagePadTokens.firstOrNull()})")
                    
                    // 4. 添加 <|vision_end|>
                    if (visionEndId != null) {
                        fullTokenIds.add(visionEndId)
                        Log.d(TAG, "添加vision_end token (ID=$visionEndId)")
                    } else {
                        val visionEndTokens = tokenizer!!.encode("<|vision_end|>")
                        fullTokenIds.addAll(visionEndTokens)
                        Log.d(TAG, "编码vision_end字符串，token数量: ${visionEndTokens.size}")
                    }
                    
                    // 5. 添加用户问题文本
                    // Note: Adding newline after <|vision_end|> doesn't increase sequence length 
                    // because tokenizer.encode("\n") returns empty tokens (already included in previous tokens)
                    fullTokenIds.addAll(textTokenIds)
                    Log.d(TAG, "添加文本tokens，数量: ${textTokenIds.size}")
                    
                    // 6. 添加 <|im_end|>
                    val imEndTokens = tokenizer!!.encode("<|im_end|>")
                    fullTokenIds.addAll(imEndTokens)
                    Log.d(TAG, "添加<|im_end|>，token数量: ${imEndTokens.size}")
                    
                    Log.d(TAG, "最终序列长度: ${fullTokenIds.size}")
                    
                    val ids = fullTokenIds.toList()
                    Log.d(TAG, "✅ 文本tokenization完成，token数量: ${ids.size} (包含${imagePadTokens.size}个image_pad token)")
                    
                    // 检查vision_start和vision_end是否在tokenIds中
                    val finalVisionStartId = tokenizer!!.getVisionStartTokenId()
                    val finalVisionEndId = tokenizer!!.getVisionEndTokenId()
                    val hasVisionStart = finalVisionStartId != null && ids.contains(finalVisionStartId)
                    val hasVisionEnd = finalVisionEndId != null && ids.contains(finalVisionEndId)
                    Log.d(TAG, "  vision_start ID: $finalVisionStartId, 在tokenIds中: $hasVisionStart")
                    Log.d(TAG, "  vision_end ID: $finalVisionEndId, 在tokenIds中: $hasVisionEnd")
                    
                    // 调试：打印完整的token IDs，帮助理解tokenization结果
                    if (ids.isNotEmpty()) {
                        val firstFew = ids.take(5).joinToString(", ")
                        val lastFew = ids.takeLast(5).joinToString(", ")
                        Log.d(TAG, "  token序列前5个: [$firstFew]")
                        Log.d(TAG, "  token序列后5个: [$lastFew]")
                        
                        // 尝试解码前几个和最后几个token，看看是否包含vision标记
                        val firstText = ids.take(3).mapNotNull { tokenId ->
                            try {
                                tokenizer!!.decode(listOf(tokenId))
                            } catch (e: Exception) {
                                null
                            }
                        }.joinToString("")
                        val lastText = ids.takeLast(3).mapNotNull { tokenId ->
                            try {
                                tokenizer!!.decode(listOf(tokenId))
                            } catch (e: Exception) {
                                null
                            }
                        }.joinToString("")
                        Log.d(TAG, "  token序列前3个解码: \"$firstText\"")
                        Log.d(TAG, "  token序列后3个解码: \"$lastText\"")
                    }
                    
                    if (!hasVisionStart || !hasVisionEnd) {
                        Log.w(TAG, "  ⚠️ vision_start或vision_end不在tokenIds中，tokenizer可能未正确识别这些特殊token")
                        Log.w(TAG, "  ⚠️ 提示：这些token可能被编码为多个普通token，或者tokenizer配置不正确")
                    }
                    
                    ids
                } catch (e: Exception) {
                    Log.e(TAG, "❌ 文本tokenization失败", e)
                    emptyList()
                }
            } else {
                Log.w(TAG, "⚠️ Tokenizer未加载，跳过文本tokenization")
                emptyList()
            }
            
            if (tokenIds.isEmpty()) {
                // Tokenizer未加载或tokenization失败，返回部分结果
                imageEmbedsTensor.close()
                throw IllegalStateException("文本tokenization失败，无法继续推理")
            }
            
            // 4. 准备Decoder输入
            // 注意：decoder模型支持input_ids（token IDs）或inputs_embeds（嵌入向量）
            // 我们需要合并图像嵌入和文本，使用inputs_embeds方式
            
            // 4.1 获取图像嵌入数据
            val imageEmbedsData = try {
                extractTensorData(imageEmbedsTensor) // [num_image_features, 1536]
            } catch (e: Exception) {
                Log.e(TAG, "❌ 提取图像嵌入数据失败", e)
                imageEmbedsTensor.close()
                throw e
            }
            
            // 4.2 获取文本嵌入（通过embed_tokens或decoder内置）
            // 注意：我们需要先检查decoder模型是否有input_ids输入
            // 如果有，可以直接使用；如果没有，需要先获取文本嵌入
            val textEmbeds = try {
                getTextEmbeddingsFromDecoder(tokenIds) // 或使用input_ids直接输入
            } catch (e: Exception) {
                Log.e(TAG, "❌ 获取文本嵌入失败", e)
                imageEmbedsTensor.close()
                throw e
            }
            
            // 4.3 合并图像和文本嵌入为 inputs_embeds
            val inputsEmbeds = try {
                mergeImageAndTextEmbeddings(imageEmbedsData, textEmbeds, tokenIds)
            } catch (e: Exception) {
                Log.e(TAG, "❌ 合并嵌入失败", e)
                imageEmbedsTensor.close()
                throw e
            }
            
            // 5. Decoder 推理（使用inputs_embeds或input_ids）
            val logitsTensor = try {
                runDecoderWithInputs(inputsEmbeds, imageEmbedsData.size, tokenIds.size)
            } catch (e: Exception) {
                Log.e(TAG, "❌ Decoder推理失败", e)
                imageEmbedsTensor.close()
                throw e
            }
            
            // 7. 解码 logits 为文本
            val outputText = try {
                decodeLogits(logitsTensor)
            } catch (e: Exception) {
                Log.e(TAG, "❌ Logits解码失败", e)
                logitsTensor.close()
                imageEmbedsTensor.close()
                throw e
            }
            
            // 清理张量
            logitsTensor.close()
            imageEmbedsTensor.close()
            
            // 8. 解析 JSON 输出为 ScreenState
            val screenState = try {
                parseOutputToScreenState(outputText, screenshot.width, screenshot.height)
            } catch (e: Exception) {
                Log.e(TAG, "❌ JSON解析失败，使用降级方案", e)
                // 解析失败时，返回包含语义描述的结果
                ScreenState(
                    elements = emptyList(),
                    semanticDescription = outputText.take(500), // 截取前500字符作为描述
                    vlAvailable = true
                )
            }
            
            // 临时实现：返回空结果（等待完整推理流程实现）
            val totalTime = System.currentTimeMillis() - totalStartTime
            Log.d(TAG, "========== VL模型推理完成 ==========")
            Log.d(TAG, "总耗时: ${totalTime}ms (${totalTime / 1000.0}秒)")
            Log.d(TAG, "视觉编码器输出形状: $imageEmbedsShape")
            
            ScreenState(
                elements = emptyList(),
                semanticDescription = "VL模型推理功能部分实现（视觉编码器已实现，输出形状: $imageEmbedsShape，总耗时: ${totalTime}ms）",
                vlAvailable = true
            )
        } catch (e: Exception) {
            val totalTime = System.currentTimeMillis() - totalStartTime
            Log.e(TAG, "❌ VL模型推理失败，总耗时: ${totalTime}ms", e)
            e.printStackTrace()
            ScreenState(
                elements = emptyList(),
                semanticDescription = "",
                vlAvailable = false
            )
        }
    }
    
    /**
     * 图像预处理（调整到目标尺寸，保持宽高比，填充）
     * 
     * **关键点**：
     * - 模型支持动态输入（`[-1, -1]`），可处理任意尺寸的截图
     * - 当前使用 672x672（测试更大尺寸，平衡速度和精度）
     * - 真实截图会自动缩放和填充到目标尺寸（保持宽高比）
     * 
     * **详细说明**：请参考 `01-06-Qwen2-VL模型结构研究.md` 中的「图像尺寸选择说明」章节
     * 
     * @param bitmap 原始图像（任意尺寸，如 1080x2400）
     * @return 预处理后的图像（目标尺寸，RGB格式，保持宽高比，黑色填充）
     */
    private fun preprocessImage(bitmap: Bitmap): Bitmap {
        // 目标尺寸：672x672（测试中，可根据需要调整，推荐能被 14 整除的尺寸：448, 560, 672, 896, 1120）
        // 详细说明请参考文档：01-06-Qwen2-VL模型结构研究.md
        // 预期：Grid=48x48, Patches=2304, 推理时间~40秒，内存~5GB
        val targetSize = 672
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
        
        // 创建448x448的画布，用黑色填充
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
        // 根据错误信息，模型期望 reshape 为 [-1, 3, 2, 14, 14]，所以 temporal=2
        val temporalPatchSize = 2 // 时间维度patch大小（模型期望为2）
        
        val width = bitmap.width
        val height = bitmap.height
        
        // 1. 计算grid_thw（网格信息）
        // grid_h = ceil(height / patch_size), grid_w = ceil(width / patch_size)
        val gridH = ceil(height.toDouble() / patchSize).toLong()
        val gridW = ceil(width.toDouble() / patchSize).toLong()
        val gridT = 1L // 单张图像，时间维度为1
        val gridThw = longArrayOf(gridT, gridH, gridW)
        val numPatches = (gridT * gridH * gridW).toInt()
        
        // 2. 模型期望的输入格式：[-1, -1]（2维扁平化）
        // 模型内部会将其reshape为: [num_patches, channels, temporal, patch_h, patch_w]
        // 所以我们需要扁平化为: [num_patches, channels * temporal * patch_h * patch_w]
        val patchDataSize = channels * temporalPatchSize * patchSize * patchSize // 3 * 2 * 14 * 14 = 1176
        Log.d(TAG, "图像尺寸: ${width}x${height}, grid_thw: [${gridT}, ${gridH}, ${gridW}], patches数量: $numPatches")
        Log.d(TAG, "模型期望输入形状（扁平化）: [num_patches, $patchDataSize] = [num_patches, channels * temporal * patch_h * patch_w]")
        Log.d(TAG, "模型内部会reshape为: [num_patches, $channels, $temporalPatchSize, $patchSize, $patchSize]")
        
        // 3. 将图像转换为patch格式（扁平化）
        // 数据布局顺序: [num_patches, channels, temporal, patch_h, patch_w] 然后扁平化
        val pixelValues = FloatArray(numPatches * patchDataSize)
        
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        
        // 将图像分割为patches，每个patch大小为14x14
        // 数据布局（扁平化）: [num_patches, channels, temporal, patch_h, patch_w]
        // 扁平化索引 = patchIndex * patchDataSize + channel * (temporal * patch_h * patch_w) + temporal_idx * (patch_h * patch_w) + py * patch_w + px
        for (patchY in 0 until gridH.toInt()) {
            for (patchX in 0 until gridW.toInt()) {
                val patchIndex = (patchY * gridW.toInt() + patchX)
                val patchBaseOffset = patchIndex * patchDataSize
                
                // 提取当前patch的像素（14x14）
                for (py in 0 until patchSize) {
                    for (px in 0 until patchSize) {
                        val imgX = patchX * patchSize + px
                        val imgY = patchY * patchSize + py
                        
                        // 边界检查（如果图像尺寸不是patchSize的整数倍）
                        val pixelValue = if (imgX < width && imgY < height) {
                            val pixel = pixels[imgY * width + imgX]
                            val r = ((pixel shr 16) and 0xFF) / 255.0f
                            val g = ((pixel shr 8) and 0xFF) / 255.0f
                            val b = (pixel and 0xFF) / 255.0f
                            floatArrayOf(r, g, b)
                        } else {
                            // 边界外的像素填充为0（黑色）
                            floatArrayOf(0.0f, 0.0f, 0.0f)
                        }
                        
                        // 对于每个通道，填充到两个时间步（temporal=2）
                        // 单张图像，两个时间步使用相同的数据
                        // 数据布局: [channel, temporal, patch_h, patch_w]
                        for (c in 0 until channels) {
                            for (t in 0 until temporalPatchSize) {
                                // 索引计算: channel * (temporal * patch_h * patch_w) + temporal * (patch_h * patch_w) + py * patch_w + px
                                val offset = patchBaseOffset + c * (temporalPatchSize * patchSize * patchSize) + t * (patchSize * patchSize) + py * patchSize + px
                                pixelValues[offset] = pixelValue[c]
                            }
                        }
                    }
                }
            }
        }
        
        // 4. 创建输入张量（2维扁平化格式）
        // pixel_values: [num_patches, patch_data_size] = [num_patches, 1176]
        // 模型内部会将其reshape为: [num_patches, channels, temporal, patch_h, patch_w]
        val pixelValuesShape = longArrayOf(numPatches.toLong(), patchDataSize.toLong())
        Log.d(TAG, "pixel_values形状（扁平化）: ${pixelValuesShape.contentToString()} = [num_patches=$numPatches, patch_data_size=$patchDataSize]")
        Log.d(TAG, "pixel_values总元素数: ${pixelValues.size} (期望: ${numPatches * patchDataSize})")
        Log.d(TAG, "模型内部会reshape为: [num_patches=$numPatches, channels=$channels, temporal=$temporalPatchSize, patch_h=$patchSize, patch_w=$patchSize]")
        
        val pixelValuesTensor = OnnxTensor.createTensor(
            ortEnv!!, 
            FloatBuffer.wrap(pixelValues), 
            pixelValuesShape
        )
        
        // grid_thw: 根据模型输入节点信息，应该是 [-1, 3]
        // 但根据错误信息，模型内部尝试将 {69,69} reshape 为 {34,2,34,2}
        // 根据官方文档，grid_thw 应该是 [batch_size, 3] = [1, [grid_t, grid_h, grid_w]]
        // 但错误信息显示输入形状是 {69,69}，可能是模型内部处理的问题
        // 尝试标准格式: [1, 3] = [grid_t, grid_h, grid_w]
        val gridThwShape = longArrayOf(1, 3)
        val gridThwArray = longArrayOf(gridT, gridH, gridW)
        Log.d(TAG, "grid_thw形状: ${gridThwShape.contentToString()} = [batch_size=1, 3]")
        Log.d(TAG, "grid_thw值: [grid_t=$gridT, grid_h=$gridH, grid_w=$gridW]")
        Log.d(TAG, "grid_thw数组: ${gridThwArray.contentToString()}")
        Log.d(TAG, "⚠️ 注意：如果出现 reshape 错误 {69,69} -> {34,2,34,2}，可能是模型内部处理的问题")
        
        val gridThwTensor = OnnxTensor.createTensor(
            ortEnv!!,
            LongBuffer.wrap(gridThwArray),
            gridThwShape
        )
        
        // 验证 grid_thw 张量的实际形状
        val gridThwInfo = gridThwTensor.info as? TensorInfo
        val gridThwActualShape = gridThwInfo?.shape?.contentToString() ?: "未知"
        Log.d(TAG, "grid_thw实际形状: $gridThwActualShape")
        
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
        Log.d(TAG, "========== 准备执行视觉编码器推理 ==========")
        Log.d(TAG, "输入张量数量: ${inputs.size}")
        inputs.forEach { (name, tensor) ->
            val info = tensor.info as? TensorInfo
            val shape = info?.shape?.contentToString() ?: "未知"
            Log.d(TAG, "  输入[$name]: 形状=$shape")
        }
        Log.d(TAG, "⚠️ 注意：视觉编码器推理可能需要30秒到几分钟，请耐心等待...")
        val startTime = System.currentTimeMillis()
        val outputs = try {
            visionEncoderSession!!.run(inputs)
        } catch (e: OutOfMemoryError) {
            Log.e(TAG, "❌ 视觉编码器推理内存不足", e)
            // 清理输入张量
            pixelValuesTensor.close()
            gridThwTensor.close()
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "❌ 视觉编码器推理执行失败", e)
            e.printStackTrace()
            // 清理输入张量
            pixelValuesTensor.close()
            gridThwTensor.close()
            throw e
        }
        val inferenceTime = System.currentTimeMillis() - startTime
        Log.d(TAG, "✅ 视觉编码器推理完成，耗时: ${inferenceTime}ms (${inferenceTime / 1000.0}秒)")
        
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
     * 从OnnxTensor中提取FloatArray数据
     */
    private fun extractTensorData(tensor: OnnxTensor): FloatArray {
        val tensorInfo = tensor.info as? TensorInfo
        val shape = tensorInfo?.shape ?: throw IllegalStateException("无法获取张量形状")
        
        // 计算总元素数
        val totalElements = shape.fold(1L) { acc, dim ->
            if (dim > 0) acc * dim else acc
        }.toInt()
        
        // 提取数据：使用getFloatBuffer()方法
        val buffer = tensor.floatBuffer
        buffer.rewind() // 确保buffer位置在开始
        val data = FloatArray(buffer.remaining())
        buffer.get(data)
        
        return data
    }
    
    /**
     * 检查decoder模型是否支持input_ids输入
     */
    private fun decoderSupportsInputIds(): Boolean {
        return ortSession?.inputNames?.contains("input_ids") == true
    }
    
    /**
     * 使用embed_tokens模型获取文本嵌入
     * 
     * @param tokenIds token ID列表
     * @return 文本嵌入向量数组，形状为 [tokenCount * embeddingDim]
     */
    private fun getTextEmbeddingsFromEmbedTokens(tokenIds: List<Int>): FloatArray {
        if (embedTokensSession == null) {
            throw IllegalStateException("文本嵌入模型未加载")
        }
        
        if (tokenIds.isEmpty()) {
            Log.w(TAG, "⚠️ tokenIds为空，返回空嵌入")
            return FloatArray(0)
        }
        
        Log.d(TAG, "开始使用embed_tokens模型获取文本嵌入，token数量: ${tokenIds.size}")
        
        val startTime = System.currentTimeMillis()
        val inputs = mutableMapOf<String, OnnxTensor>()
        
        try {
            // 准备输入：input_ids [batch_size, sequence_length]
            // 对于单个序列，batch_size=1
            val inputIdsArray = tokenIds.toIntArray()
            val inputIdsShape = longArrayOf(1, tokenIds.size.toLong()) // [1, seq_len]
            
            // 创建input_ids张量（需要Long类型）
            val inputIdsLong = inputIdsArray.map { it.toLong() }.toLongArray()
            val inputIdsTensor = OnnxTensor.createTensor(ortEnv!!, LongBuffer.wrap(inputIdsLong), inputIdsShape)
            inputs["input_ids"] = inputIdsTensor
            
            Log.d(TAG, "输入[input_ids]: 形状=${inputIdsShape.contentToString()}, 数据类型=INT64")
            
            // 执行推理
            val outputs = embedTokensSession!!.run(inputs)
            
            // 获取输出（通常是嵌入向量）
            // 输出名称可能是 "output" 或 "last_hidden_state" 或其他，需要根据实际模型确定
            val outputTensor = outputs.firstOrNull()?.value as? OnnxTensor
                ?: throw IllegalStateException("无法获取嵌入模型的输出张量")
            
            // 提取嵌入数据
            val embedTensorInfo = outputTensor.info as? TensorInfo
            val embedShape = embedTensorInfo?.shape ?: longArrayOf(0, 0, 0)
            Log.d(TAG, "文本嵌入输出形状: ${embedShape.contentToString()}")
            
            // 通常输出形状是 [batch_size, sequence_length, embedding_dim]
            // 对于batch_size=1的情况，提取的数据将是 [seq_len * embed_dim] 的平铺数组
            val embeddingData = extractTensorData(outputTensor)
            
            // 验证嵌入维度
            val expectedEmbeddingDim = 1536 // 与图像嵌入维度相同
            val actualEmbeddingDim: Int
            val actualSeqLen: Int
            
            if (embedShape.size >= 3) {
                // 形状是 [batch_size, seq_len, embed_dim]
                val batchSize = embedShape[0].toInt()
                actualSeqLen = embedShape[1].toInt()
                actualEmbeddingDim = embedShape[2].toInt()
                
                if (batchSize != 1) {
                    Log.w(TAG, "⚠️ batch_size不是1: $batchSize，可能需要调整数据提取")
                }
            } else if (embedShape.size == 2) {
                // 形状是 [seq_len, embed_dim]，没有batch维度
                actualSeqLen = embedShape[0].toInt()
                actualEmbeddingDim = embedShape[1].toInt()
            } else {
                // 异常情况，尝试从数据大小推断
                actualEmbeddingDim = embeddingData.size / tokenIds.size
                actualSeqLen = tokenIds.size
                Log.w(TAG, "⚠️ 无法从形状确定维度，从数据大小推断：embed_dim=$actualEmbeddingDim, seq_len=$actualSeqLen")
            }
            
            // 验证数据大小
            val expectedDataSize = actualSeqLen * actualEmbeddingDim
            if (embeddingData.size != expectedDataSize) {
                Log.w(TAG, "⚠️ 嵌入数据大小不匹配：期望${expectedDataSize}，实际${embeddingData.size}")
            }
            
            // 验证序列长度
            if (actualSeqLen != tokenIds.size) {
                Log.w(TAG, "⚠️ 序列长度不匹配：期望${tokenIds.size}，实际${actualSeqLen}")
            }
            
            // 验证嵌入维度
            if (actualEmbeddingDim != expectedEmbeddingDim) {
                Log.w(TAG, "⚠️ 嵌入维度不匹配：期望${expectedEmbeddingDim}，实际${actualEmbeddingDim}")
                // 继续执行，但可能需要调整
            }
            
            Log.d(TAG, "✅ 文本嵌入获取成功，形状: [batch=1, seq_len=${actualSeqLen}, embed_dim=${actualEmbeddingDim}]")
            Log.d(TAG, "✅ 文本嵌入数据大小: ${embeddingData.size} (期望: ${expectedDataSize})")
            Log.d(TAG, "✅ 文本嵌入获取耗时: ${System.currentTimeMillis() - startTime}ms")
            
            // 清理输入张量
            inputIdsTensor.close()
            
            // 清理其他输出（但保留我们使用的）
            outputs.forEach { if (it.value != outputTensor) (it.value as? OnnxTensor)?.close() }
            outputTensor.close()
            
            return embeddingData
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ 使用embed_tokens模型获取文本嵌入失败", e)
            // 清理输入
            inputs.values.forEach { it.close() }
            throw e
        }
    }
    
    /**
     * 获取文本嵌入（从embed_tokens模型获取）
     * 
     * 如果decoder支持input_ids，可以直接使用（返回空数组）；否则使用embed_tokens模型获取嵌入
     */
    private fun getTextEmbeddingsFromDecoder(tokenIds: List<Int>): FloatArray {
        // 检查decoder是否支持input_ids
        if (decoderSupportsInputIds()) {
            // decoder支持input_ids，不需要单独获取嵌入
            // 返回空数组，表示使用input_ids方式
            Log.d(TAG, "Decoder支持input_ids，将直接使用token IDs")
            return FloatArray(0)
        }
        
        // decoder不支持input_ids，需要使用embed_tokens模型获取文本嵌入
        if (embedTokensSession == null) {
            throw IllegalStateException("Decoder不支持input_ids，且embed_tokens模型未加载，无法获取文本嵌入")
        }
        
        Log.d(TAG, "使用embed_tokens模型获取文本嵌入（${tokenIds.size}个token）")
        return getTextEmbeddingsFromEmbedTokens(tokenIds)
    }
    
    /**
     * 合并图像和文本嵌入为 inputs_embeds
     * 
     * 格式：根据Qwen2-VL的格式要求
     * 通常是：[vision_start_token_embed, image_embeds..., vision_end_token_embed, text_embeds...]
     */
    private fun mergeImageAndTextEmbeddings(
        imageEmbedsData: FloatArray,
        textEmbeds: FloatArray,
        tokenIds: List<Int>
    ): Pair<FloatArray?, LongArray?> {
        // 如果textEmbeds为空（使用input_ids方式），返回imageEmbedsData和tokenIds
        if (textEmbeds.isEmpty() && decoderSupportsInputIds()) {
            // 使用input_ids方式：返回图像嵌入和合并后的token IDs
            val visionStartId = tokenizer?.getVisionStartTokenId()
            val visionEndId = tokenizer?.getVisionEndTokenId()
            
            if (visionStartId == null || visionEndId == null) {
                throw IllegalStateException("无法获取vision特殊token ID")
            }
            
            // 构建完整的token IDs序列：[vision_start, image_tokens..., vision_end, text_tokens...]
            // 注意：图像特征数量需要转换为token数量（通常是1:1或1:4的比例）
            // 根据Qwen2-VL，图像特征数量 = image_embeds_data.size / 1536
            val imageFeatureCount = imageEmbedsData.size / 1536
            
            // 构建token序列（简化：假设图像特征对应一定数量的token）
            // 实际格式需要根据Qwen2-VL的具体要求
            val mergedTokenIds = mutableListOf<Int>()
            mergedTokenIds.add(visionStartId)
            // 图像特征对应的token（可能需要特殊处理）
            // 暂时添加占位符，实际需要根据模型格式
            for (i in 0 until imageFeatureCount) {
                // 可能需要特殊的图像token或使用特殊标记
                // TODO: 确认Qwen2-VL中图像特征如何转换为token
            }
            mergedTokenIds.add(visionEndId)
            mergedTokenIds.addAll(tokenIds.filter { it != visionStartId && it != visionEndId })
            
            // 将Int列表转换为LongArray
            val mergedTokenIdsLong = mergedTokenIds.map { it.toLong() }.toLongArray()
            return Pair(null, mergedTokenIdsLong) // null表示使用input_ids
        }
        
        // 使用inputs_embeds方式：合并图像和文本嵌入
        // 根据Qwen2-VL官方实现，正确的方式是：
        // 1. 所有token（包括image_pad）通过embed_tokens转换为嵌入
        // 2. 找到image_pad token的位置（ID=151655）
        // 3. 用图像嵌入替换这些位置的嵌入（masked_scatter）
        Log.d(TAG, "使用inputs_embeds方式合并嵌入（masked_scatter方式）")
        
        val visionStartId = tokenizer?.getVisionStartTokenId()
        val visionEndId = tokenizer?.getVisionEndTokenId()
        val imageTokenId = 151655 // 根据官方config.json，使用Int类型匹配tokenIds
        val embeddingDim = 1536
        
        // 计算图像特征数量
        val imageFeatureCount = imageEmbedsData.size / embeddingDim
        
        // 找到所有image_pad token的位置
        val imagePadIndices = tokenIds.mapIndexedNotNull { index, tokenId ->
            if (tokenId == imageTokenId) index else null
        }
        
        Log.d(TAG, "找到 ${imagePadIndices.size} 个image_pad token (ID=$imageTokenId)")
        
        if (imagePadIndices.size != imageFeatureCount) {
            throw IllegalStateException("image_pad token数量(${imagePadIndices.size})与图像特征数量($imageFeatureCount)不匹配")
        }
        
        // 总序列长度：就是tokenIds的数量（因为我们已经通过embed_tokens转换了所有token）
        val totalSeqLen = tokenIds.size
        
        if (textEmbeds.isEmpty() || textEmbeds.size != totalSeqLen * embeddingDim) {
            throw IllegalStateException("textEmbeds大小(${textEmbeds.size})与tokenIds数量($totalSeqLen)不匹配")
        }
        
        Log.d(TAG, "序列长度计算详情:")
        Log.d(TAG, "  - tokenIds总数: ${tokenIds.size}")
        Log.d(TAG, "  - 图像特征数量: $imageFeatureCount")
        Log.d(TAG, "  - image_pad token数量: ${imagePadIndices.size}")
        Log.d(TAG, "  - 总序列长度: $totalSeqLen")
        Log.d(TAG, "  - 嵌入维度: $embeddingDim")
        
        // 创建合并后的嵌入数组：先复制所有文本嵌入（包括image_pad的嵌入）
        val mergedEmbeds = FloatArray(textEmbeds.size)
        System.arraycopy(textEmbeds, 0, mergedEmbeds, 0, textEmbeds.size)
        
        // 使用masked_scatter方式：替换image_pad token位置的嵌入为图像嵌入
        var imageOffset = 0
        for (padIndex in imagePadIndices) {
            val embedOffset = padIndex * embeddingDim
            if (embedOffset + embeddingDim <= mergedEmbeds.size && imageOffset + embeddingDim <= imageEmbedsData.size) {
                System.arraycopy(imageEmbedsData, imageOffset, mergedEmbeds, embedOffset, embeddingDim)
                imageOffset += embeddingDim
            } else {
                throw IllegalStateException("替换image_pad嵌入时索引越界: padIndex=$padIndex, embedOffset=$embedOffset, imageOffset=$imageOffset")
            }
        }
        
        Log.d(TAG, "✅ 使用masked_scatter方式替换了 ${imagePadIndices.size} 个image_pad token的嵌入")
        
        val expectedSize = totalSeqLen * embeddingDim
        Log.d(TAG, "✅ 合并嵌入完成，总大小: ${mergedEmbeds.size} (期望: $expectedSize)")
        
        if (mergedEmbeds.size != expectedSize) {
            throw IllegalStateException("合并嵌入大小不匹配：actual=${mergedEmbeds.size}, expected=$expectedSize")
        }
        
        Log.d(TAG, "✅ 序列长度验证通过: $totalSeqLen tokens, ${mergedEmbeds.size} 字节")
        return Pair(mergedEmbeds, null) // 返回inputs_embeds，不使用input_ids
    }
    
    /**
     * 运行Decoder推理
     * 
     * @param inputsEmbedsOrTokens 如果是Pair<FloatArray?, LongArray?>：
     *   - FloatArray不为null：使用inputs_embeds
     *   - LongArray不为null：使用input_ids（token IDs）
     */
    private fun runDecoderWithInputs(
        inputsEmbedsOrTokens: Pair<FloatArray?, LongArray?>,
        imageFeatureCount: Int,
        textTokenCount: Int
    ): OnnxTensor {
        if (ortSession == null) {
            throw IllegalStateException("解码器模型未加载")
        }
        
        val inputs = mutableMapOf<String, OnnxTensor>()
        val session = ortSession!!
        
        // 检查使用哪种输入方式
        val (inputsEmbeds, inputIds) = inputsEmbedsOrTokens
        
        // 计算序列长度（在更外层作用域定义，以便后续使用）
        val seqLen = if (inputIds != null) {
            inputIds.size
        } else if (inputsEmbeds != null) {
            // inputs_embeds的形状是 [batch, seq_len, hidden_size]
            // 实际长度需要从inputsEmbeds中计算，这里先用textTokenCount估算
            textTokenCount + imageFeatureCount
        } else {
            throw IllegalStateException("既没有input_ids也没有inputs_embeds")
        }
        
        if (inputIds != null && decoderSupportsInputIds()) {
            // 使用input_ids方式
            Log.d(TAG, "使用input_ids方式调用decoder")
            
            // 1. 准备input_ids
            val inputIdsShape = longArrayOf(1, inputIds.size.toLong()) // [batch, seq_len]
            val inputIdsTensor = OnnxTensor.createTensor(
                ortEnv!!,
                LongBuffer.wrap(inputIds),
                inputIdsShape
            )
            inputs["input_ids"] = inputIdsTensor
            
            // 2. 准备attention_mask（全1，表示所有token都参与注意力）
            // 注意：目前保持与inputs_embeds/input_ids的长度一致
            // 如果后续发现需要调整，可以根据past_key_values的seq_len维度来调整
            val attentionMask = LongArray(seqLen) { 1L }
            val attentionMaskShape = longArrayOf(1, seqLen.toLong())
            val attentionMaskTensor = OnnxTensor.createTensor(
                ortEnv!!,
                LongBuffer.wrap(attentionMask),
                attentionMaskShape
            )
            inputs["attention_mask"] = attentionMaskTensor
            
            // 3. 准备position_ids（位置编码）
            // Qwen2-VL的position_ids格式可能是 [3, batch, seq_len]
            // TODO: 确认position_ids的具体格式
            val positionIds = preparePositionIds(seqLen)
            inputs["position_ids"] = positionIds
            
            // 4. 初始化past_key_values（第一次推理，所有past_key_values为null或零）
            initializePastKeyValues(inputs, session, seqLen)
            
        } else if (inputsEmbeds != null) {
            // 使用inputs_embeds方式
            Log.d(TAG, "使用inputs_embeds方式调用decoder")
            
            // 1. 准备inputs_embeds
            // Shape: [batch=1, seq_len, hidden_size=1536]
            val originalSeqLen = inputsEmbeds.size / 1536
            
            if (inputsEmbeds.size != originalSeqLen * 1536) {
                throw IllegalStateException("inputs_embeds大小不匹配：${inputsEmbeds.size} != $originalSeqLen * 1536")
            }
            
            // Note: ONNX Runtime Android does not support zero-dimension tensors.
            // Therefore, past_key_values must use seq_len=1 as a placeholder (instead of 0 for empty cache).
            // This causes a sequence length mismatch that cannot be resolved through code-level workarounds.
            // See: https://github.com/microsoft/onnxruntime/issues/26841
            val pastSeqLen = 1  // ONNX Runtime limitation: cannot use 0 for empty cache
            val embeddingDim = 1536
            
            // inputs_embeds保持原始长度，不padding
            val inputsEmbedsShape = longArrayOf(1, originalSeqLen.toLong(), embeddingDim.toLong())
            val inputsEmbedsTensor = OnnxTensor.createTensor(
                ortEnv!!,
                FloatBuffer.wrap(inputsEmbeds),
                inputsEmbedsShape
            )
            inputs["inputs_embeds"] = inputsEmbedsTensor
            Log.d(TAG, "inputs_embeds形状: [${inputsEmbedsShape.contentToString()}]")
            
            // 2. 准备attention_mask
            // attention_mask length = past_seq_len + current_seq_len
            // First position is 0 (ignore past_key_values placeholder), rest are 1 (for inputs_embeds)
            val attentionMaskLen = pastSeqLen + originalSeqLen
            val attentionMask = LongArray(attentionMaskLen) { index ->
                if (index < pastSeqLen) 0L else 1L
            }
            val attentionMaskShape = longArrayOf(1, attentionMaskLen.toLong())
            val attentionMaskTensor = OnnxTensor.createTensor(
                ortEnv!!,
                LongBuffer.wrap(attentionMask),
                attentionMaskShape
            )
            inputs["attention_mask"] = attentionMaskTensor
            Log.d(TAG, "attention_mask: length=$attentionMaskLen, shape=${attentionMaskShape.contentToString()}")
            
            // 3. 准备position_ids
            // position_ids should match inputs_embeds length, not attention_mask length
            val positionIdsLen = originalSeqLen
            val positionIds = preparePositionIds(positionIdsLen)
            inputs["position_ids"] = positionIds
            
            // 4. 初始化past_key_values（第一次推理）
            initializePastKeyValues(inputs, session, pastSeqLen)
        } else {
            throw IllegalStateException("既没有input_ids也没有inputs_embeds")
        }
        
        // 验证所有必需的输入都已提供
        val requiredInputs = session.inputNames.toSet()
        val providedInputs = inputs.keys
        val missingInputs = requiredInputs - providedInputs
        
        if (missingInputs.isNotEmpty()) {
            // 某些输入可能可选（如past_key_values在第一次推理时可能为null）
            Log.d(TAG, "⚠️ 缺少以下输入节点: $missingInputs")
            // 对于past_key_values，第一次推理需要初始化
            for (missingInput in missingInputs) {
                if (missingInput.startsWith("past_key_values")) {
                    // 初始化past_key_values
                    try {
                        initializePastKeyValue(inputs, session, missingInput, seqLen)
                    } catch (e: Exception) {
                        Log.w(TAG, "⚠️ 初始化 past_key_value '$missingInput' 失败，尝试跳过: ${e.message}")
                        // 如果初始化失败，可能这个输入是可选的，继续
                    }
                } else {
                    Log.e(TAG, "❌ 缺少必需的输入节点: $missingInput")
                }
            }
        }
        
        // 执行推理前的维度验证
        val inputsEmbedsShape = (inputs["inputs_embeds"]?.info as? TensorInfo)?.shape
        val attentionMaskShape = (inputs["attention_mask"]?.info as? TensorInfo)?.shape
        val positionIdsShape = (inputs["position_ids"]?.info as? TensorInfo)?.shape
        
        val currentSeqLen = inputsEmbedsShape?.getOrNull(1)?.toInt() ?: -1
        val attentionMaskLen = attentionMaskShape?.getOrNull(1)?.toInt() ?: -1
        val positionIdsLen = positionIdsShape?.getOrNull(2)?.toInt() ?: -1
        
        // Only log warnings for actual mismatches
        if (currentSeqLen > 0 && attentionMaskLen > 0 && currentSeqLen != attentionMaskLen) {
            Log.w(TAG, "Sequence length mismatch: inputs_embeds=$currentSeqLen, attention_mask=$attentionMaskLen")
        }
        if (currentSeqLen > 0 && positionIdsLen > 0 && currentSeqLen != positionIdsLen) {
            Log.w(TAG, "Sequence length mismatch: inputs_embeds=$currentSeqLen, position_ids=$positionIdsLen")
        }
        
        // 执行推理
        Log.d(TAG, "========== 开始Decoder推理 ==========")
        Log.d(TAG, "输入节点数量: ${inputs.size}")
        inputs.forEach { (name, tensor) ->
            val info = tensor.info as? TensorInfo
            val shape = info?.shape?.contentToString() ?: "未知"
            Log.d(TAG, "  输入[$name]: 形状=$shape")
        }
        
        val startTime = System.currentTimeMillis()
        val outputs = try {
            session.run(inputs)
        } catch (e: Exception) {
            Log.e(TAG, "❌ Decoder推理失败", e)
            // 清理输入张量
            inputs.values.forEach { it.close() }
            throw e
        }
        
        val inferenceTime = System.currentTimeMillis() - startTime
        Log.d(TAG, "✅ Decoder推理完成，耗时: ${inferenceTime}ms (${inferenceTime / 1000.0}秒)")
        
        // 获取logits输出（查找名为"logits"的输出节点）
        val logitsTensor = outputs.firstOrNull { it.key == "logits" }?.value as? OnnxTensor
            ?: throw IllegalStateException("无法获取logits输出：未找到名为'logits'的输出节点")
        
        // 清理输入张量
        inputs.values.forEach { it.close() }
        
        // 清理其他输出（但保留logits）
        outputs.forEach { if (it.key != "logits") (it.value as? OnnxTensor)?.close() }
        
        return logitsTensor
    }
    
    /**
     * 准备position_ids（位置编码）
     * 
     * Qwen2-VL的position_ids格式可能是 [3, batch, seq_len]
     */
    private fun preparePositionIds(seqLen: Int): OnnxTensor {
        // TODO: 确认position_ids的具体格式
        // 可能的格式：[3, batch, seq_len]，包含3个不同的位置编码
        // 简化实现：创建标准的position_ids
        val batchSize = 1
        val positionIdsShape = longArrayOf(3, batchSize.toLong(), seqLen.toLong())
        
        // 创建position_ids数据：每个位置的索引
        val positionIds = LongArray(3 * batchSize * seqLen)
        for (i in 0 until seqLen) {
            positionIds[i] = i.toLong() // 第一组：标准位置
            positionIds[seqLen + i] = i.toLong() // 第二组
            positionIds[2 * seqLen + i] = i.toLong() // 第三组
        }
        
        return OnnxTensor.createTensor(
            ortEnv!!,
            LongBuffer.wrap(positionIds),
            positionIdsShape
        )
    }
    
    /**
     * 初始化past_key_values（第一次推理）
     * 
     * @param seqLen past_key_values的seq_len维度（必须>=1，ONNX Runtime不支持0维度）
     *                See: https://github.com/microsoft/onnxruntime/issues/26841
     */
    private fun initializePastKeyValues(
        inputs: MutableMap<String, OnnxTensor>,
        session: OrtSession,
        seqLen: Int
    ) {
        // 查找所有past_key_values输入节点
        val pastKeyValueNames = session.inputNames.filter { it.startsWith("past_key_values") }
        
        // ONNX Runtime Android limitation: cannot use 0 for empty cache
        val actualSeqLen = seqLen.coerceAtLeast(1)
        
        for (name in pastKeyValueNames) {
            initializePastKeyValue(inputs, session, name, actualSeqLen)
        }
    }
    
    /**
     * 初始化单个past_key_value
     * 
     * 注意：对于第一次推理，past_key_values 通常是空的或使用小的初始值
     * 如果形状包含动态维度（-1），需要用具体值替换
     * 
     * past_key_values形状: [batch, num_heads, seq_len, head_dim]
     * 对于第一次推理，seq_len应该是0（空缓存），但ONNX Runtime Android不支持0维度，所以使用1
     * See: https://github.com/microsoft/onnxruntime/issues/26841
     */
    private fun initializePastKeyValue(
        inputs: MutableMap<String, OnnxTensor>,
        session: OrtSession,
        name: String,
        seqLen: Int
    ) {
        val inputInfo = session.inputInfo[name]
        val tensorInfo = inputInfo?.info as? TensorInfo
        val originalShape = tensorInfo?.shape ?: return
        
        Log.d(TAG, "初始化 past_key_value '$name', 原始形状: ${originalShape.contentToString()}")
        
        // 检查是否有动态维度（-1）
        val hasDynamicDim = originalShape.any { it < 0 }
        
        if (hasDynamicDim) {
            // 如果有动态维度，使用具体值替换
            // past_key_values 形状通常是 [batch, num_heads, seq_len, head_dim] = [-1, 2, -1, 128]
            // 对于第一次推理：
            //   - batch = 1
            //   - num_heads = 2
            //   - seq_len = seqLen (must be >= 1 due to ONNX Runtime limitation)
            //   - head_dim = 128
            val concreteShape = originalShape.mapIndexed { index, dim ->
                when {
                    dim < 0 -> {
                        // 根据维度位置和已知信息推断应该的值
                        when (index) {
                            0 -> 1L  // batch_size = 1
                            1 -> {
                                // num_heads维度，检查原始形状是否有固定值
                                if (originalShape.size > index + 1 && originalShape[index + 1] > 0) {
                                    // 如果下一个维度是固定的，说明这个维度也是固定的（但可能是-1）
                                    // 从模型结构看，num_heads通常是2
                                    2L
                                } else {
                                    2L                                      // 默认使用2（根据模型结构）
                                }
                            }
                            2 -> seqLen.toLong().coerceAtLeast(1L)  // seq_len (must be >= 1, ONNX Runtime limitation)
                            3 -> {
                                // head_dim维度，检查是否有固定值
                                if (index < originalShape.size && originalShape[index] > 0) {
                                    originalShape[index]
                                } else {
                                    128L  // 默认使用128（根据模型结构）
                                }
                            }
                            else -> {
                                // 其他维度，尝试从原始形状中获取固定值，否则使用1
                                if (index < originalShape.size && originalShape[index] > 0) {
                                    originalShape[index]
                                } else {
                                    1L
                                }
                            }
                        }
                    }
                    else -> dim  // 固定维度，直接使用
                }
            }.toLongArray()
            
            // 确保所有维度都是有效的（>0）
            val finalShape = concreteShape.map { if (it <= 0L) 1L else it }.toLongArray()
            val finalTotalElements = finalShape.fold(1L) { acc, dim -> acc * dim }.toInt()
            
            Log.d(TAG, "past_key_value '$name' has dynamic dimensions, using concrete shape: ${finalShape.contentToString()}, elements: $finalTotalElements")
            
            // 创建空的tensor（所有值为0）
            val data = FloatArray(finalTotalElements) { 0f }
            val tensor = OnnxTensor.createTensor(
                ortEnv!!,
                FloatBuffer.wrap(data),
                finalShape
            )
            inputs[name] = tensor
            Log.d(TAG, "✅ 已初始化 past_key_value '$name' (形状: ${finalShape.contentToString()}, 元素数: $finalTotalElements)")
        } else {
            // 没有动态维度，直接使用原始形状
            val totalElements = originalShape.fold(1L) { acc, dim -> acc * dim }.toInt()
            val data = FloatArray(totalElements) { 0f }
            val tensor = OnnxTensor.createTensor(
                ortEnv!!,
                FloatBuffer.wrap(data),
                originalShape
            )
            inputs[name] = tensor
            Log.d(TAG, "✅ 已初始化 past_key_value '$name' (形状: ${originalShape.contentToString()}, 元素数: $totalElements)")
        }
    }
    
    /**
     * 解码 logits 为文本
     */
    private fun decodeLogits(logitsTensor: OnnxTensor): String {
        if (tokenizer == null || !tokenizer!!.isLoaded) {
            throw IllegalStateException("Tokenizer未加载，无法解码logits")
        }
        
        try {
            val tensorInfo = logitsTensor.info as? TensorInfo
            val shape = tensorInfo?.shape ?: throw IllegalStateException("无法获取logits形状")
            
            // logits形状应该是: [batch, seq_len, vocab_size]
            // 通常是: [1, seq_len, 151936]
            val batchSize = if (shape.size >= 1 && shape[0] > 0) shape[0].toInt() else 1
            val seqLen = if (shape.size >= 2 && shape[1] > 0) shape[1].toInt() else 0
            val vocabSize = if (shape.size >= 3 && shape[2] > 0) shape[2].toInt() else 151936
            
            Log.d(TAG, "Logits形状: [batch=$batchSize, seq_len=$seqLen, vocab_size=$vocabSize]")
            
            // 提取logits数据
            val buffer = logitsTensor.floatBuffer
            buffer.rewind() // 确保buffer位置在开始
            val logitsData = FloatArray(buffer.remaining())
            buffer.get(logitsData)
            
            // 对每个位置进行argmax，找到最高概率的token ID
            val tokenIds = mutableListOf<Int>()
            for (pos in 0 until seqLen) {
                var maxProb = Float.NEGATIVE_INFINITY
                var maxTokenId = 0
                
                // 在当前位置的所有vocab中找到最高概率的token
                for (vocabId in 0 until vocabSize) {
                    val index = pos * vocabSize + vocabId
                    if (index < logitsData.size) {
                        val prob = logitsData[index]
                        if (prob > maxProb) {
                            maxProb = prob
                            maxTokenId = vocabId
                        }
                    }
                }
                
                tokenIds.add(maxTokenId)
            }
            
            Log.d(TAG, "解码得到 ${tokenIds.size} 个token IDs")
            
            // 使用tokenizer解码token IDs为文本
            val decodedText = tokenizer!!.decode(tokenIds)
            Log.d(TAG, "解码后的文本长度: ${decodedText.length}")
            
            return decodedText
        } catch (e: Exception) {
            Log.e(TAG, "❌ Logits解码失败", e)
            throw e
        }
    }
    
    /**
     * 解析JSON输出为ScreenState结构
     */
    private fun parseOutputToScreenState(outputText: String, imageWidth: Int, imageHeight: Int): ScreenState {
        try {
            // 1. 从输出文本中提取JSON部分
            // 模型输出可能包含一些前缀或后缀文本，需要提取JSON
            val jsonText = extractJsonFromText(outputText)
            
            if (jsonText.isEmpty()) {
                Log.w(TAG, "⚠️ 无法从输出中提取JSON，使用原始文本作为描述")
                return ScreenState(
                    elements = emptyList(),
                    semanticDescription = outputText.take(500),
                    vlAvailable = true
                )
            }
            
            // 2. 解析JSON
            val json = org.json.JSONObject(jsonText)
            
            // 3. 解析elements
            val elementsJson = json.optJSONArray("elements")
            val elements = if (elementsJson != null) {
                parseElements(elementsJson, imageWidth, imageHeight)
            } else {
                emptyList()
            }
            
            // 4. 解析semantic_description
            val semanticDescription = json.optString("semantic_description", "")
            
            Log.d(TAG, "✅ JSON解析成功: elements=${elements.size}, description长度=${semanticDescription.length}")
            
            return ScreenState(
                elements = elements,
                semanticDescription = semanticDescription,
                vlAvailable = true
            )
        } catch (e: Exception) {
            Log.e(TAG, "❌ JSON解析失败", e)
            // 解析失败时，返回包含原始文本的描述
            return ScreenState(
                elements = emptyList(),
                semanticDescription = outputText.take(500),
                vlAvailable = true
            )
        }
    }
    
    /**
     * 从输出文本中提取JSON部分
     */
    private fun extractJsonFromText(text: String): String {
        // 尝试查找JSON对象（以{开始，以}结束）
        var braceCount = 0
        var jsonStart = -1
        
        for (i in text.indices) {
            when (text[i]) {
                '{' -> {
                    if (braceCount == 0) {
                        jsonStart = i
                    }
                    braceCount++
                }
                '}' -> {
                    braceCount--
                    if (braceCount == 0 && jsonStart >= 0) {
                        return text.substring(jsonStart, i + 1)
                    }
                }
            }
        }
        
        // 如果没找到完整的JSON对象，尝试查找JSON数组
        if (jsonStart < 0) {
            var bracketCount = 0
            var arrayStart = -1
            
            for (i in text.indices) {
                when (text[i]) {
                    '[' -> {
                        if (bracketCount == 0) {
                            arrayStart = i
                        }
                        bracketCount++
                    }
                    ']' -> {
                        bracketCount--
                        if (bracketCount == 0 && arrayStart >= 0) {
                            return text.substring(arrayStart, i + 1)
                        }
                    }
                }
            }
        }
        
        return ""
    }
    
    /**
     * 解析elements数组
     */
    private fun parseElements(elementsJson: org.json.JSONArray, imageWidth: Int, imageHeight: Int): List<com.testwings.utils.UIElement> {
        val elements = mutableListOf<com.testwings.utils.UIElement>()
        
        for (i in 0 until elementsJson.length()) {
            try {
                val elementJson = elementsJson.getJSONObject(i)
                val element = parseUIElement(elementJson, imageWidth, imageHeight)
                elements.add(element)
            } catch (e: Exception) {
                Log.w(TAG, "解析element[$i]失败", e)
            }
        }
        
        return elements
    }
    
    /**
     * 解析单个UIElement
     */
    private fun parseUIElement(elementJson: org.json.JSONObject, imageWidth: Int, imageHeight: Int): com.testwings.utils.UIElement {
        val typeStr = elementJson.optString("type", "OTHER")
        val type = when (typeStr.lowercase()) {
            "button" -> com.testwings.utils.UIElementType.BUTTON
            "input", "textfield", "edittext" -> com.testwings.utils.UIElementType.INPUT
            "text", "label" -> com.testwings.utils.UIElementType.TEXT
            "image", "icon" -> com.testwings.utils.UIElementType.IMAGE
            else -> com.testwings.utils.UIElementType.OTHER
        }
        
        val text = elementJson.optString("text", "")
        
        // 解析bounds
        val boundsJson = elementJson.optJSONObject("bounds")
        val bounds = if (boundsJson != null) {
            android.graphics.Rect(
                boundsJson.optInt("x", 0),
                boundsJson.optInt("y", 0),
                boundsJson.optInt("x", 0) + boundsJson.optInt("width", 0),
                boundsJson.optInt("y", 0) + boundsJson.optInt("height", 0)
            )
        } else {
            // 如果没有bounds，使用center创建默认bounds
            val centerJson = elementJson.optJSONObject("center")
            val centerX = centerJson?.optInt("x", imageWidth / 2) ?: (imageWidth / 2)
            val centerY = centerJson?.optInt("y", imageHeight / 2) ?: (imageHeight / 2)
            android.graphics.Rect(centerX - 50, centerY - 20, centerX + 50, centerY + 20)
        }
        
        // 解析center
        val centerJson = elementJson.optJSONObject("center")
        val center = if (centerJson != null) {
            android.graphics.Point(
                centerJson.optInt("x", bounds.centerX()),
                centerJson.optInt("y", bounds.centerY())
            )
        } else {
            android.graphics.Point(bounds.centerX(), bounds.centerY())
        }
        
        val confidence = elementJson.optDouble("confidence", 1.0).toFloat()
        val semanticDescription = elementJson.optString("semantic_description", "")
        
        return com.testwings.utils.UIElement(
            type = type,
            text = text,
            bounds = bounds,
            center = center,
            confidence = confidence,
            semanticDescription = semanticDescription
        )
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
