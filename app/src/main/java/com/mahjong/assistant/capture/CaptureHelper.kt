package com.mahjong.assistant.capture

import android.app.Activity
import android.graphics.Bitmap
import android.hardware.display.DisplayManager
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.Looper

/**
 * 截屏辅助 — 处理MediaProjection截取
 */
object CaptureHelper {
    fun captureAndCalibrate(
        activity: Activity,
        projection: MediaProjection,
        callback: (Bitmap?) -> Unit
    ) {
        val metrics = activity.resources.displayMetrics
        val width = metrics.widthPixels
        val height = metrics.heightPixels

        val imageReader = ImageReader.newInstance(
            width, height,
            android.graphics.PixelFormat.RGBA_8888, 2
        )

        val virtualDisplay = projection.createVirtualDisplay(
            "calibrate",
            width, height, metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader.surface, null, null
        )

        Handler(Looper.getMainLooper()).postDelayed({
            val image = imageReader.acquireLatestImage()
            if (image != null) {
                val planes = image.planes
                val buffer = planes[0].buffer
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                bitmap.copyPixelsFromBuffer(buffer)
                image.close()

                virtualDisplay.release()
                imageReader.close()
                projection.stop()

                callback(bitmap)
            } else {
                virtualDisplay.release()
                imageReader.close()
                projection.stop()
                callback(null)
            }
        }, 500)
    }
}
