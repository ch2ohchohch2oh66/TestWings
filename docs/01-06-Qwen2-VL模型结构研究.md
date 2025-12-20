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
  - **实际形状**：`[num_patches, 1176]`（2维扁平化）
  - **模型内部reshape为**：`[num_patches, 3, 2, 14, 14]`
  - **计算方式**：
    - `num_patches = grid_t * grid_h * grid_w`（对于448x448图像：1 * 32 * 32 = 1024）
    - `1176 = channels * temporal * patch_h * patch_w = 3 * 2 * 14 * 14`
  - **数据布局**：按 `[num_patches, channels, temporal, patch_h, patch_w]` 顺序扁平化
  - **关键参数**：
    - `patch_size = 14`（固定值）
    - `channels = 3`（RGB）
    - `temporal_patch_size = 2` ⭐ **重要**：模型期望 temporal=2，不是1
- `grid_thw`: `[-1, 3]`, INT64 - **网格信息** `[grid_t, grid_h, grid_w]` ⭐
  - **实际形状**：`[1, 3]` = `[[grid_t, grid_h, grid_w]]`
  - **格式**：`[[1, 32, 32]]`（对于448x448图像）
  - `grid_t`: 时间维度（图像通常为1）
  - `grid_h`: 高度方向的patch数量 = `ceil(height / 14)`（对于448x448：32）
  - `grid_w`: 宽度方向的patch数量 = `ceil(width / 14)`（对于448x448：32）

**输出节点（1个）**：
- `image_features`: `[-1, 1536]`, FLOAT - **图像特征向量（1536维）** ⭐
  - **实际形状**：`[256, 1536]`（已验证）
  - **说明**：从1024个patches降维到256个特征向量（可能经过池化或降维）
  - **每个特征向量**：1536维（符合解码器输入要求）

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
根据 Hugging Face 上的 `onnx-community/Qwen2-VL-2B-Instruct` 仓库，完整模型由3个ONNX模型组成：

**必需模型文件（3个）**：
1. **decoder_model_merged_q4f16.onnx**：合并的解码器模型（Q4F16量化，约869MB）✅ 已加载
   - 作用：将合并的图像+文本嵌入转换为文本输出（logits）
   - 输入：`inputs_embeds`, `attention_mask`, `position_ids`, `past_key_values`
   - 输出：`logits`, `present_key_values`
2. **vision_encoder_q4f16.onnx**：视觉编码器（Q4F16量化，约1.33GB）✅ 已加载
   - 作用：将图像转换为嵌入向量（image embeddings）
   - 输入：`pixel_values`, `grid_thw`
   - 输出：`image_features` (形状: `[num_image_features, 1536]`)
3. **embed_tokens_q4f16.onnx**：文本嵌入模型（Q4F16量化，约467MB）⭐ **新增，必需**
   - 作用：将文本token IDs转换为嵌入向量（text embeddings）
   - 输入：`input_ids`（token IDs）
   - 输出：文本嵌入向量（形状: `[batch_size, sequence_length, 1536]`）

**必需配置文件（3个）**：
- **config.json**：模型配置（包含模型架构参数）✅ 已存在
- **preprocessor_config.json**：图像预处理配置 ✅ 已存在
- **tokenizer.json**：Tokenizer 配置 ✅ 已存在

**注意**：INT8量化版本不支持（ONNX Runtime Android不支持ConvInteger操作符），必须使用Q4F16版本。

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
- **尺寸**：调整到目标尺寸（保持宽高比，填充）⭐ **已验证**
  - 模型支持动态输入尺寸（`[-1, -1]`），当前使用 448x448（根据 README.md 示例）
  - 真实截图（任意尺寸）会自动缩放和填充到目标尺寸
  - 详细说明请参考下方「图像尺寸选择说明」章节
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

## ✅ 已验证信息

### 模型加载测试结果（Q4F16版本）

**视觉编码器模型**：`vision_encoder_q4f16.onnx` (1270MB) ✅ 加载成功（约6秒）

**解码器模型**：`decoder_model_merged_q4f16.onnx` (829MB) ✅ 加载成功（约4秒）

**注意**：INT8量化版本使用ConvInteger操作符，ONNX Runtime Android不支持，因此使用Q4F16版本。

---

### 视觉编码器推理测试结果（2025-12-15）✅ **已成功**

**测试环境**：
- 图像尺寸：448x448（根据 README.md 示例）
- 设备：Android 设备（12GB+ 内存）

**输入格式（已验证）**：
- `pixel_values`: `[num_patches, 1176]`（2维扁平化）
  - `num_patches = grid_t * grid_h * grid_w = 1 * 32 * 32 = 1024`
  - `1176 = channels * temporal * patch_h * patch_w = 3 * 2 * 14 * 14`
  - 模型内部会 reshape 为：`[1024, 3, 2, 14, 14]`
- `grid_thw`: `[1, 3]` = `[[grid_t, grid_h, grid_w]]` = `[[1, 32, 32]]`
  - `grid_t = 1`（单张图像）
  - `grid_h = ceil(448 / 14) = 32`
  - `grid_w = ceil(448 / 14) = 32`

**关键参数（已验证）**：
- `patch_size = 14`（固定值）
- `channels = 3`（RGB）
- `temporal_patch_size = 2` ⭐ **重要**：模型期望 temporal=2，不是1
- `grid_thw = [1, 32, 32]`（对于 448x448 图像）

**输出格式（已验证）**：
- `image_features`: 形状取决于输入图像尺寸 ⭐ **已验证**
  - **448x448**：`[256, 1536]` - 256 个特征向量（从 1024 个 patches 降维，比例 4:1）
  - **672x672**：`[576, 1536]` - 576 个特征向量（从 2304 个 patches 降维，比例 4:1）✅ **2025-12-16 测试**
  - **重要发现**：输出特征向量数量 = 输入 patches 数量 / 4（固定降维比例）
  - 每个特征向量 1536 维（符合解码器输入要求）

**推理性能对比**：

| 图像尺寸 | Patches | 输出特征数 | 推理时间 | 总耗时 |
|---------|---------|-----------|---------|--------|
| **448x448** | 1024 | 256 | ~17.9秒 | ~18.2秒 |
| **672x672** | 2304 | 576 | ~50.6秒 | ~50.9秒 ✅ **已验证** |

**测试状态**：✅ **测试成功，视觉编码器推理正常工作**

---

### 视觉编码器推理测试结果（672x672，2025-12-16）✅ **已成功**

**测试环境**：
- 图像尺寸：672x672（测试更大尺寸）
- 设备：Android 设备（12GB+ 内存）

**输入格式（已验证）**：
- `pixel_values`: `[2304, 1176]`（2维扁平化）
  - `num_patches = grid_t * grid_h * grid_w = 1 * 48 * 48 = 2304`
  - `1176 = channels * temporal * patch_h * patch_w = 3 * 2 * 14 * 14`
  - 模型内部会 reshape 为：`[2304, 3, 2, 14, 14]`
- `grid_thw`: `[1, 3]` = `[[grid_t, grid_h, grid_w]]` = `[[1, 48, 48]]`
  - `grid_t = 1`（单张图像）
  - `grid_h = ceil(672 / 14) = 48`
  - `grid_w = ceil(672 / 14) = 48`

**输出格式（已验证）**：
- `image_features`: `[576, 1536]` ⭐ **已验证**
  - 576 个特征向量（从 2304 个 patches 降维，比例 4:1）
  - 每个特征向量 1536 维（符合解码器输入要求）

**推理性能**：
- 图像预处理：~15ms
- 视觉编码器推理：~50.6秒
- 总耗时：~50.9秒

**重要发现**：
- ✅ 输出特征向量数量 = 输入 patches 数量 / 4（固定降维比例 4:1）
- ✅ 448x448: 1024 patches → 256 features
- ✅ 672x672: 2304 patches → 576 features
- ✅ 推理时间与 patches 数量大致成正比（2304/1024 ≈ 2.25，50.6/17.9 ≈ 2.83）

**测试状态**：✅ **测试成功，672x672 尺寸验证通过**

---

### 图像尺寸选择说明 ⭐ **重要**

**Q: 448x448 代表什么含义？模型只能处理这个尺寸吗？**

**A: 不是的！** 448x448 只是示例尺寸，不是硬性限制。

**关键点**：
1. ✅ **模型支持动态输入**：输入形状是 `[-1, -1]`，可以处理任意尺寸的图像
2. ✅ **代码已实现自动缩放**：真实截图（如 1080x2400）会自动缩放和填充到目标尺寸
3. ✅ **448x448 只是示例**：根据 README.md，用于快速测试和调试

**真实使用场景处理**：
- 真实截图可能是任意尺寸（如 1080x2400、1440x3200 等）
- 代码会自动：
  1. 保持宽高比缩放
  2. 填充到目标尺寸（黑色填充）
  3. 计算对应的 grid_thw
  4. 转换为 patch 格式

**尺寸选择建议**：

| 目标尺寸 | Grid | Patches | 输出特征数 | 推理时间 | 内存占用 | 适用场景 |
|---------|------|---------|-----------|---------|---------|---------|
| **448x448** | 32x32 | 1024 | 256 | ~18秒 | ~3GB | 快速测试、简单界面 ⭐ 已验证 |
| **560x560** | 40x40 | 1600 | 400 | ~28秒 | ~4GB | 中等复杂度界面 |
| **672x672** | 48x48 | 2304 | 576 | ~51秒 | ~5GB | 复杂界面、需要更高精度 ✅ **已验证** |
| **896x896** | 64x64 | 4096 | 1024 | ~60秒 | ~6GB | 高精度需求、复杂布局 |
| **1120x1120** | 80x80 | 6400 | 1600 | ~90秒 | ~8GB | 最高精度、细节丰富 |

**重要发现**：输出特征向量数量 = 输入 patches 数量 / 4（固定降维比例 4:1）

**推荐策略**：
- **开发测试**：使用 448x448（快速迭代）
- **生产环境**：根据设备性能和精度需求选择
  - 高性能设备（12GB+ 内存）：672x672 或 896x896
  - 中等性能设备（8-12GB 内存）：560x560
  - 低性能设备（<8GB 内存）：448x448

**如何调整**：
- 修改 `VisionLanguageManager.kt` 中 `preprocessImage` 方法的 `targetSize` 变量
- 推荐使用能被 14 整除的尺寸（避免计算复杂度）

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

3. **必需的额外模型文件**（已确认）：
   - `vision_encoder_q4f16.onnx` - 视觉编码器 ✅ 已确认（Q4F16量化，约1.33GB）
   - `embed_tokens_q4f16.onnx` - 文本嵌入模型 ✅ 已确认（Q4F16量化，约467MB）

## 📝 待确认事项

1. **视觉编码器输入格式**：✅ **已确认并验证**
   - ✅ 需要 `pixel_values`（扁平化的patch数据）和 `grid_thw`（网格信息）
   - ✅ 图像到patch的转换已实现（patch_size=14）
   - ✅ `grid_thw = [grid_t, grid_h, grid_w]` 格式已确认（grid_t=1，grid_h=ceil(height/14)，grid_w=ceil(width/14)）
   - ✅ 图像尺寸应为 **448x448**（根据 README.md，能被 14 整除）
   - ✅ `temporal_patch_size = 2`（模型期望，不是1）
   - ✅ `pixel_values` 形状：`[num_patches, 1176]`，模型内部 reshape 为 `[num_patches, 3, 2, 14, 14]`

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

## ⚠️ 常见问题和解决方案

### 问题1：Reshape错误 - Input shape与requested shape不匹配

**错误信息**：
```
The input tensor cannot be reshaped to the requested shape. 
Input shape:{4761,588}, requested shape:{-1,3,2,14,14}
```

**原因**：
- `temporal_patch_size` 设置为1，但模型期望为2
- `pixel_values` 形状应为 `[num_patches, 1176]`（1176 = 3 * 2 * 14 * 14），而不是 `[num_patches, 588]`

**解决方案**：
- 将 `temporal_patch_size` 设置为 `2`
- 确保 `pixel_values` 形状为 `[num_patches, 1176]`

---

### 问题2：Reshape错误 - grid_thw reshape失败

**错误信息**：
```
Input shape:{69,69}, requested shape:{34,2,34,2}
```

**原因**：
- 图像尺寸为960x960，导致 `grid_h = ceil(960/14) = 69`，`grid_w = 69`
- 69 不能被模型内部处理逻辑正确处理

**解决方案**：
- 使用能被 14 整除的目标尺寸（推荐）
- 推荐尺寸：448, 560, 672, 896, 1120（都能被 14 整除）
- 当前使用 **448x448**（根据 README.md 示例，推理速度快）

---

### 问题6：如何选择图像尺寸？

**问题**：真实截图不是 448x448，应该如何处理？

**说明**：请参考上方「图像尺寸选择说明」章节，包含详细的尺寸选择建议和推荐策略。

---

### 问题3：Invalid rank错误

**错误信息**：
```
Invalid rank for input: pixel_values Got: 5 Expected: 2
```

**原因**：
- 提供了5维张量 `[num_patches, 3, 2, 14, 14]`，但模型期望2维扁平化输入

**解决方案**：
- 将输入扁平化为 `[num_patches, 1176]`（2维）
- 模型内部会自动 reshape 为 `[num_patches, 3, 2, 14, 14]`

---

### 问题4：模型文件找不到

**原因**：
- 模型文件未下载或路径不正确

**解决方案**：
- 确保模型文件在 `/sdcard/Android/data/com.testwings/files/models/vl/` 目录
- 检查文件名是否正确（`vision_encoder_q4f16.onnx`、`decoder_model_merged_q4f16.onnx`）
- 参考 `01-05-Qwen2-VL模型部署操作手册.md` 进行部署

---

### 问题5：INT8量化模型不支持

**错误信息**：
- ConvInteger操作符相关错误

**原因**：
- ONNX Runtime Android 不支持 ConvInteger 操作符

**解决方案**：
- 使用 **Q4F16量化版本**（推荐）
- 不要使用 INT8 量化版本

---

## 🔗 参考资源

- Hugging Face 模型页面：https://huggingface.co/onnx-community/Qwen2-VL-2B-Instruct
- Qwen2-VL 官方文档：https://github.com/QwenLM/Qwen-VL
- ONNX Runtime Android 文档：https://onnxruntime.ai/docs/tutorials/mobile/
- 模型 README.md：`e:\AutoTestDemo\models\vl\README.md`（包含ONNX转换脚本和示例）
