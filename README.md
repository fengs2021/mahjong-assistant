# 雀魂助手 (Mahjong Soul Assistant)

绿色悬浮窗出牌辅助工具，支持**本地模板匹配识别**和**手动输入**两种模式。

## 功能

- 🟢 绿色悬浮窗，置顶显示，可拖动
- 📸 截取屏幕 → OpenCV 模板匹配 → 自动识别手牌
- ✏️ 手动点牌输入 → 实时分析
- 🧠 向听数计算 (一般形/七对子/国士无双)
- 📊 切牌推荐 + 进张分析 + 役种提示
- 📷 模板校准模式 (逐张截取34种牌)

## 技术栈

- Kotlin + Jetpack
- OpenCV Android SDK 4.9 (模板匹配)
- MediaProjection API (截屏)
- WindowManager (悬浮窗)
- Custom Gradle build

## 构建

1. 下载 [OpenCV Android SDK 4.9](https://opencv.org/releases/)
2. 解压后复制:
   - `sdk/java/src/org/opencv/` → `app/src/main/java/org/opencv/`
   - `sdk/native/libs/arm64-v8a/libopencv_java4.so` → `app/src/main/jniLibs/arm64-v8a/`
   - `sdk/native/libs/armeabi-v7a/libopencv_java4.so` → `app/src/main/jniLibs/armeabi-v7a/`
3. Android Studio 打开项目 → Build APK

## 使用

1. 安装APK → 授权悬浮窗
2. **首次**: 点「📷 校准模板」→ 逐张截取34种牌
3. **日常**: 悬浮窗 → 📸截取分析 或 ✏手动输入
4. 浮窗显示向听数 + 最优切牌 + 备选方案
