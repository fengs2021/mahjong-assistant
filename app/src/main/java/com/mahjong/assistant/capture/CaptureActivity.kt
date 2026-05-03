package com.mahjong.assistant.capture

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import com.mahjong.assistant.overlay.OverlayService
import com.mahjong.assistant.util.FLog

/**
 * 透明Activity — 仅用于申请MediaProjection权限
 * 授权完成后自动返回雀魂, 避免截到桌面
 */
class CaptureActivity : Activity() {

    companion object {
        private const val REQUEST_CODE = 1001

        // 雀魂各版本包名 (按概率排序)
        private val MAJSOUL_PACKAGES = arrayOf(
            "com.soulgamechst.majsoul",      // 你的版本
            "com.majsoul.riichimahjong",     // 中国版
            "com.shengqu.majsoul",            // 盛趣版
            "com.komoe.majsoulgp",            // 国际版
            "com.dmm.majsoul",                // DMM版
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        FLog.i("CaptureAct", "onCreate")

        try {
            val mpManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val intent = mpManager.createScreenCaptureIntent()
            startActivityForResult(intent, REQUEST_CODE)
        } catch (e: Exception) {
            FLog.e("CaptureAct", "启动授权失败", e)
            finish()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        FLog.i("CaptureAct", "onActivityResult: code=$resultCode data=${data != null}")

        if (requestCode == REQUEST_CODE) {
            // 传给 OverlayService (不管成功失败都传, Service判断)
            val svcIntent = Intent(this, OverlayService::class.java).apply {
                action = OverlayService.ACTION_INIT_CAPTURE
                putExtra(OverlayService.EXTRA_RESULT_CODE, resultCode)
                if (data != null) putExtra(OverlayService.EXTRA_DATA, data)
            }
            startService(svcIntent)
        }

        // 尝试返回雀魂
        returnToMajsoul()
        finish()
    }

    /**
     * 尝试启动雀魂回到前台 (不新建task, 恢复已有实例)
     */
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
        FLog.w("CaptureAct", "未找到雀魂包名, 手动切换回游戏")
    }
}
