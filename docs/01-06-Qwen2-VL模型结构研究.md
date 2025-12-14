# Qwen2-VL-2B-Instruct ONNX 模型结构研究

## 📋 模型架构

Qwen2-VL-2B-Instruct 是一个多模态 Vision-Language 模型，包含两个主要组件：

### 1. 视觉编码器（Vision Encoder）✅ **已验证**
- **文件**：`vision_encoder_q4f16.onnx`（Q4F16量化，约1.27GB，推荐）⭐
- **输入**（2个节点）：
  - `pixel_values`: `[-1, -1]`, FLOAT - 图像像素值（扁平化）
  - `grid_thw`: `[-1, 3]`, INT64 - 网格/位置信息（高度、宽度、时间步）
- **输出**（1个节点）：
  - `image_features`: `[-1, 1536]`, FLOAT - 图像特征向量（1536维）⭐
- **注意**：输入格式不是标准的 (batch, channels, height, width)，需要特殊处理

### 2. 解码器（Decoder Model）✅ **已验证**
- **文件**：`decoder_model_merged_q4f16.onnx`（Q4F16量化，约829MB，推荐）⭐
  - **注意**：`decoder_model_merged_int8.onnx` 不支持（ONNX Runtime Android不支持ConvInteger操作符）
- **输入**（59个节点）：
  - `inputs_embeds`: `[-1, -1, 1536]`, FLOAT - **嵌入向量输入**（已编码的文本+图像特征，1536维）⭐
  - `attention_mask`: `[-1, -1]`, INT64 - 注意力掩码
  - `position_ids`: `[3, -1, -1]`, INT64 - 位置编码
  - `past_key_values.0.key` ~ `past_key_values.27.value` (56个) - 用于增量解码的键值缓存
- **输出**（57个节点）：
  - `logits`: `[-1, -1, 151936]`, FLOAT - **词汇表logits**（151936是词汇表大小）⭐
  - `present.0.key` ~ `present.27.value` (56个) - 更新的键值缓存

## 🔍 关键发现

### 实际模型结构（已验证）✅

**视觉编码器模型**：`vision_encoder_q4f16.onnx` (1270MB) ✅ 加载成功

**输入节点（2个）**：
- `pixel_values`: `[-1, -1]`, FLOAT - **图像像素值（扁平化的patch数据）** ⭐
- `grid_thw`: `[-1, 3]`, INT64 - **网格信息** `[grid_t, grid_h, grid_w]` ⭐
  - `grid_t`: 时间维度（图像通常为1）
  - `grid_h`: 高度方向的patch数量
  - `grid_w`: 宽度方向的patch数量

**输出节点（1个）**：
- `image_features`: `[-1, 1536]`, FLOAT - **图像特征向量（1536维）** ⭐

**解码器模型**：`decoder_model_merged_q4f16.onnx` (829MB) ✅ 加载成功

**输入节点（59个）**：
- `inputs_embeds`: `[-1, -1, 1536]`, FLOAT - **嵌入向量输入**（已编码的文本+图像特征，1536维）⭐
- `attention_mask`: `[-1, -1]`, INT64 - 注意力掩码
- `position_ids`: `[3, -1, -1]`, INT64 - 位置编码
- `past_key_values.0.key` ~ `past_key_values.27.value` (56个) - 用于增量解码的键值缓存

**输出节点（57个）**：
- `logits`: `[-1, -1, 151936]`, FLOAT - **词汇表logits**（151936是词汇表大小）⭐
- `present.0.key` ~ `present.27.value` (56个) - 更新的键值缓存

**重要发现**：
1. ✅ **视觉编码器已成功加载，输入格式需要特殊处理（pixel_values + grid_thw）**
2. ✅ **视觉编码器输出1536维特征向量，与解码器输入匹配**
3. ⚠️ **需要将图像转换为patch格式，并计算grid_thw**
4. ⚠️ **需要实现文本tokenization和嵌入，合并为inputs_embeds**
5. ✅ **输出是 `logits`，需要解码为文本，然后解析为ScreenState**

### 模型文件结构
根据 Hugging Face 上的 `onnx-community/Qwen2-VL-2B-Instruct` 仓库：
- **decoder_model_merged_int8.onnx**：合并的解码器模型（INT8量化，约1.55GB）✅ 已加载
- **vision_encoder.onnx**：视觉编码器（需要单独下载？）
- **config.json**：模型配置（包含模型架构参数）✅ 已存在
- **preprocessor_config.json**：图像预处理配置 ✅ 已存在
- **tokenizer.json**：Tokenizer 配置 ✅ 已存在

### 输入输出格式

#### 文本输入格式
- 使用特殊 token 标记图像位置：
  - `<|vision_start|>`：图像开始标记
  - `<|vision_end|>`：图像结束标记
- 示例 prompt：
  ```
  <|vision_start|>Describe all UI elements in this screenshot, including their types, text content, and bounding box coordinates in JSON format.<|vision_end|>
  ```

#### 图像预处理
- **尺寸**：调整到 960x960 像素（保持宽高比，填充）
- **归一化**：根据 `preprocessor_config.json` 中的配置
- **格式**：RGB，转换为模型输入张量

#### 输出解析
- 模型输出是 token 序列，需要：
  1. 使用 tokenizer 解码为文本
  2. 解析 JSON 格式的结构化输出
  3. 转换为 `ScreenState` 结构

## 🎯 实现策略

### 方案1：使用结构化 Prompt（推荐）
让模型输出 JSON 格式的结构化数据：
```json
{
  "elements": [
    {
      "type": "button",
      "text": "捕获屏幕",
      "bounds": {"x": 100, "y": 200, "width": 200, "height": 50},
      "center": {"x": 200, "y": 225}
    }
  ],
  "semantic_description": "主屏幕，包含多个APP图标"
}
```

### 方案2：后处理解析
如果模型输出自然语言，使用 LLM 或规则解析为结构化数据。

## ✅ 已验证信息（2025-12-14）

### 模型加载测试结果（Q4F16版本）

**视觉编码器模型**：`vision_encoder_q4f16.onnx` (1270MB) ✅ 加载成功（约18秒）

**解码器模型**：`decoder_model_merged_q4f16.onnx` (829MB) ✅ 加载成功（约13秒）

**注意**：INT8量化版本使用ConvInteger操作符，ONNX Runtime Android不支持，因此使用Q4F16版本。

**输入节点（59个）**：
- `inputs_embeds`: `[-1, -1, 1536]`, FLOAT ⭐ **关键输入**（嵌入向量，1536维）
- `attention_mask`: `[-1, -1]`, INT64
- `position_ids`: `[3, -1, -1]`, INT64
- `past_key_values.0.key` ~ `past_key_values.27.value` (56个) - 增量解码缓存

**输出节点（57个）**：
- `logits`: `[-1, -1, 151936]`, FLOAT ⭐ **关键输出**（词汇表大小151936）
- `present.0.key` ~ `present.27.value` (56个) - 更新的键值缓存

### 关键发现 ⚠️

1. **这是decoder模型，不包含视觉编码器**
   - 输入是 `inputs_embeds`（已编码的嵌入向量），不是原始图像
   - 需要单独的视觉编码器将图像转换为嵌入向量

2. **需要完整的处理流程**：
   ```
   图像 → 视觉编码器 → 图像嵌入向量
   文本 → Tokenizer → Token IDs → 嵌入层 → 文本嵌入向量
   合并 → inputs_embeds → Decoder模型 → logits → Tokenizer解码 → 文本 → 解析JSON
   ```

3. **可能需要的额外文件**：
   - `vision_encoder.onnx` - 视觉编码器（需要确认是否存在）
   - `embed_tokens.onnx` - 词嵌入层（需要确认是否存在）

## 📝 待确认事项

1. **视觉编码器输入格式**：✅ **已确认**
   - ✅ 需要 `pixel_values`（扁平化的patch数据）和 `grid_thw`（网格信息）
   - ⚠️ 需要实现图像到patch的转换（patch_size通常为14）
   - ⚠️ 需要计算 `grid_thw = [grid_t, grid_h, grid_w]`（grid_t=1，grid_h=height/14，grid_w=width/14）

2. **嵌入层**：
   - 如何将文本token转换为嵌入向量？
   - 是否需要 `embed_tokens.onnx` 文件？
   - 如何合并图像嵌入和文本嵌入为 `inputs_embeds`？

3. **Tokenizer 使用**：
   - 如何在 Android 上使用 `tokenizer.json`？
   - 是否需要额外的 tokenizer 库（如 `tokenizers`）？

4. **输出解析**：
   - 如何从 `logits` 解码为文本？
   - 如何解析为结构化 JSON？

## 🔗 参考资源

- Hugging Face 模型页面：https://huggingface.co/onnx-community/Qwen2-VL-2B-Instruct
- Qwen2-VL 官方文档：https://github.com/QwenLM/Qwen-VL
- ONNX Runtime Android 文档：https://onnxruntime.ai/docs/tutorials/mobile/
