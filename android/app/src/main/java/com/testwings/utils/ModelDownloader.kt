package com.testwings.utils

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * 模型文件下载管理器
 * 负责从Hugging Face下载VL模型文件
 */
class ModelDownloader(private val context: Context) {
    
    private val TAG = "ModelDownloader"
    
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
     * 模型文件URL（Hugging Face）
     * 优先使用：onnx-community/Qwen2-VL-2B-Instruct
     * 默认下载 INT8 量化版本（推荐）
     */
    private val modelUrl = "https://huggingface.co/onnx-community/Qwen2-VL-2B-Instruct/resolve/main/onnx/decoder_model_merged_int8.onnx"
    
    /**
     * 配置文件URL
     */
    private val configUrl = "https://huggingface.co/onnx-community/Qwen2-VL-2B-Instruct/resolve/main/config.json"
    
    /**
     * 预处理器配置文件URL
     */
    private val preprocessorConfigUrl = "https://huggingface.co/onnx-community/Qwen2-VL-2B-Instruct/resolve/main/preprocessor_config.json"
    
    /**
     * Tokenizer文件URL（可选）
     */
    private val tokenizerUrl = "https://huggingface.co/onnx-community/Qwen2-VL-2B-Instruct/resolve/main/tokenizer.json"
    
    /**
     * 下载结果
     */
    data class DownloadResult(
        val success: Boolean,
        val filePath: String? = null,
        val error: String? = null,
        val downloadedBytes: Long = 0,
        val totalBytes: Long = 0
    )
    
    /**
     * 下载模型文件
     * @param onProgress 下载进度回调（0-100）
     * @return 下载结果
     */
    suspend fun downloadModel(onProgress: ((Int) -> Unit)? = null): DownloadResult = withContext(Dispatchers.IO) {
        // 下载时保持原始文件名（decoder_model_merged_int8.onnx）
        val modelFile = File(modelsDir, "decoder_model_merged_int8.onnx")
        
        return@withContext try {
            Log.d(TAG, "开始下载模型文件: $modelUrl")
            Log.d(TAG, "保存路径: ${modelFile.absolutePath}")
            
            val result = downloadFile(modelUrl, modelFile, onProgress)
            
            if (result.success) {
                Log.d(TAG, "模型文件下载成功: ${modelFile.absolutePath}, 大小: ${modelFile.length()} bytes")
            } else {
                Log.e(TAG, "模型文件下载失败: ${result.error}")
            }
            
            result
        } catch (e: Exception) {
            Log.e(TAG, "下载模型文件异常", e)
            DownloadResult(
                success = false,
                error = "下载异常: ${e.message}"
            )
        }
    }
    
    /**
     * 下载Tokenizer文件
     * @param onProgress 下载进度回调（0-100）
     * @return 下载结果
     */
    suspend fun downloadTokenizer(onProgress: ((Int) -> Unit)? = null): DownloadResult = withContext(Dispatchers.IO) {
        val tokenizerFile = File(modelsDir, "tokenizer.json")
        
        return@withContext try {
            Log.d(TAG, "开始下载Tokenizer文件: $tokenizerUrl")
            Log.d(TAG, "保存路径: ${tokenizerFile.absolutePath}")
            
            val result = downloadFile(tokenizerUrl, tokenizerFile, onProgress)
            
            if (result.success) {
                Log.d(TAG, "Tokenizer文件下载成功: ${tokenizerFile.absolutePath}, 大小: ${tokenizerFile.length()} bytes")
            } else {
                Log.e(TAG, "Tokenizer文件下载失败: ${result.error}")
            }
            
            result
        } catch (e: Exception) {
            Log.e(TAG, "下载Tokenizer文件异常", e)
            DownloadResult(
                success = false,
                error = "下载异常: ${e.message}"
            )
        }
    }
    
    /**
     * 下载配置文件
     * @param onProgress 下载进度回调（0-100）
     * @return 下载结果
     */
    suspend fun downloadConfig(onProgress: ((Int) -> Unit)? = null): DownloadResult = withContext(Dispatchers.IO) {
        val configFile = File(modelsDir, "config.json")
        
        return@withContext try {
            Log.d(TAG, "开始下载配置文件: $configUrl")
            Log.d(TAG, "保存路径: ${configFile.absolutePath}")
            
            val result = downloadFile(configUrl, configFile, onProgress)
            
            if (result.success) {
                Log.d(TAG, "配置文件下载成功: ${configFile.absolutePath}")
            } else {
                Log.e(TAG, "配置文件下载失败: ${result.error}")
            }
            
            result
        } catch (e: Exception) {
            Log.e(TAG, "下载配置文件异常", e)
            DownloadResult(
                success = false,
                error = "下载异常: ${e.message}"
            )
        }
    }
    
    /**
     * 下载预处理器配置文件
     * @param onProgress 下载进度回调（0-100）
     * @return 下载结果
     */
    suspend fun downloadPreprocessorConfig(onProgress: ((Int) -> Unit)? = null): DownloadResult = withContext(Dispatchers.IO) {
        val preprocessorConfigFile = File(modelsDir, "preprocessor_config.json")
        
        return@withContext try {
            Log.d(TAG, "开始下载预处理器配置文件: $preprocessorConfigUrl")
            Log.d(TAG, "保存路径: ${preprocessorConfigFile.absolutePath}")
            
            val result = downloadFile(preprocessorConfigUrl, preprocessorConfigFile, onProgress)
            
            if (result.success) {
                Log.d(TAG, "预处理器配置文件下载成功: ${preprocessorConfigFile.absolutePath}")
            } else {
                Log.e(TAG, "预处理器配置文件下载失败: ${result.error}")
            }
            
            result
        } catch (e: Exception) {
            Log.e(TAG, "下载预处理器配置文件异常", e)
            DownloadResult(
                success = false,
                error = "下载异常: ${e.message}"
            )
        }
    }
    
    /**
     * 下载所有必需的文件（模型 + 配置文件 + Tokenizer）
     * @param onProgress 下载进度回调（进度百分比，状态描述）
     * @return 是否全部下载成功
     */
    suspend fun downloadAll(onProgress: ((Int, String) -> Unit)? = null): Boolean = withContext(Dispatchers.IO) {
        // 下载模型文件（0-70%）
        val modelResult = downloadModel { progress ->
            onProgress?.invoke((progress * 0.7).toInt(), "下载模型文件...")
        }
        
        if (!modelResult.success) {
            Log.e(TAG, "模型文件下载失败，停止下载")
            return@withContext false
        }
        
        // 下载配置文件（70-85%）
        val configResult = downloadConfig { progress ->
            onProgress?.invoke(70 + (progress * 0.15).toInt(), "下载配置文件...")
        }
        
        if (!configResult.success) {
            Log.e(TAG, "配置文件下载失败")
            return@withContext false
        }
        
        // 下载预处理器配置文件（85-95%）
        val preprocessorConfigResult = downloadPreprocessorConfig { progress ->
            onProgress?.invoke(85 + (progress * 0.1).toInt(), "下载预处理器配置...")
        }
        
        if (!preprocessorConfigResult.success) {
            Log.e(TAG, "预处理器配置文件下载失败")
            return@withContext false
        }
        
        // 下载Tokenizer文件（95-100%，可选）
        val tokenizerResult = downloadTokenizer { progress ->
            onProgress?.invoke(95 + (progress * 0.05).toInt(), "下载Tokenizer文件...")
        }
        
        // Tokenizer是可选的，失败不影响整体
        if (!tokenizerResult.success) {
            Log.w(TAG, "Tokenizer文件下载失败（可选文件，不影响使用）")
        }
        
        Log.d(TAG, "所有必需文件下载成功")
        return@withContext true
    }
    
    /**
     * 下载文件（支持断点续传）
     */
    private suspend fun downloadFile(
        urlString: String,
        targetFile: File,
        onProgress: ((Int) -> Unit)? = null
    ): DownloadResult = withContext(Dispatchers.IO) {
        var connection: HttpURLConnection? = null
        var inputStream: InputStream? = null
        var outputStream: FileOutputStream? = null
        
        try {
            val url = URL(urlString)
            connection = url.openConnection() as HttpURLConnection
            
            // 支持断点续传
            val existingSize = if (targetFile.exists()) targetFile.length() else 0L
            if (existingSize > 0) {
                connection.setRequestProperty("Range", "bytes=$existingSize-")
                Log.d(TAG, "断点续传: 已下载 $existingSize bytes")
            }
            
            connection.connectTimeout = 30000 // 30秒
            connection.readTimeout = 60000 // 60秒
            connection.connect()
            
            val responseCode = connection.responseCode
            if (responseCode != HttpURLConnection.HTTP_OK && 
                responseCode != HttpURLConnection.HTTP_PARTIAL) {
                return@withContext DownloadResult(
                    success = false,
                    error = "HTTP错误: $responseCode"
                )
            }
            
            val contentLength = connection.contentLengthLong
            val totalSize = if (contentLength > 0) existingSize + contentLength else -1
            
            inputStream = connection.inputStream
            outputStream = if (existingSize > 0) {
                // 追加模式（断点续传）
                FileOutputStream(targetFile, true)
            } else {
                // 新建文件
                FileOutputStream(targetFile)
            }
            
            val buffer = ByteArray(8192)
            var downloaded = existingSize
            var bytesRead: Int
            
            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                outputStream.write(buffer, 0, bytesRead)
                downloaded += bytesRead
                
                // 更新进度
                if (totalSize > 0) {
                    val progress = ((downloaded * 100) / totalSize).toInt()
                    onProgress?.invoke(progress)
                }
            }
            
            outputStream.flush()
            
            DownloadResult(
                success = true,
                filePath = targetFile.absolutePath,
                downloadedBytes = downloaded,
                totalBytes = totalSize
            )
        } catch (e: Exception) {
            Log.e(TAG, "下载文件失败: ${urlString}", e)
            DownloadResult(
                success = false,
                error = "下载失败: ${e.message}",
                downloadedBytes = if (targetFile.exists()) targetFile.length() else 0
            )
        } finally {
            inputStream?.close()
            outputStream?.close()
            connection?.disconnect()
        }
    }
    
    /**
     * 获取模型文件路径（用于显示给用户）
     * 通过文件后缀自动发现 .onnx 模型文件
     */
    fun getModelFilePath(): String {
        // 按优先级查找模型文件
        val priorityFiles = listOf(
            "decoder_model_merged_int8.onnx",      // INT8量化（推荐）
            "decoder_model_merged_q4f16.onnx",     // Q4F16量化（内存不足时）
            "decoder_model_merged.onnx",           // 未量化版本
            "qwen_vl_chat_int8.onnx"               // 兼容旧版本
        )
        
        // 先按优先级查找
        for (fileName in priorityFiles) {
            val file = File(modelsDir, fileName)
            if (file.exists() && file.length() > 0) {
                return file.absolutePath
            }
        }
        
        // 如果优先级文件都不存在，查找任何 .onnx 文件
        modelsDir.listFiles()?.forEach { file ->
            if (file.isFile && file.name.endsWith(".onnx", ignoreCase = true) && file.length() > 0) {
                return file.absolutePath
            }
        }
        
        // 如果都没有，返回默认文件名（用于错误提示）
        return File(modelsDir, "decoder_model_merged_int8.onnx").absolutePath
    }
    
    /**
     * 检查文件是否已下载
     * 通过文件后缀自动发现 .onnx 模型文件
     */
    fun isModelDownloaded(): Boolean {
        // 检查是否有任何 .onnx 模型文件
        val modelExists = modelsDir.listFiles()?.any { file ->
            file.isFile && file.name.endsWith(".onnx", ignoreCase = true) && file.length() > 0
        } ?: false
        
        // 检查配置文件（必需）
        val configFile = File(modelsDir, "config.json")
        val configExists = configFile.exists() && configFile.length() > 0
        
        return modelExists && configExists
    }
}
