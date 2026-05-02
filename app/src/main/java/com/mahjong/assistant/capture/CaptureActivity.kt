package com.mahjong.assistant.capture

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.hardware.display.DisplayManager
import android.media.ImageReader
import android.media.projection.MediaProjectionManager
import android.os.*
import com.mahjong.assistant.overlay.OverlayService
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * 透明Activity — 授权 + 截屏 + 分析
 * targetSdk=34 不强制前台服务，可直接使用MediaProjection
 */
class CaptureActivity : Activity() {

    companion object {
        private const val REQUEST_PROJECTION = 2001
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(pm.createScreenCaptureIntent(), REQUEST_PROJECTION)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode != REQUEST_PROJECTION || resultCode != RESULT_OK || data == null) {
            updateOverlay("● 截图权限被拒绝")
            finish()
            return
        }

        val pm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val projection = try {
            pm.getMediaProjection(resultCode, data)
        } catch (e: SecurityException) {
            updateOverlay("● 安全异常: ${e.message?.take(40)}")
            finish()
            return
        }

        if (projection == null) {
            updateOverlay("● MediaProjection创建失败")
            finish()
            return
        }

        capture(projection)
    }

    private fun capture(projection: android.media.projection.MediaProjection) {
        val metrics = resources.displayMetrics
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val density = metrics.densityDpi

        var imageReader: ImageReader? = null
        var display: android.hardware.display.VirtualDisplay? = null

        try {
            imageReader = ImageReader.newInstance(width, height, android.graphics.PixelFormat.RGBA_8888, 2)
            display = projection.createVirtualDisplay(
                "mahjong-capture", width, height, density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader.surface, null, null
            )

            val reader = imageReader
            val vd = display

            Handler(Looper.getMainLooper()).postDelayed({
                try {
                    val image = reader.acquireLatestImage()
                    if (image != null) {
                        val buffer = image.planes[0].buffer
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
                    try { vd.release() } catch (_: Exception) {}
                    try { reader.close() } catch (_: Exception) {}
                    try { projection.stop() } catch (_: Exception) {}
                    finish()
                }
            }, 800)
        } catch (e: Exception) {
            updateOverlay("● 截屏失败: ${e.message?.take(30)}")
            try { display?.release() } catch (_: Exception) {}
            try { imageReader?.close() } catch (_: Exception) {}
            try { projection.stop() } catch (_: Exception) {}
            finish()
        }
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
                conn.setRequestProperty("User-Agent", "MahjongAssistant/2.7")
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
                action = OverlayService.ACTION_UPDATE
                putExtra(OverlayService.EXTRA_HAND, hand)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(i) else startService(i)
        } catch (_: Exception) {}
    }

    private fun updateOverlay(msg: String) {
        try {
            val i = Intent(this, OverlayService::class.java).apply {
                action = OverlayService.ACTION_UPDATE_STATUS
                putExtra("status_msg", msg)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(i) else startService(i)
        } catch (_: Exception) {}
    }
}
