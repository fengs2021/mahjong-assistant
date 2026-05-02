package com.mahjong.assistant.capture

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import java.io.File
import java.io.FileOutputStream

/**
 * 牌面模板匹配识别器 v2.0
 *
 * 模板来源优先级:
 *   1. APK assets 预置模板 (34张雀魂标准牌面) — 开箱即用
 *   2. 内部存储手动校准模板 — 覆盖/补充标准模板
 *
 * 识别流程:
 *   截图 → Canny边缘检测找牌区域 → 模板匹配 → 返回tileId+置信度
 */
object TileMatcher {

    private const val TAG = "TileMatcher"
    private const val ASSET_TILES_DIR = "tiles"     // assets/tiles/
    private const val TEMPLATE_DIR = "tile_templates" // 内部存储模板目录

    data class MatchResult(
        val tileId: Int,         // 0-33
        val confidence: Double,  // 0.0~1.0
        val needsCheck: Boolean  // 置信度<0.55需人工确认
    )

    data class RectRegion(val x: Int, val y: Int, val w: Int, val h: Int)

    // 模板缓存
    private val templates = mutableMapOf<Int, Mat>()
    @Volatile private var templatesLoaded = false
    private var initDiagnostic = "未初始化"

    /** OpenCV是否加载成功 */
    private var opencvReady = false

    // ─── tileId映射: 中文文件名 → ID ───

    private val nameToId = mapOf(
        "一万" to 0,  "二万" to 1,  "三万" to 2,  "四万" to 3,  "五万" to 4,
        "六万" to 5,  "七万" to 6,  "八万" to 7,  "九万" to 8,
        "一筒" to 9,  "二筒" to 10, "三筒" to 11, "四筒" to 12, "五筒" to 13,
        "六筒" to 14, "七筒" to 15, "八筒" to 16, "九筒" to 17,
        "一索" to 18, "二索" to 19, "三索" to 20, "四索" to 21, "五索" to 22,
        "六索" to 23, "七索" to 24, "八索" to 25, "九索" to 26,
        "东" to 27,  "南" to 28,  "西" to 29,  "北" to 30,
        "白" to 31,  "发" to 32,  "中" to 33
    )

    // ─── 初始化 ───

    fun init(context: Context): Boolean {
        return try {
            // 1. 加载OpenCV
            opencvReady = try {
                OpenCVLoader.initLocal().also {
                    android.util.Log.i(TAG, "OpenCV initLocal: $it")
                }
            } catch (e: UnsatisfiedLinkError) {
                android.util.Log.e(TAG, "OpenCV UnsatisfiedLinkError: ${e.message}. ABI=${android.os.Build.SUPPORTED_ABIS.contentToString()}")
                false
            }

            if (!opencvReady) {
                initDiagnostic = "OpenCV加载失败 ABI=${android.os.Build.SUPPORTED_ABIS.contentToString()}"
                return false
            }

            // 2. 加载模板
            templatesLoaded = loadPresetTemplates(context)
            if (!templatesLoaded) {
                // 回退: 尝试加载手动校准的模板
                templatesLoaded = loadCalibratedTemplates(context)
            }

            initDiagnostic = if (templatesLoaded) {
                "就绪: ${templates.size}/34 模板 OpenCV=${opencvReady}"
            } else {
                "模板为空: assets未找到且无手动校准"
            }
            android.util.Log.i(TAG, initDiagnostic)
            templatesLoaded
        } catch (e: Exception) {
            initDiagnostic = "初始化崩溃: ${e.message}"
            android.util.Log.e(TAG, initDiagnostic, e)
            false
        }
    }

    /** 诊断信息 */
    fun getDiagnostic(): String = "$initDiagnostic | 模板数=${templates.size} | OpenCV=$opencvReady"
    fun hasTemplates(): Boolean = templatesLoaded
    fun templateCount(): Int = templates.size
    fun isOpencvReady(): Boolean = opencvReady

    // ─── 从assets加载预置模板 ───

    private fun loadPresetTemplates(context: Context): Boolean {
        return try {
            val assets = context.assets
            val tileFiles = assets.list(ASSET_TILES_DIR) ?: run {
                android.util.Log.w(TAG, "assets/tiles/ 目录为空或不存在")
                return false
            }

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
                        Imgproc.cvtColor(mat, mat, Imgproc.COLOR_RGBA2BGR)
                        templates[tileId] = mat
                        bitmap.recycle()
                        loaded++
                    }
                } catch (e: Exception) {
                    android.util.Log.w(TAG, "加载模板失败: $filename — ${e.message}")
                }
            }

            android.util.Log.i(TAG, "从assets加载 $loaded/34 模板")
            loaded > 0
        } catch (e: Exception) {
            android.util.Log.e(TAG, "assets加载异常: ${e.message}", e)
            false
        }
    }

    // ─── 从内部存储加载手动校准模板 ───

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
                    Imgproc.cvtColor(mat, mat, Imgproc.COLOR_RGBA2BGR)
                    // 手动模板覆盖预置模板
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

    // ─── 手动校准 (保留接口兼容) ───

    fun saveTemplate(
        context: Context,
        screenshot: Bitmap,
        tileX: Int, tileY: Int, tileW: Int, tileH: Int,
        tileId: Int
    ): Boolean {
        val dir = File(context.filesDir, TEMPLATE_DIR)
        if (!dir.exists()) dir.mkdirs()

        val tile = Bitmap.createBitmap(screenshot, tileX, tileY, tileW, tileH)
        val normalized = Bitmap.createScaledBitmap(tile, 80, 112, true)
        tile.recycle()

        val file = File(dir, "tile_${tileId}.png")
        FileOutputStream(file).use { out ->
            normalized.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        normalized.recycle()

        // 重新加载
        templates[tileId]?.release()
        val mat = Mat()
        val reloaded = BitmapFactory.decodeFile(file.absolutePath)
        Utils.bitmapToMat(reloaded, mat)
        Imgproc.cvtColor(mat, mat, Imgproc.COLOR_RGBA2BGR)
        templates[tileId] = mat
        reloaded.recycle()
        templatesLoaded = true
        return true
    }

    fun calibrateFromHand(
        context: Context,
        screenshot: Bitmap,
        handTiles: IntArray
    ): Int {
        val regions = findTileRegions(screenshot)
        if (regions.size < handTiles.size) return 0

        var saved = 0
        val sortedRegions = regions.sortedBy { it.x }
        for (i in handTiles.indices) {
            if (i >= sortedRegions.size) break
            val r = sortedRegions[i]
            if (saveTemplate(context, screenshot, r.x, r.y, r.w, r.h, handTiles[i])) {
                saved++
            }
        }
        return saved
    }

    // ─── 识别: 模板匹配 ───

    fun recognize(screenshot: Bitmap): Pair<List<MatchResult>, Double> {
        if (!templatesLoaded) return Pair(emptyList(), -1.0)

        val regions = findTileRegions(screenshot)
        if (regions.isEmpty()) return Pair(emptyList(), -2.0)

        val srcMat = Mat()
        Utils.bitmapToMat(screenshot, srcMat)
        Imgproc.cvtColor(srcMat, srcMat, Imgproc.COLOR_RGBA2BGR)

        val results = mutableListOf<MatchResult>()
        for (region in regions.sortedBy { it.x }) {
            // 边界保护
            val rx = region.x.coerceAtLeast(0)
            val ry = region.y.coerceAtLeast(0)
            val rw = minOf(region.w, srcMat.cols() - rx)
            val rh = minOf(region.h, srcMat.rows() - ry)
            if (rw <= 0 || rh <= 0) continue

            val tileMat = Mat(srcMat, Rect(rx, ry, rw, rh))
            var bestId = -1
            var bestScore = 0.0

            for ((tileId, template) in templates) {
                val resized = Mat()
                Imgproc.resize(template, resized, Size(rw.toDouble(), rh.toDouble()))

                val result = Mat()
                Imgproc.matchTemplate(tileMat, resized, result, Imgproc.TM_CCOEFF_NORMED)
                val score = Core.minMaxLoc(result).maxVal
                result.release()
                resized.release()

                if (score > bestScore) {
                    bestScore = score
                    bestId = tileId
                }
            }
            tileMat.release()

            if (bestId >= 0) {
                results.add(MatchResult(bestId, bestScore, bestScore < 0.55))
            }
        }
        srcMat.release()

        val reliableCount = results.count { !it.needsCheck }
        val overallConfidence = if (results.isNotEmpty()) {
            reliableCount.toDouble() / results.size
        } else 0.0

        return Pair(results, overallConfidence)
    }

    // ─── 查找牌区域 (Canny边缘检测 + 轮廓查找) ───

    fun findTileRegions(bitmap: Bitmap): List<RectRegion> {
        val width = bitmap.width
        val height = bitmap.height

        val src = Mat()
        Utils.bitmapToMat(bitmap, src)
        Imgproc.cvtColor(src, src, Imgproc.COLOR_RGBA2GRAY)

        // 自适应阈值: 根据图片大小调整Canny参数
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
        val minArea = imgArea / 1200
        val maxArea = imgArea / 5

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

        val sorted = regions.sortedBy { it.x }
        return mergeNearby(sorted)
    }

    private fun mergeNearby(regions: List<RectRegion>): List<RectRegion> {
        if (regions.isEmpty()) return regions
        val merged = mutableListOf(regions[0])
        for (i in 1 until regions.size) {
            val last = merged.last()
            val curr = regions[i]
            if (curr.x - last.x < last.w * 0.4) {
                if (curr.w * curr.h > last.w * last.h) {
                    merged[merged.lastIndex] = curr
                }
            } else {
                merged.add(curr)
            }
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
        for ((_, mat) in templates) {
            mat.release()
        }
        templates.clear()
        templatesLoaded = false
    }
}
