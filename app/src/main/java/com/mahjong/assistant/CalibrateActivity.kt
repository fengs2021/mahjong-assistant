package com.mahjong.assistant

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.mahjong.assistant.capture.TileMatcher
import com.mahjong.assistant.capture.CaptureHelper
import com.mahjong.assistant.engine.Tiles
import java.io.File

/**
 * 模板校准 — 逐张截取34种牌的模板图
 *
 * 流程:
 *   1. 用户先在雀魂里摆好一张待校准的手牌 (全部是同一种牌最好)
 *   2. 点"截取模板" → 自动截屏 → 识别牌区域
 *   3. 用户确认每张牌对应的tileId → 保存为模板
 *   4. 重复直到34种牌全部校准完毕
 */
class CalibrateActivity : AppCompatActivity() {

    companion object {
        const val REQUEST_CAPTURE = 3001
    }

    private lateinit var mediaProjectionManager: MediaProjectionManager
    private lateinit var statusText: TextView
    private lateinit var templateGrid: LinearLayout
    private lateinit var progressText: TextView

    // 已校准的牌
    private val calibratedTiles = mutableSetOf<Int>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        mediaProjectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF1A3A1A.toInt())
            setPadding(16, 30, 16, 16)
        }.also { root ->
            // 标题
            TextView(this).apply {
                text = "📷 模板校准"
                textSize = 20f
                setTextColor(0xFFA0F0A0.toInt())
                gravity = Gravity.CENTER
                setPadding(0, 0, 0, 8)
            }.also { root.addView(it) }

            progressText = TextView(this).apply {
                text = "已校准: 0/34"
                textSize = 14f
                setTextColor(0xFF00CC66.toInt())
                gravity = Gravity.CENTER
                setPadding(0, 0, 0, 12)
            }.also { root.addView(it) }

            // 说明
            TextView(this).apply {
                text = "请在雀魂中打开手牌界面，\n确保手牌清晰可见，然后截取。\n\n推荐: 组一副全是同种牌的手牌\n(如全是1m)，一次校准一种。"
                textSize = 12f
                setTextColor(0xFF6A9A6A.toInt())
                setPadding(8, 8, 8, 12)
            }.also { root.addView(it) }

            // 截取按钮
            Button(this).apply {
                text = "📸 截取模板"
                textSize = 18f
                setBackgroundColor(0xFF00CC66.toInt())
                setTextColor(0xFF0A2A0A.toInt())
                setPadding(0, 14, 0, 14)
                setOnClickListener { startScreenCapture() }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }.also { root.addView(it) }

            // 快速校准区 — 逐牌按钮
            TextView(this).apply {
                text = "\n快速校准: 点击牌名开始截取"
                textSize = 12f
                setTextColor(0xFF6A9A6A.toInt())
                setPadding(0, 12, 0, 4)
            }.also { root.addView(it) }

            templateGrid = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
            }
            buildQuickCalibrateGrid(templateGrid)
            root.addView(templateGrid)

            // 状态
            statusText = TextView(this).apply {
                text = "● 就绪"
                textSize = 11f
                setTextColor(0xFF6A9A6A.toInt())
                gravity = Gravity.CENTER
                setPadding(0, 16, 0, 0)
            }.also { root.addView(it) }
        })

        // 加载已校准状态
        refreshCalibratedStatus()
    }

    private fun buildQuickCalibrateGrid(container: LinearLayout) {
        val suits = listOf(
            "萬" to (0..8),
            "筒" to (9..17),
            "索" to (18..26),
            "字" to (27..33)
        )

        for ((label, range) in suits) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, 2, 0, 2)
                gravity = Gravity.CENTER_VERTICAL
            }

            TextView(this).apply {
                text = label
                textSize = 10f
                setTextColor(0xFF6A9A6A.toInt())
                layoutParams = LinearLayout.LayoutParams(28, LinearLayout.LayoutParams.WRAP_CONTENT)
            }.also { row.addView(it) }

            for (tileId in range) {
                Button(this).apply {
                    text = Tiles.name(tileId)
                    textSize = 9f
                    minWidth = 0
                    setPadding(4, 2, 4, 2)
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { marginEnd = 1 }
                    updateCalibrateButton(this, tileId)
                    setOnClickListener { quickCalibrate(tileId) }
                }.also { row.addView(it) }
            }
            container.addView(row)
        }
    }

    private fun updateCalibrateButton(btn: Button, tileId: Int) {
        if (tileId in calibratedTiles) {
            btn.setBackgroundColor(0xFF00CC66.toInt())
            btn.setTextColor(0xFF0A2A0A.toInt())
        } else {
            btn.setBackgroundColor(0xFF2D2D2D.toInt())
            btn.setTextColor(0xFF888888.toInt())
        }
    }

    private fun refreshCalibratedStatus() {
        calibratedTiles.clear()
        for (id in 0..33) {
            val file = File(filesDir, "tile_templates/tile_${id}.png")
            if (file.exists()) calibratedTiles.add(id)
        }
        progressText.text = "已校准: ${calibratedTiles.size}/34"

        // 刷新按钮颜色
        val grid = templateGrid
        for (i in 0 until grid.childCount) {
            val row = grid.getChildAt(i) as? LinearLayout ?: continue
            for (j in 1 until row.childCount) {
                val btn = row.getChildAt(j) as? Button ?: continue
                val tileId = getTileIdFromBtn(btn, i)
                if (tileId >= 0) updateCalibrateButton(btn, tileId)
            }
        }
    }

    private fun getTileIdFromBtn(btn: Button, rowIndex: Int): Int {
        val rowStart = when (rowIndex) {
            0 -> 0; 1 -> 9; 2 -> 18; 3 -> 27; else -> -1
        }
        if (rowStart < 0) return -1
        val name = btn.text.toString()
        return Tiles.parse(name).firstOrNull() ?: -1
    }

    private fun quickCalibrate(tileId: Int) {
        statusText.text = "● 准备截取 ${Tiles.name(tileId)}"
        startScreenCapture()
    }

    private fun startScreenCapture() {
        startActivityForResult(
            mediaProjectionManager.createScreenCaptureIntent(),
            REQUEST_CAPTURE
        )
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == REQUEST_CAPTURE && resultCode == RESULT_OK && data != null) {
            statusText.text = "● 截屏中..."
            val projection = mediaProjectionManager.getMediaProjection(resultCode, data)

            // 执行截屏和分析
            CaptureHelper.captureAndCalibrate(this, projection) { bitmap ->
                if (bitmap != null) {
                    processCalibration(bitmap)
                } else {
                    statusText.text = "● 截屏失败"
                }
            }
        }
    }

    private fun processCalibration(screenshot: Bitmap) {
        // 找牌区域
        val regions = TileMatcher.findTileRegions(screenshot)
        if (regions.isEmpty()) {
            statusText.text = "● 未检测到牌区域，请重试"
            screenshot.recycle()
            return
        }

        statusText.text = "● 检测到 ${regions.size} 块牌区域"

        // 显示截图让用户手动标记每块牌
        // 简化: 按从左到右依次弹出对话框选择牌型
        showManualLabelDialog(screenshot, regions.sortedBy { it.x }, 0)
    }

    private fun showManualLabelDialog(
        screenshot: Bitmap,
        regions: List<TileMatcher.RectRegion>,
        currentIndex: Int
    ) {
        if (currentIndex >= regions.size) {
            screenshot.recycle()
            refreshCalibratedStatus()
            statusText.text = "● 校准完成！已保存 ${calibratedTiles.size} 种牌"
            Toast.makeText(this, "校准完成", Toast.LENGTH_SHORT).show()
            return
        }

        val region = regions[currentIndex]
        val tileBitmap = Bitmap.createBitmap(screenshot, region.x, region.y, region.w, region.h)

        // 弹出牌选择对话框
        val tileNames = (0..33).map { Tiles.name(it) }.toTypedArray()

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("牌 #${currentIndex + 1}/${regions.size}")
            .setItems(tileNames) { _, which ->
                TileMatcher.saveTemplate(this, screenshot, region.x, region.y, region.w, region.h, which)
                calibratedTiles.add(which)
                statusText.text = "● 已保存: ${Tiles.name(which)} (${currentIndex + 1}/${regions.size})"
                tileBitmap.recycle()
                showManualLabelDialog(screenshot, regions, currentIndex + 1)
            }
            .setNegativeButton("跳过") { _, _ ->
                tileBitmap.recycle()
                showManualLabelDialog(screenshot, regions, currentIndex + 1)
            }
            .setCancelable(false)
            .show()
    }
}
