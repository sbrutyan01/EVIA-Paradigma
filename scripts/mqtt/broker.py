#!/usr/bin/env python3
"""A very small MQTT 3.1.1 broker that checks what the generated client sends.

It accepts one client, answers CONNECT and SUBSCRIBE, writes every PUBLISH it receives to stdout,
then sends two messages back on the subscribed topic. Usage: broker.py [port]
"""
import socket, sys, threading, time

PORT = int(sys.argv[1]) if len(sys.argv) > 1 else 0


def text(b, i):
    n = (b[i] << 8) | b[i + 1]
    return b[i + 2:i + 2 + n].decode("utf-8", "replace"), i + 2 + n


def read_packet(f):
    head = f.read(1)
    if not head:
        return None, None
    length, mult = 0, 1
    while True:
        d = f.read(1)
        if not d:
            return None, None
        length += (d[0] & 0x7F) * mult
        mult *= 128
        if not d[0] & 0x80:
            break
    return head[0], f.read(length)


def publish(sock, topic, payload):
    body = len(topic).to_bytes(2, "big") + topic.encode() + payload.encode()
    out = bytearray([0x30])
    n = len(body)
    while True:
        d = n % 128
        n //= 128
        out.append(d | (0x80 if n else 0))
        if not n:
            break
    sock.sendall(bytes(out) + body)


def main():
    srv = socket.socket()
    srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    srv.bind(("127.0.0.1", PORT))
    srv.listen(1)
    print("port", srv.getsockname()[1], flush=True)
    sock, _ = srv.accept()
    f = sock.makefile("rb")
    subscribed = []
    while True:
        kind, body = read_packet(f)
        if kind is None:
            break
        if kind & 0xF0 == 0x10:                       # CONNECT
            name, i = text(body, 0)
            level = body[i]
            client, _ = text(body, i + 4)   # level, flags, keep alive
            print(f"connect {name} level {level} client {client}", flush=True)
            sock.sendall(bytes([0x20, 2, 0, 0]))      # CONNACK, accepted
        elif kind & 0xF0 == 0x30:                     # PUBLISH
            topic, i = text(body, 0)
            print(f"publish {topic} = {body[i:].decode('utf-8', 'replace')}", flush=True)
        elif kind & 0xF0 == 0x80:                     # SUBSCRIBE
            topic, i = text(body, 2)
            subscribed.append(topic)
            print(f"subscribe {topic} qos {body[i]}", flush=True)
            sock.sendall(bytes([0x90, 3, body[0], body[1], 0]))
            threading.Thread(target=answer, args=(sock, topic), daemon=True).start()
        elif kind & 0xF0 == 0xC0:                     # PINGREQ
            sock.sendall(bytes([0xD0, 0]))
    sock.close()


def answer(sock, topic):
    time.sleep(0.3)
    publish(sock, topic, "42")
    time.sleep(0.3)
    publish(sock, topic, "-3.5")


main()
