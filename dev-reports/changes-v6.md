# ScreenCapture.kt v6.0 改动说明

**日期**: 2026-05-07
**文件**: `app/src/main/java/com/mahjong/assistant/capture/ScreenCapture.kt`
**行数变化**: 1210 → 1132 行 (-78行)

## 目标

移除 ScreenCapture.kt 中所有 YOLO 运行时代码，纯模板匹配做主识别。YoloDetector 类本身保留（采集器要用）。

## 具体改动

### 1. recognizeImpl() - 删除 YOLO 检测块 (原 343-372 行)
- 删除了 YOLO 全图端到端检测代码块（`YoloDetector.detect()` 调用 + 交叉验证逻辑）
- 注释从 `模板匹配 (降级方案)` 改为 `模板匹配 (主方案)`
- 现在直接进入模板匹配分支（精准定位→副露补充→全图扫描兜底）

### 2. 删除 crossValidateBottom() 函数 (原 448-484 行)
- 该函数用于 YOLO 低置信度结果用模板匹配交叉验证
- 参数类型含 `YoloDetector.Detection`，不再需要

### 3. 删除 categorizeYoloDetections() 函数 (原 506-556 行)
- 该函数将 YOLO 全图检测结果按空间位置分类为手牌/四家河底
- 同时删除了函数内联的 `lastFourRiver` 赋值逻辑

### 4. 重构 RiverRegion.contains (原 425-431 行)
- 参数从 `YoloDetector.Detection` 改为普通坐标 `(cx: Int, cy: Int)`
- 简化实现为单行表达式

### 5. 删除 lastYoloDetections 变量 (原 498 行)
- 移除 `@Volatile private var lastYoloDetections: List<YoloDetector.Detection> = emptyList()`
- 保留 `lastFourRiver` 缓存变量（combinedRiver() 仍需要）

### 6. 重写 scanAllRivers() (原 1165-1193 行)
- 不再依赖 YOLO 缓存 `lastYoloDetections`
- 改为：对四方牌河区域分别裁剪→CLAHE增强→模板匹配扫描→返回 FourRiverState
- 若 screenshot 为 null 则返回空 FourRiverState
- 参考现有 scanMeldArea 的裁剪→模板匹配做法
- 每个区域用 34 种模板在 7 级 scale(0.6~1.2) 下匹配，阈值 0.65
- 结果缓存到 lastFourRiver

### 7. 杂项清理
- 更新 initDiagnostic 日志：移除 "YOLO+" 前缀，改为纯模板描述
- 更新文件头版本号：v5.0 → v6.0

## 验证结果

- ✅ 文件中 `YoloDetector` 引用数: 0（不含注释）
- ✅ `YOLO` 关键字仅在注释 `无 YOLO 依赖` 中出现一次
- ✅ `fromCounts()` 工具函数保留（被 scanAllRivers/combinedRiver 使用）
- ✅ `lastFourRiver` 缓存保留
- ✅ `combinedRiver()` 函数保持不变
- ✅ `riverRegions` 坐标定义保持不变
