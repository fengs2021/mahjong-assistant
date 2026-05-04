package com.mahjong.assistant.capture

import android.content.Context
import android.graphics.Bitmap
import com.mahjong.assistant.engine.Tiles
import com.mahjong.assistant.util.FLog
import org.opencv.android.Utils
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import java.io.*

/**
 * YOLO检测结果 — bounding box + 类别
 */
private data class Detection(
    val x: Float, val y: Float, val w: Float, val h: Float,
    val classId: Int, val conf: Float
)

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

    @Volatile private var net: Any? = null  // Net 类型延迟引用, 避免类加载时JNI崩溃
    @Volatile var loaded = false

    fun init(context: Context) {
        if (loaded) return
        try {
            android.util.Log.i(TAG, "TileDetector.init start")
            val modelFile = File(context.filesDir, "mahjong_hand.onnx")
            android.util.Log.i(TAG, "modelFile exists=${modelFile.exists()} path=${modelFile.absolutePath}")
            
            if (!modelFile.exists()) {
                // 从 assets 复制到 filesDir (OpenCV DNN 需要文件路径)
                val input = context.assets.open("mahjong_hand.onnx")
                val output = FileOutputStream(modelFile)
                val copied = input.copyTo(output)
                input.close(); output.close()
                android.util.Log.i(TAG, "copied ${copied} bytes from assets")
            }
            android.util.Log.i(TAG, "model size=${modelFile.length()}")
            
            // 延迟引用 Dnn 类, 避免静态初始化崩溃
            val dnnClass = Class.forName("org.opencv.dnn.Dnn")
            val readNetMethod = dnnClass.getMethod("readNetFromONNX", String::class.java)
            net = readNetMethod.invoke(null, modelFile.absolutePath)
            
            loaded = true
            android.util.Log.i(TAG, "YOLO模型加载成功: ${modelFile.length() / 1024}KB")
            FLog.i(TAG, "YOLO模型加载成功: ${modelFile.length() / 1024}KB")
        } catch (e: Exception) {
            android.util.Log.e(TAG, "YOLO模型加载失败", e)
            FLog.e(TAG, "YOLO模型加载失败: ${e.message}")
            loaded = false
        } catch (e: Error) {
            android.util.Log.e(TAG, "YOLO模型加载JNI崩溃", e)
            FLog.e(TAG, "YOLO模型加载JNI崩溃: ${e.message}")
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

        try {
            val srcMat = Mat()
            Utils.bitmapToMat(screenshot, srcMat)

            // 裁剪手牌ROI
            val roi = Mat(srcMat, handROI)
            val resized = Mat()
            Imgproc.resize(roi, resized, Size(INPUT_SIZE.toDouble(), INPUT_SIZE.toDouble()))

            // 预处理: BGR → blob (反射调用 Dnn.blobFromImage)
            // 注意: 必须用 Double.TYPE/Boolean.TYPE 匹配原始类型签名
            val dnnClass = Class.forName("org.opencv.dnn.Dnn")
            val blobFromImage = dnnClass.getMethod("blobFromImage",
                Mat::class.java,
                java.lang.Double.TYPE,      // double scalefactor
                Size::class.java,
                Scalar::class.java,
                java.lang.Boolean.TYPE,     // boolean swapRB
                java.lang.Boolean.TYPE)     // boolean crop
            val blob = blobFromImage.invoke(null, resized, 1.0 / 255.0,
                Size(INPUT_SIZE.toDouble(), INPUT_SIZE.toDouble()),
                Scalar(0.0), java.lang.Boolean.TRUE, java.lang.Boolean.FALSE)

            resized.release()
            roi.release()
            srcMat.release()

            // 推理 (反射调用 setInput + forward)
            val nn = net ?: return emptyList()
            val netClass = nn.javaClass
            // OpenCV Net.setInput 通常需要 (Mat, String) 两个参数
            netClass.getMethod("setInput", Mat::class.java, String::class.java).invoke(nn, blob, "")
            val output = netClass.getMethod("forward").invoke(nn) as Mat
            (blob as Mat).release()

            // 解码
            val detections = decodeYoloOutput(output)
            output.release()

            // 按x坐标排序 (从左到右 = 手牌顺序)
            val sorted = detections.sortedBy { it.x }

            val results = sorted.map { det ->
                TileMatcher.MatchResult(det.classId, det.conf.toDouble(), det.conf < 0.7)
            }

            FLog.i(TAG, "YOLO检测: ${results.size}张 → ${Tiles.toDisplayString(results.map { it.tileId }.toIntArray())}")
            return results
        } catch (e: Exception) {
            android.util.Log.e(TAG, "YOLO推理失败: ${e.javaClass.simpleName}", e)
            FLog.e(TAG, "YOLO推理失败: ${e.javaClass.simpleName}: ${e.message}")
            return emptyList()
        }
    }

    // ─── YOLOv8 输出解码 ───

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

                // sigmoid激活: class scores
                var maxScore = 0f
                var bestClass = -1
                for (c in 4 until 38) {
                    val score = sigmoid(data[base + c])
                    if (score > maxScore) {
                        maxScore = score
                        bestClass = c - 4
                    }
                }

                if (maxScore < CONF_THRESHOLD) continue

                // sigmoid激活: bbox
                val sx = sigmoid(data[base + 0])
                val sy = sigmoid(data[base + 1])
                val sw = sigmoid(data[base + 2])
                val sh = sigmoid(data[base + 3])

                // 转像素坐标 (anchor-free YOLOv8)
                val px = (sx * 2 - 0.5f + gx) * stride
                val py = (sy * 2 - 0.5f + gy) * stride
                val pw = sw * sw * 4 * stride
                val ph = sh * sh * 4 * stride

                val left = px - pw / 2
                val top = py - ph / 2

                candidates.add(Detection(left, top, pw, ph, bestClass, maxScore))
            }
            offset += nCells * 38
        }

        return nms(candidates, NMS_THRESHOLD)
    }

    private fun sigmoid(x: Float): Float = (1.0 / (1.0 + Math.exp((-x).toDouble()))).toFloat()

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
