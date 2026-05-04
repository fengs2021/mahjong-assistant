package com.mahjong.assistant.overlay

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.view.*
import android.widget.*
import androidx.core.app.NotificationCompat
import com.mahjong.assistant.R
import com.mahjong.assistant.ReviewActivity
import com.mahjong.assistant.capture.CaptureActivity
import com.mahjong.assistant.capture.ScreenCaptureService
import com.mahjong.assistant.capture.TileMatcher
import com.mahjong.assistant.engine.DefenseAnalyzer
import com.mahjong.assistant.engine.Efficiency
import com.mahjong.assistant.engine.Shanten
import com.mahjong.assistant.engine.TenhouClient
import com.mahjong.assistant.engine.Tiles
import com.mahjong.assistant.util.FLog
import java.io.File

/**
 * 绿色悬浮窗 Service — 雀魂出牌辅助覆盖层
 */
class OverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var overlayView: View
    private var layoutParams: WindowManager.LayoutParams? = null

    // UI 组件
    private lateinit var titleBar: LinearLayout
    private lateinit var tileDisplay: TextView
    private lateinit var shantenLabel: TextView
    private lateinit var recommendLabel: TextView
    private lateinit var dangerLabel: TextView
    private lateinit var captureBtn: Button
    private lateinit var manualBtn: Button
    private lateinit var autoBtn: Button

    private var currentHand = IntArray(0)
    private var prevHandTiles = IntArray(0)  // 上一帧手牌, 用于检测提起的牌

    // 配色 (createOverlay 中使用)
    private val colorPanel = 0x40000000.toInt()  // 黑色25%

    // 自动截图
    private val autoHandler = Handler(Looper.getMainLooper())
    private var autoRunnable: Runnable? = null
    private var isAutoCapturing = false
    private val autoIntervalMs = 3000L  // 3秒

    // 天凤查询后台线程
    private var tenhouThread: HandlerThread? = null
    private var tenhouHandler: Handler? = null
    private var tenhouWebView: android.webkit.WebView? = null

    // 存储最近识别结果，供点击跳转ReviewActivity
    private var lastTileIds = IntArray(0)
    private var lastConfidences = DoubleArray(0)
    private var lastScreenshotPath: String? = null

    // 内嵌牌选择面板
    private var isPickerVisible = false
    private lateinit var pickerPanel: LinearLayout
    private var pickerButtons = mutableMapOf<Int, Button>()
    private val editedHand = mutableListOf<Int>()

    companion object {
        const val CHANNEL_ID = "mahjong_overlay"
        const val NOTIFICATION_ID = 1
        const val ACTION_STOP = "com.mahjong.assistant.STOP"
        const val ACTION_UPDATE = "com.mahjong.assistant.UPDATE"
        const val ACTION_UPDATE_STATUS = "com.mahjong.assistant.UPDATE_STATUS"
        const val ACTION_INIT_CAPTURE = "com.mahjong.assistant.INIT_CAPTURE"
        const val ACTION_CAPTURE_DONE = "com.mahjong.assistant.CAPTURE_DONE"
        const val EXTRA_HAND = "hand_tiles"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_DATA = "data"

        const val CODE_UNSET = Int.MIN_VALUE
        @JvmStatic var captureResultCode: Int = CODE_UNSET
        @JvmStatic var captureResultData: Intent? = null
    }

    // 截图相关 (已移至 CaptureActivity; 仅保留授权凭据)
    private var screenDpi = 0
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onCreate() {
        super.onCreate()
        try {
            FLog.init(filesDir)
            FLog.i("OverlaySvc", "onCreate")

            windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

            val m = resources.displayMetrics
            screenDpi = m.densityDpi
            FLog.i("OverlaySvc", "dpi=$screenDpi")
            val tmOk = TileMatcher.init(this)
            FLog.i("OverlaySvc", "TileMatcher.init=$tmOk diag=${TileMatcher.getDiagnostic()}")
            checkMajsoulPackage()
            createNotificationChannel()
            // Android 15 禁止普通 App 在 startForeground 声明 mediaProjection
            // (需要 CAPTURE_VIDEO_OUTPUT 签名级权限)
            // 仅声明 specialUse; getMediaProjection() 在 targetSdk=34 时不检查 FGS type
            val fgTypes = if (Build.VERSION.SDK_INT >= 34)
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            else 0
            startForeground(NOTIFICATION_ID, buildNotification(), fgTypes)
            FLog.i("OverlaySvc", "startForeground OK")
            createOverlay()
            FLog.i("OverlaySvc", "createOverlay OK")
            tileDisplay.text = "就绪"

            // 天凤 WebView + 后台线程 (用于异步查询天凤牌理)
            try {
                tenhouWebView = android.webkit.WebView(this).apply {
                    settings.javaScriptEnabled = true
                }
                TenhouClient.init(tenhouWebView!!)
                tenhouThread = HandlerThread("TenhouThread").apply { start() }
                tenhouHandler = Handler(tenhouThread!!.looper)
                FLog.i("OverlaySvc", "TenhouClient init OK")
            } catch (e: Exception) {
                FLog.e("OverlaySvc", "TenhouClient init失败", e)
            }
        } catch (e: Exception) {
            FLog.e("OverlaySvc", "onCreate崩溃", e)
            stopSelf()
        } catch (e: Error) {
            FLog.e("OverlaySvc", "onCreate JNI崩溃", e)
            stopSelf()
        }
    }

    // ─── 通知 (前台Service必需) ───

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "雀魂助手",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "悬浮窗运行中"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("雀魂助手")
            .setContentText("悬浮窗运行中")
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_media_pause, "停止",
                PendingIntent.getService(this, 0,
                    Intent(this, OverlayService::class.java).setAction(ACTION_STOP),
                    PendingIntent.FLAG_IMMUTABLE))
            .build()
    }

    // ─── 悬浮窗布局 ───

    private fun createOverlay() {
        val ctx = this

        val colorBg = 0xD9185018.toInt()
        val colorPanel = 0x40000000.toInt()
        val colorAccent = 0xFF5CFF5C.toInt()
        val colorText = 0xFFD0F0D0.toInt()
        val colorSub = 0xFF80B080.toInt()

        fun roundedBg(color: Int, radius: Float): android.graphics.drawable.GradientDrawable {
            return android.graphics.drawable.GradientDrawable().apply {
                setColor(color); cornerRadius = radius
            }
        }

        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedBg(colorBg, 10f)
            setPadding(3, 2, 3, 3)
        }

        // 标题栏: 文字 + 小关闭图标
        val titleBar = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(4, 2, 2, 2)
            gravity = Gravity.CENTER_VERTICAL
        }
        val titleText = TextView(ctx).apply {
            text = "牌效助手"
            textSize = 10f; setTextColor(colorAccent)
        }
        titleBar.addView(titleText, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val closeBtn = TextView(ctx).apply {
            text = "✕"; textSize = 10f
            setTextColor(0xFF558855.toInt())
            setPadding(8, 0, 4, 0)
            setOnClickListener { stopSelf() }
        }
        titleBar.addView(closeBtn)
        container.addView(titleBar)

        // 当前牌型
        tileDisplay = TextView(ctx).apply {
            text = "等候截图..."
            textSize = 11f; setTextColor(colorAccent)
            setSingleLine(false); maxLines = 2
            gravity = Gravity.CENTER
            setPadding(2, 3, 2, 1)
        }
        container.addView(tileDisplay, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        // 分隔
        container.addView(View(ctx).apply {
            setBackgroundColor(0xFF3A6A3A.toInt())
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1))

        // 向听数
        shantenLabel = TextView(ctx).apply {
            text = "向听: --"; textSize = 15f
            setTextColor(colorAccent); gravity = Gravity.CENTER
            setPadding(0, 3, 0, 1)
            background = roundedBg(colorPanel, 5f)
        }
        container.addView(shantenLabel, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = 3 })

        // 推荐切牌 (内联显示前2)
        recommendLabel = TextView(ctx).apply {
            text = "等待分析..."; textSize = 12f; setTextColor(colorAccent)
            gravity = Gravity.CENTER
            setSingleLine(false); maxLines = 2
            setPadding(4, 4, 4, 4)
            background = roundedBg(colorPanel, 6f)
        }
        container.addView(recommendLabel, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = 3 })

        // 放铳警告
        dangerLabel = TextView(ctx).apply {
            text = ""; textSize = 10f; setTextColor(0xFFFFCC44.toInt())
            gravity = Gravity.CENTER
            setSingleLine(false); maxLines = 1
            setPadding(4, 2, 4, 2)
            background = roundedBg(0x50303000.toInt(), 4f)
            visibility = View.GONE  // 默认隐藏
        }
        container.addView(dangerLabel, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = 2 })

        // 控制按钮 (小)
        val btnRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 2, 0, 0)
        }
        captureBtn = Button(ctx).apply {
            text = "📷"; textSize = 10f; minWidth = 0; minHeight = 0
            setBackgroundColor(0xFF2D6A2D.toInt()); setTextColor(colorText)
            setPadding(0, 2, 0, 2)
            setOnClickListener { onCapture() }
        }
        btnRow.addView(captureBtn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = 2 })
        manualBtn = Button(ctx).apply {
            text = "✏"; textSize = 10f; minWidth = 0; minHeight = 0
            setBackgroundColor(colorPanel); setTextColor(colorSub)
            setPadding(0, 2, 0, 2)
            setOnClickListener { onManualInput() }
        }
        btnRow.addView(manualBtn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = 2 })
        autoBtn = Button(ctx).apply {
            text = "🔄"; textSize = 10f; minWidth = 0; minHeight = 0
            setBackgroundColor(colorPanel); setTextColor(colorSub)
            setPadding(0, 2, 0, 2)
            setOnClickListener { toggleAutoCapture() }
        }
        btnRow.addView(autoBtn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        container.addView(btnRow)

        // 内嵌牌选择面板 (默认隐藏)
        pickerPanel = createInlinePicker()
        container.addView(pickerPanel)

        overlayView = container
        this.titleBar = titleBar

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        layoutParams = WindowManager.LayoutParams(
            (140 * resources.displayMetrics.density).toInt(),
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = 10; y = 100
        }

        titleBar.setOnTouchListener(DragTouchListener())
        windowManager.addView(overlayView, layoutParams)
        FLog.i("OverlaySvc", "overlay created: dpi=$screenDpi")
    }

    // ─── 回调 ───

    /**
     * 启动时诊断: 能否查询到雀魂包名
     * Android 11+ 需要在 manifest <queries> 中声明
     */
    private var majsoulPkg: String? = null

    private fun checkMajsoulPackage() {
        val candidates = arrayOf(
            "com.soulgamechst.majsoul",
            "com.majsoul.riichimahjong",
            "com.shengqu.majsoul",
            "com.komoe.majsoulgp",
            "com.dmm.majsoul",
        )
        for (pkg in candidates) {
            try {
                val intent = packageManager.getLaunchIntentForPackage(pkg)
                if (intent != null) {
                    majsoulPkg = pkg
                    FLog.i("OverlaySvc", "✓ 找到雀魂: $pkg")
                    return
                }
            } catch (_: Exception) {}
        }
        FLog.w("OverlaySvc", "✗ 未找到雀魂! queries声明可能未生效")
    }

    private inner class DragTouchListener : View.OnTouchListener {
        private var initialX = 0
        private var initialY = 0
        private var initialTouchX = 0f
        private var initialTouchY = 0f

        override fun onTouch(v: View, event: MotionEvent): Boolean {
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = layoutParams!!.x
                    initialY = layoutParams!!.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    layoutParams!!.x = initialX - (event.rawX - initialTouchX).toInt()
                    layoutParams!!.y = initialY + (event.rawY - initialTouchY).toInt()
                    windowManager.updateViewLayout(overlayView, layoutParams)
                    return true
                }
            }
            return false
        }
    }

    // ─── 更新建议 ───

    fun updateAdvice(hand: IntArray) {
        if (hand.size == 13) {
            currentHand = hand
            updateAdviceShantenOnly(hand)
            return
        }
        if (hand.size != 14) {
            return
        }

        currentHand = hand
        val result = Shanten.calculate(hand)
        val advice = Efficiency.analyze(hand)
        val safety = DefenseAnalyzer.analyzeBasic(hand)  // 基础模式: 无河底

        // 向听数
        shantenLabel.text = when (result.shanten) {
            -1 -> "和了!!!"
            0 -> "听牌!"
            else -> "向听: ${result.shanten}"
        }
        shantenLabel.setTextColor(when {
            result.shanten <= -1 -> 0xFFFFCC00.toInt()
            result.shanten == 0 -> 0xFF00CC66.toInt()
            else -> 0xFFA0F0A0.toInt()
        })

        // 推荐 (内联前2) + 安全标注
        if (advice.isNotEmpty()) {
            val best = advice[0]
            val bestSafe = DefenseAnalyzer.dangerOf(best.tile, safety)
            val safeEmoji = bestSafe?.let { DefenseAnalyzer.safetyEmoji(it.dangerLevel) } ?: ""
            val sb = StringBuilder("🏆 切${best.tileName} 进${best.ukeire}枚 $safeEmoji")
            if (advice.size >= 2) {
                val second = advice[1]
                val secondSafe = DefenseAnalyzer.dangerOf(second.tile, safety)
                val sEmoji = secondSafe?.let { DefenseAnalyzer.safetyEmoji(it.dangerLevel) } ?: ""
                sb.append("\n次切${second.tileName} $sEmoji")
            }
            recommendLabel.text = sb.toString()
        }

        val handStr = Tiles.toCompactString(hand)
        tileDisplay.text = handStr

        // 异步查询天凤牌理, 优先采用天凤推荐
        val tenhouH = tenhouHandler
        if (tenhouH != null && advice.isNotEmpty()) {
            tenhouH.post {
                try {
                    val th = TenhouClient.query(hand, 2500)
                    if (th != null) {
                        runOnUiThread {
                            shantenLabel.text = when (th.shanten) {
                                -1 -> "和了!!! [天凤]"
                                0 -> "听牌! [天凤]"
                                else -> "向听: ${th.shanten} [天凤]"
                            }
                            recommendLabel.text = "切 ${th.bestDiscardName}  (进张${th.ukeire}枚) [天凤]"
                        }
                    }
                } catch (e: Exception) {
                    FLog.e("OverlaySvc", "tenhou query error", e)
                }
            }
        }
    }

    /** 13张手牌: 向听数 + 有效进张 */
    private fun updateAdviceShantenOnly(hand13: IntArray) {
        if (hand13.size != 13) return
        val (shanten, ukeireTiles) = Efficiency.analyze13(hand13)
        val totalUkeire = ukeireTiles.sumOf { it.second }

        tileDisplay.text = Tiles.toCompactString(hand13)

        shantenLabel.text = when {
            shanten <= -1 -> "和了!!!"
            shanten == 0 -> "听牌!"
            else -> "向听: $shanten"
        }
        shantenLabel.setTextColor(when {
            shanten <= -1 -> 0xFFFFCC00.toInt()
            shanten == 0 -> 0xFF00CC66.toInt()
            else -> 0xFFA0F0A0.toInt()
        })

        if (ukeireTiles.isNotEmpty()) {
            val names = ukeireTiles.take(6).joinToString(" ") { "${Tiles.name(it.first)}×${it.second}" }
            recommendLabel.text = "进${ukeireTiles.size}种${totalUkeire}枚: $names"
        } else {
            recommendLabel.text = "无有效进张"
        }
    }

    private fun launchReview(tileIds: IntArray, confidences: DoubleArray, ssPath: String?) {
        try {
            val intent = Intent(this, ReviewActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra(ReviewActivity.EXTRA_TILE_IDS, tileIds)
                putExtra(ReviewActivity.EXTRA_CONFIDENCES, confidences)
                putExtra(ReviewActivity.EXTRA_LOG, TileMatcher.lastLog)
                if (ssPath != null) putExtra(ReviewActivity.EXTRA_SCREENSHOT_PATH, ssPath)
            }
            startActivity(intent)
        } catch (_: Exception) {}
    }

    private fun onCapture() {
        if (Build.VERSION.SDK_INT < 21) {
            FLog.w("OverlaySvc", "SDK too old: ${Build.VERSION.SDK_INT}")
            return
        }
        val svc = ScreenCaptureService.instance
        if (svc != null) {
            FLog.i("OverlaySvc", "onCapture → A11y")
            svc.captureAndRecognize()
        } else {
            // 无障碍不可用 → 走 MediaProjection
            FLog.i("OverlaySvc", "onCapture → CaptureActivity (A11y null)")
            val intent = Intent(this, CaptureActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
        }
    }

    /** 切换自动截图 */
    private fun toggleAutoCapture() {
        if (isAutoCapturing) {
            stopAutoCapture()
        } else {
            startAutoCapture()
        }
    }

    private fun startAutoCapture() {
        if (isAutoCapturing) return
        isAutoCapturing = true
        autoBtn.text = "⏸️"
        autoBtn.setBackgroundColor(0xFFFF6B35.toInt())
        FLog.i("OverlaySvc", "auto capture started")

        val runnable = object : Runnable {
            override fun run() {
                if (!isAutoCapturing) return
                onCapture()
                autoHandler.postDelayed(this, autoIntervalMs)
            }
        }
        autoRunnable = runnable
        autoHandler.post(runnable)
    }

    private fun stopAutoCapture() {
        isAutoCapturing = false
        autoRunnable?.let { autoHandler.removeCallbacks(it) }
        autoRunnable = null
        autoBtn.text = "🔄"
        autoBtn.setBackgroundColor(0x40000000.toInt())
        FLog.i("OverlaySvc", "auto capture stopped")
    }

    /** 点击建议区 → 打开ReviewActivity手动编辑 */
    private fun openReviewForEdit() {
        if (lastTileIds.isEmpty()) return
        launchReview(lastTileIds, lastConfidences, lastScreenshotPath)
    }

    // ─── 内嵌牌选择面板 ───

    private fun createInlinePicker(): LinearLayout {
        val ctx = this
        val panel = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(2, 4, 2, 2)
        }

        // 4列×9行 = 34牌 + 确认/清空
        val grid = GridLayout(ctx).apply {
            columnCount = 4
            useDefaultMargins = false
        }

        val tileNames = arrayOf(
            "一万","二万","三万","四万","五万","六万","七万","八万","九万",
            "一筒","二筒","三筒","四筒","五筒","六筒","七筒","八筒","九筒",
            "一索","二索","三索","四索","五索","六索","七索","八索","九索",
            "东","南","西","北","白","发","中"
        )

        for (tileId in 0..33) {
            val btn = Button(ctx).apply {
                text = tileNames[tileId]
                textSize = 9f; minWidth = 0; minHeight = 0
                setPadding(4, 4, 4, 4)
                setBackgroundColor(0xFF2D5A2D.toInt())
                setTextColor(0xFFA0F0A0.toInt())
                setOnClickListener { onTilePicked(tileId) }
                val params = GridLayout.LayoutParams().apply {
                    width = 0; height = GridLayout.LayoutParams.WRAP_CONTENT
                    columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                    setMargins(2, 2, 2, 2)
                }
                layoutParams = params
            }
            pickerButtons[tileId] = btn
            grid.addView(btn)
        }
        panel.addView(grid)

        // 操作行: 清空 + 确认
        val opRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 4, 0, 0)
        }
        val clearBtn = Button(ctx).apply {
            text = "清空"; textSize = 9f; minWidth = 0; minHeight = 0
            setBackgroundColor(0xFF553333.toInt()); setTextColor(0xFFFFAAAA.toInt())
            setPadding(8, 4, 8, 4)
            setOnClickListener { editedHand.clear(); refreshPickerButtons() }
        }
        opRow.addView(clearBtn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = 4 })
        val confirmBtn = Button(ctx).apply {
            text = "✓ 确认"; textSize = 9f; minWidth = 0; minHeight = 0
            setBackgroundColor(0xFF2D6A2D.toInt()); setTextColor(0xFF5CFF5C.toInt())
            setPadding(8, 4, 8, 4)
            setOnClickListener { confirmManualHand() }
        }
        opRow.addView(confirmBtn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        panel.addView(opRow)

        return panel
    }

    private fun togglePicker() {
        isPickerVisible = !isPickerVisible
        if (isPickerVisible) {
            // 初始化为当前手牌
            editedHand.clear()
            if (currentHand.isNotEmpty()) {
                editedHand.addAll(currentHand.toList())
            }
            refreshPickerButtons()
            pickerPanel.visibility = View.VISIBLE
            manualBtn.setBackgroundColor(0xFF2D6A2D.toInt())
            manualBtn.setTextColor(0xFF5CFF5C.toInt())
        } else {
            pickerPanel.visibility = View.GONE
            manualBtn.setBackgroundColor(colorPanel)
            manualBtn.setTextColor(0xFF80B080.toInt())
        }
    }

    private fun onTilePicked(tileId: Int) {
        val cnt = editedHand.count { it == tileId }
        if (cnt >= 4) {
            editedHand.removeAll { it == tileId }
        } else {
            if (editedHand.size >= 14) return // 最多14张
            editedHand.add(tileId)
        }
        editedHand.sort()
        refreshPickerButtons()
    }

    private fun refreshPickerButtons() {
        for ((tileId, btn) in pickerButtons) {
            val cnt = editedHand.count { it == tileId }
            val name = btn.text.toString().replace(Regex("×\\d"), "")
            btn.text = if (cnt > 0) "${name}×$cnt" else name
            btn.setBackgroundColor(if (cnt >= 4) 0xFF2D2D2D.toInt() else 0xFF2D5A2D.toInt())
            btn.setTextColor(if (cnt >= 4) 0xFF888888.toInt() else 0xFFA0F0A0.toInt())
        }
    }

    private fun confirmManualHand() {
        val total = editedHand.size
        if (total !in 13..14) {
            tileDisplay.text = "需要13或14张牌 (当前${total}张)"
            return
        }
        isPickerVisible = false
        pickerPanel.visibility = View.GONE
        manualBtn.setBackgroundColor(colorPanel)
        manualBtn.setTextColor(0xFF80B080.toInt())

        val hand = editedHand.sorted().toIntArray()
        updateAdvice(hand)
    }

    private fun runOnUiThread(action: () -> Unit) {
        mainHandler.post(action)
    }

    private fun onManualInput() {
        togglePicker()
    }

    // ─── Service 生命周期 ───

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_INIT_CAPTURE -> {
                captureResultCode = intent.getIntExtra(EXTRA_RESULT_CODE, -1)
                if (Build.VERSION.SDK_INT >= 33) {
                    captureResultData = intent.getParcelableExtra(EXTRA_DATA, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    captureResultData = intent.getParcelableExtra(EXTRA_DATA)
                }
                return START_STICKY
            }
            ACTION_CAPTURE_DONE -> {
                val tileIds = intent.getIntArrayExtra("tile_ids") ?: intArrayOf()
                val confidences = intent.getDoubleArrayExtra("confidences") ?: doubleArrayOf()
                val ssPath = intent.getStringExtra("screenshot_path")

                lastTileIds = tileIds
                lastConfidences = confidences
                lastScreenshotPath = ssPath

                // 检测提起的牌: 对比当前帧vs上一帧, 消失的牌=提起的牌
                if (tileIds.isNotEmpty() && prevHandTiles.isNotEmpty() && tileIds.size < prevHandTiles.size) {
                    val missing = prevHandTiles.filter { pt -> tileIds.none { it == pt } }
                    if (missing.isNotEmpty()) {
                        val safety = DefenseAnalyzer.analyzeBasic(prevHandTiles)
                        val sb = StringBuilder()
                        for (tid in missing) {
                            if (tid !in 0..33) continue
                            val sr = DefenseAnalyzer.dangerOf(tid, safety)
                            val emoji = sr?.let { DefenseAnalyzer.safetyEmoji(it.dangerLevel) } ?: ""
                            val name = Tiles.name(tid)
                            sb.append("提起$name $emoji ")
                        }
                        dangerLabel.text = sb.toString().trim()
                        dangerLabel.visibility = View.VISIBLE
                    }
                }

                // 更新牌型显示
                tileDisplay.text = if (tileIds.isNotEmpty())
                    Tiles.toCompactString(tileIds) else "未识别"

                if (tileIds.size >= 14) {
                    dangerLabel.visibility = View.GONE  // 正常手牌, 隐藏放铳警告
                    updateAdvice(tileIds)
                } else if (tileIds.size == 13) {
                    updateAdviceShantenOnly(tileIds)
                }

                prevHandTiles = tileIds.copyOf()
            }
            ACTION_UPDATE -> {
                val handArray = intent.getIntArrayExtra(EXTRA_HAND)
                if (handArray != null && handArray.size == 14) {
                    updateAdvice(handArray)
                }
            }
            ACTION_UPDATE_STATUS -> {}
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopAutoCapture()
        TenhouClient.release()
        tenhouWebView?.destroy()
        tenhouWebView = null
        tenhouThread?.quitSafely()
        tenhouThread = null
        tenhouHandler = null
        FLog.i("OverlaySvc", "onDestroy")
        if (::overlayView.isInitialized && overlayView.isAttachedToWindow) {
            windowManager.removeView(overlayView)
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
        FLog.shutdown()
    }
}
