package com.mahjong.assistant

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.widget.*
import android.widget.ImageView
import androidx.appcompat.app.AppCompatActivity
import com.mahjong.assistant.engine.Tiles
import com.mahjong.assistant.overlay.OverlayService
import java.io.File

/**
 * 识别结果审核编辑界面
 * 接收 TileMatcher 的识别结果 → 用户修改 → 确认后发给悬浮窗
 */
class ReviewActivity : AppCompatActivity() {

    private val currentTiles = mutableListOf<Int>()
    private val tileConfidences = mutableMapOf<Int, Double>() // tileId → 最新置信度
    private lateinit var tileContainer: LinearLayout
    private lateinit var statusLabel: TextView
    private lateinit var confirmBtn: Button

    companion object {
        const val EXTRA_TILE_IDS = "tile_ids"
        const val EXTRA_CONFIDENCES = "confidences"
        const val EXTRA_LOG = "detect_log"
        const val EXTRA_SCREENSHOT_PATH = "screenshot_path"
    }

    private lateinit var logLabel: TextView
    private var screenshotPath: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 先读截图路径
        screenshotPath = intent.getStringExtra(EXTRA_SCREENSHOT_PATH)

        setContentView(createLayout())

        // 加载识别结果
        val tileIds = intent.getIntArrayExtra(EXTRA_TILE_IDS) ?: intArrayOf()
        val confidences = intent.getDoubleArrayExtra(EXTRA_CONFIDENCES) ?: doubleArrayOf()

        for (i in tileIds.indices) {
            if (tileIds[i] in 0..33) {
                currentTiles.add(tileIds[i])
                if (i < confidences.size) {
                    tileConfidences[tileIds[i]] = maxOf(
                        tileConfidences[tileIds[i]] ?: 0.0,
                        confidences[i]
                    )
                }
            }
        }
        currentTiles.sort()

        // 显示检测日志
        val detectLog = intent.getStringExtra(EXTRA_LOG) ?: ""
        if (detectLog.isNotEmpty()) {
            logLabel.text = detectLog
        }

        refreshTileBar()
    }

    private fun createLayout(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF1A2E1A.toInt())
            setPadding(16, 40, 16, 16)
        }.also { root ->
            // 标题
            TextView(this).apply {
                text = "🔍 识别结果审核"
                textSize = 20f
                setTextColor(0xFFA0F0A0.toInt())
                gravity = Gravity.CENTER
                setPadding(0, 0, 0, 8)
            }.also { root.addView(it) }

            TextView(this).apply {
                text = "点击牌可替换 · 确认前请核对手牌正确"
                textSize = 12f
                setTextColor(0xFF6A9A6A.toInt())
                gravity = Gravity.CENTER
                setPadding(0, 0, 0, 16)
            }.also { root.addView(it) }

            // 截图预览 (对照用)
            val screenshotView = ImageView(this).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER
                adjustViewBounds = true
                setBackgroundColor(0xFF1A2A1A.toInt())
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dp(160) // 固定高度约160dp
                ).apply { bottomMargin = dp(8) }
                setOnClickListener {
                    // 点击放大全屏查看
                    showScreenshotFullscreen()
                }
            }
            root.addView(screenshotView)
            loadScreenshot(screenshotView)

            // 牌显示区域
            val scrollWrapper = HorizontalScrollView(this).apply {
                setBackgroundColor(0xFF2D5A2D.toInt())
                setPadding(8, 4, 8, 4)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }

            tileContainer = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(4, 8, 4, 8)
            }
            scrollWrapper.addView(tileContainer)
            root.addView(scrollWrapper)

            // 状态
            statusLabel = TextView(this).apply {
                text = ""
                textSize = 13f
                setTextColor(0xFFA0F0A0.toInt())
                gravity = Gravity.CENTER
                setPadding(0, 8, 0, 0)
            }.also { root.addView(it) }

            // 按钮行
            val btnRow1 = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, 16, 0, 8)
            }

            Button(this).apply {
                text = "＋ 添加牌"
                setBackgroundColor(0xFF2D5A2D.toInt())
                setTextColor(0xFFA0F0A0.toInt())
                setOnClickListener { showAddTileDialog() }
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    .apply { marginEnd = 8 }
            }.also { btnRow1.addView(it) }

            Button(this).apply {
                text = "－ 删除"
                setBackgroundColor(0xFF3A2D2D.toInt())
                setTextColor(0xFFF0A0A0.toInt())
                setOnClickListener { deleteLastTile() }
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }.also { btnRow1.addView(it) }

            root.addView(btnRow1)

            // 确认/取消
            val btnRow2 = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, 8, 0, 0)
            }

            confirmBtn = Button(this).apply {
                text = buildConfirmText()
                textSize = 16f
                setBackgroundColor(0xFF00CC66.toInt())
                setTextColor(0xFF0A2A0A.toInt())
                setOnClickListener { confirmAndSend() }
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 2f)
                    .apply { marginEnd = 8 }
            }.also { btnRow2.addView(it) }

            Button(this).apply {
                text = "返回"
                textSize = 16f
                setBackgroundColor(0xFF2D2D2D.toInt())
                setTextColor(0xFF888888.toInt())
                setOnClickListener { finish() }
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }.also { btnRow2.addView(it) }

            root.addView(btnRow2)

            // 检测日志 (可折叠)
            logLabel = TextView(this).apply {
                text = ""
                textSize = 10f
                setTextColor(0xFF6A9A6A.toInt())
                setBackgroundColor(0xFF1A2A1A.toInt())
                setPadding(dp(8), dp(8), dp(8), dp(8))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(8) }
            }
            root.addView(logLabel)
        }
    }

    private fun refreshTileBar() {
        tileContainer.removeAllViews()

        for ((index, tileId) in currentTiles.withIndex()) {
            val conf = tileConfidences[tileId] ?: 0.0
            val bgColor = when {
                conf >= 0.80 -> 0xFF1A3D1A.toInt() // 绿色高置信
                conf >= 0.55 -> 0xFF3D3D1A.toInt() // 黄色中等
                else -> 0xFF3D1A1A.toInt()         // 红色需确认
            }

            val btn = Button(this).apply {
                text = Tiles.name(tileId)
                textSize = 16f
                setTextColor(when {
                    conf >= 0.80 -> 0xFF00CC66.toInt()
                    conf >= 0.55 -> 0xFFCCAA00.toInt()
                    else -> 0xFFCC3333.toInt()
                })
                setBackgroundColor(bgColor)
                minWidth = 0; minHeight = 0
                setPadding(dp(10), dp(6), dp(10), dp(6))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = dp(4) }

                setOnClickListener { replaceTile(index) }
            }
            tileContainer.addView(btn)
        }

        // 空白占位（保持区域高度）
        if (currentTiles.isEmpty()) {
            TextView(this).apply {
                text = "  (空 — 请添加牌)"
                textSize = 14f
                setTextColor(0xFF6A6A6A.toInt())
                setPadding(dp(8), dp(8), dp(8), dp(8))
            }.also { tileContainer.addView(it) }
        }

        updateStatus()
    }

    private fun updateStatus() {
        val total = currentTiles.size
        val canConfirm = total in 13..14

        val handStr = if (currentTiles.isNotEmpty()) {
            Tiles.toDisplayString(currentTiles.sorted().toIntArray())
        } else "—"

        val lowConf = currentTiles.count { (tileConfidences[it] ?: 0.0) < 0.55 }

        statusLabel.text = buildString {
            append("手牌: $handStr  (${total}张)")
            if (lowConf > 0) append("  ⚠${lowConf}张低置信度")
        }

        confirmBtn.text = buildConfirmText()
        confirmBtn.isEnabled = canConfirm
        confirmBtn.setBackgroundColor(if (canConfirm) 0xFF00CC66.toInt() else 0xFF2D5A2D.toInt())
        confirmBtn.setTextColor(if (canConfirm) 0xFF0A2A0A.toInt() else 0xFF6A9A6A.toInt())
    }

    private fun buildConfirmText(): String {
        val total = currentTiles.size
        return when {
            total == 13 -> "✅ 确认 (13张 — 未摸牌)"
            total == 14 -> "✅ 确认分析 (14张)"
            else -> "确认 (需13-14张, 当前${total}张)"
        }
    }

    private fun replaceTile(index: Int) {
        showReplaceDialog { newTileId ->
            val oldTileId = currentTiles[index]
            currentTiles[index] = newTileId
            currentTiles.sort()
            if (currentTiles.count { it == oldTileId } == 0) {
                tileConfidences.remove(oldTileId)
            }
            refreshTileBar()
        }
    }

    private fun showReplaceDialog(onPicked: (Int) -> Unit) {
        val scroll = ScrollView(this).apply { setPadding(dp(16), dp(12), dp(16), dp(12)) }
        val grid = GridLayout(this).apply { columnCount = 4 }

        for (tileId in 0..33) {
            val cnt = currentTiles.count { it == tileId }
            val btn = Button(this).apply {
                text = if (cnt > 0) "${Tiles.name(tileId)}×$cnt" else Tiles.name(tileId)
                textSize = 14f
                setBackgroundColor(if (cnt >= 4) 0xFF2D2D2D.toInt() else 0xFF2D5A2D.toInt())
                setTextColor(if (cnt >= 4) 0xFF888888.toInt() else 0xFFA0F0A0.toInt())
                isEnabled = cnt < 4
                minWidth = 0; minHeight = 0
                setPadding(dp(14), dp(8), dp(14), dp(8))
                layoutParams = GridLayout.LayoutParams().apply {
                    width = 0; height = GridLayout.LayoutParams.WRAP_CONTENT
                    columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                    setMargins(dp(4), dp(4), dp(4), dp(4))
                }
                tag = tileId
            }
            btn.setOnClickListener {
                onPicked(tileId)
                (it.parent?.parent?.parent as? AlertDialog)?.dismiss()
            }
            grid.addView(btn)
        }

        scroll.addView(grid)
        AlertDialog.Builder(this)
            .setTitle("替换为")
            .setView(scroll)
            .setNegativeButton("取消", null)
            .show()
    }

    private fun deleteLastTile() {
        if (currentTiles.isNotEmpty()) {
            val removed = currentTiles.removeAt(currentTiles.lastIndex)
            if (currentTiles.count { it == removed } == 0) {
                tileConfidences.remove(removed)
            }
            refreshTileBar()
        }
    }

    private fun showAddTileDialog() {
        if (currentTiles.size >= 14) {
            Toast.makeText(this, "最多14张", Toast.LENGTH_SHORT).show()
            return
        }
        showTilePicker { tileId ->
            if (currentTiles.count { it == tileId } >= 4) {
                Toast.makeText(this, "${Tiles.name(tileId)} 最多4张", Toast.LENGTH_SHORT).show()
                return@showTilePicker
            }
            currentTiles.add(tileId)
            currentTiles.sort()
            refreshTileBar()
        }
    }

    private fun showTilePicker(onPicked: (Int) -> Unit) {
        val scroll = ScrollView(this).apply {
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        val grid = GridLayout(this).apply {
            columnCount = 4
            useDefaultMargins = false
        }

        val allTiles = (0..33).toList()
        val buttons = mutableMapOf<Int, Button>()

        for (tileId in allTiles) {
            val cnt = currentTiles.count { it == tileId }
            val btn = Button(this).apply {
                text = if (cnt > 0) "${Tiles.name(tileId)}×$cnt" else Tiles.name(tileId)
                textSize = 14f
                setBackgroundColor(if (cnt >= 4) 0xFF2D2D2D.toInt() else 0xFF2D5A2D.toInt())
                setTextColor(if (cnt >= 4) 0xFF888888.toInt() else 0xFFA0F0A0.toInt())
                minWidth = 0; minHeight = 0
                setPadding(dp(14), dp(8), dp(14), dp(8))
                val params = GridLayout.LayoutParams().apply {
                    width = 0
                    height = GridLayout.LayoutParams.WRAP_CONTENT
                    columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                    setMargins(dp(4), dp(4), dp(4), dp(4))
                }
                layoutParams = params
            }
            buttons[tileId] = btn
            grid.addView(btn)
        }

        scroll.addView(grid)

        val dialog = AlertDialog.Builder(this)
            .setTitle("选择牌 (满4张再点归零)")
            .setView(scroll)
            .setNegativeButton("取消", null)
            .create()

        // 绑定toggle点击
        for ((tileId, btn) in buttons) {
            btn.setOnClickListener {
                val cnt = currentTiles.count { it == tileId }
                if (cnt >= 4) {
                    // 满4张 → 全删
                    currentTiles.removeAll { it == tileId }
                } else {
                    if (currentTiles.size >= 14) {
                        Toast.makeText(this, "最多14张", Toast.LENGTH_SHORT).show()
                        return@setOnClickListener
                    }
                    currentTiles.add(tileId)
                }
                currentTiles.sort()
                refreshTileBar()
                // 更新按钮
                val newCnt = currentTiles.count { it == tileId }
                btn.text = if (newCnt > 0) "${Tiles.name(tileId)}×$newCnt" else Tiles.name(tileId)
                btn.setBackgroundColor(if (newCnt >= 4) 0xFF2D2D2D.toInt() else 0xFF2D5A2D.toInt())
                btn.setTextColor(if (newCnt >= 4) 0xFF888888.toInt() else 0xFFA0F0A0.toInt())
            }
        }

        dialog.show()
    }

    private fun confirmAndSend() {
        val total = currentTiles.size
        if (total !in 13..14) {
            Toast.makeText(this, "需要13或14张牌", Toast.LENGTH_SHORT).show()
            return
        }

        val hand = currentTiles.sorted().toIntArray()

        // 发给 OverlayService
        val intent = Intent(this, OverlayService::class.java).apply {
            action = OverlayService.ACTION_UPDATE
            putExtra(OverlayService.EXTRA_HAND, hand)
        }
        try {
            startService(intent)
        } catch (_: Exception) {}

        setResult(Activity.RESULT_OK)
        finish()
    }

    private fun loadScreenshot(view: ImageView) {
        val path = screenshotPath ?: return
        val file = File(path)
        if (!file.exists()) return

        try {
            val opts = BitmapFactory.Options().apply {
                inSampleSize = 4 // 缩至1/4节省内存
            }
            val bitmap = BitmapFactory.decodeFile(path, opts)
            if (bitmap != null) {
                view.setImageBitmap(bitmap)
            }
        } catch (_: Exception) {}
    }

    private fun showScreenshotFullscreen() {
        val path = screenshotPath ?: return
        val file = File(path)
        if (!file.exists()) return

        val dialogView = ImageView(this).apply {
            setBackgroundColor(0xFF000000.toInt())
            scaleType = ImageView.ScaleType.FIT_CENTER
            try {
                setImageBitmap(BitmapFactory.decodeFile(path))
            } catch (_: Exception) {}
        }

        AlertDialog.Builder(this)
            .setView(dialogView)
            .setPositiveButton("关闭") { d, _ -> d.dismiss() }
            .create().apply {
                window?.setLayout(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.MATCH_PARENT
                )
            }.show()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
