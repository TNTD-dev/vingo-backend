#!/usr/bin/env python3
"""Giả IPN của VNPay có chữ ký hợp lệ, gửi vào API trên máy local.

Ví dụ:
  python scripts/simulate_ipn.py --order ABC123 --amount 500000 --result success
  python scripts/simulate_ipn.py --order ABC123 --amount 400000 --result success   # sai số tiền
  python scripts/simulate_ipn.py --order ABC123 --result success --repeat 2         # IPN gửi 2 lần
  python scripts/simulate_ipn.py --order ABC123 --bad-signature                     # chữ ký sai

--order là vnp_TxnRef (payment_intent.txn_ref). Bỏ --amount thì script đọc
orders.total_amount từ Postgres trong deploy/compose.dev.yaml.
Đọc VNPAY_HASH_SECRET, VNPAY_TMN_CODE từ .env.local. Chỉ dùng thư viện chuẩn.
"""
from __future__ import annotations

import argparse
import hashlib
import hmac
import os
import random
import subprocess
import sys
import urllib.parse
import urllib.request
from datetime import datetime, timedelta, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
VN_TZ = timezone(timedelta(hours=7))


def load_env(path: Path) -> dict[str, str]:
    env: dict[str, str] = {}
    if path.exists():
        for line in path.read_text(encoding="utf-8").splitlines():
            line = line.strip()
            if line and not line.startswith("#") and "=" in line:
                k, v = line.split("=", 1)
                env[k.strip()] = v.strip().strip('"').strip("'")
    env.update({k: v for k, v in os.environ.items() if k.startswith("VNPAY_")})
    return env


def amount_from_db(txn_ref: str) -> int:
    if not txn_ref.replace("-", "").isalnum():
        sys.exit("--order chỉ gồm chữ, số và dấu '-'")
    sql = (
        "SELECT o.total_amount FROM orders o "
        "LEFT JOIN payment_intent p ON p.order_id = o.id "
        f"WHERE p.txn_ref = '{txn_ref}' OR o.code = '{txn_ref}' LIMIT 1"
    )
    out = subprocess.run(
        ["docker", "compose", "-f", str(ROOT / "deploy/compose.dev.yaml"), "exec", "-T", "postgres",
         "psql", "-U", "vingo", "-d", "vingo", "-tAc", sql],
        capture_output=True, text=True, check=False,
    )
    value = out.stdout.strip()
    if not value:
        sys.exit(f"Không tìm thấy đơn {txn_ref} trong DB, hãy truyền --amount")
    return int(value)


def sign(params: dict[str, str], secret: str) -> str:
    # VNPay: sắp xếp theo tên, URL-encode giá trị (quote_plus), nối bằng &, HMAC-SHA512.
    query = "&".join(f"{k}={urllib.parse.quote_plus(v)}" for k, v in sorted(params.items()))
    return hmac.new(secret.encode(), query.encode(), hashlib.sha512).hexdigest()


def main() -> None:
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--order", required=True, help="vnp_TxnRef")
    p.add_argument("--amount", type=int, help="Số tiền VND (script tự nhân 100)")
    p.add_argument("--result", choices=["success", "failed"], default="success")
    p.add_argument("--repeat", type=int, default=1)
    p.add_argument("--bad-signature", action="store_true")
    p.add_argument("--base-url", default="http://localhost:8080")
    args = p.parse_args()

    env = load_env(ROOT / ".env.local")
    secret = env.get("VNPAY_HASH_SECRET")
    if not secret:
        sys.exit("Thiếu VNPAY_HASH_SECRET trong .env.local")
    amount = args.amount if args.amount is not None else amount_from_db(args.order)
    ok = args.result == "success"

    params = {
        "vnp_TmnCode": env.get("VNPAY_TMN_CODE", "DEVTMN01"),
        "vnp_Amount": str(amount * 100),
        "vnp_BankCode": "NCB",
        "vnp_BankTranNo": f"VNP{random.randint(10**7, 10**8 - 1)}",
        "vnp_CardType": "ATM",
        "vnp_PayDate": datetime.now(VN_TZ).strftime("%Y%m%d%H%M%S"),
        "vnp_OrderInfo": f"Thanh toan don {args.order}",
        "vnp_TransactionNo": str(random.randint(10**7, 10**8 - 1)),
        "vnp_ResponseCode": "00" if ok else "24",
        "vnp_TransactionStatus": "00" if ok else "02",
        "vnp_TxnRef": args.order,
    }
    params["vnp_SecureHash"] = "0" * 128 if args.bad_signature else sign(params, secret)
    url = f"{args.base_url}/api/v1/payments/vnpay/ipn?{urllib.parse.urlencode(params)}"

    for i in range(args.repeat):
        try:
            with urllib.request.urlopen(url, timeout=10) as resp:
                body = resp.read().decode()
                print(f"[{i + 1}] HTTP {resp.status}: {body}")
        except urllib.error.HTTPError as e:
            print(f"[{i + 1}] HTTP {e.code}: {e.read().decode()}")
        except urllib.error.URLError as e:
            sys.exit(f"Không gọi được API ({e.reason}). API đã chạy ở {args.base_url} chưa?")


if __name__ == "__main__":
    main()
