package com.mahjong.assistant.capture

import android.content.Context
import android.graphics.Bitmap
import com.mahjong.assistant.engine.Tiles
import com.mahjong.assistant.util.FLog
import org.opencv.android.Utils
import org.opencv.core.*
import org.opencv.dnn.Dnn
import org.opencv.dnn.Net
import org.opencv.imgproc.Imgproc
import java.io.*

/**
 * YOLOv8 牌面检测器 — 替换模板匹配
 *
 * 模型: YOLOv8n, 34类(万/筒/索/字), ONNX导出
 * 输入: 手牌ROI裁剪 → 640×640
 * 输出: [1, 38, 8400] → 解码 → NMS → 13张牌
 */
object TileDetector {

    private const val TAG = "TileDetector"
    private const val INPUT_SIZE = 640
    private const val CONF_THRESHOLD = 0.25f
    private const val NMS_THRESHOLD = 0.45f

    // 手牌ROI坐标 (PPG-AN00 2800×1264)
    private val handROI = Rect(536, 1106, 1443, 143)  // 13×111+间距

    @Volatile private var net: Net? = null
    @Volatile var loaded = false

    fun init(context: Context) {
        if (loaded) return
        try {
            val modelFile = File(context.filesDir, "mahjong_hand.onnx")
            if (!modelFile.exists()) {
                // 从 assets 复制到 filesDir (OpenCV DNN 需要文件路径)
                context.assets.open("mahjong_hand.onnx").use { input ->
                    FileOutputStream(modelFile).use { output ->
                        input.copyTo(output)
                    }
                }
            }
            net = Dnn.readNetFromONNX(modelFile.absolutePath)
            loaded = true
            FLog.i(TAG, "YOLO模型加载成功: ${modelFile.length() / 1024}KB")
        } catch (e: Exception) {
            FLog.e(TAG, "YOLO模型加载失败: ${e.message}")
            loaded = false
        }
    }

    fun release() {
        net = null
        loaded = false
    }

    /**
     * 检测手牌中的所有牌
     * @return 按x坐标排序的检测结果
     */
    fun detect(screenshot: Bitmap): List<TileMatcher.MatchResult> {
        if (!loaded) {
            FLog.w(TAG, "模型未加载, 回退模板匹配")
            return emptyList()
        }

        val srcMat = Mat()
        Utils.bitmapToMat(screenshot, srcMat)

        // 裁剪手牌ROI
        val roi = Mat(srcMat, handROI)
        val resized = Mat()
        Imgproc.resize(roi, resized, Size(INPUT_SIZE.toDouble(), INPUT_SIZE.toDouble()))

        // 预处理: BGR → blob
        val blob = Dnn.blobFromImage(
            resized, 1.0 / 255.0,
            Size(INPUT_SIZE.toDouble(), INPUT_SIZE.toDouble()),
            Scalar(0.0), true, false
        )

        resized.release()
        roi.release()
        srcMat.release()

        // 推理
        val nn = net ?: return emptyList()
        nn.setInput(blob)
        val output = nn.forward()
        blob.release()

        // 解码
        val detections = decodeYoloOutput(output)
        output.release()

        // 按x坐标排序 (从左到右 = 手牌顺序)
        detections.sortBy { d -> d.x }

        // 转换为 MatchResult
        val results = detections.map { det ->
            TileMatcher.MatchResult(det.classId, det.conf.toDouble(), det.conf < 0.7)
        }

        FLog.i(TAG, "YOLO检测: ${results.size}张 → ${Tiles.toDisplayString(results.map { it.tileId }.toIntArray())}")
        return results
    }

    // ─── YOLOv8 输出解码 ───

    private data class Detection(
        val x: Float, val y: Float, val w: Float, val h: Float,
        val classId: Int, val conf: Float
    )

    private fun decodeYoloOutput(output: Mat): List<Detection> {
        // output shape: [1, 38, 8400]
        val data = FloatArray(38 * 8400)
        output.get(0, 0, data)

        val candidates = mutableListOf<Detection>()
        val strides = intArrayOf(8, 16, 32)
        val gridSizes = intArrayOf(80, 40, 20)

        var offset = 0
        for (s in strides.indices) {
            val grid = gridSizes[s]
            val stride = strides[s]
            val nCells = grid * grid

            for (i in 0 until nCells) {
                val base = offset + i * 38
                val gy = i / grid
                val gx = i % grid

                // 找到最高分类别
                var maxScore = 0f
                var bestClass = -1
                for (c in 4 until 38) {
                    val score = data[base + c]
                    if (score > maxScore) {
                        maxScore = score
                        bestClass = c - 4
                    }
                }

                if (maxScore < CONF_THRESHOLD) continue

                // bbox 解码
                val x = data[base + 0]
                val y = data[base + 1]
                val w = data[base + 2]
                val h = data[base + 3]

                // 归一化 → 像素坐标
                val px = x * stride + gx * stride
                val py = y * stride + gy * stride
                val pw = w * stride
                val ph = h * stride

                // 转为中心点 → 左上角
                val left = px - pw / 2
                val top = py - ph / 2

                candidates.add(Detection(left, top, pw, ph, bestClass, maxScore))
            }
            offset += nCells * 38
        }

        // NMS
        return nms(candidates, NMS_THRESHOLD)
    }

    private fun nms(detections: List<Detection>, iouThreshold: Float): List<Detection> {
        if (detections.isEmpty()) return emptyList()

        val sorted = detections.sortedByDescending { it.conf }.toMutableList()
        val result = mutableListOf<Detection>()

        while (sorted.isNotEmpty()) {
            val best = sorted.removeAt(0)
            result.add(best)

            val iter = sorted.iterator()
            while (iter.hasNext()) {
                val d = iter.next()
                if (iou(best, d) > iouThreshold) {
                    iter.remove()
                }
            }
        }
        return result
    }

    private fun iou(a: Detection, b: Detection): Float {
        val ax1 = a.x; val ay1 = a.y
        val ax2 = a.x + a.w; val ay2 = a.y + a.h
        val bx1 = b.x; val bx2 = b.x + b.w
        val by1 = b.y; val by2 = b.y + b.h

        val interX1 = maxOf(ax1, bx1)
        val interY1 = maxOf(ay1, by1)
        val interX2 = minOf(ax2, bx2)
        val interY2 = minOf(ay2, by2)

        val interW = maxOf(0f, interX2 - interX1)
        val interH = maxOf(0f, interY2 - interY1)
        val interArea = interW * interH

        val areaA = a.w * a.h
        val areaB = b.w * b.h
        val union = areaA + areaB - interArea

        return if (union > 0f) interArea / union else 0f
    }
}
