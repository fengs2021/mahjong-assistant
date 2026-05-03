package com.mahjong.assistant.overlay

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.*
import android.widget.*
import androidx.core.app.NotificationCompat
import com.mahjong.assistant.R
import com.mahjong.assistant.ReviewActivity
import com.mahjong.assistant.capture.TileMatcher
import com.mahjong.assistant.engine.Efficiency
import com.mahjong.assistant.engine.Shanten
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
    private lateinit var shantenLabel: TextView
    private lateinit var recommendLabel: TextView
    private lateinit var altContainer: LinearLayout
    private lateinit var statusLabel: TextView
    private lateinit var captureBtn: Button
    private lateinit var manualBtn: Button

    private var currentHand = IntArray(0)

    // 存储最近识别结果，供点击跳转ReviewActivity
    private var lastTileIds = IntArray(0)
    private var lastConfidences = DoubleArray(0)
    private var lastScreenshotPath: String? = null

    private val logLines = mutableListOf<String>()
    private fun log(msg: String) {
        logLines.add(msg)
        if (logLines.size > 50) logLines.removeAt(0)
        runOnUiThread { statusLabel.text = logLines.joinToString("\n") }
    }

    companion object {
        const val CHANNEL_ID = "mahjong_overlay"
        const val NOTIFICATION_ID = 1
        const val ACTION_STOP = "com.mahjong.assistant.STOP"
        const val ACTION_UPDATE = "com.mahjong.assistant.UPDATE"
        const val ACTION_UPDATE_STATUS = "com.mahjong.assistant.UPDATE_STATUS"
        const val ACTION_INIT_CAPTURE = "com.mahjong.assistant.INIT_CAPTURE"
        const val EXTRA_HAND = "hand_tiles"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_DATA = "data"

        const val CODE_UNSET = Int.MIN_VALUE
        @JvmStatic var captureResultCode: Int = CODE_UNSET
        @JvmStatic var captureResultData: Intent? = null
    }

    // 截图相关
    private var projection: MediaProjection? = null
    private var screenDpi = 0
    private var isCapturing = false
    private var captureScheduled = false  // 防止重复调度截图
    private var pendingCapture = false  // 授权后自动截图
    private var authPollCount = 0
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
            val fgTypes = if (Build.VERSION.SDK_INT >= 34)
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            else 0
            startForeground(NOTIFICATION_ID, buildNotification(), fgTypes)
            FLog.i("OverlaySvc", "startForeground OK")
            createOverlay()
            FLog.i("OverlaySvc", "createOverlay OK")
            log("就绪 | dpi=$screenDpi")
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

        // 配色 (借鉴 majsoul-tiles-android)
        val colorBg = 0xD9185018.toInt()       // 深绿85%
        val colorPanel = 0x40000000.toInt()     // 黑色25%
        val colorAccent = 0xFF5CFF5C.toInt()    // 亮绿
        val colorText = 0xFFD0F0D0.toInt()      // 淡绿
        val colorSub = 0xFF80B080.toInt()       // 中绿
        val colorBorder = 0xFF3A6A3A.toInt()    // 边框绿
        val colorRed = 0xFFFF5555.toInt()

        // 圆角辅助函数
        fun roundedBg(color: Int, radius: Float): android.graphics.drawable.GradientDrawable {
            return android.graphics.drawable.GradientDrawable().apply {
                setColor(color); cornerRadius = radius
            }
        }

        // 主容器
        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedBg(colorBg, 16f)
            setPadding(8, 8, 8, 8)
        }

        // 标题栏
        val titleBar = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(10, 8, 6, 8)
            gravity = Gravity.CENTER_VERTICAL
        }

        val dot = TextView(ctx).apply {
            text = "●"
            textSize = 10f; setTextColor(colorAccent)
            setPadding(0, 0, 8, 0)
        }
        titleBar.addView(dot)

        val titleText = TextView(ctx).apply {
            text = "牌效助手"
            textSize = 15f; setTextColor(colorAccent)
        }
        titleBar.addView(titleText, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        val closeBtn = Button(ctx).apply {
            text = "✕"; textSize = 12f; minWidth = 0; minHeight = 0
            setTextColor(colorSub)
            setBackgroundColor(colorPanel)
            setPadding(10, 4, 10, 4)
            setOnClickListener { stopSelf() }
        }
        titleBar.addView(closeBtn)
        container.addView(titleBar)

        // 向听数
        shantenLabel = TextView(ctx).apply {
            text = "向听: --"; textSize = 28f
            setTextColor(colorAccent); gravity = Gravity.CENTER
            setPadding(0, 8, 0, 4)
            background = roundedBg(colorPanel, 8f)
            setOnClickListener { openReviewForEdit() }
        }
        container.addView(shantenLabel, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = 6 })

        // 推荐切牌卡片
        val recCard = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedBg(colorPanel, 10f)
            setPadding(12, 10, 12, 12)
        }
        TextView(ctx).apply {
            text = "🏆 推荐切牌"; textSize = 10f; setTextColor(colorSub)
            setPadding(0, 0, 0, 4)
        }.also { recCard.addView(it) }
        recommendLabel = TextView(ctx).apply {
            text = "等待分析..."; textSize = 20f; setTextColor(colorAccent)
            gravity = Gravity.CENTER
            setOnClickListener { openReviewForEdit() }
        }
        recCard.addView(recommendLabel)
        container.addView(recCard, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = 6 })

        // 备选列表
        altContainer = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(4, 4, 4, 0)
        }
        container.addView(altContainer)

        // 日志框 (加大, 滚动)
        val logScroll = ScrollView(ctx).apply {
            background = roundedBg(0x40000000.toInt(), 8f)
        }
        val logInner = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(8, 6, 8, 6)
        }
        statusLabel = TextView(ctx).apply {
            text = "就绪"; textSize = 10f
            setTextColor(0xFFA0D0A0.toInt())
            typeface = android.graphics.Typeface.MONOSPACE
            setSingleLine(false); maxLines = 30
        }
        logInner.addView(statusLabel)
        logScroll.addView(logInner, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ))
        container.addView(logScroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, (80 * resources.displayMetrics.density).toInt()
        ).apply { topMargin = 6 })

        // 控制按钮
        val btnRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 4, 0, 0)
        }

        captureBtn = Button(ctx).apply {
            text = "📷 截取"; textSize = 11f; minWidth = 0; minHeight = 0
            setBackgroundColor(0xFF2D6A2D.toInt()); setTextColor(colorText)
            setPadding(0, 8, 0, 8)
            setOnClickListener { onCapture() }
        }
        btnRow.addView(captureBtn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = 4 })

        manualBtn = Button(ctx).apply {
            text = "✏ 手动"; textSize = 11f; minWidth = 0; minHeight = 0
            setBackgroundColor(colorPanel); setTextColor(colorSub)
            setPadding(0, 8, 0, 8)
            setOnClickListener { onManualInput() }
        }
        btnRow.addView(manualBtn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        container.addView(btnRow)

        overlayView = container
        this.titleBar = titleBar

        // Window参数
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        layoutParams = WindowManager.LayoutParams(
            (260 * resources.displayMetrics.density).toInt(),
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
        val (sw, sh) = getScreenSize()
        FLog.i("OverlaySvc", "overlay created: ${sw}x${sh} dpi=$screenDpi")
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
                    log("✓ 雀魂: $pkg")
                    return
                }
            } catch (_: Exception) {}
        }
        FLog.w("OverlaySvc", "✗ 未找到雀魂! queries声明可能未生效, 请检查AndroidManifest")
        log("✗ 未找到雀魂! 需queries声明")
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
            updateAdviceShantenOnly(hand)
            return
        }
        if (hand.size != 14) {
            log("● 需要13-14张牌 (当前${hand.size}张)")
            return
        }

        currentHand = hand
        val result = Shanten.calculate(hand)
        val advice = Efficiency.analyze(hand)

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

        // 推荐
        if (advice.isNotEmpty()) {
            val best = advice[0]
            recommendLabel.text = "切 ${best.tileName}  (进张${best.ukeire}枚)"

            // 备选
            altContainer.removeAllViews()
            for (i in 1 until minOf(5, advice.size)) {
                val a = advice[i]
                val tv = TextView(this).apply {
                    text = "  ${i + 1}. 切${a.tileName}  [${a.ukeire}枚]"
                    textSize = 11f
                    setTextColor(0xFF6A9A6A.toInt())
                }
                altContainer.addView(tv)
            }
        }

        val handStr = Tiles.toDisplayString(hand)
        log("● 手牌: $handStr")
    }

    private fun scheduleDelayedCapture(reason: String) {
        if (captureScheduled) {
            FLog.i("OverlaySvc", "scheduleDelayedCapture 跳过(已调度): $reason")
            return
        }
        captureScheduled = true
        FLog.i("OverlaySvc", "scheduleDelayedCapture: $reason, 4s后截图")
        log("● $reason, 4s后自动截图")
        mainHandler.postDelayed({
            captureScheduled = false
            onCapture()
        }, 4000)
    }

    private fun getScreenSize(): Pair<Int, Int> {
        return if (Build.VERSION.SDK_INT >= 30) {
            val bounds = windowManager.currentWindowMetrics.bounds
            Pair(bounds.width(), bounds.height())
        } else {
            val point = android.graphics.Point()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealSize(point)
            Pair(point.x, point.y)
        }
    }

    private fun onCapture() {
        if (isCapturing) {
            log("⏳ 正在截图中,跳过")
            return
        }

        FLog.i("OverlaySvc", "onCapture EXEC: code=$captureResultCode data=${captureResultData != null} projection=${projection != null}")
        log("onCapture: code=${captureResultCode} data=${captureResultData != null}")

        if (captureResultCode == CODE_UNSET || captureResultData == null) {
            log("● 无授权 → 启动CaptureActivity")
            pendingCapture = true
            authPollCount = 0
            try {
                val intent = Intent(this, com.mahjong.assistant.capture.CaptureActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(intent)
                log("● 已启动授权Activity, 开始轮询...")
                pollForAuth()
            } catch (e: Exception) {
                log("✖ 授权启动失败: ${e.message?.take(40)}")
                pendingCapture = false
            }
            return
        }

        isCapturing = true
        val (sw, sh) = getScreenSize()
        FLog.i("OverlaySvc", "开始截图 ${sw}x${sh}")
        log("● 开始截图 ${sw}x${sh}")

        try {
            overlayView.visibility = View.INVISIBLE

            if (projection == null) {
                FLog.i("OverlaySvc", "创建MediaProjection...")
                log("● 创建MediaProjection...")
                val pm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                try {
                    projection = pm.getMediaProjection(captureResultCode, captureResultData!!)
                    FLog.i("OverlaySvc", "MediaProjection创建完成: ${projection != null}")
                    log("● MediaProjection OK")
                } catch (e: Exception) {
                    FLog.e("OverlaySvc", "getMediaProjection崩溃", e)
                    log("✖ MediaProjection创建失败: ${e.message?.take(40)}")
                    isCapturing = false
                    overlayView.visibility = View.VISIBLE
                    return
                }
            }

            projection!!.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    projection = null
                    log("✖ MediaProjection.onStop")
                }
            }, mainHandler)

            val reader = ImageReader.newInstance(sw, sh, android.graphics.PixelFormat.RGBA_8888, 1)
            log("● ImageReader ready")

            // 用可变容器持有vd (lambda定义在vd之前, 需延迟绑定)
            var vdRef: VirtualDisplay? = null

            reader.setOnImageAvailableListener({ r ->
                if (!isCapturing) return@setOnImageAvailableListener
                isCapturing = false
                FLog.i("OverlaySvc", "image available")
                log("● image available")

                try {
                    val img: Image? = r.acquireLatestImage()
                    if (img != null) {
                        val plane = img.planes[0]
                        val buffer = plane.buffer
                        val pixelStride = plane.pixelStride
                        val rowStride = plane.rowStride
                        val rowPadding = rowStride - r.width * pixelStride
                        FLog.i("OverlaySvc", "plane: w=${r.width} h=${r.height} pStride=$pixelStride rowStride=$rowStride padding=$rowPadding")

                        val bitmap = run {
                            // 统一逐行复制 (RGBA buffer → ARGB bitmap)
                            val bmp = Bitmap.createBitmap(r.width, r.height, Bitmap.Config.ARGB_8888)
                            val pixels = IntArray(r.width)
                            val byteBuf = ByteArray(rowStride)
                            buffer.rewind()
                            for (y in 0 until r.height) {
                                buffer.get(byteBuf, 0, minOf(rowStride, buffer.remaining()))
                                for (x in 0 until r.width) {
                                    val off = x * pixelStride
                                    pixels[x] = ((byteBuf[off+3].toInt() and 0xFF) shl 24) or
                                        ((byteBuf[off].toInt() and 0xFF) shl 16) or
                                        ((byteBuf[off+1].toInt() and 0xFF) shl 8) or
                                        (byteBuf[off+2].toInt() and 0xFF)
                                }
                                bmp.setPixels(pixels, 0, r.width, 0, y, r.width, 1)
                            }
                            bmp
                        }
                        img.close()
                        FLog.i("OverlaySvc", "bitmap ${bitmap.width}x${bitmap.height}")
                        log("● bitmap ${bitmap.width}x${bitmap.height}")
                        processScreenshot(bitmap)
                        cleanup(r, vdRef)  // 识别完成后恢复悬浮窗
                    } else {
                        FLog.w("OverlaySvc", "截屏无数据")
                        log("✖ 截屏无数据")
                        cleanup(r, null)
                    }
                } catch (e: Exception) {
                    FLog.e("OverlaySvc", "bitmap异常", e)
                    log("✖ bitmap异常: ${e.message?.take(40)}")
                    cleanup(r, null)
                }
            }, mainHandler)

            val vd = projection!!.createVirtualDisplay(
                "mahjong-ss", sw, sh, screenDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.surface, null, null
            )
            vdRef = vd
            log("● VirtualDisplay created")

            mainHandler.postDelayed({
                if (isCapturing) {
                    isCapturing = false
                    log("✖ 截屏超时3s")
                    cleanup(reader, vd)
                }
            }, 3000)

        } catch (e: SecurityException) {
            isCapturing = false
            captureScheduled = false
            overlayView.visibility = View.VISIBLE
            FLog.e("OverlaySvc", "SecurityException", e)
            log("✖ SecurityException: 权限失效")
        } catch (e: Exception) {
            isCapturing = false
            captureScheduled = false
            overlayView.visibility = View.VISIBLE
            FLog.e("OverlaySvc", "截屏异常", e)
            log("✖ 截屏异常: ${e.message?.take(40)}")
        }
    }

    private fun processScreenshot(bitmap: Bitmap) {
        FLog.i("OverlaySvc", "processScreenshot start")
        log("● 开始识别...")
        val (results, _) = TileMatcher.recognize(bitmap)
        FLog.i("OverlaySvc", "recognize done: ${results.size} tiles")

        val ssPath = saveScreenshotFile(bitmap)
        bitmap.recycle()

        log(TileMatcher.lastLog)

        val tileIds = results.map { it.tileId }.toIntArray()
        val confidences = results.map { it.confidence }.toDoubleArray()
        val handStr = if (tileIds.isNotEmpty()) Tiles.toDisplayString(tileIds) else "—"

        // 存储结果供点击编辑
        lastTileIds = tileIds
        lastConfidences = confidences
        lastScreenshotPath = ssPath

        if (results.size >= 14) {
            val uncertain = results.count { it.needsCheck }
            log("● 识别${results.size}张: $handStr" +
                if (uncertain > 0) " | ⚠${uncertain}张待确认" else " ✓")
            updateAdvice(tileIds)
        } else if (results.size == 13) {
            log("● 13张: $handStr | 等待摸牌...")
            updateAdviceShantenOnly(tileIds)
        } else if (results.isNotEmpty()) {
            log("● 仅${results.size}张: $handStr → 点击可手动修改")
        } else {
            log("✖ 未检测到牌")
        }
        // 不再自动跳转ReviewActivity; 点击建议区可手动编辑
    }

    /** 13张手牌: 向听数 + 有效进张 */
    private fun updateAdviceShantenOnly(hand13: IntArray) {
        if (hand13.size != 13) return
        val (shanten, ukeireTiles) = Efficiency.analyze13(hand13)
        val totalUkeire = ukeireTiles.sumOf { it.second }

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
            recommendLabel.text = "进张: ${ukeireTiles.size}种${totalUkeire}枚"
            // 列出进张牌种
            altContainer.removeAllViews()
            val names = ukeireTiles.take(10).joinToString(" ") {
                "${Tiles.name(it.first)}×${it.second}"
            }
            val tv = TextView(this).apply {
                text = names
                textSize = 11f
                setTextColor(0xFF6A9A6A.toInt())
                setPadding(4, 2, 4, 0)
            }
            altContainer.addView(tv)
        } else {
            recommendLabel.text = "无有效进张"
            altContainer.removeAllViews()
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

    /** 点击建议区 → 打开ReviewActivity手动编辑 */
    private fun openReviewForEdit() {
        if (lastTileIds.isEmpty()) {
            log("● 请先截取识别")
            return
        }
        launchReview(lastTileIds, lastConfidences, lastScreenshotPath)
    }

    private fun saveScreenshotFile(bitmap: Bitmap): String? {
        return try {
            val dir = File(cacheDir, "screenshots")
            if (!dir.exists()) dir.mkdirs()
            val file = File(dir, "capture_${System.currentTimeMillis()}.jpg")
            val fos = java.io.FileOutputStream(file)
            bitmap.compress(Bitmap.CompressFormat.JPEG, 70, fos)
            fos.close()
            file.absolutePath
        } catch (_: Exception) { null }
    }

    private fun cleanup(reader: ImageReader?, vd: VirtualDisplay?) {
        try { vd?.release() } catch (_: Exception) {}
        try { reader?.close() } catch (_: Exception) {}
        overlayView.visibility = View.VISIBLE
    }

    private fun runOnUiThread(action: () -> Unit) {
        mainHandler.post(action)
    }

    private fun onManualInput() {
        val intent = Intent(this, com.mahjong.assistant.ManualInputActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(intent)
    }

    /** 轮询等待CaptureActivity传回授权 (fallback) */
    private fun pollForAuth() {
        authPollCount++
        if (captureResultCode != CODE_UNSET && captureResultData != null) {
            pendingCapture = false
            log("● pollForAuth#${authPollCount}: 授权已就绪, 2.5s后截图")
            scheduleDelayedCapture("pollForAuth#${authPollCount}")
            return
        }
        if (authPollCount > 120) {
            log("● 等待授权中... (已${authPollCount}轮, 将在授权后自动截图)")
            return
        }
        mainHandler.postDelayed({ pollForAuth() }, 500)
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
                log("INIT_CAPTURE: code=$captureResultCode data=${captureResultData != null} pending=$pendingCapture poll=$authPollCount")

                // 只要有有效凭证, 直接触发延迟截图 (不依赖 pendingCapture, 因为Service可能被重建过)
                if (captureResultCode != CODE_UNSET && captureResultData != null) {
                    pendingCapture = false
                    authPollCount = 0
                    log("● 授权有效, 2.5s后自动截图")
                    scheduleDelayedCapture("INIT_CAPTURE授权")
                }
                return START_STICKY
            }
            ACTION_UPDATE -> {
                val handArray = intent.getIntArrayExtra(EXTRA_HAND)
                if (handArray != null && handArray.size == 14) {
                    updateAdvice(handArray)
                } else {
                    val msg = intent.getStringExtra("status_msg")
                    if (msg != null) {
                        log(msg)
                    }
                }
            }
            ACTION_UPDATE_STATUS -> {
                val msg = intent.getStringExtra("status_msg")
                if (msg != null) {
                    log(msg)
                }
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        FLog.i("OverlaySvc", "onDestroy")
        if (::overlayView.isInitialized && overlayView.isAttachedToWindow) {
            windowManager.removeView(overlayView)
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
        FLog.shutdown()
    }
}
