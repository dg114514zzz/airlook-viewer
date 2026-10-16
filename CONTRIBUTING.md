## 一、你手上有那台耳勺吗？

本项目只支持 **Xylla / AIR-Look 系 WiFi 可视耳勺**（Wi-Fi 热点名形如 `JesHome-XXXX` 或
`Enjoy-XXXXXX`，原厂 App 是 `com.air.airlook`）。通信靠两条 UDP 通道：

| 通道 | 端口 | 用途 |
|---|---|---|
| 视频流 | UDP 8032 | 启动包 `99 99 01 00` + 20 个 0 字节；数据帧 = 24 字节头 + MJPEG 分片 |
| 控制通道 | UDP 50000 | 24 字节报文：`99 99` + u16 命令字 + u32 序号 + int32 数值 + 8 字节 0 |

**没有设备也能做的改动：**

- 文档、注释、代码可读性
- 界面布局与配色（`res/layout/main.xml`、`res/values/`）
- 三块纯算法逻辑 —— 这三块有**离线单元测试**，改完必须跑通：
  - `FrameAssembler.java` 帧头解析与分片重组
  - `RotationFilter.java` 加速度计 → 旋转角度
  - `Recorder.java` 的 YUV 转换（`luma` / `chromaU` / `chromaV` / `toNv12` / `toI420`）

**必须有设备才能验证的改动**（请务必在 PR 里说明你测过没有）：

- `MainActivity.java` —— 网络绑定、UDP 收发、控制通道、拍照/录像落盘、断连判断
- 任何涉及协议字段、时序、灯光、模式的改动

## 二、本地验证

```bash
# 离线单元测试（不需要设备、不需要联网；只需 JDK + Android SDK 里的 android.jar）
cd android-app
bash test.sh

# 完整构建（会先自动跑一遍上面的测试）
bash build.sh                    # 产物：android-app/耳镜查看器.apk
VK=19 VN=1.1 bash build.sh       # 指定版本号
```

工具链位置与 JDK 可用环境变量覆盖，见 `_env.sh` 顶部注释。

## 三、提 PR 的流程

标准的 fork 流程：

```bash
# 1. 在 GitHub 上点 Fork
git clone https://github.com/<你的用户名>/<仓库名>.git
cd <仓库名>
git checkout -b fix/描述一下改了什么

# 2. 改代码 → 跑测试 → 提交
cd android-app && bash test.sh
cd ..
git add -A
git commit -m "修复：...（做了什么、为什么）"
git push origin fix/描述一下改了什么

# 3. 在 GitHub 上发起 Pull Request
```

## 四、PR 里请写明这些

因为大量行为只能靠真机验证，PR 描述里这几条决定了审阅速度：

1. **改了什么、为什么**（一两句话即可）
2. **怎么验证的**：
   - 跑了 `bash test.sh`？结果如何
   - 有设备吗？是什么机型 / 系统版本？测了哪几个功能、结果如何
   - 没设备的话直接写「未在设备上验证」，我会在合并说明里标注——**不会因此拒绝**，
     但涉及协议或网络时序的改动，通常会要求先有人验证过
3. 涉及协议的改动，**请注明判断依据**（哪来的字段定义、报文样例、抓包结果）。
   协议不是猜出来的，改动也不能靠猜。

## 五、代码约定

- **纯 Java，零第三方依赖。** 不引入 Gradle、Kotlin、AndroidX、FFmpeg、任何 SDK。
  解码用系统自带的 `BitmapFactory`，编码用 `MediaCodec`/`MediaMuxer`——
  这是这个项目能只有 64 KB、只有 3 个权限的根本原因。
- **不加混淆**，保持可读可审。
- 注释和提交信息用中文，说明「为什么」而不只是「做了什么」。
- **改 UI 时不要新增图片资源**：布局全部用纯色与系统控件，这样不挑屏幕密度。
- 修改 `build.sh` 时注意：Windows 版 `apksigner` 收到中文路径参数会乱码，
  所以脚本先签成 ASCII 临时名再改名。

## 六、许可证

本项目以 **GPL-3.0** 发布（见 `LICENSE`）。你提交的代码将同样按 GPL-3.0 授权。

其中 `RotationFilter.java` 是从开源项目 [rbeilvert/otoscope](https://github.com/rbeilvert/otoscope)
（GPL-3.0）的实现移植而来，`FrameAssembler.java` 的帧头字段定义也参照了该项目——
这也是本项目选择 GPL-3.0 而非 MIT 的原因。改动这两处时请留意保持许可证一致。

## 七、报 Bug

请在 Issue 里带上：**机型、系统版本、App 版本、复现步骤、以及现象截图**。

调试信息很有用：App 出画面的同时会在左上角显示一行统计（包数 / 帧数 / 丢帧数）；
画面出不来时它会自动展开详细诊断（设备地址、收到的原始字节）。把那段文字或截图附上，
定位会快很多。
