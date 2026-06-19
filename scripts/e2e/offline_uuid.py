#!/usr/bin/env python3
import argparse
import hashlib
import uuid


def offline_uuid(username: str) -> uuid.UUID:
    digest = bytearray(hashlib.md5(f"OfflinePlayer:{username}".encode("utf-8")).digest())
    digest[6] = (digest[6] & 0x0F) | 0x30
    digest[8] = (digest[8] & 0x3F) | 0x80
    return uuid.UUID(bytes=bytes(digest))


def main():
    parser = argparse.ArgumentParser(description="Compute Minecraft offline-mode UUID")
    parser.add_argument("username")
    args = parser.parse_args()
    print(offline_uuid(args.username))


if __name__ == "__main__":
    main()

