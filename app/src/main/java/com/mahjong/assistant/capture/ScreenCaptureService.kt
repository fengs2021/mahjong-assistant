package com.mahjong.assistant.capture

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.ScreenCaptureCallback
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import com.mahjong.assistant.overlay.OverlayService
import com.mahjong.assistant.util.FLog
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 无障碍截图服务 — 绕过 Android 15 MediaProjection FGS 限制
 *
 * AccessibilityService.takeScreenshot() (API 34+) 不依赖 MediaProjection,
 * 不需要 foreground service 声明。
 */
class ScreenCaptureService : AccessibilityService() {

    companion object {
        @Volatile var instance: ScreenCaptureService? = null
        private val mainHandler = Handler(Looper.getMainLooper())

        /** 检查无障碍服务是否已开启 */
        fun isEnabled(context: Context): Boolean {
            val services = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            return services.contains(context.packageName + "/" + ScreenCaptureService::class.java.name)
        }

        /** 打开无障碍设置页 */
        fun openSettings(context: Context) {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 不监听事件，仅用于截图
    }

    override fun onInterrupt() {}

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        FLog.i("A11ySvc", "onServiceConnected")
    }

    override fun onDestroy() {
        instance = null
        FLog.i("A11ySvc", "onDestroy")
        super.onDestroy()
    }

    /**
     * 执行截图 → 识别 → 发送结果到 OverlayService
     */
    fun captureAndRecognize() {
        if (Build.VERSION.SDK_INT < 34) {
            FLog.w("A11ySvc", "takeScreenshot requires API 34+")
            return
        }

        FLog.i("A11ySvc", "captureAndRecognize start")

        val latch = CountDownLatch(1)
        var bitmap: Bitmap? = null

        try {
            takeScreenshot(
                displayId,
                mainHandler,
                object : ScreenCaptureCallback {
                    override fun onSuccess(result: ScreenCaptureResult) {
                        FLog.i("A11ySvc", "takeScreenshot OK")
                        try {
                            bitmap = Bitmap.wrapHardwareBuffer(
                                result.hardwareBuffer,
                                result.colorSpace
                            )?.copy(Bitmap.Config.ARGB_8888, false)
                            result.hardwareBuffer.close()
                        } catch (e: Exception) {
                            FLog.e("A11ySvc", "bitmap wrap失败", e)
                        }
                        latch.countDown()
                    }

                    override fun onFailure(errorCode: Int) {
                        FLog.e("A11ySvc", "takeScreenshot失败 code=$errorCode")
                        latch.countDown()
                    }
                }
            )
        } catch (e: Exception) {
            FLog.e("A11ySvc", "takeScreenshot异常", e)
            latch.countDown()
        }

        // 等最多 5 秒
        try { latch.await(5, TimeUnit.SECONDS) } catch (_: InterruptedException) {}

        val bmp = bitmap
        if (bmp == null) {
            FLog.w("A11ySvc", "截图失败或无数据")
            sendErrorToOverlay("截图失败")
            return
        }

        FLog.i("A11ySvc", "bitmap ${bmp.width}x${bmp.height}")

        // 识别
        val (results, _) = TileMatcher.recognize(bmp)

        // 存截图文件
        val ssPath = try {
            val dir = File(cacheDir, "screenshots")
            dir.mkdirs()
            val file = File(dir, "capture_${System.currentTimeMillis()}.jpg")
            file.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 70, it) }
            file.absolutePath
        } catch (_: Exception) { null }

        bmp.recycle()

        // 发结果到 OverlayService
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

        FLog.i("A11ySvc", "captureAndRecognize done: ${results.size} tiles")
    }

    private fun sendErrorToOverlay(msg: String) {
        val intent = Intent(this, OverlayService::class.java).apply {
            action = OverlayService.ACTION_UPDATE_STATUS
            putExtra("status_msg", "✖ $msg")
        }
        startService(intent)
    }
}
