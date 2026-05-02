package com.mahjong.assistant.capture

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import androidx.core.app.NotificationCompat
import com.mahjong.assistant.overlay.OverlayService
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * 前台服务 — 在 mediaProjection 上下文中执行截屏
 * Android 15 强制要求，必须在前台服务中持有 MediaProjection
 * 使用 ImageReader.setOnImageAvailableListener 持续消费帧避免系统回收
 */
class CaptureForegroundService : Service() {

    companion object {
        const val CHANNEL_ID = "mahjong_capture"
        const val NOTIFICATION_ID = 2
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_DATA_INTENT = "data_intent"
        private const val CAPTURE_TIMEOUT_MS = 3000L
    }

    private var projection: MediaProjection? = null
    private var imageReader: ImageReader? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var captured = false
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "截屏服务", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "截屏分析运行中"; setShowBadge(false)
                }
            )
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("雀魂助手")
            .setContentText("截屏中...")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, -1) ?: -1
        val dataIntent: Intent? = if (Build.VERSION.SDK_INT >= 33) {
            intent?.getParcelableExtra(EXTRA_DATA_INTENT, Intent::class.java)
        } else {
            @Suppress("DEPRECATION") intent?.getParcelableExtra(EXTRA_DATA_INTENT)
        }

        if (resultCode != Activity.RESULT_OK || dataIntent == null) {
            bail("● 截屏参数错误")
            return START_NOT_STICKY
        }

        // 给系统足够时间确认前台服务状态
        mainHandler.postDelayed({ startCapture(resultCode, dataIntent) }, 500)
        return START_STICKY
    }

    private fun startCapture(resultCode: Int, data: Intent) {
        try {
            val pm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            projection = pm.getMediaProjection(resultCode, data) ?: run {
                bail("● MediaProjection创建失败"); return
            }

            val metrics = resources.displayMetrics
            imageReader = ImageReader.newInstance(
                metrics.widthPixels, metrics.heightPixels,
                android.graphics.PixelFormat.RGBA_8888, 3
            )

            // 持续监听帧到达，避免系统因不活跃回收 projection
            imageReader!!.setOnImageAvailableListener({ reader ->
                if (captured) return@setOnImageAvailableListener
                captured = true

                try {
                    val image: Image? = reader.acquireLatestImage()
                    if (image != null) {
                        val buffer = image.planes[0].buffer
                        val bitmap = Bitmap.createBitmap(
                            reader.width, reader.height, Bitmap.Config.ARGB_8888
                        )
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
                        updateOverlay("● 截屏无数据")
                    }
                } catch (e: Exception) {
                    updateOverlay("● 异常: ${e.message?.take(30)}")
                } finally {
                    cleanup()
                }
            }, mainHandler)

            virtualDisplay = projection!!.createVirtualDisplay(
                "mahjong-capture",
                metrics.widthPixels, metrics.heightPixels, metrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader!!.surface, null, null
            )

            // 超时保护：3秒没帧就放弃
            mainHandler.postDelayed({
                if (!captured) {
                    updateOverlay("● 截屏超时")
                    cleanup()
                }
            }, CAPTURE_TIMEOUT_MS)

        } catch (e: SecurityException) {
            bail("● 安全异常: ${e.message?.take(40)}")
        } catch (e: Exception) {
            bail("● 截屏失败: ${e.message?.take(30)}")
        }
    }

    private fun cleanup() {
        try { virtualDisplay?.release() } catch (_: Exception) {}
        try { imageReader?.close() } catch (_: Exception) {}
        try { projection?.stop() } catch (_: Exception) {}
        virtualDisplay = null; imageReader = null; projection = null
        stopSelf()
    }

    private fun bail(msg: String) {
        updateOverlay(msg)
        cleanup()
    }

    // ─── 天凤 ───

    private fun verifyWithTenhou(hand: IntArray) {
        Thread {
            try {
                val q = toTenhouString(hand)
                if (q.isEmpty()) return@Thread
                val url = URL("https://tenhou.net/2/?$q")
                val conn = url.openConnection() as HttpURLConnection
                conn.connectTimeout = 5000; conn.readTimeout = 5000
                conn.setRequestProperty("User-Agent", "MahjongAssistant/2.8")
                val r = BufferedReader(InputStreamReader(conn.inputStream, "UTF-8")).readText()
                conn.disconnect()
                val lines = r.split("\n").take(3).joinToString("\n")
                if (lines.isNotBlank()) updateOverlay("● 天凤: $lines")
            } catch (_: Exception) {}
        }.start()
    }

    private fun toTenhouString(hand: IntArray): String {
        val c = IntArray(34); for (id in hand) if (id in 0..33) c[id]++
        return buildString {
            for (s in 0..3) {
                val ch = charArrayOf('m','p','s','z')[s]
                var p = ""; val st = s * 9; val en = if (s == 3) 7 else 9
                for (n in 0 until en) { val cnt = c[st + n]; if (cnt > 0) p += (n + 1).toString().repeat(cnt) }
                if (p.isNotEmpty()) append(p).append(ch)
            }
        }
    }

    private fun sendToOverlay(hand: IntArray) {
        try {
            val i = Intent(this, OverlayService::class.java).apply {
                action = OverlayService.ACTION_UPDATE; putExtra(OverlayService.EXTRA_HAND, hand)
            }
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
        } catch (_: Exception) {}
    }

    private fun updateOverlay(msg: String) {
        try {
            val i = Intent(this, OverlayService::class.java).apply {
                action = OverlayService.ACTION_UPDATE_STATUS; putExtra("status_msg", msg)
            }
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
        } catch (_: Exception) {}
    }

    override fun onBind(intent: Intent?) = null
}
