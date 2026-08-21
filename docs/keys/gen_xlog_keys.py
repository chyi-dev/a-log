#!/usr/bin/env python3
"""Generate Mars-xlog style secp256k1 key pair (dev only).

Output format matches Tencent mars gen_key.py:
  private: hex(privkey)
  public:  hex(pubkey_x) + hex(pubkey_y)   # 128 hex chars
"""
from __future__ import annotations

import argparse
from pathlib import Path

from cryptography.hazmat.primitives.asymmetric import ec


def generate() -> tuple[str, str]:
    key = ec.generate_private_key(ec.SECP256K1())
    priv = key.private_numbers().private_value.to_bytes(32, "big").hex()
    nums = key.public_key().public_numbers()
    pub = nums.x.to_bytes(32, "big").hex() + nums.y.to_bytes(32, "big").hex()
    return priv, pub


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--repo-root",
        type=Path,
        default=Path(__file__).resolve().parents[2],
        help="repository root (default: two levels above this script)",
    )
    args = parser.parse_args()
    root: Path = args.repo_root
    priv, pub = generate()

    priv_path = root / "docs" / "keys" / "xlog_private.hex"
    pub_path = root / "sample-xlog" / "src" / "main" / "assets" / "xlog_public.hex"
    priv_path.parent.mkdir(parents=True, exist_ok=True)
    pub_path.parent.mkdir(parents=True, exist_ok=True)
    priv_path.write_text(priv + "\n", encoding="utf-8")
    pub_path.write_text(pub + "\n", encoding="utf-8")
    print("wrote", priv_path)
    print("wrote", pub_path)
    print("public (appender_open parameter):")
    print(pub)


if __name__ == "__main__":
    main()
