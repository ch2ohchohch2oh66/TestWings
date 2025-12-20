# Android 14+ MediaProjection 限制与解决方案

## 一、问题分析

### 1.1 Android 14+ 的严格限制

Android 14 (API 34) 及更高版本对 MediaProjection API 实施了严格的安全限制：

1. **每次捕获都需要用户授权**
   - 每次调用 `MediaProjection#createVirtualDisplay` 都需要新的权限授权
   - 不能重用权限结果（resultCode + Intent data）
   - 错误信息：`Don't re-use the resultData to retrieve the same projection instance`

2. **不能重用 MediaProjection 实例**
   - 每个 MediaProjection 实例只能用于一次捕获会话
   - 不能在同一实例上多次调用 `createVirtualDisplay`

3. **系统安全限制，无法绕过**
   - 这是 Android 系统层面的安全机制
   - 旨在保护用户隐私，防止恶意应用长期监控屏幕
   - **无法通过技术手段绕过**

### 1.2 对 TestWings 自动化测试的影响

**核心问题**：TestWings 需要连续捕获屏幕进行自动化测试，但 Android 14+ 要求每次捕获都需要用户手动授权。

**影响分析**：
- ❌ **无法实现完全自动化**：每次捕获都需要用户手动点击授权对话框
- ❌ **测试流程中断**：自动化测试无法连续执行
- ❌ **用户体验差**：需要频繁手动操作
- ❌ **不适合 CI/CD**：无法在无人值守环境下运行

---

## 二、解决方案评估

### 2.1 方案对比

| 方案 | 可行性 | 优点 | 缺点 | 推荐度 |
|------|--------|------|------|--------|
| **1. 接受限制，手动授权** | ⚠️ 部分可行 | - 无需修改代码<br>- 兼容所有 Android 版本 | - 无法完全自动化<br>- 需要人工干预 | ⭐⭐ |
| **2. 使用 AccessibilityService** | ✅ 完全可行 | - 可以自动化捕获<br>- 一次授权，持续使用<br>- 无每次授权限制 | - 需要用户授权无障碍服务<br>- 需要声明 AccessibilityService<br>- 可能被应用商店审查 | ⭐⭐⭐⭐⭐ |
| **3. 混合方案** | ✅ 可行 | - Android 13- 使用 MediaProjection<br>- Android 14+ 使用 AccessibilityService | - 需要维护两套代码<br>- 增加复杂度 | ⭐⭐⭐⭐ |
| **4. 降低捕获频率** | ⚠️ 部分可行 | - 减少授权次数<br>- 简化实现 | - 可能影响测试准确性<br>- 仍然需要手动授权 | ⭐⭐ |

### 2.2 推荐方案：使用 AccessibilityService

#### 2.2.1 为什么推荐 AccessibilityService

1. **自动化友好**
   - 一次授权，持续使用
   - 不需要每次捕获都授权
   - 支持完全自动化测试

2. **符合 TestWings 的设计理念**
   - TestWings 文档中提到：AccessibilityService 是"最优方式"
   - 文档原文："这是普通应用唯一可行的自动化操作方案，且用户只需一次授权"

3. **技术成熟**
   - AccessibilityService 提供了 `takeScreenshot()` 方法
   - 可以替代 MediaProjection 进行屏幕捕获
   - Android 官方支持的功能

#### 2.2.2 AccessibilityService 的限制

1. **需要用户手动启用**
   - 用户需要在系统设置中手动启用无障碍服务
   - 无法通过代码自动启用（Android 11+）

2. **应用商店审查**
   - 需要说明为什么使用无障碍服务
   - 需要确保服务确实是为了辅助用户，而不是滥用

3. **用户体验**
   - 首次使用需要引导用户启用无障碍服务
   - 比 MediaProjection 的授权流程稍复杂

---

## 三、实施建议

### 3.1 短期方案（兼容现有设计）

**采用混合方案**：
- **Android 13 及以下**：继续使用 MediaProjection（当前实现）
- **Android 14+**：使用 AccessibilityService 的 `takeScreenshot()` 方法

**优势**：
- 兼容所有 Android 版本
- Android 13- 设备保持当前体验
- Android 14+ 设备支持自动化测试

### 3.2 长期方案（推荐）

**全面采用 AccessibilityService**：
- 统一使用 AccessibilityService 进行屏幕捕获
- 移除非必需的 MediaProjection 代码
- 简化代码维护

**优势**：
- 所有 Android 版本都支持自动化
- 代码更简洁，维护成本更低
- 用户体验更一致

### 3.3 代码修改建议

1. **创建 ScreenCaptureAccessibilityService**
   ```kotlin
   class ScreenCaptureAccessibilityService : AccessibilityService() {
       override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
       override fun onInterrupt() {}
       
       fun takeScreenshot(): Bitmap? {
           return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
               takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, 
                   object : AccessibilityService.TakeScreenshotCallback {
                       override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                           // 处理截图
                       }
                       override fun onFailure(errorCode: Int) {
                           // 处理错误
                       }
                   })
               null
           } else {
               null
           }
       }
   }
   ```

2. **修改 MainActivity**
   - 检测 Android 版本
   - Android 14+ 使用 AccessibilityService
   - Android 13- 使用 MediaProjection（或统一使用 AccessibilityService）

3. **用户引导**
   - 首次使用时引导用户启用无障碍服务
   - 提供清晰的说明和操作指引

---

## 四、结论

### 4.1 当前 MediaProjection 方案的限制

**在 Android 14+ 上，当前的 MediaProjection 方案无法支持完全自动化测试。**

原因：
- 每次捕获都需要用户手动授权
- 无法绕过系统安全限制
- 不适合连续捕获的场景

### 4.2 推荐方案

**使用 AccessibilityService 进行屏幕捕获**

理由：
1. ✅ 支持完全自动化
2. ✅ 一次授权，持续使用
3. ✅ 符合 TestWings 的原始设计理念
4. ✅ 技术成熟可靠

### 4.3 实施优先级

1. **高优先级**：实现 AccessibilityService 截图功能
2. **中优先级**：添加用户引导和权限说明
3. **低优先级**：考虑是否完全移除 MediaProjection 代码

---

## 五、参考资源

- [Android AccessibilityService 官方文档](https://developer.android.com/guide/topics/ui/accessibility/service)
- [Android 14 MediaProjection 变更说明](https://developer.android.com/about/versions/14/behavior-changes-14)
- [AccessibilityService.takeScreenshot() API](https://developer.android.com/reference/android/accessibilityservice/AccessibilityService#takeScreenshot(int,%20java.util.concurrent.Executor,%20android.accessibilityservice.AccessibilityService.TakeScreenshotCallback))
