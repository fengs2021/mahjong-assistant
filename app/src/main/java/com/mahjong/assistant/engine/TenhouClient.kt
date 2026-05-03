package com.mahjong.assistant.engine

import android.os.Handler
import android.os.Looper
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import com.mahjong.assistant.util.FLog
import okhttp3.OkHttpClient
import okhttp3.Request as OkRequest
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * 天凤牌理 WebView 查询客户端
 *
 * 天凤页面 (tenhou.net/2/) 使用 JS 渲染, 必须用 WebView 抓取。
 * Android 5.0+ WebView 不走系统 HTTP 代理 → 国内需用 OkHttp 代理拦截。
 * 解析 textarea 中的天凤分析结果, 提取最优切牌建议。
 */
object TenhouClient {

    private var webView: WebView? = null
    private var isReady = false
    private val mainHandler = Handler(Looper.getMainLooper())

    /** OkHttp 代理客户端 (用于 WebView 请求拦截) */
    private var proxyClient: OkHttpClient? = null

    const val PROXY_HOST = "127.0.0.1"
    const val PROXY_PORT = 7890

    /** 天凤推荐结果 */
    data class Advice(
        val bestDiscard: Int,          // 推荐切的牌ID
        val bestDiscardName: String,   // 牌名
        val shanten: Int,              // 向听数
        val ukeire: Int                // 有效进张数
    )

    /** 初始化 WebView + 代理 (需在主线程调用一次) */
    fun init(wv: WebView, proxyHost: String = PROXY_HOST, proxyPort: Int = PROXY_PORT) {
        webView = wv
        wv.settings.javaScriptEnabled = true

        // 创建 OkHttp 代理客户端
        proxyClient = OkHttpClient.Builder()
            .proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress(proxyHost, proxyPort)))
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .build()

        // WebViewClient 拦截所有请求, 通过代理发出
        wv.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest
            ): WebResourceResponse? {
                return try {
                    val okReq = OkRequest.Builder()
                        .url(request.url.toString())
                        .header("User-Agent", request.requestHeaders["User-Agent"] ?: "Mozilla/5.0")
                        .build()
                    val response = proxyClient!!.newCall(okReq).execute()
                    val mime = response.header("content-type", "text/html")?.split(";")?.firstOrNull() ?: "text/html"
                    val encoding = response.header("content-encoding") ?: "UTF-8"
                    val body = response.body
                    if (body != null) {
                        WebResourceResponse(mime, encoding, body.byteStream())
                    } else null
                } catch (e: Exception) {
                    FLog.w("Tenhou", "proxy intercept failed: ${request.url.host}")
                    null // 让 WebView 直连降级
                }
            }
        }

        isReady = true
        FLog.i("Tenhou", "WebView ready, proxy=$proxyHost:$proxyPort")
    }

    fun release() {
        isReady = false
        webView = null
        proxyClient = null
        FLog.i("Tenhou", "released")
    }

    /**
     * 查询天凤牌理
     * @param hand 14张手牌 (0-33)
     * @param timeoutMs 超时毫秒
     * @return Advice? null表示失败需降级本地引擎
     */
    fun query(hand: IntArray, timeoutMs: Long = 3000): Advice? {
        if (!isReady || webView == null) {
            FLog.w("Tenhou", "not ready, skip")
            return null
        }

        val handStr = toTenhouString(hand)
        if (handStr.isEmpty() || hand.size != 14) {
            FLog.w("Tenhou", "invalid hand size=${hand.size}")
            return null
        }

        val url = "https://tenhou.net/2/?q=$handStr"
        val latch = CountDownLatch(1)
        val resultRef = AtomicReference<String?>(null)

        mainHandler.post {
            try {
                webView?.loadUrl(url)
                // 等待页面加载完成后提取 textarea
                mainHandler.postDelayed({
                    try {
                        webView?.evaluateJavascript(
                            "(function(){var ta=document.querySelector('textarea');return ta?ta.value:'';})()"
                        ) { raw ->
                            val content = raw?.trim('"')?.replace("\\n", "\n")?.replace("\\t", "\t") ?: ""
                            if (content.isNotBlank()) {
                                resultRef.set(content)
                            }
                            latch.countDown()
                        }
                    } catch (e: Exception) {
                        FLog.e("Tenhou", "evaluateJavascript failed", e)
                        latch.countDown()
                    }
                }, 1500) // 1.5s 等待页面渲染
            } catch (e: Exception) {
                FLog.e("Tenhou", "loadUrl failed", e)
                latch.countDown()
            }
        }

        return try {
            if (latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
                val content = resultRef.get()
                if (content != null) {
                    parseResult(content, hand)
                } else null
            } else {
                FLog.w("Tenhou", "timeout ${timeoutMs}ms")
                null
            }
        } catch (e: Exception) {
            FLog.e("Tenhou", "query failed", e)
            null
        }
    }

    /**
     * 解析天凤 textarea 输出
     *
     * 天凤输出格式示例:
     *   牌理 234m456p67s1123z
     *   向聴数 あと1
     *   待ち 58s
     *   有効牌 4種15枚
     *
     * 或14张手牌格式:
     *   牌理 123m456p789s112z
     *   向聴数 あと0 → 听牌
     *
     *   打 [1z] 待 58s 有効牌 2種8枚
     *   打 [2z] 待 58s 有効牌 2種8枚
     *   打 [6s] 向聴 1 有効牌 4種15枚
     */
    private fun parseResult(content: String, hand: IntArray): Advice? {
        try {
            val lines = content.lines().map { it.trim() }.filter { it.isNotBlank() }

            var shanten = 99
            var bestDiscardName = ""
            var bestUkeire = 0

            for (line in lines) {
                // 向聴数
                when {
                    line.contains("あと0") || line.contains("聴牌") ->
                        shanten = 0
                    line.contains("あと1") -> shanten = 1
                    line.contains("あと2") -> shanten = 2
                    line.contains("あと") -> {
                        val match = Regex("あと(\\d)").find(line)
                        shanten = match?.groupValues?.get(1)?.toIntOrNull() ?: shanten
                    }
                }

                // "打 [X]" 行: 天凤推荐切牌
                val discardMatch = Regex("打\\s*\\[(.+?)\\]").find(line)
                if (discardMatch != null && bestDiscardName.isEmpty()) {
                    bestDiscardName = discardMatch.groupValues[1]
                }

                // 有効牌 N種M枚
                val ukeireMatch = Regex("有効牌\\s*(\\d+)種(\\d+)枚").find(line)
                if (ukeireMatch != null && bestDiscardName.isNotEmpty()) {
                    bestUkeire = ukeireMatch.groupValues[2].toIntOrNull() ?: 0
                    break // 找到第一个推荐就够了
                }
            }

            if (shanten == 99 || bestDiscardName.isEmpty()) {
                FLog.w("Tenhou", "parse failed: shanten=$shanten discard=$bestDiscardName\n$content")
                return null
            }

            // 天凤牌名映射到 tileId
            val tileId = parseTenhouTile(bestDiscardName)
            if (tileId < 0) return null

            FLog.i("Tenhou", "result: 切$bestDiscardName shanten=$shanten ukeire=$bestUkeire")
            return Advice(tileId, bestDiscardName, shanten, bestUkeire)

        } catch (e: Exception) {
            FLog.e("Tenhou", "parse exception", e)
            return null
        }
    }

    /** 天凤牌名 → tileId (0-33) */
    private fun parseTenhouTile(name: String): Int {
        // 天凤格式: "1m" "5p" "7s" "1z"(東) ... "7z"(中)
        val re = Regex("(\\d+)([mpsz])")
        val m = re.find(name) ?: return -1
        val num = m.groupValues[1].toIntOrNull() ?: return -1
        val suit = m.groupValues[2]
        val base = when (suit) {
            "m" -> 0; "p" -> 9; "s" -> 18; "z" -> 27; else -> return -1
        }
        return base + num - 1
    }

    /** 手牌转天凤 query string */
    fun toTenhouString(hand: IntArray): String {
        if (hand.isEmpty()) return ""
        val sorted = hand.sorted()
        val sb = StringBuilder()
        var i = 0
        while (i < sorted.size) {
            val tile = sorted[i]
            val suit = when {
                tile < 9 -> 'm'; tile < 18 -> 'p'; tile < 27 -> 's'; else -> 'z'
            }
            val base = when (suit) { 'm' -> 0; 'p' -> 9; 's' -> 18; else -> 27 }
            val num = tile - base + 1
            // 收集同花色连续数字
            val nums = mutableListOf<Int>()
            while (i < sorted.size) {
                val t = sorted[i]
                val s = when { t < 9 -> 'm'; t < 18 -> 'p'; t < 27 -> 's'; else -> 'z' }
                if (s != suit) break
                nums.add(t - base + 1)
                i++
            }
            sb.append(nums.joinToString("")).append(suit)
        }
        return sb.toString()
    }
}
