package com.mahjong.assistant.engine

/**
 * 牌効率分析 — 最优切牌推荐
 */
object Efficiency {

    data class DiscardAdvice(
        val tile: Int,           // 推荐切的牌ID
        val tileName: String,    // 牌名
        val shantenAfter: Int,   // 切后向听数
        val ukeire: Int,         // 有效进张数
        val ukeireTiles: List<Pair<Int, Int>>, // (牌ID, 剩余枚数)
        val deltaShanten: Int    // 向听变化 (负=改善)
    )

    /** 13张手牌分析: 向听数 + 有效进张 */
    fun analyze13(hand: IntArray, visible: IntArray = IntArray(0)): Pair<Int, List<Pair<Int, Int>>> {
        val currentS = Shanten.calculate(hand).shanten
        val (ukeire, tiles) = calcUkeire(hand, visible, currentS)
        return Pair(currentS, tiles)
    }

    /**
     * 分析14张手牌的切牌建议
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
            }
        }
    }
}
