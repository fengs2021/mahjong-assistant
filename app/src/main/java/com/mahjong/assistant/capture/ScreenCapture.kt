package com.mahjong.assistant.capture

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.mahjong.assistant.engine.Tiles
import com.mahjong.assistant.util.FLog
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import java.io.File
import java.io.FileOutputStream

/**
 * 牌面模板匹配识别器 v5.0
 *
 * 识别策略:
 *   1. 精准定位 (主): 固定坐标裁剪每张牌, 直接模板匹配 — 快速精准
 *   2. 白板检测: 方差<100 直判 — 解决白板特征少误判
 *   3. 全图扫描 (兜底): 跨设备无坐标时使用
 *
 * 模板: 98×143 设备专属 (PPG-AN00 2800×1264 dpi=560)
 */
object TileMatcher {

    private const val TAG = "TileMatcher"
    private const val ASSET_TILES_DIR = "tiles"
    private const val TEMPLATE_DIR = "tile_templates"

    data class MatchResult(
        val tileId: Int,
        val confidence: Double,
        val needsCheck: Boolean
    )

    data class RectRegion(val x: Int, val y: Int, val w: Int, val h: Int)

    /** 是否使用精准定位模式 (当前设备坐标匹配时启用) */
    @Volatile var usePositionMode = true

    // ─── 精准定位坐标 (PPG-AN00 横屏 2800×1264) ───
    private data class TileSlot(
        val slotLeft: Int,   // 牌位左边缘(原始截图x)
        val slotTop: Int,    // 牌位上边缘(y)
        val faceLeft: Int,   // 牌面左边缘(去掉3D边框)
        val faceTop: Int,    // 牌面上边缘
        val faceW: Int,      // 牌面宽度
        val faceH: Int       // 牌面高度
    )

    // 主手牌13张 (间距111px)
    private val mainHandSlots: List<TileSlot> = (0..12).map { i ->
        val slotLeft = 300 + 236 + i * 111
        val faceLeft = slotLeft + 5
        TileSlot(
            slotLeft = slotLeft,
            slotTop = 1010,
            faceLeft = faceLeft,
            faceTop = 1106,
            faceW = 98,
            faceH = 143
        )
    }

    // 摸牌 (第14张, 距主手牌右边缘38px)
    private val drawnSlot = TileSlot(
        slotLeft = 2014,
        slotTop = 1010,
        faceLeft = 2019,
        faceTop = 1106,
        faceW = 98,
        faceH = 143
    )

    // 模板缓存
    private val templates = mutableMapOf<Int, Mat>()
    // 副露专用模板 (从截图中采集, 宽高比~1:1 vs 手牌98:143)
    // Pair(竖放模板, 横放模板) — 横放可为null
    private val meldTemplates = mutableMapOf<Int, Pair<Mat?, Mat?>>()
    @Volatile private var templatesLoaded = false
    private var initDiagnostic = "未初始化"
    private var opencvReady = false

    @Volatile var lastLog: String = ""
    @Volatile var lastEmptySlots: List<Int> = emptyList()  // 空位slot索引(提起的牌)
    @Volatile var lastHandY: Int = 1106  // 上次检测到手牌y(自适应)
    @Volatile var lastHandH: Int = 143   // 上次检测到手牌h

    // ─── tileId映射 (兼容简繁体文件名) ───
    private val nameToId = mapOf(
        "一万" to 0,  "二万" to 1,  "三万" to 2,  "四万" to 3,  "五万" to 4,
        "六万" to 5,  "七万" to 6,  "八万" to 7,  "九万" to 8,
        "一筒" to 9,  "二筒" to 10, "三筒" to 11, "四筒" to 12, "五筒" to 13,
        "六筒" to 14, "七筒" to 15, "八筒" to 16, "九筒" to 17,
        "一索" to 18, "二索" to 19, "三索" to 20, "四索" to 21, "五索" to 22,
        "六索" to 23, "七索" to 24, "八索" to 25, "九索" to 26,
        "东" to 27, "東" to 27, "南" to 28, "西" to 29, "北" to 30,
        "白" to 31, "发" to 32, "発" to 32, "中" to 33
    )

    // ─── 阈值 (新模板匹配分0.995+, 大幅提高门槛) ───
    private val thresholds = IntArray(34) { 85 } // 默认0.85
    init {
        // 白板用方差检测, 不依赖模板匹配
        thresholds[31] = 0  // 白 — 方差直判, 不用阈值
    }
    private fun getThreshold(tileId: Int): Double = thresholds[tileId].toDouble() / 100.0

    // ─── 初始化 ───
    fun init(context: Context): Boolean {
        FLog.i("TileMatcher", "init start")
        return try {
            opencvReady = try {
                OpenCVLoader.initLocal().also {
                    android.util.Log.i(TAG, "OpenCV initLocal: $it")
                }
            } catch (e: UnsatisfiedLinkError) {
                android.util.Log.e(TAG, "OpenCV UnsatisfiedLinkError: ${e.message}")
                false
            }

            if (!opencvReady) {
                initDiagnostic = "OpenCV加载失败"
                return false
            }

            templatesLoaded = loadPresetTemplates(context)
            if (!templatesLoaded) {
                templatesLoaded = loadCalibratedTemplates(context)
            }
            val meldCount = loadMeldTemplates(context)

            initDiagnostic = if (templatesLoaded) {
                "就绪: ${templates.size}/34 模板 + ${meldCount}副露 OpenCV=${opencvReady}"
            } else {
                "模板为空"
            }
            FLog.i("TileMatcher", initDiagnostic)
            android.util.Log.i(TAG, initDiagnostic)
            templatesLoaded
        } catch (e: Exception) {
            initDiagnostic = "初始化崩溃: ${e.message}"
            FLog.e("TileMatcher", initDiagnostic, e)
            false
        }
    }

    fun getDiagnostic(): String = "$initDiagnostic | 模板数=${templates.size} | OpenCV=$opencvReady"
    fun hasTemplates(): Boolean = templatesLoaded
    fun templateCount(): Int = templates.size
    fun isOpencvReady(): Boolean = opencvReady

    /** 公开接口: 识别单张牌面Bitmap → (tileId, confidence) 或 null */
    fun identifySingleTile(tileBitmap: Bitmap): Pair<Int, Double>? {
        if (!templatesLoaded || !opencvReady) return null
        return try {
            val bmp = tileBitmap.copy(Bitmap.Config.ARGB_8888, false)
            val mat = Mat()
            Utils.bitmapToMat(bmp, mat)
            Imgproc.cvtColor(mat, mat, Imgproc.COLOR_RGBA2GRAY)
            bmp.recycle()
            matchSingleTile(mat)
        } catch (e: Exception) {
            FLog.w(TAG, "identifySingleTile error: ${e.message}")
            null
        }
    }

    // ─── 模板加载 ───
    private fun loadPresetTemplates(context: Context): Boolean {
        return try {
            val assets = context.assets
            val tileFiles = assets.list(ASSET_TILES_DIR) ?: return false

            var loaded = 0
            for (filename in tileFiles) {
                val nameWithoutExt = filename.removeSuffix(".png")
                val tileId = nameToId[nameWithoutExt] ?: continue

                try {
                    val inputStream = assets.open("$ASSET_TILES_DIR/$filename")
                    val bitmap = BitmapFactory.decodeStream(inputStream)
                    inputStream.close()

                    if (bitmap != null) {
                        val mat = Mat()
                        Utils.bitmapToMat(bitmap, mat)
                        Imgproc.cvtColor(mat, mat, Imgproc.COLOR_RGBA2GRAY)
                        templates[tileId] = mat
                        bitmap.recycle()
                        loaded++
                    }
                } catch (e: Exception) {
                    android.util.Log.w(TAG, "加载模板失败: $filename")
                }
            }
            android.util.Log.i(TAG, "从assets加载 $loaded/34 模板 (98×143)")
            loaded > 0
        } catch (e: Exception) {
            android.util.Log.e(TAG, "assets加载异常: ${e.message}", e)
            false
        }
    }

    private fun loadCalibratedTemplates(context: Context): Boolean {
        val dir = File(context.filesDir, TEMPLATE_DIR)
        if (!dir.exists()) return false

        var loaded = 0
        for (tileId in 0..33) {
            val file = File(dir, "tile_${tileId}.png")
            if (!file.exists()) continue
            try {
                val bitmap = BitmapFactory.decodeFile(file.absolutePath)
                if (bitmap != null) {
                    val mat = Mat()
                    Utils.bitmapToMat(bitmap, mat)
                    Imgproc.cvtColor(mat, mat, Imgproc.COLOR_RGBA2GRAY)
                    templates[tileId]?.release()
                    templates[tileId] = mat
                    bitmap.recycle()
                    loaded++
                }
            } catch (e: Exception) {
                android.util.Log.w(TAG, "加载校准模板失败 tile_${tileId}")
            }
        }
        android.util.Log.i(TAG, "从内部存储加载 $loaded 校准模板")
        return templates.isNotEmpty()
    }

    // ─── 副露模板加载 (从截图中采集, 宽高比~1:1) ───
    private const val MELD_TILES_DIR = "meld_tiles"

    private fun loadMeldTemplates(context: Context): Int {
        return try {
            val assets = context.assets
            val files = assets.list(MELD_TILES_DIR) ?: return 0
            var loaded = 0
            for (filename in files) {
                val nameWithoutExt = filename.removeSuffix(".png")
                // 解析: "八索_竖" → name="八索" direction=竖
                val isVertical = nameWithoutExt.endsWith("_竖") || nameWithoutExt.endsWith("_竖2")
                val isHorizontal = nameWithoutExt.endsWith("_横") || nameWithoutExt.endsWith("_横2")
                if (!isVertical && !isHorizontal) continue
                val tileName = if (isVertical) {
                    nameWithoutExt.removeSuffix("_竖").removeSuffix("_竖2")
                } else {
                    nameWithoutExt.removeSuffix("_横").removeSuffix("_横2")
                }
                val tileId = nameToId[tileName] ?: continue
                try {
                    val inputStream = assets.open("$MELD_TILES_DIR/$filename")
                    val bitmap = BitmapFactory.decodeStream(inputStream)
                    inputStream.close()
                    if (bitmap != null) {
                        val mat = Mat()
                        Utils.bitmapToMat(bitmap, mat)
                        Imgproc.cvtColor(mat, mat, Imgproc.COLOR_RGBA2GRAY)
                        val existing = meldTemplates[tileId]
                        if (isVertical) {
                            meldTemplates[tileId] = Pair(mat, existing?.second)
                        } else {
                            meldTemplates[tileId] = Pair(existing?.first, mat)
                        }
                        bitmap.recycle()
                        loaded++
                    }
                } catch (e: Exception) {
                    android.util.Log.w(TAG, "加载副露模板失败: $filename")
                }
            }
            android.util.Log.i(TAG, "副露模板: $loaded 张 (${meldTemplates.size} 种)")
            loaded
        } catch (e: Exception) {
            android.util.Log.w(TAG, "副露模板加载异常: ${e.message}")
            0
        }
    }

    // ─── 手动校准 ───
    fun saveTemplate(
        context: Context, screenshot: Bitmap,
        tileX: Int, tileY: Int, tileW: Int, tileH: Int, tileId: Int
    ): Boolean {
        val dir = File(context.filesDir, TEMPLATE_DIR)
        if (!dir.exists()) dir.mkdirs()

        val tile = Bitmap.createBitmap(screenshot, tileX, tileY, tileW, tileH)
        val file = File(dir, "tile_${tileId}.png")
        FileOutputStream(file).use { out ->
            tile.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        tile.recycle()

        templates[tileId]?.release()
        val mat = Mat()
        val reloaded = BitmapFactory.decodeFile(file.absolutePath)
        Utils.bitmapToMat(reloaded, mat)
        Imgproc.cvtColor(mat, mat, Imgproc.COLOR_RGBA2GRAY)
        templates[tileId] = mat
        reloaded.recycle()
        templatesLoaded = true
        return true
    }

    fun calibrateFromHand(context: Context, screenshot: Bitmap, handTiles: IntArray): Int {
        val regions = findTileRegions(screenshot)
        if (regions.size < handTiles.size) return 0
        var saved = 0
        val sortedRegions = regions.sortedBy { it.x }
        for (i in handTiles.indices) {
            if (i >= sortedRegions.size) break
            val r = sortedRegions[i]
            if (saveTemplate(context, screenshot, r.x, r.y, r.w, r.h, handTiles[i])) saved++
        }
        return saved
    }

    // ═══════════════════════════════════════════
    // 识别主入口
    // ═══════════════════════════════════════════

    fun recognize(screenshot: Bitmap): Pair<List<MatchResult>, Double> {
        return try {
            recognizeImpl(screenshot)
        } catch (e: Exception) {
            android.util.Log.e(TAG, "识别崩溃: ${e.message}", e)
            lastLog = "识别异常: ${e.message?.take(80)}"
            Pair(emptyList(), -1.0)
        } catch (e: Error) {
            android.util.Log.e(TAG, "识别JNI崩溃: ${e.message}", e)
            lastLog = "识别JNI异常: ${e.message?.take(80)}"
            Pair(emptyList(), -1.0)
        }
    }

    private fun recognizeImpl(screenshot: Bitmap): Pair<List<MatchResult>, Double> {
        FLog.i("TileMatcher", "recognizeImpl start ${screenshot.width}x${screenshot.height}")

        if (!templatesLoaded) {
            lastLog = "模板未加载"
            return Pair(emptyList(), -1.0)
        }

        // 策略: 精准定位 → 副露补充 → 全图扫描兜底
        val results = if (usePositionMode) {
            val posResults = recognizeByPosition(screenshot)
            val highConf = posResults.count { !it.needsCheck }
            if (highConf >= 1) {
                // 手牌不足13张 → 扫描右下副露区域
                if (posResults.size < 13) {
                    val meldResults = scanMeldArea(screenshot, lastHandY, lastHandH)
                    FLog.i("TileMatcher", "副露扫描: ${meldResults.size}张 → 合并=${posResults.size + meldResults.size}")
                    posResults + meldResults
                } else {
                    posResults
                }
            } else {
                FLog.w("TileMatcher", "精准定位0高置信(${posResults.size}张低分), 回退全图")
                fullImageScan(screenshot)
            }
        } else {
            fullImageScan(screenshot)
        }

        val reliableCount = results.count { !it.needsCheck }
        val overallConfidence = if (results.isNotEmpty()) reliableCount.toDouble() / results.size else 0.0

        val logParts = mutableListOf<String>()
        logParts.add("截图: ${screenshot.width}×${screenshot.height}")
        logParts.add("结果: ${results.size}张 | 高置信${reliableCount}张 | 模式=${if(usePositionMode)"精准" else "扫描"}")
        val handStr = if (results.isNotEmpty()) {
            Tiles.toDisplayString(results.map { it.tileId }.toIntArray())
        } else "—"
        logParts.add("手牌: $handStr")
        if (results.size in 1..12) {
            logParts.add("⚠ 检测不足13张，可在审核界面手动补牌")
        }

        lastLog = logParts.joinToString("\n")
        FLog.i("TileMatcher", "recognizeImpl done: ${results.size} results, reliable=$reliableCount")
        return Pair(results, overallConfidence)
    }

    // ═══════════════════════════════════════════
    // 精准定位识别 (v5.0 新增)
    // ═══════════════════════════════════════════

    private fun recognizeByPosition(screenshot: Bitmap): List<MatchResult> {
        FLog.i("TileMatcher", "recognizeByPosition start")
        val srcMat = Mat()
        Utils.bitmapToMat(screenshot, srcMat)  // RGBA
        val gray = Mat()
        Imgproc.cvtColor(srcMat, gray, Imgproc.COLOR_RGBA2GRAY)
        srcMat.release()

        // detectHandY返回纹理最丰富行(牌面中心), 减去半高得到faceTop
        val texCenterY = detectHandY(gray)
        val (detectedFaceY, detectedFaceH) = if (texCenterY < 0) {
            // 低方差 → 没有牌面纹理 → fallback默认坐标
            Pair(1106, 143)
        } else {
            val faceY = (texCenterY - 71).coerceIn(0, gray.rows() - 143)
            Pair(faceY, 143)
        }
        lastHandY = detectedFaceY; lastHandH = detectedFaceH

        val results = mutableListOf<MatchResult>()
        val emptySlots = mutableListOf<Int>()

        // 主手牌 13张 (自适应y)
        for ((i, slot) in mainHandSlots.withIndex()) {
            val faceRight = slot.faceLeft + slot.faceW
            val faceBottom = detectedFaceY + detectedFaceH
            if (faceRight > gray.cols() || faceBottom > gray.rows()) continue

            val tileMat = Mat(gray, Rect(slot.faceLeft, detectedFaceY, slot.faceW, detectedFaceH))
            // 亮度检测: 空位(桌面背景 mean~56, 有牌mean~119+)
            val meanCheck = MatOfDouble()
            Core.meanStdDev(tileMat, meanCheck, MatOfDouble())
            val hasTile = meanCheck.get(0, 0)[0] > 80.0
            meanCheck.release()
            if (!hasTile) {
                emptySlots.add(i)
                FLog.i("TileMatcher", "  slot[$i]: 空位(提起)")
                tileMat.release()
                continue
            }
            val (tileId, score) = matchSingleTileMultiDir(tileMat)
            tileMat.release()

            if (tileId >= 0) {
                results.add(MatchResult(tileId, score, score < 0.70))
                FLog.i("TileMatcher", "  slot[$i]: ${Tiles.name(tileId)} (${"%.3f".format(score)})")
            }
        }
        lastEmptySlots = emptySlots.toList()

        // 摸牌: 副露时手牌减少, 摸牌位置左移
        // 动态计算摸牌位置: 最后一张手牌右边 + 间距(43px)
        val handHighConf = results.count { !it.needsCheck }
        val drawnSlotDynamic = if (handHighConf in 1..12) {
            val lastSlotIdx = handHighConf - 1
            val lastSlot = mainHandSlots[lastSlotIdx]
            val drawnFaceLeft = lastSlot.faceLeft + lastSlot.faceW + 43  // 间距同13张时
            TileSlot(
                slotLeft = drawnFaceLeft - 5,
                slotTop = drawnSlot.slotTop,
                faceLeft = drawnFaceLeft,
                faceTop = detectedFaceY,
                faceW = drawnSlot.faceW,
                faceH = detectedFaceH
            )
        } else {
            drawnSlot  // 13张或无牌时用默认坐标
        }

        val dFaceRight = drawnSlotDynamic.faceLeft + drawnSlotDynamic.faceW
        val dFaceBottom = detectedFaceY + detectedFaceH
        if (dFaceRight <= gray.cols() && dFaceBottom <= gray.rows()) {
            // 副露时摸牌位置可能偏移, 扩大搜索范围
            var bestDrawnTile = -1; var bestDrawnScore = 0.0; var bestDrawnX = drawnSlotDynamic.faceLeft
            for (dx in -20..20 step 3) {
                val sx = (drawnSlotDynamic.faceLeft + dx).coerceIn(0, gray.cols() - drawnSlotDynamic.faceW)
                val tileMat = Mat(gray, Rect(sx, detectedFaceY, drawnSlotDynamic.faceW, detectedFaceH))
                val meanCheck = MatOfDouble()
                Core.meanStdDev(tileMat, meanCheck, MatOfDouble())
                val hasTile = meanCheck.get(0, 0)[0] > 80.0
                meanCheck.release()
                if (hasTile) {
                    val (tid, score) = matchSingleTileMultiDir(tileMat)
                    if (tid >= 0 && score > bestDrawnScore) {
                        bestDrawnScore = score; bestDrawnTile = tid; bestDrawnX = sx
                    }
                }
                tileMat.release()
            }
            if (bestDrawnTile >= 0) {
                results.add(MatchResult(bestDrawnTile, bestDrawnScore, bestDrawnScore < 0.70))
                FLog.i("TileMatcher", "  drawn: ${Tiles.name(bestDrawnTile)} (${"%.3f".format(bestDrawnScore)}) x=$bestDrawnX")
            } else {
                FLog.i("TileMatcher", "  drawn: 无牌(桌面背景)")
            }
        }

        gray.release()
        FLog.i("TileMatcher", "recognizeByPosition done: ${results.size} tiles")
        return results
    }

    /**
     * 匹配单张牌: 方差检测(白板) → 模板匹配
     * @return Pair(tileId, score), tileId=-1表示匹配失败
     */
    private fun matchSingleTile(tileGray: Mat): Pair<Int, Double> {
        // 1. 方差检测 — 白板 (var<100)
        if (isBlankTile(tileGray)) {
            return Pair(31, 1.0)  // 白, 满分
        }

        // 2. 模板匹配
        var bestId = -1
        var bestScore = 0.0

        for ((tileId, template) in templates) {
            if (tileId == 31) continue  // 白跳过模板匹配

            val resized = Mat()
            Imgproc.resize(template, resized, Size(tileGray.cols().toDouble(), tileGray.rows().toDouble()))
            val result = Mat()
            Imgproc.matchTemplate(tileGray, resized, result, Imgproc.TM_CCOEFF_NORMED)
            val score = result.get(0, 0)[0]
            result.release(); resized.release()

            if (score > bestScore) {
                bestScore = score; bestId = tileId
            }
        }

        // 阈值判定
        return if (bestId >= 0 && bestScore >= getThreshold(bestId)) {
            Pair(bestId, bestScore)
        } else {
            Pair(-1, bestScore)
        }
    }

    /**
     * 方向感知匹配 — 按宽高比自动选方向
     *   竖牌(w<h) → 尝试0°(正常竖) + 180°(上下颠倒)
     *   横牌(w>h) → 尝试90°CW + 270°CW(左右翻转)
     */
    private fun matchSingleTileMultiDir(tileGray: Mat): Pair<Int, Double> {
        if (isBlankTile(tileGray)) return Pair(31, 1.0)

        val cropW = tileGray.cols()
        val cropH = tileGray.rows()
        val isHorizontal = cropW > cropH

        // 选择匹配方向: 竖→上下, 横→左右
        val rotations = if (isHorizontal) {
            intArrayOf(Core.ROTATE_90_CLOCKWISE, Core.ROTATE_90_COUNTERCLOCKWISE)
        } else {
            intArrayOf(-1, Core.ROTATE_180)  // -1 = 不旋转(0°)
        }

        var bestId = -1
        var bestScore = 0.0

        for ((tileId, template) in templates) {
            if (tileId == 31) continue

            for (rot in rotations) {
                val src = if (rot == -1) template else {
                    val r = Mat()
                    Core.rotate(template, r, rot)
                    r
                }

                val resized = Mat()
                Imgproc.resize(src, resized, Size(cropW.toDouble(), cropH.toDouble()))
                val result = Mat()
                Imgproc.matchTemplate(tileGray, resized, result, Imgproc.TM_CCOEFF_NORMED)
                val score = result.get(0, 0)[0]
                result.release(); resized.release()
                if (rot != -1) src.release()

                if (score > bestScore) {
                    bestScore = score; bestId = tileId
                }
            }
        }

        return if (bestId >= 0 && bestScore >= getThreshold(bestId)) {
            Pair(bestId, bestScore)
        } else {
            Pair(-1, bestScore)
        }
    }

    /**
     * 自适应检测手牌行 y 坐标 (纹理中心行, 非牌面顶部)
     * 扫描 y=1000~1280 范围, 找灰度水平方差最大的行 — 牌面纹理丰富方差高
     * @return 纹理中心行y, 或-1表示未检测到牌面(方差<20则fallback默认坐标)
     */
    private fun detectHandY(gray: Mat): Int {
        val imgW = gray.cols()
        val scanH = 5  // 每条带高度
        val handX = 400  // 手牌区域起始x
        val handW = minOf(imgW - handX, 1800)

        var bestY = 1106  // 兜底
        var bestStd = 0.0

        for (y in 1000..1280 step 5) {
            if (y + scanH > gray.rows()) break
            val band = Mat(gray, Rect(handX, y, handW, scanH))
            val m = MatOfDouble(); val s = MatOfDouble()
            Core.meanStdDev(band, m, s)
            val std = s.get(0, 0)[0]
            band.release(); m.release(); s.release()
            if (std > bestStd) { bestStd = std; bestY = y }
        }
        // 方差<20 = 无牌面纹理(桌面/空白区域), 返回-1触发fallback
        if (bestStd < 20.0) {
            FLog.i("TileMatcher", "detectHandY: 无牌面纹理(std=${String.format("%.1f",bestStd)}), fallback默认坐标")
            return -1
        }
FLog.i("TileMatcher", "detectHandY: texCenter=$bestY (std=${String.format("%.1f", bestStd)})")
        return bestY
    }

    /**
     * 白板方差检测: 灰度方差<100 → 白板
     * 白板是纯色面, 方差远低于所有其他牌种 (>1500)
     */
    private fun isBlankTile(tileGray: Mat): Boolean {
        val mean = MatOfDouble(); val stddev = MatOfDouble()
        Core.meanStdDev(tileGray, mean, stddev)
        val avg = mean.get(0, 0)[0]          // 平均灰度
        val variance = stddev.get(0, 0)[0].let { it * it }
        mean.release(); stddev.release()
        // 白板: 低方差(纯色面) AND 高亮度(米白~200+, 不是深蓝桌面~30)
        return variance < 100.0 && avg > 150.0
    }

    // ═══════════════════════════════════════════
    // 全图扫描 (兜底方案)
    // ═══════════════════════════════════════════

    private fun fullImageScan(screenshot: Bitmap): List<MatchResult> {
        FLog.i("TileMatcher", "fullImageScan start")
        val srcMat = Mat()
        Utils.bitmapToMat(screenshot, srcMat)
        val gray = Mat()
        Imgproc.cvtColor(srcMat, gray, Imgproc.COLOR_RGBA2GRAY)

        val imgW = gray.cols(); val imgH = gray.rows()

        // 多区域策略: 副露时手牌位置上移, 从宽到窄逐区域尝试
        val roiConfigs = listOf(
            Pair((imgH * 0.65).toInt(), minOf((imgH * 0.32).toInt(), imgH - (imgH * 0.65).toInt())),
            Pair((imgH * 0.55).toInt(), minOf((imgH * 0.42).toInt(), imgH - (imgH * 0.55).toInt())),
            Pair((imgH * 0.78).toInt(), minOf((imgH * 0.20).toInt(), imgH - (imgH * 0.78).toInt()))
        )

        val clahe = Imgproc.createCLAHE(3.0, Size(8.0, 8.0))
        data class Hit(val tileId: Int, val x: Int, val score: Double)

        for ((idx, cfg) in roiConfigs.withIndex()) {
            val (handTop, handH) = cfg
            if (handH < 30) continue

            // 降采样: ROI高度>250px时缩小到200px, 避免主线程卡死(fullImageScan是同步的)
            val downsample = if (handH > 250) handH.toDouble() / 200.0 else 1.0
            val scanH = if (downsample > 1.0) 200 else handH
            val scanW = (imgW / downsample).toInt()

            val roiRaw = Mat(gray, Rect(0, handTop, imgW, handH))
            val roiSmall = Mat()
            if (downsample > 1.0) {
                Imgproc.resize(roiRaw, roiSmall, Size(scanW.toDouble(), scanH.toDouble()))
            } else {
                roiRaw.copyTo(roiSmall)
            }
            roiRaw.release()
            val roi = Mat()
            clahe.apply(roiSmall, roi)
            roiSmall.release()

            FLog.i("TileMatcher", "ROI#$idx: handTop=$handTop handH=$handH→scanH=$scanH downsample=" + String.format("%.1f", downsample))

            val hits = mutableListOf<Hit>()
            val allBestScores = mutableMapOf<Int, Double>()

            for ((tileId, template) in templates) {
                if (tileId == 31) continue
                val th = getThreshold(tileId)
                val tH = template.rows().toDouble()
                val tW = template.cols().toDouble()
                // 同步降采样模板: targetScale基于降采样后的尺寸
                val targetScale = minOf(scanH.toDouble(), 200.0) / tH
                val perTemplateScales = DoubleArray(13) { i -> targetScale * (0.70 + i * 0.05) }

                var bestScore = 0.0
                var bestX = 0
                val templateEq = Mat()
                clahe.apply(template, templateEq)

                for (scale in perTemplateScales) {
                    val sw = (tW * scale).toInt()
                    val sh = (tH * scale).toInt()
                    if (sw < 8 || sh < 8 || sw > roi.cols() || sh > roi.rows()) continue
                    val resized = Mat()
                    Imgproc.resize(templateEq, resized, Size(sw.toDouble(), sh.toDouble()))
                    val result = Mat()
                    Imgproc.matchTemplate(roi, resized, result, Imgproc.TM_CCOEFF_NORMED)
                    val mm = Core.minMaxLoc(result)
                    if (mm.maxVal > bestScore) {
                        bestScore = mm.maxVal; bestX = mm.maxLoc.x.toInt()
                    }
                    result.release(); resized.release()
                }
                templateEq.release()
                allBestScores[tileId] = bestScore
                if (bestScore >= th) hits.add(Hit(tileId, bestX, bestScore))
            }
            roi.release()

            val allScoresStr = (0..33).joinToString(" ") { tid ->
                val s = allBestScores[tid] ?: 0.0
                val mark = if (s >= getThreshold(tid)) "OK" else "--"
                Tiles.name(tid) + ":" + "%.2f".format(s) + mark
            }
            FLog.i("TileMatcher", "ROI#$idx ALL34: $allScoresStr")
            android.util.Log.i(TAG, "ROI#$idx ALL34: $allScoresStr")

            if (hits.isEmpty()) {
                FLog.w("TileMatcher", "ROI#$idx 无结果, 放弃(不继续更大ROI)")
                break  // 不继续更大ROI — 越大越慢, 卡死风险更高
            }

            val sorted = hits.sortedBy { it.x }
            val filtered = mutableListOf<Hit>()
            for (h in sorted) {
                val dupIdx = filtered.indexOfFirst { kotlin.math.abs(it.x - h.x) < 15 }
                if (dupIdx < 0) filtered.add(h)
                else if (h.score > filtered[dupIdx].score) filtered[dupIdx] = h
            }
            val count = IntArray(34)
            val final = mutableListOf<Hit>()
            for (h in filtered) {
                if (count[h.tileId] < 4) { final.add(h); count[h.tileId]++ }
            }

            gray.release(); srcMat.release()
            FLog.i("TileMatcher", "fullImageScan done: ${final.size} final (ROI#$idx)")
            return final.map { MatchResult(it.tileId, it.score, it.score < 0.55) }
        }

        gray.release(); srcMat.release()
        FLog.w("TileMatcher", "全图0结果(所有ROI)")
        return emptyList()
    }

    // ═══════════════════════════════════════════
    // 副露区域扫描 (玩家鸣牌后的亮出牌组)
    // 副露在手牌同高度右侧, 牌面约手牌等大
    // ═══════════════════════════════════════════

    private fun scanMeldArea(screenshot: Bitmap, handY: Int, handH: Int): List<MatchResult> {
        FLog.i("TileMatcher", "scanMeldArea start")
        val srcMat = Mat()
        Utils.bitmapToMat(screenshot, srcMat)
        val gray = Mat()
        Imgproc.cvtColor(srcMat, gray, Imgproc.COLOR_RGBA2GRAY)
        srcMat.release()

        val imgW = gray.cols(); val imgH = gray.rows()

        // 副露区域: 底部右侧, 以手牌y为基准向上扩50向下扩80 (覆盖杠牌横放上下分列)
        val meldY = maxOf(0, handY - 50)
        val meldH = minOf(handH + 130, imgH - meldY)
        val meldX = 1150           // 手牌右侧+副露条向左延伸
        val meldW = imgW - meldX

        if (meldW < 50 || meldH < 30) {
            gray.release()
            FLog.w("TileMatcher", "副露ROI过小: ${meldW}x$meldH")
            return emptyList()
        }

        val roiRaw = Mat(gray, Rect(meldX, meldY, meldW, meldH))
        val clahe = Imgproc.createCLAHE(3.0, Size(8.0, 8.0))
        val roi = Mat()
        clahe.apply(roiRaw, roi)
        roiRaw.release()

        FLog.i("TileMatcher", "副露ROI: x=$meldX y=$meldY w=$meldW h=$meldH (img=${imgW}x$imgH)")

        data class Hit(val tileId: Int, val x: Int, val y: Int, val w: Int, val h: Int, val score: Double)
        val hits = mutableListOf<Hit>()

        // 副露牌与手牌等大(~98×143), 缩放 0.85~1.15, 阈值降低容忍副露渲染差异
        val meldTargetH = 143.0
        val minScale = 0.85
        val maxScale = 1.15
        val scaleSteps = 7   // 0.85 + 6*0.05 = 1.15
        val meldThreshold = 0.65

        // 方向: 0°(竖放), 90°CW(横放左), 270°CW(横放右)
        val templateRots = intArrayOf(-1, Core.ROTATE_90_CLOCKWISE, Core.ROTATE_90_COUNTERCLOCKWISE)
        for ((tileId, template) in templates) {
            if (tileId == 31) continue
            val th = meldThreshold

            for (rot in templateRots) {
                val tSrc = if (rot == -1) template else {
                    val r = Mat(); Core.rotate(template, r, rot); r
                }
                val tH = tSrc.rows().toDouble()
                val tW = tSrc.cols().toDouble()
                val targetScale = meldTargetH / tH

                val templateEq = Mat()
                clahe.apply(tSrc, templateEq)
                if (rot != -1) tSrc.release()

                for (i in 0 until scaleSteps) {
                    val scale = targetScale * (minScale + i * (maxScale - minScale) / (scaleSteps - 1))
                    val sw = (tW * scale).toInt()
                    val sh = (tH * scale).toInt()
                    if (sw < 6 || sh < 6 || sw > roi.cols() || sh > roi.rows()) continue

                    val resized = Mat()
                    Imgproc.resize(templateEq, resized, Size(sw.toDouble(), sh.toDouble()))
                    val result = Mat()
                    Imgproc.matchTemplate(roi, resized, result, Imgproc.TM_CCOEFF_NORMED)
                    val mm = Core.minMaxLoc(result)
                    if (mm.maxVal >= th) {
                        hits.add(Hit(tileId, meldX + mm.maxLoc.x.toInt(), meldY + mm.maxLoc.y.toInt(), sw, sh, mm.maxVal))
                    }
                    result.release(); resized.release()
                }
                templateEq.release()
            }
        }

        // ─── 副露专用模板匹配 (宽高比~1:1, 从截图中采集) ───
        for ((tileId, meldPair) in meldTemplates) {
            val (vertTmpl, horzTmpl) = meldPair
            // 竖放副露匹配
            if (vertTmpl != null) {
                val vH = vertTmpl.rows().toDouble(); val vW = vertTmpl.cols().toDouble()
                val vertEq = Mat(); clahe.apply(vertTmpl, vertEq)
                val vTargetScale = 1.0  // 副露模板尺寸即目标尺寸
                for (i in 0 until 7) {
                    val scale = vTargetScale * (0.85 + i * 0.30 / 6)
                    val sw = (vW * scale).toInt(); val sh = (vH * scale).toInt()
                    if (sw < 6 || sh < 6 || sw > roi.cols() || sh > roi.rows()) continue
                    val resized = Mat()
                    Imgproc.resize(vertEq, resized, Size(sw.toDouble(), sh.toDouble()))
                    val result = Mat()
                    Imgproc.matchTemplate(roi, resized, result, Imgproc.TM_CCOEFF_NORMED)
                    val mm = Core.minMaxLoc(result)
                    if (mm.maxVal >= meldThreshold) {
                        hits.add(Hit(tileId, meldX + mm.maxLoc.x.toInt(), meldY + mm.maxLoc.y.toInt(), sw, sh, mm.maxVal))
                    }
                    result.release(); resized.release()
                }
                vertEq.release()
            }
            // 横放副露匹配
            if (horzTmpl != null) {
                val hH = horzTmpl.rows().toDouble(); val hW = horzTmpl.cols().toDouble()
                val horzEq = Mat(); clahe.apply(horzTmpl, horzEq)
                val hTargetScale = 1.0  // 副露模板尺寸即目标尺寸
                for (i in 0 until 7) {
                    val scale = hTargetScale * (0.85 + i * 0.30 / 6)
                    val sw = (hW * scale).toInt(); val sh = (hH * scale).toInt()
                    if (sw < 6 || sh < 6 || sw > roi.cols() || sh > roi.rows()) continue
                    val resized = Mat()
                    Imgproc.resize(horzEq, resized, Size(sw.toDouble(), sh.toDouble()))
                    val result = Mat()
                    Imgproc.matchTemplate(roi, resized, result, Imgproc.TM_CCOEFF_NORMED)
                    val mm = Core.minMaxLoc(result)
                    if (mm.maxVal >= meldThreshold) {
                        hits.add(Hit(tileId, meldX + mm.maxLoc.x.toInt(), meldY + mm.maxLoc.y.toInt(), sw, sh, mm.maxVal))
                    }
                    result.release(); resized.release()
                }
                horzEq.release()
            }
        }

        roi.release(); gray.release()

        // NMS去重: IoU>0.2合并, 保留最高分
        hits.sortByDescending { it.score }
        val filtered = mutableListOf<Hit>()
        for (h in hits) {
            // 排除摸牌/手牌区 (x<1500与手牌y重叠)
            if (h.x < 1500 && kotlin.math.abs(h.y - 1106) < 50) continue
            val overlap = filtered.any { k ->
                val ix1 = maxOf(h.x, k.x); val iy1 = maxOf(h.y, k.y)
                val ix2 = minOf(h.x + h.w, k.x + k.w); val iy2 = minOf(h.y + h.h, k.y + k.h)
                if (ix1 < ix2 && iy1 < iy2) {
                    val inter = (ix2 - ix1) * (iy2 - iy1).toDouble()
                    val union = (h.w * h.h + k.w * k.h).toDouble() - inter
                    inter / union > 0.2
                } else false
            }
            if (!overlap) filtered.add(h)
        }

        val count = IntArray(34)
        val final = mutableListOf<Hit>()
        for (h in filtered) {
            if (count[h.tileId] < 4) { final.add(h); count[h.tileId]++ }
        }

        FLog.i("TileMatcher", "scanMeldArea done: ${hits.size} raw → ${filtered.size} dedup → ${final.size} final")
        android.util.Log.i(TAG, "副露: ${final.size}张 " + final.joinToString(" ") {
            "${Tiles.name(it.tileId)}@(${it.x},${it.y}) ${"%.2f".format(it.score)}"
        })

        return final.map { MatchResult(it.tileId, it.score, it.score < 0.55) }
    }

    // ─── Canny区域匹配 (保留用于校准) ───
    private fun recognizeByRegions(screenshot: Bitmap, regions: List<RectRegion>): List<MatchResult> {
        val srcMat = Mat()
        Utils.bitmapToMat(screenshot, srcMat)
        Imgproc.cvtColor(srcMat, srcMat, Imgproc.COLOR_RGBA2GRAY)

        val results = mutableListOf<MatchResult>()
        for (region in regions.sortedBy { it.x }) {
            val rx = region.x.coerceAtLeast(0)
            val ry = region.y.coerceAtLeast(0)
            val rw = minOf(region.w, srcMat.cols() - rx)
            val rh = minOf(region.h, srcMat.rows() - ry)
            if (rw <= 0 || rh <= 0) continue

            val tileMat = Mat(srcMat, Rect(rx, ry, rw, rh))
            val (tileId, score) = matchSingleTile(tileMat)
            tileMat.release()
            if (tileId >= 0) results.add(MatchResult(tileId, score, score < 0.55))
        }
        srcMat.release()
        return results
    }

    // ─── Canny边缘检测 (保留用于校准) ───
    fun findTileRegions(bitmap: Bitmap): List<RectRegion> {
        val width = bitmap.width; val height = bitmap.height
        val src = Mat()
        Utils.bitmapToMat(bitmap, src)
        Imgproc.cvtColor(src, src, Imgproc.COLOR_RGBA2GRAY)

        val scale = kotlin.math.sqrt((width * height).toDouble() / 2_000_000.0).coerceIn(0.8, 2.0)
        val lowThreshold = (40.0 * scale).coerceIn(30.0, 100.0)
        val highThreshold = (120.0 * scale).coerceIn(90.0, 200.0)

        val edges = Mat()
        Imgproc.Canny(src, edges, lowThreshold, highThreshold)
        val dilated = Mat()
        val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(3.0, 3.0))
        Imgproc.dilate(edges, dilated, kernel, Point(-1.0, -1.0), 2)

        val contours = mutableListOf<MatOfPoint>()
        val hierarchy = Mat()
        Imgproc.findContours(dilated, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)

        val regions = mutableListOf<RectRegion>()
        val imgArea = width * height
        val minArea = imgArea / 1200; val maxArea = imgArea / 5

        for (contour in contours) {
            val rect = Imgproc.boundingRect(contour)
            val area = rect.width * rect.height
            if (area in minArea..maxArea) {
                val ratio = rect.width.toDouble() / rect.height
                if (ratio in 0.45..0.90) {
                    regions.add(RectRegion(rect.x, rect.y, rect.width, rect.height))
                }
            }
        }
        src.release(); edges.release(); dilated.release(); hierarchy.release()
        for (c in contours) c.release()
        return mergeNearby(regions.sortedBy { it.x })
    }

    private fun mergeNearby(regions: List<RectRegion>): List<RectRegion> {
        if (regions.isEmpty()) return regions
        val merged = mutableListOf(regions[0])
        for (i in 1 until regions.size) {
            val last = merged.last(); val curr = regions[i]
            if (curr.x - last.x < last.w * 0.4) {
                if (curr.w * curr.h > last.w * last.h) merged[merged.lastIndex] = curr
            } else merged.add(curr)
        }
        return merged
    }

    // ─── 调试 ───
    fun saveDebugImage(context: Context, bitmap: Bitmap, name: String): String {
        val dir = File(context.filesDir, "debug")
        if (!dir.exists()) dir.mkdirs()
        val file = File(dir, "$name.png")
        FileOutputStream(file).use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 90, out)
        }
        return file.absolutePath
    }

    // ─── 清理 ───
    fun release() {
        for ((_, mat) in templates) mat.release()
        templates.clear()
        templatesLoaded = false
    }
}
