package com.testwings.testcase

import android.content.Context
import android.graphics.Point
import android.util.Log
import com.testwings.utils.DeviceController
import com.testwings.utils.OcrResult
import com.testwings.utils.ScreenCapture
import com.testwings.utils.ScreenState
import com.testwings.utils.SemanticMatcher
import kotlinx.coroutines.delay

/**
 * 测试执行引擎
 */
class TestExecutor(
    private val context: Context,
    private val screenCapture: ScreenCapture? = null
) {
    
    companion object {
        private const val TAG = "TestExecutor"
    }
    
    /**
     * 测试执行结果
     */
    data class ExecutionResult(
        /**
         * 是否执行成功
         */
        val success: Boolean,
        
        /**
         * 执行的步骤结果列表
         */
        val stepResults: List<StepResult>,
        
        /**
         * 总耗时（毫秒）
         */
        val totalDuration: Long,
        
        /**
         * 错误信息（如果失败）
         */
        val error: String? = null
    )
    
    /**
     * 步骤执行结果
     */
    data class StepResult(
        /**
         * 步骤序号
         */
        val stepNumber: Int,
        
        /**
         * 步骤描述
         */
        val description: String,
        
        /**
         * 是否执行成功
         */
        val success: Boolean,
        
        /**
         * 执行耗时（毫秒）
         */
        val duration: Long,
        
        /**
         * 验证结果（如果有验证）
         */
        val verificationResult: VerificationResult? = null,
        
        /**
         * 错误信息（如果失败）
         */
        val error: String? = null,
        
        /**
         * 截图路径（如果有截图）
         */
        val screenshotPath: String? = null
    )
    
    /**
     * 验证结果
     */
    data class VerificationResult(
        /**
         * 是否验证通过
         */
        val passed: Boolean,
        
        /**
         * 验证信息
         */
        val message: String
    )
    
    /**
     * 执行测试用例
     * @param testCase 测试用例
     * @param onStepComplete 步骤完成回调（可选，用于UI更新）
     * @param getScreenState 获取当前屏幕状态的函数（优先使用VL模型，用于元素定位和验证）
     * @param getOcrResult 获取当前OCR结果的函数（降级方案，用于元素定位和验证）
     * @param triggerScreenshotAndWaitForOcr 触发截图和OCR识别的函数（用于截图操作），返回最新的OCR结果
     * @return 执行结果
     */
    suspend fun execute(
        testCase: TestCase,
        onStepComplete: ((StepResult) -> Unit)? = null,
        getScreenState: (() -> ScreenState?)? = null,
        getOcrResult: (() -> OcrResult?)? = null,
        triggerScreenshotAndWaitForOcr: (suspend () -> OcrResult?)? = null
    ): ExecutionResult {
        Log.d(TAG, "开始执行测试用例: ${testCase.name}")
        val startTime = System.currentTimeMillis()
        val stepResults = mutableListOf<StepResult>()
        
        // 保存当前步骤的OCR结果（用于验证）
        var currentStepOcrResult: OcrResult? = null
        
        try {
            for (step in testCase.steps) {
                Log.d(TAG, "执行步骤 ${step.stepNumber}: ${step.description}")
                val stepStartTime = System.currentTimeMillis()
                
                // 执行操作（如果是截图操作，executeAction 已经处理了截图和OCR/VL识别，并返回了OCR结果）
                val actionResult = executeAction(step.action, getScreenState, getOcrResult, triggerScreenshotAndWaitForOcr)
                
                // 如果是截图操作，需要等待VL识别完成后再进行验证
                // triggerScreenshotAndWaitForOcr 已经等待了VL识别完成（最多70秒）
                if (step.action is Action.Screenshot && step.verification != null) {
                    // 截图操作且有验证步骤，确保VL识别已完成
                    // triggerScreenshotAndWaitForOcr 已经等待了VL识别完成，这里做短暂确认等待（最多5秒）
                    var vlReadyRetry = 0
                    val maxVlReadyRetries = 10 // 最多再等待5秒（作为保险，确保VL结果已更新）
                    
                    // 等待VL识别完成：screenState不为null且vlAvailable=true
                    while (vlReadyRetry < maxVlReadyRetries) {
                        val screenState = getScreenState?.invoke()
                        if (screenState != null && screenState.vlAvailable) {
                            // VL识别已完成
                            Log.d(TAG, "VL识别已完成: vlAvailable=true, elements=${screenState.elements.size}")
                            break
                        }
                        delay(500)
                        vlReadyRetry++
                    }
                    
                    val finalScreenState = getScreenState?.invoke()
                    if (finalScreenState == null || !finalScreenState.vlAvailable) {
                        Log.w(TAG, "VL识别未完成或失败，继续执行验证（将使用OCR降级方案）")
                    } else if (finalScreenState.elements.isEmpty()) {
                        Log.w(TAG, "VL识别完成但elements为空，使用OCR降级方案进行验证")
                    }
                }
                
                // 获取屏幕状态和OCR结果用于验证
                // 如果是截图操作，executeAction 已经返回了OCR结果
                val currentScreenState = getScreenState?.invoke()
                currentStepOcrResult = if (step.action is Action.Screenshot) {
                    // 截图操作，使用 executeAction 返回的OCR结果
                    actionResult.ocrResult
                } else {
                    // 非截图操作，使用当前的OCR结果
                    getOcrResult?.invoke()
                }
                
                // 等待操作后延迟
                if (step.waitAfter > 0) {
                    delay(step.waitAfter)
                }
                
                // 执行验证（如果有）
                // 注意：传入 getScreenState 函数引用以获取最新状态，而不是快照值
                // OCR结果使用快照值即可，因为已经通过 executeAction 返回
                val verificationResult = step.verification?.let { verification ->
                    verify(
                        verification,
                        getScreenState = getScreenState,  // 传入函数引用，获取最新状态
                        getOcrResult = { currentStepOcrResult }  // OCR结果使用快照（已通过executeAction返回）
                    )
                }
                
                // 如果验证失败且是必需的，标记步骤为失败
                val stepSuccess = actionResult.success && 
                    (verificationResult == null || verificationResult.passed || !step.verification!!.required)
                
                val stepDuration = System.currentTimeMillis() - stepStartTime
                
                val stepResult = StepResult(
                    stepNumber = step.stepNumber,
                    description = step.description,
                    success = stepSuccess,
                    duration = stepDuration,
                    verificationResult = verificationResult,
                    error = if (!actionResult.success) actionResult.error else null,
                    screenshotPath = if (step.action is Action.Screenshot) {
                        // 截图操作，保存截图
                        screenCapture?.let { capture ->
                            // 这里需要获取当前屏幕的bitmap，暂时返回null
                            // 实际实现中，可以从MainActivity获取最新的截图
                            null
                        }
                    } else null
                )
                
                stepResults.add(stepResult)
                onStepComplete?.invoke(stepResult)
                
                // 如果步骤失败且验证是必需的，停止执行
                if (!stepSuccess && step.verification?.required == true) {
                    Log.w(TAG, "步骤 ${step.stepNumber} 验证失败，停止执行")
                    break
                }
            }
            
            val totalDuration = System.currentTimeMillis() - startTime
            val allStepsPassed = stepResults.all { it.success }
            
            Log.d(TAG, "测试用例执行完成: ${testCase.name}, 成功: $allStepsPassed, 耗时: ${totalDuration}ms")
            
            return ExecutionResult(
                success = allStepsPassed,
                stepResults = stepResults,
                totalDuration = totalDuration
            )
        } catch (e: Exception) {
            Log.e(TAG, "执行测试用例失败", e)
            val totalDuration = System.currentTimeMillis() - startTime
            return ExecutionResult(
                success = false,
                stepResults = stepResults,
                totalDuration = totalDuration,
                error = "执行失败: ${e.message}"
            )
        }
    }
    
    /**
     * 操作执行结果（包含OCR结果，用于截图操作）
     */
    private data class ActionResultWithOcr(
        val success: Boolean,
        val error: String? = null,
        val ocrResult: OcrResult? = null  // 截图操作返回的OCR结果
    )
    
    /**
     * 执行操作
     */
    private suspend fun executeAction(
        action: Action,
        getScreenState: (() -> ScreenState?)? = null,
        getOcrResult: (() -> OcrResult?)? = null,
        triggerScreenshotAndWaitForOcr: (suspend () -> OcrResult?)? = null
    ): ActionResultWithOcr {
        return when (action) {
            is Action.Click -> {
                val result = executeClick(action, getScreenState, getOcrResult)
                ActionResultWithOcr(success = result.success, error = result.error)
            }
            is Action.Input -> {
                val result = executeInput(action, getScreenState, getOcrResult)
                ActionResultWithOcr(success = result.success, error = result.error)
            }
            is Action.Swipe -> {
                val result = executeSwipe(action)
                ActionResultWithOcr(success = result.success, error = result.error)
            }
            is Action.Key -> {
                val result = executeKey(action)
                ActionResultWithOcr(success = result.success, error = result.error)
            }
            is Action.Wait -> {
                val result = executeWait(action)
                ActionResultWithOcr(success = result.success, error = result.error)
            }
            is Action.Screenshot -> {
                // 执行截图操作，触发实际的截图和OCR/VL识别
                // 注意：triggerScreenshotAndWaitForOcr 会等待OCR和VL识别完成（最多60秒）
                if (triggerScreenshotAndWaitForOcr != null) {
                    Log.d(TAG, "执行截图操作，触发截图、OCR和VL识别...")
                    try {
                        val ocrResult = triggerScreenshotAndWaitForOcr()
                        if (ocrResult != null) {
                            Log.d(TAG, "截图和OCR识别成功，VL识别结果可通过 getScreenState() 获取")
                            ActionResultWithOcr(success = true, ocrResult = ocrResult)
                        } else {
                            Log.w(TAG, "截图成功但OCR识别失败或超时，VL识别结果可通过 getScreenState() 获取")
                            ActionResultWithOcr(success = true) // 截图操作本身成功，OCR失败不影响
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "截图操作失败", e)
                        ActionResultWithOcr(success = false, error = "截图操作失败: ${e.message}")
                    }
                } else {
                    Log.w(TAG, "截图操作未配置 triggerScreenshotAndWaitForOcr，无法执行实际截图")
                    ActionResultWithOcr(success = false, error = "截图功能未配置")
                }
            }
        }
    }
    
    /**
     * 执行点击操作
     */
    private suspend fun executeClick(
        action: Action.Click,
        getScreenState: (() -> ScreenState?)? = null,
        getOcrResult: (() -> OcrResult?)? = null
    ): ActionResult {
        val screenState = getScreenState?.invoke()
        val ocrResult = getOcrResult?.invoke()
        // 使用异步定位，优先使用VL模型，降级到OCR
        val locateResult = ElementLocator.locateAsync(action.locateBy, action.value, screenState, ocrResult, useSemanticMatch = true)
        
        return if (locateResult.success && locateResult.point != null) {
            val success = DeviceController.click(locateResult.point.x, locateResult.point.y)
            if (success) {
                Log.d(TAG, "点击操作成功: (${locateResult.point.x}, ${locateResult.point.y})")
                ActionResult(success = true)
            } else {
                Log.e(TAG, "点击操作失败")
                ActionResult(success = false, error = "点击操作执行失败")
            }
        } else {
            Log.e(TAG, "定位失败: ${locateResult.error}")
            ActionResult(success = false, error = locateResult.error)
        }
    }
    
    /**
     * 执行输入操作
     */
    private suspend fun executeInput(
        action: Action.Input,
        getScreenState: (() -> ScreenState?)? = null,
        getOcrResult: (() -> OcrResult?)? = null
    ): ActionResult {
        return when (action.locateBy) {
            LocateBy.RESOURCE_ID -> {
                val success = DeviceController.inputTextByResourceId(action.locateValue, action.text)
                if (success) {
                    Log.d(TAG, "输入操作成功: ${action.text}")
                    ActionResult(success = true)
                } else {
                    Log.e(TAG, "输入操作失败")
                    ActionResult(success = false, error = "输入操作执行失败")
                }
            }
            else -> {
                // 对于TEXT和COORDINATE定位，先定位到输入框，然后点击，再输入
                val screenState = getScreenState?.invoke()
                val ocrResult = getOcrResult?.invoke()
                // 使用异步定位，优先使用VL模型，降级到OCR
                val locateResult = ElementLocator.locateAsync(action.locateBy, action.locateValue, screenState, ocrResult, useSemanticMatch = true)
                
                if (locateResult.success && locateResult.point != null) {
                    // 先点击输入框
                    val clickSuccess = DeviceController.click(locateResult.point.x, locateResult.point.y)
                    if (!clickSuccess) {
                        return ActionResult(success = false, error = "点击输入框失败")
                    }
                    
                    // 等待一下，确保输入框获得焦点
                    delay(300)
                    
                    // 使用Accessibility Service输入文本
                    // 注意：这里需要找到当前焦点的输入框，暂时使用坐标点击后的输入
                    // 实际实现中，可以结合Accessibility Service来输入
                    val inputSuccess = DeviceController.inputTextByLabel(action.locateValue, action.text)
                    if (inputSuccess) {
                        Log.d(TAG, "输入操作成功: ${action.text}")
                        ActionResult(success = true)
                    } else {
                        Log.e(TAG, "输入操作失败")
                        ActionResult(success = false, error = "输入文本失败")
                    }
                } else {
                    ActionResult(success = false, error = locateResult.error)
                }
            }
        }
    }
    
    /**
     * 执行滑动操作
     */
    private fun executeSwipe(action: Action.Swipe): ActionResult {
        val success = when (action.direction) {
            SwipeDirection.UP -> DeviceController.swipeUp(action.distance)
            SwipeDirection.DOWN -> DeviceController.swipeDown(action.distance)
            SwipeDirection.LEFT -> DeviceController.swipeLeft(action.distance)
            SwipeDirection.RIGHT -> DeviceController.swipeRight(action.distance)
        }
        
        return if (success) {
            Log.d(TAG, "滑动操作成功: ${action.direction}, 距离: ${action.distance}")
            ActionResult(success = true)
        } else {
            Log.e(TAG, "滑动操作失败")
            ActionResult(success = false, error = "滑动操作执行失败")
        }
    }
    
    /**
     * 执行系统按键操作
     */
    private fun executeKey(action: Action.Key): ActionResult {
        val success = when (action.keyType) {
            KeyType.BACK -> DeviceController.pressBack()
            KeyType.HOME -> DeviceController.pressHome()
            KeyType.RECENT -> DeviceController.pressRecentApps()
        }
        
        return if (success) {
            Log.d(TAG, "按键操作成功: ${action.keyType}")
            ActionResult(success = true)
        } else {
            Log.e(TAG, "按键操作失败")
            ActionResult(success = false, error = "按键操作执行失败")
        }
    }
    
    /**
     * 执行等待操作
     */
    private suspend fun executeWait(action: Action.Wait): ActionResult {
        delay(action.duration)
        Log.d(TAG, "等待操作完成: ${action.duration}ms")
        return ActionResult(success = true)
    }
    
    /**
     * 验证操作结果
     */
    private suspend fun verify(
        verification: Verification,
        getScreenState: (() -> ScreenState?)? = null,
        getOcrResult: (() -> OcrResult?)? = null
    ): VerificationResult {
        return when (verification.type) {
            VerificationType.TEXT_EXISTS -> {
                verifyTextExists(verification.value, getScreenState, getOcrResult)
            }
            VerificationType.TEXT_NOT_EXISTS -> {
                verifyTextNotExists(verification.value, getScreenState, getOcrResult)
            }
            VerificationType.ELEMENT_EXISTS -> {
                verifyElementExists(verification.value)
            }
            VerificationType.ELEMENT_NOT_EXISTS -> {
                verifyElementNotExists(verification.value)
            }
        }
    }
    
    /**
     * 验证文本存在（优先使用VL模型，降级到OCR + 语义匹配）
     */
    private suspend fun verifyTextExists(
        text: String,
        getScreenState: (() -> ScreenState?)? = null,
        getOcrResult: (() -> OcrResult?)? = null
    ): VerificationResult {
        // 第一步：优先使用VL模型验证
        val screenState = getScreenState?.invoke()
        if (screenState != null && screenState.vlAvailable && screenState.elements.isNotEmpty()) {
            val matchedElement = screenState.findElementByText(text, useSemanticMatch = false)
            if (matchedElement != null) {
                return VerificationResult(
                    passed = true,
                    message = "文本 '$text' 存在（VL模型识别，置信度: ${matchedElement.confidence}）"
                )
            }
        }
        
        // 第二步：降级到OCR验证
        val ocrResult = getOcrResult?.invoke()
        return if (ocrResult != null && ocrResult.isSuccess) {
            // 使用语义匹配器进行智能匹配
            val matchResult = SemanticMatcher.matchText(text, ocrResult, useSemanticMatch = true)
            
            if (matchResult.success) {
                val matchTypeStr = when (matchResult.matchType) {
                    SemanticMatcher.MatchType.DIRECT -> "OCR直接匹配"
                    SemanticMatcher.MatchType.SEMANTIC -> "OCR语义匹配"
                }
                // 如果VL识别已完成但elements为空，在消息中说明
                val vlStatusStr = if (screenState != null && screenState.vlAvailable && screenState.elements.isEmpty()) {
                    "（VL识别已完成但elements为空，使用OCR）"
                } else if (screenState == null || !screenState.vlAvailable) {
                    "（VL识别未完成，使用OCR）"
                } else {
                    ""
                }
                VerificationResult(
                    passed = true,
                    message = "文本 '$text' 存在（$matchTypeStr$vlStatusStr，置信度: ${matchResult.confidence}）"
                )
            } else {
                VerificationResult(passed = false, message = "文本 '$text' 不存在: ${matchResult.error}")
            }
        } else {
            VerificationResult(passed = false, message = "VL和OCR识别结果都不可用，无法验证")
        }
    }
    
    /**
     * 验证文本不存在（优先使用VL模型，降级到OCR + 语义匹配）
     */
    private suspend fun verifyTextNotExists(
        text: String,
        getScreenState: (() -> ScreenState?)? = null,
        getOcrResult: (() -> OcrResult?)? = null
    ): VerificationResult {
        // 第一步：优先使用VL模型验证
        val screenState = getScreenState?.invoke()
        if (screenState != null && screenState.vlAvailable && screenState.elements.isNotEmpty()) {
            val matchedElement = screenState.findElementByText(text, useSemanticMatch = false)
            if (matchedElement == null) {
                return VerificationResult(
                    passed = true,
                    message = "文本 '$text' 不存在（符合预期，VL模型验证）"
                )
            } else {
                return VerificationResult(
                    passed = false,
                    message = "文本 '$text' 存在（不符合预期，VL模型识别到: '${matchedElement.text}'）"
                )
            }
        }
        
        // 第二步：降级到OCR验证
        val ocrResult = getOcrResult?.invoke()
        return if (ocrResult != null && ocrResult.isSuccess) {
            // 使用语义匹配器进行智能匹配
            val matchResult = SemanticMatcher.matchText(text, ocrResult, useSemanticMatch = true)
            
            // 如果VL识别已完成但elements为空，在消息中说明
            val vlStatusStr = if (screenState != null && screenState.vlAvailable && screenState.elements.isEmpty()) {
                "（VL识别已完成但elements为空，使用OCR）"
            } else if (screenState == null || !screenState.vlAvailable) {
                "（VL识别未完成，使用OCR）"
            } else {
                ""
            }
            
            if (!matchResult.success) {
                VerificationResult(passed = true, message = "文本 '$text' 不存在（符合预期$vlStatusStr）")
            } else {
                VerificationResult(
                    passed = false,
                    message = "文本 '$text' 存在（不符合预期$vlStatusStr，匹配到: '${matchResult.matchedBlock?.text}'）"
                )
            }
        } else {
            VerificationResult(passed = false, message = "VL和OCR识别结果都不可用，无法验证")
        }
    }
    
    /**
     * 验证元素存在（使用Accessibility Service）
     */
    private fun verifyElementExists(resourceId: String): VerificationResult {
        val service = com.testwings.service.TestWingsAccessibilityService.getInstance()
        return if (service != null) {
            val node = service.findNodeByResourceId(resourceId)
            if (node != null) {
                node.recycle()
                VerificationResult(passed = true, message = "元素 '$resourceId' 存在")
            } else {
                VerificationResult(passed = false, message = "元素 '$resourceId' 不存在")
            }
        } else {
            VerificationResult(passed = false, message = "无障碍服务未启用，无法验证")
        }
    }
    
    /**
     * 验证元素不存在（使用Accessibility Service）
     */
    private fun verifyElementNotExists(resourceId: String): VerificationResult {
        val service = com.testwings.service.TestWingsAccessibilityService.getInstance()
        return if (service != null) {
            val node = service.findNodeByResourceId(resourceId)
            if (node == null) {
                VerificationResult(passed = true, message = "元素 '$resourceId' 不存在（符合预期）")
            } else {
                node.recycle()
                VerificationResult(passed = false, message = "元素 '$resourceId' 存在（不符合预期）")
            }
        } else {
            VerificationResult(passed = false, message = "无障碍服务未启用，无法验证")
        }
    }
    
    /**
     * 操作执行结果
     */
    private data class ActionResult(
        val success: Boolean,
        val error: String? = null
    )
}

