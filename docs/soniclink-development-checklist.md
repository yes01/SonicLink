# SonicLink 远程控制开发清单

## 目标

将 SonicLink 开发成一个安卓端 Agent。手机安装 APK 后，在与 Mac 部署的测试平台处于同一局域网的情况下，主动连接平台，实现投屏、远程控制、多设备管理等能力。正常使用阶段不依赖 root、系统签名或 ADB。

目标能力：

- APK 主动向平台注册设备并保持心跳。
- 通过 `AccessibilityService.dispatchGesture` 实现全局点击、滑动、多指手势。
- 通过无障碍全局动作实现返回、主页、多任务、通知栏等系统操作。
- 通过 `MediaProjection + MediaCodec` 实现屏幕采集和视频编码推流。
- 支持多台手机同时在线。
- 支持断线重连、授权引导、运行状态展示和错误提示。

## 当前基础

- 当前项目结构仍接近 `sonic-android-apk`。
- 已有 `SonicManagerServiceV2`，手机端监听 TCP `2334`，但只支持有限插件命令。
- 已有旧触控插件使用隐藏 `InputManager` 反射和 local socket，不适合普通安装包方案。
- 已有音频服务使用 `MediaProjection`，但还没有屏幕视频采集和投流模块。
- `app/build.gradle` 当前还没有 OkHttp/WebSocket 依赖。
- `AndroidManifest.xml` 当前还没有自定义 `AccessibilityService` 声明。

## 阶段一：项目基础

- [x] 确定应用名称、包名、版本号策略，必要时从 Sonic 原包名中独立出来。
- [x] 决定旧 Sonic TCP 服务是保留、废弃，还是标记为 legacy 模块（当前保留为非导出 legacy 服务）。
- [x] 增加网络客户端依赖，建议使用 OkHttp WebSocket。
- [x] 梳理协程依赖版本，避免后续前台服务和 WebSocket 使用时版本混乱。
- [x] 定义统一配置模型：
  - [x] 平台服务器地址。
  - [x] HTTP API 地址。
  - [x] WebSocket 地址。
  - [x] 配对 token 或设备 token。
  - [x] 设备显示名称。
  - [x] 是否开机/启动后自动连接。
- [x] 使用 SharedPreferences 或 DataStore 持久化配置。
- [x] 增加简单设置页，用于配置服务器地址和查看连接状态。
- [ ] 如果需要区分测试环境和正式环境，再考虑增加 build flavor。

## 阶段二：权限与初始化引导

- [x] 在 `MainActivity` 中增加初始化检查页面：
  - [x] 是否已配置平台地址。
  - [x] 是否已开启无障碍服务。
  - [x] 是否已授予或可请求屏幕录制权限。
  - [x] 前台服务是否运行中。
  - [x] 是否已连接平台。
- [x] 增加按钮跳转到系统无障碍设置页。
- [x] 检测 SonicLink 无障碍服务是否已开启。
- [x] 增加 Android 13+ 侧载安装时“受限设置”的用户引导。
- [x] 增加 MediaProjection 屏幕录制授权流程。
- [x] 增加前台通知，展示连接和投屏状态。
- [x] 通知中提供停止连接/停止投屏入口。
- [ ] 长时间运行需要时再增加 wake lock，避免一开始过度申请。

## 阶段三：无障碍控制服务

- [x] 新建 `SonicLinkAccessibilityService`。
- [x] 新建 `res/xml/sonic_link_accessibility_service.xml`。
- [x] 配置无障碍服务：
  - [x] `android:canPerformGestures="true"`。
  - [x] 如需文本输入或节点读取，配置 `android:canRetrieveWindowContent="true"`。
  - [x] 只订阅必要的事件类型，减少性能和隐私影响。
  - [x] 增加清晰的无障碍服务说明文案。
- [x] 在 `AndroidManifest.xml` 中声明服务，并使用 `android.permission.BIND_ACCESSIBILITY_SERVICE`。
- [x] 增加服务状态管理，让 Agent 连接层能判断无障碍是否可用。
- [x] 实现手势能力：
  - [x] 全局坐标点击。
  - [x] 长按。
  - [x] 指定时长滑动。
  - [x] 拖拽。
  - [x] 多指手势，使用多个 `StrokeDescription`。
  - [x] 手势取消和超时处理。
- [x] 实现系统全局动作：
  - [x] 返回。
  - [x] Home。
  - [x] 最近任务。
  - [x] 通知栏。
  - [x] 快捷设置，视系统支持情况而定。
  - [x] 电源菜单，视系统版本支持情况而定。
- [x] 实现屏幕指标获取：
  - [x] 当前真实显示宽高。
  - [x] 当前旋转方向。
  - [x] 屏幕密度。
  - [x] 必要时记录状态栏、导航栏、刘海区域影响。
- [x] 统一坐标协议：
  - [x] 平台下发绝对物理屏幕坐标。
  - [x] 心跳中上报当前宽高和旋转方向。
  - [x] 对越界坐标返回明确错误。
- [x] 增加控制命令结果回调：
  - [x] 执行成功。
  - [x] 无障碍未开启。
  - [x] 手势被取消。
  - [x] 手势超时。
  - [x] 当前动作不支持。

## 阶段四：设备 Agent 连接

- [x] 新建前台服务 `SonicLinkAgentService`。
- [x] 由 Agent 服务负责维护到 Mac 平台的 WebSocket 长连接。
- [x] 定义统一消息信封：
  - [x] `type`：消息类型。
  - [x] `requestId`：请求 ID。
  - [x] `deviceId`：设备 ID。
  - [x] `timestamp`：时间戳。
  - [x] `payload`：业务数据。
- [x] 实现设备注册消息：
  - [x] 稳定设备 ID。
  - [x] 品牌和型号。
  - [x] Android 版本和 API level。
  - [x] APK 版本。
  - [x] 屏幕宽高、旋转方向、密度。
  - [x] 无障碍是否开启。
  - [x] 投屏权限/投屏会话状态。
  - [x] 电量。
  - [x] WiFi/IP 信息，能获取则上报。
- [x] 实现心跳，建议每 5 秒一次。
- [x] 实现断线重连和退避策略。
- [x] 实现服务端命令处理：
  - [x] `tap`。
  - [x] `long_press`。
  - [x] `swipe`。
  - [x] `multi_touch`。
  - [x] `global_action`。
  - [x] `input_text`。
  - [x] `start_stream`。
  - [x] `stop_stream`。
  - [x] `get_status`。
- [x] 实现命令响应消息。
- [x] 每次连接都进行 token 鉴权。
- [x] 支持单设备同一时间只允许一个用户控制，或支持显式抢占。
- [x] 避免局域网内任意客户端控制手机：
  - [x] 使用 APK 主动连接平台的鉴权 WebSocket。
  - [x] 不在手机端暴露未鉴权 TCP 控制端口。
  - [ ] 如果未来跨网使用，再考虑 TLS。

## 阶段五：屏幕投流

- [x] 新建 `ScreenCaptureActivity`，或复用透明授权 Activity 来请求 MediaProjection 权限。
- [x] 新建 `ScreenStreamService`，或作为 `SonicLinkAgentService` 内部模块。
- [x] 使用 `MediaProjection` 采集屏幕。
- [x] 创建 `VirtualDisplay`。
- [x] 使用 `MediaCodec` 编码 H.264。
- [x] 在视频帧前发送 codec config、SPS、PPS。
- [x] 通过 WebSocket binary frame 或独立流 socket 发送编码数据。
- [x] 定义视频包格式：
  - [x] 编码配置包。
  - [x] 关键帧包。
  - [x] 普通帧包。
  - [x] 时间戳。
  - [x] 宽高和旋转方向。
- [x] 增加码率和帧率配置：
  - [x] 首版默认 720p 或限制最大尺寸。
  - [x] 局域网首版建议 15-30 FPS。
  - [ ] 后续再做自适应码率。
- [x] 处理旋转：
  - [x] 屏幕尺寸变化时重建编码器。
  - [x] 通知平台新的宽高和方向。
- [x] 实现开始投屏和停止投屏命令。
- [x] 停止时正确释放 MediaProjection、VirtualDisplay、MediaCodec。
- [x] 决定音频是否进入首版：
  - [x] 如果不需要，首版不要接入现有音频服务。
  - [ ] 如果需要，后续作为独立音频流补充。

## 阶段六：文本输入

- [x] 决定优先输入方案：
  - [x] 无障碍节点 `ACTION_SET_TEXT`。
  - [ ] 现有自定义输入法。
  - [ ] 剪贴板粘贴兜底。
- [x] 使用无障碍节点检测当前是否有可编辑输入框。
- [x] 实现 `set_text` 命令，在存在可编辑节点时设置文本。
- [x] 当前没有可编辑节点时返回明确失败原因。
- [x] 如无必要，避免默认使用剪贴板方案，减少隐私和兼容风险。
- [x] 视需求支持类按键操作：
  - [x] 回车。
  - [x] 删除。
  - [x] 全选。

## 阶段七：平台后端对接

平台仓库：`/Users/lx/Downloads/workspace/xin_testing_platform`

- [x] 增加 APK Agent 设备字段：
  - [x] `connection_mode`：`adb` 或 `agent`。
  - [x] `agent_device_id`。
  - [x] `agent_status`。
  - [x] `agent_last_seen_at`。
  - [x] `agent_capabilities`。
- [x] 增加 Agent WebSocket 接口：
  - [x] 设备注册。
  - [x] 心跳。
  - [x] 命令响应。
  - [x] 二进制视频帧或投屏会话通道。
- [x] 增加用户控制通道，或复用现有远程控制 API。
- [x] 增加设备会话路由：
  - [x] 用户命令 -> 后端 -> 对应 APK WebSocket。
  - [x] APK 响应 -> 后端 -> 用户会话。
- [x] 复用或扩展现有单用户占用/抢占逻辑。
- [x] 增加视频流转发：
  - [x] APK H.264 -> 后端 -> 前端 WebSocket。
  - [x] 尽量保持与现有 JMuxer 播放链路兼容。
- [x] 增加 API 兼容层，使前端对 ADB 设备和 Agent 设备使用统一远控动作。
- [x] APK 断开后，服务端清理在线状态和投屏会话。
- [x] 增加日志和指标：
  - [x] 命令延迟。
  - [x] 投屏 FPS。
  - [ ] 丢帧数。
  - [x] 重连次数。

## 阶段八：前端对接

平台仓库：`/Users/lx/Downloads/workspace/xin_testing_platform`

- [x] 远程设备列表展示 Agent 连接设备。
- [x] 显示连接方式标签：
  - [x] USB ADB。
  - [x] Wireless ADB。
  - [x] SonicLink Agent。
- [x] 尽量复用现有远程设备页面。
- [x] Agent 设备使用 Agent 投屏 WebSocket，而不是 scrcpy WebSocket。
- [x] 坐标映射继续复用现有逻辑，但屏幕尺寸以 Agent 上报为准。
- [x] 根据 `connection_mode` 路由点击、滑动、按键、文本命令。
- [x] 增加安装和配置引导：
  - [x] 安装 APK。
  - [x] 配置服务器地址。
  - [x] 开启无障碍。
  - [x] 授权屏幕录制。
- [x] 展示实时健康状态：
  - [x] 最后心跳时间。
  - [x] 无障碍是否开启。
  - [x] 投屏权限/会话状态。
  - [x] 电量和网络状态。

## 阶段九：多设备支持

- [x] APK 首次启动生成稳定设备 ID。
- [x] 支持 APK 或平台侧设置设备别名。
- [x] 每台手机维护独立 WebSocket 会话。
- [x] 每台手机的投屏会话按设备 ID 隔离。
- [x] 后端增加容量控制：
  - [x] 最大同时投屏数量。
  - [ ] 带宽估算。
  - [ ] Mac CPU 负载估算。
- [ ] 增加每台设备独立投屏质量配置。
- [ ] 在目标局域网中测试 2 台、4 台、8 台设备同时在线。

## 阶段十：稳定性与边界场景

- [x] 处理 App 被杀和服务重启。
- [x] 处理 WiFi 切换和 IP 变化。
- [x] 处理平台服务重启。
- [ ] 处理无障碍服务运行中被关闭。
- [x] 处理 MediaProjection 授权被撤销。
- [ ] 处理息屏和锁屏。
- [x] 处理投屏和控制过程中旋转屏幕。
- [x] 处理刘海屏、状态栏、导航栏差异。
- [x] 处理 Android 13/14 前台服务限制。
- [x] 处理各厂商 ROM 后台限制。
- [x] 所有阻塞状态都需要明确提示用户。

## 阶段十一：安全与隐私

- [x] 设备注册必须校验配对 token。
- [x] token 本地持久化保存。
- [x] 平台支持吊销设备 token。
- [x] 不记录敏感文本输入内容。
- [x] 远程控制或投屏期间展示常驻通知。
- [x] APK 内提供明确的停止控制按钮。
- [x] 局域网首版可先使用 HTTP/WS，跨网使用前再补 TLS/WSS。
- [ ] 如果未来通过 Google Play 分发，需要提前评估无障碍 API 政策。

## 阶段十二：测试计划

- [x] 单元测试消息解析和命令路由。
- [ ] 能做的情况下，为手势构造逻辑增加 instrumentation 测试。
- [ ] 手动测试系统版本：
  - [ ] Android 10。
  - [ ] Android 11。
  - [ ] Android 12。
  - [ ] Android 13。
  - [ ] Android 14。
  - [ ] 尽量覆盖小米、华为、OPPO、vivo、三星等机型。
- [ ] 手势测试：
  - [ ] 点击桌面 App 图标。
  - [ ] 滑动桌面。
  - [ ] 长按。
  - [ ] 双指缩放。
  - [ ] 返回/Home/最近任务。
- [ ] 投屏测试：
  - [ ] 反复开始和停止。
  - [ ] 投屏中旋转屏幕。
  - [ ] 连续运行 30 分钟。
  - [ ] 两台以上设备同时投屏。
- [ ] 延迟目标：
  - [ ] 局域网内手势命令往返低于 200 ms。
  - [ ] 首版视频端到端延迟低于 500 ms。
  - [ ] WiFi 或服务中断后 10 秒内自动重连。

## 建议里程碑

### M1：只做控制原型

- [x] 声明并启用无障碍服务。
- [x] APK 能连接后端 WebSocket。
- [x] 后端能下发点击、滑动、返回、Home 命令。
- [x] 前端能基于静态或手动输入的屏幕尺寸控制一台手机。

### M2：Agent 设备中心

- [x] APK 注册和心跳完成。
- [x] 平台能展示 SonicLink 设备。
- [x] 用户占用和抢占逻辑可用。
- [x] 设备状态能展示在线、离线、无障碍状态。

### M3：基础投屏

- [x] APK 请求 MediaProjection 授权。
- [x] H.264 视频帧能到达后端。
- [x] 前端能显示实时画面。
- [x] 基于实时画面的坐标映射和点击可用。

### M4：局域网可用版本

- [x] 断线重连稳定。
- [x] 支持多设备会话。
- [x] 投屏和控制对横竖屏切换稳定。
- [x] 安装、配置、授权、错误提示清晰。
- [ ] 多设备 30 分钟稳定性测试通过。

## 待确认决策

- [x] 控制消息和视频二进制数据使用同一个 WebSocket，还是拆成两个通道。
- [x] 首版投屏使用 raw H.264 + JMuxer、WebRTC，还是 MJPEG。
- [ ] 是否保留包名 `org.cloud.sonic.android`，还是改名避免和上游 Sonic 混淆。
- [ ] 是否保留现有 TCP socketmanager 模块，还是逐步替换成 OkHttp/WebSocket。
- [x] 文本输入优先使用无障碍节点编辑，还是自定义输入法。
