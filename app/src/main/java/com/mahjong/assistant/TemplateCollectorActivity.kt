package com.mahjong.assistant

import android.app.AlertDialog
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.os.Environment
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.mahjong.assistant.capture.TileMatcher
import com.mahjong.assistant.engine.Tiles
import java.io.File
import java.io.FileOutputStream

/**
 * 模板采集器：加载截图 → 自动分割14张手牌 → 识别+手动标注 → 一键保存模板
 *
 * 工作流：
 * 1. 输入截图路径 (默认 /sdcard/Download/ 下的图片)
 * 2. 自动按手牌坐标分割 (横屏PPG-AN00: 98×143)
 * 3. 每张牌显示裁剪图 + 自动识别结果 + 下拉修改
 * 4. 保存到 /sdcard/Download/mahjong_templates/
 */
class TemplateCollectorActivity : AppCompatActivity() {

    // 手牌坐标 (横屏 PPG-AN00 2800×1264)
    companion object {
        const val BASE_X = 536       // 第一张牌 slotLeft
        const val FACE_OFFSET = 5   // slot → face 偏移
        const val SLOT_GAP = 111     // 牌间间隔
        const val FACE_Y = 1106     // 牌面上边缘
        const val FACE_W = 98       // 牌面宽
        const val FACE_H = 143      // 牌面高
        const val DRAWN_SLOT_X = 2014
    }

    private lateinit var pathInput: EditText
    private lateinit var tileGrid: LinearLayout
    private lateinit var statusLabel: TextView
    private val tileLabels = mutableMapOf<Int, Spinner>()   // slot → spinner
    private val tileImages = mutableMapOf<Int, Bitmap>()    // slot → cropped bitmap
    private var currentBitmap: Bitmap? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(createLayout())
    }

    private fun createLayout(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF1A2E1A.toInt())
            setPadding(16, 40, 16, 16)
        }.also { root ->
            // 标题
            TextView(this).apply {
                text = "📷 模板采集器"; textSize = 18f
                setTextColor(0xFF5CFF5C.toInt()); gravity = Gravity.CENTER
                setPadding(0, 0, 0, 12)
            }.also { root.addView(it) }

            // 路径输入行
            val pathRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            pathInput = EditText(this).apply {
                hint = "/sdcard/Download/screenshot.jpg"
                setTextColor(0xFFA0F0A0.toInt()); setHintTextColor(0xFF446644.toInt())
                setBackgroundColor(0xFF2D3A2D.toInt())
                setPadding(12, 8, 12, 8); textSize = 13f
                // 预填Download目录下最近的文件
                val recent = findLatestScreenshot()
                if (recent != null) setText(recent)
            }
            pathRow.addView(pathInput, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

            Button(this).apply {
                text = "加载"; textSize = 13f
                setBackgroundColor(0xFF2D6A2D.toInt()); setTextColor(0xFF5CFF5C.toInt())
                setPadding(16, 8, 16, 8)
                setOnClickListener { loadAndSplit() }
            }.also { pathRow.addView(it) }
            root.addView(pathRow)

            // 状态
            statusLabel = TextView(this).apply {
                text = "输入截图路径后点「加载」"
                textSize = 11f; setTextColor(0xFF80B080.toInt())
                setPadding(0, 8, 0, 8); gravity = Gravity.CENTER
            }.also { root.addView(it) }

            // 牌网格 (ScrollView内)
            val scroll = ScrollView(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
                )
            }
            tileGrid = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
            }
            scroll.addView(tileGrid)
            root.addView(scroll)

            // 保存按钮
            Button(this).apply {
                text = "💾 保存全部模板"; textSize = 14f
                setBackgroundColor(0xFF2D6A2D.toInt()); setTextColor(0xFF5CFF5C.toInt())
                setPadding(0, 12, 0, 12)
                setOnClickListener { saveAll() }
            }.also { root.addView(it) }
        }
    }

    private fun findLatestScreenshot(): String? {
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).absolutePath)
        if (!dir.exists()) return null
        val images = dir.listFiles { f ->
            f.isFile && (f.name.endsWith(".jpg") || f.name.endsWith(".jpeg") || f.name.endsWith(".png"))
        } ?: return null
        return images.maxByOrNull { it.lastModified() }?.absolutePath
    }

    private fun loadAndSplit() {
        val path = pathInput.text.toString().trim()
        if (path.isEmpty()) {
            Toast.makeText(this, "请输入截图路径", Toast.LENGTH_SHORT).show()
            return
        }
        val file = File(path)
        if (!file.exists()) {
            statusLabel.text = "✗ 文件不存在: $path"
            return
        }

        try {
            // 加载截图 (降采样节省内存)
            val opts = BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            currentBitmap = BitmapFactory.decodeFile(path, opts)
            if (currentBitmap == null) {
                statusLabel.text = "✗ 无法解码图片"
                return
            }
            val w = currentBitmap!!.width
            val h = currentBitmap!!.height
            statusLabel.text = "截图 ${w}×${h}，分割手牌..."

            tileGrid.removeAllViews()
            tileImages.clear()
            tileLabels.clear()

            // 分割13张主手牌
            for (i in 0..12) {
                val faceLeft = BASE_X + FACE_OFFSET + i * SLOT_GAP
                val faceRight = faceLeft + FACE_W
                val faceBottom = FACE_Y + FACE_H
                if (faceRight > w || faceBottom > h) continue

                val tileBmp = Bitmap.createBitmap(currentBitmap!!, faceLeft, FACE_Y, FACE_W, FACE_H)
                tileImages[i] = tileBmp
                addTileRow(i, tileBmp)
            }

            // 摸牌 (slot 13)
            val drawnFaceLeft = DRAWN_SLOT_X + FACE_OFFSET
            val drawnFaceRight = drawnFaceLeft + FACE_W
            val drawnFaceBottom = FACE_Y + FACE_H
            if (drawnFaceRight <= w && drawnFaceBottom <= h) {
                val drawnBmp = Bitmap.createBitmap(currentBitmap!!, drawnFaceLeft, FACE_Y, FACE_W, FACE_H)
                tileImages[13] = drawnBmp
                addTileRow(13, drawnBmp, isDrawn = true)
            }

            statusLabel.text = "已分割 ${tileImages.size} 张牌，请确认标注"
        } catch (e: Exception) {
            statusLabel.text = "✗ 加载失败: ${e.message}"
        }
    }

    private fun addTileRow(slot: Int, bmp: Bitmap, isDrawn: Boolean = false) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(4, 4, 4, 4)
        }

        // 标签
        val label = TextView(this).apply {
            text = if (isDrawn) "摸" else "牌${slot + 1}"
            textSize = 10f; setTextColor(0xFF80B080.toInt())
            setPadding(0, 0, 8, 0)
            layoutParams = LinearLayout.LayoutParams(dp(36), LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        row.addView(label)

        // 裁剪图 (缩放到可视大小)
        val iv = ImageView(this).apply {
            val scaled = Bitmap.createScaledBitmap(bmp, dp(32), dp(47), true)
            setImageBitmap(scaled)
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(0xFF1A2A1A.toInt())
            layoutParams = LinearLayout.LayoutParams(dp(36), dp(52)).apply { marginEnd = 8 }
        }
        row.addView(iv)

        // 自动识别
        val autoName = try {
            identifyTile(bmp)
        } catch (_: Exception) { "?" }

        // 下拉选择器
        val spinner = Spinner(this).apply {
            val allNames = (0..33).map { Tiles.name(it) }
            val adapter = ArrayAdapter(this@TemplateCollectorActivity, android.R.layout.simple_spinner_item, allNames)
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            this.adapter = adapter
            // 选中自动识别的结果
            val idx = allNames.indexOf(autoName)
            if (idx >= 0) setSelection(idx)
            setBackgroundColor(0xFF2D3A2D.toInt())
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        row.addView(spinner)
        tileLabels[slot] = spinner

        // 识别状态
        val status = TextView(this).apply {
            text = if (autoName != "?") "✓$autoName" else "?"
            textSize = 10f
            setTextColor(if (autoName != "?") 0xFF5CFF5C.toInt() else 0xFFFFAA55.toInt())
            setPadding(8, 0, 0, 0)
            layoutParams = LinearLayout.LayoutParams(dp(70), LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        row.addView(status)

        tileGrid.addView(row)
    }

    /** 用模板匹配识别单张牌 */
    private fun identifyTile(bmp: Bitmap): String {
        return try {
            val result = TileMatcher.identifySingleTile(bmp)
            if (result != null && result.first >= 0) Tiles.name(result.first) else "?"
        } catch (_: Exception) { "?" }
    }

    private fun saveAll() {
        if (tileImages.isEmpty()) {
            Toast.makeText(this, "请先加载截图", Toast.LENGTH_SHORT).show()
            return
        }

        val outDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "mahjong_templates")
        if (!outDir.exists()) outDir.mkdirs()

        var saved = 0
        val report = StringBuilder()
        for ((slot, bmp) in tileImages) {
            val spinner = tileLabels[slot] ?: continue
            val tileName = spinner.selectedItem as String
            // 跳过未标注的
            if (tileName == "?") continue

            val fileName = "${tileName}_hand_${slot}.png"
            val outFile = File(outDir, fileName)
            try {
                FileOutputStream(outFile).use { fos ->
                    bmp.compress(Bitmap.CompressFormat.PNG, 100, fos)
                }
                saved++
                report.appendLine("  ✓ $fileName")
            } catch (e: Exception) {
                report.appendLine("  ✗ $fileName: ${e.message}")
            }
        }

        val msg = "保存 $saved/${tileImages.size} 张到\n${outDir.absolutePath}\n\n$report"
        AlertDialog.Builder(this)
            .setTitle("保存完成")
            .setMessage(msg)
            .setPositiveButton("确定", null)
            .show()
        statusLabel.text = "已保存 $saved 张 → ${outDir.absolutePath}"
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        super.onDestroy()
        currentBitmap?.recycle()
    }
}
