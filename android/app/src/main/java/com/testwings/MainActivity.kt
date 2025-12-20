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
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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
import com.testwings.utils.ScreenState
import com.testwings.utils.VisionLanguageManager
import com.testwings.ui.TestCaseManagerSection

class MainActivity : ComponentActivity() {
    private lateinit var mediaProjectionManager: MediaProjectionManager
    private var mediaProjection: MediaProjection? = null
    private var imageReader: ImageReader? = null
    private var virtualDisplay: VirtualDisplay? = null
    
    // 注意：Android 14+ 严格限制：不能重用 resultData，每次捕获都需要重新请求权限
    // 这是系统安全限制，无法绕过
    private var screenCapture: ScreenCapture? = null
    private var ocrRecognizer: com.testwings.utils.IOcrRecognizer? = null
    private var visionLanguageManager: VisionLanguageManager? = null
    private val coroutineScope = CoroutineScope(Dispatchers.Main + kotlinx.coroutines.SupervisorJob())
    
    // OCR结果状态（用于传递给Compose UI）
    private var ocrResultState: OcrResult? = null
    private var onOcrResultUpdate: ((OcrResult?) -> Unit)? = null
    
    // VL识别结果状态（用于UI显示）
    private var screenStateResult: ScreenState? = null
    private var onScreenStateUpdate: ((ScreenState?) -> Unit)? = null
    
    // 用于测试用例执行的OCR结果等待
    private var pendingOcrResult: OcrResult? = null
    private var ocrResultReady: Boolean = false
    
    // 用于测试用例执行的VL识别结果（ScreenState）
    private var pendingScreenState: ScreenState? = null
    private var screenStateReady: Boolean = false
    
    // 防止重复处理图像的标志
    private var isCapturing: Boolean = false
    
    // 防止重复请求屏幕捕获的标志
    private var isRequestingCapture: Boolean = false
    
    // 防止无限循环的错误计数器（用于检测"单个应用"模式导致的错误）
    private var captureErrorCount: Int = 0
    private val MAX_CAPTURE_ERROR_COUNT = 3
    
    private val screenCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        Log.d("MainActivity", "========== 权限回调开始 ==========")
        Log.d("MainActivity", "resultCode=${result.resultCode}, RESULT_OK=${RESULT_OK}, data=${result.data != null}")
        Log.d("MainActivity", "Android版本=${Build.VERSION.SDK_INT}, >=34=${Build.VERSION.SDK_INT >= 34}")
        
        try {
            if (result.resultCode == RESULT_OK && result.data != null) {
                Log.d("MainActivity", "✅ 屏幕捕获权限已授权，resultCode=${result.resultCode}")
                
                // 注意：Android 14+ 不允许重用 resultData，每次捕获都需要重新请求权限
                // 这是系统安全限制，无法绕过
                
                // Android 14+ 需要确保服务已启动
                if (Build.VERSION.SDK_INT >= 34) {
                    Log.d("MainActivity", "Android 14+，调用 waitForServiceAndStart...")
                    // 服务已经在 requestScreenCapture 中启动，这里直接等待并启动
                    try {
                        waitForServiceAndStart(result.resultCode, result.data!!, 0)
                        Log.d("MainActivity", "waitForServiceAndStart 调用完成")
                    } catch (e: Exception) {
                        Log.e("MainActivity", "调用 waitForServiceAndStart 时异常", e)
                        e.printStackTrace()
                    }
                } else {
                    Log.d("MainActivity", "Android 13 及以下，直接启动")
                    // Android 13 及以下直接启动
                    isRequestingCapture = false // 重置标志
                    startMediaProjection(result.resultCode, result.data!!)
                }
            } else {
                Log.w("MainActivity", "❌ 屏幕捕获权限被拒绝，resultCode=${result.resultCode}, data=${result.data != null}")
                isRequestingCapture = false // 重置标志
                Toast.makeText(this, "屏幕捕获权限被拒绝", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "❌ 处理屏幕捕获权限结果时发生异常", e)
            isRequestingCapture = false // 重置标志
            Toast.makeText(this, "处理授权结果失败: ${e.message}", Toast.LENGTH_LONG).show()
            e.printStackTrace()
        } finally {
            Log.d("MainActivity", "========== 权限回调结束 ==========")
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
            Log.d("MainActivity", "启动前台服务...")
            ContextCompat.startForegroundService(applicationContext, serviceIntent)
            Log.d("MainActivity", "前台服务启动命令已发送")
        } catch (e: IllegalStateException) {
            Log.w("MainActivity", "startForegroundService 失败，尝试普通启动", e)
            // 如果失败，尝试普通启动
            try {
                applicationContext.startService(serviceIntent)
                Log.d("MainActivity", "普通启动服务命令已发送")
            } catch (e2: Exception) {
                Log.e("MainActivity", "启动服务失败", e2)
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "启动服务异常", e)
        }
    }
    
    private fun isServiceRunning(): Boolean {
        val activityManager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val isRunning = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // Android 8.0+ 使用新的API
            @Suppress("DEPRECATION")
            val runningServices = activityManager.getRunningServices(Integer.MAX_VALUE)
            runningServices?.any { it.service.className == ScreenCaptureService::class.java.name } ?: false
        } else {
            @Suppress("DEPRECATION")
            val runningServices = activityManager.getRunningServices(Integer.MAX_VALUE)
            runningServices.any { it.service.className == ScreenCaptureService::class.java.name }
        }
        Log.d("MainActivity", "检查服务运行状态: $isRunning (服务类名: ${ScreenCaptureService::class.java.name})")
        return isRunning
    }
    
    private fun waitForServiceAndStart(resultCode: Int, data: android.content.Intent, retryCount: Int) {
        Log.d("MainActivity", "========== waitForServiceAndStart 开始 ==========")
        Log.d("MainActivity", "waitForServiceAndStart: retryCount=$retryCount, resultCode=$resultCode, data=${data != null}")
        
        if (retryCount > 20) {
            Log.e("MainActivity", "服务启动超时，retryCount=$retryCount")
            runOnUiThread {
                Toast.makeText(this, "服务启动超时，请重试", Toast.LENGTH_LONG).show()
            }
            return
        }
        
        try {
            // 确保服务运行
            Log.d("MainActivity", "确保服务运行...")
            ensureServiceRunning()
            
            // 检查服务是否运行
            val serviceRunning = isServiceRunning()
            Log.d("MainActivity", "服务运行状态: $serviceRunning")
            
            if (serviceRunning) {
            // 服务已运行，优化：减少延迟时间
            Log.d("MainActivity", "服务已运行，准备创建 MediaProjection...")
            Handler(Looper.getMainLooper()).postDelayed({
                try {
                    // 再次确保服务运行
                    ensureServiceRunning()
                    // 优化：减少等待时间，从 1 秒减少到 300ms
                    Handler(Looper.getMainLooper()).postDelayed({
                        try {
                            Log.d("MainActivity", "开始创建 MediaProjection，resultCode=$resultCode")
                            mediaProjection = mediaProjectionManager.getMediaProjection(resultCode, data)
                            Log.d("MainActivity", "✅ MediaProjection 创建成功")
                            
                            // 重置错误计数器（成功创建MediaProjection）
                            captureErrorCount = 0
                            
                            // Android 14+ 需要注册 MediaProjection.Callback 来处理 onStop()
                            if (Build.VERSION.SDK_INT >= 34) {
                                Log.d("MainActivity", "注册 MediaProjection.Callback (Android 14+)")
                                mediaProjection?.registerCallback(object : MediaProjection.Callback() {
                                    override fun onStop() {
                                        Log.d("MainActivity", "⚠️ MediaProjection 已停止（正常行为：获取图像后系统会自动停止）")
                                        Log.d("MainActivity", "停止时状态: isCapturing=$isCapturing, virtualDisplay=${virtualDisplay != null}, imageReader=${imageReader != null}")
                                        
                                        // 清理资源
                                        try {
                                            virtualDisplay?.release()
                                            virtualDisplay = null
                                            imageReader?.close()
                                            imageReader = null
                                            isCapturing = false
                                            
                                            // Android 14+：MediaProjection 已停止，清理实例
                                            // 注意：Android 14+ 不能重用 resultData，下次捕获需要重新请求权限
                                            if (Build.VERSION.SDK_INT >= 34) {
                                                Log.d("MainActivity", "Android 14+，MediaProjection 已停止，已清理实例（下次需要重新请求权限）")
                                                mediaProjection = null
                                            }
                                            
                                            Log.d("MainActivity", "✅ 资源已清理")
                                        } catch (e: Exception) {
                                            Log.e("MainActivity", "清理资源时异常", e)
                                        }
                                    }
                                }, Handler(Looper.getMainLooper()))
                            }
                            
                            Log.d("MainActivity", "开始捕获屏幕...")
                            isRequestingCapture = false // 重置标志
                            captureScreen()
                        } catch (e: SecurityException) {
                            Log.e("MainActivity", "创建 MediaProjection 时 SecurityException，retryCount=$retryCount", e)
                            // 如果还是失败，可能是 HarmonyOS 的特殊要求，尝试更长的延迟
                            if (retryCount < 10) {
                                Log.d("MainActivity", "SecurityException，重试中...")
                                Handler(Looper.getMainLooper()).postDelayed({
                                    waitForServiceAndStart(resultCode, data, retryCount + 1)
                                }, 500) // 从 1 秒减少到 500ms
                            } else {
                                Log.e("MainActivity", "SecurityException，重试次数已达上限")
                                runOnUiThread {
                                    Toast.makeText(this, "需要前台服务才能捕获屏幕，请检查通知权限", Toast.LENGTH_LONG).show()
                                }
                                e.printStackTrace()
                            }
                        } catch (e: Exception) {
                            Log.e("MainActivity", "创建 MediaProjection 时异常", e)
                            runOnUiThread {
                                Toast.makeText(this, "创建 MediaProjection 失败: ${e.message}", Toast.LENGTH_LONG).show()
                            }
                            e.printStackTrace()
                        }
                    }, 300) // 从 1 秒减少到 300ms
                } catch (e: Exception) {
                    Toast.makeText(this, "启动失败: ${e.message}", Toast.LENGTH_LONG).show()
                    e.printStackTrace()
                }
            }, 200) // 从 500ms 减少到 200ms
        } else {
            // 服务未运行，继续等待
            Log.d("MainActivity", "服务未运行，等待中... (retryCount=$retryCount)")
            Handler(Looper.getMainLooper()).postDelayed({
                waitForServiceAndStart(resultCode, data, retryCount + 1)
            }, 300) // 从 500ms 减少到 300ms
        }
        } catch (e: Exception) {
            Log.e("MainActivity", "❌ waitForServiceAndStart 外层异常，retryCount=$retryCount", e)
            if (retryCount < 5) {
                // 重试
                Handler(Looper.getMainLooper()).postDelayed({
                    waitForServiceAndStart(resultCode, data, retryCount + 1)
                }, 500)
            } else {
                runOnUiThread {
                    Toast.makeText(this, "启动屏幕捕获失败: ${e.message}", Toast.LENGTH_LONG).show()
                }
                e.printStackTrace()
            }
        }
        
        Log.d("MainActivity", "========== waitForServiceAndStart 结束 ==========")
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        
        mediaProjectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        screenCapture = ScreenCapture(this)
        ocrRecognizer = OcrRecognizerFactory.create(this)
        
        // 初始化Vision-Language模型管理器
        visionLanguageManager = VisionLanguageManager(this)
        
        // 检查模型文件是否存在，并尝试加载（用于测试）
        // TODO: VL推理测试已OK，暂时注释掉，集中修复捕获屏幕权限问题
        /*
        coroutineScope.launch {
            val isAvailable = visionLanguageManager?.isModelAvailable() ?: false
            if (isAvailable) {
                Log.d("MainActivity", "VL模型文件已就绪，开始测试加载...")
                // 测试加载模型，查看模型结构信息
                val loaded = visionLanguageManager?.loadModel { progress ->
                    Log.d("MainActivity", "模型加载进度: $progress%")
                } ?: false
                if (loaded) {
                    Log.d("MainActivity", "✅ VL模型加载成功！可以查看日志了解模型结构")
                    // 测试 vision_encoder 推理（使用一张测试截图）
                    testVisionEncoderInference()
                } else {
                    Log.e("MainActivity", "❌ VL模型加载失败，请检查日志")
                }
            } else {
                Log.w("MainActivity", "VL模型文件不存在，将使用OCR作为降级方案")
            }
        }
        */
        
        setContent {
            TestWingsTheme {
                var ocrResultState by remember { mutableStateOf<OcrResult?>(null) }
                var screenStateResult by remember { mutableStateOf<ScreenState?>(null) }
                
                // 保存更新函数，供Activity使用
                onOcrResultUpdate = { result ->
                    ocrResultState = result
                }
                onScreenStateUpdate = { result ->
                    screenStateResult = result
                }
                
                MainScreen(
                    onCaptureClick = { requestScreenCapture() },
                    onOpenAccessibilitySettings = { openAccessibilitySettings() },
                    ocrResult = ocrResultState,
                    onOcrResultChange = { 
                        ocrResultState = it
                        onOcrResultUpdate = null // 清除引用
                    },
                    screenState = screenStateResult,
                    onScreenStateChange = {
                        screenStateResult = it
                        onScreenStateUpdate = null // 清除引用
                    }
                )
            }
        }
    }
    
    /**
     * 检查无障碍服务是否已启用
     */
    private fun checkAccessibilityServiceEnabled(): Boolean {
        return TestWingsAccessibilityService.isServiceEnabled(this)
    }
    
    /**
     * 打开无障碍设置页面
     */
    private fun openAccessibilitySettings() {
        try {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            startActivity(intent)
            Toast.makeText(
                this,
                "请找到并启用 TestWings 无障碍服务",
                Toast.LENGTH_LONG
            ).show()
        } catch (e: Exception) {
            Log.e("MainActivity", "无法打开无障碍设置", e)
            Toast.makeText(
                this,
                "无法打开无障碍设置，请手动前往：设置 → 辅助功能 → TestWings",
                Toast.LENGTH_LONG
            ).show()
        }
    }
    
    private fun requestScreenCapture() {
        // 防止重复请求
        if (isRequestingCapture) {
            Log.d("MainActivity", "屏幕捕获请求正在进行中，忽略重复请求")
            return
        }
        
        try {
            isRequestingCapture = true
            Log.d("MainActivity", "请求屏幕捕获，Android版本=${Build.VERSION.SDK_INT}")
            
            // 统一使用 AccessibilityService 进行屏幕捕获
            // 检查无障碍服务是否已启用
            if (!checkAccessibilityServiceEnabled()) {
                Log.w("MainActivity", "⚠️ 无障碍服务未启用，需要先启用")
                isRequestingCapture = false
                runOnUiThread {
                    Toast.makeText(
                        this,
                        "请先启用 TestWings 无障碍服务才能捕获屏幕",
                        Toast.LENGTH_LONG
                    ).show()
                    // 延迟打开设置页面，避免Toast被遮挡
                    Handler(Looper.getMainLooper()).postDelayed({
                        openAccessibilitySettings()
                    }, 1000)
                }
                return
            }
            
            // 无障碍服务已启用，直接进行截图
            Log.d("MainActivity", "✅ 无障碍服务已启用，开始捕获屏幕")
            isRequestingCapture = false
            captureScreenWithAccessibilityService()
            return
            
            // Android 14+ (API 34+) 严格限制：每次捕获都需要重新请求权限，不能重用 resultData
            // 这是系统安全限制，无法绕过
            if (Build.VERSION.SDK_INT < 34) {
                // Android 13 及以下可以重用 MediaProjection 实例
                if (mediaProjection != null) {
                    Log.d("MainActivity", "检测到已有的 MediaProjection 实例（Android 13-），尝试使用")
                    isRequestingCapture = false
                    captureScreen()
                    return
                }
            } else {
                // Android 14+：每次都需要重新请求权限，不能重用权限结果
                // 即使有 MediaProjection 实例，也可能已失效，需要重新请求
                if (mediaProjection != null) {
                    Log.d("MainActivity", "Android 14+，检测到 MediaProjection 实例，尝试使用（如果失效会自动重新请求权限）")
                    isRequestingCapture = false
                    captureScreen()
                    return
                }
            }
            
            // Android 15+ 要求前台服务必须运行
            // 检查服务是否已经在运行
            if (isServiceRunning()) {
                Log.d("MainActivity", "前台服务已运行，直接请求权限")
                // Android 15+ 提示用户必须选择"整个屏幕"
                if (Build.VERSION.SDK_INT >= 35) {
                    runOnUiThread {
                        Toast.makeText(
                            this,
                            "请选择「整个屏幕」以进行屏幕捕获",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
                // 服务已在运行，立即显示授权弹窗（无需等待）
                val intent = mediaProjectionManager.createScreenCaptureIntent()
                screenCaptureLauncher.launch(intent)
                return
            }
            
            // 服务未运行，先启动服务
            Log.d("MainActivity", "前台服务未运行，先启动服务")
            val serviceIntent = Intent(applicationContext, ScreenCaptureService::class.java)
            try {
                // 使用 ContextCompat.startForegroundService 更可靠
                ContextCompat.startForegroundService(applicationContext, serviceIntent)
                Log.d("MainActivity", "前台服务启动命令已发送")
            } catch (e: IllegalStateException) {
                Log.w("MainActivity", "startForegroundService 失败，尝试普通启动", e)
                // 如果失败，尝试普通启动
                try {
                    applicationContext.startService(serviceIntent)
                } catch (e2: Exception) {
                    Log.e("MainActivity", "启动服务失败", e2)
                    Toast.makeText(this, "启动前台服务失败: ${e2.message}", Toast.LENGTH_LONG).show()
                    return
                }
            } catch (e: Exception) {
                Log.e("MainActivity", "启动服务异常", e)
                Toast.makeText(this, "启动服务异常: ${e.message}", Toast.LENGTH_LONG).show()
                return
            }
            
            // 优化：减少延迟时间，前台服务启动通常很快（onCreate 中立即调用 startForeground）
            Handler(Looper.getMainLooper()).postDelayed({
                try {
                    // 检查服务是否运行
                    if (isServiceRunning()) {
                        Log.d("MainActivity", "服务已运行，请求权限")
                        // 服务已运行，短暂等待确保系统识别到服务（减少延迟）
                        Handler(Looper.getMainLooper()).postDelayed({
                            try {
                                // Android 15+ 提示用户必须选择"整个屏幕"
                                if (Build.VERSION.SDK_INT >= 35) {
                                    runOnUiThread {
                                        Toast.makeText(
                                            this@MainActivity,
                                            "请选择「整个屏幕」以进行屏幕捕获",
                                            Toast.LENGTH_LONG
                                        ).show()
                                    }
                                }
                                val intent = mediaProjectionManager.createScreenCaptureIntent()
                                screenCaptureLauncher.launch(intent)
                            } catch (e: Exception) {
                                Log.e("MainActivity", "启动权限请求失败", e)
                                Toast.makeText(this@MainActivity, "启动权限请求失败: ${e.message}", Toast.LENGTH_LONG).show()
                            }
                        }, 200) // 从 1.5 秒减少到 200ms
                    } else {
                        Log.w("MainActivity", "服务未运行，但仍尝试请求权限")
                        // 服务未运行，提示用户
                        Toast.makeText(this@MainActivity, "前台服务启动失败，请检查通知权限", Toast.LENGTH_LONG).show()
                        // 仍然尝试请求权限（Android 15 可能仍然允许）
                        try {
                            // Android 15+ 提示用户必须选择"整个屏幕"
                            if (Build.VERSION.SDK_INT >= 35) {
                                runOnUiThread {
                                    Toast.makeText(
                                        this@MainActivity,
                                        "请选择「整个屏幕」以进行屏幕捕获",
                                        Toast.LENGTH_LONG
                                    ).show()
                                }
                            }
                            val intent = mediaProjectionManager.createScreenCaptureIntent()
                            screenCaptureLauncher.launch(intent)
                        } catch (e: Exception) {
                            Log.e("MainActivity", "启动权限请求失败", e)
                            Toast.makeText(this@MainActivity, "启动权限请求失败: ${e.message}", Toast.LENGTH_LONG).show()
                        }
                    }
                } catch (e: Exception) {
                    Log.e("MainActivity", "延迟启动权限请求时异常", e)
                    Toast.makeText(this@MainActivity, "启动权限请求异常: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }, 300) // 从 2 秒减少到 300ms
        } catch (e: Exception) {
            Log.e("MainActivity", "requestScreenCapture 异常", e)
            Toast.makeText(this, "请求屏幕捕获失败: ${e.message}", Toast.LENGTH_LONG).show()
            e.printStackTrace()
        } finally {
            // 在权限请求完成后重置标志（延迟重置，给权限弹窗时间）
            Handler(Looper.getMainLooper()).postDelayed({
                isRequestingCapture = false
            }, 2000) // 2秒后重置，给权限弹窗足够时间
        }
    }
    
    private fun captureScreen() {
        Log.d("MainActivity", "========== captureScreen 开始 ==========")
        Log.d("MainActivity", "isCapturing=$isCapturing, mediaProjection=${mediaProjection != null}")
        
        // 如果正在截图，忽略新的截图请求
        if (isCapturing) {
            Log.d("MainActivity", "⚠️ 正在截图，忽略重复请求")
            return
        }
        
        // 检查 MediaProjection 是否有效
        if (mediaProjection == null) {
            Log.e("MainActivity", "❌ MediaProjection 为 null，无法截图")
            // Android 14+ 每次都需要重新请求权限，这是系统限制
            runOnUiThread {
                Toast.makeText(this, "MediaProjection 未初始化，请先授权", Toast.LENGTH_SHORT).show()
            }
            // 自动请求授权
            Handler(Looper.getMainLooper()).postDelayed({
                requestScreenCapture()
            }, 500)
            return
        }
        
        // 确保前台服务正在运行（Android 15 要求）
        if (!isServiceRunning()) {
            Log.w("MainActivity", "⚠️ 前台服务未运行，尝试启动...")
            ensureServiceRunning()
            // 等待服务启动
            Handler(Looper.getMainLooper()).postDelayed({
                if (isServiceRunning()) {
                    Log.d("MainActivity", "✅ 服务已启动，继续截图")
                    captureScreen()
                } else {
                    Log.e("MainActivity", "❌ 服务启动失败，无法截图")
                    Toast.makeText(this, "前台服务启动失败，无法截图", Toast.LENGTH_LONG).show()
                }
            }, 500)
            return
        }
        
        // 清理之前的资源
        Log.d("MainActivity", "清理之前的资源...")
        virtualDisplay?.release()
        virtualDisplay = null
        imageReader?.close()
        imageReader = null
        
        // 设置截图标志
        isCapturing = true
        Log.d("MainActivity", "isCapturing 已设置为 true")
        
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
        
        Log.d("MainActivity", "屏幕尺寸: ${width}x${height}, density=$density")
        
        // 将 buffer 改为 1，只缓存1帧图像，避免多帧导致多张截图
        try {
            imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 1)
            Log.d("MainActivity", "✅ ImageReader 创建成功: ${width}x${height}")
        } catch (e: Exception) {
            Log.e("MainActivity", "❌ ImageReader 创建失败", e)
            isCapturing = false
            Toast.makeText(this, "ImageReader 创建失败: ${e.message}", Toast.LENGTH_LONG).show()
            return
        }
        
        // 创建 VirtualDisplay（Android 15 要求前台服务必须正在运行）
        // 注意：有时需要短暂延迟以确保服务完全就绪
        try {
            Log.d("MainActivity", "开始创建 VirtualDisplay...")
            Log.d("MainActivity", "MediaProjection 状态: ${mediaProjection != null}")
            Log.d("MainActivity", "ImageReader surface: ${imageReader?.surface != null}")
            Log.d("MainActivity", "前台服务运行状态: ${isServiceRunning()}")
            
            // Android 15 有时需要短暂延迟以确保服务完全就绪
            // 如果第一次创建失败，重试一次（最多重试2次）
            var retryCount = 0
            var success = false
            
            while (retryCount < 2 && !success) {
                if (retryCount > 0) {
                    Log.d("MainActivity", "VirtualDisplay 创建重试，retryCount=$retryCount，等待100ms...")
                    // 短暂延迟后重试（在主线程上等待）
                    try {
                        Thread.sleep(100)
                    } catch (e: InterruptedException) {
                        Log.w("MainActivity", "等待被中断", e)
                    }
                }
                
                virtualDisplay = mediaProjection?.createVirtualDisplay(
                    "ScreenCapture",
                    width, height, density,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    imageReader?.surface, null, null
                )
                
                if (virtualDisplay != null) {
                    Log.d("MainActivity", "✅ VirtualDisplay 创建成功 (尝试 ${retryCount + 1})")
                    success = true
                } else {
                    retryCount++
                    if (retryCount < 2) {
                        Log.w("MainActivity", "⚠️ VirtualDisplay 创建返回 null，准备重试 (尝试 $retryCount)")
                    } else {
                        Log.e("MainActivity", "❌ VirtualDisplay 创建失败（返回 null，已重试 $retryCount 次）")
                    }
                }
            }
            
            if (!success) {
                isCapturing = false
                Toast.makeText(this, "VirtualDisplay 创建失败，请重试", Toast.LENGTH_LONG).show()
                return
            }
        } catch (e: SecurityException) {
            Log.e("MainActivity", "❌ 创建 VirtualDisplay 时 SecurityException", e)
            
            // 检查错误类型
            val errorMessage = e.message ?: ""
            
            // Android 14+ 的关键错误：不能重用 resultData
            if (errorMessage.contains("re-use the resultData") || errorMessage.contains("re-use")) {
                Log.w("MainActivity", "⚠️ Android 14+ 限制：不能重用 resultData，每次捕获都需要重新请求权限")
                
                // 清理资源
                try {
                    mediaProjection?.stop()
                } catch (stopException: Exception) {
                    Log.w("MainActivity", "停止 MediaProjection 时异常（可忽略）", stopException)
                }
                mediaProjection = null
                isCapturing = false
                
                // 重置错误计数器（这是正常的 Android 14+ 限制）
                captureErrorCount = 0
                
                // 提示用户需要重新授权（Android 14+ 的限制）
                runOnUiThread {
                    if (Build.VERSION.SDK_INT >= 34) {
                        Toast.makeText(
                            this,
                            "Android 14+ 需要每次捕获都重新授权，这是系统安全限制",
                            Toast.LENGTH_LONG
                        ).show()
                    } else {
                        Toast.makeText(
                            this,
                            "屏幕捕获权限已失效，请重新授权",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
                
                // 不自动重新请求，让用户手动触发（避免无限循环和重复弹窗）
                Log.d("MainActivity", "需要用户手动重新请求权限（避免自动循环）")
                return
            }
            
            // 其他 MediaProjection 相关的错误
            if (errorMessage.contains("non-current MediaProjection") || errorMessage.contains("MediaProjection")) {
                Log.w("MainActivity", "⚠️ MediaProjection 已失效")
                
                // 清理失效的 MediaProjection
                try {
                    mediaProjection?.stop()
                } catch (stopException: Exception) {
                    Log.w("MainActivity", "停止失效的 MediaProjection 时异常（可忽略）", stopException)
                }
                mediaProjection = null
                isCapturing = false
                
                // Android 14+ 上，MediaProjection 只能用于一次捕获会话
                if (Build.VERSION.SDK_INT >= 34) {
                    Log.d("MainActivity", "Android 14+，MediaProjection 已失效，需要重新请求权限")
                    // 重置错误计数器
                    captureErrorCount = 0
                } else {
                    // Android 13 及以下，提示用户权限失效
                    runOnUiThread {
                        Toast.makeText(
                            this,
                            "屏幕捕获权限已失效，请重新授权",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
                
                // 不自动重新请求，让用户手动触发（避免无限循环）
                Log.d("MainActivity", "需要用户手动重新请求权限（避免自动循环）")
                return
            } else {
                // 其他 SecurityException：可能是选择了"单个应用"模式或其他权限问题
                isCapturing = false
                captureErrorCount++
                
                Log.w("MainActivity", "⚠️ SecurityException（非 non-current MediaProjection），错误计数: $captureErrorCount/$MAX_CAPTURE_ERROR_COUNT")
                
                // 检查是否可能是选择了"单个应用"模式
                if (captureErrorCount >= MAX_CAPTURE_ERROR_COUNT) {
                    // 错误次数过多，可能是选择了"单个应用"模式，提示用户
                    runOnUiThread {
                        Toast.makeText(
                            this,
                            "请选择「整个屏幕」而非「单个应用」\nTestWings需要捕获整个屏幕才能正常工作",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                    // 重置错误计数器，避免一直提示
                    captureErrorCount = 0
                    // 不自动重新请求，让用户手动操作
                    isRequestingCapture = false
                } else {
                    // 其他 SecurityException（如前台服务未运行）
                    runOnUiThread {
                        Toast.makeText(this, "创建 VirtualDisplay 失败: 需要前台服务运行", Toast.LENGTH_LONG).show()
                    }
                    // 继续尝试（可能是临时问题）
                }
            }
            e.printStackTrace()
            return
        } catch (e: Exception) {
            Log.e("MainActivity", "❌ 创建 VirtualDisplay 时异常", e)
            isCapturing = false
            runOnUiThread {
                Toast.makeText(this, "创建 VirtualDisplay 失败: ${e.message}", Toast.LENGTH_LONG).show()
            }
            e.printStackTrace()
            return
        }
        
        Log.d("MainActivity", "设置 ImageReader 监听器...")
        imageReader?.setOnImageAvailableListener({ reader ->
            Log.d("MainActivity", "📸 图像可用回调触发，isCapturing=$isCapturing")
            
            // 如果已经处理过图像，忽略后续的图像（防止多帧导致多张截图）
            if (!isCapturing) {
                Log.d("MainActivity", "⚠️ 已处理过图像，忽略后续图像")
                return@setOnImageAvailableListener
            }
            
            val image = reader.acquireLatestImage()
            if (image != null) {
                Log.d("MainActivity", "✅ 获取到图像: ${image.width}x${image.height}")
                // 立即设置标志为 false，防止重复处理
                isCapturing = false
                Log.d("MainActivity", "isCapturing 已设置为 false")
                val bitmap = imageToBitmap(image)
                image.close()
                
                // 重置错误计数器（成功获取图像）
                captureErrorCount = 0
                
                // 检查图像有效性（Android 15+ 如果选择了"单个应用"可能获取到异常图像）
                if (Build.VERSION.SDK_INT >= 35) {
                    val isValidImage = isValidCaptureBitmap(bitmap)
                    if (!isValidImage) {
                        Log.w("MainActivity", "⚠️ 检测到异常图像，可能是选择了「单个应用」模式")
                        runOnUiThread {
                            Toast.makeText(
                                this@MainActivity,
                                "检测到异常图像，请选择「整个屏幕」而非「单个应用」",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                        // 不进行后续处理
                        return@setOnImageAvailableListener
                    }
                }
                
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
                            
                            // 测试VL模型推理（如果模型已加载）
                            // TODO: VL推理测试已OK，暂时注释掉，集中修复捕获屏幕权限问题
                            /*
                            visionLanguageManager?.let { vlm ->
                                try {
                                    Log.d("MainActivity", "🧪 开始测试VL模型推理（使用实际截图）...")
                                    val screenState = vlm.understand(bitmap)
                                    Log.d("MainActivity", "✅ VL模型推理完成: vlAvailable=${screenState.vlAvailable}, elements=${screenState.elements.size}")
                                } catch (e: Exception) {
                                    Log.e("MainActivity", "❌ VL模型推理测试失败", e)
                                    e.printStackTrace()
                                }
                            }
                            */
                            
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
                Log.w("MainActivity", "⚠️ 图像为 null，重置标志")
                isCapturing = false
            }
        }, Handler(Looper.getMainLooper()))
        
        Log.d("MainActivity", "========== captureScreen 设置完成 ==========")
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
     * 检查捕获的图像是否有效
     * 在Android 15+上，如果选择了"单个应用"模式，可能会获取到异常图像（如黑屏、全黑等）
     * @param bitmap 待检查的图像
     * @return true 如果图像有效，false 如果图像异常
     */
    private fun isValidCaptureBitmap(bitmap: Bitmap): Boolean {
        try {
            // 检查图像尺寸是否合理（至少应该是设备屏幕的合理尺寸）
            if (bitmap.width < 100 || bitmap.height < 100) {
                Log.w("MainActivity", "图像尺寸过小: ${bitmap.width}x${bitmap.height}")
                return false
            }
            
            // 检查图像是否全黑或接近全黑（采样检查，提高性能）
            val sampleSize = 20 // 采样间隔
            var nonBlackPixelCount = 0
            var totalSampleCount = 0
            
            for (y in 0 until bitmap.height step sampleSize) {
                for (x in 0 until bitmap.width step sampleSize) {
                    totalSampleCount++
                    val pixel = bitmap.getPixel(x, y)
                    // 检查像素是否非黑色（RGB值都小于30认为是黑色）
                    val r = (pixel shr 16) and 0xFF
                    val g = (pixel shr 8) and 0xFF
                    val b = pixel and 0xFF
                    if (r > 30 || g > 30 || b > 30) {
                        nonBlackPixelCount++
                    }
                }
            }
            
            // 如果非黑色像素占比小于5%，认为图像异常（可能是黑屏）
            val nonBlackRatio = nonBlackPixelCount.toFloat() / totalSampleCount
            if (nonBlackRatio < 0.05f) {
                Log.w("MainActivity", "检测到异常图像（可能为黑屏），非黑色像素占比: ${nonBlackRatio * 100}%")
                return false
            }
            
            return true
        } catch (e: Exception) {
            Log.e("MainActivity", "检查图像有效性时异常", e)
            // 异常时默认认为有效，避免误判
            return true
        }
    }
    
    /**
     * 触发截图并等待OCR结果（用于测试用例执行）
     * 这是一个 suspend 函数，会等待OCR识别完成
     */
    suspend fun triggerScreenshotAndWaitForOcr(): OcrResult? {
        // 切换到IO线程进行等待，避免阻塞主线程（ANR保护）
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            // 重置状态（在开始新的截图前清理旧结果，释放内存）
            // 注意：这里清理旧的VL识别结果是为了释放内存，新的截图后会有新的识别结果
            synchronized(this@MainActivity) {
                pendingOcrResult = null
                ocrResultReady = false
                pendingScreenState = null
                screenStateReady = false
            }
            // 提示系统进行垃圾回收（不能强制，但可以建议）
            System.gc()
            
            // 先检查前置条件（在IO线程检查，减少主线程占用）
            val canCapture = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                // 检查无障碍服务是否启用
                if (!checkAccessibilityServiceEnabled()) {
                    Log.w("MainActivity", "无障碍服务未启用，无法截图")
                    return@withContext false
                }
                
                // 检查服务实例是否可用
                val accessibilityService = TestWingsAccessibilityService.getInstance()
                if (accessibilityService == null) {
                    Log.w("MainActivity", "AccessibilityService 实例为 null，无法截图")
                    return@withContext false
                }
                
                // 检查 Android 版本
                if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q) {
                    Log.w("MainActivity", "Android 版本过低，无法使用 AccessibilityService 截图")
                    return@withContext false
                }
                
                // 前置条件都满足，触发截图（异步执行，不会阻塞）
                try {
                    captureScreenWithAccessibilityService()
                    true
                } catch (e: Exception) {
                    Log.e("MainActivity", "触发截图失败", e)
                    false
                }
            }
            
            // 如果无法截图，直接返回null
            if (!canCapture) {
                Log.w("MainActivity", "无法触发截图，返回null")
                return@withContext null
            }
            
            // 等待OCR和VL识别结果（在IO线程上等待，不阻塞主线程）
            // OCR通常很快（<1秒），VL识别需要30-60秒（视觉编码器推理）
            // 对于测试用例执行，我们需要等待VL识别完成以便进行元素定位和验证
            
            // 等待OCR完成（通常很快，最多等待10秒）
            var ocrRetryCount = 0
            val maxOcrRetries = 20 // 20 * 500ms = 10秒（OCR通常<1秒）
            while (!ocrResultReady && ocrRetryCount < maxOcrRetries) {
                kotlinx.coroutines.delay(500)
                ocrRetryCount++
                if (ocrRetryCount % 10 == 0) {
                    Log.d("MainActivity", "等待OCR识别完成... (${ocrRetryCount * 500}ms)")
                }
            }
            
            // 等待VL识别完成（需要更长时间，最多等待70秒，确保有足够时间）
            var vlRetryCount = 0
            val maxVlRetries = 140 // 140 * 500ms = 70秒（VL识别通常需要50-60秒）
            while (!screenStateReady && vlRetryCount < maxVlRetries) {
                kotlinx.coroutines.delay(500)
                vlRetryCount++
                if (vlRetryCount % 20 == 0) {
                    Log.d("MainActivity", "等待VL识别完成... (${vlRetryCount * 500}ms)")
                }
            }
        
            if (!ocrResultReady) {
                Log.w("MainActivity", "OCR识别超时（${ocrRetryCount * 500}ms），但继续执行")
            }
            if (!screenStateReady) {
                Log.w("MainActivity", "VL识别超时（${vlRetryCount * 500}ms），将使用OCR降级方案")
            } else {
                Log.d("MainActivity", "✅ OCR和VL识别均已完成（OCR: ${ocrRetryCount * 500}ms, VL: ${vlRetryCount * 500}ms）")
            }
            
            // 获取OCR结果（VL结果通过 getScreenState() 获取）
            synchronized(this@MainActivity) {
                val result = pendingOcrResult
                // 注意：不清空 pendingScreenState，保留给后续的元素定位使用
                pendingOcrResult = null
                ocrResultReady = false
                return@withContext result
            }
        }
    }
    
    /**
     * 获取当前的屏幕状态（VL模型识别结果）
     * 用于 TestExecutor 的元素定位和验证
     */
    fun getScreenState(): ScreenState? {
        return synchronized(this) {
            pendingScreenState
        }
    }
    
    /**
     * 使用 AccessibilityService 捕获屏幕
     * 这是统一使用的屏幕捕获方法，所有 Android 版本都使用此方法
     */
    private fun captureScreenWithAccessibilityService() {
        Log.d("MainActivity", "========== captureScreenWithAccessibilityService 开始 ==========")
        
        // 如果正在截图，忽略新的截图请求
        if (isCapturing) {
            Log.d("MainActivity", "⚠️ 正在截图，忽略重复请求")
            return
        }
        
        // 检查无障碍服务是否已启用
        if (!checkAccessibilityServiceEnabled()) {
            Log.e("MainActivity", "❌ 无障碍服务未启用")
            runOnUiThread {
                Toast.makeText(
                    this,
                    "无障碍服务未启用，请先启用 TestWings 无障碍服务",
                    Toast.LENGTH_SHORT
                ).show()
                openAccessibilitySettings()
            }
            return
        }
        
        // 获取 AccessibilityService 实例
        val accessibilityService = TestWingsAccessibilityService.getInstance()
        if (accessibilityService == null) {
            Log.e("MainActivity", "❌ AccessibilityService 实例为 null")
            runOnUiThread {
                Toast.makeText(
                    this,
                    "无障碍服务未启动，请确保已启用 TestWings 无障碍服务",
                    Toast.LENGTH_LONG
                ).show()
            }
            // 延迟重试，等待服务启动
            Handler(Looper.getMainLooper()).postDelayed({
                captureScreenWithAccessibilityService()
            }, 1000)
            return
        }
        
        // 检查 Android 版本，takeScreenshot 需要 Android Q (API 29) 或更高版本
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            Log.e("MainActivity", "❌ takeScreenshot 需要 Android Q (API 29) 或更高版本，当前版本: ${Build.VERSION.SDK_INT}")
            runOnUiThread {
                Toast.makeText(
                    this,
                    "屏幕捕获功能需要 Android 10.0 或更高版本",
                    Toast.LENGTH_LONG
                ).show()
            }
            return
        }
        
        // 设置截图标志
        isCapturing = true
        Log.d("MainActivity", "isCapturing 已设置为 true")
        
        // 在后台线程执行截图，避免阻塞主线程
        Thread {
            try {
                Log.d("MainActivity", "开始使用 AccessibilityService 截图...")
                val bitmap = accessibilityService.takeScreenshotSync()
                
                if (bitmap != null) {
                    Log.d("MainActivity", "✅ AccessibilityService 截图成功: ${bitmap.width}x${bitmap.height}")
                    
                    // 在主线程处理截图结果
                    runOnUiThread {
                        isCapturing = false
                        processCapturedBitmap(bitmap)
                    }
                } else {
                    Log.e("MainActivity", "❌ AccessibilityService 截图失败，返回 null")
                    runOnUiThread {
                        isCapturing = false
                        Toast.makeText(
                            this@MainActivity,
                            "截图失败，请重试",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            } catch (e: Exception) {
                Log.e("MainActivity", "❌ AccessibilityService 截图异常", e)
                runOnUiThread {
                    isCapturing = false
                    Toast.makeText(
                        this@MainActivity,
                        "截图失败: ${e.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }.start()
    }
    
    /**
     * 处理捕获到的 Bitmap（保存、OCR识别等）
     */
    private fun processCapturedBitmap(bitmap: Bitmap) {
        Log.d("MainActivity", "处理捕获的 Bitmap: ${bitmap.width}x${bitmap.height}")
        
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
                    
                    // 同时进行VL模型识别（用于元素定位和验证）
                    visionLanguageManager?.let { vlm ->
                        coroutineScope.launch {
                            try {
                                // 检查Activity是否还存在，如果已销毁则不执行
                                if (isFinishing || isDestroyed) {
                                    Log.w("MainActivity", "Activity已销毁，取消VL识别")
                                    return@launch
                                }
                                
                                Log.d("MainActivity", "开始VL模型识别...")
                                // VL识别开始，更新UI状态为"识别中"
                                // 检查Activity是否还存在，避免崩溃
                                if (!isFinishing && !isDestroyed) {
                                    runOnUiThread {
                                        try {
                                            if (!isFinishing && !isDestroyed) {
                                                onScreenStateUpdate?.invoke(
                                                    ScreenState(
                                                        elements = emptyList(),
                                                        semanticDescription = "VL模型识别中，请稍候...（约需30-60秒）",
                                                        vlAvailable = true
                                                    )
                                                )
                                            }
                                        } catch (e: Exception) {
                                            Log.e("MainActivity", "更新UI状态时异常", e)
                                        }
                                    }
                                }
                                
                                // 再次检查Activity状态，VL识别可能需要30-60秒
                                if (isFinishing || isDestroyed) {
                                    Log.w("MainActivity", "VL识别过程中Activity已销毁，取消UI更新")
                                    return@launch
                                }
                                
                                val screenState = vlm.understand(bitmap)
                                
                                // 再次检查Activity状态
                                if (isFinishing || isDestroyed) {
                                    Log.w("MainActivity", "VL识别完成后Activity已销毁，取消UI更新")
                                    return@launch
                                }
                                
                                // 如果VL识别结果elements为空，使用OCR结果转换为UIElement（快速方案）
                                val finalScreenState = if (screenState.elements.isEmpty()) {
                                    val ocrResultForConversion = synchronized(this@MainActivity) {
                                        pendingOcrResult
                                    }
                                    if (ocrResultForConversion != null && ocrResultForConversion.isSuccess) {
                                        // 将OCR结果转换为UIElement
                                        val elements = ocrResultForConversion.textBlocks.map { textBlock ->
                                            com.testwings.utils.UIElement(
                                                type = com.testwings.utils.UIElementType.TEXT,
                                                text = textBlock.text,
                                                bounds = textBlock.boundingBox,
                                                center = android.graphics.Point(
                                                    textBlock.boundingBox.centerX(),
                                                    textBlock.boundingBox.centerY()
                                                ),
                                                confidence = textBlock.confidence,
                                                semanticDescription = "OCR识别（临时VL降级方案）"
                                            )
                                        }
                                        Log.d("MainActivity", "VL识别elements为空，已将OCR结果转换为 ${elements.size} 个UIElement")
                                        screenState.copy(
                                            elements = elements,
                                            ocrResult = ocrResultForConversion,
                                            semanticDescription = screenState.semanticDescription + "（使用OCR降级方案转换为UIElement）"
                                        )
                                    } else {
                                        screenState
                                    }
                                } else {
                                    screenState
                                }
                                
                                synchronized(this@MainActivity) {
                                    pendingScreenState = finalScreenState
                                    screenStateReady = true
                                }
                                Log.d("MainActivity", "✅ VL模型识别完成: elements=${finalScreenState.elements.size}, vlAvailable=${finalScreenState.vlAvailable}")
                                
                                // VL识别完成后，更新UI显示结果（固定窗口显示，类似OCR识别结果）
                                // 检查Activity是否还存在，避免崩溃
                                if (!isFinishing && !isDestroyed) {
                                    runOnUiThread {
                                        try {
                                            if (!isFinishing && !isDestroyed) {
                                                // 更新UI状态（固定窗口显示）
                                                onScreenStateUpdate?.invoke(finalScreenState)
                                            }
                                        } catch (e: Exception) {
                                            Log.e("MainActivity", "更新UI状态时异常", e)
                                        }
                                    }
                                }
                            } catch (e: OutOfMemoryError) {
                                Log.e("MainActivity", "❌ VL模型识别内存不足（OOM）", e)
                                // VL识别失败时，设置一个空的 ScreenState（vlAvailable=false）
                                synchronized(this@MainActivity) {
                                    pendingScreenState = ScreenState(
                                        elements = emptyList(),
                                        semanticDescription = "识别失败: 内存不足（OOM），VL模型需要大量内存",
                                        vlAvailable = false
                                    )
                                    screenStateReady = true
                                }
                                
                                // 显示错误提示并更新UI状态（检查Activity状态）
                                if (!isFinishing && !isDestroyed) {
                                    runOnUiThread {
                                        try {
                                            if (!isFinishing && !isDestroyed) {
                                                val errorState = ScreenState(
                                                    elements = emptyList(),
                                                    semanticDescription = "识别失败: 内存不足（OOM）",
                                                    vlAvailable = false
                                                )
                                                onScreenStateUpdate?.invoke(errorState)
                                            }
                                        } catch (e: Exception) {
                                            Log.e("MainActivity", "更新UI状态时异常", e)
                                        }
                                    }
                                }
                            } catch (e: Exception) {
                                Log.e("MainActivity", "❌ VL模型识别失败", e)
                                e.printStackTrace()
                                // VL识别失败时，设置一个空的 ScreenState（vlAvailable=false）
                                synchronized(this@MainActivity) {
                                    pendingScreenState = ScreenState(
                                        elements = emptyList(),
                                        semanticDescription = "识别失败: ${e.message?.take(100) ?: "未知错误"}",
                                        vlAvailable = false
                                    )
                                    screenStateReady = true
                                }
                                
                                // 显示错误提示并更新UI状态（检查Activity状态）
                                if (!isFinishing && !isDestroyed) {
                                    runOnUiThread {
                                        try {
                                            if (!isFinishing && !isDestroyed) {
                                                val errorState = ScreenState(
                                                    elements = emptyList(),
                                                    semanticDescription = "识别失败: ${e.message?.take(100) ?: "未知错误"}",
                                                    vlAvailable = false
                                                )
                                                onScreenStateUpdate?.invoke(errorState)
                                            }
                                        } catch (e2: Exception) {
                                            Log.e("MainActivity", "更新UI状态时异常", e2)
                                        }
                                    }
                                }
                            }
                        }
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
                            "OCR识别失败: ${e.message}",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
        } ?: run {
            Log.w("MainActivity", "OCR识别器未初始化")
            runOnUiThread {
                Toast.makeText(
                    this,
                    "截图成功但OCR识别器未初始化",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }
    
    override fun onResume() {
        super.onResume()
        Log.d("MainActivity", "onResume: Activity已恢复")
        // 当Activity恢复时（例如从设置页面返回），刷新无障碍服务状态
        // 这会触发Compose重新检查状态
        // 注意：这里不需要手动刷新，因为MainScreen中的LaunchedEffect会在Activity恢复时重新执行
    }
    
    override fun onPause() {
        super.onPause()
        Log.d("MainActivity", "onPause: Activity已暂停")
    }
    
    override fun onStop() {
        super.onStop()
        Log.d("MainActivity", "onStop: Activity已停止")
        // 注意：不在这里停止 MediaProjection，保留它以便下次使用
    }
    
    /**
     * 测试 vision_encoder 推理功能
     * 在模型加载成功后自动调用，验证输入格式和输出是否正确
     */
    private fun testVisionEncoderInference() {
        coroutineScope.launch {
            try {
                Log.d("MainActivity", "🧪 开始测试 vision_encoder 推理功能...")
                
                // 如果已经有截图，使用最新的截图；否则创建一个测试图像
                val testBitmap = if (ocrResultState != null && screenCapture != null) {
                    // 尝试从保存的截图中加载（如果有的话）
                    // 这里先创建一个简单的测试图像
                    createTestBitmap()
                } else {
                    // 创建一个简单的测试图像（960x960，黑色背景）
                    createTestBitmap()
                }
                
                Log.d("MainActivity", "创建测试图像: ${testBitmap.width}x${testBitmap.height}")
                
                // 调用 understand 方法，这会触发 vision_encoder 推理
                val screenState = visionLanguageManager?.understand(testBitmap)
                
                if (screenState != null) {
                    Log.d("MainActivity", "✅ vision_encoder 推理测试成功！")
                    Log.d("MainActivity", "   输出状态: vlAvailable=${screenState.vlAvailable}")
                    Log.d("MainActivity", "   元素数量: ${screenState.elements.size}")
                } else {
                    Log.e("MainActivity", "❌ vision_encoder 推理测试失败：返回结果为空")
                }
            } catch (e: Exception) {
                Log.e("MainActivity", "❌ vision_encoder 推理测试异常", e)
                e.printStackTrace()
            }
        }
    }
    
    /**
     * 创建一个测试用的 Bitmap（960x960，用于测试 vision_encoder）
     */
    private fun createTestBitmap(): Bitmap {
        val width = 960
        val height = 960
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        
        // 填充为黑色背景
        bitmap.eraseColor(android.graphics.Color.BLACK)
        
        // 在中心绘制一个白色矩形（用于测试）
        val canvas = android.graphics.Canvas(bitmap)
        val paint = android.graphics.Paint().apply {
            color = android.graphics.Color.WHITE
            style = android.graphics.Paint.Style.FILL
        }
        val rectSize = 200
        val left = (width - rectSize) / 2
        val top = (height - rectSize) / 2
        canvas.drawRect(
            left.toFloat(),
            top.toFloat(),
            (left + rectSize).toFloat(),
            (top + rectSize).toFloat(),
            paint
        )
        
        return bitmap
    }
    
    override fun onDestroy() {
        super.onDestroy()
        Log.d("MainActivity", "onDestroy: Activity正在销毁")
        
        // 取消所有协程，避免在Activity销毁后执行UI操作
        coroutineScope.cancel()
        
        // 清理资源
        try {
            virtualDisplay?.release()
            virtualDisplay = null
            mediaProjection?.stop()
            mediaProjection = null
            imageReader?.close()
            imageReader = null
        } catch (e: Exception) {
            Log.e("MainActivity", "清理资源时异常", e)
        }
        
        // 清除回调引用，避免内存泄漏
        onOcrResultUpdate = null
        onScreenStateUpdate = null
        
        Log.d("MainActivity", "onDestroy: 资源清理完成")
    }
}

@Composable
fun MainScreen(
    onCaptureClick: () -> Unit,
    onOpenAccessibilitySettings: () -> Unit,
    ocrResult: OcrResult? = null,
    onOcrResultChange: (OcrResult?) -> Unit = {},
    screenState: ScreenState? = null,
    onScreenStateChange: (ScreenState?) -> Unit = {}
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
                onOcrResultChange = onOcrResultChange,
                screenState = screenState,
                onScreenStateChange = onScreenStateChange
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
    onOcrResultChange: (OcrResult?) -> Unit = {},
    screenState: ScreenState? = null,
    onScreenStateChange: (ScreenState?) -> Unit = {}
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
        
        // VL识别结果显示区域
        screenState?.let { state ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer
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
                            text = "VL模型识别结果",
                            style = MaterialTheme.typography.titleMedium
                        )
                        TextButton(
                            onClick = { onScreenStateChange(null) }
                        ) {
                            Text("清除")
                        }
                    }
                    
                    if (state.vlAvailable) {
                        if (state.elements.isNotEmpty()) {
                            Text(
                                text = "✅ 识别成功",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Text(
                                text = "识别到 ${state.elements.size} 个UI元素",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            
                            // 显示元素列表（可滚动）
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
                                    state.elements.take(10).forEachIndexed { index, element ->
                                        Text(
                                            text = "${index + 1}. [${element.type}] ${element.text} - (${element.centerX},${element.centerY})",
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                    }
                                    if (state.elements.size > 10) {
                                        Text(
                                            text = "... 还有 ${state.elements.size - 10} 个元素",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        } else {
                            Text(
                                text = "⏳ 视觉编码器推理完成",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            if (state.semanticDescription.isNotEmpty()) {
                                Text(
                                    text = state.semanticDescription,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    } else {
                        Text(
                            text = "❌ 识别失败或模型不可用",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        if (state.semanticDescription.isNotEmpty()) {
                            Text(
                                text = state.semanticDescription,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
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
