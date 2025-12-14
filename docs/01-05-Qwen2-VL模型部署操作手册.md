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
├── decoder_model_merged_q4f16.onnx     # 解码器模型（Q4F16量化，约829MB，推荐）⭐
│   # 或 decoder_model_merged.onnx      # 解码器模型（未量化版本，不推荐）
│   # 注意：decoder_model_merged_int8.onnx 不支持（ONNX Runtime Android不支持ConvInteger操作符）
├── vision_encoder_q4f16.onnx           # 视觉编码器（Q4F16量化，约1.27GB，推荐）⭐
│   # 或 vision_encoder.onnx            # 视觉编码器（未量化版本，不推荐）
│   # 注意：vision_encoder_int8.onnx 不支持（ONNX Runtime Android不支持ConvInteger操作符）
├── config.json                          # 模型配置文件（约1.57KB，必需）
├── preprocessor_config.json             # 预处理器配置（约567B，必需）
└── tokenizer.json                       # Tokenizer配置文件（约11.4MB，必需）
```

**注意**：APP会自动识别任何 `.onnx` 文件，按优先级使用（Q4F16 > 未量化 > 其他）。**INT8量化版本不支持**（ONNX Runtime Android不支持ConvInteger操作符）

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

**2. 视觉编码器（Vision Encoder）** ⭐ **新增**
- **格式**：ONNX格式（`.onnx` 后缀）
- **文件名**：保持原始文件名，APP会自动识别
  - `vision_encoder_q4f16.onnx`：Q4F16量化版本，约1.27GB（推荐）⭐
  - `vision_encoder.onnx`：未量化版本（不推荐，文件更大）
  - `vision_encoder_int8.onnx`：INT8量化版本（不支持，ONNX Runtime Android不支持ConvInteger操作符）
- **作用**：将图像转换为嵌入向量（image embeddings）
- **必需**：是（用于图像理解）
- **来源**：Hugging Face - https://huggingface.co/onnx-community/Qwen2-VL-2B-Instruct

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
- **解码器**：`decoder_model_merged_q4f16.onnx` (约829MB，推荐) ⭐
- **视觉编码器**：`vision_encoder_q4f16.onnx` (约1.27GB，推荐) ⭐
- **总大小**：约2.1GB
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

**需要下载的5个必需文件**（只下载这些，不需要全量）：
1. `onnx/decoder_model_merged_int8.onnx` (约1.55GB) - **解码器模型（INT8版本，推荐）**
   - 或 `onnx/decoder_model_merged_q4f16.onnx` (约869MB) - **Q4F16版本（内存不足时）**
2. `onnx/vision_encoder_int8.onnx` (约669MB) - **视觉编码器（INT8版本，推荐）** ⭐ **新增**
   - 或 `onnx/vision_encoder_q4f16.onnx` (约1.33GB) - **Q4F16版本**
3. `config.json` (约1.57KB) - 模型配置
4. `preprocessor_config.json` (约567B) - 预处理器配置
5. `tokenizer.json` (约11.4MB) - Tokenizer配置

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

# 下载视觉编码器（Q4F16版本，推荐）⭐ 新增
python -m huggingface_hub.cli.hf download onnx-community/Qwen2-VL-2B-Instruct onnx/vision_encoder_q4f16.onnx --local-dir .

# 注意：INT8量化版本不支持（ONNX Runtime Android不支持ConvInteger操作符）
# 不要下载以下文件：
# - onnx/decoder_model_merged_int8.onnx
# - onnx/vision_encoder_int8.onnx

# 下载配置文件
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
# 下载INT8版本（推荐，1.55GB）
python docs/tools/download_model.py

# 或下载Q4F16版本（内存不足时，869MB）
python docs/tools/download_model.py --q4f16
```

**下载完成后，检查文件**：
```powershell
# 查看下载的文件
Get-ChildItem -Recurse | Select-Object Name, @{Name="Size(MB)";Expression={[math]::Round($_.Length/1MB, 2)}}

# 应该看到以下4个必需文件：
# onnx/
#   └── decoder_model_merged_int8.onnx (约1550 MB) ← INT8版本，推荐
#   # 或 decoder_model_merged_q4f16.onnx (约869 MB) ← Q4F16版本，内存不足时
# config.json (约0.002 MB)
# preprocessor_config.json (约0.001 MB)
# tokenizer.json (约11.4 MB)
```

**重要说明**：
- **只需要这5个文件**，不需要下载整个仓库（约6-7GB）
- **解码器模型**：
  - 推荐：`decoder_model_merged_q4f16.onnx` (约829MB) - Q4F16量化，兼容性最好 ⭐
  - 不推荐：`decoder_model_merged_int8.onnx` (约1.55GB) - INT8量化，不支持（ConvInteger操作符）
- **视觉编码器**：⭐ **新增**
  - 推荐：`vision_encoder_q4f16.onnx` (约1.27GB) - Q4F16量化，兼容性最好 ⭐
  - 不推荐：`vision_encoder_int8.onnx` (约669MB) - INT8量化，不支持（ConvInteger操作符）

#### 步骤3：验证文件完整性

```powershell
# 检查量化模型文件（推荐使用INT8版本）
Get-Item onnx/decoder_model_merged_int8.onnx | Select-Object Name, @{Name="Size(MB)";Expression={[math]::Round($_.Length/1MB, 2)}}
# 应该显示约1550 MB大小

# 或者如果使用Q4F16版本（内存不足时）
# Get-Item onnx/decoder_model_merged_q4f16.onnx | Select-Object Name, @{Name="Size(MB)";Expression={[math]::Round($_.Length/1MB, 2)}}
# 应该显示约869 MB大小

# 验证视觉编码器文件 ⭐ 新增
Get-Item onnx/vision_encoder_int8.onnx | Select-Object Name, @{Name="Size(MB)";Expression={[math]::Round($_.Length/1MB, 2)}}
# 应该显示约669 MB大小

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

# 注意：INT8量化版本不支持（ONNX Runtime Android不支持ConvInteger操作符）
# 不要使用以下文件：
# - onnx/decoder_model_merged_int8.onnx
# - onnx/vision_encoder_int8.onnx

# 1. 拷贝解码器模型文件（保持原始文件名，APP会自动识别）
adb push $DECODER_FILE /sdcard/Android/data/com.testwings/files/models/vl/

# 2. 拷贝视觉编码器文件（保持原始文件名，APP会自动识别）⭐ 新增
adb push $VISION_ENCODER_FILE /sdcard/Android/data/com.testwings/files/models/vl/

# 2. 拷贝配置文件（必需）
adb push config.json /sdcard/Android/data/com.testwings/files/models/vl/config.json
adb push preprocessor_config.json /sdcard/Android/data/com.testwings/files/models/vl/preprocessor_config.json

# 3. 拷贝tokenizer（必需，11.4MB）
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
$VISION_ENCODER_FILE = "onnx\vision_encoder_q4f16.onnx"  # ⭐ 新增

# 注意：INT8量化版本不支持（ONNX Runtime Android不支持ConvInteger操作符）

# 拷贝解码器模型文件（保持原始文件名，APP会自动识别）
adb push "$DOWNLOAD_DIR\$DECODER_FILE" /sdcard/Android/data/com.testwings/files/models/vl/

# 拷贝视觉编码器文件（保持原始文件名，APP会自动识别）⭐ 新增
adb push "$DOWNLOAD_DIR\$VISION_ENCODER_FILE" /sdcard/Android/data/com.testwings/files/models/vl/
adb push "$DOWNLOAD_DIR\config.json" /sdcard/Android/data/com.testwings/files/models/vl/config.json
adb push "$DOWNLOAD_DIR\preprocessor_config.json" /sdcard/Android/data/com.testwings/files/models/vl/preprocessor_config.json
adb push "$DOWNLOAD_DIR\tokenizer.json" /sdcard/Android/data/com.testwings/files/models/vl/tokenizer.json
```

#### 步骤7：验证文件已成功拷贝

```powershell
# 检查手机上的文件
adb shell ls -lh /sdcard/Android/data/com.testwings/files/models/vl/

# 应该看到（使用Q4F16版本，推荐）：
# -rw-rw---- 1 u0_a123 u0_a123  829M ... decoder_model_merged_q4f16.onnx  ⭐
# -rw-rw---- 1 u0_a123 u0_a123 1.27G ... vision_encoder_q4f16.onnx  ⭐
# -rw-rw---- 1 u0_a123 u0_a123  1.57K ... config.json
# -rw-rw---- 1 u0_a123 u0_a123   567B ... preprocessor_config.json
# -rw-rw---- 1 u0_a123 u0_a123  11.4M ... tokenizer.json

# 验证文件大小是否正确
adb shell du -sh /sdcard/Android/data/com.testwings/files/models/vl/*
```

---

## 🔍 验证和测试

### 步骤8：测试APP

```powershell
# 运行APP
adb shell am start -n com.testwings/.MainActivity

# 查看日志，确认模型加载（PowerShell使用Select-String替代grep）
adb logcat | Select-String -Pattern "VisionLanguageManager|ModelDownloader" -CaseSensitive:$false

# 应该看到类似：
# VisionLanguageManager: ✅ 解码器模型文件存在: /sdcard/Android/data/com.testwings/files/models/vl/decoder_model_merged_q4f16.onnx
# VisionLanguageManager: ✅ 视觉编码器模型文件存在: /sdcard/Android/data/com.testwings/files/models/vl/vision_encoder_q4f16.onnx
# VisionLanguageManager: 配置文件存在: /sdcard/Android/data/com.testwings/files/models/vl/config.json
# MainActivity: VL模型文件已就绪，可以加载
```

### 验证清单

安装完成后，确认以下项目：

- [ ] 模型文件已下载（INT8版本约1.55GB，或Q4F16版本约869MB）
- [ ] 配置文件已下载（config.json, preprocessor_config.json）
- [ ] Tokenizer文件已下载（tokenizer.json, 11.4MB）
- [ ] 文件已成功拷贝到手机
- [ ] 文件大小正确（使用 `adb shell ls -lh` 检查）：
  - decoder_model_merged_int8.onnx: 1.55GB (INT8，推荐) 或 decoder_model_merged_q4f16.onnx: 869MB (Q4F16，内存不足时)
  - vision_encoder_int8.onnx: 669MB (INT8，推荐) 或 vision_encoder_q4f16.onnx: 1.33GB (Q4F16) ⭐ 新增
  - config.json: 约1.57KB
  - preprocessor_config.json: 约567B
  - tokenizer.json: 约11.4MB
- [ ] APP可以检测到模型文件（查看日志）

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
