package com.mahjong.assistant.engine

/**
 * 向听数计算 — 一般形/七对子/国士无双
 *
 * 公式法: shanten = 8 - 2*complete - partial - hasPair
 * 其中 partial 是「不含雀头的最优不重叠搭子数」
 */
object Shanten {

    data class Result(
        val shanten: Int,     // -1=和了, 0=听牌, 1=一向听...
        val type: String,     // "normal" / "chiitoi" / "kokushi"
        val tenpaiTiles: List<Int>
    )

    fun calculate(hand: IntArray): Result {
        val counts = IntArray(34)
        for (t in hand) counts[t]++

        val normal = calcNormal(counts)
        val chiitoi = calcChiitoi(counts)
        val kokushi = calcKokushi(counts)

        return listOf(normal, chiitoi, kokushi).minByOrNull { it.shanten }!!
    }

    // ─── 一般形 ───

    private fun calcNormal(counts: IntArray): Result {
        var bestShanten = 8

        for (pairTile in 0 until 34) {
            if (counts[pairTile] >= 2) {
                val c = counts.copyOf()
                c[pairTile] -= 2
                val (complete, partial) = optimalDecomposition(c)
                val s = 8 - 2 * complete - partial - 1  // -1 for pair
                bestShanten = minOf(bestShanten, s)
            } else if (counts[pairTile] == 1) {
                val c = counts.copyOf()
                c[pairTile] -= 1
                val (complete, partial) = optimalDecomposition(c)
                val s = 8 - 2 * complete - partial  // no pair
                bestShanten = minOf(bestShanten, s)
            }
        }

        return Result(maxOf(-1, bestShanten), "normal", emptyList())
    }

    // ─── 最优分解 (完成面子 + 搭子) ───

    data class Block(val type: BlockType, val tiles: IntArray, val value: Int)

    enum class BlockType { COMPLETE, PARTIAL }

    private fun enumerateBlocks(counts: IntArray): List<Block> {
        val blocks = mutableListOf<Block>()

        // 刻子 (complete)
        for (i in 0 until 34) {
            if (counts[i] >= 3) {
                blocks.add(Block(BlockType.COMPLETE, intArrayOf(i, i, i), 2))
            }
        }

        // 顺子 (complete)
        for (suitStart in intArrayOf(0, 9, 18)) {
            for (i in suitStart until suitStart + 7) {
                if (counts[i] >= 1 && counts[i + 1] >= 1 && counts[i + 2] >= 1) {
                    blocks.add(Block(BlockType.COMPLETE, intArrayOf(i, i + 1, i + 2), 2))
                }
            }
        }

        // 对子 (partial, 可变成刻子)
        for (i in 0 until 34) {
            if (counts[i] >= 2) {
                blocks.add(Block(BlockType.PARTIAL, intArrayOf(i, i), 1))
            }
        }

        // 搭子 (partial) — 两面/坎张/边张
        for (suitStart in intArrayOf(0, 9, 18)) {
            for (i in suitStart until suitStart + 8) {
                if (i + 1 < suitStart + 9 && counts[i] >= 1 && counts[i + 1] >= 1) {
                    blocks.add(Block(BlockType.PARTIAL, intArrayOf(i, i + 1), 1))
                }
                if (i + 2 < suitStart + 9 && counts[i] >= 1 && counts[i + 2] >= 1) {
                    blocks.add(Block(BlockType.PARTIAL, intArrayOf(i, i + 2), 1))
                }
            }
        }

        return blocks
    }

    private fun optimalDecomposition(counts: IntArray): Pair<Int, Int> {
        val blocks = enumerateBlocks(counts)
        if (blocks.isEmpty()) return Pair(0, 0)

        // DFS找最优不重叠块集
        var bestComplete = 0
        var bestPartial = 0
        var bestTotal = 0

        fun dfs(idx: Int, complete: Int, partial: Int, used: BooleanArray) {
            val total = 2 * complete + partial
            if (total > bestTotal) {
                bestTotal = total
                bestComplete = complete
                bestPartial = partial
            }
            if (idx >= blocks.size) return

            val block = blocks[idx]
            // 检查是否与已选重叠
            var canUse = true
            for (t in block.tiles) {
                if (used[t]) { canUse = false; break }
            }

            // 选这个块
            if (canUse) {
                val newUsed = used.copyOf()
                for (t in block.tiles) newUsed[t] = true
                if (block.type == BlockType.COMPLETE) {
                    dfs(idx + 1, complete + 1, partial, newUsed)
                } else {
                    dfs(idx + 1, complete, partial + 1, newUsed)
                }
            }

            // 不选
            dfs(idx + 1, complete, partial, used)
        }

        dfs(0, 0, 0, BooleanArray(34))
        return Pair(bestComplete, bestPartial)
    }

    // ─── 七对子 ───

    private fun calcChiitoi(counts: IntArray): Result {
        var pairs = 0
        for (c in counts) pairs += c / 2
        val shanten = maxOf(0, 6 - pairs)
        return Result(shanten, "chiitoi", emptyList())
    }

    // ─── 国士无双 ───

    private fun calcKokushi(counts: IntArray): Result {
        val yaochu = intArrayOf(0, 8, 9, 17, 18, 26, 27, 28, 29, 30, 31, 32, 33)
        var hasPair = false
        var missing = 0
        for (t in yaochu) {
            when {
                counts[t] >= 2 -> hasPair = true
                counts[t] == 0 -> missing++
            }
        }
        val shanten = if (hasPair) missing else missing + 1
        return Result(shanten, "kokushi", emptyList())
    }

    // ─── 听牌待牌计算 ───

    fun tenpaiWaits(counts: IntArray): List<Int> {
        val waits = mutableListOf<Int>()
        for (t in 0 until 34) {
            val test = counts.copyOf()
            test[t]++
            if (calcNormal(test).shanten <= -1) {
                waits.add(t)
            }
        }
        return waits
    }
}
