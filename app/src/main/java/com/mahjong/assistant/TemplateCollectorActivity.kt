package com.mahjong.assistant

import android.app.AlertDialog
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.PointF
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
import java.io.File
import java.io.FileOutputStream

class TemplateCollectorActivity : AppCompatActivity() {

    companion object {
        private const val REQUEST_PICK_IMAGE = 1001
        const val HAND_FACE_X = 541; const val HAND_FACE_Y = 1106
        const val HAND_FACE_W = 98; const val HAND_FACE_H = 143
        const val HAND_SLOT_GAP = 111
    }

    private lateinit var pathInput: EditText
    private lateinit var statusLabel: TextView
    private lateinit var contentArea: FrameLayout
    private lateinit var scrollContent: LinearLayout
    private lateinit var tabHand: Button; private lateinit var tabMeld: Button; private lateinit var tabRiver: Button
    private var currentBitmap: Bitmap? = null; private var currentTab = "hand"; private var selectedUri: Uri? = null
    private lateinit var meldMarkerView: MeldMarkerView
    private lateinit var btnAddAnn: Button; private lateinit var btnDoneAnn: Button; private lateinit var btnDelAnn: Button
    private lateinit var annContainer: LinearLayout
    private var isMeldAnnotationMode = false

    data class TileSlice(val id: String, val bmp: Bitmap, var label: String)
    private val slices = mutableListOf<TileSlice>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        FLog.init(filesDir); FLog.i("CollAct", "onCreate")
        setContentView(createLayout())
    }

    private fun createLayout(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(0xFF1A2E1A.toInt()); setPadding(16, 40, 16, 16)
        }.also { root ->
            TextView(this).apply {
                text = "📷 模板采集器"; textSize = 18f; setTextColor(0xFF5CFF5C.toInt()); gravity = Gravity.CENTER; setPadding(0, 0, 0, 8)
            }.also { root.addView(it) }

            // 路径行
            val pathRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            Button(this).apply {
                text = "选择"; textSize = 12f; setBackgroundColor(0xFF2D6A2D.toInt()); setTextColor(0xFF80B080.toInt()); setPadding(dp(8), dp(6), dp(8), dp(6))
                setOnClickListener { startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type = "image/*"; putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("image/jpeg", "image/png")) }, REQUEST_PICK_IMAGE) }
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

            // Tab行
            val tabRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, 8, 0, 4) }
            tabHand = makeTab("手牌", "hand", tabRow); tabMeld = makeTab("副露", "meld", tabRow); tabRiver = makeTab("河底", "river", tabRow)
            root.addView(tabRow)
            statusLabel = TextView(this).apply {
                text = "输入路径后点「加载」"; textSize = 11f; setTextColor(0xFF80B080.toInt()); setPadding(0, 4, 0, 4); gravity = Gravity.CENTER
            }.also { root.addView(it) }

            // 内容区(FrameLayout容纳MeldMarkerView或ScrollView)
            contentArea = FrameLayout(this).apply {
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
                setBackgroundColor(0xFF1A2E1A.toInt())
            }
            root.addView(contentArea)

            // 副露标注按钮行(默认隐藏)
            val annBtnRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, 4, 0, 4); visibility = View.GONE }
            btnAddAnn = Button(this).apply {
                text = "＋标注"; textSize = 11f; setBackgroundColor(0xFF2D6A2D.toInt()); setTextColor(0xFF5CFF5C.toInt()); setPadding(dp(6), dp(4), dp(6), dp(4))
                setOnClickListener { startMeldAnnotation() }
            }; annBtnRow.addView(btnAddAnn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = 4 })
            btnDoneAnn = Button(this).apply {
                text = "完成"; textSize = 11f; setBackgroundColor(0xFF2D3A2D.toInt()); setTextColor(0xFF80B080.toInt()); setPadding(dp(6), dp(4), dp(6), dp(4))
                setOnClickListener { finishMeldAnnotation() }
            }; annBtnRow.addView(btnDoneAnn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = 4 })
            btnDelAnn = Button(this).apply {
                text = "撤销"; textSize = 11f; setBackgroundColor(0xFF3A2D2D.toInt()); setTextColor(0xFFFF8080.toInt()); setPadding(dp(6), dp(4), dp(6), dp(4))
                setOnClickListener { meldMarkerView.cancelAnnotation(); setAnnotationButtons(false) }
            }; annBtnRow.addView(btnDelAnn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            root.addView(annBtnRow)

            // 标注列表(默认隐藏)
            annContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
            root.addView(annContainer)

            // 保存
            Button(this).apply {
                text = "💾 保存全部"; textSize = 14f; setBackgroundColor(0xFF2D6A2D.toInt()); setTextColor(0xFF5CFF5C.toInt())
                setPadding(0, 12, 0, 12); setOnClickListener { saveAll() }
            }.also { root.addView(it) }

            // 初始化MeldMarkerView(稍后loaded时setImage)
            meldMarkerView = MeldMarkerView(this).apply {
                layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
                visibility = View.GONE
                onAnnotationComplete = { ann -> onMeldAnnotationAdded(ann) }
            }
            contentArea.addView(meldMarkerView)

            // 初始化ScrollView(手牌/河底用)
            scrollContent = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            val scroll = ScrollView(this).apply { addView(scrollContent, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)); visibility = View.GONE }
            contentArea.addView(scroll)
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
                text.startsWith("content://") -> { contentResolver.openInputStream(Uri.parse(text))?.use { BitmapFactory.decodeStream(it, null, opts) } }
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
        val img = currentBitmap ?: return; slices.clear(); scrollContent.removeAllViews()
        meldMarkerView.visibility = View.GONE
        contentArea.getChildAt(1)?.visibility = View.GONE  // ScrollView
        annContainer.visibility = View.GONE; annContainer.removeAllViews()
        btnAddAnn.parent?.let { (it as View).visibility = View.GONE }
        isMeldAnnotationMode = false
        meldMarkerView.mode = MeldMarkerView.Mode.PAN

        FLog.i("CollAct", "doSlice tab=$currentTab img=${img.width}x${img.height}")
        when (currentTab) {
            "hand" -> {
                contentArea.getChildAt(1)?.visibility = View.VISIBLE  // ScrollView
                sliceHandAndDrawn(img)
                for ((i, s) in slices.withIndex()) addSliceRow(i, s, scrollContent)
            }
            "meld" -> {
                // 切出副露区域的子图给MeldMarkerView
                sliceHandAndDrawn(img)  // 算handEndX/drawnEndX

                val iw = img.width; val ih = img.height
                val meldX = (if (drawnEndX > 0) drawnEndX else handEndX + HAND_FACE_W) + 10
                val roiY = Math.max(0, HAND_FACE_Y - 50); val roiH = Math.min(HAND_FACE_H + 80, ih - roiY).toInt()
                val meldW = Math.min(iw - meldX, img.width - meldX)
                FLog.i("CollAct", "meldView: meldX=$meldX roiY=$roiY meldW=$meldW roiH=$roiH")
                if (meldW < 20 || roiH < 20) {
                    statusLabel.text = "副露区太小或不存在"
                } else {
                    val meldBmp = Bitmap.createBitmap(img, meldX.coerceIn(0, iw-1), roiY.coerceIn(0, ih-1), meldW.coerceAtMost(iw-meldX), roiH.coerceAtMost(ih-roiY))
                    meldMarkerView.setImage(meldBmp)
                    meldMarkerView.visibility = View.VISIBLE
                    btnAddAnn.parent?.let { (it as View).visibility = View.VISIBLE }
                    annContainer.visibility = View.VISIBLE
                    slices.clear()  // 副露不用slices, 用annotations
                }
            }
            "river" -> sliceRiver(img)
        }
        FLog.i("CollAct", "doSlice done: ${slices.size} slices, ${meldMarkerView.getAnnotations().size} annotations")
        statusLabel.text = "截图 ${img.width}×${img.height} — $currentTab: ${if (currentTab == "meld") "${meldMarkerView.getAnnotations().size}标" else "${slices.size}张"}"
    }

    // ═══════ 副露手动标注 ═══════
    private fun startMeldAnnotation() {
        meldMarkerView.startAnnotation()
        setAnnotationButtons(true)
        statusLabel.text = "依次点4个角: 左上→右上→右下→左下"
    }

    private fun finishMeldAnnotation() {
        if (meldMarkerView.mode == MeldMarkerView.Mode.ADJUST) meldMarkerView.finishAdjust()
        setAnnotationButtons(false)
        refreshAnnotationList()
        statusLabel.text = "副露标注: ${meldMarkerView.getAnnotations().size} 张"
    }

    private fun setAnnotationButtons(annotating: Boolean) {
        btnAddAnn.setTextColor(if (annotating) 0xFF80B080.toInt() else 0xFF5CFF5C.toInt())
        btnAddAnn.isEnabled = !annotating || meldMarkerView.mode != MeldMarkerView.Mode.MARK
        btnDoneAnn.isEnabled = annotating
        btnDelAnn.isEnabled = true
    }

    private fun onMeldAnnotationAdded(ann: MeldMarkerView.Annotation) {
        setAnnotationButtons(false)
        refreshAnnotationList()
        statusLabel.text = "副露标注: ${meldMarkerView.getAnnotations().size} 张"
    }

    private fun refreshAnnotationList() {
        annContainer.removeAllViews()
        val anns = meldMarkerView.getAnnotations()
        for ((i, ann) in anns.withIndex()) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(2, 4, 2, 4) }
            row.addView(TextView(this).apply { text = "${i+1}"; textSize = 11f; setTextColor(0xFF80B080.toInt()); setPadding(0, 0, 6, 0) })

            // 裁剪缩略图
            val crop = meldMarkerView.cropAnnotation(ann)
            if (crop != null) {
                val dstH = dp(40); val dstW = Math.min(dp(52), Math.max(dp(22), crop.width * dstH / crop.height))
                row.addView(ImageView(this).apply {
                    setImageBitmap(Bitmap.createScaledBitmap(crop, dstW, dstH, true))
                    setBackgroundColor(0xFF1A2A1A.toInt()); layoutParams = LinearLayout.LayoutParams(dstW, dstH).apply { marginEnd = 4 }
                })
            }

            // Spinner
            val spinner = Spinner(this).apply {
                val options = mutableListOf("未识别"); options.addAll(tileNames)
                adapter = ArrayAdapter(this@TemplateCollectorActivity, android.R.layout.simple_spinner_dropdown_item, options)
                setBackgroundColor(0xFF2D3A2D.toInt()); setPopupBackgroundDrawable(ColorDrawable(0xFF1A2E1A.toInt()))
                if (ann.label != "未识别") { val idx = tileNames.indexOf(ann.label); if (idx >= 0) setSelection(idx + 1) }
                setOnItemSelectedListener(object : AdapterView.OnItemSelectedListener {
                    override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) { ann.label = parent?.getItemAtPosition(pos)?.toString() ?: "未识别" }
                    override fun onNothingSelected(parent: AdapterView<*>?) {}
                }); layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }; row.addView(spinner)

            // 调整按钮
            Button(this).apply {
                text = "调"; textSize = 9f; setBackgroundColor(0xFF2D3A2D.toInt()); setTextColor(0xFF80B080.toInt()); setPadding(dp(4), dp(2), dp(4), dp(2))
                setOnClickListener {
                    meldMarkerView.startAdjust(i)
                    setAnnotationButtons(true)
                    statusLabel.text = "拖拽角点调整，点「完成」确认"
                }
            }.also { row.addView(it) }

            // 删除
            Button(this).apply {
                text = "✕"; textSize = 9f; setBackgroundColor(0xFF3A2D2D.toInt()); setTextColor(0xFFFF8080.toInt()); setPadding(dp(4), dp(2), dp(4), dp(2))
                setOnClickListener { meldMarkerView.removeAnnotation(i); refreshAnnotationList(); statusLabel.text = "副露标注: ${meldMarkerView.getAnnotations().size} 张" }
            }.also { row.addView(it) }

            annContainer.addView(row)
        }
    }

    // ═══════ 手牌+摸牌 ═══════
    private var handEndX = HAND_FACE_X; private var drawnEndX = 0

    private fun sliceHandAndDrawn(img: Bitmap) {
        val iw = img.width; val ih = img.height; var lastMatchedX = -1; var lastVisibleX = -1; var matchedCount = 0; var emptyCount = 0
        for (i in 0..12) {
            val x = HAND_FACE_X + i * HAND_SLOT_GAP
            if (x + HAND_FACE_W > iw || HAND_FACE_Y + HAND_FACE_H > ih) continue
            val px = IntArray(HAND_FACE_W * HAND_FACE_H); img.getPixels(px, 0, HAND_FACE_W, x, HAND_FACE_Y, HAND_FACE_W, HAND_FACE_H)
            val mean = px.sumOf { (Color.red(it) + Color.green(it) + Color.blue(it)) / 3 }.toDouble() / px.size
            if (mean < 80) { emptyCount++; continue }
            lastVisibleX = x
            val bmp = Bitmap.createBitmap(img, x, HAND_FACE_Y, HAND_FACE_W, HAND_FACE_H)
            val result = TileMatcher.identifySingleTile(bmp)
            if (result != null && result.second >= 0.85) { lastMatchedX = x; matchedCount++ }
            if (currentTab != "meld") slices.add(TileSlice("牌${i+1}", bmp, "hand_${i}"))
        }
        handEndX = if (lastVisibleX >= 0) lastVisibleX + HAND_FACE_W else HAND_FACE_X
        val drawnX = handEndX + 43
        FLog.i("CollAct", "手牌: match=$matchedCount empty=$emptyCount handEndX=$handEndX drawnX=$drawnX")
        drawnEndX = 0
        if (drawnX + HAND_FACE_W <= iw) {
            val px = IntArray(HAND_FACE_W * HAND_FACE_H); img.getPixels(px, 0, HAND_FACE_W, drawnX, HAND_FACE_Y, HAND_FACE_W, HAND_FACE_H)
            val mean = px.sumOf { (Color.red(it) + Color.green(it) + Color.blue(it)) / 3 }.toDouble() / px.size
            FLog.i("CollAct", "摸牌: x=$drawnX mean=${mean.toInt()}")
            if (mean >= 80 && currentTab != "meld") { slices.add(TileSlice("摸牌", Bitmap.createBitmap(img, drawnX, HAND_FACE_Y, HAND_FACE_W, HAND_FACE_H), "hand_drawn")); drawnEndX = drawnX + HAND_FACE_W }
            else if (mean >= 80) { drawnEndX = drawnX + HAND_FACE_W }
        }
    }

    private fun sliceRiver(img: Bitmap) {}

    // ═══════ UI 渲染 ═══════
    private val tileNames = arrayOf("一万","二万","三万","四万","五万","六万","七万","八万","九万","一筒","二筒","三筒","四筒","五筒","六筒","七筒","八筒","九筒","一索","二索","三索","四索","五索","六索","七索","八索","九索","東","南","西","北","白","発","中")

    private fun addSliceRow(index: Int, s: TileSlice, parent: LinearLayout) {
        try {
            FLog.i("CollAct", "addSliceRow[$index] ${s.id} size=${s.bmp.width}x${s.bmp.height}")
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(2, 4, 2, 4) }
            row.addView(TextView(this).apply { text = "${index+1}"; textSize = 9f; setTextColor(0xFF80B080.toInt()); setPadding(0, 0, 4, 0); layoutParams = LinearLayout.LayoutParams(dp(18), LinearLayout.LayoutParams.WRAP_CONTENT) })
            val dstH = dp(48); val dstW = Math.min(dp(52), Math.max(dp(22), s.bmp.width * dstH / s.bmp.height))
            row.addView(ImageView(this).apply { setImageBitmap(Bitmap.createScaledBitmap(s.bmp, dstW, dstH, true)); setBackgroundColor(0xFF1A2A1A.toInt()); layoutParams = LinearLayout.LayoutParams(dstW, dstH).apply { marginEnd = 6 } })
            val autoResult = autoIdentify(s.bmp)
            val autoName = if (autoResult != null && autoResult.second >= 0.85) tileNames.getOrNull(autoResult.first) else null
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
            parent.addView(row)
        } catch (e: Exception) { FLog.e("CollAct", "addSliceRow[$index] 崩溃", e) }
    }

    private fun autoIdentify(bmp: Bitmap): Pair<Int, Double>? = TileMatcher.identifySingleTile(bmp)

    // ═══════ 保存 ═══════
    private fun saveAll() {
        if (currentTab == "meld") {
            saveMeldAnnotations()
            return
        }
        FLog.i("CollAct", "saveAll tab=$currentTab slices=${slices.size}")
        if (slices.isEmpty()) { Toast.makeText(this, "请先加载截图", Toast.LENGTH_SHORT).show(); return }
        val outDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "mahjong_templates")
        if (!outDir.exists()) outDir.mkdirs()
        var saved = 0; var skipped = 0
        for (s in slices) {
            val name = s.label
            if (name == "未识别" || name == "未知") { skipped++; continue }
            val fileName = if (currentTab == "hand") {
                val direction = if (s.bmp.width > s.bmp.height) "横" else "竖"
                var candidate = "${name}_${direction}.png"; var counter = 2
                while (File(outDir, candidate).exists()) { candidate = "${name}_${direction}${counter}.png"; counter++ }
                candidate
            } else "${s.label}.png"
            try { FileOutputStream(File(outDir, fileName)).use { s.bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }; saved++ } catch (_: Exception) { skipped++ }
        }
        AlertDialog.Builder(this).setTitle("保存完成").setMessage("$saved/${slices.size} 张 →\n${outDir.absolutePath}").setPositiveButton("确定", null).show()
        statusLabel.text = "已保存 $saved 张"
    }

    private fun saveMeldAnnotations() {
        val anns = meldMarkerView.getAnnotations()
        FLog.i("CollAct", "saveAll meld anns=${anns.size}")
        if (anns.isEmpty()) { Toast.makeText(this, "请先添加标注", Toast.LENGTH_SHORT).show(); return }
        val outDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "mahjong_templates")
        if (!outDir.exists()) outDir.mkdirs()
        var saved = 0; var skipped = 0
        for (ann in anns) {
            if (ann.label == "未识别" || ann.label == "未知") { skipped++; continue }
            val crop = meldMarkerView.cropAnnotation(ann) ?: run { skipped++; continue }
            var candidate = "${ann.label}_${ann.direction}.png"; var counter = 2
            while (File(outDir, candidate).exists()) { candidate = "${ann.label}_${direction}${counter}.png"; counter++ }
            try { FileOutputStream(File(outDir, candidate)).use { crop.compress(Bitmap.CompressFormat.PNG, 100, it) }; saved++ } catch (_: Exception) { skipped++ }
        }
        FLog.i("CollAct", "meld保存: $saved/$saved+$skipped")
        AlertDialog.Builder(this).setTitle("保存完成")
            .setMessage("已保存 $saved/${anns.size} 张\n$skipped 张未标注(跳过)\n→ ${outDir.absolutePath}\n\n记得把.png文件移到 assets/meld_tiles/ 目录并重新编译")
            .setPositiveButton("确定", null).show()
        statusLabel.text = "已保存 $saved 张"
    }

    private fun findLatestScreenshot(): String? {
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).absolutePath)
        if (!dir.exists()) return null
        return dir.listFiles { f -> f.isFile && (f.name.endsWith(".jpg") || f.name.endsWith(".png")) }?.maxByOrNull { it.lastModified() }?.absolutePath
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    @Deprecated("Use registerForActivityResult")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_PICK_IMAGE && resultCode == RESULT_OK && data?.data != null) {
            selectedUri = data.data; pathInput.setText(selectedUri.toString()); loadAndSlice()
        }
    }

    override fun onDestroy() { super.onDestroy(); FLog.i("CollAct", "onDestroy"); currentBitmap?.recycle() }
}
