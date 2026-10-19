#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
自包含 SOCKS5 服务器（无第三方依赖）——跑在大疆/移远模块的内置 Linux 上。
用法： python3 socks5.py [端口，默认 1080]
支持： SOCKS5 无认证 + CONNECT（TCP）。UDP ASSOCIATE 按需再加。
资源占用极低，适合模块那点存储/内存（48MB）。
"""
import socket, threading, select, sys

PORT = int(sys.argv[1]) if len(sys.argv) > 1 else 1080


def pipe(a, b):
    try:
        while True:
            r, _, _ = select.select([a, b], [], [], 300)
            if not r:
                break
            for s in r:
                d = s.recv(65536)
                if not d:
                    return
                (b if s is a else a).sendall(d)
    except Exception:
        pass
    finally:
        for s in (a, b):
            try:
                s.close()
            except Exception:
                pass


def handle(c):
    try:
        # 握手：客户端发 VER,NM,METHODS…；选无认证(0x00)
        hdr = c.recv(2)
        c.recv(hdr[1])
        c.sendall(b"\x05\x00")
        # 请求
        req = c.recv(4)
        atyp = req[3]
        if atyp == 1:
            host = socket.inet_ntoa(c.recv(4))
        elif atyp == 3:
            host = c.recv(c.recv(1)[0]).decode()
        else:
            host = socket.inet_ntoa(c.recv(16))
        port = int.from_bytes(c.recv(2), "big")
        up = socket.create_connection((host, port), 10)
        c.sendall(b"\x05\x00\x00\x01" + socket.inet_aton("0.0.0.0") + port.to_bytes(2, "big"))
        t = threading.Thread(target=pipe, args=(c, up), daemon=True)
        t.start()
        pipe(up, c)
    except Exception:
        try:
            c.close()
        except Exception:
            pass


def main():
    s = socket.socket()
    s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    s.bind(("0.0.0.0", PORT))
    s.listen(256)
    print("SOCKS5 listening on 0.0.0.0:%d" % PORT)
    sys.stdout.flush()
    while True:
        c, _ = s.accept()
        threading.Thread(target=handle, args=(c,), daemon=True).start()


if __name__ == "__main__":
    main()
