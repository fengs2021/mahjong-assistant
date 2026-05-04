package com.mahjong.assistant

import android.app.AlertDialog
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.os.Environment
import android.view.Gravity
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import java.io.FileOutputStream

/**
 * 模板采集器 v2：3 Tab — 手牌/副露/河底
 * 加载截图 → 固定坐标网格自动分割 → 列表展示 → 用户标注 → 保存
 */
class TemplateCollectorActivity : AppCompatActivity() {

    companion object {
        // ─── 手牌 (PPG-AN00横屏 2800x1264) ───
        const val HAND_FACE_X = 541       // faceLeft (536+5)
        const val HAND_FACE_Y = 1106
        const val HAND_FACE_W = 98
        const val HAND_FACE_H = 143
        const val HAND_SLOT_GAP = 111
        const val HAND_DRAWN_X = 2019    // faceLeft

        // ─── 副露 (和scanMeldArea一致) ───
        const val MELD_X1 = 1150
        const val MELD_Y1 = 1000
        const val MELD_Y2 = 1269
        const val MELD_TILE_W = 104
        const val MELD_TILE_H = 112
        const val MELD_TILE_GAP = 6

        // ─── 河底 (4家) ───
        // 自家河底
        const val RIVER_SELF_X1 = 800; const val RIVER_SELF_X2 = 1100
        const val RIVER_SELF_Y1 = 432; const val RIVER_SELF_Y2 = 560
        // 下家河底(右)
        const val RIVER_SHIMO_X1 = 1550; const val RIVER_SHIMO_X2 = 1780
        const val RIVER_SHIMO_Y1 = 180; const val RIVER_SHIMO_Y2 = 500
        // 对家河底(上)
        const val RIVER_TOI_X1 = 1060; const val RIVER_TOI_X2 = 1740
        const val RIVER_TOI_Y1 = 120; const val RIVER_TOI_Y2 = 280
        // 上家河底(左)
        const val RIVER_KAMI_X1 = 1020; const val RIVER_KAMI_X2 = 1250
        const val RIVER_KAMI_Y1 = 180; const val RIVER_KAMI_Y2 = 500
        // 河底牌尺寸
        const val RIVER_TILE_W = 46; const val RIVER_TILE_H = 64
        const val RIVER_TILE_GAP = 3
    }

    private lateinit var pathInput: EditText
    private lateinit var statusLabel: TextView
    private lateinit var tileContainer: LinearLayout
    private lateinit var tabHand: Button
    private lateinit var tabMeld: Button
    private lateinit var tabRiver: Button
    private var currentBitmap: Bitmap? = null
    private var currentTab = "hand"

    // 切割结果
    data class TileSlice(val id: String, val bmp: Bitmap, val label: String)
    private val slices = mutableListOf<TileSlice>()

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
                setPadding(0, 0, 0, 8)
            }.also { root.addView(it) }

            // 路径行
            val pathRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            pathInput = EditText(this).apply {
                hint = "/sdcard/Download/screenshot.jpg"
                setTextColor(0xFFA0F0A0.toInt()); setHintTextColor(0xFF446644.toInt())
                setBackgroundColor(0xFF2D3A2D.toInt())
                setPadding(12, 8, 12, 8); textSize = 13f
                val recent = findLatestScreenshot()
                if (recent != null) setText(recent)
            }
            pathRow.addView(pathInput, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            Button(this).apply {
                text = "加载"; textSize = 13f
                setBackgroundColor(0xFF2D6A2D.toInt()); setTextColor(0xFF5CFF5C.toInt())
                setPadding(16, 8, 16, 8)
                setOnClickListener { loadAndSlice() }
            }.also { pathRow.addView(it) }
            root.addView(pathRow)

            // Tab行
            val tabRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, 8, 0, 4)
            }
            tabHand = makeTab("手牌", "hand", tabRow)
            tabMeld = makeTab("副露", "meld", tabRow)
            tabRiver = makeTab("河底", "river", tabRow)
            root.addView(tabRow)

            // 状态
            statusLabel = TextView(this).apply {
                text = "输入路径后点「加载」"
                textSize = 11f; setTextColor(0xFF80B080.toInt())
                setPadding(0, 4, 0, 4); gravity = Gravity.CENTER
            }.also { root.addView(it) }

            // 可滑动的内容区
            val scroll = ScrollView(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
                )
            }
            tileContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            scroll.addView(tileContainer)
            root.addView(scroll)

            // 保存
            Button(this).apply {
                text = "💾 保存全部 ($currentTab)"
                textSize = 14f
                setBackgroundColor(0xFF2D6A2D.toInt()); setTextColor(0xFF5CFF5C.toInt())
                setPadding(0, 12, 0, 12)
                setOnClickListener { saveAll() }
            }.also { root.addView(it) }
        }
    }

    private fun makeTab(label: String, tag: String, parent: LinearLayout): Button {
        return Button(this).apply {
            text = label; textSize = 12f; minWidth = 0
            setBackgroundColor(0xFF2D3A2D.toInt()); setTextColor(0xFF80B080.toInt())
            setPadding(dp(12), dp(4), dp(12), dp(4))
            setOnClickListener { switchTab(tag) }
            parent.addView(this, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = 4 })
        }
    }

    private fun switchTab(tag: String) {
        currentTab = tag
        for ((btn, t) in listOf(tabHand to "hand", tabMeld to "meld", tabRiver to "river")) {
            if (t == tag) {
                btn.setBackgroundColor(0xFF2D6A2D.toInt())
                btn.setTextColor(0xFF5CFF5C.toInt())
            } else {
                btn.setBackgroundColor(0xFF2D3A2D.toInt())
                btn.setTextColor(0xFF80B080.toInt())
            }
        }
        doSlice()
    }

    private fun loadAndSlice() {
        val path = pathInput.text.toString().trim()
        if (path.isEmpty()) { Toast.makeText(this, "输入截图路径", Toast.LENGTH_SHORT).show(); return }
        val file = File(path)
        if (!file.exists()) { statusLabel.text = "✗ 文件不存在"; return }
        try {
            currentBitmap?.recycle()
            currentBitmap = BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 })
            if (currentBitmap == null) { statusLabel.text = "✗ 无法解码"; return }
            val w = currentBitmap!!.width; val h = currentBitmap!!.height
            statusLabel.text = "截图 ${w}×${h} — $currentTab"
            doSlice()
        } catch (e: Exception) {
            statusLabel.text = "✗ ${e.message}"
        }
    }

    private fun doSlice() {
        val img = currentBitmap ?: return
        slices.clear()
        tileContainer.removeAllViews()

        when (currentTab) {
            "hand" -> sliceHand(img)
            "meld" -> sliceMeld(img)
            "river" -> sliceRiver(img)
        }

        // 渲染
        for ((i, s) in slices.withIndex()) {
            addSliceRow(i, s)
        }
        statusLabel.text = "截图 ${img.width}×${img.height} — $currentTab: ${slices.size} 张"
    }

    // ═══════ 手牌分割 ═══════
    private fun sliceHand(img: Bitmap) {
        val iw = img.width; val ih = img.height
        for (i in 0..12) {
            val x = HAND_FACE_X + i * HAND_SLOT_GAP
            if (x + HAND_FACE_W > iw || HAND_FACE_Y + HAND_FACE_H > ih) continue
            val bmp = Bitmap.createBitmap(img, x, HAND_FACE_Y, HAND_FACE_W, HAND_FACE_H)
            slices.add(TileSlice("牌${i+1}", bmp, "hand_$i"))
        }
        // 摸牌
        if (HAND_DRAWN_X + HAND_FACE_W <= iw) {
            val bmp = Bitmap.createBitmap(img, HAND_DRAWN_X, HAND_FACE_Y, HAND_FACE_W, HAND_FACE_H)
            slices.add(TileSlice("摸牌", bmp, "hand_drawn"))
        }
    }

    // ═══════ 副露分割 ═══════
    private fun sliceMeld(img: Bitmap) {
        val iw = img.width; val ih = img.height
        val meldX2 = iw
        val meldY2 = minOf(MELD_Y2, ih)
        val step = MELD_TILE_W + MELD_TILE_GAP

        var count = 0
        var x = MELD_X1
        while (x + MELD_TILE_W <= meldX2) {
            if (MELD_Y1 + MELD_TILE_H <= meldY2) {
                val bmp = Bitmap.createBitmap(img, x, MELD_Y1, MELD_TILE_W, MELD_TILE_H)
                slices.add(TileSlice("副露${count+1}", bmp, "meld_$count"))
                count++
            }
            x += step
        }
    }

    // ═══════ 河底分割 ═══════
    private fun sliceRiver(img: Bitmap) {
        val iw = img.width; val ih = img.height
        val step = RIVER_TILE_W + RIVER_TILE_GAP

        data class Roi(val name: String, val x1: Int, val y1: Int, val x2: Int, val y2: Int)
        val rois = listOf(
            Roi("自家", RIVER_SELF_X1, RIVER_SELF_Y1, RIVER_SELF_X2, RIVER_SELF_Y2),
            Roi("下家", RIVER_SHIMO_X1, RIVER_SHIMO_Y1, RIVER_SHIMO_X2, RIVER_SHIMO_Y2),
            Roi("对家", RIVER_TOI_X1, RIVER_TOI_Y1, RIVER_TOI_X2, RIVER_TOI_Y2),
            Roi("上家", RIVER_KAMI_X1, RIVER_KAMI_Y1, RIVER_KAMI_X2, RIVER_KAMI_Y2),
        )

        var count = 0
        for (roi in rois) {
            val x2 = minOf(roi.x2, iw)
            val y2 = minOf(roi.y2, ih)
            val rows = (y2 - roi.y1) / (RIVER_TILE_H + RIVER_TILE_GAP)
            if (rows < 1) continue

            for (row in 0 until rows) {
                val y = roi.y1 + row * (RIVER_TILE_H + RIVER_TILE_GAP)
                var x = roi.x1
                while (x + RIVER_TILE_W <= x2) {
                    if (y + RIVER_TILE_H <= y2) {
                        val bmp = Bitmap.createBitmap(img, x, y, RIVER_TILE_W, RIVER_TILE_H)
                        slices.add(TileSlice("${roi.name}河底${count+1}", bmp, "river_${roi.name}_$count"))
                        count++
                    }
                    x += step
                }
            }
        }
    }

    // ═══════ UI渲染 ═══════
    private fun addSliceRow(index: Int, s: TileSlice) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(4, 4, 4, 4)
        }

        // 序号
        row.addView(TextView(this).apply {
            text = "${index+1}"; textSize = 10f
            setTextColor(0xFF80B080.toInt()); setPadding(0, 0, 6, 0)
            layoutParams = LinearLayout.LayoutParams(dp(24), LinearLayout.LayoutParams.WRAP_CONTENT)
        })

        // 裁剪图
        val dstW = if (currentTab == "hand") dp(32) else dp(38)
        val dstH = if (currentTab == "hand") dp(47) else (s.bmp.height * dstW / s.bmp.width)
        row.addView(ImageView(this).apply {
            setImageBitmap(Bitmap.createScaledBitmap(s.bmp, dstW, dstH, true))
            setBackgroundColor(0xFF1A2A1A.toInt())
            layoutParams = LinearLayout.LayoutParams(dstW, dstH).apply { marginEnd = 8 }
        })

        // ID标签
        row.addView(TextView(this).apply {
            text = s.id; textSize = 10f; setTextColor(0xFFA0F0A0.toInt())
            layoutParams = LinearLayout.LayoutParams(dp(60), LinearLayout.LayoutParams.WRAP_CONTENT)
        })

        tileContainer.addView(row)
    }

    private fun saveAll() {
        if (slices.isEmpty()) { Toast.makeText(this, "请先加载截图", Toast.LENGTH_SHORT).show(); return }
        val outDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "mahjong_templates")
        if (!outDir.exists()) outDir.mkdirs()

        var saved = 0
        for (s in slices) {
            val fileName = "${s.label}.png"
            try {
                FileOutputStream(File(outDir, fileName)).use { s.bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                saved++
            } catch (_: Exception) {}
        }

        AlertDialog.Builder(this)
            .setTitle("保存完成")
            .setMessage("$saved/${slices.size} 张 →\n${outDir.absolutePath}")
            .setPositiveButton("确定", null).show()
        statusLabel.text = "已保存 $saved 张"
    }

    private fun findLatestScreenshot(): String? {
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).absolutePath)
        if (!dir.exists()) return null
        return dir.listFiles { f -> f.isFile && (f.name.endsWith(".jpg") || f.name.endsWith(".png")) }
            ?.maxByOrNull { it.lastModified() }?.absolutePath
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
    private fun minOf(a: Int, b: Int) = if (a < b) a else b

    override fun onDestroy() {
        super.onDestroy()
        currentBitmap?.recycle()
    }
}
