# 程序以及文案都由AI编写
# 耳镜查看器

给 **AIR-Look / Xylla 系 WiFi 可视挖耳勺**写的干净客户端。

原厂 App（`com.air.airlook`）内置十几个广告 SDK、要求读取已安装应用列表权限、还能自行下载安装 APK。
这个替代品只保留「连上设备看画面」这一件事：**3 个权限、无广告、无追踪、无第三方 SDK、不联网**。

- **安装包**：`android-app/耳镜查看器.apk`
- **应用名**：耳镜查看器　**包名**：`com.airlook.viewer`　**版本**：1.0（versionCode 18）

## 功能

| 功能 | 说明 |
|---|---|
| 实时画面 | 设备热点 → UDP 8032 收流，MJPEG 分片重组 |
| 画面亮度 / 对比度 | ColorMatrix 交给 GPU 处理，不掉帧，设置会记住 |
| 自动旋转 | 读帧头里的加速度计让画面跟随姿态转正；**开启时取景框变圆形**（长方形旋转会显得很乱） |
| 镜像 | 画面左右翻转 |
| 灯光开关 | 该硬件只有开 / 关两态，不能调光（实测结论，见下） |
| 拍照 | 存到 `Pictures/耳镜/` |
| 录视频 | H.264/MP4 存到 `Movies/耳镜/`；退出 App 不保存，设备断连自动保存 |

权限只有 3 个：`INTERNET`、`ACCESS_NETWORK_STATE`、`ACCESS_WIFI_STATE`。
另有一个 `WRITE_EXTERNAL_STORAGE`（带 `maxSdkVersion=28`），**只在 Android 9 及更早的系统上生效**
（旧系统的相册写入必须用它），新系统的权限列表里不会出现它。

## 兼容性

| 项目 | 值 | 说明 |
|---|---|---|
| 最低系统 | **Android 8.0**（API 26） | 2017 年之后的机型基本都满足 |
| 目标系统 | Android 13（API 33） | 在 Android 14 / 15 上侧载安装正常 |
| CPU 架构 | **全部** | 包内**没有任何原生库**（纯 Java），arm64 / armv7 / x86 都能跑 |
| 屏幕 | 任意分辨率，竖屏 | 布局用 dp、无图片资源，不挑屏幕密度 |
| 需要的硬件 | WiFi（必需） | 没 WiFi 的设备装不上（本来也用不到） |
| 安装包 | **60 KB** | 原厂 App 是 51 MB |
| 运行时权限 | Android 10+：**0 个**<br>Android 8 / 9：1 个（存储） | 打开即用，不会弹权限框 |

**已知限制（如实说明）**：

- **HarmonyOS NEXT（纯血鸿蒙 5.x 及以上）不能用** —— 它不再支持安卓应用。
  HarmonyOS 4.x 及以下（内核基于安卓）正常；本项目就是在华为 nova 10 青春版 / HarmonyOS 4.2 上跑通的。
- **Android 9 及更早**：需要授予一个存储权限，否则拍照 / 录像无法保存到相册（系统 API 限制）。
  预览、亮度、旋转等功能不受影响。
- **Android 9 及更早**：照片会存到相册默认目录，而不是 `Pictures/耳镜/` 子目录
  —— 指定子目录的 API 是 Android 10 才有的。
- 平板可以运行，但界面是按手机竖屏设计的。

## App安全声明

```bash
bash verify-app.sh          # 不带参数默认检查 android-app/耳镜查看器.apk
```

脚本是**只读**的，会打印 6 项可核对的信息。实测结果：

| 检查 | 结果 |
|---|---|---|
| 申请的权限 | Android 10+ 只有 **3 个**；另有 1 个带 `maxSdkVersion=28` 的存储权限 | 没有定位、通讯录、已装应用列表等敏感权限 |
| 包内文件 | **7 个**（其中 4 个是签名文件） | 没有原生 `.so` 库、没有第三方 SDK，只有自己的一个 dex |
| HTTP/HTTPS 网址 | **0 个** | 没有任何服务器地址，数据无处可发 |
| 出现的 IP | 只有 `192.168.x.x` | 全是内网地址，就是耳勺热点的候选地址 |
| 源码 | 4 个文件、约 1450 行，无混淆 | 全部可读可审 |

> 扫描会命中 `http://schemas.android.com/apk/res/android` 一次——那是安卓 XML 必须写的**命名空间声明**，
> 不是网址，永远不会被访问。脚本输出里有标注。

另有一条不需要看代码的佐证：**手机开飞行模式、只连耳勺热点，功能完全正常**——
说明它根本不依赖互联网。

## 装机

1. 长按耳勺开机（前端灯亮起）
2. **先把手机的其它 WiFi 断开**，连耳勺热点（名字形如 `JesHome-xxxx`，无密码、开放网络）
   —— 这类设备同一时间只允许一个客户端连接
3. 安装并打开「耳镜查看器」，画面自动出现

## 目录结构

仓库里只有源码和脚本；工具链、密钥、厂商库都在本地、不提交（见 `.gitignore`）。

```
.
├── README.md                  本文件
├── CONTRIBUTING.md            参与贡献指南（先读这个）
├── LICENSE                    GPL-3.0
├── verify-app.sh              自检脚本（权限 / 包内容 / 联网扫描）
├── .github/                   Issue 与 PR 模板
└── android-app/
    ├── 耳镜查看器.apk          已签名的安装包（仅 60 KB）
    ├── build.sh               一键重新构建（需自备工具链）
    ├── test.sh                离线单元测试（不需要设备、不需要联网）
    ├── _env.sh                build.sh / test.sh 共用的环境探测
    ├── AndroidManifest.xml
    ├── src/com/airlook/viewer/
    │   ├── MainActivity.java    界面 / 网络 / 控制通道
    │   ├── FrameAssembler.java  24 字节帧头解析 + 分片重组
    │   ├── RotationFilter.java  加速度计 → 旋转角度
    │   └── Recorder.java        MediaCodec + MediaMuxer 录像
    ├── tests/com/airlook/viewer/
    │   ├── SelfTest.java        帧组装（用真机实测参数构造）
    │   ├── RotTest.java         旋转算法（上游官方单测向量）
    │   └── YuvTest.java         YUV 转换（BT.601）
    └── res/
        ├── layout/main.xml      界面布局（纯黑，自下而上三部分）
        └── values/strings.xml   应用名
```

本地还有（**不在仓库里**）：`android-build-tools/`（构建工具链约 830 MB）、
`keys/debug.keystore`（签名密钥）、`reference/libmlcamera-2.5.so`（厂商 SDK 库，仅本地分析用）、
`android-app/old-versions/`（历史版本 APK）、`desktop-viewer/`（电脑版 Python 查看器，需电脑带无线网卡）。

## 自己构建

需要自备：JDK 17+、Android SDK platform-33 与 build-tools 33.0.2、以及
[R8](https://maven.aliyun.com/repository/google/com/android/tools/r8/)（`d8` 用；build-tools 自带那版
遇到含内部类的 class 会崩）。Python 可选（只用于把 dex 塞进 APK，没有会自动改用 JDK 自带的 `jar`）。

```bash
cd android-app

bash test.sh                      # 离线单元测试：不需设备、不需联网
bash build.sh                     # 产物：耳镜查看器.apk（会先自动跑一遍上面的测试）

VK=19 VN=1.1 bash build.sh        # 指定 versionCode / versionName
OUTNAME=耳镜查看器-1.1.apk bash build.sh
SKIP_TESTS=1 bash build.sh        # 跳过构建前自测

AIRLOOK_TOOLS=D:/android-sdk bash build.sh   # 工具链不在同级目录时
AIRLOOK_JDK=/c/Java/jdk-21 bash build.sh     # JDK 不在注册表 / PATH 里时
```

注意四点：

- **versionCode 必须递增**，否则手机拒绝覆盖安装。不写 `VK` 就用 `AndroidManifest.xml` 里的值。
- 脚本先把包签成 ASCII 临时名、再改名成中文——Windows 版 `apksigner` 收到中文路径参数会乱码失败。
- **仓库里不含签名密钥**（公开密钥等于让任何人都能签出你手机认可的"升级包"）。
  `build.sh` 检测不到密钥时会用 `keytool` **自动生成一把**，所以 clone 下来能直接构建。
  但要注意：自动生成的密钥与官方包不是同一把，**签出来的包无法覆盖安装官方版**，需要先卸载。
- JDK 探测会验证「是不是完整 JDK」（看 `bin/keytool` 在不在）——因为 Windows 上还有一类
  只有 `java`/`javac` 的转发目录，用它构建会中途失败。
  
## 协议速查（Xylla / AIR-Look，实测）

- 设备 IP `192.168.0.10`；视频 **UDP 8032**；控制 **UDP 50000**
- 启动：发 24 字节 `99 99 01 00` + 20×0，连发 11 次，之后每 800ms 保活
- 回包：24 字节头 + 负载。**只需校验第 0 字节 `0x66`**——第 1 字节是 type
  （`0x01` 帧首片 / `0x03` 续片），把它当固定 magic 校验会导致所有续片被丢弃
  （症状：包数在涨、帧数恒为 0、无效包数 = 总包数）
- 头字段（小端）：`[4-7]` 整帧字节数 `[8-9]` 宽 `[10-11]` 高 `[12-13]` 分片序号
  `[14-15]` 本片负载长度 `[16-19]` 打包的加速度计（3×10 bit）
- 控制报文：`99 99` + 命令字(u16) + 序号(u32) + 数值(u32) + 8 字节 0
- 灯光命令字 `0x1016`：**开灯用 1、关灯用 0**。实测大数值（如 1000）会被固件静默忽略，
  表现成「关掉之后再也打不开」——只有给耳勺断电重启才能恢复
- 原厂 App **从未调用**过灯光接口（全部 dex 里零命中）：不是硬件不支持调光，而是压根没做界面
- 加速度计 → 旋转角度的算法见 `RotationFilter.java`，含 20 采样滑动窗口的抖动抑制与 180° 安装偏移

## 声明

- 本项目与任何耳勺厂商无关，未使用厂商代码，也不分发厂商的任何二进制文件
  （`libmlcamera-2.5.so` 只在你自己的机器上做本地分析）。
  协议参数来自对**自购设备**与其通信过程的观察，用于实现互操作性。
- 包名 `com.airlook.viewer` 与 `com.air.airlook` 不同，所以它是独立应用，不会覆盖或干扰原厂 App。
- 感谢 [rbeilvert/otoscope](https://github.com/rbeilvert/otoscope)：本项目的
  `RotationFilter.java` 由其 Kotlin 实现移植而来，帧头字段定义也与之核对过。
  因此本项目同样以 **GPL-3.0** 发布（见 `LICENSE`）。
- 如果你的设备不在支持列表里，开机后连上它的热点、用 `verify-app.sh` 思路抓一次包，
  比对上面的头部字段即可判断是不是同一套协议。
