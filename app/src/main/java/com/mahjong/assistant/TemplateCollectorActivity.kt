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
import com.mahjong.assistant.util.FLog
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.io.FileOutputStream

/**
 * 模板采集器 v3：3 Tab — 手牌/副露/河底
 * 副露采用固定步进切分(50px步进×90px宽)，适配叠放无间隙布局
 */
class TemplateCollectorActivity : AppCompatActivity() {

    companion object {
        private const val REQUEST_PICK_IMAGE = 1001
        const val HAND_FACE_X = 541; const val HAND_FACE_Y = 1106
        const val HAND_FACE_W = 98; const val HAND_FACE_H = 143
        const val HAND_SLOT_GAP = 111; const val HAND_DRAWN_X = 2019
        const val MELD_X1 = 1150; const val MELD_Y1 = 1000; const val MELD_Y2 = 1269
        const val MELD_TILE_W = 104; const val MELD_TILE_H = 112; const val MELD_TILE_GAP = 6
        const val RIVER_SELF_X1 = 800; const val RIVER_SELF_X2 = 1100
        const val RIVER_SELF_Y1 = 432; const val RIVER_SELF_Y2 = 560
        const val RIVER_SHIMO_X1 = 1550; const val RIVER_SHIMO_X2 = 1780
        const val RIVER_SHIMO_Y1 = 180; const val RIVER_SHIMO_Y2 = 500
        const val RIVER_TOI_X1 = 1060; const val RIVER_TOI_X2 = 1740
        const val RIVER_TOI_Y1 = 120; const val RIVER_TOI_Y2 = 280
        const val RIVER_KAMI_X1 = 1020; const val RIVER_KAMI_X2 = 1250
        const val RIVER_KAMI_Y1 = 180; const val RIVER_KAMI_Y2 = 500
        const val RIVER_TILE_W = 46; const val RIVER_TILE_H = 64; const val RIVER_TILE_GAP = 3
    }

    private lateinit var pathInput: EditText
    private lateinit var statusLabel: TextView
    private lateinit var tileContainer: LinearLayout
    private lateinit var tabHand: Button; private lateinit var tabMeld: Button; private lateinit var tabRiver: Button
    private var currentBitmap: Bitmap? = null; private var currentTab = "hand"; private var selectedUri: Uri? = null

    data class TileSlice(val id: String, val bmp: Bitmap, var label: String)
    private val slices = mutableListOf<TileSlice>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        FLog.init(filesDir)
        FLog.i("CollAct", "onCreate")
        setContentView(createLayout())
    }

    private fun createLayout(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(0xFF1A2E1A.toInt()); setPadding(16, 40, 16, 16)
        }.also { root ->
            TextView(this).apply {
                text = "📷 模板采集器"; textSize = 18f; setTextColor(0xFF5CFF5C.toInt()); gravity = Gravity.CENTER; setPadding(0, 0, 0, 8)
            }.also { root.addView(it) }
            val pathRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            Button(this).apply {
                text = "选择"; textSize = 12f; setBackgroundColor(0xFF2D6A2D.toInt()); setTextColor(0xFF80B080.toInt()); setPadding(dp(8), dp(6), dp(8), dp(6))
                setOnClickListener {
                    startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE); type = "image/*"; putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("image/jpeg", "image/png"))
                    }, REQUEST_PICK_IMAGE)
                }
            }.also { pathRow.addView(it) }
            pathInput = EditText(this).apply {
                hint = "/sdcard/Download/screenshot.jpg"; setTextColor(0xFFA0F0A0.toInt()); setHintTextColor(0xFF446644.toInt())
                setBackgroundColor(0xFF2D3A2D.toInt()); setPadding(8, 8, 8, 8); textSize = 12f
                findLatestScreenshot()?.let { setText(it) }
            }
            pathRow.addView(pathInput, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            Button(this).apply {
                text = "加载"; textSize = 12f; setBackgroundColor(0xFF2D6A2D.toInt()); setTextColor(0xFF5CFF5C.toInt())
                setPadding(dp(8), dp(6), dp(8), dp(6)); setOnClickListener { loadAndSlice() }
            }.also { pathRow.addView(it) }
            root.addView(pathRow)
            val tabRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, 8, 0, 4) }
            tabHand = makeTab("手牌", "hand", tabRow); tabMeld = makeTab("副露", "meld", tabRow); tabRiver = makeTab("河底", "river", tabRow)
            root.addView(tabRow)
            statusLabel = TextView(this).apply {
                text = "输入路径后点「加载」"; textSize = 11f; setTextColor(0xFF80B080.toInt()); setPadding(0, 4, 0, 4); gravity = Gravity.CENTER
            }.also { root.addView(it) }
            val scroll = ScrollView(this).apply { layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f) }
            tileContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }; scroll.addView(tileContainer); root.addView(scroll)
            Button(this).apply {
                text = "💾 保存全部"; textSize = 14f; setBackgroundColor(0xFF2D6A2D.toInt()); setTextColor(0xFF5CFF5C.toInt())
                setPadding(0, 12, 0, 12); setOnClickListener { saveAll() }
            }.also { root.addView(it) }
        }
    }

    private fun makeTab(label: String, tag: String, parent: LinearLayout): Button {
        return Button(this).apply {
            text = label; textSize = 12f; minWidth = 0; setBackgroundColor(0xFF2D3A2D.toInt()); setTextColor(0xFF80B080.toInt())
            setPadding(dp(12), dp(4), dp(12), dp(4)); setOnClickListener { switchTab(tag) }
            parent.addView(this, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = 4 })
        }
    }

    private fun switchTab(tag: String) {
        FLog.i("CollAct", "switchTab → $tag"); currentTab = tag
        for ((btn, t) in listOf(tabHand to "hand", tabMeld to "meld", tabRiver to "river")) {
            if (t == tag) { btn.setBackgroundColor(0xFF2D6A2D.toInt()); btn.setTextColor(0xFF5CFF5C.toInt()) }
            else { btn.setBackgroundColor(0xFF2D3A2D.toInt()); btn.setTextColor(0xFF80B080.toInt()) }
        }
        doSlice()
    }

    private fun loadAndSlice() {
        val text = pathInput.text.toString().trim()
        FLog.i("CollAct", "loadAndSlice path=$text")
        if (text.isEmpty()) { Toast.makeText(this, "输入截图路径", Toast.LENGTH_SHORT).show(); return }
        try {
            currentBitmap?.recycle()
            val opts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
            currentBitmap = when {
                text.startsWith("content://") -> { FLog.i("CollAct", "加载content://"); contentResolver.openInputStream(Uri.parse(text))?.use { BitmapFactory.decodeStream(it, null, opts) } }
                text.startsWith("file://") -> { val f = File(Uri.parse(text).path ?: text.removePrefix("file://")); if (!f.exists()) { statusLabel.text = "✗ 文件不存在"; return }; BitmapFactory.decodeFile(f.absolutePath, opts) }
                text.startsWith("/") -> { val f = File(text); if (!f.exists()) { statusLabel.text = "✗ 文件不存在"; return }; BitmapFactory.decodeFile(text, opts) }
                selectedUri != null -> { contentResolver.openInputStream(selectedUri!!)?.use { BitmapFactory.decodeStream(it, null, opts) } }
                else -> { statusLabel.text = "✗ 不支持的路径格式"; return }
            }
            if (currentBitmap == null) { statusLabel.text = "✗ 无法解码图片"; return }
            val w = currentBitmap!!.width; val h = currentBitmap!!.height
            FLog.i("CollAct", "截图 ${w}×${h} → 切分tab=$currentTab"); statusLabel.text = "截图 ${w}×${h} — $currentTab"
            doSlice()
        } catch (e: Exception) { FLog.e("CollAct", "loadAndSlice崩溃", e); statusLabel.text = "✗ ${e.message}" }
    }

    private fun doSlice() {
        val img = currentBitmap ?: return; slices.clear(); tileContainer.removeAllViews()
        FLog.i("CollAct", "doSlice tab=$currentTab img=${img.width}x${img.height}")
        when (currentTab) { "hand" -> sliceHand(img); "meld" -> sliceMeld(img); "river" -> sliceRiver(img) }
        FLog.i("CollAct", "doSlice done: ${slices.size} 张, 开始渲染")
        for ((i, s) in slices.withIndex()) addSliceRow(i, s)
        statusLabel.text = "截图 ${img.width}×${img.height} — $currentTab: ${slices.size} 张"
    }

    private var handEndX = HAND_FACE_X; private var drawnEndX = 0

    private fun sliceHand(img: Bitmap) {
        val iw = img.width; val ih = img.height; var lastMatchedX = -1; var lastVisibleX = -1; var matchedCount = 0; var emptyCount = 0
        for (i in 0..12) {
            val x = HAND_FACE_X + i * HAND_SLOT_GAP
            if (x + HAND_FACE_W > iw || HAND_FACE_Y + HAND_FACE_H > ih) continue
            val px = IntArray(HAND_FACE_W * HAND_FACE_H); img.getPixels(px, 0, HAND_FACE_W, x, HAND_FACE_Y, HAND_FACE_W, HAND_FACE_H)
            val mean = px.sumOf { (Color.red(it) + Color.green(it) + Color.blue(it)) / 3 }.toDouble() / px.size
            if (mean < 80) { emptyCount++; continue }
            lastVisibleX = x  // 最后有牌的slot（不论是否匹配成功）
            val bmp = Bitmap.createBitmap(img, x, HAND_FACE_Y, HAND_FACE_W, HAND_FACE_H)
            val result = TileMatcher.identifySingleTile(bmp)
            if (result != null && result.second >= 0.85) { lastMatchedX = x; matchedCount++ }
            slices.add(TileSlice("牌${i+1}", bmp, "hand_${i}"))
        }
        handEndX = if (lastVisibleX >= 0) lastVisibleX + HAND_FACE_W else HAND_FACE_X
        val drawnX = handEndX + 43
        FLog.i("CollAct", "手牌: match=$matchedCount empty=$emptyCount handEndX=$handEndX drawnX=$drawnX")
        drawnEndX = 0
        if (drawnX + HAND_FACE_W <= iw) {
            val px = IntArray(HAND_FACE_W * HAND_FACE_H); img.getPixels(px, 0, HAND_FACE_W, drawnX, HAND_FACE_Y, HAND_FACE_W, HAND_FACE_H)
            val mean = px.sumOf { (Color.red(it) + Color.green(it) + Color.blue(it)) / 3 }.toDouble() / px.size
            FLog.i("CollAct", "摸牌: x=$drawnX mean=${mean.toInt()}")
            if (mean >= 80) { slices.add(TileSlice("摸牌", Bitmap.createBitmap(img, drawnX, HAND_FACE_Y, HAND_FACE_W, HAND_FACE_H), "hand_drawn")); drawnEndX = drawnX + HAND_FACE_W }
        }
    }

    // ═══════ 副露: 固定步进切分(叠放布局无间隙) ═══════
    private fun sliceMeld(img: Bitmap) {
        try {
            val iw = img.width; val ih = img.height
            val meldX = (if (drawnEndX > 0) drawnEndX else handEndX + HAND_FACE_W) + 10
            FLog.i("CollAct", "sliceMeld: meldX=$meldX drawnEndX=$drawnEndX")
            if (meldX >= iw - 20) return
            val roiY = Math.max(0, HAND_FACE_Y - 50); val roiH = minOf(HAND_FACE_H + 80, ih - roiY)
            val meldW = iw - meldX
            if (meldW < 30 || roiH < 20) return

            val pixels = IntArray(meldW * roiH); img.getPixels(pixels, 0, meldW, meldX, roiY, meldW, roiH)

            // 找整个副露亮块的左右边界
            var blockLeft = -1; var blockRight = meldW
            for (i in 0 until meldW) { var s = 0; for (y in roiH/4 until 3*roiH/4) s += (Color.red(pixels[y*meldW+i]) + Color.green(pixels[y*meldW+i]) + Color.blue(pixels[y*meldW+i])) / 3; if (s.toDouble()/(roiH/2) > 90) { blockLeft = i; break } }
            for (i in meldW-1 downTo 0) { var s = 0; for (y in roiH/4 until 3*roiH/4) s += (Color.red(pixels[y*meldW+i]) + Color.green(pixels[y*meldW+i]) + Color.blue(pixels[y*meldW+i])) / 3; if (s.toDouble()/(roiH/2) > 90) { blockRight = i+1; break } }
            if (blockLeft < 0) { FLog.w("CollAct", "sliceMeld: 未找到亮块"); return }
            val blockW = blockRight - blockLeft
            FLog.i("CollAct", "副露块: left=$blockLeft right=$blockRight w=$blockW")

            val sliceStep = 50; val sliceW = 90; val nSlices = maxOf(1, blockW / sliceStep)
            for (i in 0 until nSlices) {
                val cx = meldX + blockLeft + i * sliceStep + sliceStep / 2
                val sx = Math.max(meldX, cx - sliceW / 2); val sw = minOf(sliceW, iw - sx)
                if (sw < 30) continue
                var top = roiY
                for (y in 0 until roiH) { var s = 0; for (x in 0 until sw-1) s += (Color.red(pixels[y*meldW+(sx-meldX)+x]) + Color.green(pixels[y*meldW+(sx-meldX)+x]) + Color.blue(pixels[y*meldW+(sx-meldX)+x])) / 3; if (s.toDouble()/sw > 60) { top = roiY+y; break } }
                var bot = roiY+roiH
                for (y in roiH-1 downTo 0) { var s = 0; for (x in 0 until sw-1) s += (Color.red(pixels[y*meldW+(sx-meldX)+x]) + Color.green(pixels[y*meldW+(sx-meldX)+x]) + Color.blue(pixels[y*meldW+(sx-meldX)+x])) / 3; if (s.toDouble()/sw > 60) { bot = roiY+y+1; break } }
                val fh = Math.max(bot-top, 8); if (fh < 8) continue
                slices.add(TileSlice("副${i+1}", Bitmap.createBitmap(img, sx, top, sw, fh), "meld_${slices.size}"))
                FLog.i("CollAct", "  切出[$i]: @$sx,$top ${sw}x$fh")
            }
            FLog.i("CollAct", "sliceMeld done: ${slices.size} 张副露")
        } catch (e: Exception) { FLog.e("CollAct", "sliceMeld崩溃", e) }
    }

    private fun sliceRiver(img: Bitmap) {}

    private val tileNames = arrayOf("一万","二万","三万","四万","五万","六万","七万","八万","九万","一筒","二筒","三筒","四筒","五筒","六筒","七筒","八筒","九筒","一索","二索","三索","四索","五索","六索","七索","八索","九索","東","南","西","北","白","発","中")

    private fun addSliceRow(index: Int, s: TileSlice) {
        try {
            FLog.i("CollAct", "addSliceRow[$index] ${s.id} size=${s.bmp.width}x${s.bmp.height}")
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(2, 4, 2, 4) }
            row.addView(TextView(this).apply { text = "${index+1}"; textSize = 9f; setTextColor(0xFF80B080.toInt()); setPadding(0, 0, 4, 0); layoutParams = LinearLayout.LayoutParams(dp(18), LinearLayout.LayoutParams.WRAP_CONTENT) })
            val dstH = dp(48); val dstW = Math.min(dp(52), Math.max(dp(22), s.bmp.width * dstH / s.bmp.height))
            row.addView(ImageView(this).apply { setImageBitmap(Bitmap.createScaledBitmap(s.bmp, dstW, dstH, true)); setBackgroundColor(0xFF1A2A1A.toInt()); layoutParams = LinearLayout.LayoutParams(dstW, dstH).apply { marginEnd = 6 } })
            val autoResult = autoIdentify(s.bmp)
            val autoName = if (autoResult != null && autoResult.second >= (if (currentTab == "meld") 0.20 else 0.85)) tileNames.getOrNull(autoResult.first) else null
            val autoLabel = if (autoName != null) autoName else "未识别"; val autoScore = if (autoResult != null) String.format("%.2f", autoResult.second) else ""
            FLog.i("CollAct", "  auto: $autoLabel score=$autoScore")
            val spinner = Spinner(this).apply {
                val options = mutableListOf("未识别"); options.addAll(tileNames)
                adapter = ArrayAdapter(this@TemplateCollectorActivity, android.R.layout.simple_spinner_dropdown_item, options)
                setBackgroundColor(0xFF2D3A2D.toInt()); setPopupBackgroundDrawable(ColorDrawable(0xFF1A2E1A.toInt()))
                val selIdx = if (autoName != null) tileNames.indexOf(autoName)+1 else 0; if (selIdx >= 0) setSelection(selIdx)
                tag = s; s.label = if (autoName != null) autoName else "未识别"
                setOnItemSelectedListener(object : AdapterView.OnItemSelectedListener {
                    override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) { s.label = parent?.getItemAtPosition(pos)?.toString() ?: "未识别" }
                    override fun onNothingSelected(parent: AdapterView<*>?) {}
                }); layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }; row.addView(spinner)
            val labelColor = when { autoName != null && autoResult!!.second >= 0.85 -> 0xFF5CFF5C.toInt(); autoName != null -> 0xFFA0C040.toInt(); else -> 0xFF606060.toInt() }
            val showText = when { autoName != null -> "$autoLabel $autoScore"; autoResult != null && autoResult.first in 0 until tileNames.size -> "最佳:" + tileNames[autoResult.first] + " " + String.format("%.2f", autoResult.second); else -> "未识别" }
            row.addView(TextView(this).apply { text = showText; textSize = 9f; setTextColor(labelColor); layoutParams = LinearLayout.LayoutParams(dp(76), LinearLayout.LayoutParams.WRAP_CONTENT) })
            tileContainer.addView(row)
        } catch (e: Exception) { FLog.e("CollAct", "addSliceRow[$index] 崩溃", e) }
    }

    private fun autoIdentify(bmp: Bitmap): Pair<Int, Double>? = TileMatcher.identifySingleTile(bmp)

    private fun saveAll() {
        FLog.i("CollAct", "saveAll tab=$currentTab slices=${slices.size}")
        if (slices.isEmpty()) { Toast.makeText(this, "请先加载截图", Toast.LENGTH_SHORT).show(); return }
        val outDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "mahjong_templates")
        if (!outDir.exists()) outDir.mkdirs()
        var saved = 0; var skipped = 0
        for (s in slices) {
            val name = s.label
            if (name == "未识别" || name == "未知") { skipped++; continue }
            val fileName = if (currentTab == "meld" || currentTab == "hand") {
                val direction = if (s.bmp.width > s.bmp.height) "横" else "竖"
                var candidate = "${name}_${direction}.png"; var counter = 2
                while (File(outDir, candidate).exists()) { candidate = "${name}_${direction}${counter}.png"; counter++ }
                candidate
            } else "${s.label}.png"
            try { FileOutputStream(File(outDir, fileName)).use { s.bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }; saved++ } catch (_: Exception) { skipped++ }
        }
        val msg = if (currentTab == "meld") "已保存 $saved/${slices.size} 张\n$skipped 张未标注(跳过)\n→ ${outDir.absolutePath}\n\n记得把.png文件移到 assets/meld_tiles/ 目录并重新编译" else "$saved/${slices.size} 张 →\n${outDir.absolutePath}"
        AlertDialog.Builder(this).setTitle("保存完成").setMessage(msg).setPositiveButton("确定", null).show()
        statusLabel.text = "已保存 $saved 张"
    }

    private fun findLatestScreenshot(): String? {
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).absolutePath)
        if (!dir.exists()) return null
        return dir.listFiles { f -> f.isFile && (f.name.endsWith(".jpg") || f.name.endsWith(".png")) }?.maxByOrNull { it.lastModified() }?.absolutePath
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
    private fun minOf(a: Int, b: Int) = if (a < b) a else b

    @Deprecated("Use registerForActivityResult")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_PICK_IMAGE && resultCode == RESULT_OK && data?.data != null) {
            selectedUri = data.data; pathInput.setText(selectedUri.toString()); loadAndSlice()
        }
    }

    override fun onDestroy() { super.onDestroy(); FLog.i("CollAct", "onDestroy"); currentBitmap?.recycle() }
}
