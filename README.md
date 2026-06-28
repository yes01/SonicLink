# SonicLink

SonicLink 是一个面向 Android 设备控制和信息采集的插件 APK。应用启动后可以拉起前台管理服务，并通过本地 TCP Socket 接收外部控制端指令，用于获取设备应用列表、Wi-Fi 信息，以及配合输入法、音频、触控等插件能力完成远程设备操作。

## 项目结构

- `app`：Android 应用模块，包含主入口、前台服务、输入法服务、音频插件、触控插件、应用列表和 Wi-Fi 信息采集能力。
- `lib_socketmanager`：Socket 通信库，封装 TCP Server、TCP Client、粘包处理、编解码和连接状态管理。

## 环境要求

- Android Studio 或命令行 Android 构建环境
- JDK 17
- Android SDK 34
- 可用的 Android 设备或模拟器

## 构建

在项目根目录执行：

```bash
./gradlew assembleDebug
```

构建产物位于：

```text
app/build/outputs/apk/debug/app-debug.apk
```

Windows 环境可使用：

```bat
gradlew.bat assembleDebug
```

## 安装与运行

安装 debug 包：

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

启动普通入口：

```bash
adb shell am start -n org.cloud.sonic.android/.MainActivity
```

启动管理服务入口：

```bash
adb shell am start -n org.cloud.sonic.android/.SonicServiceActivity
```

`SonicServiceActivity` 会启动 `SonicManagerServiceV2`，该服务默认监听设备端 TCP 端口 `2334`。如果需要从电脑侧连接，可先做端口转发：

```bash
adb forward tcp:2222 tcp:2334
```

随后连接本机 `127.0.0.1:2222` 即可向设备端服务发送指令。当前服务支持的基础指令包括：

```text
action_get_all_app_info
action_get_all_wifi_info
org.cloud.sonic.android.STOP
```

## 测试

运行本地单元测试：

```bash
./gradlew test
```

运行 Android 设备测试：

```bash
./gradlew connectedAndroidTest
```
