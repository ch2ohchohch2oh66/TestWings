# Qwen2-VL-2B-Instruct 模型部署操作手册

## 📋 目录

- [模型文件说明](#模型文件说明)
- [前置要求](#前置要求)
- [快速开始](#快速开始)
- [详细安装步骤](#详细安装步骤)
- [验证和测试](#验证和测试)
- [常见问题](#常见问题)
- [相关资源](#相关资源)

---

## 📁 模型文件说明

### 文件位置

**重要**：模型文件不再放在assets中，而是放在外部存储，避免APK过大。

```
/sdcard/Android/data/com.testwings/files/models/vl/
├── decoder_model_merged_q4f16.onnx     # 解码器模型（Q4F16量化，约869MB，推荐）⭐
│   # 或 decoder_model_merged.onnx      # 解码器模型（未量化版本，不推荐）
│   # 注意：decoder_model_merged_int8.onnx 不支持（ONNX Runtime Android不支持ConvInteger操作符）
├── vision_encoder_q4f16.onnx           # 视觉编码器（Q4F16量化，约1.33GB，推荐）⭐
│   # 或 vision_encoder.onnx            # 视觉编码器（未量化版本，不推荐）
│   # 注意：vision_encoder_int8.onnx 不支持（ONNX Runtime Android不支持ConvInteger操作符）
├── embed_tokens_q4f16.onnx             # 文本嵌入模型（Q4F16量化，约467MB，必需）⭐ 新增
│   # 或 embed_tokens.onnx              # 文本嵌入模型（未量化版本，约933MB，不推荐）
│   # 注意：embed_tokens_int8.onnx 不支持（ONNX Runtime Android不支持ConvInteger操作符）
├── config.json                          # 模型配置文件（约1.57KB，必需）
├── preprocessor_config.json             # 预处理器配置（约567B，必需）
└── tokenizer.json                       # Tokenizer配置文件（约11.4MB，必需）
```

**注意**：APP会自动识别任何 `.onnx` 文件，按优先级使用（Q4F16 > 未量化 > 其他）。**INT8量化版本不支持**（ONNX Runtime Android不支持ConvInteger操作符）

### 必需文件清单（完整列表）

| 文件类型 | 文件名 | 大小 | 必需性 | 说明 |
|---------|--------|------|--------|------|
| **模型文件** | `decoder_model_merged_q4f16.onnx` | 约869MB | ✅ 必需 | 解码器模型，将嵌入向量转换为文本 |
| **模型文件** | `vision_encoder_q4f16.onnx` | 约1.33GB | ✅ 必需 | 视觉编码器，将图像转换为嵌入向量 |
| **模型文件** | `embed_tokens_q4f16.onnx` | 约467MB | ✅ 必需 ⭐ **新增** | 文本嵌入模型，将token IDs转换为嵌入向量 |
| **配置文件** | `config.json` | 约1.57KB | ✅ 必需 | 模型架构配置和超参数 |
| **配置文件** | `preprocessor_config.json` | 约567B | ✅ 必需 | 图像预处理配置 |
| **配置文件** | `tokenizer.json` | 约11.4MB | ✅ 必需 | Tokenizer配置，用于文本编码/解码 |
| **总计** | 6个文件 | **约2.67GB** | - | - |

**可选文件（不需要下载）**：
- `tokenizer_config.json` - Tokenizer额外配置（tokenizer.json已包含所需信息）
- `generation_config.json` - 生成配置（当前不需要）
- `vocab.json`, `merges.txt` - BPE词汇表（tokenizer.json已包含）
- `added_tokens.json`, `chat_template.json` - 其他配置（当前不需要）

### 文件要求

#### 模型文件（必需）

**1. 解码器模型（Decoder Model）**
- **格式**：ONNX格式（`.onnx` 后缀）
- **文件名**：保持原始文件名，APP会自动识别
  - `decoder_model_merged_q4f16.onnx`：Q4F16量化版本，约829MB（推荐）⭐
  - `decoder_model_merged.onnx`：未量化版本（不推荐，文件更大）
  - `decoder_model_merged_int8.onnx`：INT8量化版本（不支持，ONNX Runtime Android不支持ConvInteger操作符）
- **作用**：将嵌入向量转换为文本输出（logits）
- **自动识别**：APP会按优先级自动查找（Q4F16 > 未量化 > 其他），**INT8版本会被跳过**

**2. 视觉编码器（Vision Encoder）** ⭐
- **格式**：ONNX格式（`.onnx` 后缀）
- **文件名**：保持原始文件名，APP会自动识别
  - `vision_encoder_q4f16.onnx`：Q4F16量化版本，约1.33GB（推荐）⭐
  - `vision_encoder.onnx`：未量化版本（不推荐，文件更大）
  - `vision_encoder_int8.onnx`：INT8量化版本（不支持，ONNX Runtime Android不支持ConvInteger操作符）
- **作用**：将图像转换为嵌入向量（image embeddings）
- **必需**：是（用于图像理解）
- **来源**：Hugging Face - https://huggingface.co/onnx-community/Qwen2-VL-2B-Instruct

**3. 文本嵌入模型（Embed Tokens）** ⭐ **新增，必需**
- **格式**：ONNX格式（`.onnx` 后缀）
- **文件名**：保持原始文件名，APP会自动识别
  - `embed_tokens_q4f16.onnx`：Q4F16量化版本，约467MB（推荐）⭐
  - `embed_tokens.onnx`：未量化版本（约933MB，不推荐）
  - `embed_tokens_int8.onnx`：INT8量化版本（不支持，ONNX Runtime Android不支持ConvInteger操作符）
- **作用**：将文本token IDs转换为嵌入向量（text embeddings），用于与图像嵌入合并
- **必需**：是（用于文本嵌入转换）
- **来源**：Hugging Face - https://huggingface.co/onnx-community/Qwen2-VL-2B-Instruct
- **注意**：这是新添加的必需模型，之前使用占位符方案无法正常工作

**注意**：
- 量化后准确率损失较小
- **必须使用 Q4F16 量化版本**（ONNX Runtime Android不支持INT8版本的ConvInteger操作符）
- Q4F16版本兼容性最好，推荐使用

#### config.json（模型配置）
- **格式**：JSON格式
- **内容**：模型架构配置、超参数等
- **大小**：几KB
- **必需**：是

#### preprocessor_config.json（预处理器配置）
- **格式**：JSON格式
- **内容**：输入预处理配置
- **大小**：几KB
- **必需**：是

#### tokenizer.json（Tokenizer配置）
- **格式**：JSON格式
- **大小**：约11.4MB
- **必需**：是（必需文件）

### 注意事项

1. **文件位置**：模型文件放在外部存储，不在APK中
   - APK大小正常（不会包含模型文件）
   - 每次编译安装不会重新打包模型文件
   - 模型文件只需下载/拷贝一次

2. **内存要求**：
   - Qwen2-VL-2B模型运行时需要约3-4GB内存（INT8版本，包含解码器和视觉编码器）
   - Q4F16版本需要约2-3GB内存
   - 确保设备有足够内存（推荐8GB+，12GB更佳）

3. **性能优化**：
   - 支持NPU加速的设备性能更好
   - 首次加载可能需要较长时间（需要加载两个模型：解码器和视觉编码器）

4. **存储空间**：
   - INT8版本：需要约2.2GB可用存储空间（解码器1.55GB + 视觉编码器669MB）
   - Q4F16版本：需要约2.2GB可用存储空间（解码器869MB + 视觉编码器1.33GB）
   - 确保设备有足够存储空间

---

## 📋 前置要求

- Python 3.7+ 已安装
- ADB工具已安装并配置（用于拷贝文件到手机）
- 手机已连接并启用USB调试
- 至少3-5GB可用存储空间

---

## 🚀 快速开始

### 推荐方案：onnx-community/Qwen2-VL-2B-Instruct ⭐

**模型信息**：
- **模型名称**：onnx-community/Qwen2-VL-2B-Instruct
- **模型文件**：
- **解码器**：`decoder_model_merged_q4f16.onnx` (约869MB，推荐) ⭐
- **视觉编码器**：`vision_encoder_q4f16.onnx` (约1.33GB，推荐) ⭐
- **文本嵌入模型**：`embed_tokens_q4f16.onnx` (约467MB，必需) ⭐ 新增
- **总大小**：约2.67GB（模型文件）+ 约11.4MB（配置文件）
- **内存占用**：约3-4GB（运行时）
- **注意**：INT8量化版本使用ConvInteger操作符，ONNX Runtime Android不支持，因此必须使用Q4F16版本
- **优点**：社区维护、下载量高、2B适合移动端、Q4F16兼容性最好

**简要流程**：
1. 安装huggingface-cli工具（在CMD或PowerShell中执行 `pip install huggingface_hub`）
2. 下载模型文件（使用 `python -m huggingface_hub.cli.hf download` 命令）
3. 拷贝到手机（使用ADB或文件管理器）

**备选方案**：如果内存不足，可以使用 `pdufour/Qwen2-VL-2B-Instruct-ONNX-Q4-F16`（更小但精度可能略低）

---

## 📥 详细安装步骤

### 方案1：使用 onnx-community/Qwen2-VL-2B-Instruct

#### 步骤1：安装huggingface-cli

**在Windows 11上执行**：

1. **打开命令提示符（CMD）或PowerShell**：
   - 按 `Win + R`，输入 `cmd` 或 `powershell`，按回车
   - 或按 `Win + X`，选择"Windows PowerShell"或"终端"

2. **检查Python是否已安装**：
   ```powershell
   python --version
   # 或
   python3 --version
   ```
   - 如果显示版本号（如 Python 3.9.x），说明已安装
   - 如果提示"不是内部或外部命令"，需要先安装Python：
     - 下载：https://www.python.org/downloads/
     - 安装时勾选"Add Python to PATH"

3. **安装huggingface_hub**：
   ```powershell
   pip install huggingface_hub
   ```
   - 如果提示"pip不是内部或外部命令"，使用：`python -m pip install huggingface_hub`

4. **验证安装**：
   ```powershell
   python -c "import huggingface_hub; print('huggingface_hub已安装')"
   ```
   - 应该显示：`huggingface_hub已安装`

**关于 `huggingface-cli` 命令不可用的问题**：
- 新版本的 `huggingface_hub` (1.2.3+) 已将命令从 `huggingface-cli` 改为 `hf`
- 但 `hf` 命令可能没有正确安装到 PATH 中，这是**正常现象**
- **解决方案**：使用 `python -m huggingface_hub.cli.hf` 替代（见下面的手动下载方法）

#### 步骤2：手动下载指定文件 ⭐

**必需文件列表（6个文件）**：

**模型文件（3个，必需）**：
1. `onnx/decoder_model_merged_q4f16.onnx` (约869MB) - 解码器模型
2. `onnx/vision_encoder_q4f16.onnx` (约1.33GB) - 视觉编码器
3. `onnx/embed_tokens_q4f16.onnx` (约467MB) - 文本嵌入模型 ⭐ **新增，必需**

**配置文件（3个，必需）**：
4. `config.json` (约1.57KB) - 模型配置
5. `preprocessor_config.json` (约567B) - 预处理器配置
6. `tokenizer.json` (约11.4MB) - Tokenizer配置

**可选文件（不需要下载）**：
- `tokenizer_config.json` - Tokenizer额外配置（不需要，tokenizer.json已包含）
- `generation_config.json` - 生成配置（不需要）
- `vocab.json`, `merges.txt` - BPE词汇表（不需要，tokenizer.json已包含）
- `added_tokens.json`, `chat_template.json` - 其他配置（不需要）

**总计**：只需要下载这6个文件，总大小约2.67GB（不需要下载整个仓库的33.7GB）
1. `onnx/decoder_model_merged_q4f16.onnx` (约869MB) - **解码器模型（Q4F16版本，推荐）** ⭐
   - 注意：INT8版本不支持（ONNX Runtime Android不支持ConvInteger操作符）
2. `onnx/vision_encoder_q4f16.onnx` (约1.33GB) - **视觉编码器（Q4F16版本，推荐）** ⭐
   - 注意：INT8版本不支持
3. `onnx/embed_tokens_q4f16.onnx` (约467MB) - **文本嵌入模型（Q4F16版本，必需）** ⭐ **新增**
   - 注意：INT8版本不支持
4. `config.json` (约1.57KB) - 模型配置（必需）
5. `preprocessor_config.json` (约567B) - 预处理器配置（必需）
6. `tokenizer.json` (约11.4MB) - Tokenizer配置（必需）

**总大小**：约2.67GB（Q4F16版本）

**手动下载命令**：

```powershell
# 1. 创建下载目录
New-Item -ItemType Directory -Force -Path ./models/vl
cd ./models/vl

# 2. 创建onnx子目录
New-Item -ItemType Directory -Force -Path ./onnx

# 3. 下载必需文件（逐个执行，只下载这4个文件）

# 下载解码器模型（Q4F16版本，推荐）⭐
python -m huggingface_hub.cli.hf download onnx-community/Qwen2-VL-2B-Instruct onnx/decoder_model_merged_q4f16.onnx --local-dir .

# 下载视觉编码器（Q4F16版本，推荐）⭐
python -m huggingface_hub.cli.hf download onnx-community/Qwen2-VL-2B-Instruct onnx/vision_encoder_q4f16.onnx --local-dir .

# 下载文本嵌入模型（Q4F16版本，必需）⭐ 新增
python -m huggingface_hub.cli.hf download onnx-community/Qwen2-VL-2B-Instruct onnx/embed_tokens_q4f16.onnx --local-dir .

# 注意：INT8量化版本不支持（ONNX Runtime Android不支持ConvInteger操作符）
# 不要下载以下文件：
# - onnx/decoder_model_merged_int8.onnx
# - onnx/vision_encoder_int8.onnx
# - onnx/embed_tokens_int8.onnx

# 下载配置文件（必需）
python -m huggingface_hub.cli.hf download onnx-community/Qwen2-VL-2B-Instruct config.json --local-dir .
python -m huggingface_hub.cli.hf download onnx-community/Qwen2-VL-2B-Instruct preprocessor_config.json --local-dir .
python -m huggingface_hub.cli.hf download onnx-community/Qwen2-VL-2B-Instruct tokenizer.json --local-dir .
```

**下载时间**：
- **只下载必需文件**：约1.57GB（INT8版本）或约881MB（Q4F16版本）
- 可能需要5-15分钟（取决于网络速度）
- 如果中断，可以重新运行，会自动续传

**备选方法：使用Python脚本自动下载**（如果不想手动输入命令）

可以使用项目提供的 `download_model.py` 脚本（位于 `docs/tools/download_model.py`）：
```powershell
# 下载Q4F16版本（推荐，唯一支持的版本，包含3个模型文件）
python docs/tools/download_model.py

# 或明确指定Q4F16版本（与上面相同）
python docs/tools/download_model.py --q4f16
```

**注意**：脚本会自动下载所有6个必需文件（3个模型文件 + 3个配置文件），总大小约2.67GB。

**下载完成后，检查文件**：
```powershell
# 查看下载的文件
Get-ChildItem -Recurse | Select-Object Name, @{Name="Size(MB)";Expression={[math]::Round($_.Length/1MB, 2)}}

# 应该看到以下6个必需文件（Q4F16版本）：
# onnx/
#   ├── decoder_model_merged_q4f16.onnx (约869 MB) ← 解码器
#   ├── vision_encoder_q4f16.onnx (约1330 MB) ← 视觉编码器
#   └── embed_tokens_q4f16.onnx (约467 MB) ← 文本嵌入模型 ⭐ 新增
# config.json (约0.002 MB)
# preprocessor_config.json (约0.001 MB)
# tokenizer.json (约11.4 MB)
```

**重要说明**：
- **只需要这6个文件**，不需要下载整个仓库（约33.7GB）
- **解码器模型**：
  - 推荐：`decoder_model_merged_q4f16.onnx` (约869MB) - Q4F16量化，兼容性最好 ⭐
  - 不推荐：`decoder_model_merged_int8.onnx` (约1.55GB) - INT8量化，不支持（ConvInteger操作符）
- **视觉编码器**：
  - 推荐：`vision_encoder_q4f16.onnx` (约1.33GB) - Q4F16量化，兼容性最好 ⭐
  - 不推荐：`vision_encoder_int8.onnx` (约669MB) - INT8量化，不支持（ConvInteger操作符）
- **文本嵌入模型**：⭐ **新增，必需**
  - 推荐：`embed_tokens_q4f16.onnx` (约467MB) - Q4F16量化，兼容性最好 ⭐
  - 不推荐：`embed_tokens_int8.onnx` (约233MB) - INT8量化，不支持（ConvInteger操作符）
  - **注意**：这是新添加的必需模型，缺少此模型将导致文本嵌入无法正常工作

#### 步骤3：验证文件完整性

```powershell
# 检查模型文件（Q4F16版本，推荐）⭐
Get-Item onnx/decoder_model_merged_q4f16.onnx | Select-Object Name, @{Name="Size(MB)";Expression={[math]::Round($_.Length/1MB, 2)}}
# 应该显示约869 MB大小

Get-Item onnx/vision_encoder_q4f16.onnx | Select-Object Name, @{Name="Size(MB)";Expression={[math]::Round($_.Length/1MB, 2)}}
# 应该显示约1330 MB大小

Get-Item onnx/embed_tokens_q4f16.onnx | Select-Object Name, @{Name="Size(MB)";Expression={[math]::Round($_.Length/1MB, 2)}} ⭐ 新增
# 应该显示约467 MB大小

# 检查配置文件
Get-Item config.json, preprocessor_config.json, tokenizer.json | Select-Object Name, @{Name="Size(KB)";Expression={[math]::Round($_.Length/1KB, 2)}}
# config.json 约1.57 KB
# preprocessor_config.json 约0.57 KB
# tokenizer.json 约11400 KB (11.4 MB)
```

#### 步骤4：连接手机并验证ADB

```powershell
# 连接手机（USB或WiFi ADB）
# 验证连接
adb devices
# 应该看到你的设备，例如：
# List of devices attached
# ABC123XYZ    device
```

#### 步骤5：在手机上创建目录

```powershell
# 创建模型文件目录
adb shell mkdir -p /sdcard/Android/data/com.testwings/files/models/vl/

# 验证目录创建成功
adb shell ls /sdcard/Android/data/com.testwings/files/models/vl/
```

#### 步骤6：拷贝文件到手机

**在下载目录 `models/vl` 中执行**（例如：`E:\AutoTestDemo\models\vl`）：

```powershell
# 确保在下载目录中（应该包含 onnx/、config.json 等文件）
# 如果不在，切换到下载目录：
# cd E:\AutoTestDemo\models\vl

# 选择要使用的模型版本（根据内存情况选择）

# 使用Q4F16量化版本（推荐，兼容性最好）⭐
$DECODER_FILE = "onnx/decoder_model_merged_q4f16.onnx"
$VISION_ENCODER_FILE = "onnx/vision_encoder_q4f16.onnx"
$EMBED_TOKENS_FILE = "onnx/embed_tokens_q4f16.onnx"  # ⭐ 新增

# 注意：INT8量化版本不支持（ONNX Runtime Android不支持ConvInteger操作符）
# 不要使用以下文件：
# - onnx/decoder_model_merged_int8.onnx
# - onnx/vision_encoder_int8.onnx
# - onnx/embed_tokens_int8.onnx

# 1. 拷贝解码器模型文件（保持原始文件名，APP会自动识别）
adb push $DECODER_FILE /sdcard/Android/data/com.testwings/files/models/vl/

# 2. 拷贝视觉编码器文件（保持原始文件名，APP会自动识别）
adb push $VISION_ENCODER_FILE /sdcard/Android/data/com.testwings/files/models/vl/

# 3. 拷贝文本嵌入模型文件（保持原始文件名，APP会自动识别）⭐ 新增
adb push $EMBED_TOKENS_FILE /sdcard/Android/data/com.testwings/files/models/vl/

# 4. 拷贝配置文件（必需）
adb push config.json /sdcard/Android/data/com.testwings/files/models/vl/config.json
adb push preprocessor_config.json /sdcard/Android/data/com.testwings/files/models/vl/preprocessor_config.json

# 5. 拷贝tokenizer（必需，11.4MB）
adb push tokenizer.json /sdcard/Android/data/com.testwings/files/models/vl/tokenizer.json

# 拷贝过程可能需要几分钟（模型文件较大，1.55GB或869MB）
# 请耐心等待，不要中断
```

**或者使用绝对路径**（如果不在下载目录中）：

```powershell
# 使用绝对路径，假设下载目录是：E:\AutoTestDemo\models\vl
$DOWNLOAD_DIR = "E:\AutoTestDemo\models\vl"

# 选择模型版本
# 使用Q4F16量化版本（推荐，兼容性最好）⭐
$DECODER_FILE = "onnx\decoder_model_merged_q4f16.onnx"  # Windows路径使用反斜杠
$VISION_ENCODER_FILE = "onnx\vision_encoder_q4f16.onnx"
$EMBED_TOKENS_FILE = "onnx\embed_tokens_q4f16.onnx"  # ⭐ 新增

# 注意：INT8量化版本不支持（ONNX Runtime Android不支持ConvInteger操作符）

# 拷贝解码器模型文件（保持原始文件名，APP会自动识别）
adb push "$DOWNLOAD_DIR\$DECODER_FILE" /sdcard/Android/data/com.testwings/files/models/vl/

# 拷贝视觉编码器文件（保持原始文件名，APP会自动识别）
adb push "$DOWNLOAD_DIR\$VISION_ENCODER_FILE" /sdcard/Android/data/com.testwings/files/models/vl/

# 拷贝文本嵌入模型文件（保持原始文件名，APP会自动识别）⭐ 新增
adb push "$DOWNLOAD_DIR\$EMBED_TOKENS_FILE" /sdcard/Android/data/com.testwings/files/models/vl/

adb push "$DOWNLOAD_DIR\config.json" /sdcard/Android/data/com.testwings/files/models/vl/config.json
adb push "$DOWNLOAD_DIR\preprocessor_config.json" /sdcard/Android/data/com.testwings/files/models/vl/preprocessor_config.json
adb push "$DOWNLOAD_DIR\tokenizer.json" /sdcard/Android/data/com.testwings/files/models/vl/tokenizer.json
```

#### 步骤7：验证文件已成功拷贝

```powershell
# 检查手机上的文件
adb shell ls -lh /sdcard/Android/data/com.testwings/files/models/vl/

# 应该看到（使用Q4F16版本，推荐）：
# -rw-rw---- 1 u0_a123 u0_a123  869M ... decoder_model_merged_q4f16.onnx  ⭐
# -rw-rw---- 1 u0_a123 u0_a123 1.33G ... vision_encoder_q4f16.onnx  ⭐
# -rw-rw---- 1 u0_a123 u0_a123  467M ... embed_tokens_q4f16.onnx  ⭐ 新增
# -rw-rw---- 1 u0_a123 u0_a123  1.57K ... config.json
# -rw-rw---- 1 u0_a123 u0_a123   567B ... preprocessor_config.json
# -rw-rw---- 1 u0_a123 u0_a123  11.4M ... tokenizer.json

# 验证文件大小是否正确
adb shell du -sh /sdcard/Android/data/com.testwings/files/models/vl/*
```

---

## 🔍 验证和测试

### 步骤8：视觉编码器测试流程

#### 8.1 准备工作

**1. 启动 Logcat 监控（推荐使用 Android Studio Logcat 或命令行）**

**方法1：使用 Android Studio Logcat（推荐）**
- 打开 Android Studio
- 连接设备
- 在 Logcat 中设置过滤：`tag:VisionLanguageManager`
- 或使用包名过滤：`package:com.testwings`

**方法2：使用命令行（PowerShell）**
```powershell
# 清除旧日志
adb logcat -c

# 实时监控 VisionLanguageManager 日志
adb logcat | Select-String -Pattern "VisionLanguageManager" -CaseSensitive:$false

# 或同时监控错误日志
adb logcat | Select-String -Pattern "VisionLanguageManager|ERROR|FATAL" -CaseSensitive:$false
```

**2. 启动 APP**

**方法1：直接启动（如果APP已安装）**
```powershell
# 启动APP
adb shell am start -n com.testwings/.MainActivity

# 或先停止再启动（如果需要重启）
adb shell am force-stop com.testwings
adb shell am start -n com.testwings/.MainActivity
```

**方法2：重新编译并安装（如果代码有更新）**
```powershell
# 1. 切换到项目根目录（Android子项目）
cd e:\AutoTestDemo\TestWings\android

# 2. 编译APK
.\gradlew assembleDebug

# 3. 安装APK（使用相对路径）
adb install -r app\build\outputs\apk\debug\app-debug.apk

# 4. 启动APP
adb shell am start -n com.testwings/.MainActivity
```

**方法3：使用Android Studio**
- 在 Android Studio 中点击 "Run" 按钮
- 或使用快捷键 `Shift+F10`（Windows/Linux）或 `Ctrl+R`（Mac）

#### 8.2 测试流程和关键日志监控点

**阶段1：模型文件检查（预期耗时：< 1秒）**

**关键日志：**
```
✅ 找到解码器模型: decoder_model_merged_q4f16.onnx (829MB)
✅ 找到视觉编码器模型: vision_encoder_q4f16.onnx (1270MB)
✅ 解码器模型文件存在: /storage/emulated/0/Android/data/com.testwings/files/models/vl/decoder_model_merged_q4f16.onnx
✅ 视觉编码器模型文件存在: /storage/emulated/0/Android/data/com.testwings/files/models/vl/vision_encoder_q4f16.onnx
配置文件存在: /storage/emulated/0/Android/data/com.testwings/files/models/vl/config.json
```

**判断标准：**
- ✅ **通过**：看到所有 ✅ 标记的日志
- ❌ **失败**：看到 ❌ 标记或文件不存在的错误

---

**阶段2：模型加载（预期耗时：20-30秒）**

**关键日志序列：**

```
开始加载VL模型
  解码器: /storage/emulated/0/Android/data/com.testwings/files/models/vl/decoder_model_merged_q4f16.onnx
  视觉编码器: /storage/emulated/0/Android/data/com.testwings/files/models/vl/vision_encoder_q4f16.onnx
ONNX Runtime环境初始化成功
准备加载视觉编码器模型，大小: 1270MB
```

**视觉编码器加载（约20秒）：**
```
视觉编码器会话创建成功
========== 视觉编码器模型结构 ==========
输入节点数量: 2
输入[0]: pixel_values
  - 形状: [-1, -1]
  - 数据类型: FLOAT
输入[1]: grid_thw
  - 形状: [-1, 3]
  - 数据类型: INT64
输出节点数量: 1
输出[0]: image_features
  - 形状: [-1, 1536]
  - 数据类型: FLOAT
========================================
```

**解码器加载（约13秒）：**
```
准备加载解码器模型，大小: 829MB
解码器会话创建成功
========== 解码器模型结构 ==========
输入节点数量: 59
输出节点数量: 57
...
====================================
Tokenizer加载待实现
VL模型加载成功（解码器 + 视觉编码器）
```

**判断标准：**
- ✅ **通过**：看到 `VL模型加载成功（解码器 + 视觉编码器）`
- ❌ **失败**：看到 `VL模型加载失败` 或异常堆栈

---

**阶段3：图像预处理（预期耗时：< 50ms）**

**关键日志：**
```
========== 开始VL模型推理 ==========
图片尺寸: 960x960
✅ 图像预处理完成，尺寸: 960x960，耗时: XXms
```

**判断标准：**
- ✅ **通过**：看到 `✅ 图像预处理完成` 且耗时 < 100ms
- ❌ **失败**：预处理耗时过长或出现错误

---

**阶段4：输入数据准备（预期耗时：< 1秒）**

**关键日志：**
```
开始视觉编码器推理...
图像尺寸: 960x960, grid_thw: [1, 69, 69], patches数量: 4761
patch数据大小: 588 (channels=3, temporal=1, patchSize=14)
pixel_values形状: [4761, 588]
========== 准备执行视觉编码器推理 ==========
输入张量数量: 2
  输入[pixel_values]: 形状=[4761, 588]
  输入[grid_thw]: 形状=[1, 3]
⚠️ 注意：视觉编码器推理可能需要30秒到几分钟，请耐心等待...
```

**判断标准：**
- ✅ **通过**：看到所有输入张量信息，形状正确
- ❌ **失败**：输入张量数量不对或形状错误

---

**阶段5：视觉编码器推理（关键阶段，预期耗时：30秒-5分钟）**

**这是最关键的阶段，推理可能耗时较长：**

**关键日志：**
```
========== 准备执行视觉编码器推理 ==========
输入张量数量: 2
  输入[pixel_values]: 形状=[4761, 588]
  输入[grid_thw]: 形状=[1, 3]
⚠️ 注意：视觉编码器推理可能需要30秒到几分钟，请耐心等待...
```

**推理完成后应该看到：**
```
✅ 视觉编码器推理完成，耗时: XXXXXms (XX.X秒)
视觉编码器输出形状: [4761, 1536]
视觉编码器输出类型: FLOAT
✅ 输出形状验证通过（包含1536维特征向量）
输出维度详情: batch_size=4761, feature_dim=1536
```

**判断标准：**
- ✅ **通过**：看到 `✅ 视觉编码器推理完成` 且输出形状正确 `[4761, 1536]`
- ⚠️ **警告**：推理耗时超过5分钟（可能设备性能不足）
- ❌ **失败**：看到 `❌ 视觉编码器推理执行失败` 或 `OutOfMemoryError`

---

**阶段6：推理流程完成（预期耗时：< 100ms）**

**关键日志：**
```
✅ 视觉编码器推理完成，嵌入向量形状: [4761, 1536]
========== VL模型推理完成 ==========
总耗时: XXXXXms (XX.X秒)
视觉编码器输出形状: [4761, 1536]
```

**判断标准：**
- ✅ **通过**：看到 `========== VL模型推理完成 ==========`
- ❌ **失败**：看到 `❌ VL模型推理失败`

---

#### 8.3 如何确认测试结束

**测试成功结束的标志：**

1. **看到完整的推理流程日志：**
   ```
   ✅ 视觉编码器推理完成，耗时: XXXXXms
   ✅ 视觉编码器推理完成，嵌入向量形状: [4761, 1536]
   ========== VL模型推理完成 ==========
   ```

2. **没有错误日志：**
   - 没有 `❌` 标记
   - 没有 `OutOfMemoryError`
   - 没有异常堆栈

3. **输出形状正确：**
   - 输出形状包含 `1536`（特征维度）
   - 输出形状包含 `4761`（patches数量）

**测试失败或卡住的标志：**

1. **进程被终止：**
   - 日志突然停止
   - 看到新的进程启动日志（`PROCESS STARTED`）
   - 之前的进程ID消失

2. **内存不足：**
   - 看到 `OutOfMemoryError`
   - 看到 `❌ 视觉编码器推理内存不足`

3. **推理超时：**
   - 在 `准备执行视觉编码器推理...` 后超过5分钟没有新日志
   - 进程可能被系统杀死（ANR）

---

#### 8.4 监控关键日志的命令

**实时监控（推荐）：**

```powershell
# 方法1：只监控 VisionLanguageManager 日志
adb logcat -c
adb logcat | Select-String -Pattern "VisionLanguageManager" -CaseSensitive:$false

# 方法2：监控关键步骤（使用正则表达式）
adb logcat | Select-String -Pattern "开始|完成|失败|耗时|❌|✅|==========" -CaseSensitive:$false

# 方法3：同时监控错误日志
adb logcat | Select-String -Pattern "VisionLanguageManager|ERROR|FATAL|OutOfMemory" -CaseSensitive:$false
```

**保存日志到文件（便于分析）：**

```powershell
# 保存完整日志
adb logcat > vision_encoder_test.log

# 或只保存 VisionLanguageManager 日志
adb logcat | Select-String -Pattern "VisionLanguageManager" -CaseSensitive:$false | Tee-Object -FilePath vision_encoder_test.log
```

**查看特定阶段的日志：**

```powershell
# 查看模型加载阶段
adb logcat -d | Select-String -Pattern "模型加载|会话创建|模型结构" -CaseSensitive:$false

# 查看推理阶段
adb logcat -d | Select-String -Pattern "推理|inference|run" -CaseSensitive:$false

# 查看错误日志
adb logcat -d | Select-String -Pattern "ERROR|FATAL|Exception|Error" -CaseSensitive:$false
```

---

#### 8.5 测试时间线参考

**正常测试时间线（12GB内存设备）：**

| 阶段 | 预期耗时 | 关键日志 |
|------|---------|---------|
| 模型文件检查 | < 1秒 | `✅ 找到解码器模型` |
| 视觉编码器加载 | 20-30秒 | `视觉编码器会话创建成功` |
| 解码器加载 | 13-20秒 | `解码器会话创建成功` |
| 图像预处理 | < 50ms | `✅ 图像预处理完成` |
| 输入数据准备 | < 1秒 | `pixel_values形状: [4761, 588]` |
| **视觉编码器推理** | **30秒-3分钟** | `✅ 视觉编码器推理完成` |
| 推理流程完成 | < 100ms | `========== VL模型推理完成 ==========` |
| **总耗时** | **约1-5分钟** | - |

**如果推理超过5分钟：**
- 可能是设备性能不足
- 可能是内存不足导致系统降频
- 建议检查设备内存和CPU使用情况

---

#### 8.6 常见问题诊断

**问题1：推理卡在 `准备执行视觉编码器推理...`**

**可能原因：**
1. 推理需要很长时间（正常，可能需要1-5分钟）
2. 内存不足导致进程被杀死
3. 设备性能不足

**诊断方法：**
```powershell
# 检查是否有错误日志
adb logcat -d | Select-String -Pattern "ERROR|FATAL|OutOfMemory|ANR" -CaseSensitive:$false

# 检查进程是否还在运行
adb shell ps | Select-String -Pattern "com.testwings" -CaseSensitive:$false

# 检查内存使用情况
adb shell dumpsys meminfo com.testwings
```

**解决方案：**
- 等待更长时间（最多5分钟）
- 如果超过5分钟，可能是内存不足，检查设备内存
- 如果进程被杀死，查看是否有 OOM 错误

---

**问题2：看不到新添加的日志**

**可能原因：**
- 代码未重新编译部署

**解决方法：**
```powershell
# 1. 确认代码已保存
# 2. 重新编译
./gradlew assembleDebug

# 3. 重新安装
adb install -r app/build/outputs/apk/debug/app-debug.apk

# 4. 重新运行测试
```

---

**问题3：进程被系统杀死**

**检查方法：**
```powershell
# 查看是否有 OOM 错误
adb logcat -d | Select-String -Pattern "OutOfMemory|lowmemorykiller|oom" -CaseSensitive:$false -CaseSensitive:$false

# 查看内存使用情况
adb shell dumpsys meminfo com.testwings | Select-String -Pattern "TOTAL|Native Heap|Dalvik Heap"
```

**解决方案：**
- 关闭其他应用，释放内存
- 使用更小的模型（如果可用）
- 降低图像分辨率（修改预处理代码）

---

#### 8.7 测试成功标准

**完整的测试成功应该包含以下所有日志：**

```
✅ 找到解码器模型: decoder_model_merged_q4f16.onnx (829MB)
✅ 找到视觉编码器模型: vision_encoder_q4f16.onnx (1270MB)
✅ 解码器模型文件存在: ...
✅ 视觉编码器模型文件存在: ...
配置文件存在: ...
开始加载VL模型
ONNX Runtime环境初始化成功
视觉编码器会话创建成功
========== 视觉编码器模型结构 ==========
解码器会话创建成功
========== 解码器模型结构 ==========
VL模型加载成功（解码器 + 视觉编码器）
========== 开始VL模型推理 ==========
✅ 图像预处理完成，尺寸: 960x960，耗时: XXms
开始视觉编码器推理...
pixel_values形状: [4761, 588]
========== 准备执行视觉编码器推理 ==========
输入张量数量: 2
  输入[pixel_values]: 形状=[4761, 588]
  输入[grid_thw]: 形状=[1, 3]
⚠️ 注意：视觉编码器推理可能需要30秒到几分钟，请耐心等待...
✅ 视觉编码器推理完成，耗时: XXXXXms (XX.X秒)
视觉编码器输出形状: [4761, 1536]
✅ 输出形状验证通过（包含1536维特征向量）
✅ 视觉编码器推理完成，嵌入向量形状: [4761, 1536]
========== VL模型推理完成 ==========
总耗时: XXXXXms (XX.X秒)
```

**如果看到以上所有日志，说明测试成功！** ✅

---

#### 8.8 快速参考：关键日志检查清单

**使用此清单快速检查测试进度：**

| 阶段 | 关键日志 | 预期时间 | 状态检查 |
|------|---------|---------|---------|
| **1. 文件检查** | `✅ 找到解码器模型`<br>`✅ 找到视觉编码器模型` | < 1秒 | ✅ 必须看到 |
| **2. 环境初始化** | `ONNX Runtime环境初始化成功` | < 1秒 | ✅ 必须看到 |
| **3. 视觉编码器加载** | `视觉编码器会话创建成功`<br>`========== 视觉编码器模型结构 ==========` | 20-30秒 | ✅ 必须看到 |
| **4. 解码器加载** | `解码器会话创建成功`<br>`========== 解码器模型结构 ==========` | 13-20秒 | ✅ 必须看到 |
| **5. 模型加载完成** | `VL模型加载成功（解码器 + 视觉编码器）` | - | ✅ 必须看到 |
| **6. 推理开始** | `========== 开始VL模型推理 ==========` | - | ✅ 必须看到 |
| **7. 图像预处理** | `✅ 图像预处理完成，尺寸: 960x960，耗时: XXms` | < 50ms | ✅ 必须看到 |
| **8. 输入准备** | `pixel_values形状: [4761, 588]`<br>`========== 准备执行视觉编码器推理 ==========` | < 1秒 | ✅ 必须看到 |
| **9. 推理执行** | `⚠️ 注意：视觉编码器推理可能需要30秒到几分钟` | **30秒-5分钟** | ⏳ **等待中** |
| **10. 推理完成** | `✅ 视觉编码器推理完成，耗时: XXXXXms`<br>`视觉编码器输出形状: [4761, 1536]` | - | ✅ 必须看到 |
| **11. 流程完成** | `========== VL模型推理完成 ==========` | - | ✅ 必须看到 |

**测试结束判断：**
- ✅ **成功**：看到阶段10和11的日志
- ❌ **失败**：看到 `❌` 标记或异常
- ⏳ **进行中**：卡在阶段9，等待中（最多等待5分钟）

---

#### 8.9 一键测试脚本

**创建测试脚本（可选）：**

```powershell
# test_vision_encoder.ps1
# 视觉编码器测试脚本

Write-Host "========== 开始视觉编码器测试 ==========" -ForegroundColor Green

# 1. 清除旧日志
Write-Host "清除旧日志..." -ForegroundColor Yellow
adb logcat -c

# 2. 启动APP
Write-Host "启动APP..." -ForegroundColor Yellow
adb shell am start -n com.testwings/.MainActivity
Start-Sleep -Seconds 2

# 3. 监控日志（30秒）
Write-Host "监控日志30秒..." -ForegroundColor Yellow
$logFile = "vision_encoder_test_$(Get-Date -Format 'yyyyMMdd_HHmmss').log"
adb logcat | Select-String -Pattern "VisionLanguageManager" -CaseSensitive:$false | Tee-Object -FilePath $logFile

# 4. 检查关键日志
Write-Host "`n检查测试结果..." -ForegroundColor Yellow
$content = Get-Content $logFile -Raw

if ($content -match "VL模型加载成功") {
    Write-Host "✅ 模型加载成功" -ForegroundColor Green
} else {
    Write-Host "❌ 模型加载失败" -ForegroundColor Red
}

if ($content -match "视觉编码器推理完成") {
    Write-Host "✅ 视觉编码器推理完成" -ForegroundColor Green
} else {
    Write-Host "⚠️ 视觉编码器推理未完成（可能还在进行中）" -ForegroundColor Yellow
}

if ($content -match "VL模型推理完成") {
    Write-Host "✅ 测试成功！" -ForegroundColor Green
} else {
    Write-Host "⚠️ 测试可能还在进行中或失败，请查看日志文件: $logFile" -ForegroundColor Yellow
}

Write-Host "`n日志文件已保存: $logFile" -ForegroundColor Cyan
```

**使用方法：**
```powershell
# 保存为 test_vision_encoder.ps1
# 运行测试
.\test_vision_encoder.ps1
```

### 验证清单

安装完成后，确认以下项目：

- [ ] 模型文件已下载（Q4F16版本，必需）：
  - decoder_model_merged_q4f16.onnx: 约869MB
  - vision_encoder_q4f16.onnx: 约1.33GB
  - embed_tokens_q4f16.onnx: 约467MB ⭐ 新增，必需
- [ ] 配置文件已下载（必需）：
  - config.json: 约1.57KB
  - preprocessor_config.json: 约567B
  - tokenizer.json: 约11.4MB
- [ ] 文件已成功拷贝到手机
- [ ] 文件大小正确（使用 `adb shell ls -lh` 检查）：
  - decoder_model_merged_q4f16.onnx: 约869MB
  - vision_encoder_q4f16.onnx: 约1.33GB
  - embed_tokens_q4f16.onnx: 约467MB ⭐ 新增
  - config.json: 约1.57KB
  - preprocessor_config.json: 约567B
  - tokenizer.json: 约11.4MB
- [ ] APP可以检测到所有模型文件（查看日志）

---

## 🔄 备用方案

### 方案2：如果内存不足，使用 pdufour/Qwen2-VL-2B-Instruct-ONNX-Q4-F16

**适用场景**：设备内存较小（8GB以下）

**操作步骤**（与方案1类似，使用Python命令下载指定文件）：

```powershell
# 1. 创建下载目录
New-Item -ItemType Directory -Force -Path ./models/vl-q4
cd ./models/vl-q4
New-Item -ItemType Directory -Force -Path ./onnx

# 2. 下载必需文件（根据实际文件结构调整文件名）
python -m huggingface_hub.cli.hf download pdufour/Qwen2-VL-2B-Instruct-ONNX-Q4-F16 [实际模型文件名] --local-dir .
python -m huggingface_hub.cli.hf download pdufour/Qwen2-VL-2B-Instruct-ONNX-Q4-F16 config.json --local-dir .
python -m huggingface_hub.cli.hf download pdufour/Qwen2-VL-2B-Instruct-ONNX-Q4-F16 preprocessor_config.json --local-dir .
python -m huggingface_hub.cli.hf download pdufour/Qwen2-VL-2B-Instruct-ONNX-Q4-F16 tokenizer.json --local-dir .

# 3. 查看文件结构
Get-ChildItem -Recurse | Select-Object Name, @{Name="Size(MB)";Expression={[math]::Round($_.Length/1MB, 2)}}

# 4. 在下载目录中拷贝到手机（保持原始文件名，APP会自动识别）
adb push [实际模型文件名] /sdcard/Android/data/com.testwings/files/models/vl/
adb push config.json /sdcard/Android/data/com.testwings/files/models/vl/config.json
adb push preprocessor_config.json /sdcard/Android/data/com.testwings/files/models/vl/preprocessor_config.json
adb push tokenizer.json /sdcard/Android/data/com.testwings/files/models/vl/tokenizer.json
```

---

## ⚠️ 常见问题

### 问题1：下载速度慢

**解决方案**：
```powershell
# 使用镜像站点（如果可用）
$env:HF_ENDPOINT = "https://hf-mirror.com"
python -m huggingface_hub.cli.hf download onnx-community/Qwen2-VL-2B-Instruct onnx/decoder_model_merged_q4f16.onnx --local-dir .
python -m huggingface_hub.cli.hf download onnx-community/Qwen2-VL-2B-Instruct onnx/vision_encoder_q4f16.onnx --local-dir .
```

### 问题2：ADB连接失败

**检查项**：
1. USB调试是否已启用
2. USB驱动是否已安装
3. 尝试使用WiFi ADB：
   ```powershell
   adb tcpip 5555
   adb connect [手机IP]:5555
   ```

### 问题3：文件拷贝失败（空间不足）

**解决方案**：
1. 检查手机存储空间：`adb shell df -h /sdcard`
2. 清理手机存储空间
3. 确保至少有5GB可用空间

### 问题4：模型文件找不到

**检查**：
```powershell
# 检查文件是否存在
adb shell ls -lh /sdcard/Android/data/com.testwings/files/models/vl/

# 检查文件权限
adb shell ls -la /sdcard/Android/data/com.testwings/files/models/vl/
```

---

## 📚 相关资源

- 模型页面：https://huggingface.co/onnx-community/Qwen2-VL-2B-Instruct
- Qwen-VL官方文档：https://github.com/QwenLM/Qwen-VL
- ONNX Runtime文档：https://onnxruntime.ai/
- 模型量化指南：https://onnxruntime.ai/docs/performance/quantization.html
- Hugging Face CLI文档：https://huggingface.co/docs/huggingface_hub/guides/download
- ADB文档：https://developer.android.com/tools/adb

---

## 🎯 下一步

模型文件安装完成后：
1. 运行APP，测试VL模型功能
2. 查看日志，确认模型加载成功
3. 如果遇到问题，检查日志中的错误信息
