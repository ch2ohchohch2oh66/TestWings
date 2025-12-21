# API Vision-Language服务配置示例

本文档说明如何配置和使用API方式的Vision-Language服务。

## 概述

TestWings支持通过API方式调用云端或局域网的VL模型服务，作为本地部署的替代方案。这对于快速验证DEMO或无法在设备上部署大型模型的场景非常有用。

## 配置方式

### 1. 在代码中配置

```kotlin
// 在MainActivity或配置类中
val apiConfig = ApiVisionLanguageServiceConfig(
    endpoint = "https://your-api-server.com/v1/vision/understand",
    authType = AuthType.BEARER_TOKEN,
    bearerToken = "your-api-token-here",
    timeoutMs = 30000, // 30秒
    maxRetries = 3,
    retryDelayMs = 1000,
    imageQuality = 0.8f,
    maxImageSize = 1920
)

// 配置到统一管理器
visionLanguageServiceManager?.configureApiService(apiConfig)

// 设置部署类型为API方式
visionLanguageServiceManager?.setDeploymentType(
    VisionLanguageServiceManager.DeploymentType.API_CLOUD
)
```

### 2. 支持的认证方式

#### API Key认证
```kotlin
val apiConfig = ApiVisionLanguageServiceConfig(
    endpoint = "https://api.example.com/v1/vision",
    authType = AuthType.API_KEY,
    apiKey = "your-api-key-here"
)
```

#### Bearer Token认证
```kotlin
val apiConfig = ApiVisionLanguageServiceConfig(
    endpoint = "https://api.example.com/v1/vision",
    authType = AuthType.BEARER_TOKEN,
    bearerToken = "your-bearer-token-here"
)
```

#### 无认证
```kotlin
val apiConfig = ApiVisionLanguageServiceConfig(
    endpoint = "http://localhost:8080/v1/vision",
    authType = AuthType.NONE
)
```

### 3. 自动降级模式

使用`AUTO`模式，系统会自动选择最佳服务：

```kotlin
visionLanguageServiceManager = VisionLanguageServiceManager(
    context = this,
    deploymentType = VisionLanguageServiceManager.DeploymentType.AUTO
)

// 初始化时会自动选择：
// 1. 如果本地模型可用，优先使用本地
// 2. 如果本地不可用但API已配置，降级到API
// 3. 如果都不可用，返回空结果
```

## API请求格式

### 请求体

API服务会发送以下格式的JSON请求：

```json
{
  "image": "base64-encoded-image-data"
}
```

### 响应格式

API服务期望以下格式的JSON响应：

#### 标准格式（推荐）
```json
{
  "elements": [
    {
      "type": "BUTTON",
      "text": "登录",
      "bounds": {
        "left": 100,
        "top": 200,
        "right": 250,
        "bottom": 250
      },
      "center": {
        "x": 175,
        "y": 225
      },
      "confidence": 0.95,
      "semantic_description": "登录按钮，位于屏幕中央"
    }
  ],
  "semantic_description": "这是一个登录页面，包含用户名输入框、密码输入框和登录按钮"
}
```

#### 简化格式（也支持）
```json
{
  "ui_elements": [
    {
      "type": "BUTTON",
      "text": "登录",
      "x": 100,
      "y": 200,
      "width": 150,
      "height": 50,
      "confidence": 0.95
    }
  ],
  "description": "登录页面"
}
```

#### 嵌套格式（也支持）
```json
{
  "data": {
    "elements": [...],
    "semantic_description": "..."
  }
}
```

### UI元素类型

支持的元素类型：
- `BUTTON` - 按钮
- `INPUT` - 输入框
- `TEXT` - 文本
- `IMAGE` - 图片/图标
- `OTHER` - 其他元素

## 使用示例

### 完整示例

```kotlin
class MainActivity : ComponentActivity() {
    private var visionLanguageServiceManager: VisionLanguageServiceManager? = null
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // 创建统一管理器
        visionLanguageServiceManager = VisionLanguageServiceManager(
            context = this,
            deploymentType = VisionLanguageServiceManager.DeploymentType.AUTO
        )
        
        // 配置API服务（可选）
        val apiConfig = ApiVisionLanguageServiceConfig(
            endpoint = "https://your-api-server.com/v1/vision/understand",
            authType = AuthType.BEARER_TOKEN,
            bearerToken = "your-token-here"
        )
        visionLanguageServiceManager?.configureApiService(apiConfig)
        
        // 初始化服务
        lifecycleScope.launch {
            visionLanguageServiceManager?.initialize()
        }
    }
    
    // 使用服务识别屏幕
    private suspend fun recognizeScreen(bitmap: Bitmap) {
        visionLanguageServiceManager?.let { manager ->
            val screenState = manager.understand(bitmap)
            if (screenState.vlAvailable) {
                Log.d("MainActivity", "识别成功: ${screenState.elements.size}个元素")
                Log.d("MainActivity", "当前服务类型: ${manager.getCurrentServiceType()}")
            }
        }
    }
}
```

## 常见问题

### 1. 如何切换部署方式？

```kotlin
// 切换到本地部署
manager.setDeploymentType(VisionLanguageServiceManager.DeploymentType.LOCAL)

// 切换到API方式
manager.setDeploymentType(VisionLanguageServiceManager.DeploymentType.API_CLOUD)

// 切换到自动模式
manager.setDeploymentType(VisionLanguageServiceManager.DeploymentType.AUTO)
```

### 2. 如何检查当前使用的服务类型？

```kotlin
val serviceType = manager.getCurrentServiceType()
// 返回: "LOCAL", "API", 或 "NONE"
```

### 3. API请求失败怎么办？

系统会自动重试（默认3次），如果所有重试都失败，会自动降级到其他可用服务（如果使用AUTO模式）。

### 4. 如何自定义请求头？

```kotlin
val apiConfig = ApiVisionLanguageServiceConfig(
    endpoint = "https://api.example.com/v1/vision",
    authType = AuthType.API_KEY,
    apiKey = "your-key",
    customHeaders = mapOf(
        "X-Custom-Header" to "custom-value",
        "X-Client-Version" to "1.0.0"
    )
)
```

## 注意事项

1. **图像压缩**：系统会自动压缩图像以减少传输时间，默认质量0.8，最大尺寸1920px
2. **超时设置**：根据网络情况调整`timeoutMs`，默认30秒
3. **重试机制**：默认重试3次，每次间隔1秒，可根据需要调整
4. **安全性**：API密钥和Token应妥善保管，不要硬编码在代码中

## 下一步

- 完善API响应解析（根据实际API格式适配）
- 添加更多API服务提供商的支持
- 实现API服务的健康检查和监控
