package com.mahjong.assistant

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.mahjong.assistant.engine.Tiles

/**
 * 手动输入手牌 — 点击牌按钮组成14张手牌
 * 每行牌在HorizontalScrollView中可左右拖动
 */
class ManualInputActivity : AppCompatActivity() {

    private val currentTiles = mutableListOf<Int>()
    private lateinit var handLabel: TextView
    private lateinit var analyzeBtn: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(createLayout())
    }

    private fun createLayout(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF1A3A1A.toInt())
            setPadding(12, 30, 12, 12)
        }.also { root ->
            TextView(this).apply {
                text = "手动输入手牌"
                textSize = 18f
                setTextColor(0xFFA0F0A0.toInt())
                gravity = Gravity.CENTER
                setPadding(0, 0, 0, 12)
            }.also { root.addView(it) }

            handLabel = TextView(this).apply {
                text = "请点击牌按钮 (0/14)"
                textSize = 15f
                setTextColor(0xFFA0F0A0.toInt())
                setBackgroundColor(0xFF2D5A2D.toInt())
                gravity = Gravity.CENTER
                setPadding(8, 12, 8, 12)
                minHeight = 48
            }
            root.addView(handLabel)

            // 可垂直滚动的整体容器
            val scrollContainer = ScrollView(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
                )
            }

            val grid = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, 8, 0, 8)
            }

            // 每行: 标签 + [HorizontalScrollView > 牌按钮]
            addTileRow(grid, "萬", 0..8)
            addTileRow(grid, "筒", 9..17)
            addTileRow(grid, "索", 18..26)
            addTileRow(grid, "字", 27..33, true)

            scrollContainer.addView(grid)
            root.addView(scrollContainer)

            // 控制按钮
            val ctrlRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                setPadding(0, 8, 0, 0)
            }

            Button(this).apply {
                text = "← 删除"
                setBackgroundColor(0xFF3A6A3A.toInt())
                setTextColor(0xFFA0F0A0.toInt())
                setOnClickListener { deleteTile() }
            }.also { ctrlRow.addView(it) }

            Button(this).apply {
                text = "清空"
                setBackgroundColor(0xFF3A6A3A.toInt())
                setTextColor(0xFFA0F0A0.toInt())
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginStart = 8 }
                setOnClickListener { clearTiles() }
            }.also { ctrlRow.addView(it) }

            root.addView(ctrlRow)

            analyzeBtn = Button(this).apply {
                text = "分析 (需要14张)"
                textSize = 16f
                setBackgroundColor(0xFF3A6A3A.toInt())
                setTextColor(0xFF6A9A6A.toInt())
                isEnabled = false
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = 12 }
                setOnClickListener { analyze() }
            }.also { root.addView(it) }
        }
    }

    private fun addTileRow(
        container: LinearLayout,
        label: String,
        range: IntRange,
        isHonor: Boolean = false
    ) {
        // 行容器
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 6, 0, 6)
        }

        // 标签（固定在左侧）
        TextView(this).apply {
            text = label
            textSize = 14f
            setTextColor(0xFF6A9A6A.toInt())
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                dpToPx(36), LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }.also { row.addView(it) }

        // 可水平拖动的牌按钮区域
        val hsv = HorizontalScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            )
            isHorizontalScrollBarEnabled = false
            overScrollMode = android.view.View.OVER_SCROLL_ALWAYS
        }

        val tileRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        for (tileId in range) {
            val maxCount = 4
            Button(this).apply {
                text = if (isHonor) Tiles.name(tileId) else (tileId % 9 + 1).toString()
                textSize = 13f
                setBackgroundColor(0xFF2D5A2D.toInt())
                setTextColor(0xFFA0F0A0.toInt())
                minWidth = 0
                minHeight = 0
                setPadding(dpToPx(8), dpToPx(6), dpToPx(8), dpToPx(6))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = dpToPx(3) }

                setOnClickListener {
                    if (currentTiles.size >= 14) {
                        Toast.makeText(this@ManualInputActivity, "最多14张", Toast.LENGTH_SHORT).show()
                        return@setOnClickListener
                    }
                    if (currentTiles.count { it == tileId } >= maxCount) {
                        Toast.makeText(this@ManualInputActivity, "${Tiles.name(tileId)} 最多4张", Toast.LENGTH_SHORT).show()
                        return@setOnClickListener
                    }
                    currentTiles.add(tileId)
                    updateDisplay()
                }
            }.also { tileRow.addView(it) }
        }

        hsv.addView(tileRow)
        row.addView(hsv)
        container.addView(row)
    }

    private fun dpToPx(dp: Int): Int = (dp * resources.displayMetrics.density).toInt()

    private fun deleteTile() {
        if (currentTiles.isNotEmpty()) {
            currentTiles.removeAt(currentTiles.lastIndex)
            updateDisplay()
        }
    }

    private fun clearTiles() {
        currentTiles.clear()
        updateDisplay()
    }

    private fun updateDisplay() {
        val names = currentTiles.sorted().map { Tiles.name(it) }
        handLabel.text = if (names.isEmpty()) {
            "请点击牌按钮 (0/14)"
        } else {
            "${names.joinToString(" ")}  (${names.size}/14)"
        }

        val canAnalyze = currentTiles.size == 14
        analyzeBtn.isEnabled = canAnalyze
        analyzeBtn.setBackgroundColor(if (canAnalyze) 0xFF00CC66.toInt() else 0xFF3A6A3A.toInt())
        analyzeBtn.setTextColor(if (canAnalyze) 0xFF0A2A0A.toInt() else 0xFF6A9A6A.toInt())
        analyzeBtn.text = if (canAnalyze) "🔍 分析手牌" else "分析 (需要14张)"
    }

    private fun analyze() {
        if (currentTiles.size != 14) return
        val handArray = currentTiles.sorted().toIntArray()
        val intent = Intent().apply { putExtra("hand_tiles", handArray) }
        setResult(Activity.RESULT_OK, intent)
        finish()
    }
}
