package com.mahjong.assistant.capture

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.hardware.display.DisplayManager
import android.media.ImageReader
import android.media.projection.MediaProjectionManager
import android.os.*
import androidx.core.app.NotificationCompat
import com.mahjong.assistant.R
import com.mahjong.assistant.overlay.OverlayService
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * 前台服务 — 在 mediaProjection 上下文中执行截屏
 * Android 15+ 要求 MediaProjection 必须在 foregroundService 中运行
 */
class CaptureForegroundService : Service() {

    companion object {
        const val CHANNEL_ID = "mahjong_capture"
        const val NOTIFICATION_ID = 2
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_DATA_INTENT = "data_intent"
    }

    private var wakeLock: android.os.PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("雀魂助手")
            .setContentText("截屏中...")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        // 保持屏幕常亮
        val pm = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        wakeLock = pm.newWakeLock(
            android.os.PowerManager.SCREEN_BRIGHT_WAKE_LOCK or android.os.PowerManager.ON_AFTER_RELEASE,
            "mahjong:capture"
        )
        wakeLock?.acquire(10000)

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, -1) ?: -1
        val dataIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent?.getParcelableExtra(EXTRA_DATA_INTENT, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra(EXTRA_DATA_INTENT)
        }

        if (resultCode != Activity.RESULT_OK || dataIntent == null) {
            updateOverlay("● 截屏参数错误 (code=$resultCode)")
            stopSelf()
            return START_NOT_STICKY
        }

        // Android 15: startForeground是异步的，等系统确认前台服务状态后再创建MediaProjection
        Handler(Looper.getMainLooper()).postDelayed({
            capture(resultCode, dataIntent)
        }, 350)
        return START_STICKY
    }

    private fun capture(resultCode: Int, data: Intent) {
        try {
            val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val projection = projectionManager.getMediaProjection(resultCode, data)

            if (projection == null) {
                updateOverlay("● MediaProjection创建失败")
                stopSelf()
                return
            }

            val metrics = resources.displayMetrics
            val width = metrics.widthPixels
            val height = metrics.heightPixels
            val density = metrics.densityDpi

            val imageReader = ImageReader.newInstance(width, height, android.graphics.PixelFormat.RGBA_8888, 2)

            projection.registerCallback(object : android.media.projection.MediaProjection.Callback() {
                override fun onStop() {
                    updateOverlay("● 截屏被系统终止")
                    stopSelf()
                }
            }, Handler(Looper.getMainLooper()))

            val virtualDisplay = projection.createVirtualDisplay(
                "mahjong-capture",
                width, height, density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader.surface, null, null
            )

            Handler(Looper.getMainLooper()).postDelayed({
                try {
                    val image = imageReader.acquireLatestImage()
                    if (image != null) {
                        val planes = image.planes
                        val buffer = planes[0].buffer
                        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                        bitmap.copyPixelsFromBuffer(buffer)
                        image.close()

                        val (results, _) = TileMatcher.recognize(bitmap)
                        bitmap.recycle()

                        if (results.size >= 13) {
                            val hand = results.map { it.tileId }.toIntArray()
                            sendToOverlay(hand)
                            verifyWithTenhou(hand)
                        } else {
                            updateOverlay("● 检测到${results.size}张牌")
                        }
                    } else {
                        updateOverlay("● 截屏无数据，请重试")
                    }
                } catch (e: Exception) {
                    updateOverlay("● 异常: ${e.message?.take(30)}")
                } finally {
                    try { virtualDisplay.release() } catch (_: Exception) {}
                    try { imageReader.close() } catch (_: Exception) {}
                    try { projection.stop() } catch (_: Exception) {}
                    stopSelf()
                }
            }, 800)
        } catch (e: SecurityException) {
            updateOverlay("● 安全异常: ${e.message?.take(40)}")
            stopSelf()
        } catch (e: Exception) {
            updateOverlay("● 截屏失败: ${e.message?.take(30)}")
            stopSelf()
        }
    }

    // ─── 天凤API ───

    private fun verifyWithTenhou(hand: IntArray) {
        Thread {
            try {
                val tenhouStr = toTenhouString(hand)
                if (tenhouStr.isEmpty()) return@Thread
                val url = URL("https://tenhou.net/2/?$tenhouStr")
                val conn = url.openConnection() as HttpURLConnection
                conn.connectTimeout = 5000
                conn.readTimeout = 5000
                conn.setRequestProperty("User-Agent", "MahjongAssistant/2.3")
                val reader = BufferedReader(InputStreamReader(conn.inputStream, "UTF-8"))
                val response = reader.readText()
                reader.close()
                conn.disconnect()
                val lines = response.split("\n").take(3).joinToString("\n")
                if (lines.isNotBlank()) {
                    updateOverlay("● 天凤: $lines")
                }
            } catch (_: Exception) {}
        }.start()
    }

    private fun toTenhouString(hand: IntArray): String {
        val counts = IntArray(34)
        for (id in hand) if (id in 0..33) counts[id]++
        val sb = StringBuilder()
        for (suit in 0..3) {
            val c = charArrayOf('m', 'p', 's', 'z')[suit]
            var part = ""
            val start = suit * 9
            val end = if (suit == 3) 7 else 9
            for (n in 0 until end) {
                val cnt = counts[start + n]
                if (cnt > 0) part += (n + 1).toString().repeat(cnt)
            }
            if (part.isNotEmpty()) sb.append(part).append(c)
        }
        return sb.toString()
    }

    // ─── 更新悬浮窗 ───

    private fun sendToOverlay(hand: IntArray) {
        try {
            val intent = Intent(this, OverlayService::class.java).apply {
                action = OverlayService.ACTION_UPDATE
                putExtra(OverlayService.EXTRA_HAND, hand)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        } catch (_: Exception) {}
    }

    private fun updateOverlay(msg: String) {
        try {
            val intent = Intent(this, OverlayService::class.java).apply {
                action = OverlayService.ACTION_UPDATE_STATUS
                putExtra("status_msg", msg)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        } catch (_: Exception) {}
    }

    // ─── 通知 ───

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "截屏服务", NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "截屏分析运行中"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    override fun onBind(intent: Intent?) = null

    override fun onDestroy() {
        wakeLock?.release()
        super.onDestroy()
    }
}
