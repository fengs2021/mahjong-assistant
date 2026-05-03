package com.mahjong.assistant.capture

import android.app.Activity
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
import android.os.HandlerThread
import android.view.WindowManager
import com.mahjong.assistant.overlay.OverlayService
import com.mahjong.assistant.util.FLog
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 透明Activity — 授权 + 截图一体
 *
 * Android 15 禁止普通 Service 在 startForeground 声明 mediaProjection 类型
 * (需要CAPTURE_VIDEO_OUTPUT签名级权限)，也禁止无 mediaProjection 类型的
 * Service 调用 getMediaProjection()。
 *
 * 解决方案：截图逻辑完全放在 Activity 中。
 * Activity 调用 getMediaProjection() 不受 FGS 类型限制。
 */
class CaptureActivity : Activity() {

    companion object {
        private const val REQUEST_CODE = 1001
        const val EXTRA_FORCE_AUTH = "force_auth"

        private val MAJSOUL_PACKAGES = arrayOf(
            "com.soulgamechst.majsoul",
            "com.majsoul.riichimahjong",
            "com.shengqu.majsoul",
            "com.komoe.majsoulgp",
            "com.dmm.majsoul",
        )
    }

    private var captureLatch: CountDownLatch? = null
    private var capturedBitmap: Bitmap? = null
    private val bgThread = HandlerThread("CaptureBg").apply { start() }
    private val bgHandler = Handler(bgThread.looper)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        FLog.i("CaptureAct", "onCreate forceAuth=${intent.getBooleanExtra(EXTRA_FORCE_AUTH, false)}")

        val resultCode = OverlayService.captureResultCode
        val resultData = OverlayService.captureResultData
        val hasAuth = resultCode != OverlayService.CODE_UNSET && resultData != null
        val forceAuth = intent.getBooleanExtra(EXTRA_FORCE_AUTH, false)

        if (hasAuth && !forceAuth) {
            // 已有授权 → 直接截图
            FLog.i("CaptureAct", "已有授权,直接截图")
            doCapture(resultCode, resultData!!)
        } else {
            // 请求授权
            FLog.i("CaptureAct", "请求授权")
            try {
                val mpManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                startActivityForResult(mpManager.createScreenCaptureIntent(), REQUEST_CODE)
            } catch (e: Exception) {
                FLog.e("CaptureAct", "启动授权失败", e)
                finish()
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        FLog.i("CaptureAct", "onActivityResult: code=$resultCode data=${data != null}")

        if (requestCode == REQUEST_CODE && resultCode == RESULT_OK && data != null) {
            // 保存授权到 OverlayService (供后续复用)
            OverlayService.captureResultCode = resultCode
            OverlayService.captureResultData = data

            doCapture(resultCode, data)
        } else {
            returnToMajsoul()
            finish()
        }
    }

    private fun doCapture(resultCode: Int, data: Intent) {
        FLog.i("CaptureAct", "doCapture start")
        val (sw, sh, dpi) = getScreenParams()
        FLog.i("CaptureAct", "screen: ${sw}x${sh} dpi=$dpi")

        val pm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val projection: MediaProjection
        try {
            projection = pm.getMediaProjection(resultCode, data)
            FLog.i("CaptureAct", "MediaProjection OK")
        } catch (e: Exception) {
            FLog.e("CaptureAct", "getMediaProjection失败", e)
            returnToMajsoul()
            finish()
            return
        }

        captureLatch = CountDownLatch(1)
        capturedBitmap = null

        val reader = ImageReader.newInstance(sw, sh, PixelFormat.RGBA_8888, 1)

        reader.setOnImageAvailableListener({ r ->
            try {
                val img: Image? = r.acquireLatestImage()
                if (img != null) {
                    val bitmap = imageToBitmap(img, r.width, r.height)
                    img.close()
                    capturedBitmap = bitmap
                    FLog.i("CaptureAct", "bitmap ${bitmap.width}x${bitmap.height}")
                }
            } catch (e: Exception) {
                FLog.e("CaptureAct", "image处理异常", e)
            } finally {
                captureLatch?.countDown()
                try { r.close() } catch (_: Exception) {}
            }
        }, bgHandler)

        val vd: VirtualDisplay
        try {
            vd = projection.createVirtualDisplay(
                "mj-cap", sw, sh, dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.surface, null, null
            )
            FLog.i("CaptureAct", "VirtualDisplay OK")
        } catch (e: Exception) {
            FLog.e("CaptureAct", "createVirtualDisplay失败", e)
            reader.close()
            projection.stop()
            returnToMajsoul()
            finish()
            return
        }

        // 等待截图 (最多3s)
        try {
            captureLatch?.await(3, TimeUnit.SECONDS)
        } catch (_: InterruptedException) {}

        vd.release()
        projection.stop()

        val bitmap = capturedBitmap
        if (bitmap != null) {
            processAndSend(bitmap)
        } else {
            FLog.w("CaptureAct", "截图超时/失败")
        }

        returnToMajsoul()
        finish()
    }

    private fun processAndSend(bitmap: Bitmap) {
        FLog.i("CaptureAct", "processAndSend start")
        val (results, _) = TileMatcher.recognize(bitmap)

        // 存截图文件
        val ssPath = try {
            val dir = cacheDir.resolve("screenshots")
            dir.mkdirs()
            val file = dir.resolve("capture_${System.currentTimeMillis()}.jpg")
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 70, it) }
            file.absolutePath
        } catch (_: Exception) { null }

        // 发结果给 OverlayService
        val tileIds = results.map { it.tileId }.toIntArray()
        val confidences = results.map { it.confidence }.toDoubleArray()

        val intent = Intent(this, OverlayService::class.java).apply {
            action = OverlayService.ACTION_CAPTURE_DONE
            putExtra("tile_ids", tileIds)
            putExtra("confidences", confidences)
            putExtra("log", TileMatcher.lastLog)
            if (ssPath != null) putExtra("screenshot_path", ssPath)
        }
        startService(intent)
        bitmap.recycle()
        FLog.i("CaptureAct", "processAndSend done: ${results.size} tiles")
    }

    private fun imageToBitmap(img: Image, width: Int, height: Int): Bitmap {
        val plane = img.planes[0]
        val buffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride

        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(width)
        val byteBuf = ByteArray(rowStride)
        buffer.rewind()
        for (y in 0 until height) {
            buffer.get(byteBuf, 0, minOf(rowStride, buffer.remaining()))
            for (x in 0 until width) {
                val off = x * pixelStride
                pixels[x] = ((byteBuf[off + 3].toInt() and 0xFF) shl 24) or
                        ((byteBuf[off].toInt() and 0xFF) shl 16) or
                        ((byteBuf[off + 1].toInt() and 0xFF) shl 8) or
                        (byteBuf[off + 2].toInt() and 0xFF)
            }
            bmp.setPixels(pixels, 0, width, 0, y, width, 1)
        }
        return bmp
    }

    private fun getScreenParams(): Triple<Int, Int, Int> {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val (w, h) = if (Build.VERSION.SDK_INT >= 30) {
            val bounds = wm.currentWindowMetrics.bounds
            Pair(bounds.width(), bounds.height())
        } else {
            val point = android.graphics.Point()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealSize(point)
            Pair(point.x, point.y)
        }
        val dpi = resources.displayMetrics.densityDpi
        return Triple(w, h, dpi)
    }

    private fun returnToMajsoul() {
        for (pkg in MAJSOUL_PACKAGES) {
            try {
                val intent = packageManager.getLaunchIntentForPackage(pkg)
                if (intent != null) {
                    intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(intent)
                    FLog.i("CaptureAct", "返回雀魂: $pkg")
                    return
                }
            } catch (_: Exception) {}
        }
        FLog.w("CaptureAct", "未找到雀魂包名")
    }

    override fun onDestroy() {
        super.onDestroy()
        bgThread.quitSafely()
    }
}
