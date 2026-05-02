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
 * 本地模板匹配牌识别器
 *
 * 流程:
 *   1. 校准: 从雀魂截图中截取34种牌的模板 → 保存到内部存储
 *   2. 识别: MediaProjection截图 → 找牌区域 → 模板匹配 → 返回ID+置信度
 *   3. 修正: 用户手动纠正错误识别 → 可选更新模板
 */
object TileMatcher {

    // 模板存储目录
    private const val TEMPLATE_DIR = "tile_templates"

    // 34种牌的模板缓存 (tileId → Mat)
    private val templates = mutableMapOf<Int, Mat>()
    private var templatesLoaded = false

    data class MatchResult(
        val tileId: Int,         // 匹配到的牌ID
        val confidence: Double,  // 置信度 0.0~1.0
        val needsCheck: Boolean  // 是否需要人工确认
    )

    /**
     * 初始化OpenCV并加载模板
     * @return 初始化是否成功
     */
    fun init(context: Context): Boolean {
        return try {
            if (!OpenCVLoader.initLocal()) {
                android.util.Log.e("TileMatcher", "OpenCV initLocal failed")
                false
            } else {
                android.util.Log.i("TileMatcher", "OpenCV loaded OK")
                loadTemplates(context)
                templatesLoaded
            }
        } catch (e: Exception) {
            android.util.Log.e("TileMatcher", "OpenCV init crash: ${e.message}", e)
            false
        }
    }

    /**
     * 从内部存储加载所有模板
     */
    private fun loadTemplates(context: Context) {
        templates.clear()
        val dir = File(context.filesDir, TEMPLATE_DIR)
        if (!dir.exists()) {
            templatesLoaded = false
            return
        }

        for (tileId in 0..33) {
            val file = File(dir, "tile_${tileId}.png")
            if (file.exists()) {
                val bitmap = BitmapFactory.decodeFile(file.absolutePath)
                if (bitmap != null) {
                    val mat = Mat()
                    Utils.bitmapToMat(bitmap, mat)
                    Imgproc.cvtColor(mat, mat, Imgproc.COLOR_RGBA2BGR)
                    templates[tileId] = mat
                    bitmap.recycle()
                }
            }
        }
        templatesLoaded = templates.isNotEmpty()
    }

    /**
     * 是否有模板可用
     */
    fun hasTemplates(): Boolean = templatesLoaded

    /**
     * 获取已校准的模板数量
     */
    fun templateCount(): Int = templates.size

    // ─── 校准: 保存模板 ───

    /**
     * 从截图中截取单张牌的模板
     * @param screenshot 雀魂截图
     * @param tileX, tileY, tileW, tileH 牌在截图中的位置
     * @param tileId 对应的牌ID (0-33)
     */
    fun saveTemplate(
        context: Context,
        screenshot: Bitmap,
        tileX: Int, tileY: Int, tileW: Int, tileH: Int,
        tileId: Int
    ): Boolean {
        val dir = File(context.filesDir, TEMPLATE_DIR)
        if (!dir.exists()) dir.mkdirs()

        // 裁切并标准化大小
        val tile = Bitmap.createBitmap(screenshot, tileX, tileY, tileW, tileH)
        val normalized = Bitmap.createScaledBitmap(tile, 80, 112, true)
        tile.recycle()

        val file = File(dir, "tile_${tileId}.png")
        FileOutputStream(file).use { out ->
            normalized.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        normalized.recycle()

        // 重新加载模板
        loadTemplates(context)
        return true
    }

    /**
     * 批量校准 — 从完整手牌截图+已知手牌中提取模板
     * @param screenshot 含有清晰手牌的截图
     * @param handTiles 已知的手牌序列 (按牌面从左到右)
     */
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

    /**
     * 从截图中识别手牌
     * @return Pair<识别结果列表, 总体置信度>
     */
    fun recognize(screenshot: Bitmap): Pair<List<MatchResult>, Double> {
        if (!templatesLoaded) return Pair(emptyList(), 0.0)

        val regions = findTileRegions(screenshot)
        if (regions.isEmpty()) return Pair(emptyList(), 0.0)

        val srcMat = Mat()
        Utils.bitmapToMat(screenshot, srcMat)
        Imgproc.cvtColor(srcMat, srcMat, Imgproc.COLOR_RGBA2BGR)

        val results = mutableListOf<MatchResult>()
        for (region in regions.sortedBy { it.x }) {
            // 裁切牌区域
            val tileMat = Mat(srcMat, Rect(region.x, region.y, region.w, region.h))

            // 对每个模板做匹配
            var bestId = -1
            var bestScore = 0.0

            for ((tileId, template) in templates) {
                // 调整模板大小匹配目标
                val resized = Mat()
                Imgproc.resize(template, resized, Size(region.w.toDouble(), region.h.toDouble()))

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
                val needsCheck = bestScore < 0.6  // 低于60%置信度标记为需确认
                results.add(MatchResult(bestId, bestScore, needsCheck))
            }
        }

        srcMat.release()

        // 总体置信度: 结果中可靠比例
        val reliableCount = results.count { !it.needsCheck }
        val overallConfidence = if (results.isNotEmpty()) {
            reliableCount.toDouble() / results.size
        } else 0.0

        return Pair(results, overallConfidence)
    }

    // ─── 查找牌区域 ───

    data class RectRegion(val x: Int, val y: Int, val w: Int, val h: Int)

    fun findTileRegions(bitmap: Bitmap): List<RectRegion> {
        val width = bitmap.width
        val height = bitmap.height

        // 转OpenCV Mat进行边缘检测
        val src = Mat()
        Utils.bitmapToMat(bitmap, src)
        Imgproc.cvtColor(src, src, Imgproc.COLOR_RGBA2GRAY)

        // Canny边缘检测
        val edges = Mat()
        Imgproc.Canny(src, edges, 50.0, 150.0)

        // 膨胀连接邻近边缘
        val dilated = Mat()
        val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(3.0, 3.0))
        Imgproc.dilate(edges, dilated, kernel, Point(-1.0, -1.0), 2)

        // 找轮廓
        val contours = mutableListOf<MatOfPoint>()
        val hierarchy = Mat()
        Imgproc.findContours(dilated, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)

        val regions = mutableListOf<RectRegion>()
        val imgArea = width * height
        val minArea = imgArea / 800    // 最小牌面积
        val maxArea = imgArea / 4      // 最大牌面积

        for (contour in contours) {
            val rect = Imgproc.boundingRect(contour)
            val area = rect.width * rect.height

            if (area in minArea..maxArea) {
                val ratio = rect.width.toDouble() / rect.height
                // 牌的比例: 宽<高, 比约0.55-0.8
                if (ratio in 0.5..0.85) {
                    regions.add(RectRegion(rect.x, rect.y, rect.width, rect.height))
                }
            }
        }

        // 清理
        src.release(); edges.release(); dilated.release(); hierarchy.release()
        for (c in contours) c.release()

        // 按x排序，去重
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
                // 重叠，保留大的
                if (curr.w * curr.h > last.w * last.h) {
                    merged[merged.lastIndex] = curr
                }
            } else {
                merged.add(curr)
            }
        }
        return merged
    }

    // ─── 工具: 调试截图 ───

    /**
     * 保存截图用于调试/校准
     */
    fun saveDebugImage(context: Context, bitmap: Bitmap, name: String): String {
        val dir = File(context.filesDir, "debug")
        if (!dir.exists()) dir.mkdirs()
        val file = File(dir, "$name.png")
        FileOutputStream(file).use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 90, out)
        }
        return file.absolutePath
    }
}
