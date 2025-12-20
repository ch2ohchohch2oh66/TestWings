# ONNX Runtime Android Zero Dimension限制导致Transformer模型past_key_values初始化失败问题分析

## 📋 问题概述

**问题本质**：ONNX Runtime Android不支持创建0维度的tensor，导致使用`past_key_values`进行KV缓存的Transformer模型（如Qwen2-VL、LLaMA等）在Android平台上无法正常进行首次推理。

**错误现象**：
```
ai.onnxruntime.OrtException: Error code - ORT_RUNTIME_EXCEPTION
Shape mismatch attempting to re-use buffer. {1,12,605,128} != {1,12,606,128}
```

**错误位置**：第二层attention的Reshape操作（`/model/layers.1/self_attn/Reshape_6_output_0`）

**核心矛盾**：
- 模型期望序列长度为606（基于`past_seq_len=1 + current_seq_len=605`）
- 但`inputs_embeds`的实际长度为605（正确的token数量）
- 差异：1个token，对应`past_key_values`的`seq_len=1`占位符

---

## 🔍 问题定位过程

### 阶段1：初步诊断与格式验证（2025-12-20）

#### 步骤1.1：官方格式验证

**目的**：确认我们的序列构建是否符合官方格式要求

**执行内容**：
- 运行验证脚本 `tools/verify_official_format.py`
- 对比官方`apply_chat_template`生成的格式与我们的实现
- 检查token ID是否与官方一致

**关键发现**：
- ✅ 官方格式包含system message，但ONNX推理不需要（会导致序列长度变成614）
- ✅ 我们的实现（605 tokens）符合ONNX推理的要求
- ✅ Token ID与官方一致（151644, 151645, 151652, 151653, 151655）

**结论**：
- 序列构建是正确的，问题不在序列构建逻辑

---

### 阶段2：输入形状分析与调整尝试（2025-12-20）

#### 方案1-10：多种输入形状调整尝试

**总体思路**：尝试通过调整`inputs_embeds`、`attention_mask`、`position_ids`的长度来解决不匹配问题。

**方案1：Padding `inputs_embeds`** ❌ **已尝试3次，均失败 - 绝对禁止再次尝试**

**配置**：
- `inputs_embeds`: padding到606（在开头添加1个零向量）
- `attention_mask`: 606
- `position_ids`: 606

**结果**：
1. 第一次尝试：错误从 `605 by 606` 变成 `606 by 607`
2. 第二次尝试：错误 `{1,12,605,128} != {1,12,606,128}` 变成 `606 by 607`
3. 第三次尝试：错误仍然是 `606 by 607`

**失败原因**：
- Padding会导致模型期望长度同步增加，形成无限循环
- 模型期望长度 = `inputs_embeds长度 + 1`，这个规律在padding后仍然成立

**教训**：Padding策略无效，且会导致期望长度同步增加。

---

**方案2-5：调整attention_mask和position_ids长度** ❌

**尝试的配置**：
- 方案2：`attention_mask`=606（全1），`position_ids`=605
- 方案3：所有输入长度都设为605
- 方案4：基于GitHub Issue #1990调整attention_mask
- 方案5：`position_ids`长度匹配`attention_mask`（606）

**结果**：
- 方案2/4：错误 `{1,12,605,128} != {1,12,606,128}`（第二层attention）
- 方案3：错误 `605 by 606`（第一层attention）
- 方案5：错误 `605 by 606`（第一层attention）

**失败原因**：
- 仅调整`attention_mask`或`position_ids`无法解决问题
- 模型内部期望`inputs_embeds`的长度也等于总序列长度（606）
- 但`inputs_embeds`的实际长度是605（正确的token数量）

**教训**：调整mask和position_ids无法解决根本问题，核心矛盾是`inputs_embeds`长度与模型期望不一致。

---

**方案6：测试 `past_key_values` 的 `seq_len=0`** ❌

**配置**：
- `past_key_values`: seq_len=0（尝试，但ONNX不支持，回退到1）

**结果**：
- ONNX Runtime不支持0维度的tensor，自动回退到seq_len=1
- 错误：`605 by 606`（与方案3相同）

**失败原因**：
- ONNX Runtime不支持创建0维度的tensor
- 无法使用空缓存（seq_len=0）来解决期望长度多1的问题

**关键发现**：**这是问题的根源之一** - ONNX Runtime的限制导致必须使用seq_len=1作为占位符。

---

**方案7：检查模型配置** ✅

**执行内容**：
- 读取`config.json`并提取所有关键配置参数
- 验证配置参数与代码中的硬编码值是否一致

**结果**：
- ✅ 所有配置参数正确：
  - `hidden_size = 1536` ✅
  - `num_key_value_heads = 2` ✅
  - `num_hidden_layers = 28` ✅
- ✅ past_key_values的形状初始化正确

**结论**：配置参数不是问题的根源。

---

**方案8-9：调整attention_mask内容和position_ids长度** ❌

**方案8配置**：
- `attention_mask`: [1, 606] = `[0, 1, 1, ..., 1]`（第一个位置设为0，忽略past_key_values占位符）
- `position_ids`: 605

**结果**：错误 `{1,12,605,128} != {1,12,606,128}`（第二层attention）

**方案9配置**：
- `attention_mask`: [1, 606] = `[0, 1, 1, ..., 1]`
- `position_ids`: 606（匹配attention_mask）

**结果**：错误 `605 by 606`（第一层attention，错误提前）

**失败原因**：
- 即使attention_mask的第一个位置设为0（忽略past_key_values占位符），模型内部仍然期望序列长度为606
- attention_mask的长度（606）决定了模型内部期望的序列长度，而不仅仅是attention_mask的内容
- position_ids应该匹配inputs_embeds的长度（605），而不是attention_mask的长度

**教训**：模型基于attention_mask的长度计算内部序列长度，而不是基于内容。

---

**方案10：修复序列构建 - 在`<|vision_end|>`后添加换行符** ❌

**思路**：官方格式要求`<|vision_end|>\n`，但代码中未添加换行符

**实施**：
- 在`<|vision_end|>`后添加`\n`的编码：`val newlineTokens = tokenizer!!.encode("\n")`

**结果**：
- tokenizer编码`\n`后，返回的token数量为0
- 序列长度仍然是605（没有变成606）

**失败原因**：
- `\n`可能已经被包含在之前编码的token中
- 或tokenizer对单独编码`\n`时返回空列表

**结论**：无法通过添加换行符来增加序列长度。

---

### 阶段3：深入分析与根本原因定位（2025-12-20）

#### 步骤3.1：ONNX模型输入要求分析 ✅

**目的**：确认所有输入形状是否符合ONNX模型要求

**执行内容**：
- 分析ONNX模型对所有输入节点的形状要求
- 检查我们提供的输入是否符合要求

**关键发现**：
- ✅ `inputs_embeds`: `[-1, -1, 1536]` → 我们提供 `[1, 605, 1536]` ✅
- ✅ `attention_mask`: `[-1, -1]` → 我们提供 `[1, 606]` ✅
- ✅ `position_ids`: `[3, -1, -1]` → 我们提供 `[3, 1, 605]` ✅
- ✅ `past_key_values`: `[-1, 2, -1, 128]` → 我们提供 `[1, 2, 1, 128]` ✅

**结论**：
- 所有输入的形状都符合ONNX模型的要求
- 问题不在输入形状本身，而在模型内部的计算逻辑

---

#### 步骤3.2：past_key_values处理分析 ✅

**目的**：确认past_key_values的初始化方式是否正确

**执行内容**：
- 检查past_key_values的初始化方式
- 确认是否应该使用其他值而不是零向量

**关键发现**：
- ✅ 零向量初始化是正确的（标准做法）
- ✅ seq_len=1是ONNX的限制（无法使用0维度）
- ✅ 问题不在past_key_values的初始化值

**结论**：
- past_key_values的处理方式是正确的

---

#### 步骤3.3：查找示例代码对比分析 ⭐⭐⭐ **关键发现**

**目的**：查找社区中其他人的成功实现，对比差异

**执行内容**：
- 查找qwen2-export-onnx仓库的`chat_onnx.py`实现
- 对比Python实现与Android实现的差异

**关键发现**：

**Python实现（chat_onnx.py）**：
```python
past_key_values = []
for i in range(2*n_layer):
    past_key_values.append(np.zeros((1, n_kv_heads, 0, dim_head), np.float16))
    # seq_len = 0 ✅ Python/NumPy支持

attention_mask = np.ones_like(input_ids)  # 长度 = input_ids长度
```

**序列长度关系（Python）**：
- past_seq_len = 0（空缓存）
- current_seq_len = N（input_ids长度）
- **总序列长度 = 0 + N = N**
- attention_mask长度 = N ✅（与input_ids一致）
- inputs_embeds长度 = N ✅
- ✅ **所有长度都一致**

---

**Android实现（我们的代码）**：
```kotlin
val pastSeqLen = 1  // ONNX不支持0维度
val shape = longArrayOf(1, 2, pastSeqLen.toLong(), 128)  // seq_len = 1

val attentionMaskLen = pastSeqLen + originalSeqLen  // 1 + 605 = 606
```

**序列长度关系（Android）**：
- past_seq_len = 1（ONNX限制，占位符）
- current_seq_len = 605（inputs_embeds长度）
- **总序列长度 = 1 + 605 = 606**
- attention_mask长度 = 606 ✅（逻辑上正确）
- inputs_embeds长度 = 605 ❌（不匹配总序列长度）
- ❌ **长度不一致导致错误**

---

**根本差异分析**：

| 项目 | Python实现 | Android实现 | 差异 |
|------|-----------|-------------|------|
| past_key_values.seq_len | 0 | 1 | ⚠️ ONNX限制 |
| 总序列长度 | 0 + N = N | 1 + 605 = 606 | ⚠️ 差异 |
| attention_mask长度 | N | 606 | ✅ 逻辑正确 |
| inputs_embeds长度 | N | 605 | ❌ 不匹配 |
| 结果 | ✅ 成功 | ❌ 失败 | ⚠️ 根本差异 |

**核心矛盾**：
- 模型期望：`inputs_embeds`长度 = 总序列长度 = `past_seq_len + current_seq_len`
- Python：`0 + N = N` ✅
- Android：`1 + 605 = 606`，但`inputs_embeds`只有605 ❌

**结论**：
- ✅ **找到了根本原因**：ONNX Runtime Android不支持0维度，导致必须使用seq_len=1作为占位符
- ⚠️ **但这导致序列长度计算不匹配**：模型将seq_len=1当作真实位置，期望总序列长度为606

---

#### 步骤3.4：HuggingFace页面信息提取与分析 ✅

**目的**：确认官方ONNX转换脚本的配置

**执行内容**：
- 访问HuggingFace页面：https://huggingface.co/onnx-community/Qwen2-VL-2B-Instruct
- 提取ONNX转换脚本的关键信息

**关键发现**：
1. ✅ **官方ONNX转换脚本使用`past_sequence_length = 0`**
   - 与Python实现（chat_onnx.py）一致
   - 确认了问题的根源：Android ONNX Runtime不支持0维度

2. ✅ **动态维度定义显示模型的计算逻辑**
   - `present`输出的seq_len维度是：`past_sequence_length + 1`
   - 说明模型内部计算确实是：`past_seq_len + 1`（新token）

3. ✅ **输入形状关系**
   - `attention_mask`和`inputs_embeds`的sequence_length维度在导出时是一致的
   - 但导出时使用`past_sequence_length = 0`，所以第一次推理时序列长度就是新token的长度

**结论**：
- ✅ 确认了问题的根源（应该用0，但Android不支持）
- ❌ 但没有提供直接解决方案

---

#### 步骤3.5：查找ONNX Runtime配置参数 ✅

**目的**：查找是否有配置参数可以解决zero dimension问题

**执行内容**：
1. 搜索ONNX Runtime Android官方文档
2. 搜索ONNX Runtime GitHub Issues
3. 检查SessionOptions配置选项
4. 查找其他Transformer模型实现

**关键发现**：
- ✅ SessionOptions有配置方法（`addConfigEntry`、`addFreeDimensionOverride`等）
- ❌ **没有找到专门处理zero dimension的配置参数**
- ❌ `addFreeDimensionOverride`用于设置动态维度的固定值，不能解决zero dimension问题
- ⚠️ 确认了问题的本质：ONNX Runtime Android不支持0维度tensor创建，这是**运行时限制**，不是配置问题

**结论**：
- ❌ **没有找到通过配置参数解决zero dimension问题的方法**
- ⚠️ 这不是配置问题，而是运行时限制

---

### 阶段4：问题确认与Issue提交（2025-12-20）

#### 步骤4.1：确认问题根源

**最终确认的问题根源**：

1. **ONNX Runtime Android不支持0维度tensor创建**
   - 这导致`past_key_values`必须使用`seq_len=1`作为占位符
   - 无法使用`seq_len=0`（空缓存）

2. **模型内部计算逻辑**
   - 模型期望：`inputs_embeds`长度 = 总序列长度 = `past_seq_len + current_seq_len`
   - Python实现：`0 + N = N` ✅（所有输入长度一致）
   - Android实现：`1 + 605 = 606`，但`inputs_embeds`只有605 ❌

3. **所有尝试的workaround都失败**
   - Padding inputs_embeds → 期望长度同步增加（无限循环）
   - 调整attention_mask/position_ids → 无法解决根本矛盾
   - 使用seq_len=0 → ONNX不支持

**结论**：
- ✅ **问题已定位**：这是ONNX Runtime Android的运行时限制导致的
- ⚠️ **无法通过代码层面的workaround解决**
- 📌 **需要ONNX Runtime团队的支持或修复**

---

#### 步骤4.2：提交Issue到ONNX Runtime

**Issue编号**：#26841

**标题**：`[Mobile] Android: Cannot create tensor with zero dimension (seq_len=0), causing issues with past_key_values initialization in Transformer models`

**提交时间**：2025-12-20

**状态**：Open

**链接**：https://github.com/microsoft/onnxruntime/issues/26841

**Issue内容要点**：
- 问题：ONNX Runtime Android不支持创建0维度tensor，导致Transformer模型的past_key_values无法正确初始化
- 影响：所有使用past_key_values进行KV缓存的Transformer模型（如Qwen2-VL、LLaMA等）在Android上都无法正常工作
- 根本原因：Python/NumPy支持0维度，但ONNX Runtime Android不支持，导致序列长度计算不匹配
- 已尝试方案：详细列出了方案1-10和深入分析的步骤

**搜索结果**：
- ✅ 确认这是**第一个**详细描述这个问题的Issue
- ❌ 没有找到其他用户反馈相同或类似的问题

---

## 📊 问题定位总结

### 关键里程碑

| 阶段 | 时间 | 关键发现 | 结论 |
|------|------|----------|------|
| 阶段1 | 2025-12-20 | 序列构建正确 | 问题不在序列构建 |
| 阶段2 | 2025-12-20 | 方案1-10均失败 | 无法通过调整输入形状解决 |
| 阶段3 | 2025-12-20 | **找到根本差异**：Python使用seq_len=0，Android必须用seq_len=1 | **问题定位到ONNX Runtime限制** |
| 阶段4 | 2025-12-20 | 确认无法通过代码workaround解决 | 提交Issue到ONNX Runtime |

### 核心发现

1. ✅ **序列构建正确**：605个token是正确的，符合ONNX推理要求
2. ✅ **输入形状正确**：所有输入都符合ONNX模型的要求
3. ✅ **配置参数正确**：模型配置和past_key_values初始化都正确
4. ❌ **根本问题**：ONNX Runtime Android不支持0维度，导致序列长度计算不匹配
5. ✅ **问题定位**：这是ONNX Runtime的运行时限制，不是代码问题

### 最终结论

**问题本质**：
- ONNX Runtime Android不支持创建0维度的tensor
- 这导致Transformer模型的`past_key_values`无法使用`seq_len=0`初始化（空缓存）
- 必须使用`seq_len=1`作为占位符，但模型将其当作真实位置
- 导致序列长度计算不匹配：模型期望606，但`inputs_embeds`只有605

**解决方案**：
- ❌ 无法通过代码层面的workaround解决
- ✅ 已提交Issue到ONNX Runtime团队（#26841）
- ⏳ 等待官方回复和可能的修复

---

## 📝 经验教训

### 成功的分析方法

1. **系统性排查**：从格式验证→输入形状分析→配置检查→示例代码对比，逐步缩小问题范围
2. **记录禁止重复的方案**：及时记录失败的方案，避免重复尝试（如方案1的padding策略）
3. **对比成功案例**：查找Python实现进行对比，发现了根本差异
4. **深入理解底层原理**：理解ONNX Runtime的限制和模型的计算逻辑

### 避免的弯路

1. **过早假设问题在代码实现**：实际上我们的实现是正确的
2. **盲目尝试各种workaround**：应该先理解根本原因
3. **忽略官方文档和示例**：官方文档和示例代码提供了关键线索

### 关键原则

1. **从简单到复杂**：先验证基础假设，再深入分析
2. **对比成功案例**：查找其他人的实现进行对比
3. **理解底层限制**：技术框架的限制可能无法通过代码绕过
4. **及时记录和总结**：避免重复尝试失败的方案

---

## 🔗 相关资源

- **ONNX Runtime Issue**: https://github.com/microsoft/onnxruntime/issues/26841
- **Python示例代码**: https://github.com/w3ng-git/qwen2-export-onnx
- **HuggingFace模型页面**: https://huggingface.co/onnx-community/Qwen2-VL-2B-Instruct
- **ONNX Runtime Mobile文档**: https://onnxruntime.ai/docs/build/mobile.html

---

## 📌 后续跟踪

1. **定期检查Issue状态**：关注Issue #26841的更新和回复
2. **准备补充信息**：如果需要，准备好更详细的复现步骤和代码示例
3. **关注相关讨论**：如果Issue下有讨论，积极参与并提供更多技术细节
4. **评估替代方案**：如果问题无法解决，考虑其他技术方案（如OCR临时方案或其他推理框架）

---

**文档创建时间**：2025-12-20  
**最后更新时间**：2025-12-20
