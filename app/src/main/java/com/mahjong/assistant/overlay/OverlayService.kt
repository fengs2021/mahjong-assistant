package com.mahjong.assistant.overlay

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.*
import android.widget.*
import androidx.core.app.NotificationCompat
import com.mahjong.assistant.R
import com.mahjong.assistant.engine.Efficiency
import com.mahjong.assistant.engine.Shanten
import com.mahjong.assistant.engine.Tiles

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
    private lateinit var autoToggle: Switch

    private var currentHand = IntArray(0)
    private var isAutoMode = false

    companion object {
        const val CHANNEL_ID = "mahjong_overlay"
        const val NOTIFICATION_ID = 1
        const val ACTION_STOP = "com.mahjong.assistant.STOP"
        const val ACTION_UPDATE = "com.mahjong.assistant.UPDATE"
        const val ACTION_UPDATE_STATUS = "com.mahjong.assistant.UPDATE_STATUS"
        const val EXTRA_HAND = "hand_tiles"
    }

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        createOverlay()
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

        // 主容器
        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xE61A3A1A.toInt()) // 深绿半透明
            setPadding(4, 4, 4, 4)
        }

        // 标题栏
        titleBar = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(0xFF2D5A2D.toInt())
            setPadding(10, 6, 10, 6)
        }

        val titleText = TextView(ctx).apply {
            text = "🀄 雀魂助手"
            textSize = 14f
            setTextColor(0xFFA0F0A0.toInt())
        }
        titleBar.addView(titleText, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        val closeBtn = TextView(ctx).apply {
            text = "✕"
            textSize = 16f
            setTextColor(0xFFA0F0A0.toInt())
            setPadding(12, 0, 0, 0)
            setOnClickListener { stopSelf() }
        }
        titleBar.addView(closeBtn)
        container.addView(titleBar)

        // 向听数显示
        shantenLabel = TextView(ctx).apply {
            text = "向听: --"
            textSize = 26f
            setTextColor(0xFF00CC66.toInt())
            gravity = Gravity.CENTER
            setPadding(0, 8, 0, 8)
            setBackgroundColor(0xFF2D5A2D.toInt())
        }
        container.addView(shantenLabel)

        // 推荐切牌
        val recFrame = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF2D5A2D.toInt())
            setPadding(10, 6, 10, 8)
        }

        TextView(ctx).apply {
            text = "▼ 推荐切牌"
            textSize = 11f
            setTextColor(0xFF6A9A6A.toInt())
        }.also { recFrame.addView(it) }

        recommendLabel = TextView(ctx).apply {
            text = "等待分析..."
            textSize = 17f
            setTextColor(0xFFA0F0A0.toInt())
        }
        recFrame.addView(recommendLabel)
        container.addView(recFrame, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = 4 })

        // 备选列表
        altContainer = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(8, 4, 8, 4)
        }
        container.addView(altContainer)

        // 状态栏
        statusLabel = TextView(ctx).apply {
            text = "● 就绪"
            textSize = 10f
            setTextColor(0xFF00CC66.toInt())
            setBackgroundColor(0xFF2D5A2D.toInt())
            setPadding(8, 4, 8, 4)
        }
        container.addView(statusLabel, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = 4 })

        // 控制按钮
        val btnRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 4, 0, 0)
        }

        captureBtn = Button(ctx).apply {
            text = "📸 截取"
            textSize = 11f
            setBackgroundColor(0xFF00CC66.toInt())
            setTextColor(0xFF0A2A0A.toInt())
            setOnClickListener { onCapture() }
        }
        btnRow.addView(captureBtn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = 4 })

        manualBtn = Button(ctx).apply {
            text = "✏ 手动"
            textSize = 11f
            setBackgroundColor(0xFF3A6A3A.toInt())
            setTextColor(0xFFA0F0A0.toInt())
            setOnClickListener { onManualInput() }
        }
        btnRow.addView(manualBtn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        autoToggle = Switch(ctx).apply {
            text = "自动"
            textSize = 10f
            setTextColor(0xFFA0F0A0.toInt())
            setOnCheckedChangeListener { _, checked ->
                isAutoMode = checked
                statusLabel.text = if (checked) "● 自动模式" else "● 手动模式"
            }
        }
        btnRow.addView(autoToggle)
        container.addView(btnRow)

        overlayView = container

        // Window参数
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = 10
            y = 100
        }

        // 拖动处理
        titleBar.setOnTouchListener(DragTouchListener())

        windowManager.addView(overlayView, layoutParams)
    }

    // ─── 拖动 ───

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
        if (hand.size != 14) {
            statusLabel.text = "● 需要14张牌 (当前${hand.size}张)"
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
        statusLabel.text = "● 手牌: $handStr"
    }

    // ─── 回调 ───

    private fun onCapture() {
        statusLabel.text = "● 截取中..."
        // 启动透明CaptureActivity处理MediaProjection
        val intent = Intent(this, com.mahjong.assistant.capture.CaptureActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(intent)
    }

    private fun onManualInput() {
        val intent = Intent(this, com.mahjong.assistant.ManualInputActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(intent)
    }

    // ─── Service 生命周期 ───

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_UPDATE -> {
                val handArray = intent.getIntArrayExtra(EXTRA_HAND)
                if (handArray != null && handArray.size == 14) {
                    updateAdvice(handArray)
                } else {
                    val msg = intent.getStringExtra("status_msg")
                    if (msg != null) {
                        statusLabel.text = msg
                    }
                }
            }
            ACTION_UPDATE_STATUS -> {
                val msg = intent.getStringExtra("status_msg")
                if (msg != null) {
                    statusLabel.text = msg
                }
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        if (::overlayView.isInitialized && overlayView.isAttachedToWindow) {
            windowManager.removeView(overlayView)
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
}
