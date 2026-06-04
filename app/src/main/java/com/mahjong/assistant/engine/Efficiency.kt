package com.mahjong.assistant.engine

/**
 * 牌効率分析 — 最优切牌推荐
 *
 * v7.0 新增多因子评分系统:
 *   排序优先级: 向听 > 进张(×巡目权重) > 听牌质量 > 七对子 > 断幺九 > 清一色 > 宝牌
 */
object Efficiency {

    data class DiscardAdvice(
        val tile: Int,                // 推荐切的牌ID
        val tileName: String,         // 牌名
        val shantenAfter: Int,        // 切后向听数
        val ukeire: Int,              // 有效进张数
        val ukeireTiles: List<Pair<Int, Int>>, // (牌ID, 剩余枚数)
        val deltaShanten: Int,        // 向听变化 (负=改善)

        // 多因子评分 (v7.0)
        val compositeScore: Double = 0.0,  // 综合评分 (低=优)
        val ukeireWeighted: Double = 0.0,  // 进张×巡目权重
        val waitQuality: Double = 0.0,     // 听牌质量 (高=好)
        val chiitoiScore: Double = 0.0,    // 七对子评分
        val tanyaoScore: Double = 0.0,     // 断幺九评分
        val flushScore: Double = 0.0,      // 清一色评分
        val doraScore: Double = 0.0        // 宝牌评分
    )

    /** 13张手牌分析: 向听数 + 有效进张 */
    fun analyze13(hand: IntArray, visible: IntArray = IntArray(0)): Pair<Int, List<Pair<Int, Int>>> {
        val currentS = Shanten.calculate(hand).shanten
        val (ukeire, tiles) = calcUkeire(hand, visible, currentS)
        return Pair(currentS, tiles)
    }

    /**
     * 分析14张手牌的切牌建议 (基础版: 向听+进张排序)
     */
    fun analyze(hand: IntArray, visible: IntArray = IntArray(0)): List<DiscardAdvice> {
        val current = Shanten.calculate(hand)
        val currentS = current.shanten

        val results = mutableListOf<DiscardAdvice>()
        val seen = mutableSetOf<Int>()

        for (idx in hand.indices) {
            val tile = hand[idx]
            if (tile in seen) continue
            seen.add(tile)

            val afterHand = hand.toMutableList().apply { removeAt(idx) }.toIntArray()
            val after = Shanten.calculate(afterHand)
            val afterS = after.shanten

            val (ukeire, ukeireTiles) = calcUkeire(afterHand, visible, afterS)

            results.add(DiscardAdvice(
                tile = tile,
                tileName = Tiles.name(tile),
                shantenAfter = afterS,
                ukeire = ukeire,
                ukeireTiles = ukeireTiles,
                deltaShanten = afterS - currentS
            ))
        }

        // 排序：改善向听 > 进张多
        results.sortWith(compareBy<DiscardAdvice> { it.deltaShanten }.thenByDescending { it.ukeire })
        return results
    }

    /**
     * 分析14张手牌的切牌建议 (多因子评分版)
     *
     * @param hand 14张手牌
     * @param visible 可见牌 (河底/副露)
     * @param turn 当前巡目 (1~18)
     * @param doraIndicators 宝牌指示牌ID列表 (如 [3] = 四万指示)
     */
    fun analyzeWithScoring(
        hand: IntArray,
        visible: IntArray = IntArray(0),
        turn: Int = 1,
        doraIndicators: IntArray = IntArray(0)
    ): List<DiscardAdvice> {
        val current = Shanten.calculate(hand)
        val currentS = current.shanten
        val speedWeight = if (turn > 10) 1.5 else 1.0

        val results = mutableListOf<DiscardAdvice>()
        val seen = mutableSetOf<Int>()

        for (idx in hand.indices) {
            val tile = hand[idx]
            if (tile in seen) continue
            seen.add(tile)

            val afterHand = hand.toMutableList().apply { removeAt(idx) }.toIntArray()
            val after = Shanten.calculate(afterHand)
            val afterS = after.shanten

            val (ukeire, ukeireTiles) = calcUkeire(afterHand, visible, afterS)

            // ─── 多因子评分 ───
            val counts = toCounts34(afterHand)
            val wq = waitQuality(counts)
            val cs = chiitoiScore(counts)
            val ts = tanyaoScore(counts)
            val fs = flushScore(counts)
            val ds = doraScore(counts, doraIndicators)
            val ukeireWeighted = ukeire.toDouble() * speedWeight

            // 综合评分: 越低越好 (Python式tuple比较)
            // 权重: shanten > ukeire(×巡目) > waitQuality > chiitoi > tanyao > flush > dora
            val compositeScore = afterS * 1_000_000.0
                - ukeireWeighted * 10_000.0
                - wq * 1_000.0
                - cs * 0.5 * 100.0
                - ts * 0.3 * 100.0
                - fs * 0.2 * 100.0
                - ds * 100.0

            results.add(DiscardAdvice(
                tile = tile,
                tileName = Tiles.name(tile),
                shantenAfter = afterS,
                ukeire = ukeire,
                ukeireTiles = ukeireTiles,
                deltaShanten = afterS - currentS,
                compositeScore = compositeScore,
                ukeireWeighted = ukeireWeighted,
                waitQuality = wq,
                chiitoiScore = cs,
                tanyaoScore = ts,
                flushScore = fs,
                doraScore = ds
            ))
        }

        // 按综合评分排序
        results.sortBy { it.compositeScore }
        return results
    }

    // ═══════════════════════════════════════════
    // 多因子评分函数 (移植自 RiichiAI)
    // ═══════════════════════════════════════════

    /** 把牌ID数组转为34元素计数数组 */
    private fun toCounts34(hand: IntArray): IntArray {
        val counts = IntArray(34)
        for (t in hand) if (t in 0..33) counts[t]++
        return counts
    }

    /**
     * 听牌质量: 两面+3, 边张-1
     * 只检查数牌(0~26)
     */
    private fun waitQuality(counts34: IntArray): Double {
        var score = 0.0
        for (i in 0 until 27) {
            val cnt = counts34[i]
            if (cnt <= 0) continue
            val mod = i % 9
            // 两面: i, i+1, i+2 都有牌
            if (mod <= 6 && counts34[i + 1] > 0 && counts34[i + 2] > 0) {
                score += 3.0
            }
            // 边张罚分
            if (mod == 0 || mod == 8) {
                score -= 1.0
            }
        }
        return score
    }

    /**
     * 七对子评分: 每对×2
     */
    private fun chiitoiScore(counts34: IntArray): Double {
        var pairs = 0
        for (cnt in counts34) {
            if (cnt >= 2) pairs++
        }
        return (pairs * 2).toDouble()
    }

    /**
     * 断幺九评分: 幺九牌-2, 中张牌+1
     */
    private fun tanyaoScore(counts34: IntArray): Double {
        var score = 0.0
        for (i in 0 until 34) {
            if (counts34[i] <= 0) continue
            if (i >= 27 || i % 9 == 0 || i % 9 == 8) {
                // 字牌 / 幺九牌
                score -= 2.0
            } else {
                // 中张牌 (2~8)
                score += 1.0
            }
        }
        return score
    }

    /**
     * 清一色评分: 最多花色张数×2
     */
    private fun flushScore(counts34: IntArray): Double {
        val manSum = sumCards(counts34, 0, 8)
        val pinSum = sumCards(counts34, 9, 17)
        val souSum = sumCards(counts34, 18, 26)
        return maxOf(manSum, pinSum, souSum) * 2.0
    }

    /**
     * 宝牌评分: 每张宝牌+3
     */
    private fun doraScore(counts34: IntArray, doraIndicators: IntArray): Double {
        if (doraIndicators.isEmpty()) return 0.0
        // 宝牌指示牌 → 宝牌映射 (同花色+1, 字牌按顺序)
        var score = 0.0
        for (indicator in doraIndicators) {
            val doraTile = doraNext(indicator)
            if (doraTile in 0..33) {
                score += counts34[doraTile] * 3.0
            }
        }
        return score
    }

    private fun sumCards(counts34: IntArray, from: Int, to: Int): Int {
        var sum = 0
        for (i in from..to) sum += counts34[i]
        return sum
    }

    /**
     * 宝牌指示牌 → 实际宝牌
     * 数牌: 同花色+1 (9→1)
     * 字牌: 东南西北白发 → 循环
     */
    private fun doraNext(indicator: Int): Int {
        if (indicator < 27) {
            // 数牌: 取模9的下一个
            val suit = indicator / 9
            val num = indicator % 9
            return suit * 9 + ((num + 1) % 9)
        } else {
            // 字牌: 東南西北白發中 顺序=27~33, 映射到 0~6
            val order = indicator - 27
            return 27 + ((order + 1) % 7)
        }
    }

    // ═══════════════════════════════════════════

    private fun calcUkeire(
        hand: IntArray,
        visible: IntArray,
        currentS: Int
    ): Pair<Int, List<Pair<Int, Int>>> {
        val available = IntArray(34) { 4 }

        for (t in hand) available[t]--
        for (t in visible) if (t in 0..33) available[t]--

        val ukeireTiles = mutableListOf<Pair<Int, Int>>()
        var totalUkeire = 0

        for (t in 0 until 34) {
            if (available[t] <= 0) continue

            val testHand = hand + t
            val newS = Shanten.calculate(testHand).shanten

            if (currentS > 0) {
                // 非听牌: 改善向听
                if (newS < currentS) {
                    totalUkeire += available[t]
                    ukeireTiles.add(Pair(t, available[t]))
                }
            } else {
                // 听牌: 只算和了牌
                if (newS <= -1) {
                    totalUkeire += available[t]
                    ukeireTiles.add(Pair(t, available[t]))
                }
            }
        }

        return Pair(totalUkeire, ukeireTiles)
    }

    fun formatAdvice(advice: List<DiscardAdvice>, topN: Int = 5): String {
        return buildString {
            for ((i, a) in advice.withIndex()) {
                if (i >= topN) break
                val tag = if (i == 0) "★" else "  "
                val delta = when {
                    a.deltaShanten < 0 -> "向听${a.deltaShanten}"
                    a.deltaShanten == 0 -> "向听不变"
                    else -> "向听+${a.deltaShanten}"
                }
                appendLine("$tag 切 ${a.tileName} | $delta | 进张${a.ukeire}枚")
                if (a.ukeireTiles.isNotEmpty()) {
                    val names = a.ukeireTiles.take(8).joinToString(", ") {
                        "${Tiles.name(it.first)}×${it.second}"
                    }
                    appendLine("   进张: $names")
                }

                // v7.0 多因子明细 (阈值>0才显示)
                val details = mutableListOf<String>()
                if (a.waitQuality != 0.0) details.add("听牌质量${String.format("%+.1f", a.waitQuality)}")
                if (a.chiitoiScore > 0.0) details.add("七对子${String.format("%.0f", a.chiitoiScore)}")
                if (a.tanyaoScore != 0.0) details.add("断幺九${String.format("%+.1f", a.tanyaoScore)}")
                if (a.flushScore > 0.0) details.add("清一色${String.format("%.0f", a.flushScore)}")
                if (a.doraScore > 0.0) details.add("宝牌+${String.format("%.0f", a.doraScore)}")
                if (details.isNotEmpty()) {
                    appendLine("   因: ${details.joinToString(", ")}")
                }
            }
        }
    }
}
