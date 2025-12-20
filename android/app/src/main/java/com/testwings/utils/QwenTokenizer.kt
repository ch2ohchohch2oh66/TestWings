package com.testwings.utils

import android.content.Context
import android.util.Log
import org.json.JSONObject
import org.json.JSONArray
import java.io.File

/**
 * Qwen2-VL Tokenizer
 * 负责文本的tokenization和detokenization
 * 
 * 注意：这是一个简化实现，完整实现需要支持BPE算法
 */
class QwenTokenizer(private val context: Context) {
    
    private val TAG = "QwenTokenizer"
    
    /**
     * Tokenizer配置（从tokenizer.json加载）
     */
    private var tokenizerConfig: JSONObject? = null
    
    /**
     * 词汇表（token ID -> token字符串）
     */
    private val idToToken: MutableMap<Int, String> = mutableMapOf()
    
    /**
     * 词汇表（token字符串 -> token ID）
     */
    private val tokenToId: MutableMap<String, Int> = mutableMapOf()
    
    /**
     * 特殊token
     */
    private var padToken: String? = null
    private var eosToken: String? = null
    private var bosToken: String? = null
    private var visionStartToken: String? = null
    private var visionEndToken: String? = null
    
    /**
     * 模型文件目录
     */
    private val modelsDir: File by lazy {
        val externalFilesDir = context.getExternalFilesDir(null)
            ?: context.filesDir
        File(externalFilesDir, "models/vl").apply {
            if (!exists()) {
                mkdirs()
            }
        }
    }
    
    /**
     * Tokenizer文件路径
     */
    private val tokenizerFile: File
        get() = File(modelsDir, "tokenizer.json")
    
    /**
     * 是否已加载
     */
    var isLoaded: Boolean = false
        private set
    
    /**
     * 加载tokenizer配置
     */
    fun load(): Boolean {
        return try {
            if (!tokenizerFile.exists()) {
                Log.e(TAG, "❌ Tokenizer文件不存在: ${tokenizerFile.absolutePath}")
                return false
            }
            
            Log.d(TAG, "开始加载Tokenizer: ${tokenizerFile.absolutePath}")
            val jsonContent = tokenizerFile.readText()
            tokenizerConfig = JSONObject(jsonContent)
            
            // 解析词汇表（简化实现，实际需要解析BPE merges等）
            // TODO: 完整实现需要解析tokenizer.json的完整结构
            parseVocabulary()
            
            // 解析特殊token
            parseSpecialTokens()
            
            isLoaded = true
            Log.d(TAG, "✅ Tokenizer加载成功，词汇表大小: ${tokenToId.size}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "❌ Tokenizer加载失败", e)
            e.printStackTrace()
            false
        }
    }
    
    /**
     * 解析词汇表（简化实现）
     * 注意：完整实现需要支持BPE算法和tokenizer.json的完整结构
     */
    private fun parseVocabulary() {
        try {
            val config = tokenizerConfig ?: return
            
            // tokenizer.json的结构通常是：
            // {
            //   "model": {
            //     "vocab": { ... },
            //     "merges": [ ... ]
            //   },
            //   "added_tokens": [ ... ],
            //   ...
            // }
            
            // 解析vocab
            val model = config.optJSONObject("model")
            if (model != null) {
                val vocab = model.optJSONObject("vocab")
                if (vocab != null) {
                    vocab.keys().forEach { token ->
                        val id = vocab.getInt(token)
                        idToToken[id] = token
                        tokenToId[token] = id
                    }
                }
            }
            
            // 解析added_tokens（特殊token）
            val addedTokens = config.optJSONArray("added_tokens")
            if (addedTokens != null) {
                for (i in 0 until addedTokens.length()) {
                    val tokenObj = addedTokens.getJSONObject(i)
                    val content = tokenObj.getString("content")
                    val id = tokenObj.getInt("id")
                    idToToken[id] = content
                    tokenToId[content] = id
                }
            }
            
            Log.d(TAG, "词汇表解析完成，共 ${tokenToId.size} 个token")
        } catch (e: Exception) {
            Log.e(TAG, "解析词汇表失败", e)
            throw e
        }
    }
    
    /**
     * 解析特殊token
     */
    private fun parseSpecialTokens() {
        try {
            val config = tokenizerConfig ?: return
            
            // 从config.json或tokenizer.json中获取特殊token
            val configFile = File(modelsDir, "config.json")
            if (configFile.exists()) {
                try {
                    val configJson = JSONObject(configFile.readText())
                    
                    // 尝试从config.json获取（安全处理null值和空字符串）
                    // 使用optString(key)然后检查是否为空字符串或"null"
                    val padTokenStr = configJson.optString("pad_token", "").takeIf { it.isNotEmpty() && it != "null" }
                    if (padTokenStr != null) {
                        padToken = padTokenStr
                    }
                    
                    val eosTokenStr = configJson.optString("eos_token", "").takeIf { it.isNotEmpty() && it != "null" }
                    if (eosTokenStr != null) {
                        eosToken = eosTokenStr
                    }
                    
                    val bosTokenStr = configJson.optString("bos_token", "").takeIf { it.isNotEmpty() && it != "null" }
                    if (bosTokenStr != null) {
                        bosToken = bosTokenStr
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "读取config.json失败，将使用默认值: ${e.message}")
                }
            }
            
            // 如果config.json中没有，尝试从tokenizer.json的added_tokens中查找
            if (visionStartToken == null) {
                visionStartToken = "<|vision_start|>"
            }
            if (visionEndToken == null) {
                visionEndToken = "<|vision_end|>"
            }
            
            // 默认值
            if (eosToken == null) {
                eosToken = "<|endoftext|>"
            }
            if (padToken == null) {
                padToken = eosToken
            }
            
            Log.d(TAG, "特殊token: pad=$padToken, eos=$eosToken, bos=$bosToken, vision_start=$visionStartToken, vision_end=$visionEndToken")
        } catch (e: Exception) {
            Log.e(TAG, "解析特殊token失败", e)
        }
    }
    
    /**
     * 将文本转换为token IDs（简化实现）
     * 注意：完整实现需要支持BPE算法
     */
    fun encode(text: String): List<Int> {
        if (!isLoaded) {
            Log.w(TAG, "Tokenizer未加载，返回空列表")
            return emptyList()
        }
        
        try {
            // 简化实现：直接查找完整token匹配
            // TODO: 完整实现需要支持BPE算法，处理子词tokenization
            val tokens = mutableListOf<Int>()
            
            // 先处理特殊token（如<|vision_start|>）
            var remainingText = text
            val specialTokens = listOfNotNull(
                visionStartToken,
                visionEndToken,
                eosToken,
                bosToken,
                padToken
            )
            
            // 查找并处理特殊token
            while (remainingText.isNotEmpty()) {
                var found = false
                var bestMatch: Pair<String, Int>? = null
                
                for (specialToken in specialTokens) {
                    val index = remainingText.indexOf(specialToken)
                    if (index >= 0) {
                        if (bestMatch == null || index < bestMatch.second) {
                            bestMatch = Pair(specialToken, index)
                            found = true
                        }
                    }
                }
                
                if (found && bestMatch != null) {
                    // 处理特殊token之前的文本
                    val beforeText = remainingText.substring(0, bestMatch.second)
                    if (beforeText.isNotEmpty()) {
                        // 简化：按字符或常见词汇匹配
                        tokens.addAll(encodeTextSimple(beforeText))
                    }
                    
                    // 添加特殊token
                    tokenToId[bestMatch.first]?.let { tokens.add(it) }
                    
                    // 继续处理剩余文本
                    remainingText = remainingText.substring(bestMatch.second + bestMatch.first.length)
                } else {
                    // 没有找到特殊token，处理剩余文本
                    tokens.addAll(encodeTextSimple(remainingText))
                    break
                }
            }
            
            return tokens
        } catch (e: Exception) {
            Log.e(TAG, "文本编码失败", e)
            return emptyList()
        }
    }
    
    /**
     * 简化文本编码（按字符或常见词汇匹配）
     * TODO: 完整实现需要支持BPE算法
     */
    private fun encodeTextSimple(text: String): List<Int> {
        val tokens = mutableListOf<Int>()
        
        // 简化实现：先尝试查找完整词汇，然后按字符fallback
        // 实际应该使用BPE算法
        var i = 0
        while (i < text.length) {
            var matched = false
            var longestMatch: Pair<String, Int>? = null
            
            // 尝试匹配最长可能的token（简化实现，完整版本需要BPE）
            for (j in text.length downTo i + 1) {
                val candidate = text.substring(i, j)
                tokenToId[candidate]?.let { id ->
                    if (longestMatch == null || candidate.length > longestMatch!!.first.length) {
                        longestMatch = Pair(candidate, id)
                        matched = true
                    }
                }
            }
            
            if (matched && longestMatch != null) {
                tokens.add(longestMatch!!.second)
                i += longestMatch!!.first.length
            } else {
                // 未匹配，尝试按字符编码
                val char = text[i].toString()
                tokenToId[char]?.let { tokens.add(it) } ?: run {
                    // 字符也不在词汇表中，使用UNK token或跳过
                    Log.w(TAG, "未知字符: $char")
                }
                i++
            }
        }
        
        return tokens
    }
    
    /**
     * 将token IDs转换为文本
     */
    fun decode(tokenIds: List<Int>): String {
        if (!isLoaded) {
            return ""
        }
        
        return try {
            val tokens = tokenIds.mapNotNull { id ->
                idToToken[id]
            }
            tokens.joinToString("")
        } catch (e: Exception) {
            Log.e(TAG, "Token解码失败", e)
            ""
        }
    }
    
    /**
     * 获取特殊token ID
     */
    fun getVisionStartTokenId(): Int? {
        return visionStartToken?.let { tokenToId[it] }
    }
    
    fun getVisionEndTokenId(): Int? {
        return visionEndToken?.let { tokenToId[it] }
    }
    
    fun getEosTokenId(): Int? {
        return eosToken?.let { tokenToId[it] }
    }
    
    fun getPadTokenId(): Int? {
        return padToken?.let { tokenToId[it] }
    }
    
    fun getBosTokenId(): Int? {
        return bosToken?.let { tokenToId[it] }
    }
}