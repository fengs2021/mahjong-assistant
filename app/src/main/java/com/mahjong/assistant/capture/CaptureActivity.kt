package com.mahjong.assistant.capture

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import com.mahjong.assistant.overlay.OverlayService
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * 透明Activity — 处理MediaProjection截屏
 * 支持 Android 10-15，异常自动降级
 */
class CaptureActivity : Activity() {

    companion object {
        private const val REQUEST_PROJECTION = 2001
    }

    private var projectionManager: MediaProjectionManager? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(projectionManager!!.createScreenCaptureIntent(), REQUEST_PROJECTION)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode != REQUEST_PROJECTION || resultCode != RESULT_OK || data == null) {
            updateOverlay("● 截图权限被拒绝")
            finish()
            return
        }

        try {
            val projection = projectionManager!!.getMediaProjection(resultCode, data)
            if (projection == null) {
                updateOverlay("● MediaProjection获取失败 (Android15限制)")
                finish()
                return
            }
            captureAndAnalyze(projection)
        } catch (e: SecurityException) {
            updateOverlay("● 安全异常: ${e.message?.take(40)}")
            finish()
        } catch (e: Exception) {
            updateOverlay("● 初始化失败: ${e.message?.take(40)}")
            finish()
        }
    }

    private fun captureAndAnalyze(projection: MediaProjection) {
        val metrics = resources.displayMetrics
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val density = metrics.densityDpi

        var imageReader: ImageReader? = null
        var virtualDisplay: VirtualDisplay? = null

        try {
            // Android 15: PixelFormat.RGBA_8888 可能在某些设备不受支持
            val pixelFormat = android.graphics.PixelFormat.RGBA_8888
            imageReader = ImageReader.newInstance(width, height, pixelFormat, 2)

            virtualDisplay = projection.createVirtualDisplay(
                "mahjong-capture",
                width, height, density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader.surface, null, null
            )

            // 等待一帧捕获
            val reader = imageReader
            val display = virtualDisplay
            Handler(Looper.getMainLooper()).postDelayed({
                try {
                    val image = reader.acquireLatestImage()
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

                            // 1. 本地引擎分析
                            sendToOverlay(hand)

                            // 2. 天凤交叉验证 (后台异步)
                            verifyWithTenhou(hand)
                        } else {
                            updateOverlay("● 检测到${results.size}张牌 (需≥13)")
                        }
                    } else {
                        updateOverlay("● 截屏无数据，请重试")
                    }
                } catch (e: Exception) {
                    updateOverlay("● 分析异常: ${e.message?.take(30)}")
                } finally {
                    cleanup(reader, display, projection)
                    finish()
                }
            }, 800)
        } catch (e: SecurityException) {
            updateOverlay("● Android15+ 需前台服务权限")
            cleanup(imageReader, virtualDisplay, projection)
            finish()
        } catch (e: Exception) {
            updateOverlay("● 截屏创建失败: ${e.message?.take(30)}")
            cleanup(imageReader, virtualDisplay, projection)
            finish()
        }
    }

    private fun cleanup(reader: ImageReader?, display: VirtualDisplay?, proj: MediaProjection?) {
        try { display?.release() } catch (_: Exception) {}
        try { reader?.close() } catch (_: Exception) {}
        try { proj?.stop() } catch (_: Exception) {}
    }

    // ─── 天凤API交叉验证 ───

    private fun verifyWithTenhou(hand: IntArray) {
        Thread {
            try {
                val tenhouStr = toTenhouString(hand)
                if (tenhouStr.isEmpty()) return@Thread

                val url = URL("https://tenhou.net/2/?$tenhouStr")
                val conn = url.openConnection() as HttpURLConnection
                conn.connectTimeout = 5000
                conn.readTimeout = 5000
                conn.setRequestProperty("User-Agent", "MahjongAssistant/2.2")

                val reader = BufferedReader(InputStreamReader(conn.inputStream, "UTF-8"))
                val response = reader.readText()
                reader.close()
                conn.disconnect()

                // 提取前三行
                val lines = response.split("\n").take(3).joinToString("\n")
                if (lines.isNotBlank()) {
                    updateOverlayStatusOnly("● 天凤: $lines")
                }
            } catch (_: Exception) {
                // 网络失败静默，本地引擎结果已显示
            }
        }.start()
    }

    private fun toTenhouString(hand: IntArray): String {
        val counts = IntArray(34)
        for (id in hand) counts[id]++
        val sb = StringBuilder()
        for (suit in 0..3) {
            val c = charArrayOf('m', 'p', 's', 'z')[suit]
            var part = ""
            val start = suit * 9
            val end = if (suit == 3) 7 else 9
            for (n in 0 until end) {
                val cnt = counts[start + n]
                if (cnt > 0) part += n.plus(1).toString().repeat(cnt)
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
                action = OverlayService.ACTION_UPDATE
                putExtra("status_msg", msg)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        } catch (_: Exception) {}
    }

    private fun updateOverlayStatusOnly(msg: String) {
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
}
