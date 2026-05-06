package com.mahjong.assistant.engine

/**
 * 放铳率/防御分析器 v1.0
 *
 * 输入: 手牌(14张) + 可选河底数据
 * 输出: 每张手牌的安全评级 (0.0=绝对安全 ~ 1.0=极高危)
 *
 * 计算: 基础铳率 × 巡目系数 × 剩余枚数 × 筋牌 × 壁牌 × 立直
 */
object DefenseAnalyzer {

    // ═══════ 天凤位统计: 34牌基础放铳率 (被切出后在和了牌中的出现频率) ═══════
    // 值域 0.010~0.150, 归一化到 0~1
    private val baseDanger = floatArrayOf(
        // 萬子 1m    2m    3m    4m    5m    6m    7m    8m    9m
        0.032f, 0.019f, 0.015f, 0.026f, 0.041f, 0.028f, 0.023f, 0.020f, 0.030f,
        // 筒子 1p    2p    3p    4p    5p    6p    7p    8p    9p
        0.030f, 0.025f, 0.021f, 0.028f, 0.082f, 0.032f, 0.026f, 0.023f, 0.031f,
        // 索子 1s    2s    3s    4s    5s    6s    7s    8s    9s
        0.042f, 0.022f, 0.018f, 0.029f, 0.045f, 0.030f, 0.024f, 0.020f, 0.030f,
        // 字牌 東    南    西    北    白    発    中
        0.070f, 0.060f, 0.050f, 0.040f, 0.060f, 0.080f, 0.075f
    )

    // ═══════ 结果数据结构 ═══════
    data class SafetyRating(
        val tileId: Int,
        val tileName: String,
        val dangerScore: Float,             // 0.0 安全 ~ 1.0+ 高危
        val dangerLevel: DangerLevel,
        val reasons: List<String>           // 判断依据
    )

    enum class DangerLevel {
        SAFE,       // ≤0.3  绿色
        LOW,        // ≤0.5  浅绿
        MEDIUM,     // ≤0.7  黄色
        HIGH,       // ≤1.0  橙色
        CRITICAL    // >1.0  红色
    }

    // ═══════ 河底数据 (后续由 scanRiverArea 填充) ═══════
    data class RiverState(
        val visibleCounts: IntArray,         // 34牌: 各已见几张 (河底+副露+宝牌表示)
        val opponentDiscards: List<Int>,     // 所有对手舍牌 flat list
        val isRiichi: Boolean = false,       // 是否有对手立直
        val turnEstimate: Int = 8            // 估计巡目 (手牌减少→巡目推进)
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is RiverState) return false
            return visibleCounts.contentEquals(other.visibleCounts) &&
                    opponentDiscards == other.opponentDiscards &&
                    isRiichi == other.isRiichi &&
                    turnEstimate == other.turnEstimate
        }

        override fun hashCode(): Int {
            var result = visibleCounts.contentHashCode()
            result = 31 * result + opponentDiscards.hashCode()
            result = 31 * result + isRiichi.hashCode()
            result = 31 * result + turnEstimate
            return result
        }
    }

    // ═══════ 主入口 ═══════

    /**
     * 分析手牌中每张牌的放铳危险度
     * @param hand 14张手牌
     * @param river 河底数据, null=基础模式(仅手牌内筋)
     * @return 按危险度排序的每张牌评级
     */
    fun analyze(hand: IntArray, river: RiverState? = null): List<SafetyRating> {
        val results = mutableListOf<SafetyRating>()

        // 巡目系数
        val turnFactor = turnFactor(hand.size, river?.turnEstimate ?: 8)

        for (tileId in hand.distinct()) {
            val reasons = mutableListOf<String>()
            var danger = baseDanger[tileId]

            // 1. 巡目系数
            danger *= turnFactor
            if (turnFactor > 1.3f) reasons.add("终盘×${"%.1f".format(turnFactor)}")

            // 2. 剩余枚数修正 (有河底数据时启用)
            if (river != null && river.visibleCounts[tileId] > 0) {
                val seen = river.visibleCounts[tileId]
                val remaining = 4 - seen
                val remainFactor = remaining / 4.0f
                danger *= remainFactor
                if (seen >= 3) reasons.add("残${remaining}枚")
                if (seen >= 4) reasons.add("絶対安全(0枚)")
            }

            // 3. 现物检测 (对手刚切的牌 = 绝对安全)
            if (river != null && river.opponentDiscards.contains(tileId)) {
                danger = 0.01f  // 极微风险, 仅剩抢杠/双碰极小概率
                reasons.add("现物")
            }

            // 4. 早外检测 (序盘对手切过同花色 → 较安全)
            if (danger > 0.01f && river != null && tileId < 27) {
                val suit = tileId / 9
                val earlyDiscards = river.opponentDiscards.take(6)
                val hasEarlySameSuit = earlyDiscards.any { it / 9 == suit && it != tileId }
                if (hasEarlySameSuit) {
                    danger *= 0.2f
                    reasons.add("早外")
                }
            }

            // 5. 筋牌检测
            val sujiSafe = isSujiSafe(tileId, hand, river?.opponentDiscards ?: emptyList())
            if (sujiSafe != null && danger > 0.01f) {
                danger *= 0.15f  // 筋牌极为安全
                reasons.add("筋牌(${Tiles.name(sujiSafe)})")
            }

            // 6. 无筋生牌 (有河底时, 无筋+未见+非字牌 = 危险)
            if (river != null && tileId < 27 && !reasons.any { it.contains("筋牌") || it.contains("现物") }) {
                val isSuji = isSujiSafe(tileId, hand, river.opponentDiscards) != null
                val isSeen = river.visibleCounts[tileId] > 0
                if (!isSuji && !isSeen) {
                    danger *= 1.5f
                    reasons.add("无筋生牌")
                }
            }

            // 7. 壁牌检测
            val wallFactor = wallFactor(tileId, river?.visibleCounts)
            if (wallFactor != null && wallFactor < 1.0f) {
                danger *= wallFactor
                reasons.add("壁牌")
            }

            // 5. 立直后危险牌加倍
            if (river?.isRiichi == true) {
                val isSuitTile = tileId < 27
                if (isSuitTile && danger > 0.3f) {
                    danger *= 1.5f
                    reasons.add("立直")
                }
                if (!isSuitTile && river.visibleCounts[tileId] == 0) {
                    danger *= 2.0f  // 立直后未现字牌极危
                    reasons.add("立直+生字")
                }
            }

            // 6. 赤5/dora特殊标记
            if (tileId == 4 || tileId == 13 || tileId == 22) { // 赤5
                danger *= 1.3f
                reasons.add("赤dora")
            }

            // 钳入范围
            danger = danger.coerceIn(0.0f, 2.0f)

            results.add(SafetyRating(
                tileId = tileId,
                tileName = Tiles.name(tileId),
                dangerScore = danger,
                dangerLevel = when {
                    danger <= 0.3f -> DangerLevel.SAFE
                    danger <= 0.5f -> DangerLevel.LOW
                    danger <= 0.7f -> DangerLevel.MEDIUM
                    danger <= 1.0f -> DangerLevel.HIGH
                    else -> DangerLevel.CRITICAL
                },
                reasons = reasons
            ))
        }

        return results.sortedBy { it.dangerScore }
    }

    /** 基础模式：无河底, 仅手牌内筋+基础铳率 */
    fun analyzeBasic(hand: IntArray): List<SafetyRating> = analyze(hand, null)

    // ═══════ 巡目系数 ═══════

    /** 根据巡目估算放铳倍率: 序盘0.5, 中盘1.0, 终盘2.0 */
    private fun turnFactor(handSize: Int, turnEstimate: Int): Float {
        val turn = if (handSize >= 13) turnEstimate
            else 6 + (13 - handSize) * 2  // 手牌越少巡目越大
        return when {
            turn <= 6 -> 0.6f   // 序盘: 对手听牌概率低
            turn <= 9 -> 1.0f   // 中盘
            turn <= 12 -> 1.5f  // 终盘
            else -> 2.0f        // 流局边缘
        }
    }

    // ═══════ 筋牌 ═══════

    /**
     * 判断切 tileId 是否是筋牌
     * 筋牌逻辑: 对手打过X → 同花色 X-3 和 X+3 安全
     * 返回: 对手打过的筋牌id, null=不是筋牌
     */
    private fun isSujiSafe(tileId: Int, hand: IntArray, opponentDiscards: List<Int>): Int? {
        if (tileId >= 27) return null // 字牌没有筋

        val suit = tileId / 9        // 0=m, 1=p, 2=s
        val num = tileId % 9 + 1    // 1-9
        val base = suit * 9

        // 对手打过 num+3 的牌 (同花色) → 切 num 是筋牌
        val upperSuji = base + (num + 2)
        if (num <= 6 && upperSuji < base + 9 &&
            (opponentDiscards.contains(upperSuji) || hand.contains(upperSuji))) {
            return upperSuji
        }

        // 对手打过 num-3 的牌 → 切 num 是筋牌
        val lowerSuji = base + (num - 4)
        if (num >= 4 && lowerSuji >= base &&
            (opponentDiscards.contains(lowerSuji) || hand.contains(lowerSuji))) {
            return lowerSuji
        }

        return null
    }

    // ═══════ 壁牌 ═══════

    /**
     * 计算壁牌安全系数
     * 某牌4张全见 → 壁产生
     * 壁的邻牌: 外侧较安全(只能双碰), 内侧更安全(两面被封锁)
     * @return null=不是壁影响范围, <1.0=壁牌安全系数
     */
    private fun wallFactor(tileId: Int, visibleCounts: IntArray?): Float? {
        if (tileId >= 27 || visibleCounts == null) return null

        val num = tileId % 9 + 1
        val suitBase = (tileId / 9) * 9

        // 检查左侧壁: num-1 的牌全见
        if (num > 1 && visibleCounts[suitBase + num - 2] >= 4) {
            return 0.5f  // 被壁保护, 较安全
        }

        // 检查右侧壁: num+1 的牌全见
        if (num < 9 && visibleCounts[suitBase + num] >= 4) {
            return 0.5f
        }

        // 双壁 (两侧都壁): 极安全, 只能听单骑
        if (num > 1 && num < 9 &&
            visibleCounts[suitBase + num - 2] >= 4 &&
            visibleCounts[suitBase + num] >= 4) {
            return 0.2f
        }

        return null
    }

    // ═══════ 辅助 ═══════

    /** 获取单张牌的危险度字符串简写 */
    fun safetyEmoji(level: DangerLevel): String = when (level) {
        DangerLevel.SAFE -> "✅"
        DangerLevel.LOW -> "🟢"
        DangerLevel.MEDIUM -> "🟡"
        DangerLevel.HIGH -> "⚠️"
        DangerLevel.CRITICAL -> "🔴"
    }

    /** 获取手牌中指定牌的危险度等级 */
    fun dangerOf(tileId: Int, ratings: List<SafetyRating>): SafetyRating? {
        return ratings.find { it.tileId == tileId }
    }
}
