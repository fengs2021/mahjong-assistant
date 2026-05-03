package com.mahjong.assistant.engine

/**
 * 日麻牌定义 — 34种牌，整数0-33
 */
object Tiles {
    // 花色范围
    const val MANZU_START = 0
    const val PINZU_START = 9
    const val SOUZU_START = 18
    const val KAZE_START = 27
    const val SANGEN_START = 31

    // 牌名
    val NAMES = buildMap {
        for (i in 0 until 9) {
            put(i, "${i + 1}萬")
            put(i + 9, "${i + 1}筒")
            put(i + 18, "${i + 1}索")
        }
        put(27, "東"); put(28, "南"); put(29, "西"); put(30, "北")
        put(31, "白"); put(32, "発"); put(33, "中")
    }

    fun name(tileId: Int): String = NAMES[tileId] ?: "?"

    fun isYaochu(tileId: Int): Boolean = when {
        tileId >= 27 -> true
        tileId == 0 || tileId == 8 -> true   // 1m, 9m
        tileId == 9 || tileId == 17 -> true  // 1p, 9p
        tileId == 18 || tileId == 26 -> true // 1s, 9s
        else -> false
    }

    fun suitOf(tileId: Int): Char = when {
        tileId < 9 -> 'm'
        tileId < 18 -> 'p'
        tileId < 27 -> 's'
        else -> 'z'
    }

    /**
     * 解析手牌字符串: "123m456p789s11z" → IntArray
     */
    fun parse(text: String): IntArray {
        val result = mutableListOf<Int>()
        var i = 0
        while (i < text.length) {
            if (text[i].isDigit()) {
                val numStr = buildString {
                    while (i < text.length && text[i].isDigit()) {
                        append(text[i]); i++
                    }
                }
                if (i < text.length) {
                    val suit = text[i].lowercaseChar(); i++
                    val base = when (suit) {
                        'm' -> 0; 'p' -> 9; 's' -> 18; 'z' -> 27; else -> continue
                    }
                    for (ch in numStr) {
                        result.add(base + (ch - '1'))
                    }
                }
            } else { i++ }
        }
        return result.sorted().toIntArray()
    }

    /** 紧凑牌型: "123m 456p 789s 東南西北" */
    fun toCompactString(tiles: IntArray): String {
        val sorted = tiles.sortedArray()
        val sb = StringBuilder()
        for (suit in charArrayOf('m', 'p', 's', 'z')) {
            val ofSuit = sorted.filter { suitOf(it) == suit }
            if (ofSuit.isEmpty()) continue
            if (sb.isNotEmpty()) sb.append(' ')
            if (suit == 'z') {
                sb.append(ofSuit.joinToString("") { name(it) })
            } else {
                sb.append(ofSuit.map {
                    when (suit) { 'm' -> it + 1; 'p' -> it - 8; 's' -> it - 17; else -> 0 }
                }.sorted().joinToString(""))
                sb.append(suit)
            }
        }
        return sb.toString()
    }

    fun toDisplayString(tiles: IntArray): String {
        val groups = mutableMapOf<Char, MutableList<Int>>()
        for (t in tiles) {
            val s = suitOf(t)
            groups.getOrPut(s) { mutableListOf() }.add(t)
        }
        return buildString {
            for (suit in charArrayOf('m', 'p', 's', 'z')) {
                val tiles = groups[suit] ?: continue
                if (suit == 'z') {
                    // 字牌直接显示汉字: 東南西北白発中
                    append(tiles.sorted().joinToString("") { name(it) })
                } else {
                    val nums = tiles.map {
                        when (suit) {
                            'm' -> it + 1; 'p' -> it - 8; 's' -> it - 17; else -> -1
                        }
                    }.sorted().joinToString("")
                    append(nums).append(suit)
                }
            }
        }
    }
}
