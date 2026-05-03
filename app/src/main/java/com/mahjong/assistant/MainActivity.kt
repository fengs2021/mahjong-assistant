package com.mahjong.assistant

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.mahjong.assistant.capture.TileMatcher
import com.mahjong.assistant.overlay.OverlayService

class MainActivity : AppCompatActivity() {

    companion object {
        const val REQUEST_OVERLAY = 1001
        const val REQUEST_PROJECTION = 2001
        const val REQUEST_MANUAL = 1003
        private const val PREFS = "mahjong_prefs"
        private const val KEY_AUTHORIZED = "projection_authorized"
    }

    private lateinit var statusText: TextView
    private lateinit var overlayBtn: Button
    private lateinit var projectionBtn: Button
    private lateinit var refreshBtn: Button
    private val prefs by lazy { getSharedPreferences(PREFS, Context.MODE_PRIVATE) }

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
            val hasCode = com.mahjong.assistant.overlay.OverlayService.captureResultCode != -1
            statusText.text = if (hasCode) "截屏已授权 | ${TileMatcher.getDiagnostic()}"
                             else "截屏权限需授权 | 点下方按钮"
        } else {
            statusText.text = "⚠ 需要悬浮窗权限 → 点下方按钮授权"
        }

        // 根据权限状态动态显示/隐藏按钮
        refreshButtons(overlayBtn, projectionBtn, refreshBtn)
    }

    private fun refreshButtons(overlayBtn: Button? = null, projectionBtn: Button? = null, refreshBtn: Button? = null) {
        val hasOverlay = Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this)
        val hasProjection = prefs.getBoolean(KEY_AUTHORIZED, false)
        overlayBtn?.visibility = if (hasOverlay) View.GONE else View.VISIBLE
        projectionBtn?.visibility = if (!hasOverlay || hasProjection) View.GONE else View.VISIBLE
        refreshBtn?.visibility = if (!hasOverlay || !hasProjection) View.GONE else View.VISIBLE
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

            projectionBtn = addButton(root, "📸 授权截屏") {
                val pm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as android.media.projection.MediaProjectionManager
                startActivityForResult(pm.createScreenCaptureIntent(), REQUEST_PROJECTION)
            }

            addButton(root, "✏ 手动输入手牌") {
                startActivityForResult(
                    Intent(this, ManualInputActivity::class.java), REQUEST_MANUAL
                )
            }

            refreshBtn = addButton(root, "📸 刷新截图授权") {
                prefs.edit().remove(KEY_AUTHORIZED).apply()
                val pm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as android.media.projection.MediaProjectionManager
                startActivityForResult(pm.createScreenCaptureIntent(), REQUEST_PROJECTION)
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
            refreshButtons(overlayBtn, projectionBtn, refreshBtn)
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
            REQUEST_OVERLAY -> {
                startOverlayService()
                val hasOverlay = Settings.canDrawOverlays(this)
                statusText.text = if (hasOverlay) {
                    "✓ 悬浮窗已授权 | 请授权截屏权限"
                } else {
                    "⚠ 悬浮窗权限被拒绝"
                }
                refreshButtons(overlayBtn, projectionBtn, refreshBtn)
            }

            REQUEST_PROJECTION -> {
                if (resultCode == RESULT_OK && data != null) {
                    statusText.text = "截屏已授权"
                    prefs.edit().putBoolean(KEY_AUTHORIZED, true).apply()
                    val intent = Intent(this, OverlayService::class.java).apply {
                        action = OverlayService.ACTION_INIT_CAPTURE
                        putExtra(OverlayService.EXTRA_RESULT_CODE, resultCode)
                        putExtra(OverlayService.EXTRA_DATA, data)
                    }
                    startService(intent)
                } else {
                    statusText.text = "⚠ 截屏权限被拒绝, 只能使用手动输入"
                }
                refreshButtons(overlayBtn, projectionBtn, refreshBtn)
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
