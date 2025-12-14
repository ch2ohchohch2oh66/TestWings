# Android 开发方案

## 一、开发策略

### 1.1 平台选择

**采用 Android 原生开发：**
- 使用 Android SDK 进行开发
- 目标 API Level: Android 10 (API 29) 或更高
- 兼容 HarmonyOS 4.2.0（通过 Android 兼容层）
- 后续可考虑 HarmonyOS 原生支持（类似 iOS）

### 1.2 核心 API

**Android 标准 API：**
- `MediaProjection`：用于屏幕捕获
- `AccessibilityService`：用于 UI 访问和操作执行
- `UIAutomator2`：用于自动化操作（可选）
- `WindowManager`：用于悬浮窗显示

### 1.3 开发工具

- **IDE**：Android Studio（最新版本）
- **语言**：Kotlin（推荐）或 Java
- **构建工具**：Gradle
- **调试工具**：ADB（Android Debug Bridge）

---

## 二、TestWings APP 部署方式

### 2.1 产品形态

**TestWings 以 Android APP 形式安装在手机上。**

#### 安装包格式
- **格式**：APK (Android Package)
- **安装方式**：
  - 通过 ADB 安装：`adb install testwings.apk`
  - 通过文件管理器直接安装
  - 通过应用商店分发（如果上架）

### 2.2 APP 架构设计

```
TestWings APP
├── 主界面（测试用例管理、执行控制）
├── 后台服务（AccessibilityService）
│   ├── 屏幕监控服务
│   ├── 操作执行服务
│   └── 结果记录服务
├── AI 引擎模块（完全本地部署）
│   ├── OCR 识别模块（PaddleOCR Mobile）
│   ├── UI 元素检测模块（YOLOv8 Nano）
│   ├── 用例理解模块（本地 LLM）
│   ├── 屏幕语义理解模块（本地 Vision-Language Model）
│   └── 操作规划模块（本地 LLM）
└── 数据存储
    ├── 测试用例数据库（SQLite）
    ├── 执行结果存储
    └── 截图缓存
```

### 2.3 权限要求

**Android 所需权限：**

```xml
<!-- AndroidManifest.xml -->
<uses-permission android:name="android.permission.SYSTEM_ALERT_WINDOW" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.WRITE_EXTERNAL_STORAGE" />
<uses-permission android:name="android.permission.READ_EXTERNAL_STORAGE" />
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />

<!-- 屏幕捕获权限 -->
<uses-permission android:name="android.permission.CAPTURE_VIDEO_OUTPUT" />

<!-- Accessibility Service -->
<service
    android:name=".TestWingsAccessibilityService"
    android:permission="android.permission.BIND_ACCESSIBILITY_SERVICE">
    <intent-filter>
        <action android:name="android.accessibilityservice.AccessibilityService" />
    </intent-filter>
    <meta-data
        android:name="android.accessibilityservice"
        android:resource="@xml/accessibility_service_config" />
</service>
```

**用户需要手动授权的权限：**
1. **无障碍服务权限**：用户需要在系统设置中手动开启
2. **悬浮窗权限**：用于显示测试执行状态
3. **存储权限**：用于保存测试结果和截图

---

## 三、硬件配置要求

### 3.1 最低配置要求

**完全本地部署所需配置：**
- **内存**：8GB RAM（最低），12GB+ 推荐
- **存储**：64GB+（用于存储模型文件和测试数据）
- **处理器**：支持 NPU 的芯片（如麒麟 990/9000、骁龙 8 Gen 2+）推荐

### 3.2 推荐配置

**您的设备（华为 Mate30 Pro）：**
- ✅ **内存**：8GB RAM（满足最低要求）
- ✅ **存储**：128GB+（充足）
- ✅ **处理器**：麒麟 990（支持 NPU 加速）
- ⚠️ **建议**：可以运行，但建议使用 12GB+ 内存设备以获得更好性能

---

## 四、代码结构设计

### 4.1 项目结构

```
app/
├── src/
│   ├── main/
│   │   ├── java/com/testwings/
│   │   │   ├── ui/                    # UI 界面
│   │   │   │   ├── MainActivity.kt
│   │   │   │   ├── TestCaseListActivity.kt
│   │   │   │   └── ExecutionActivity.kt
│   │   │   ├── service/               # 后台服务
│   │   │   │   ├── TestWingsAccessibilityService.kt
│   │   │   │   └── ScreenCaptureService.kt
│   │   │   ├── ai/                    # AI 引擎
│   │   │   │   ├── ocr/
│   │   │   │   │   └── OCRManager.kt
│   │   │   │   ├── vision/
│   │   │   │   │   └── UIDetector.kt
│   │   │   │   ├── llm/
│   │   │   │   │   ├── LLMManager.kt
│   │   │   │   │   ├── TestCaseUnderstanding.kt
│   │   │   │   │   └── OperationPlanner.kt
│   │   │   │   └── vision_language/
│   │   │   │       └── ScreenUnderstanding.kt
│   │   │   ├── device/                # 设备控制
│   │   │   │   ├── ScreenCapture.kt
│   │   │   │   ├── ElementLocator.kt
│   │   │   │   └── DeviceController.kt
│   │   │   ├── executor/              # 测试执行
│   │   │   │   ├── TestExecutor.kt
│   │   │   │   └── StepExecutor.kt
│   │   │   ├── database/              # 数据存储
│   │   │   │   ├── TestCaseDatabase.kt
│   │   │   │   └── ResultDatabase.kt
│   │   │   └── utils/                 # 工具类
│   │   │       ├── ModelManager.kt
│   │   │       └── NPUAccelerator.kt
│   │   ├── assets/
│   │   │   └── models/                # AI 模型文件
│   │   │       ├── ocr/
│   │   │       ├── ui_detector/
│   │   │       └── llm/
│   │   └── res/
│   └── test/                          # 测试代码
└── build.gradle
```

### 4.2 核心模块说明

#### UI 层
- **MainActivity**：主界面，测试用例管理
- **TestCaseListActivity**：测试用例列表
- **ExecutionActivity**：测试执行界面

#### 服务层
- **TestWingsAccessibilityService**：无障碍服务，用于 UI 访问和操作
- **ScreenCaptureService**：屏幕捕获服务

#### AI 引擎层（完全本地）
- **OCRManager**：OCR 文字识别
- **UIDetector**：UI 元素检测
- **LLMManager**：本地大语言模型管理
- **TestCaseUnderstanding**：用例理解
- **ScreenUnderstanding**：屏幕语义理解
- **OperationPlanner**：操作规划

#### 设备控制层
- **ScreenCapture**：屏幕捕获
- **ElementLocator**：元素定位
- **DeviceController**：设备操作执行

#### 执行层
- **TestExecutor**：测试执行引擎
- **StepExecutor**：步骤执行器

---

## 五、开发环境搭建

### 5.1 开发工具安装

1. **Android Studio**
   - 下载：https://developer.android.com/studio
   - 安装 Android SDK（API Level 29+）
   - 配置 Kotlin 插件

2. **ADB 工具**
   - 用于连接设备调试
   - 测试命令：`adb devices`

### 5.2 项目初始化

```bash
# 创建 Android 项目
# 在 Android Studio 中创建新项目
# 选择 Kotlin 语言
# 最低 SDK：API 29 (Android 10)
```

### 5.3 依赖配置

```kotlin
// build.gradle (app)
dependencies {
    // Kotlin
    implementation 'org.jetbrains.kotlin:kotlin-stdlib:1.9.0'
    
    // Coroutines
    implementation 'org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3'
    
    // Room Database
    implementation 'androidx.room:room-runtime:2.6.0'
    kapt 'androidx.room:room-compiler:2.6.0'
    
    // TensorFlow Lite (用于模型推理)
    implementation 'org.tensorflow:tensorflow-lite:2.14.0'
    implementation 'org.tensorflow:tensorflow-lite-gpu:2.14.0'
    
    // ONNX Runtime (用于 LLM 推理)
    implementation 'com.microsoft.onnxruntime:onnxruntime-android:1.16.0'
    
    // 其他依赖...
}
```

---

## 六、HarmonyOS 兼容性说明

### 6.1 兼容性

**HarmonyOS 4.2.0 兼容 Android 应用：**
- HarmonyOS 4.2.0 通过兼容层支持 Android 应用运行
- 使用 Android SDK 开发的应用可以直接在 HarmonyOS 上运行
- 无需特殊适配即可在 HarmonyOS 设备上使用

### 6.2 后续支持计划

**HarmonyOS 原生支持（类似 iOS）：**
- 当前阶段：使用 Android SDK 开发，兼容 HarmonyOS
- 后续计划：考虑开发 HarmonyOS 原生版本（使用 DevEco Studio）
- 时间节点：待核心功能稳定后

---

## 七、实施建议

### 7.1 开发阶段

1. **第一阶段：基础功能**
   - 屏幕捕获功能
   - OCR 文字识别
   - 基础操作执行（点击、输入）

2. **第二阶段：AI 集成**
   - 本地 LLM 部署
   - 用例理解功能
   - 屏幕语义理解

3. **第三阶段：完善功能**
   - 操作规划优化
   - 结果验证增强
   - 报告生成完善

### 7.2 测试策略

1. **单元测试**：核心功能模块测试
2. **集成测试**：模块间协作测试
3. **真机测试**：在华为 Mate30 Pro 上测试
4. **兼容性测试**：在不同 Android 版本上测试

---

## 八、总结

### 开发策略：
✅ **使用 Android SDK 开发**，兼容 HarmonyOS 4.2.0
✅ **完全本地部署 AI 模型**，降低测试环境复杂度
✅ **后续可考虑 HarmonyOS 原生支持**（类似 iOS）

### 硬件要求：
✅ **8GB 内存**：满足最低要求，可以运行
✅ **12GB+ 内存**：推荐配置，性能更佳
✅ **支持 NPU**：可以加速模型推理

### 优势：
- 简化开发流程（只需 Android 开发）
- 完全本地部署，保护数据隐私
- 离线可用，不依赖网络
- 降低测试环境复杂度

