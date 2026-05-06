package com.mahjong.assistant.engine

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import com.mahjong.assistant.util.FLog
import org.tensorflow.lite.InterpreterApi
import org.tensorflow.lite.InterpreterApi.Options.TfLiteRuntime
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.min

/**
 * YOLOv8 麻将牌面检测器
 *
 * 直接检测全图所有 34 种牌面，输出坐标+类别+置信度。
 * 替代原有的 OpenCV 模板匹配方案。
 *
 * 模型: best_float16.tflite (YOLOv8, 输入640×640, 输出[1,38,8400])
 */
object YoloDetector {

    private const val INPUT_SIZE = 640

    // 34 类牌名 (模型输出顺序)
    private val CLASS_NAMES = arrayOf(
        "1m","1p","1s","2m","2p","2s","3m","3p","3s","4m","4p","4s",
        "5m","5p","5s","6m","6p","6s","7m","7p","7s","8m","8p","8s",
        "9m","9p","9s","7z","5z","6z","2z","4z","3z","1z"
    )

    // 类名 → tileId (0-33)
    private val CLASS_TO_TILE: Map<String, Int> = run {
        val m = mutableMapOf<String, Int>()
        for ((i, name) in CLASS_NAMES.withIndex()) {
            m[name] = classToTileId(name)
        }
        m
    }

    // 宋体注释
    data class Detection(
        val x1: Int, val y1: Int, val x2: Int, val y2: Int,
        val tileId: Int, val tileName: String,
        val confidence: Float
    )

    private var interpreter: InterpreterApi? = null
    private var loaded = false

    /** 加载模型 (需在主线程外调用) */
    fun loadModel(modelBytes: ByteArray) {
        if (loaded) return
        val byteBuffer = ByteBuffer.allocateDirect(modelBytes.size)
            .apply { order(ByteOrder.nativeOrder()); put(modelBytes) }
        interpreter = InterpreterApi.create(
            byteBuffer,
            InterpreterApi.Options().setRuntime(TfLiteRuntime.FROM_APPLICATION_ONLY)
        )
        loaded = true
        FLog.i("Yolo", "TFLite loaded, ${modelBytes.size} bytes")
    }

    fun close() {
        interpreter?.close()
        interpreter = null
        loaded = false
    }

    /** 检测全图所有牌面 */
    fun detect(bitmap: Bitmap): List<Detection> {
        if (!loaded || interpreter == null) {
            FLog.w("Yolo", "model not loaded")
            return emptyList()
        }
        val (preprocessed, padding) = preprocess(bitmap)
        val inputBuffer = bitmapToFloatBuffer(preprocessed)
        val output = arrayOf(
            Array(4 + CLASS_NAMES.size) { FloatArray(8400) }
        )
        interpreter!!.run(inputBuffer, output)
        return postprocess(output[0], padding)
    }

    // ─── 预处理 ───

    private fun preprocess(bitmap: Bitmap): Pair<Bitmap, PaddingInfo> {
        // 灰度化
        val gray = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
        Canvas(gray).apply {
            val paint = Paint()
            android.graphics.ColorMatrix().apply { setSaturation(0f) }.let {
                paint.colorFilter = android.graphics.ColorMatrixColorFilter(it)
            }
            drawBitmap(bitmap, 0f, 0f, paint)
        }
        // 对比度拉伸
        val stretched = stretchContrast(gray)
        // 直接缩放到640×640 (model训练时用的, 不用letterbox)
        val scaled = Bitmap.createScaledBitmap(stretched, INPUT_SIZE, INPUT_SIZE, true)
        return scaled to PaddingInfo(INPUT_SIZE.toFloat() / bitmap.width, INPUT_SIZE.toFloat() / bitmap.height, 0, 0, bitmap.height)
    }

    private fun stretchContrast(bitmap: Bitmap): Bitmap {
        val w = bitmap.width; val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        var minV = 255; var maxV = 0
        for (p in pixels) {
            val lum = (Color.red(p) * 299 + Color.green(p) * 587 + Color.blue(p) * 114) / 1000
            if (lum < minV) minV = lum
            if (lum > maxV) maxV = lum
        }
        if (maxV > minV) {
            val range = maxV - minV
            for (i in pixels.indices) {
                val lum = (Color.red(pixels[i]) * 299 + Color.green(pixels[i]) * 587 + Color.blue(pixels[i]) * 114) / 1000
                val s = ((lum - minV) * 255 / range).coerceIn(0, 255)
                pixels[i] = Color.rgb(s, s, s)
            }
            bitmap.setPixels(pixels, 0, w, 0, 0, w, h)
        }
        return bitmap
    }

    private fun scaleAndPad(bitmap: Bitmap, targetSize: Int, bg: Int): Pair<Bitmap, PaddingInfo> {
        val scale = targetSize.toFloat() / maxOf(bitmap.width, bitmap.height)
        val sw = (bitmap.width * scale).toInt()
        val sh = (bitmap.height * scale).toInt()
        val padX = (targetSize - sw) / 2
        val padY = (targetSize - sh) / 2
        val out = Bitmap.createBitmap(targetSize, targetSize, Bitmap.Config.ARGB_8888)
        Canvas(out).apply {
            drawColor(bg)
            val m = Matrix().apply {
                postScale(scale, scale)
                postTranslate(padX.toFloat(), padY.toFloat())
            }
            drawBitmap(bitmap, m, Paint(Paint.ANTI_ALIAS_FLAG))
        }
        return out to PaddingInfo(scale, scale, padX, padY, bitmap.height)
    }

    // ─── 推理输入 ───

    /** 将640×640预处理后的Bitmap转为ByteBuffer (NHWC: 1×640×640×3) */
    private fun bitmapToFloatBuffer(bitmap: Bitmap): ByteBuffer {
        val size = 4 * 1 * INPUT_SIZE * INPUT_SIZE * 3
        val buffer = ByteBuffer.allocateDirect(size).apply { order(ByteOrder.nativeOrder()) }
        val pixels = IntArray(INPUT_SIZE * INPUT_SIZE)
        bitmap.getPixels(pixels, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)
        for (p in pixels) {
            buffer.putFloat(((p shr 16) and 0xFF) / 255.0f)  // R
            buffer.putFloat(((p shr 8) and 0xFF) / 255.0f)   // G
            buffer.putFloat((p and 0xFF) / 255.0f)            // B
        }
        buffer.rewind()
        return buffer
    }

    // ─── 后处理 ───

    private const val CONF_THRESHOLD = 0.25f  // 降低阈值, 模型输出偏低
    private const val IOU_THRESHOLD = 0.3f    // 河底NMS IoU阈值
    
    // 手牌底部去重用坐标聚类 (中心x间距<60px=同张牌的不同检测)
    private const val BOTTOM_CLUSTER_GAP = 60  // 中心点x间距<60px同簇

    // ═══════ 后处理 ═══════

    data class PaddingInfo(val scaleX: Float, val scaleY: Float, val padX: Int, val padY: Int,
                           val imgHeight: Int)  // 需要imgHeight判定底部

    private fun postprocess(output: Array<FloatArray>, padding: PaddingInfo): List<Detection> {
        val detections = mutableListOf<Detection>()
        val numClasses = CLASS_NAMES.size
        val numBoxes = output[0].size

        for (i in 0 until numBoxes) {
            val xc = output[0][i]; val yc = output[1][i]
            val bw = output[2][i]; val bh = output[3][i]
            var maxConf = 0f; var classId = -1
            for (c in 0 until numClasses) {
                val conf = output[4 + c][i]
                if (conf > maxConf) { maxConf = conf; classId = c }
            }
            if (maxConf < CONF_THRESHOLD) continue

            val x1 = ((xc - bw / 2 - padding.padX) / padding.scaleX).toInt()
            val y1 = ((yc - bh / 2 - padding.padY) / padding.scaleY).toInt()
            val x2 = ((xc + bw / 2 - padding.padX) / padding.scaleX).toInt()
            val y2 = ((yc + bh / 2 - padding.padY) / padding.scaleY).toInt()

            detections.add(Detection(
                x1 = x1, y1 = y1, x2 = x2, y2 = y2,
                tileId = CLASS_TO_TILE[CLASS_NAMES[classId]] ?: -1,
                tileName = CLASS_NAMES[classId],
                confidence = maxConf
            ))
        }
        return regionFilter(detections, padding.imgHeight)
    }

    /** 分区去重: 底部(y>70%imgH)中心x聚类, 河底标准NMS */
    private fun regionFilter(detections: List<Detection>, imgHeight: Int): List<Detection> {
        val bottomThresh = (imgHeight * 0.7).toInt()
        val bottom = detections.filter { it.y1 > bottomThresh || it.y2 > bottomThresh }
        val upper = detections.filter { it.y1 <= bottomThresh && it.y2 <= bottomThresh }

        // 底部: 中心x聚类 (同张牌的不同检测框合并)
        val bottomFiltered = clusterByCenterX(bottom.sortedBy { (it.x1 + it.x2) / 2 })

        // 河底: 标准NMS
        val upperFiltered = applyNMS(upper)

        val result = bottomFiltered + upperFiltered
        FLog.i("Yolo", String.format("post: raw=%d → bottom[%d→%d] upper[%d→%d]",
            detections.size, bottom.size, bottomFiltered.size, upper.size, upperFiltered.size))
        return result
    }

    /** 中心x聚类: 同x位置(中心间距<BOTTOM_CLUSTER_GAP)保留最高分 */
    private fun clusterByCenterX(sorted: List<Detection>): List<Detection> {
        if (sorted.size <= 1) return sorted
        val result = mutableListOf<Detection>()
        var clusterBest = sorted[0]
        for (i in 1 until sorted.size) {
            val cur = sorted[i]
            val cabin = (clusterBest.x1 + clusterBest.x2) / 2
            val cacur = (cur.x1 + cur.x2) / 2
            if (cacur - cabin < BOTTOM_CLUSTER_GAP) {
                if (cur.confidence > clusterBest.confidence) clusterBest = cur
            } else {
                result.add(clusterBest)
                clusterBest = cur
            }
        }
        result.add(clusterBest)
        return result
    }

    private fun applyNMS(detections: List<Detection>): List<Detection> {
        val sorted = detections.sortedByDescending { it.confidence }.toMutableList()
        val selected = mutableListOf<Detection>()
        while (sorted.isNotEmpty()) {
            val cur = sorted.removeAt(0); selected.add(cur)
            sorted.removeAll { box -> iou(cur, box) > IOU_THRESHOLD }
        }
        return selected
    }

    private fun iou(a: Detection, b: Detection): Float {
        val ix1 = max(a.x1, b.x1); val iy1 = max(a.y1, b.y1)
        val ix2 = min(a.x2, b.x2); val iy2 = min(a.y2, b.y2)
        if (ix2 < ix1 || iy2 < iy1) return 0f
        val ia = (ix2 - ix1) * (iy2 - iy1)
        val aa = (a.x2 - a.x1) * (a.y2 - a.y1)
        val ba = (b.x2 - b.x1) * (b.y2 - b.y1)
        return ia.toFloat() / (aa + ba - ia)
    }

    // ─── 类名映射 ───

    private fun classToTileId(name: String): Int {
        val suit = name.last(); val num = name.dropLast(1).toIntOrNull() ?: return -1
        return when (suit) {
            'm' -> num - 1
            'p' -> num + 8
            's' -> num + 17
            'z' -> when (num) {
                1 -> 27; 2 -> 28; 3 -> 29; 4 -> 30; 5 -> 31; 6 -> 32; 7 -> 33
                else -> -1
            }
            else -> -1
        }
    }
}
