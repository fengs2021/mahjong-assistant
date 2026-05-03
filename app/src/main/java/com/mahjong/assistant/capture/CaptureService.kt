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
import com.mahjong.assistant.ReviewActivity
import com.mahjong.assistant.engine.Tiles
import com.mahjong.assistant.overlay.OverlayService
import java.io.File

/**
 * 长期截屏服务 — 启动时获得MediaProjection授权后一直持有
 * 收到 ACTION_CAPTURE 时立即截取，无需重新授权
 */
class CaptureService : Service() {

    companion object {
        const val CHANNEL_ID = "mahjong_capture_svc"
        const val NOTIFICATION_ID = 3
        const val ACTION_CAPTURE = "com.mahjong.assistant.CAPTURE"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_DATA_INTENT = "data_intent"
    }

    private var projection: MediaProjection? = null
    private var savedResultCode = -1
    private var savedDataIntent: Intent? = null
    private var isCapturing = false
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "截屏服务", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "长期截屏服务运行中"; setShowBadge(false)
                }
            )
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CAPTURE -> {
                if (!isCapturing) captureNow()
            }
            else -> {
                // 初始化: 接收授权参数
                savedResultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, -1) ?: -1
                if (Build.VERSION.SDK_INT >= 33) {
                    savedDataIntent = intent?.getParcelableExtra(EXTRA_DATA_INTENT, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    savedDataIntent = intent?.getParcelableExtra(EXTRA_DATA_INTENT)
                }
                startForeground()
            }
        }
        return START_STICKY
    }

    private fun startForeground() {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("雀魂助手")
            .setContentText("截屏就绪")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun captureNow() {
        if (savedResultCode == -1 || savedDataIntent == null) {
            notifyStatus("● 截屏未授权, 请重启APP")
            return
        }

        isCapturing = true

        try {
            if (projection == null) {
                val pm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                val data = savedDataIntent ?: return
                projection = pm.getMediaProjection(savedResultCode, data)
            }

            val metrics = resources.displayMetrics
            val reader = ImageReader.newInstance(
                metrics.widthPixels, metrics.heightPixels,
                android.graphics.PixelFormat.RGBA_8888, 2
            )

            reader.setOnImageAvailableListener({ r ->
                if (!isCapturing) return@setOnImageAvailableListener
                isCapturing = false

                try {
                    val image: Image? = r.acquireLatestImage()
                    if (image != null) {
                        val buffer = image.planes[0].buffer
                        val bitmap = Bitmap.createBitmap(
                            r.width, r.height, Bitmap.Config.ARGB_8888
                        )
                        bitmap.copyPixelsFromBuffer(buffer)
                        image.close()

                        processScreenshot(bitmap)
                    } else {
                        notifyStatus("● 截屏无数据")
                        cleanup(reader, null)
                    }
                } catch (e: Exception) {
                    notifyStatus("● 异常: ${e.message?.take(30)}")
                    cleanup(reader, null)
                }
            }, mainHandler)

            projection!!.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    notifyStatus("● 截屏权限失效，请重启APP")
                    cleanup(reader, null)
                }
            }, mainHandler)

            val vd = projection!!.createVirtualDisplay(
                "mahjong-capture",
                metrics.widthPixels, metrics.heightPixels, metrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.surface, null, null
            )

            // 超时保护
            mainHandler.postDelayed({
                if (isCapturing) {
                    isCapturing = false
                    notifyStatus("● 截屏超时")
                    cleanup(reader, vd)
                }
            }, 3000)

        } catch (e: SecurityException) {
            isCapturing = false
            notifyStatus("● 权限失效, 请重启APP")
        } catch (e: Exception) {
            isCapturing = false
            notifyStatus("● 截屏失败: ${e.message?.take(30)}")
        }
    }

    private fun processScreenshot(bitmap: Bitmap) {
        val (results, _) = TileMatcher.recognize(bitmap)

        // 保存截图供审核预览
        val ssPath = saveScreenshot(bitmap)
        bitmap.recycle()

        // 日志推悬浮窗
        notifyStatus(TileMatcher.lastLog)

        val tileIds = results.map { it.tileId }.toIntArray()
        val confidences = results.map { it.confidence }.toDoubleArray()
        val handStr = if (tileIds.isNotEmpty()) Tiles.toDisplayString(tileIds) else "—"

        if (results.size >= 13) {
            val uncertain = results.count { it.needsCheck }
            notifyStatus(TileMatcher.lastLog + "\n● 识别${results.size}张: $handStr" +
                if (uncertain > 0) " | ⚠${uncertain}张待确认" else " ✓")
        } else if (results.isNotEmpty()) {
            notifyStatus(TileMatcher.lastLog + "\n● 仅${results.size}张: $handStr → 进入审核")
        } else {
            notifyStatus(TileMatcher.lastLog + "\n● 未检测到牌")
        }

        launchReview(tileIds, confidences, ssPath)
    }

    private fun launchReview(tileIds: IntArray, confidences: DoubleArray, ssPath: String?) {
        try {
            val intent = Intent(this, ReviewActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra(ReviewActivity.EXTRA_TILE_IDS, tileIds)
                putExtra(ReviewActivity.EXTRA_CONFIDENCES, confidences)
                putExtra(ReviewActivity.EXTRA_LOG, TileMatcher.lastLog)
                if (ssPath != null) putExtra(ReviewActivity.EXTRA_SCREENSHOT_PATH, ssPath)
            }
            startActivity(intent)
        } catch (e: Exception) {
            notifyStatus("● 审核界面启动失败: ${e.message?.take(30)}")
        }
    }

    private fun saveScreenshot(bitmap: Bitmap): String? {
        return try {
            val dir = File(cacheDir, "screenshots")
            if (!dir.exists()) dir.mkdirs()
            val file = File(dir, "capture_${System.currentTimeMillis()}.jpg")
            val fos = java.io.FileOutputStream(file)
            bitmap.compress(Bitmap.CompressFormat.JPEG, 70, fos)
            fos.close()
            file.absolutePath
        } catch (_: Exception) { null }
    }

    private fun cleanup(reader: ImageReader?, vd: VirtualDisplay?) {
        try { vd?.release() } catch (_: Exception) {}
        try { reader?.close() } catch (_: Exception) {}
    }

    private fun notifyStatus(msg: String) {
        try {
            val i = Intent(this, OverlayService::class.java).apply {
                action = OverlayService.ACTION_UPDATE_STATUS
                putExtra("status_msg", msg)
            }
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
        } catch (_: Exception) {}
    }

    override fun onDestroy() {
        try { projection?.stop() } catch (_: Exception) {}
        projection = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?) = null
}
