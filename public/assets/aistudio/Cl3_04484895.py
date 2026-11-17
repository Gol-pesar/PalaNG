#!/usr/bin/env python3
import asyncio, socket, struct, sys, os, hmac, hashlib, argparse
import websockets

try:
    import uvloop
    asyncio.set_event_loop_policy(uvloop.EventLoopPolicy())
except ImportError:
    pass

UUID        = "63ae8279-fafa-4dd0-b2ef-9d6a4ec3ffdd"
WORKER_HOST = "twilight-dream-9e3c.aliahmadiphotoofficial.workers.dev"
WORKER_URL  = f"wss://{WORKER_HOST}/"
CLEAN_IP    = "172.67.70.244"
SUBPROTO    = "chat.v2"
MAX_CHUNK   = 65536
BUFFER_MAX  = 4194304

_WS_MAJOR = int(websockets.__version__.split(".")[0]) if hasattr(websockets, "__version__") else 12
_HEADERS_KWARG = "additional_headers" if _WS_MAJOR >= 14 else "extra_headers"
BO = sys.byteorder
_int_from_bytes = int.from_bytes

def derive_key(nonce):
    tb = UUID.encode()
    return bytes(tb[i % len(tb)] ^ nonce[i] for i in range(16))

def build_init(host, port, payload=b""):
    nonce = os.urandom(16)
    key = derive_key(nonce)
    try:
        socket.inet_pton(socket.AF_INET, host); at, addr = 1, socket.inet_aton(host)
    except OSError:
        try:
            socket.inet_pton(socket.AF_INET6, host); at, addr = 3, socket.inet_pton(socket.AF_INET6, host)
        except OSError:
            hb = host.encode(); at, addr = 2, bytes([len(hb)]) + hb
    body = bytes([1]) + struct.pack("!H", port) + bytes([at]) + addr + payload
    exp = key * (len(body) // len(key)) + key[:len(body) % len(key)]
    enc = (_int_from_bytes(body, BO) ^ _int_from_bytes(exp, BO)).to_bytes(len(body), BO)
    return nonce + enc + hmac.new(key, enc, hashlib.sha256).digest()[:8], key

async def ws_connect():
    kw = {"max_size": None, "open_timeout": 15, "subprotocols": [SUBPROTO],
          "server_hostname": WORKER_HOST, "compression": None, "ping_interval": None}
    if CLEAN_IP:
        ip = f"[{CLEAN_IP}]" if ":" in CLEAN_IP else CLEAN_IP
        url = WORKER_URL.replace(WORKER_HOST, ip)
        kw[_HEADERS_KWARG] = {"Host": WORKER_HOST}
        try:
            return await websockets.connect(url, **kw)
        except Exception:
            pass
    kw[_HEADERS_KWARG] = {}
    return await websockets.connect(WORKER_URL, **kw)

async def run(target_host, target_port, stdin_fd=0, stdout_fd=1):
    ws = await ws_connect()
    init, key = build_init(target_host, target_port)
    await ws.send(init)

    raw = key * ((MAX_CHUNK // len(key)) + 1)
    mv_key = memoryview(raw)
    key_cache = {}

    try:
        ack = await asyncio.wait_for(ws.recv(), timeout=10)
    except Exception:
        try: await ws.close()
        except Exception: pass
        return
    if len(ack) < 16:
        try: await ws.close()
        except Exception: pass
        return

    ack_l = len(ack)
    ack_k = _int_from_bytes(mv_key[:ack_l], BO)
    expect = hmac.new(key, b"OK", hashlib.sha256).digest()[:16]
    if (_int_from_bytes(ack, BO) ^ ack_k).to_bytes(ack_l, BO) != expect:
        try: await ws.close()
        except Exception: pass
        return

    loop = asyncio.get_running_loop()
    rd, wr = os.pipe()
    os.dup2(stdin_fd, rd)
    reader = asyncio.StreamReader()
    await loop.connect_read_pipe(
        lambda: asyncio.StreamReaderProtocol(reader),
        os.fdopen(rd, "rb", 0),
    )

    out = sys.stdout.buffer if stdout_fd == 1 else os.fdopen(stdout_fd, "wb", 0)

    async def up():
        ws_send = ws.send
        try:
            while True:
                d = await reader.read(MAX_CHUNK)
                if not d: break
                l = len(d)
                k = key_cache.get(l)
                if k is None:
                    k = _int_from_bytes(mv_key[:l], BO)
                    if len(key_cache) < 2048: key_cache[l] = k
                await ws_send((_int_from_bytes(d, BO) ^ k).to_bytes(l, BO))
        except Exception:
            pass
        finally:
            try: await ws.close()
            except Exception: pass

    async def down():
        try:
            async for m in ws:
                if not m: continue
                l = len(m)
                k = key_cache.get(l)
                if k is None:
                    k = _int_from_bytes(mv_key[:l], BO)
                    if len(key_cache) < 2048: key_cache[l] = k
                out.write((_int_from_bytes(m, BO) ^ k).to_bytes(l, BO))
                out.flush()
        except Exception:
            pass

    try:
        await asyncio.gather(up(), down())
    finally:
        try: os.close(wr)
        except Exception: pass
        try:
            if out is not sys.stdout.buffer: out.close()
        except Exception: pass

if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("host", nargs="?", default="example.com")
    ap.add_argument("port", nargs="?", type=int, default=443)
    a = ap.parse_args()
    try:
        asyncio.run(run(a.host, a.port))
    except KeyboardInterrupt:
        pass