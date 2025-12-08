package com.testwings.utils

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.Environment
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*

class ScreenCapture(private val context: Context) {
    
    /**
     * 保存 Bitmap 到文件
     */
    fun saveBitmap(bitmap: Bitmap, filename: String = "screenshot_${getTimestamp()}.png"): String? {
        return try {
            // 获取保存路径
            val picturesDir = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Android 10+ 使用应用专属目录
                context.getExternalFilesDir(Environment.DIRECTORY_PICTURES)
            } else {
                // Android 9 及以下使用公共目录
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
            }
            
            val testWingsDir = File(picturesDir, "TestWings")
            if (!testWingsDir.exists()) {
                testWingsDir.mkdirs()
            }
            
            val file = File(testWingsDir, filename)
            
            // 保存文件
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            
            file.absolutePath
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
    
    /**
     * 获取时间戳
     */
    private fun getTimestamp(): String {
        val sdf = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
        return sdf.format(Date())
    }
}

