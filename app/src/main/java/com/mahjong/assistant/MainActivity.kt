package com.mahjong.assistant

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.mahjong.assistant.capture.ScreenCaptureService
import com.mahjong.assistant.capture.TileMatcher
import com.mahjong.assistant.overlay.OverlayService

class MainActivity : AppCompatActivity() {

    companion object {
        const val REQUEST_OVERLAY = 1001
        const val REQUEST_MANUAL = 1003
    }

    private lateinit var statusText: TextView
    private lateinit var overlayBtn: Button
    private lateinit var a11yBtn: Button
    private lateinit var refreshBtn: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(createLayout())

        val opencvOk = TileMatcher.init(this)
        statusText.text = TileMatcher.getDiagnostic()
        if (!opencvOk) {
            statusText.text = "⚠ ${TileMatcher.getDiagnostic()}\n手动输入可正常使用"
        }
    }

    override fun onResume() {
        super.onResume()

        val hasOverlay = Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this)

        if (hasOverlay) {
            startOverlayService()
            val a11yOk = ScreenCaptureService.isEnabled(this)
            statusText.text = if (a11yOk) "✓ 无障碍已开启 | ${TileMatcher.getDiagnostic()}"
                             else "⚠ 需开启无障碍服务 → 点下方按钮"
        } else {
            statusText.text = "⚠ 需要悬浮窗权限 → 点下方按钮授权"
        }

        refreshButtons(overlayBtn, a11yBtn, refreshBtn)
    }

    private fun refreshButtons(overlayBtn: Button? = null, a11yBtn: Button? = null, refreshBtn: Button? = null) {
        val hasOverlay = Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this)
        val hasA11y = ScreenCaptureService.isEnabled(this)
        overlayBtn?.visibility = if (hasOverlay) View.GONE else View.VISIBLE
        a11yBtn?.visibility = if (!hasOverlay || hasA11y) View.GONE else View.VISIBLE
        refreshBtn?.visibility = if (!hasOverlay || !hasA11y) View.GONE else View.VISIBLE
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

            // 授权按钮组 — 仅权限缺失时显示
            overlayBtn = addButton(root, "📱 授权悬浮窗") {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    startActivityForResult(
                        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")),
                        REQUEST_OVERLAY
                    )
                }
            }

            a11yBtn = addButton(root, "♿ 开启无障碍服务") {
                ScreenCaptureService.openSettings(this)
            }

            addButton(root, "✏ 手动输入手牌") {
                startActivityForResult(
                    Intent(this, ManualInputActivity::class.java), REQUEST_MANUAL
                )
            }

            addButton(root, "📷 模板采集器") {
                startActivity(Intent(this, TemplateCollectorActivity::class.java))
            }

            refreshBtn = addButton(root, "♿ 检查无障碍") {
                ScreenCaptureService.openSettings(this)
            }

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

            // 根据权限状态动态显隐
            refreshButtons(overlayBtn, a11yBtn, refreshBtn)
        }
    }

    private fun addButton(parent: LinearLayout, text: String, onClick: () -> Unit): Button {
        return Button(this).apply {
            this.text = text
            textSize = 16f
            setBackgroundColor(0xFF00CC66.toInt())
            setTextColor(0xFF0A2A0A.toInt())
            setPadding(0, 14, 0, 14)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 10 }
            setOnClickListener { onClick() }
        }.also { parent.addView(it) }
    }

    private fun startOverlayService() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && Settings.canDrawOverlays(this)) {
            val intent = Intent(this, OverlayService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(intent)
                } else {
                    startService(intent)
                }
            } catch (e: Exception) {
                // Android 15+ 可能因后台启动限制抛出 ForegroundServiceStartNotAllowedException
                // Service 内部会在 onCreate 自己调 startForeground
                android.util.Log.w("MainActivity", "startForegroundService失败, 回退startService: ${e.message}")
                startService(intent)
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            REQUEST_OVERLAY -> {
                startOverlayService()
                val hasOverlay = Settings.canDrawOverlays(this)
                statusText.text = if (hasOverlay) {
                    "✓ 悬浮窗已授权 | 请开启无障碍服务"
                } else {
                    "⚠ 悬浮窗权限被拒绝"
                }
                refreshButtons(overlayBtn, a11yBtn, refreshBtn)
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
