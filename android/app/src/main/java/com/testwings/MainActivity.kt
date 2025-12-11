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
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import com.testwings.utils.GooglePlayServicesChecker
import com.testwings.utils.OcrRecognizerFactory
import com.testwings.utils.OcrResult
import com.testwings.utils.ScreenCapture
import com.testwings.ui.TestCaseManagerSection

class MainActivity : ComponentActivity() {
    private lateinit var mediaProjectionManager: MediaProjectionManager
    private var mediaProjection: MediaProjection? = null
    private var imageReader: ImageReader? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var screenCapture: ScreenCapture? = null
    private var ocrRecognizer: com.testwings.utils.IOcrRecognizer? = null
    private val coroutineScope = CoroutineScope(Dispatchers.Main)
    
    // OCR结果状态（用于传递给Compose UI）
    private var ocrResultState: OcrResult? = null
    private var onOcrResultUpdate: ((OcrResult?) -> Unit)? = null
    
    // 用于测试用例执行的OCR结果等待
    private var pendingOcrResult: OcrResult? = null
    private var ocrResultReady: Boolean = false
    
    // 防止重复处理图像的标志
    private var isCapturing: Boolean = false
    
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
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // Android 8.0+ 使用新的API
            @Suppress("DEPRECATION")
            val runningServices = activityManager.getRunningServices(Integer.MAX_VALUE)
            runningServices?.any { it.service.className == ScreenCaptureService::class.java.name } ?: false
        } else {
            @Suppress("DEPRECATION")
            val runningServices = activityManager.getRunningServices(Integer.MAX_VALUE)
            runningServices.any { it.service.className == ScreenCaptureService::class.java.name }
        }
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
            // 服务已运行，优化：减少延迟时间
            Handler(Looper.getMainLooper()).postDelayed({
                try {
                    // 再次确保服务运行
                    ensureServiceRunning()
                    // 优化：减少等待时间，从 1 秒减少到 300ms
                    Handler(Looper.getMainLooper()).postDelayed({
                        try {
                            mediaProjection = mediaProjectionManager.getMediaProjection(resultCode, data)
                            captureScreen()
                        } catch (e: SecurityException) {
                            // 如果还是失败，可能是 HarmonyOS 的特殊要求，尝试更长的延迟
                            if (retryCount < 10) {
                                Handler(Looper.getMainLooper()).postDelayed({
                                    waitForServiceAndStart(resultCode, data, retryCount + 1)
                                }, 500) // 从 1 秒减少到 500ms
                            } else {
                                Toast.makeText(this, "需要前台服务才能捕获屏幕，请检查通知权限", Toast.LENGTH_LONG).show()
                                e.printStackTrace()
                            }
                        }
                    }, 300) // 从 1 秒减少到 300ms
                } catch (e: Exception) {
                    Toast.makeText(this, "启动失败: ${e.message}", Toast.LENGTH_LONG).show()
                    e.printStackTrace()
                }
            }, 200) // 从 500ms 减少到 200ms
        } else {
            // 服务未运行，继续等待
            Handler(Looper.getMainLooper()).postDelayed({
                waitForServiceAndStart(resultCode, data, retryCount + 1)
            }, 300) // 从 500ms 减少到 300ms
        }
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        
        mediaProjectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        screenCapture = ScreenCapture(this)
        ocrRecognizer = OcrRecognizerFactory.create(this)
        
        setContent {
            TestWingsTheme {
                var ocrResultState by remember { mutableStateOf<OcrResult?>(null) }
                
                // 保存更新函数，供Activity使用
                onOcrResultUpdate = { result ->
                    ocrResultState = result
                }
                
                MainScreen(
                    onCaptureClick = { requestScreenCapture() },
                    onOpenAccessibilitySettings = { openAccessibilitySettings() },
                    ocrResult = ocrResultState,
                    onOcrResultChange = { 
                        ocrResultState = it
                        onOcrResultUpdate = null // 清除引用
                    }
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
        // 检查服务是否已经在运行
        if (isServiceRunning()) {
            // 服务已在运行，立即显示授权弹窗（无需等待）
            val intent = mediaProjectionManager.createScreenCaptureIntent()
            screenCaptureLauncher.launch(intent)
            return
        }
        
        // 服务未运行，先启动服务
        val serviceIntent = Intent(applicationContext, ScreenCaptureService::class.java)
        try {
            // 使用 ContextCompat.startForegroundService 更可靠
            ContextCompat.startForegroundService(applicationContext, serviceIntent)
        } catch (e: IllegalStateException) {
            // 如果失败，尝试普通启动
            applicationContext.startService(serviceIntent)
        }
        
        // 优化：减少延迟时间，前台服务启动通常很快（onCreate 中立即调用 startForeground）
        Handler(Looper.getMainLooper()).postDelayed({
            // 检查服务是否运行
            if (isServiceRunning()) {
                // 服务已运行，短暂等待确保系统识别到服务（减少延迟）
                Handler(Looper.getMainLooper()).postDelayed({
                    val intent = mediaProjectionManager.createScreenCaptureIntent()
                    screenCaptureLauncher.launch(intent)
                }, 200) // 从 1.5 秒减少到 200ms
            } else {
                // 服务未运行，提示用户
                Toast.makeText(this, "前台服务启动失败，请检查通知权限", Toast.LENGTH_LONG).show()
                // 仍然尝试请求权限
                val intent = mediaProjectionManager.createScreenCaptureIntent()
                screenCaptureLauncher.launch(intent)
            }
        }, 300) // 从 2 秒减少到 300ms
    }
    
    private fun captureScreen() {
        // 如果正在截图，忽略新的截图请求
        if (isCapturing) {
            Log.d("MainActivity", "正在截图，忽略重复请求")
            return
        }
        
        // 清理之前的资源
        virtualDisplay?.release()
        virtualDisplay = null
        imageReader?.close()
        imageReader = null
        
        // 设置截图标志
        isCapturing = true
        
        val metrics = DisplayMetrics()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Android 11+ 使用 WindowMetrics（但为了兼容性，仍使用 getRealMetrics）
            @Suppress("DEPRECATION")
            display?.getRealMetrics(metrics)
        } else {
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getMetrics(metrics)
        }
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val density = metrics.densityDpi
        
        // 将 buffer 改为 1，只缓存1帧图像，避免多帧导致多张截图
        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 1)
        
        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "ScreenCapture",
            width, height, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface, null, null
        )
        
        imageReader?.setOnImageAvailableListener({ reader ->
            // 如果已经处理过图像，忽略后续的图像（防止多帧导致多张截图）
            if (!isCapturing) {
                Log.d("MainActivity", "已处理过图像，忽略后续图像")
                return@setOnImageAvailableListener
            }
            
            val image = reader.acquireLatestImage()
            if (image != null) {
                // 立即设置标志为 false，防止重复处理
                isCapturing = false
                val bitmap = imageToBitmap(image)
                image.close()
                
                // 保存截图
                val filePath = screenCapture?.saveBitmap(bitmap)
                
                // 进行OCR识别
                ocrRecognizer?.let { recognizer ->
                    Log.d("MainActivity", "开始OCR识别，识别器类型: ${recognizer.javaClass.simpleName}")
                    coroutineScope.launch {
                        try {
                            val ocrResult = recognizer.recognize(bitmap)
                            
                            Log.d("MainActivity", "OCR识别完成，结果: isSuccess=${ocrResult.isSuccess}, textBlocks=${ocrResult.textBlocks.size}, fullTextLength=${ocrResult.fullText.length}")
                            
                            // 更新用于测试用例执行的OCR结果
                            synchronized(this@MainActivity) {
                                pendingOcrResult = ocrResult
                                ocrResultReady = true
                            }
                            
                            // 更新 UI
                            runOnUiThread {
                                val message = if (filePath != null) {
                                    "截图已保存: $filePath\n" +
                                    if (ocrResult.isSuccess) {
                                        "OCR识别成功: 识别到 ${ocrResult.textBlocks.size} 个文本块\n" +
                                        "识别文字: ${ocrResult.fullText.take(100)}${if (ocrResult.fullText.length > 100) "..." else ""}"
                                    } else {
                                        val recognizerType = recognizer.javaClass.simpleName
                                        when (recognizerType) {
                                            "PaddleOcrRecognizer" -> {
                                                "OCR识别失败: PaddleOCR尚未集成\n（当前为占位实现，需要集成PaddleOCR库）"
                                            }
                                            "MlKitOcrRecognizer" -> {
                                                "OCR识别失败: ML Kit需要Google Play Store\n（HarmonyOS设备未安装，需要集成PaddleOCR）\n\n解决方案：\n1. 安装Google Play Store（可能不可用）\n2. 集成PaddleOCR（推荐，完全离线）"
                                            }
                                            else -> {
                                                "OCR识别失败或未识别到文字"
                                            }
                                        }
                                    }
                                } else {
                                    "截图保存失败"
                                }
                                Toast.makeText(
                                    this@MainActivity,
                                    message,
                                    Toast.LENGTH_LONG
                                ).show()
                                
                                // 更新OCR结果到UI
                                onOcrResultUpdate?.invoke(ocrResult)
                            }
                        } catch (e: Exception) {
                            Log.e("MainActivity", "OCR识别异常", e)
                            // 标记OCR失败
                            synchronized(this@MainActivity) {
                                pendingOcrResult = null
                                ocrResultReady = true
                            }
                            runOnUiThread {
                                Toast.makeText(
                                    this@MainActivity,
                                    if (filePath != null) "截图已保存: $filePath\nOCR识别异常: ${e.message}" else "截图保存失败",
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        }
                    }
                } ?: runOnUiThread {
                    // 没有OCR识别器，标记为完成但结果为空
                    synchronized(this@MainActivity) {
                        pendingOcrResult = null
                        ocrResultReady = true
                    }
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
            } else {
                // 图像为 null，重置标志
                isCapturing = false
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
     * 触发截图并等待OCR结果（用于测试用例执行）
     * 这是一个 suspend 函数，会等待OCR识别完成
     */
    suspend fun triggerScreenshotAndWaitForOcr(): OcrResult? {
        // 重置状态
        synchronized(this) {
            pendingOcrResult = null
            ocrResultReady = false
        }
        
        // 如果已经有有效的 MediaProjection 实例，直接使用
        if (mediaProjection != null) {
            try {
                // 触发截图（异步）
                captureScreen()
            } catch (e: Exception) {
                // 如果 MediaProjection 已失效，需要重新获取
                mediaProjection = null
                Log.d("MainActivity", "MediaProjection 已失效，需要重新授权")
                return null
            }
        } else {
            // 没有 MediaProjection，无法截图
            Log.w("MainActivity", "MediaProjection 未初始化，无法截图")
            return null
        }
        
        // 等待OCR结果（最多等待10秒）
        var retryCount = 0
        val maxRetries = 20 // 20 * 500ms = 10秒
        while (!ocrResultReady && retryCount < maxRetries) {
            kotlinx.coroutines.delay(500)
            retryCount++
        }
        
        // 获取OCR结果
        synchronized(this) {
            val result = pendingOcrResult
            pendingOcrResult = null
            ocrResultReady = false
            return result
        }
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
    
    override fun onResume() {
        super.onResume()
        // 当Activity恢复时（例如从设置页面返回），刷新无障碍服务状态
        // 这会触发Compose重新检查状态
        // 注意：这里不需要手动刷新，因为MainScreen中的LaunchedEffect会在Activity恢复时重新执行
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
    onOpenAccessibilitySettings: () -> Unit,
    ocrResult: OcrResult? = null,
    onOcrResultChange: (OcrResult?) -> Unit = {}
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var isAccessibilityEnabled by remember { mutableStateOf(false) }
    
    // 将操作计数状态提升到 MainScreen，避免页面切换时丢失
    var clickCount by remember { mutableStateOf(0) }
    var swipeUpCount by remember { mutableStateOf(0) }
    var swipeDownCount by remember { mutableStateOf(0) }
    var swipeLeftCount by remember { mutableStateOf(0) }
    var swipeRightCount by remember { mutableStateOf(0) }
    var lastOperation by remember { mutableStateOf<String?>(null) }
    
    // 刷新无障碍服务状态的函数
    val refreshAccessibilityStatus: () -> Unit = {
        isAccessibilityEnabled = DeviceController.isAccessibilityServiceEnabled(context)
    }
    
    // 检查无障碍服务状态（传入context以提高准确性）
    LaunchedEffect(Unit) {
        isAccessibilityEnabled = DeviceController.isAccessibilityServiceEnabled(context)
    }
    
    // 定期检查服务状态（传入context以提高准确性）
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(1000)
            isAccessibilityEnabled = DeviceController.isAccessibilityServiceEnabled(context)
        }
    }
    
    // 使用 HorizontalPager 实现三页布局（左页、主页、右页）
    val pagerState = rememberPagerState(initialPage = 1, pageCount = { 3 })
    
    // 监听页面切换，用于验证滑动操作
    val currentPage = pagerState.currentPage
    
    HorizontalPager(
        state = pagerState,
        modifier = Modifier.fillMaxSize()
    ) { page ->
        when (page) {
            0 -> LeftPage()  // 左侧空白页
            1 -> MainContentPage(  // 主页面（当前内容）
                onCaptureClick = onCaptureClick,
                onOpenAccessibilitySettings = onOpenAccessibilitySettings,
                isAccessibilityEnabled = isAccessibilityEnabled,
                onRefreshAccessibilityStatus = refreshAccessibilityStatus,
                pagerState = pagerState,
                clickCount = clickCount,
                swipeUpCount = swipeUpCount,
                swipeDownCount = swipeDownCount,
                swipeLeftCount = swipeLeftCount,
                swipeRightCount = swipeRightCount,
                lastOperation = lastOperation,
                ocrResult = ocrResult,
                onClickCountChange = { clickCount = it },
                onSwipeUpCountChange = { swipeUpCount = it },
                onSwipeDownCountChange = { swipeDownCount = it },
                onSwipeLeftCountChange = { swipeLeftCount = it },
                onSwipeRightCountChange = { swipeRightCount = it },
                onLastOperationChange = { lastOperation = it },
                onOcrResultChange = onOcrResultChange
            )
            2 -> RightPage()  // 右侧空白页
        }
    }
}

/**
 * 主内容页面
 */
@Composable
fun MainContentPage(
    onCaptureClick: () -> Unit,
    onOpenAccessibilitySettings: () -> Unit,
    isAccessibilityEnabled: Boolean,
    onRefreshAccessibilityStatus: () -> Unit,
    pagerState: PagerState,
    clickCount: Int,
    swipeUpCount: Int,
    swipeDownCount: Int,
    swipeLeftCount: Int,
    swipeRightCount: Int,
    lastOperation: String?,
    ocrResult: OcrResult? = null,
    onClickCountChange: (Int) -> Unit,
    onSwipeUpCountChange: (Int) -> Unit,
    onSwipeDownCountChange: (Int) -> Unit,
    onSwipeLeftCountChange: (Int) -> Unit,
    onSwipeRightCountChange: (Int) -> Unit,
    onLastOperationChange: (String?) -> Unit,
    onOcrResultChange: (OcrResult?) -> Unit = {}
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scrollState = rememberScrollState()
    val currentPage = pagerState.currentPage
    val totalPages = 3
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // 页面位置指示
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer
            )
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "页面位置",
                    style = MaterialTheme.typography.titleSmall
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    for (i in 0 until totalPages) {
                        Box(
                            modifier = Modifier
                                .size(if (i == currentPage) 12.dp else 8.dp)
                                .background(
                                    color = if (i == currentPage) 
                                        MaterialTheme.colorScheme.primary 
                                    else 
                                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
                                    shape = CircleShape
                                )
                        )
                    }
                }
                Text(
                    text = "${currentPage + 1}/$totalPages",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
            }
        }
        
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
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "无障碍服务状态",
                        style = MaterialTheme.typography.titleMedium
                    )
                    // 刷新按钮
                    TextButton(
                        onClick = {
                            onRefreshAccessibilityStatus()
                            android.widget.Toast.makeText(
                                context,
                                "已刷新状态",
                                android.widget.Toast.LENGTH_SHORT
                            ).show()
                        }
                    ) {
                        Text("🔄 刷新")
                    }
                }
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
        
        // OCR识别结果
        if (ocrResult != null) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = if (ocrResult.isSuccess) 
                        MaterialTheme.colorScheme.primaryContainer 
                    else 
                        MaterialTheme.colorScheme.errorContainer
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "OCR识别结果",
                            style = MaterialTheme.typography.titleMedium
                        )
                        TextButton(
                            onClick = { onOcrResultChange(null) }
                        ) {
                            Text("清除")
                        }
                    }
                    
                    if (ocrResult.isSuccess) {
                        Text(
                            text = "✅ 识别成功",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            text = "识别到 ${ocrResult.textBlocks.size} 个文本块",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        
                        // 显示完整文字（可滚动）
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 200.dp)
                        ) {
                            Column(
                                modifier = Modifier
                                    .padding(12.dp)
                                    .verticalScroll(rememberScrollState())
                            ) {
                                Text(
                                    text = ocrResult.fullText,
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                        
                        // 显示文本块列表（前5个）
                        if (ocrResult.textBlocks.isNotEmpty()) {
                            Text(
                                text = "文本块详情：",
                                style = MaterialTheme.typography.titleSmall
                            )
                            ocrResult.textBlocks.take(5).forEachIndexed { index, block ->
                                Card(
                                    colors = CardDefaults.cardColors(
                                        containerColor = MaterialTheme.colorScheme.surface
                                    ),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(
                                        modifier = Modifier.padding(8.dp),
                                        verticalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        Text(
                                            text = "${index + 1}. ${block.text}",
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                        Text(
                                            text = "位置: (${block.boundingBox.left}, ${block.boundingBox.top}) - (${block.boundingBox.right}, ${block.boundingBox.bottom})",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                            if (ocrResult.textBlocks.size > 5) {
                                Text(
                                    text = "... 还有 ${ocrResult.textBlocks.size - 5} 个文本块",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    } else {
                        Text(
                            text = "❌ 识别失败或未识别到文字",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }
        }
        
        // 测试用例执行功能
        val mainActivity = context as? MainActivity
        TestCaseManagerSection(
            ocrResult = ocrResult,
            onExecutionComplete = { result ->
                android.widget.Toast.makeText(
                    context,
                    if (result.success) "测试用例执行成功" else "测试用例执行失败",
                    android.widget.Toast.LENGTH_SHORT
                ).show()
            },
            triggerScreenshotAndWaitForOcr = mainActivity?.let { activity ->
                { activity.triggerScreenshotAndWaitForOcr() }
            }
        )
        
        // 操作测试功能（需要无障碍服务）
        if (isAccessibilityEnabled) {
            OperationTestSection(
                context = context,
                pagerState = pagerState,
                clickCount = clickCount,
                swipeUpCount = swipeUpCount,
                swipeDownCount = swipeDownCount,
                swipeLeftCount = swipeLeftCount,
                swipeRightCount = swipeRightCount,
                lastOperation = lastOperation,
                onClickCountChange = onClickCountChange,
                onSwipeUpCountChange = onSwipeUpCountChange,
                onSwipeDownCountChange = onSwipeDownCountChange,
                onSwipeLeftCountChange = onSwipeLeftCountChange,
                onSwipeRightCountChange = onSwipeRightCountChange,
                onLastOperationChange = onLastOperationChange
            )
        }
    }
}

/**
 * 左侧空白页面（用于测试向右滑动）
 */
@Composable
fun LeftPage() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = "← 左侧页面",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = "向左滑动可以返回主页面",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * 右侧空白页面（用于测试向左滑动）
 */
@Composable
fun RightPage() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = "右侧页面 →",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = "向右滑动可以返回主页面",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
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
fun OperationTestSection(
    context: Context,
    pagerState: PagerState,
    clickCount: Int,
    swipeUpCount: Int,
    swipeDownCount: Int,
    swipeLeftCount: Int,
    swipeRightCount: Int,
    lastOperation: String?,
    onClickCountChange: (Int) -> Unit,
    onSwipeUpCountChange: (Int) -> Unit,
    onSwipeDownCountChange: (Int) -> Unit,
    onSwipeLeftCountChange: (Int) -> Unit,
    onSwipeRightCountChange: (Int) -> Unit,
    onLastOperationChange: (String?) -> Unit
) {
    // 记录滑动前的页面位置，用于验证滑动操作
    val pageBeforeSwipe = remember { mutableStateOf(pagerState.currentPage) }
    
    // 获取屏幕尺寸
    val displayMetrics = context.resources.displayMetrics
    val screenWidth = displayMetrics.widthPixels
    val screenHeight = displayMetrics.heightPixels
    val centerX = screenWidth / 2
    val centerY = screenHeight / 2
    
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
                text = "💡 提示：操作会立即执行，在当前页面即可看到效果。上下滑动可以滚动当前页面，左右滑动可以切换页面。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            
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
                                val newCount = clickCount + 1
                                onClickCountChange(newCount)
                                onLastOperationChange(if (result) {
                                    "点击屏幕中心 ($centerX, $centerY) - 成功 (第 $newCount 次)"
                                } else {
                                    "点击屏幕中心 ($centerX, $centerY) - 失败"
                                })
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
                                val result = DeviceController.swipeUp()
                                val newCount = swipeUpCount + 1
                                onSwipeUpCountChange(newCount)
                                onLastOperationChange(if (result) {
                                    "向上滑动 - 成功 (第 $newCount 次)"
                                } else {
                                    "向上滑动 - 失败"
                                })
                                android.widget.Toast.makeText(
                                    context,
                                    if (result) "✅ 向上滑动已执行！" else "❌ 滑动失败",
                                    android.widget.Toast.LENGTH_SHORT
                                ).show()
                            },
                            modifier = Modifier.weight(1f)
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
                                val result = DeviceController.swipeDown()
                                val newCount = swipeDownCount + 1
                                onSwipeDownCountChange(newCount)
                                onLastOperationChange(if (result) {
                                    "向下滑动 - 成功 (第 $newCount 次)"
                                } else {
                                    "向下滑动 - 失败"
                                })
                                android.widget.Toast.makeText(
                                    context,
                                    if (result) "✅ 向下滑动已执行！" else "❌ 滑动失败",
                                    android.widget.Toast.LENGTH_SHORT
                                ).show()
                            },
                            modifier = Modifier.weight(1f)
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
                                // 记录滑动前的页面位置
                                pageBeforeSwipe.value = pagerState.currentPage
                                val result = DeviceController.swipeLeft()
                                val newCount = swipeLeftCount + 1
                                onSwipeLeftCountChange(newCount)
                                // 延迟检查页面是否切换（给滑动操作时间执行）
                                CoroutineScope(Dispatchers.Main).launch {
                                    delay(300) // 等待300ms让滑动操作完成
                                    val newPage = pagerState.currentPage
                                    if (newPage > pageBeforeSwipe.value) {
                                        // 页面向右切换了（从主页切换到右侧页），说明向左滑动成功
                                        onLastOperationChange("向左滑动 - 成功 (第 $newCount 次) - 页面已切换")
                                        android.widget.Toast.makeText(
                                            context,
                                            "✅ 向左滑动成功！页面已切换",
                                            android.widget.Toast.LENGTH_SHORT
                                        ).show()
                                    } else if (result) {
                                        onLastOperationChange("向左滑动 - 已执行 (第 $newCount 次)")
                                        android.widget.Toast.makeText(
                                            context,
                                            "✅ 向左滑动已执行！",
                                            android.widget.Toast.LENGTH_SHORT
                                        ).show()
                                    } else {
                                        onLastOperationChange("向左滑动 - 失败")
                                        android.widget.Toast.makeText(
                                            context,
                                            "❌ 滑动失败",
                                            android.widget.Toast.LENGTH_SHORT
                                        ).show()
                                    }
                                }
                            },
                            modifier = Modifier.weight(1f)
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
                                // 记录滑动前的页面位置
                                pageBeforeSwipe.value = pagerState.currentPage
                                val result = DeviceController.swipeRight()
                                val newCount = swipeRightCount + 1
                                onSwipeRightCountChange(newCount)
                                // 延迟检查页面是否切换（给滑动操作时间执行）
                                CoroutineScope(Dispatchers.Main).launch {
                                    delay(300) // 等待300ms让滑动操作完成
                                    val newPage = pagerState.currentPage
                                    if (newPage < pageBeforeSwipe.value) {
                                        // 页面向左切换了（从主页切换到左侧页），说明向右滑动成功
                                        onLastOperationChange("向右滑动 - 成功 (第 $newCount 次) - 页面已切换")
                                        android.widget.Toast.makeText(
                                            context,
                                            "✅ 向右滑动成功！页面已切换",
                                            android.widget.Toast.LENGTH_SHORT
                                        ).show()
                                    } else if (result) {
                                        onLastOperationChange("向右滑动 - 已执行 (第 $newCount 次)")
                                        android.widget.Toast.makeText(
                                            context,
                                            "✅ 向右滑动已执行！",
                                            android.widget.Toast.LENGTH_SHORT
                                        ).show()
                                    } else {
                                        onLastOperationChange("向右滑动 - 失败")
                                        android.widget.Toast.makeText(
                                            context,
                                            "❌ 滑动失败",
                                            android.widget.Toast.LENGTH_SHORT
                                        ).show()
                                    }
                                }
                            },
                            modifier = Modifier.weight(1f)
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
                            onLastOperationChange(if (result) {
                                "返回键 - 成功"
                            } else {
                                "返回键 - 失败"
                            })
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
