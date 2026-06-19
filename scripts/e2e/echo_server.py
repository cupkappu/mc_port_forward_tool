#!/usr/bin/env python3
import argparse
import socket
import threading


def handle_client(conn, addr):
    with conn:
        print(f"client connected {addr[0]}:{addr[1]}", flush=True)
        while True:
            data = conn.recv(65536)
            if not data:
                break
            conn.sendall(data)
        print(f"client disconnected {addr[0]}:{addr[1]}", flush=True)


def main():
    parser = argparse.ArgumentParser(description="TCP echo target for MC Transport E2E tests")
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=10000)
    args = parser.parse_args()

    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as server:
        server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        server.bind((args.host, args.port))
        server.listen()
        print(f"echo server listening on {args.host}:{args.port}", flush=True)
        while True:
            conn, addr = server.accept()
            thread = threading.Thread(target=handle_client, args=(conn, addr), daemon=True)
            thread.start()


if __name__ == "__main__":
    main()

