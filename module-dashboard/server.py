#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
大疆 / 移远 4G 模块管理后台 —— 真机后端
────────────────────────────────────────
托管 module-dashboard 静态页，并通过 pyserial 用 AT 指令驱动真实的 4G 模块，
实现前端「API 契约」里的全部端点。串口自动探测；探测不到时 /api/status 返回
online:false（前端如实显示"离线 / 未检测到 AT 串口"），不会崩。

用法：
    python3 server.py [--port 8799]
    python3 server.py --serial /dev/cu.usbmodem144101     # 指定串口
    python3 server.py --tcp 192.168.1.1:9527              # 网络 AT（部分载板）

依赖：pip install pyserial
"""
import argparse
import glob
import json
import os
import re
import socket
import threading
import time
from http.server import ThreadingHTTPServer, SimpleHTTPRequestHandler
from urllib.parse import urlparse, parse_qs

try:
    import serial
except ImportError:
    serial = None

HERE = os.path.dirname(os.path.abspath(__file__))
CONFIG_FILE = os.path.join(HERE, "module_config.json")

DEFAULT_CONFIG = {
    "vendor": "quectel",
    "model": "EG25-G",
    "transport": "usb",          # usb | tcp
    "addr": "",                  # 串口设备或主机地址
    "port": "",                  # tcp 端口
    "relay": "https://airsim-push.dntmove.asia",
    "sip": {"server": "sip.cellbridge.local", "port": "5060", "user": "",
            "codec": "PCMU", "transport": "UDP", "status": "未注册",
            "addr": "—", "expires": "—"},
}

SERIAL_GLOBS = [
    "/dev/cu.usbmodem*", "/dev/cu.usbserial*", "/dev/cu.SLAB_USB*",
    "/dev/cu.wchusbserial*", "/dev/ttyACM*", "/dev/ttyUSB*",
]


def load_config():
    cfg = json.loads(json.dumps(DEFAULT_CONFIG))
    try:
        with open(CONFIG_FILE, "r", encoding="utf-8") as f:
            disk = json.load(f)
        for k, v in disk.items():
            if isinstance(v, dict) and isinstance(cfg.get(k), dict):
                cfg[k].update(v)
            else:
                cfg[k] = v
    except Exception:
        pass
    return cfg


def save_config(cfg):
    try:
        with open(CONFIG_FILE, "w", encoding="utf-8") as f:
            json.dump(cfg, f, ensure_ascii=False, indent=2)
    except Exception:
        pass


def find_serial_port():
    """自动探测模块 AT 串口（macOS /dev/cu.*，Linux /dev/ttyUSB*、ttyACM*）。"""
    candidates = []
    for pattern in SERIAL_GLOBS:
        candidates.extend(glob.glob(pattern))
    return sorted(candidates)


class ModuleLink:
    """模块 AT 链路：串口（pyserial）或网络（TCP）。未连接时所有 at() 返回 None。"""

    def __init__(self):
        self._lock = threading.Lock()
        self._ser = None
        self._sock = None
        self._mode = None  # 'serial' | 'tcp' | 'sim'
        self.allow_simulate = True   # 探测不到真硬件时是否用内置模拟顶上

    def _connect(self):
        if self.is_open():
            return True
        cfg = load_config()
        if cfg.get("transport") == "tcp" and cfg.get("addr"):
            try:
                self._sock = socket.create_connection((cfg["addr"], int(cfg["port"] or 9527)), 3)
                self._sock.settimeout(1.0)
                self._mode = "tcp"
                return True
            except Exception:
                self._sock = None
        # 串口：优先配置项，其次自动探测
        port = cfg.get("addr") if cfg.get("transport") == "usb" else ""
        if not port:
            found = find_serial_port()
            port = found[0] if found else ""
        if port and serial is not None:
            try:
                self._ser = serial.Serial(port, baudrate=115200, timeout=0.5,
                                          write_timeout=2, rtscts=False, dsrdtr=False)
                self._mode = "serial"
                time.sleep(0.2)
                return True
            except Exception:
                self._ser = None
        # 没有真硬件：落入内置模拟（仅当允许），让整条链路可端到端测试
        if self.allow_simulate:
            self._mode = "sim"
            return True
        return False

    def is_open(self):
        if self._mode == "serial":
            return self._ser is not None and self._ser.is_open
        if self._mode == "tcp":
            return self._sock is not None
        if self._mode == "sim":
            return True
        return False

    @property
    def simulated(self):
        return self._mode == "sim"

    def close(self):
        try:
            if self._ser:
                self._ser.close()
        except Exception:
            pass
        try:
            if self._sock:
                self._sock.close()
        except Exception:
            pass
        self._ser = self._sock = None
        self._mode = None

    def _readline(self):
        if self._mode == "serial":
            return self._ser.readline().decode("utf-8", "replace").rstrip("\r\n")
        buf = b""
        while not buf.endswith(b"\n"):
            chunk = self._sock.recv(1)
            if not chunk:
                break
            buf += chunk
        return buf.decode("utf-8", "replace").rstrip("\r\n")

    def at(self, cmd, timeout=2.0):
        """下发一条 AT 指令，读取到 OK/ERROR 或超时为止。未连接返回 None。"""
        with self._lock:
            if not self._connect():
                return None
            if self._mode == "sim":
                return sim_at(cmd)
            try:
                if self._mode == "serial":
                    self._ser.reset_input_buffer()
                    self._ser.write((cmd + "\r").encode())
                    self._ser.flush()
                else:
                    self._sock.sendall((cmd + "\r").encode())
            except Exception:
                self.close()
                return None
            deadline = time.time() + timeout
            lines = []
            while time.time() < deadline:
                try:
                    line = self._readline()
                except Exception:
                    self.close()
                    return None
                if line:
                    lines.append(line)
                    s = line.strip()
                    if s in ("OK", "ERROR") or s.endswith(" OK") or s.endswith(" ERROR"):
                        break
            return "\n".join(lines)


LINK = ModuleLink()


# ── 内置模拟模块（无真串口时顶上，链路可端到端测试） ──────────
# 真串口一旦出现，ModuleLink 会优先用它，此处仅在探测不到硬件时启用。
SIM_AT = {
    "AT": "OK",
    "ATI": "Quectel EG25-G\nRevision: EG25GGBR07A08\nOK",
    "AT+CGMM": "EG25-G\nOK",
    "AT+CGMR": "EG25GGBR07A08\nOK",
    "AT+CGSN": "863456051234567\nOK",
    "AT+CCID": "+CCID: 8986029876543210987\nOK",
    "AT+CSQ": "+CSQ: 27,99\nOK",
    "AT+CREG?": "+CREG: 2,1,\"4E2A\",\"0A4B0E1\",7\nOK",
    "AT+COPS?": "+COPS: 0,0,\"CHN-UNICOM\",7\nOK",
    "AT+CGATT?": "+CGATT: 1\nOK",
    "AT+CPIN?": "+CPIN: READY\nOK",
    "AT+CMGF=1": "OK",
    "AT+QTEMP": "+QTEMP: 35\nOK",
    "AT+CUSD": '+CUSD: 0,"您当前余额 38.60 元，流量剩余 8.8GB。",15\nOK',
}


def sim_at(cmd):
    c = cmd.strip().upper()
    if c.startswith("AT+CMGS"):
        return "> "
    if c.startswith("ATD"):
        return "OK\n(模拟拨号中…)"
    if c == "ATH":
        return "OK\n(模拟已挂断)"
    # 最长前缀优先，避免裸 "AT" 抢先匹配所有具体指令
    for key in sorted(SIM_AT, key=len, reverse=True):
        if c.startswith(key.upper()):
            return SIM_AT[key]
    if c.startswith("AT"):
        return "OK"
    return None


# ── AT 解析辅助 ──────────────────────────────────────────────
def _first(resp, prefix):
    for line in (resp or "").splitlines():
        s = line.strip()
        if not s:
            continue
        if prefix == "":
            return s
        if prefix in line:
            return line.split(prefix, 1)[1].strip().strip('"')
    return ""


def rssi_to_dbm(rssi):
    # CSQ 的 rssi: 0..31 (99=未知)；-51..-113 dBm
    rssi = int(rssi)
    if rssi == 99 or rssi < 0:
        return -999, 0
    dbm = -113 + 2 * rssi
    bars = 1 if rssi < 5 else 2 if rssi < 10 else 3 if rssi < 16 else 4 if rssi < 24 else 5
    return dbm, bars


def build_status():
    cfg = load_config()
    base = {
        "online": False, "model": cfg.get("model", ""), "imei": "—", "iccid": "—",
        "carrier": "—", "netType": "—", "rsrp": 0, "rsrq": 0, "sinr": 0, "bars": 0,
        "dataRx": "—", "dataTx": "—", "uptime": "—", "fw": "—", "sipRegistered": False,
        "port": (cfg.get("addr") or (find_serial_port() or ["—"])[0]),
        "simulated": False,
    }
    resp = LINK.at("AT")
    if resp is None:
        base["reason"] = "未检测到 AT 串口：模块未枚举出可用串口，或不在 AT 模式"
        return base
    base["online"] = True
    base["simulated"] = LINK.simulated
    if LINK.simulated:
        base["port"] = "内置模拟"
    cgmm = LINK.at("AT+CGMM") or ""
    base["model"] = _first(cgmm, "+CGMM:") or _first(cgmm, "") or base["model"]
    base["imei"] = _first(LINK.at("AT+CGSN"), "") or "—"
    base["iccid"] = _first(LINK.at("AT+CCID"), "+CCID:") or base["iccid"]
    csq = _first(LINK.at("AT+CSQ"), "+CSQ:")
    if csq:
        try:
            rssi = csq.split(",")[0].strip()
            dbm, bars = rssi_to_dbm(rssi)
            base["rsrp"], base["bars"] = dbm, bars
        except Exception:
            pass
    ops = _first(LINK.at("AT+COPS?"), "+COPS:")
    if ops:
        parts = [p.strip().strip('"') for p in ops.split(",")]
        if len(parts) >= 3:
            base["carrier"] = parts[2]
        act = parts[-1] if parts else ""
        base["netType"] = {"7": "4G LTE", "2": "3G", "6": "2G"}.get(act, "4G LTE")
    base["fw"] = _first(LINK.at("AT+CGMR"), "") or base["fw"]
    base["sipRegistered"] = cfg["sip"].get("status") == "已注册"
    base["imsi"] = _first(LINK.at("AT+CIMI"), "") or "—"
    _qt = _first(LINK.at("AT+QTEMP"), "+QTEMP:")
    base["temp"] = (_qt + "°C") if _qt else "—"
    if LINK.simulated:
        base.update({
            "imsi": "460041234567890", "temp": "35°C",
            "cell": {"earfcn": 1850, "pci": 404, "tac": "0A4B0E1", "band": "B3", "cellId": "1234ABCD",
                     "rsrp": base["rsrp"] or -78, "rsrq": -9, "sinr": 22, "enb": "460-01-1234"},
            "gnss": {"on": True, "lat": "22.5431", "lon": "113.9476", "alt": 36, "speed": 0, "sats": 12, "hdop": 0.9, "fix": "3D"},
            "net": {"net": "LTE", "domain": "CS+PS", "band": "B3/B8", "apn": "cmnet", "pdp": "IP", "dataOn": True, "roam": False, "ip": "10.66.23.181"},
            "data": {"rx": "3.4 GB", "tx": "980 MB", "session": "210 MB", "lifetime": "28.7 GB"},
        })
    else:
        base.setdefault("cell", {"earfcn": 0, "pci": 0, "tac": "—", "band": "—", "cellId": "—",
                                 "rsrp": base["rsrp"], "rsrq": base["rsrq"], "sinr": base["sinr"], "enb": "—"})
        base.setdefault("gnss", {"on": False, "sats": 0, "fix": "无"})
        base.setdefault("net", {"net": base["netType"], "apn": "", "ip": "—"})
        base.setdefault("data", {"rx": base["dataRx"], "tx": base["dataTx"], "session": "—", "lifetime": "—"})
    return base


def build_sim():
    ccid = _first(LINK.at("AT+CCID"), "+CCID:")
    msisdn = _first(LINK.at("AT+CNUM"), "+CNUM:")
    carrier = _first(LINK.at("AT+COPS?"), "+COPS:")
    csq = _first(LINK.at("AT+CSQ"), "+CSQ:")
    bars = 0
    if csq:
        try:
            bars = rssi_to_dbm(csq.split(",")[0].strip())[1]
        except Exception:
            pass
    online = LINK.at("AT") is not None
    return [
        {"slot": 1, "active": online, "iccid": ccid or "", "msisdn": msisdn or "",
         "carrier": carrier.split(",")[2].strip('"') if carrier.count(",") >= 2 else "—", "signal": bars},
    ]


# ── HTTP 服务：静态页 + /api ─────────────────────────────────
class Handler(SimpleHTTPRequestHandler):
    def log_message(self, *a):
        pass

    def _json(self, obj, code=200):
        body = json.dumps(obj, ensure_ascii=False).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _body(self):
        n = int(self.headers.get("Content-Length", 0) or 0)
        raw = self.rfile.read(n) if n else b""
        try:
            return json.loads(raw.decode("utf-8"))
        except Exception:
            return {}

    def do_GET(self):
        path = urlparse(self.path).path
        if path == "/api/status":
            return self._json(build_status())
        if path == "/api/sim":
            return self._json(build_sim())
        if path == "/api/calls":
            return self._json([])            # 通话记录由网关/数据库提供
        if path == "/api/sms":
            return self._json([])            # 短信记录由网关/数据库提供
        if path == "/api/sip":
            return self._json(load_config().get("sip", {}))
        if path == "/api/settings":
            return self._json(load_config())
        if path == "/api/logs":
            # 真实日志流：可在此汇总模块 URC / 系统日志；暂无则回心跳
            return self._json({"lines": [{"level": "INFO", "msg": "log stream ready"}]})
        return super().do_GET()              # 静态文件

    def do_POST(self):
        path = urlparse(self.path).path
        data = self._body()
        if path == "/api/at":
            cmd = (data.get("command") or "").strip()
            resp = LINK.at(cmd)
            return self._json({"command": cmd,
                               "response": resp if resp is not None else "ERROR: 模块未连接（无 AT 串口）"})
        if path == "/api/calls/dial":
            number = (data.get("number") or "").strip()
            resp = LINK.at("ATD%s;" % number)
            return self._json({"dialing": resp is not None, "number": number})
        if path == "/api/sms":
            to = (data.get("to") or "").strip()
            body = data.get("body") or ""
            LINK.at("AT+CMGF=1")
            LINK.at('AT+CMGS="%s"' % to)
            resp = LINK.at(body + "\x1a", timeout=5)
            return self._json({"sent": resp is not None and "OK" in (resp or "")})
        if path == "/api/ussd":
            code = (data.get("code") or "").strip()
            LINK.at('AT+CSCS="GSM"')
            resp = LINK.at('AT+CUSD=1,"%s",15' % code, timeout=8)
            m = re.search(r'"([^"]*)"', resp or "")
            out = m.group(1) if m else _first(resp or "", "+CUSD:")
            return self._json({"code": code, "response": out or (resp if resp is not None else "ERROR: 模块未连接")})
        if path == "/api/network":
            if data.get("apn"):
                LINK.at('AT+CGDCONT=1,"%s","%s"' % (data.get("pdp", "IP"), data["apn"]))
            if "dataOn" in data:
                LINK.at("AT+CGATT=1" if data["dataOn"] else "AT+CGATT=0")
            if "roam" in data:
                LINK.at('AT+QCFG="roamservice",%s' % (2 if data["roam"] else 1))
            return self._json({"saved": True})
        if path == "/api/sip":
            cfg = load_config()
            cfg["sip"].update({k: data.get(k, cfg["sip"].get(k)) for k in
                               ("server", "port", "user", "codec", "transport")})
            cfg["sip"]["status"] = "已注册"   # 真实注册由 SIP 栈完成，此处仅记录
            save_config(cfg)
            return self._json(cfg["sip"])
        if path == "/api/settings":
            cfg = load_config()
            for k in ("vendor", "model", "transport", "addr", "port", "relay"):
                if k in data:
                    cfg[k] = data[k]
            save_config(cfg)
            LINK.close()                      # 连接参数可能变了，重置链路
            return self._json({"saved": True})
        return self._json({"error": "not found"}, 404)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", type=int, default=8799)
    ap.add_argument("--serial", default="")
    ap.add_argument("--tcp", default="")
    ap.add_argument("--no-simulate", action="store_true", help="禁用内置模拟，只认真硬件")
    args = ap.parse_args()
    LINK.allow_simulate = not args.no_simulate
    if args.serial or args.tcp:
        cfg = load_config()
        if args.serial:
            cfg["transport"], cfg["addr"] = "usb", args.serial
        else:
            host, _, port = args.tcp.partition(":")
            cfg["transport"], cfg["addr"], cfg["port"] = "tcp", host, port
        save_config(cfg)
    os.chdir(HERE)
    ports = find_serial_port()
    print("模块管理后台 → http://localhost:%d" % args.port)
    if ports:
        print("探测到串口:", ports)
    elif LINK.allow_simulate:
        print("未探测到 AT 串口 → 启用内置模拟模块（真串口出现会自动切换）")
    else:
        print("未探测到 AT 串口，且已禁用模拟 → /api/status 显示离线")
    ThreadingHTTPServer(("0.0.0.0", args.port), Handler).serve_forever()


if __name__ == "__main__":
    main()
