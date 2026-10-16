# -*- coding: utf-8 -*-
"""
AIR-Look / Xylla 可视挖耳勺 —— 极简画面查看器（第一版）

只做一件事：把耳勺的画面显示出来。灯光、电量、录像、拍照一律不做。

协议来源：开源项目 rbeilvert/otoscope（GPL-3.0）对 AIR-Look 的逆向成果。
本文件是同一套协议的 Python 重新实现，纯标准库，无需安装任何第三方包。

------------------------------------------------------------
工作原理（Xylla / AIR-Look 系）
------------------------------------------------------------
1. 手机或电脑连接耳勺发出的开放热点（SSID 形如 JesHome-XXXX / Enjoy-XXXXXX，无密码）
2. 耳勺的 IP 通常是 192.168.0.10
3. 向该 IP 的 UDP 8032 端口发送「开始预览」指令（24 字节：99 99 01 00 + 20 个 0）
   - 启动时连发 11 次，之后每 800ms 补发 1 次作为保活
4. 耳勺会把画面切成很多 UDP 小包回传到同一个端口
   每个包 = 24 字节头 + 数据段（头格式见 FrameAssembler）
5. 按 chunk_seq 顺序拼回完整 JPEG，交给浏览器显示
------------------------------------------------------------

用法：
    python airlook_view.py                  # 默认 192.168.0.10，自动探测
    python airlook_view.py --camera 192.168.0.1
    python airlook_view.py --probe-only     # 只探测不出图，用于排错

启动后在浏览器打开脚本提示的地址即可看到实时画面。
"""

import argparse
import http.server
import os
import re
import socket
import struct
import subprocess
import sys
import threading
import time

# Windows 控制台可能是 GBK，强制 UTF-8 输出，避免中文报错
try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    sys.stderr.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass

# ----------------------------------------------------------------------------
# 协议常量（与 rbeilvert/otoscope 的 FrameAssembler.kt / XyllaCameraClient.kt 一致）
# ----------------------------------------------------------------------------
DEFAULT_CAMERA_IP = "192.168.0.10"
VIDEO_PORT = 8032            # 视频/预览通道
CONTROL_PORT = 50000         # 控制通道（电量、板卡信息；第一版用不到）

CMD_START_PREVIEW = 1        # 开始预览
CMD_STOP_PREVIEW = 2         # 停止预览（本程序不使用）

HEADER_SIZE = 24             # 每个 UDP 包的头部长度
PACKET_PREFIX = 0x66         # 包头魔数
FORMAT_MJPEG = 1             # 数据段格式：JPEG
FORMAT_YUYV = 4              # 数据段格式：YUYV（未实现）
MAX_FRAME_SIZE = 4 * 1024 * 1024

START_BURST = 11             # 启动时连发次数
KEEPALIVE_SEC = 0.8          # 保活间隔


# ----------------------------------------------------------------------------
# 控制台输出helpers
# ----------------------------------------------------------------------------
def log(msg):
    print(f"[{time.strftime('%H:%M:%S')}] {msg}", flush=True)


def hexdump(data, n=32):
    d = data[:n]
    return " ".join(f"{b:02x}" for b in d)


# ----------------------------------------------------------------------------
# 帧组装器：24 字节头 + 分片，按 chunk_seq 拼回整帧
# ----------------------------------------------------------------------------
class FrameAssembler:
    """把 UDP 分片拼成完整 JPEG。逐行对应 Kotlin 版 FrameAssembler.feed()。"""

    def __init__(self):
        self.reset()
        self.dropped = 0
        self.completed = 0

    def reset(self):
        self.buffer = None
        self.accum = 0
        self.last_seq = -1
        self.expected = 0
        self.fmt = 0
        self.width = 0
        self.height = 0
        self.accel = 0

    def feed(self, pkt, length):
        """返回 (状态, 数据)
        状态: 'invalid' | 'building' | 'dropped' | 'frame'
        """
        if length < HEADER_SIZE:
            return "invalid", None
        if pkt[0] != PACKET_PREFIX:
            return "invalid", None

        total_size = int.from_bytes(pkt[4:8], "little")
        chunk_seq = int.from_bytes(pkt[12:14], "little")
        chunk_len = int.from_bytes(pkt[14:16], "little")

        if chunk_len <= 0 or chunk_len + HEADER_SIZE > length:
            return "invalid", None
        if total_size <= 0 or total_size > MAX_FRAME_SIZE:
            return "invalid", None

        # 新一帧开始：缓冲区空 / 尺寸变了 / 序号回退 / 序号归零
        is_new_frame = (
            self.buffer is None
            or total_size != self.expected
            or chunk_seq < self.last_seq
            or chunk_seq == 0
        )
        if is_new_frame:
            self.buffer = bytearray(total_size)
            self.fmt = pkt[2]
            self.width = int.from_bytes(pkt[8:10], "little")
            self.height = int.from_bytes(pkt[10:12], "little")
            self.expected = total_size
            self.accum = 0
            self.last_seq = -1

        # 必须是连续序号，丢包就整帧作废
        if chunk_seq != self.last_seq + 1:
            self.reset()
            self.dropped += 1
            return "dropped", None
        if self.accum + chunk_len > self.expected:
            self.reset()
            self.dropped += 1
            return "dropped", None

        self.buffer[self.accum:self.accum + chunk_len] = pkt[HEADER_SIZE:HEADER_SIZE + chunk_len]
        self.accum += chunk_len
        self.last_seq = chunk_seq
        self.accel = int.from_bytes(pkt[16:20], "little")

        if self.accum == self.expected:
            frame = bytes(self.buffer)
            meta = (self.fmt, self.width, self.height, self.accel)
            self.buffer = None
            self.accum = 0
            self.last_seq = -1
            self.completed += 1
            return "frame", (frame, meta)
        return "building", None


# ----------------------------------------------------------------------------
# 最新帧共享容器（接收线程写，HTTP 线程读）
# ----------------------------------------------------------------------------
class FrameHolder:
    def __init__(self):
        self.cond = threading.Condition()
        self.jpeg = None
        self.version = 0

    def put(self, jpeg):
        with self.cond:
            self.jpeg = jpeg
            self.version += 1
            self.cond.notify_all()

    def get(self):
        with self.cond:
            return self.jpeg, self.version

    def wait_new(self, last_version, timeout=5.0):
        with self.cond:
            if self.version == last_version:
                self.cond.wait(timeout)
            return self.jpeg, self.version


# ----------------------------------------------------------------------------
# 网络探测
# ----------------------------------------------------------------------------
def sh(args):
    try:
        r = subprocess.run(args, capture_output=True, timeout=15,
                           creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0))
        return r.stdout.decode("utf-8", "replace")
    except Exception:
        return ""


def wifi_ssid():
    out = sh(["netsh", "wlan", "show", "interfaces"])
    m = re.search(r"^\s*SSID\s*:\s*(.+?)\s*$", out, re.M)
    return m.group(1) if m else None


def default_gateway():
    out = sh(["route", "print", "-4"])
    for line in out.splitlines():
        p = line.split()
        if len(p) >= 3 and p[0] == "0.0.0.0" and p[1] == "0.0.0.0":
            return p[2]
    return None


def local_ip_towards(dst_ip):
    """返回访问 dst_ip 时会使用的本机网卡地址。"""
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.settimeout(2)
        s.connect((dst_ip, 1))
        ip = s.getsockname()[0]
        s.close()
        return ip
    except Exception:
        return None


# ----------------------------------------------------------------------------
# UDP 接收线程
# ----------------------------------------------------------------------------
class Receiver(threading.Thread):
    def __init__(self, camera_ip, video_port, holder, bind_ip=None, save_dir=None):
        super().__init__(daemon=True)
        self.camera_ip = camera_ip
        self.video_port = video_port
        self.holder = holder
        self.bind_ip = bind_ip
        self.save_dir = save_dir
        self.stop_flag = threading.Event()

        self.packets = 0
        self.bytes = 0
        self.frames = 0
        self.first_frame_meta = None
        self.saved = 0
        self.assembler = FrameAssembler()
        self.last_packet_ts = 0.0
        self.save_next = True

    def send_cmd(self, sock, addr, cmd, burst):
        pkt = bytes([0x99, 0x99, cmd & 0xFF, (cmd >> 8) & 0xFF]) + b"\x00" * 20
        for _ in range(burst):
            try:
                sock.sendto(pkt, addr)
            except Exception:
                pass

    def run(self):
        sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        for size in (4 * 1024 * 1024, 1024 * 1024, 262144):
            try:
                sock.setsockopt(socket.SOL_SOCKET, socket.SO_RCVBUF, size)
                break
            except Exception:
                continue
        # Windows 上未绑定的 UDP socket 调 getsockname() 会抛 WSAEINVAL(10022)，
        # 所以这里必须先显式绑定一次。绑定到具体网卡可避免多网卡时走错出口。
        bound_ip = None
        if self.bind_ip:
            try:
                sock.bind((self.bind_ip, 0))
                bound_ip = self.bind_ip
            except Exception as e:
                log(f"绑定网卡 {self.bind_ip} 失败（{e}），改用默认网卡")
        if bound_ip is None:
            try:
                sock.bind(("0.0.0.0", 0))
            except Exception as e:
                log(f"socket 绑定失败：{e}")
                return
        sock.settimeout(1.0)

        addr = (self.camera_ip, self.video_port)
        sock_ip, sock_port = sock.getsockname()[0], sock.getsockname()[1]
        log(f"本机 {sock_ip}:{sock_port} → 目标 {self.camera_ip}:{self.video_port}")
        log(f"发送「开始预览」指令 ×{START_BURST} …")
        self.send_cmd(sock, addr, CMD_START_PREVIEW, START_BURST)

        last_keepalive = time.time()
        first_logged = False
        t0 = time.time()
        last_report = t0

        while not self.stop_flag.is_set():
            now = time.time()
            if now - last_keepalive >= KEEPALIVE_SEC:
                self.send_cmd(sock, addr, CMD_START_PREVIEW, 1)
                last_keepalive = now

            try:
                data, _src = sock.recvfrom(8192)
            except socket.timeout:
                if now - t0 > 6 and self.packets == 0:
                    log("（还没有收到任何数据包…）")
                continue
            except OSError as e:
                # Windows 特例：向还没就绪的对端发 UDP 会收到 ICMP 端口不可达，
                # 内核在下一次 recvfrom 时抛 WSAECONNRESET(10054)。
                # 这是正常现象（设备刚开机时很常见），不能当成致命错误退出。
                if getattr(e, "winerror", None) == 10054:
                    continue
                log(f"接收异常：{e}")
                break
            except Exception as e:
                log(f"接收异常：{e}")
                break

            self.packets += 1
            self.bytes += len(data)
            self.last_packet_ts = now

            if not first_logged:
                first_logged = True
                log(f"收到第一个包，长度 {len(data)} 字节：{hexdump(data)}")
                log("→ 设备在线，数据通道已建立")

            status, payload = self.assembler.feed(data, len(data))
            if status == "frame":
                jpeg, meta = payload
                fmt, w, h, _accel = meta
                self.frames += 1
                if self.first_frame_meta is None:
                    self.first_frame_meta = meta
                    log(f"首帧完成：{w}×{h}，格式={'MJPEG' if fmt == FORMAT_MJPEG else fmt}，"
                        f"{len(jpeg)} 字节")
                if fmt == FORMAT_MJPEG:
                    self.holder.put(jpeg)
                    if self.save_dir and self.saved < 3:
                        try:
                            path = os.path.join(self.save_dir, f"frame_{self.saved:02d}.jpg")
                            with open(path, "wb") as f:
                                f.write(jpeg)
                            self.saved += 1
                            log(f"已保存样张 {path}")
                        except Exception:
                            pass

            if now - last_report >= 5.0:
                fps = (self.frames - getattr(self, "_prev_frames", 0)) / (now - last_report)
                self._prev_frames = self.frames
                mbps = (self.bytes * 8 / 1e6) / (now - t0)
                log(f"统计：包 {self.packets}，整帧 {self.frames}，丢帧 {self.assembler.dropped}，"
                    f"约 {fps:.1f} fps，平均 {mbps:.1f} Mbps")
                last_report = now

        try:
            self.send_cmd(sock, addr, CMD_STOP_PREVIEW, 3)
        except Exception:
            pass
        sock.close()


# ----------------------------------------------------------------------------
# MJPEG HTTP 服务：浏览器负责解码，所以不需要任何图像库
# ----------------------------------------------------------------------------
BOUNDARY = "airlookframe"


class ViewerHTTPServer(http.server.ThreadingHTTPServer):
    """关闭 SO_REUSEADDR。

    Windows 上 SO_REUSEADDR 的语义与 Linux 不同：它允许第二个进程「悄悄」绑上
    已被占用的端口。结果是程序打印「已启动」，浏览器却拿不到任何响应，极难排查。
    关掉之后端口冲突会正常抛错，从而触发下方的自动换端口逻辑。
    """
    allow_reuse_address = False


class Handler(http.server.BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    holder = None
    receiver = None
    started = None

    def log_message(self, *a):
        pass

    def do_GET(self):
        path = self.path.split("?")[0]
        if path == "/":
            self.page()
        elif path == "/stream":
            self.stream()
        elif path == "/snapshot":
            self.snapshot()
        elif path == "/stats":
            self.stats()
        else:
            self.send_error(404)

    def page(self):
        body = PAGE.encode("utf-8")
        self.send_response(200)
        self.send_header("Content-Type", "text/html; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(body)

    def stream(self):
        self.send_response(200)
        self.send_header("Age", "0")
        self.send_header("Cache-Control", "no-store, no-cache, must-revalidate")
        self.send_header("Pragma", "no-cache")
        self.send_header("Content-Type", f"multipart/x-mixed-replace; boundary={BOUNDARY}")
        self.end_headers()
        last = -1
        try:
            while True:
                jpeg, ver = Handler.holder.wait_new(last, timeout=5.0)
                if jpeg is None:
                    if Handler.receiver and Handler.receiver.packets == 0:
                        continue
                    continue
                if ver == last:
                    continue
                last = ver
                self.wfile.write(f"--{BOUNDARY}\r\n".encode())
                self.wfile.write(b"Content-Type: image/jpeg\r\n")
                self.wfile.write(f"Content-Length: {len(jpeg)}\r\n\r\n".encode())
                self.wfile.write(jpeg)
                self.wfile.write(b"\r\n")
        except (BrokenPipeError, ConnectionResetError, ConnectionAbortedError, OSError):
            return

    def snapshot(self):
        jpeg, _ = Handler.holder.get()
        if jpeg is None:
            self.send_error(503, "no frame yet")
            return
        self.send_response(200)
        self.send_header("Content-Type", "image/jpeg")
        self.send_header("Content-Length", str(len(jpeg)))
        self.send_header("Content-Disposition", 'attachment; filename="snapshot.jpg"')
        self.end_headers()
        self.wfile.write(jpeg)

    def stats(self):
        r = Handler.receiver
        import json
        d = {
            "camera_ip": r.camera_ip if r else None,
            "video_port": r.video_port if r else None,
            "packets": r.packets if r else 0,
            "frames": r.frames if r else 0,
            "dropped": r.assembler.dropped if r else 0,
            "first_frame": r.first_frame_meta if r else None,
            "uptime_sec": round(time.time() - Handler.started, 1) if Handler.started else 0,
        }
        body = json.dumps(d, ensure_ascii=False).encode("utf-8")
        self.send_response(200)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)


PAGE = """<!DOCTYPE html>
<html lang="zh-CN"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>AIR-Look 画面查看器</title>
<style>
  * { box-sizing: border-box; }
  body { margin:0; background:#141414; color:#e8e8e8;
         font-family:-apple-system,Segoe UI,Microsoft YaHei,sans-serif;
         display:flex; flex-direction:column; align-items:center;
         min-height:100vh; padding:20px; }
  h1 { font-size:15px; font-weight:500; letter-spacing:.02em;
       color:#9a9a9a; margin:0 0 16px; }
  .stage { width:min(100%,720px); aspect-ratio:1/1; background:#000;
           border:1px solid #2a2a2a; border-radius:12px;
           display:flex; align-items:center; justify-content:center;
           overflow:hidden; position:relative; }
  .stage img { width:100%; height:100%; object-fit:contain; border-radius:50%;
               display:none; }
  .hint { position:absolute; color:#6a6a6a; font-size:13px; text-align:center;
          line-height:1.7; padding:0 24px; }
  .bar { display:flex; gap:10px; margin-top:16px; align-items:center;
         flex-wrap:wrap; justify-content:center; }
  button { background:#242424; color:#dcdcdc; border:1px solid #333;
           border-radius:8px; padding:8px 16px; font-size:13px;
           cursor:pointer; font-family:inherit; }
  button:hover { background:#2e2e2e; border-color:#444; }
  .stat { font-size:12px; color:#7a7a7a; font-variant-numeric:tabular-nums; }
</style></head><body>
<h1>AIR-Look 画面查看器</h1>
<div class="stage">
  <div class="hint" id="hint">正在等待画面…<br>请确认已连接到耳勺的热点</div>
  <img id="v" src="/stream" onload="document.getElementById('hint').style.display='none';document.getElementById('v').style.display='block'">
</div>
<div class="bar">
  <button onclick="location.href='/snapshot'">保存当前画面</button>
  <button onclick="location.reload()">重新连接</button>
  <span class="stat" id="st">—</span>
</div>
<script>
setInterval(async () => {
  try {
    const r = await fetch('/stats'); const d = await r.json();
    document.getElementById('st').textContent =
      `${d.frames} 帧 · 丢弃 ${d.dropped} · ${d.uptime_sec}s`;
  } catch(e) {}
}, 2000);
</script>
</body></html>
"""


# ----------------------------------------------------------------------------
def main():
    ap = argparse.ArgumentParser(description="AIR-Look / Xylla 可视挖耳勺画面查看器")
    ap.add_argument("--camera", default=DEFAULT_CAMERA_IP, help="设备 IP，默认 192.168.0.10")
    ap.add_argument("--port", type=int, default=VIDEO_PORT, help="视频 UDP 端口，默认 8032")
    ap.add_argument("--http-port", type=int, default=8765, help="网页端口，默认 8765")
    ap.add_argument("--bind", default=None, help="强制绑定本机网卡地址")
    ap.add_argument("--no-save", action="store_true", help="不保存样张")
    ap.add_argument("--probe-only", action="store_true", help="只探测，不启动网页")
    ap.add_argument("--seconds", type=int, default=0, help="定时退出秒数（0=一直运行）")
    args = ap.parse_args()

    here = os.path.dirname(os.path.abspath(__file__))
    save_dir = None if args.no_save else os.path.join(here, "captures")
    if save_dir:
        os.makedirs(save_dir, exist_ok=True)

    log("=" * 62)
    log("AIR-Look / Xylla 可视挖耳勺 · 画面查看器")
    log("=" * 62)

    # --- 环境自检 ---
    ssid = wifi_ssid()
    gw = default_gateway()
    local = local_ip_towards(args.camera)
    log(f"当前 WiFi：{ssid if ssid else '（未检测到 / 未连接无线）'}")
    log(f"默认网关：{gw if gw else '（无）'}")
    log(f"本机用于访问设备的地址：{local if local else '（无法确定）'}")

    if ssid:
        if re.search(r"(JesHome|Enjoy|AIR-ES|iTiMO|softish|Cooleer|Soulear)", ssid, re.I):
            log(f"✓ WiFi 名称「{ssid}」看起来就是耳勺热点")
        else:
            log(f"⚠ WiFi 名称「{ssid}」不像耳勺热点，请确认已连接到设备热点")
    else:
        log("⚠ 未检测到已连接的 WiFi，请先连接耳勺热点")

    # --- 候选设备地址 ---
    candidates = []
    for c in (args.camera, gw, DEFAULT_CAMERA_IP, "192.168.0.1"):
        if c and c not in candidates:
            candidates.append(c)

    holder = FrameHolder()
    receiver = None

    for ip in candidates:
        log("-" * 62)
        log(f"尝试设备地址 {ip} …")
        holder2 = FrameHolder()
        r = Receiver(ip, args.port, holder2, bind_ip=args.bind, save_dir=save_dir)
        r.start()
        deadline = time.time() + 9
        while time.time() < deadline:
            if r.packets > 0:
                break
            time.sleep(0.25)
        if r.packets > 0:
            receiver, holder = r, holder2
            log(f"✓ {ip} 有响应，使用该地址")
            break
        log(f"✗ {ip} 无响应")
        r.stop_flag.set()
        r.join(timeout=3)

    if receiver is None:
        log("=" * 62)
        log("所有候选地址都没有响应。请依次检查：")
        log("  1. 耳勺是否已开机（长按开机键 2 秒，前端灯亮）")
        log("  2. 电脑是否已连接到耳勺发出的 WiFi 热点（无密码）")
        log("  3. 是否被 Windows 防火墙拦截（首次运行请点「允许访问」）")
        log("  4. 耳勺同一时间只允许一台设备连接——先把手机 WiFi 断开")
        log("可使用 --camera 参数手动指定地址后重试。")
        log("=" * 62)
        sys.exit(1)

    if args.probe_only:
        log("探测模式：等待 15 秒收集统计 …")
        time.sleep(15)
        log(f"结论：包 {receiver.packets}，整帧 {receiver.frames}，"
            f"丢帧 {receiver.assembler.dropped}，首帧 {receiver.first_frame_meta}")
        receiver.stop_flag.set()
        sys.exit(0)

    # --- 启动网页服务 ---
    Handler.holder = holder
    Handler.receiver = receiver
    Handler.started = time.time()

    httpd = None
    actual_port = args.http_port
    for p in range(args.http_port, args.http_port + 10):
        try:
            httpd = ViewerHTTPServer(("127.0.0.1", p), Handler)
            actual_port = p
            break
        except OSError:
            continue
    if httpd is None:
        log(f"端口 {args.http_port}~{args.http_port + 9} 都被占用。"
            f"请用 --http-port 指定其它端口后重试。")
        receiver.stop_flag.set()
        sys.exit(1)
    if actual_port != args.http_port:
        log(f"端口 {args.http_port} 被占用，已自动改用 {actual_port}")

    t = threading.Thread(target=httpd.serve_forever, daemon=True)
    t.start()

    url = f"http://127.0.0.1:{actual_port}/"
    log("=" * 62)
    log("✅ 已启动。请在浏览器打开：")
    log(f"     {url}")
    log(f"（也可以把 http://127.0.0.1:{actual_port}/stream 丢给 VLC / ffplay 播放）")
    log("画面会自动出现在网页里；换耳道方向时画面会自动旋转。")
    log("按 Ctrl+C 退出")
    log("=" * 62)

    try:
        if args.seconds:
            time.sleep(args.seconds)
        else:
            while True:
                time.sleep(1)
    except KeyboardInterrupt:
        log("已手动停止")
    finally:
        receiver.stop_flag.set()
        httpd.shutdown()


if __name__ == "__main__":
    main()
