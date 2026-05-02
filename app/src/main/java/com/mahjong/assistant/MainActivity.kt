package com.mahjong.assistant

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.mahjong.assistant.capture.CaptureHelper
import com.mahjong.assistant.capture.TileMatcher
import com.mahjong.assistant.overlay.OverlayService

class MainActivity : AppCompatActivity() {

    companion object {
        const val REQUEST_OVERLAY = 1001
        const val REQUEST_MANUAL = 1003
    }

    private lateinit var statusText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(createLayout())

        // 初始化OpenCV
        val opencvOk = TileMatcher.init(this)
        if (!opencvOk) {
            statusText.text = "⚠ 截取功能暂不可用 (需arm64/v7a设备)\n手动输入可正常使用"
        }
    }

    override fun onResume() {
        super.onResume()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            startActivityForResult(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")),
                REQUEST_OVERLAY
            )
        } else {
            startOverlayService()
        }
    }

    private fun createLayout(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF1A3A1A.toInt())
            setPadding(20, 40, 20, 20)
        }.also { root ->
            TextView(this).apply {
                text = "🀄 雀魂助手"
                textSize = 22f
                setTextColor(0xFFA0F0A0.toInt())
                setPadding(0, 0, 0, 8)
            }.also { root.addView(it) }

            statusText = TextView(this).apply {
                text = "模板: ${TileMatcher.templateCount()}/34 | 悬浮窗已启动"
                textSize = 13f
                setTextColor(0xFF6A9A6A.toInt())
                setPadding(0, 0, 0, 16)
            }.also { root.addView(it) }

            // 截取分析
            addButton(root, "📸 截取分析") {
                statusText.text = "截取中..."
                startScreenAnalysis()
            }

            // 手动输入
            addButton(root, "✏ 手动输入手牌") {
                startActivityForResult(
                    Intent(this, ManualInputActivity::class.java), REQUEST_MANUAL
                )
            }

            // 模板校准
            addButton(root, "📷 校准模板 (${TileMatcher.templateCount()}/34)") {
                startActivity(Intent(this, CalibrateActivity::class.java))
            }

            // 停止
            Button(this).apply {
                text = "■ 停止悬浮窗"
                textSize = 14f
                setBackgroundColor(0xFF3A1A1A.toInt())
                setTextColor(0xFFFF5555.toInt())
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = 24 }
                setOnClickListener {
                    stopService(Intent(this@MainActivity, OverlayService::class.java))
                    statusText.text = "已停止"
                }
            }.also { root.addView(it) }
        }
    }

    private fun addButton(parent: LinearLayout, text: String, onClick: () -> Unit) {
        Button(this).apply {
            this.text = text
            textSize = 16f
            setBackgroundColor(if (text.startsWith("📷")) 0xFF2D5A2D.toInt() else 0xFF00CC66.toInt())
            setTextColor(if (text.startsWith("📷")) 0xFFA0F0A0.toInt() else 0xFF0A2A0A.toInt())
            setPadding(0, 14, 0, 14)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 10 }
            setOnClickListener { onClick() }
        }.also { parent.addView(it) }
    }

    private fun startScreenAnalysis() {
        // 使用MediaProjection截屏
        val projectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as android.media.projection.MediaProjectionManager
        startActivityForResult(projectionManager.createScreenCaptureIntent(), 2001)
    }

    private fun startOverlayService() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && Settings.canDrawOverlays(this)) {
            val intent = Intent(this, OverlayService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            REQUEST_OVERLAY -> startOverlayService()

            2001 -> {
                if (resultCode == RESULT_OK && data != null) {
                    statusText.text = "识别中..."
                    val projection = (getSystemService(MEDIA_PROJECTION_SERVICE)
                        as android.media.projection.MediaProjectionManager)
                        .getMediaProjection(resultCode, data)

                    CaptureHelper.captureAndCalibrate(this, projection) { bitmap ->
                        if (bitmap != null) {
                            val (results, confidence) = TileMatcher.recognize(bitmap)
                            bitmap.recycle()

                            if (results.size >= 13) {
                                val hand = results.map { it.tileId }.toIntArray()
                                updateOverlay(hand)

                                val uncertain = results.count { it.needsCheck }
                                statusText.text = if (uncertain > 0) {
                                    "⚠ $uncertain 张低置信度，点悬浮窗手动修正 (总置信度 ${(confidence*100).toInt()}%)"
                                } else {
                                    "✓ 识别完成 (置信度 ${(confidence*100).toInt()}%)"
                                }
                            } else {
                                statusText.text = "✗ 未检测到足够牌面 (${results.size}/13)"
                            }
                        } else {
                            statusText.text = "✗ 截屏失败"
                        }
                    }
                }
            }

            REQUEST_MANUAL -> {
                if (resultCode == RESULT_OK && data != null) {
                    val handArray = data.getIntArrayExtra("hand_tiles")
                    if (handArray != null && handArray.size == 14) {
                        updateOverlay(handArray)
                        statusText.text = "✓ 手动输入完成"
                    }
                }
            }
        }
    }

    private fun updateOverlay(hand: IntArray) {
        val intent = Intent(this, OverlayService::class.java).apply {
            action = OverlayService.ACTION_UPDATE
            putExtra(OverlayService.EXTRA_HAND, hand)
        }
        startService(intent)
    }
}
