package com.testwings

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import com.testwings.service.ScreenCaptureService
import com.testwings.service.TestWingsAccessibilityService
import com.testwings.ui.theme.TestWingsTheme
import com.testwings.utils.DeviceController
import com.testwings.utils.ScreenCapture

class MainActivity : ComponentActivity() {
    private lateinit var mediaProjectionManager: MediaProjectionManager
    private var mediaProjection: MediaProjection? = null
    private var imageReader: ImageReader? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var screenCapture: ScreenCapture? = null
    
    private val screenCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            // Android 14+ 需要确保服务已启动
            if (Build.VERSION.SDK_INT >= 34) {
                // 服务已经在 requestScreenCapture 中启动，这里直接等待并启动
                waitForServiceAndStart(result.resultCode, result.data!!, 0)
            } else {
                // Android 13 及以下直接启动
                startMediaProjection(result.resultCode, result.data!!)
            }
        } else {
            Toast.makeText(this, "屏幕捕获权限被拒绝", Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun startMediaProjection(resultCode: Int, data: android.content.Intent) {
        try {
            // HarmonyOS 和 Android 14+ 都要求前台服务
            // 为了兼容性，无论版本如何都启动前台服务
            ensureServiceRunning()
            
            // 等待服务完全启动并检查服务状态
            waitForServiceAndStart(resultCode, data, 0)
        } catch (e: SecurityException) {
            // 如果是 SecurityException，可能是 HarmonyOS 的特殊要求
            Toast.makeText(this, "需要前台服务才能捕获屏幕。请确保通知权限已开启。", Toast.LENGTH_LONG).show()
            e.printStackTrace()
        } catch (e: Exception) {
            Toast.makeText(this, "启动屏幕捕获失败: ${e.message}", Toast.LENGTH_LONG).show()
            e.printStackTrace()
        }
    }
    
    private fun ensureServiceRunning() {
        val serviceIntent = Intent(applicationContext, ScreenCaptureService::class.java)
        try {
            // 使用 ContextCompat.startForegroundService 更可靠
            ContextCompat.startForegroundService(applicationContext, serviceIntent)
        } catch (e: IllegalStateException) {
            // 如果失败，尝试普通启动
            applicationContext.startService(serviceIntent)
        }
    }
    
    private fun isServiceRunning(): Boolean {
        val activityManager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val runningServices = activityManager.getRunningServices(Integer.MAX_VALUE)
        return runningServices.any { it.service.className == ScreenCaptureService::class.java.name }
    }
    
    private fun waitForServiceAndStart(resultCode: Int, data: android.content.Intent, retryCount: Int) {
        if (retryCount > 20) {
            Toast.makeText(this, "服务启动超时，请重试", Toast.LENGTH_LONG).show()
            return
        }
        
        // 确保服务运行
        ensureServiceRunning()
        
        // 检查服务是否运行
        if (isServiceRunning()) {
            // 服务已运行，等待更长时间确保系统识别到服务
            Handler(Looper.getMainLooper()).postDelayed({
                try {
                    // 再次确保服务运行
                    ensureServiceRunning()
                    // 再等待一下让系统完全识别服务
                    Handler(Looper.getMainLooper()).postDelayed({
                        try {
                            mediaProjection = mediaProjectionManager.getMediaProjection(resultCode, data)
                            captureScreen()
                        } catch (e: SecurityException) {
                            // 如果还是失败，可能是 HarmonyOS 的特殊要求，尝试更长的延迟
                            if (retryCount < 10) {
                                Handler(Looper.getMainLooper()).postDelayed({
                                    waitForServiceAndStart(resultCode, data, retryCount + 1)
                                }, 1000) // 增加延迟到 1 秒
                            } else {
                                Toast.makeText(this, "需要前台服务才能捕获屏幕，请检查通知权限", Toast.LENGTH_LONG).show()
                                e.printStackTrace()
                            }
                        }
                    }, 1000) // 等待 1 秒让系统识别服务
                } catch (e: Exception) {
                    Toast.makeText(this, "启动失败: ${e.message}", Toast.LENGTH_LONG).show()
                    e.printStackTrace()
                }
            }, 500)
        } else {
            // 服务未运行，继续等待
            Handler(Looper.getMainLooper()).postDelayed({
                waitForServiceAndStart(resultCode, data, retryCount + 1)
            }, 500)
        }
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        
        mediaProjectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        screenCapture = ScreenCapture(this)
        
        setContent {
            TestWingsTheme {
                MainScreen(
                    onCaptureClick = { requestScreenCapture() },
                    onOpenAccessibilitySettings = { openAccessibilitySettings() }
                )
            }
        }
    }
    
    private fun requestScreenCapture() {
        // 如果已经有有效的 MediaProjection 实例，直接使用（避免重复授权）
        if (mediaProjection != null) {
            try {
                captureScreen()
                return
            } catch (e: Exception) {
                // 如果 MediaProjection 已失效，需要重新获取
                mediaProjection = null
                Log.d("MainActivity", "MediaProjection 已失效，需要重新授权")
            }
        }
        
        // HarmonyOS 和 Android 14+ 都需要前台服务，为了兼容性，无论版本如何都启动
        // 先启动服务并等待完全启动
        val serviceIntent = Intent(applicationContext, ScreenCaptureService::class.java)
        try {
            // 使用 ContextCompat.startForegroundService 更可靠
            ContextCompat.startForegroundService(applicationContext, serviceIntent)
        } catch (e: IllegalStateException) {
            // 如果失败，尝试普通启动
            applicationContext.startService(serviceIntent)
        }
        
        // 等待服务完全启动后再请求权限（给服务足够时间启动并显示通知）
        Handler(Looper.getMainLooper()).postDelayed({
            // 检查服务是否运行
            if (isServiceRunning()) {
                // 服务已运行，再等待一下确保系统识别到服务
                Handler(Looper.getMainLooper()).postDelayed({
                    val intent = mediaProjectionManager.createScreenCaptureIntent()
                    screenCaptureLauncher.launch(intent)
                }, 1500) // 再等待 1.5 秒
            } else {
                // 服务未运行，提示用户
                Toast.makeText(this, "前台服务启动失败，请检查通知权限", Toast.LENGTH_LONG).show()
                // 仍然尝试请求权限
                val intent = mediaProjectionManager.createScreenCaptureIntent()
                screenCaptureLauncher.launch(intent)
            }
        }, 2000) // 等待 2 秒让服务完全启动
    }
    
    private fun captureScreen() {
        val metrics = DisplayMetrics()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            display?.getRealMetrics(metrics)
        } else {
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getMetrics(metrics)
        }
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val density = metrics.densityDpi
        
        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        
        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "ScreenCapture",
            width, height, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface, null, null
        )
        
        imageReader?.setOnImageAvailableListener({ reader ->
            val image = reader.acquireLatestImage()
            if (image != null) {
                val bitmap = imageToBitmap(image)
                image.close()
                
                // 保存截图
                val filePath = screenCapture?.saveBitmap(bitmap)
                
                // 更新 UI（通过 Activity 的方式）
                runOnUiThread {
                    Toast.makeText(
                        this,
                        if (filePath != null) "截图已保存: $filePath" else "截图保存失败",
                        Toast.LENGTH_LONG
                    ).show()
                }
                
                // 清理 VirtualDisplay（但保留 MediaProjection 以便下次使用）
                virtualDisplay?.release()
                virtualDisplay = null
                imageReader?.close()
                imageReader = null
                
                // 注意：不调用 mediaProjection?.stop()，保留 MediaProjection 实例
                // 这样在同一个应用会话中，下次截图就不需要重新授权了
                
                // 停止前台服务
                if (Build.VERSION.SDK_INT >= 34) {
                    val serviceIntent = Intent(applicationContext, ScreenCaptureService::class.java)
                    applicationContext.stopService(serviceIntent)
                }
            }
        }, null)
    }
    
    private fun imageToBitmap(image: Image): Bitmap {
        val planes = image.planes
        val buffer = planes[0].buffer
        val pixelStride = planes[0].pixelStride
        val rowStride = planes[0].rowStride
        val rowPadding = rowStride - pixelStride * image.width
        
        val bitmap = Bitmap.createBitmap(
            image.width + rowPadding / pixelStride,
            image.height,
            Bitmap.Config.ARGB_8888
        )
        bitmap.copyPixelsFromBuffer(buffer)
        return Bitmap.createBitmap(bitmap, 0, 0, image.width, image.height)
    }
    
    /**
     * 打开无障碍设置页面
     */
    private fun openAccessibilitySettings() {
        try {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(
                this,
                "无法打开无障碍设置，请手动前往：设置 → 辅助功能 → TestWings",
                Toast.LENGTH_LONG
            ).show()
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        virtualDisplay?.release()
        mediaProjection?.stop()
        imageReader?.close()
    }
}

@Composable
fun MainScreen(
    onCaptureClick: () -> Unit,
    onOpenAccessibilitySettings: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var isAccessibilityEnabled by remember { mutableStateOf(false) }
    
    // 检查无障碍服务状态
    LaunchedEffect(Unit) {
        isAccessibilityEnabled = DeviceController.isAccessibilityServiceEnabled()
    }
    
    // 定期检查服务状态
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(1000)
            isAccessibilityEnabled = DeviceController.isAccessibilityServiceEnabled()
        }
    }
    
    val scrollState = rememberScrollState()
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "TestWings - 自动化测试",
            style = MaterialTheme.typography.headlineMedium
        )
        
        // 无障碍服务状态卡片
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = if (isAccessibilityEnabled) 
                    MaterialTheme.colorScheme.primaryContainer 
                else 
                    MaterialTheme.colorScheme.errorContainer
            )
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "无障碍服务状态",
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = if (isAccessibilityEnabled) "✅ 已启用" else "❌ 未启用",
                    style = MaterialTheme.typography.bodyMedium
                )
                if (!isAccessibilityEnabled) {
                    Button(
                        onClick = onOpenAccessibilitySettings,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("前往设置开启")
                    }
                }
            }
        }
        
        // 屏幕捕获功能
        Card(
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "屏幕捕获",
                    style = MaterialTheme.typography.titleMedium
                )
                Button(
                    onClick = onCaptureClick,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("捕获屏幕")
                }
            }
        }
        
        // 操作测试功能（需要无障碍服务）
        if (isAccessibilityEnabled) {
            OperationTestSection(context = context)
        }
    }
}

@Composable
fun ScreenCaptureScreen(
    onCaptureClick: () -> Unit
) {
    var statusText by remember { mutableStateOf("准备就绪") }
    var screenshotBitmap by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "TestWings - 屏幕捕获",
            style = MaterialTheme.typography.headlineMedium
        )
        
        Button(
            onClick = {
                statusText = "正在请求屏幕捕获权限..."
                onCaptureClick()
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("捕获屏幕")
        }
        
        Text(
            text = statusText,
            style = MaterialTheme.typography.bodyMedium
        )
        
        if (screenshotBitmap != null) {
            Image(
                bitmap = screenshotBitmap!!.asImageBitmap(),
                contentDescription = "截图",
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(16.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "点击按钮开始捕获屏幕",
                    style = MaterialTheme.typography.bodyLarge
                )
            }
        }
    }
}

/**
 * 操作测试区域
 */
@Composable
fun OperationTestSection(context: Context) {
    var clickCount by remember { mutableStateOf(0) }
    var swipeUpCount by remember { mutableStateOf(0) }
    var swipeDownCount by remember { mutableStateOf(0) }
    var swipeLeftCount by remember { mutableStateOf(0) }
    var swipeRightCount by remember { mutableStateOf(0) }
    var lastOperation by remember { mutableStateOf<String?>(null) }
    var countdown by remember { mutableStateOf(0) }
    var isDelayedMode by remember { mutableStateOf(false) }
    
    // 获取屏幕尺寸
    val displayMetrics = context.resources.displayMetrics
    val screenWidth = displayMetrics.widthPixels
    val screenHeight = displayMetrics.heightPixels
    val centerX = screenWidth / 2
    val centerY = screenHeight / 2
    
    // 延迟执行协程
    LaunchedEffect(countdown) {
        if (countdown > 0) {
            delay(1000)
            countdown--
        } else if (countdown == 0 && isDelayedMode) {
            isDelayedMode = false
        }
    }
    
    // 执行延迟操作
    fun executeWithDelay(action: () -> Unit, operationName: String) {
        if (isDelayedMode) {
            android.widget.Toast.makeText(
                context,
                "上一个操作还在倒计时中，请稍候",
                android.widget.Toast.LENGTH_SHORT
            ).show()
            return
        }
        
        isDelayedMode = true
        countdown = 3
        
        // 启动倒计时
        CoroutineScope(Dispatchers.Main).launch {
            for (i in 3 downTo 1) {
                countdown = i
                android.widget.Toast.makeText(
                    context,
                    "$operationName 将在 $i 秒后执行，请切换到目标应用",
                    android.widget.Toast.LENGTH_SHORT
                ).show()
                delay(1000)
            }
            countdown = 0
            action()
            isDelayedMode = false
        }
    }
    
    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "操作测试",
                style = MaterialTheme.typography.titleMedium
            )
            
            // 操作说明
            Text(
                text = "💡 提示：点击滑动按钮后，有3秒倒计时，请在这3秒内切换到目标应用（如设置、浏览器），操作会在倒计时结束后执行。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            
            // 倒计时显示
            if (countdown > 0) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "⏰ 倒计时: $countdown 秒后执行操作",
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }
            
            // 最后操作显示
            if (lastOperation != null) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "✅ $lastOperation",
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
            
            // 点击测试
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "点击测试（屏幕中心）",
                        style = MaterialTheme.typography.titleSmall
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Button(
                            onClick = {
                                val result = DeviceController.click(centerX, centerY)
                                clickCount++
                                lastOperation = if (result) {
                                    "点击屏幕中心 ($centerX, $centerY) - 成功 (第 $clickCount 次)"
                                } else {
                                    "点击屏幕中心 ($centerX, $centerY) - 失败"
                                }
                                android.widget.Toast.makeText(
                                    context,
                                    if (result) "✅ 点击成功！" else "❌ 点击失败",
                                    android.widget.Toast.LENGTH_SHORT
                                ).show()
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("点击中心")
                        }
                        Text(
                            text = "已测试: $clickCount",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    Text(
                        text = "坐标: ($centerX, $centerY)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            
            // 滑动测试
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "滑动测试",
                        style = MaterialTheme.typography.titleSmall
                    )
                    
                    // 上下滑动
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Button(
                            onClick = {
                                executeWithDelay({
                                    val result = DeviceController.swipeUp()
                                    swipeUpCount++
                                    lastOperation = if (result) {
                                        "向上滑动 - 成功 (第 $swipeUpCount 次)"
                                    } else {
                                        "向上滑动 - 失败"
                                    }
                                    android.widget.Toast.makeText(
                                        context,
                                        if (result) "✅ 向上滑动已执行！" else "❌ 滑动失败",
                                        android.widget.Toast.LENGTH_SHORT
                                    ).show()
                                }, "向上滑动")
                            },
                            modifier = Modifier.weight(1f),
                            enabled = !isDelayedMode
                        ) {
                            Text("向上滑动")
                        }
                        Text(
                            text = "$swipeUpCount",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Button(
                            onClick = {
                                executeWithDelay({
                                    val result = DeviceController.swipeDown()
                                    swipeDownCount++
                                    lastOperation = if (result) {
                                        "向下滑动 - 成功 (第 $swipeDownCount 次)"
                                    } else {
                                        "向下滑动 - 失败"
                                    }
                                    android.widget.Toast.makeText(
                                        context,
                                        if (result) "✅ 向下滑动已执行！" else "❌ 滑动失败",
                                        android.widget.Toast.LENGTH_SHORT
                                    ).show()
                                }, "向下滑动")
                            },
                            modifier = Modifier.weight(1f),
                            enabled = !isDelayedMode
                        ) {
                            Text("向下滑动")
                        }
                        Text(
                            text = "$swipeDownCount",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    
                    // 左右滑动
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Button(
                            onClick = {
                                executeWithDelay({
                                    val result = DeviceController.swipeLeft()
                                    swipeLeftCount++
                                    lastOperation = if (result) {
                                        "向左滑动 - 成功 (第 $swipeLeftCount 次)"
                                    } else {
                                        "向左滑动 - 失败"
                                    }
                                    android.widget.Toast.makeText(
                                        context,
                                        if (result) "✅ 向左滑动已执行！" else "❌ 滑动失败",
                                        android.widget.Toast.LENGTH_SHORT
                                    ).show()
                                }, "向左滑动")
                            },
                            modifier = Modifier.weight(1f),
                            enabled = !isDelayedMode
                        ) {
                            Text("向左滑动")
                        }
    Text(
                            text = "$swipeLeftCount",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Button(
                            onClick = {
                                executeWithDelay({
                                    val result = DeviceController.swipeRight()
                                    swipeRightCount++
                                    lastOperation = if (result) {
                                        "向右滑动 - 成功 (第 $swipeRightCount 次)"
                                    } else {
                                        "向右滑动 - 失败"
                                    }
                                    android.widget.Toast.makeText(
                                        context,
                                        if (result) "✅ 向右滑动已执行！" else "❌ 滑动失败",
                                        android.widget.Toast.LENGTH_SHORT
                                    ).show()
                                }, "向右滑动")
                            },
                            modifier = Modifier.weight(1f),
                            enabled = !isDelayedMode
                        ) {
                            Text("向右滑动")
                        }
                        Text(
                            text = "$swipeRightCount",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
            
            // 系统按键测试
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "系统按键测试",
                        style = MaterialTheme.typography.titleSmall
                    )
                    Button(
                        onClick = {
                            val result = DeviceController.pressBack()
                            lastOperation = if (result) {
                                "返回键 - 成功"
                            } else {
                                "返回键 - 失败"
                            }
                            android.widget.Toast.makeText(
                                context,
                                if (result) "✅ 返回键成功！" else "❌ 返回键失败",
                                android.widget.Toast.LENGTH_SHORT
                            ).show()
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("返回键")
                    }
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
fun ScreenCapturePreview() {
    TestWingsTheme {
        ScreenCaptureScreen(onCaptureClick = {})
    }
}
