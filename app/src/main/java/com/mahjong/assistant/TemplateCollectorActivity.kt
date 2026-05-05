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
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.io.FileOutputStream

/**
 * 模板采集器 v2：3 Tab — 手牌/副露/河底
 * 加载截图 → 固定坐标网格自动分割 → 列表展示 → 用户标注 → 保存
 */
class TemplateCollectorActivity : AppCompatActivity() {

    companion object {
        private const val REQUEST_PICK_IMAGE = 1001
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
    private var selectedUri: Uri? = null

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

            // 路径行: [选择] [输入框] [加载]
            val pathRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            Button(this).apply {
                text = "选择"; textSize = 12f
                setBackgroundColor(0xFF2D6A2D.toInt()); setTextColor(0xFF80B080.toInt())
                setPadding(dp(8), dp(6), dp(8), dp(6))
                setOnClickListener {
                    val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = "image/*"
                        putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("image/jpeg", "image/png"))
                    }
                    startActivityForResult(intent, REQUEST_PICK_IMAGE)
                }
            }.also { pathRow.addView(it) }
            pathInput = EditText(this).apply {
                hint = "/sdcard/Download/screenshot.jpg"
                setTextColor(0xFFA0F0A0.toInt()); setHintTextColor(0xFF446644.toInt())
                setBackgroundColor(0xFF2D3A2D.toInt())
                setPadding(8, 8, 8, 8); textSize = 12f
                val recent = findLatestScreenshot()
                if (recent != null) setText(recent)
            }
            pathRow.addView(pathInput, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            Button(this).apply {
                text = "加载"; textSize = 12f
                setBackgroundColor(0xFF2D6A2D.toInt()); setTextColor(0xFF5CFF5C.toInt())
                setPadding(dp(8), dp(6), dp(8), dp(6))
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
        val text = pathInput.text.toString().trim()
        if (text.isEmpty()) { Toast.makeText(this, "输入截图路径", Toast.LENGTH_SHORT).show(); return }

        try {
            currentBitmap?.recycle()
            val opts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }

            // 支持 file://, content:// 和绝对路径
            currentBitmap = when {
                text.startsWith("content://") -> {
                    contentResolver.openInputStream(Uri.parse(text))?.use {
                        BitmapFactory.decodeStream(it, null, opts)
                    }
                }
                text.startsWith("file://") -> {
                    val f = File(Uri.parse(text).path ?: text.removePrefix("file://"))
                    if (!f.exists()) { statusLabel.text = "✗ 文件不存在"; return }
                    BitmapFactory.decodeFile(f.absolutePath, opts)
                }
                text.startsWith("/") -> {
                    val f = File(text)
                    if (!f.exists()) { statusLabel.text = "✗ 文件不存在"; return }
                    BitmapFactory.decodeFile(text, opts)
                }
                // 可能是 selectedUri content:// (onActivityResult 已设置)
                selectedUri != null -> {
                    contentResolver.openInputStream(selectedUri!!)?.use {
                        BitmapFactory.decodeStream(it, null, opts)
                    }
                }
                else -> {
                    statusLabel.text = "✗ 不支持的路径格式"; return
                }
            }

            if (currentBitmap == null) { statusLabel.text = "✗ 无法解码图片"; return }
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
        var lastTileX = -1  // 跟踪最后一张牌的x坐标
        for (i in 0..12) {
            val x = HAND_FACE_X + i * HAND_SLOT_GAP
            if (x + HAND_FACE_W > iw || HAND_FACE_Y + HAND_FACE_H > ih) continue
            val px = IntArray(HAND_FACE_W * HAND_FACE_H)
            img.getPixels(px, 0, HAND_FACE_W, x, HAND_FACE_Y, HAND_FACE_W, HAND_FACE_H)
            val mean = px.sumOf { (Color.red(it) + Color.green(it) + Color.blue(it)) / 3 }.toDouble() / px.size
            if (mean < 80) continue
            lastTileX = x
            val bmp = Bitmap.createBitmap(img, x, HAND_FACE_Y, HAND_FACE_W, HAND_FACE_H)
            slices.add(TileSlice("牌${i+1}", bmp, "hand_${i}"))
        }
        // 摸牌: 最后牌右+43px(牌间标准间距)
        val drawnX = if (lastTileX > 0) lastTileX + HAND_FACE_W + 43 else HAND_DRAWN_X
        if (drawnX + HAND_FACE_W <= iw) {
            val px = IntArray(HAND_FACE_W * HAND_FACE_H)
            img.getPixels(px, 0, HAND_FACE_W, drawnX, HAND_FACE_Y, HAND_FACE_W, HAND_FACE_H)
            val mean = px.sumOf { (Color.red(it) + Color.green(it) + Color.blue(it)) / 3 }.toDouble() / px.size
            if (mean >= 80) {
                val bmp = Bitmap.createBitmap(img, drawnX, HAND_FACE_Y, HAND_FACE_W, HAND_FACE_H)
                slices.add(TileSlice("摸牌", bmp, "hand_drawn"))
            }
        }
    }

    // ═══════ 副露分割 (边缘检测精准切割) ═══════
    private fun sliceMeld(img: Bitmap) {
        val iw = img.width; val ih = img.height
        val meldX = HAND_FACE_X + 13 * HAND_SLOT_GAP + HAND_FACE_W + 10
        if (meldX >= iw - 20) return

        val scanY = HAND_FACE_Y + HAND_FACE_H / 2
        val scanW = iw - meldX
        val pixels = IntArray(scanW)
        img.getPixels(pixels, 0, scanW, meldX, scanY, scanW, 1)
        val bright = IntArray(scanW) { i -> val c = pixels[i]; (Color.red(c)+Color.green(c)+Color.blue(c))/3 }

        // 找亮段: 连续>=8px 亮度>90
        val segs = mutableListOf<Pair<Int,Int>>()
        var s = -1
        for (i in bright.indices) {
            if (bright[i] > 90) { if (s < 0) s = i }
            else { if (s >= 0 && i - s >= 8) segs.add(Pair(s, i)); s = -1 }
        }
        if (s >= 0 && bright.size - s >= 8) segs.add(Pair(s, bright.size - 1))

        // 合并间隙<3px (副露牌间距小, 不像手牌111px)
        val merged = mutableListOf<Pair<Int,Int>>()
        for (seg in segs.sortedBy { it.first }) {
            if (merged.isEmpty() || seg.first - merged.last().second > 3)
                merged.add(seg)
            else
                merged[merged.lastIndex] = Pair(merged.last().first, seg.second)
        }

        // 竖直收缩
        for ((sx, ex) in merged) {
            val cx = meldX + sx; val cw = ex - sx
            if (cw < 8 || cw > 200) continue  // 牌面不可能超200px宽
            val roiY1 = Math.max(0, HAND_FACE_Y - 50)
            val roiH = minOf(HAND_FACE_H + 80, ih - roiY1)
            val rp = IntArray(cw * roiH)
            img.getPixels(rp, 0, cw, cx, roiY1, cw, roiH)
            var top = roiY1
            for (y in 0 until roiH) {
                val sum = (0 until cw).sumOf { val c = rp[y*cw+it]; (Color.red(c)+Color.green(c)+Color.blue(c))/3 }
                if (sum.toDouble()/cw > 80) { top = roiY1+y; break }
            }
            var bot = roiY1+roiH
            for (y in roiH-1 downTo 0) {
                val sum = (0 until cw).sumOf { val c = rp[y*cw+it]; (Color.red(c)+Color.green(c)+Color.blue(c))/3 }
                if (sum.toDouble()/cw > 80) { bot = roiY1+y+1; break }
            }
            val fh = Math.max(bot-top, 10)
            if (fh < 10) continue
            val bmp = Bitmap.createBitmap(img, cx, top, cw, fh)
            slices.add(TileSlice("@${cx},${top} ${cw}x$fh", bmp, "meld_${slices.size}"))
        }
    }

    // ═══════ 河底分割 (边缘检测, 4家) ═══════
    private fun sliceRiver(img: Bitmap) {
        // 河底坐标因人而异, 不做自动切
    }

    // ═══════ UI渲染 (全部tab: 自动识别+Spinner标注) ═══════
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
            setPadding(2, 4, 2, 4)
        }

        // 序号
        row.addView(TextView(this).apply {
            text = "${index+1}"; textSize = 9f
            setTextColor(0xFF80B080.toInt()); setPadding(0, 0, 4, 0)
            layoutParams = LinearLayout.LayoutParams(dp(18), LinearLayout.LayoutParams.WRAP_CONTENT)
        })

        // 裁剪图
        val dstH = dp(48)
        val dstW = Math.min(dp(52), Math.max(dp(22), s.bmp.width * dstH / s.bmp.height))
        row.addView(ImageView(this).apply {
            setImageBitmap(Bitmap.createScaledBitmap(s.bmp, dstW, dstH, true))
            setBackgroundColor(0xFF1A2A1A.toInt())
            layoutParams = LinearLayout.LayoutParams(dstW, dstH).apply { marginEnd = 6 }
        })

        // 自动识别 (所有tab)
        val autoResult = autoIdentify(s.bmp)
        val autoName = if (autoResult != null && autoResult.second >= 0.70) tileNames.getOrNull(autoResult.first) else null
        val autoLabel = if (autoName != null) autoName else "未识别"
        val autoScore = if (autoResult != null) "%.2f".format(autoResult.second) else ""

        // Spinner
        val spinner = Spinner(this).apply {
            val options = mutableListOf("未识别")
            options.addAll(tileNames)
            adapter = ArrayAdapter(this@TemplateCollectorActivity, android.R.layout.simple_spinner_dropdown_item, options)
            setBackgroundColor(0xFF2D3A2D.toInt())
            setPopupBackgroundDrawable(ColorDrawable(0xFF1A2E1A.toInt()))
            val selIdx = if (autoName != null) tileNames.indexOf(autoName) + 1 else 0
            if (selIdx >= 0) setSelection(selIdx)
            tag = s
            s.label = if (autoName != null) autoName else "未识别"
            setOnItemSelectedListener(object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) {
                    s.label = parent?.getItemAtPosition(pos)?.toString() ?: "未识别"
                }
                override fun onNothingSelected(parent: AdapterView<*>?) {}
            })
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        row.addView(spinner)

        // 识别结果标签
        val labelColor = when {
            autoName != null && autoResult!!.second >= 0.85 -> 0xFF5CFF5C.toInt()
            autoName != null -> 0xFFA0C040.toInt()
            else -> 0xFF606060.toInt()
        }
        row.addView(TextView(this).apply {
            text = if (autoName != null) "$autoLabel $autoScore" else "未识别"
            textSize = 9f; setTextColor(labelColor)
            layoutParams = LinearLayout.LayoutParams(dp(68), LinearLayout.LayoutParams.WRAP_CONTENT)
        })

        tileContainer.addView(row)
    }

    private fun autoIdentify(bmp: Bitmap): Pair<Int, Double>? {
        return TileMatcher.identifySingleTile(bmp)
    }

    private fun saveAll() {
        if (slices.isEmpty()) { Toast.makeText(this, "请先加载截图", Toast.LENGTH_SHORT).show(); return }
        val outDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "mahjong_templates")
        if (!outDir.exists()) outDir.mkdirs()

        var saved = 0; var skipped = 0
        for (s in slices) {
            val name = s.label
            // 手牌/副露模板按牌名命名
            val fileName = if ((currentTab == "meld" || currentTab == "hand") && name != "未识别" && name != "未知") {
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

    @Deprecated("Use registerForActivityResult")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_PICK_IMAGE && resultCode == RESULT_OK && data?.data != null) {
            selectedUri = data.data
            // 将URI字符串填入输入框，用selectedUri直接加载(不转换路径)
            pathInput.setText(selectedUri.toString())
            loadAndSlice()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        currentBitmap?.recycle()
    }
}
