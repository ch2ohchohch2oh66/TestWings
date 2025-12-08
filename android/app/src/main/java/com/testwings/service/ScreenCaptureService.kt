package com.testwings.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

class ScreenCaptureService : Service() {
    
    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        // 立即启动前台服务（必须在 onCreate 中调用，5 秒内必须调用 startForeground）
        val notification = createNotification()
        try {
            startForeground(NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 确保服务在前台运行（每次启动命令都调用）
        val notification = createNotification()
        try {
            startForeground(NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return START_STICKY
    }
    
    override fun onBind(intent: Intent?): IBinder? {
        return null
    }
    
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = getSystemService(NotificationManager::class.java)
            
            // 检查渠道是否已存在
            val existingChannel = notificationManager.getNotificationChannel(CHANNEL_ID)
            if (existingChannel == null) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    "屏幕捕获服务",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "用于屏幕捕获的前台服务"
                    setShowBadge(false)
                }
                notificationManager.createNotificationChannel(channel)
            }
        }
    }
    
    private fun createNotification(): Notification {
        // 使用应用图标，如果失败则使用系统默认图标
        val iconId = try {
            resources.getIdentifier("ic_launcher", "mipmap", packageName).takeIf { it != 0 }
                ?: android.R.drawable.ic_menu_camera
        } catch (e: Exception) {
            android.R.drawable.ic_menu_camera
        }
        
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("TestWings 屏幕捕获")
            .setContentText("正在捕获屏幕...")
            .setSmallIcon(iconId)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true) // 设置为持续通知
            .setAutoCancel(false) // 不自动取消
            .build()
    }
    
    companion object {
        private const val CHANNEL_ID = "screen_capture_channel"
        private const val NOTIFICATION_ID = 1
    }
}

