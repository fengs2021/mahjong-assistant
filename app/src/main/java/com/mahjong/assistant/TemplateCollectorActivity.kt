package com.mahjong.assistant

import android.app.AlertDialog
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.os.Environment
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.mahjong.assistant.capture.TileMatcher
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
    data class TileSlice(val id: String, val bmp: Bitmap, var label: String)
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

    // ═══════ 副露分割 (边缘检测精准切割) ═══════
    private fun sliceMeld(img: Bitmap) {
        val iw = img.width; val ih = img.height
        // ROI: 手牌右侧到屏幕右边缘, Y同手牌区域±buffer
        val meldX = HAND_FACE_X + 13 * HAND_SLOT_GAP + HAND_FACE_W + 10  // 手牌最后slot右侧
        if (meldX >= iw - 20) return

        // 取ROI中心行亮度profile找牌边界
        val scanY = HAND_FACE_Y + HAND_FACE_H / 2
        val pixels = IntArray(iw - meldX)
        img.getPixels(pixels, 0, iw - meldX, meldX, scanY, iw - meldX, 1)

        // 灰度亮度
        val bright = IntArray(pixels.size) { i ->
            val c = pixels[i]
            ((Color.red(c) + Color.green(c) + Color.blue(c)) / 3)
        }

        // 找亮→暗过渡(牌面边缘): 连续>15px亮度>100的区域=牌面
        val tileSegments = mutableListOf<Pair<Int, Int>>()  // (startX, endX) relative to meldX
        var segStart = -1
        for (i in bright.indices) {
            if (bright[i] > 100) {
                if (segStart < 0) segStart = i
            } else {
                if (segStart >= 0 && i - segStart >= 15) {
                    tileSegments.add(Pair(segStart, i))
                }
                segStart = -1
            }
        }
        if (segStart >= 0 && bright.size - segStart >= 15) {
            tileSegments.add(Pair(segStart, bright.size - 1))
        }

        // 合并相邻(间隙<8px)
        val merged = mutableListOf<Pair<Int, Int>>()
        for (seg in tileSegments.sortedBy { it.first }) {
            if (merged.isEmpty() || seg.first - merged.last().second > 8) {
                merged.add(seg)
            } else {
                merged[merged.lastIndex] = Pair(merged.last().first, seg.second)
            }
        }

        // 垂直方向收缩: 找每段实际top/bottom
        for ((sx, ex) in merged) {
            val cx = meldX + sx; val cw = ex - sx
            // 粗切ROI
            val roiY1 = Math.max(0, HAND_FACE_Y - 30)
            val roiH = minOf(HAND_FACE_H + 60, ih - roiY1)
            val roiPixels = IntArray(cw * roiH)
            img.getPixels(roiPixels, 0, cw, cx, roiY1, cw, roiH)

            // 找top: 从上往下第一个亮行
            var top = roiY1
            for (y in 0 until roiH) {
                val rowSum = (0 until cw).sumOf {
                    val c = roiPixels[y * cw + it]
                    (Color.red(c) + Color.green(c) + Color.blue(c)) / 3
                }
                if (rowSum.toDouble() / cw > 80) { top = roiY1 + y; break }
            }
            // 找bottom: 从下往上第一个亮行
            var bot = roiY1 + roiH
            for (y in roiH - 1 downTo 0) {
                val rowSum = (0 until cw).sumOf {
                    val c = roiPixels[y * cw + it]
                    (Color.red(c) + Color.green(c) + Color.blue(c)) / 3
                }
                if (rowSum.toDouble() / cw > 80) { bot = roiY1 + y + 1; break }
            }
            val fh = Math.max(bot - top, 20)
            if (fh < 20 || cw < 15) continue

            val bmp = Bitmap.createBitmap(img, cx, top, cw, fh)
            val label = "meld_${slices.size}"
            slices.add(TileSlice("@${cx},${top} ${cw}x$fh", bmp, label))
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

    // ═══════ UI渲染 (副露tab: 自动识别+Spinner标注) ═══════
    private val tileNames = arrayOf(
        "一万","二万","三万","四万","五万","六万","七万","八万","九万",
        "一筒","二筒","三筒","四筒","五筒","六筒","七筒","八筒","九筒",
        "一索","二索","三索","四索","五索","六索","七索","八索","九索",
        "東","南","西","北","白","発","中"
    )

    private fun addSliceRow(index: Int, s: TileSlice) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(4, 6, 4, 6)
        }

        // 序号
        row.addView(TextView(this).apply {
            text = "${index+1}"; textSize = 10f
            setTextColor(0xFF80B080.toInt()); setPadding(0, 0, 6, 0)
            layoutParams = LinearLayout.LayoutParams(dp(22), LinearLayout.LayoutParams.WRAP_CONTENT)
        })

        // 裁剪图
        val dstH = dp(52)
        val dstW = Math.min(dp(56), Math.max(dp(24), s.bmp.width * dstH / s.bmp.height))
        row.addView(ImageView(this).apply {
            setImageBitmap(Bitmap.createScaledBitmap(s.bmp, dstW, dstH, true))
            setBackgroundColor(0xFF1A2A1A.toInt())
            layoutParams = LinearLayout.LayoutParams(dstW, dstH).apply { marginEnd = 8 }
        })

        // 自动识别 (仅副露tab)
        val autoResult = if (currentTab == "meld") autoIdentify(s.bmp) else null

        if (currentTab == "meld") {
            // Spinner下拉选牌
            val spinner = Spinner(this).apply {
                val options = mutableListOf("未知")
                options.addAll(tileNames)
                val adapter = ArrayAdapter(this@TemplateCollectorActivity,
                    android.R.layout.simple_spinner_dropdown_item, options)
                this.adapter = adapter
                setBackgroundColor(0xFF2D3A2D.toInt())
                setPopupBackgroundDrawable(android.graphics.drawable.ColorDrawable(0xFF1A2E1A.toInt()))
                // 设置默认选中自动识别结果(降级"未知")
                val autoName = if (autoResult != null) tileNames.getOrNull(autoResult.first) else null
                val selIdx = if (autoName != null) tileNames.indexOf(autoName) + 1 else 0
                if (selIdx >= 0) setSelection(selIdx)
                // 存储选中值
                tag = s
                setOnItemSelectedListener(object : AdapterView.OnItemSelectedListener {
                    override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) {
                        val item = parent?.getItemAtPosition(pos)?.toString() ?: "未知"
                        s.label = item
                    }
                    override fun onNothingSelected(parent: AdapterView<*>?) {}
                })
                s.label = options[selIdx]
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            row.addView(spinner)

            // 自动识别结果提示
            val autoText = if (autoResult != null) " ${tileNames.getOrNull(autoResult.first) ?: "?"} ${"%.2f".format(autoResult.second)}" else ""
            row.addView(TextView(this).apply {
                text = autoText; textSize = 9f
                setTextColor(if (autoResult != null && autoResult.second >= 0.70) 0xFF5CFF5C.toInt() else 0xFF808080.toInt())
                layoutParams = LinearLayout.LayoutParams(dp(70), LinearLayout.LayoutParams.WRAP_CONTENT)
            })
        } else {
            // 手牌/河底: 仅显示ID标签
            row.addView(TextView(this).apply {
                text = s.id; textSize = 10f; setTextColor(0xFFA0F0A0.toInt())
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
        }

        tileContainer.addView(row)
    }

    private fun autoIdentify(bmp: Bitmap): Pair<Int, Double>? {
        val result = TileMatcher.identifySingleTile(bmp) ?: return null
        return if (result.second >= 0.40) result else null  // 低分不显示
    }

    private fun saveAll() {
        if (slices.isEmpty()) { Toast.makeText(this, "请先加载截图", Toast.LENGTH_SHORT).show(); return }
        val outDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "mahjong_templates")
        if (!outDir.exists()) outDir.mkdirs()

        var saved = 0; var skipped = 0
        for (s in slices) {
            val name = s.label
            // 副露模板按牌名_方向命名
            val fileName = if (currentTab == "meld" && name != "未知") {
                val direction = if (s.bmp.width > s.bmp.height) "横" else "竖"
                // 找第一个不重复的编号
                var n = ""
                var candidate = "${name}_${direction}.png"
                var counter = 2
                while (File(outDir, candidate).exists()) {
                    n = counter.toString()
                    candidate = "${name}_${direction}${n}.png"
                    counter++
                }
                candidate
            } else {
                "${s.label}.png"
            }
            try {
                FileOutputStream(File(outDir, fileName)).use { s.bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                saved++
            } catch (_: Exception) {
                skipped++
            }
        }

        val msg = if (currentTab == "meld")
            "已保存 " + saved + "/" + slices.size + " 张\n" + skipped + " 张未标注(跳过)\n→ " + outDir.absolutePath +
            "\n\n记得把.png文件移到 assets/meld_tiles/ 目录并重新编译"
        else
            saved.toString() + "/" + slices.size + " 张 →\n" + outDir.absolutePath

        AlertDialog.Builder(this)
            .setTitle("保存完成")
            .setMessage(msg)
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
