package com.mahjong.assistant.capture

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.hardware.display.DisplayManager
import android.media.ImageReader
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import com.mahjong.assistant.overlay.OverlayService

/**
 * 透明Activity — 处理MediaProjection截屏
 * 从悬浮窗或MainActivity启动，截屏后分析并更新悬浮窗
 */
class CaptureActivity : Activity() {

    companion object {
        private const val REQUEST_PROJECTION = 2001
    }

    private var projectionManager: MediaProjectionManager? = null
    private var wasLaunchedFromOverlay = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        wasLaunchedFromOverlay = intent.getBooleanExtra("from_overlay", false)

        projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(projectionManager!!.createScreenCaptureIntent(), REQUEST_PROJECTION)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode != REQUEST_PROJECTION || resultCode != RESULT_OK || data == null) {
            // 用户拒绝授权
            updateOverlayStatus("● 截图权限被拒绝")
            finish()
            return
        }

        val projection = projectionManager!!.getMediaProjection(resultCode, data)
        captureAndAnalyze(projection)
    }

    private fun captureAndAnalyze(projection: android.media.projection.MediaProjection) {
        val metrics = resources.displayMetrics
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val density = metrics.densityDpi

        val imageReader = ImageReader.newInstance(width, height, android.graphics.PixelFormat.RGBA_8888, 2)

        val virtualDisplay = projection.createVirtualDisplay(
            "mahjong-capture",
            width, height, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader.surface, null, null
        )

        // 等待一帧截取
        Handler(Looper.getMainLooper()).postDelayed({
            try {
                val image = imageReader.acquireLatestImage()
                if (image != null) {
                    val planes = image.planes
                    val buffer = planes[0].buffer
                    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    bitmap.copyPixelsFromBuffer(buffer)
                    image.close()

                    // 分析
                    val (results, _) = TileMatcher.recognize(bitmap)
                    bitmap.recycle()

                    if (results.size >= 13) {
                        val hand = results.map { it.tileId }.toIntArray()

                        // 更新悬浮窗
                        val intent = Intent(this, OverlayService::class.java).apply {
                            action = OverlayService.ACTION_UPDATE
                            putExtra(OverlayService.EXTRA_HAND, hand)
                        }
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            startForegroundService(intent)
                        } else {
                            startService(intent)
                        }
                    } else {
                        updateOverlayStatus("● 检测到${results.size}张牌 (需13张以上)")
                    }
                } else {
                    updateOverlayStatus("● 截屏失败: 无图像数据")
                }
            } catch (e: Exception) {
                updateOverlayStatus("● 分析异常: ${e.message}")
            } finally {
                virtualDisplay.release()
                imageReader.close()
                projection.stop()
                finish()
            }
        }, 800) // 800ms等待帧渲染
    }

    private fun updateOverlayStatus(msg: String) {
        // 通过空手牌数组通知悬浮窗更新状态
        // 实际可以通过Broadcast或直接启动service传消息
        val intent = Intent(this, OverlayService::class.java).apply {
            action = OverlayService.ACTION_UPDATE
            putExtra("status_msg", msg)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }
}
