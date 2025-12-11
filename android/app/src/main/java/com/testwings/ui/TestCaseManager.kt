package com.testwings.ui

import android.content.Context
import android.util.Log
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.testwings.testcase.*
import com.testwings.utils.OcrResult
import kotlinx.coroutines.launch
import java.io.InputStream

/**
 * 测试用例管理界面
 */
@Composable
fun TestCaseManagerSection(
    ocrResult: OcrResult? = null,
    onExecutionComplete: ((TestExecutor.ExecutionResult) -> Unit)? = null,
    triggerScreenshotAndWaitForOcr: (suspend () -> OcrResult?)? = null
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    
    var testCases by remember { mutableStateOf<List<TestCase>>(emptyList()) }
    var selectedTestCase by remember { mutableStateOf<TestCase?>(null) }
    var isExecuting by remember { mutableStateOf(false) }
    var executionResult by remember { mutableStateOf<TestExecutor.ExecutionResult?>(null) }
    
    // 加载测试用例
    LaunchedEffect(Unit) {
        testCases = loadTestCases(context)
    }
    
    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "测试用例执行",
                    style = MaterialTheme.typography.titleMedium
                )
                if (testCases.isEmpty()) {
                    TextButton(
                        onClick = {
                            coroutineScope.launch {
                                testCases = loadTestCases(context)
                            }
                        }
                    ) {
                        Text("刷新")
                    }
                }
            }
            
            if (testCases.isEmpty()) {
                Text(
                    text = "暂无测试用例",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                // 测试用例列表
                testCases.forEach { testCase ->
                    TestCaseItem(
                        testCase = testCase,
                        isSelected = selectedTestCase?.name == testCase.name,
                        isExecuting = isExecuting,
                        onSelect = { selectedTestCase = testCase },
                        onExecute = {
                            // 点击执行按钮时，设置选中的测试用例并执行
                            selectedTestCase = testCase
                            isExecuting = true
                            executionResult = null
                            coroutineScope.launch {
                                executeTestCase(context, testCase, ocrResult, triggerScreenshotAndWaitForOcr) { result ->
                                    executionResult = result
                                    isExecuting = false
                                    onExecutionComplete?.invoke(result)
                                }
                            }
                        }
                    )
                }
            }
            
            // 执行按钮
            if (selectedTestCase != null) {
                Button(
                    onClick = {
                        if (!isExecuting && selectedTestCase != null) {
                            isExecuting = true
                            executionResult = null
                            coroutineScope.launch {
                                executeTestCase(context, selectedTestCase!!, ocrResult, triggerScreenshotAndWaitForOcr) { result ->
                                    executionResult = result
                                    isExecuting = false
                                    onExecutionComplete?.invoke(result)
                                }
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isExecuting && selectedTestCase != null
                ) {
                    if (isExecuting) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("执行中...")
                    } else {
                        Text("执行测试用例")
                    }
                }
            }
            
            // 执行结果
            executionResult?.let { result ->
                ExecutionResultCard(result = result)
            }
        }
    }
}

/**
 * 测试用例项
 */
@Composable
private fun TestCaseItem(
    testCase: TestCase,
    isSelected: Boolean,
    isExecuting: Boolean,
    onSelect: () -> Unit,
    onExecute: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected)
                MaterialTheme.colorScheme.primaryContainer
            else
                MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = testCase.name,
                    style = MaterialTheme.typography.titleSmall
                )
                testCase.description?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    text = "共 ${testCase.steps.size} 个步骤",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(
                onClick = onExecute,
                enabled = !isExecuting
            ) {
                Icon(
                    imageVector = Icons.Default.PlayArrow,
                    contentDescription = "执行"
                )
            }
        }
    }
}

/**
 * 执行结果卡片
 */
@Composable
private fun ExecutionResultCard(
    result: TestExecutor.ExecutionResult
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (result.success)
                MaterialTheme.colorScheme.primaryContainer
            else
                MaterialTheme.colorScheme.errorContainer
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "执行结果",
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = if (result.success) "✅ 成功" else "❌ 失败",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            
            Text(
                text = "总耗时: ${result.totalDuration}ms",
                style = MaterialTheme.typography.bodySmall
            )
            
            Text(
                text = "步骤数: ${result.stepResults.size}",
                style = MaterialTheme.typography.bodySmall
            )
            
            result.error?.let {
                Text(
                    text = "错误: $it",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
            
            // 步骤详情（可展开）
            var expanded by remember { mutableStateOf(false) }
            TextButton(
                onClick = { expanded = !expanded },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (expanded) "收起步骤详情" else "展开步骤详情")
            }
            
            if (expanded) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    result.stepResults.forEach { stepResult ->
                        StepResultItem(stepResult = stepResult)
                    }
                }
            }
        }
    }
}

/**
 * 步骤结果项
 */
@Composable
private fun StepResultItem(
    stepResult: TestExecutor.StepResult
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (stepResult.success)
                MaterialTheme.colorScheme.surfaceVariant
            else
                MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "步骤 ${stepResult.stepNumber}: ${stepResult.description}",
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    text = if (stepResult.success) "✅" else "❌",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            
            Text(
                text = "耗时: ${stepResult.duration}ms",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            
            stepResult.error?.let {
                Text(
                    text = "错误: $it",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
            
            stepResult.verificationResult?.let { verification ->
                Text(
                    text = "验证: ${verification.message}",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (verification.passed)
                        MaterialTheme.colorScheme.primary
                    else
                        MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

/**
 * 加载测试用例
 */
private fun loadTestCases(context: Context): List<TestCase> {
    val testCases = mutableListOf<TestCase>()
    
    try {
        // 从assets目录加载测试用例
        val assets = context.assets
        val files = assets.list("testcases") ?: emptyArray()
        
        files.forEach { fileName ->
            if (fileName.endsWith(".json")) {
                try {
                    val inputStream: InputStream = assets.open("testcases/$fileName")
                    val jsonString = inputStream.bufferedReader().use { it.readText() }
                    val testCase = TestCaseParser.parse(jsonString)
                    testCase?.let { testCases.add(it) }
                } catch (e: Exception) {
                    Log.e("TestCaseManager", "加载测试用例失败: $fileName", e)
                }
            }
        }
    } catch (e: Exception) {
        Log.e("TestCaseManager", "加载测试用例列表失败", e)
    }
    
    return testCases
}

/**
 * 执行测试用例
 */
private suspend fun executeTestCase(
    context: Context,
    testCase: TestCase,
    ocrResult: OcrResult?,
    triggerScreenshotAndWaitForOcr: (suspend () -> OcrResult?)?,
    onComplete: (TestExecutor.ExecutionResult) -> Unit
) {
    val executor = TestExecutor(context)
    // 创建一个 getOcrResult 函数，可以从 MainActivity 获取最新的OCR结果
    // 注意：如果 triggerScreenshotAndWaitForOcr 不为 null，说明可以从 MainActivity 获取结果
    val getOcrResult: (() -> OcrResult?)? = if (triggerScreenshotAndWaitForOcr != null) {
        // 从 MainActivity 获取最新的OCR结果
        // 注意：这里需要访问 MainActivity 的 ocrResultState，但由于是 private，我们使用一个变通方法
        // 实际上，triggerScreenshotAndWaitForOcr 已经更新了 MainActivity 的 pendingOcrResult
        // 但是 pendingOcrResult 在 triggerScreenshotAndWaitForOcr 返回后会被清空
        // 所以，我们需要在 executeAction 中保存OCR结果，或者使用 ocrResult 参数
        // 暂时使用 ocrResult 参数，但这不是最新的结果
        // TODO: 改进这个逻辑，让 getOcrResult 能够从 MainActivity 获取最新的OCR结果
        { ocrResult }
    } else {
        { ocrResult }
    }
    
    val result = executor.execute(
        testCase = testCase,
        onStepComplete = { stepResult ->
            Log.d("TestCaseManager", "步骤 ${stepResult.stepNumber} 完成: ${stepResult.description}, 成功: ${stepResult.success}")
        },
        getOcrResult = getOcrResult,
        triggerScreenshotAndWaitForOcr = triggerScreenshotAndWaitForOcr
    )
    onComplete(result)
}

