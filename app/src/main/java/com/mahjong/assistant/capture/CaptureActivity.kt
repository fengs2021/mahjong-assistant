package com.mahjong.assistant.capture

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import com.mahjong.assistant.overlay.OverlayService

/**
 * 透明Activity — 仅获取MediaProjection授权，委托CaptureForegroundService截屏
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

        val intent = Intent(this, CaptureForegroundService::class.java).apply {
            putExtra(CaptureForegroundService.EXTRA_RESULT_CODE, resultCode)
            putExtra(CaptureForegroundService.EXTRA_DATA_INTENT, data)
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        } catch (e: Exception) {
            updateOverlay("● 启动截屏失败: ${e.message?.take(30)}")
        }
        finish()
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
