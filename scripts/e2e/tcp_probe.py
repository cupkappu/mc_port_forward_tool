#!/usr/bin/env python3
import argparse
import socket
import threading
import time


def payload_for(index: int, size: int) -> bytes:
    seed = f"mc-transport-probe-{index}-".encode("ascii")
    return (seed * ((size // len(seed)) + 1))[:size]


def describe_mismatch(expected: bytes, actual: bytes, connections: int, size: int) -> str:
    first_diff = next(
        (i for i, (left, right) in enumerate(zip(expected, actual)) if left != right),
        min(len(expected), len(actual)),
    )
    matching_probe = next(
        (i for i in range(connections) if actual == payload_for(i, size)),
        None,
    )
    detail = f"first_diff={first_diff}"
    if matching_probe is not None:
        detail += f", actual_matches_probe={matching_probe}"
    return detail


def run_probe(host: str, port: int, timeout: float, index: int, size: int, connections: int):
    expected = payload_for(index, size)
    started = time.time()
    with socket.create_connection((host, port), timeout=timeout) as sock:
        sock.settimeout(timeout)
        sock.sendall(expected)
        received = bytearray()
        while len(received) < len(expected):
            chunk = sock.recv(len(expected) - len(received))
            if not chunk:
                break
            received.extend(chunk)
    elapsed = time.time() - started
    actual = bytes(received)
    if actual != expected:
        raise AssertionError(
            f"probe {index} mismatch: expected {len(expected)} bytes, got {len(actual)} bytes"
            f" ({describe_mismatch(expected, actual, connections, size)})"
        )
    print(f"probe {index} ok: {len(expected)} bytes in {elapsed:.3f}s", flush=True)


def main():
    parser = argparse.ArgumentParser(description="TCP round-trip probe for MC Transport")
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=25580)
    parser.add_argument("--timeout", type=float, default=10.0)
    parser.add_argument("--connections", type=int, default=1)
    parser.add_argument("--bytes", type=int, default=4096)
    args = parser.parse_args()

    errors = []

    def worker(index: int):
        try:
            run_probe(args.host, args.port, args.timeout, index, args.bytes, args.connections)
        except Exception as exc:
            errors.append((index, exc))

    threads = [
        threading.Thread(target=worker, args=(index,), daemon=False)
        for index in range(args.connections)
    ]
    for thread in threads:
        thread.start()
    for thread in threads:
        thread.join()

    if errors:
        for index, exc in errors:
            print(f"probe {index} failed: {exc}", flush=True)
        raise SystemExit(1)


if __name__ == "__main__":
    main()
