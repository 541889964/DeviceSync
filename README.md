# DeviceSync

手机 + 平板局域网 P2P 状态同步。

## 本地构建

本项目不含 `gradle-wrapper.jar`。两种方式补全：

1. 用 Android Studio 直接打开项目，IDE 会自动生成 wrapper。
2. 命令行（需本机已安装 Gradle 8.9+）：
gradle wrapper --gradle-version 8.9
./gradlew :app:assembleDebug

text

## GitHub Actions

工作流使用 `gradle/actions/setup-gradle@v4` 直接调用 `gradle`，不依赖 wrapper jar。
推送后自动构建，产物在 Actions run 的 `devicesync-apk` Artifacts。

## 保活

- 前台服务 `connectedDevice`
- START_STICKY + onTaskRemoved Alarm
- WorkManager 15 分钟兜底
- PARTIAL_WAKE_LOCK + WIFI_MODE_FULL_HIGH_PERF 双锁
- 首次打开后请在应用内点「保活设置」授予电池优化豁免和厂商自启动白名单
