#!/usr/bin/env python3
"""Tạo hoặc cập nhật tài khoản ACTIVE (ADMIN/BUYER) với Argon2id hash, không lưu mật khẩu vào repo."""

import argparse
import getpass
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent


def hash_password(raw_password: str) -> str:
    try:
        from argon2 import PasswordHasher
    except ImportError:
        sys.exit("Thiếu thư viện argon2-cffi. Cài đặt bằng: pip install argon2-cffi")

    hasher = PasswordHasher(time_cost=2, memory_cost=16384, parallelism=1, hash_len=32, salt_len=16)
    return hasher.hash(raw_password)


def sql_literal(value: str | None) -> str:
    if value is None:
        return "NULL"
    return "'" + value.replace("'", "''") + "'"


def main() -> None:
    parser = argparse.ArgumentParser(description="Cấp tài khoản Vingo ACTIVE với mật khẩu Argon2id.")
    parser.add_argument("--email", required=True, help="Email đăng nhập")
    parser.add_argument("--name", required=True, help="Họ tên hiển thị")
    parser.add_argument("--role", required=True, choices=["BUYER", "ADMIN"], help="Vai trò (BUYER hoặc ADMIN)")
    parser.add_argument("--phone", default=None, help="Số điện thoại (tùy chọn)")
    parser.add_argument("--compose", default="deploy/compose.dev.yaml", help="Đường dẫn file docker compose")
    parser.add_argument("--print-sql-only", action="store_true", help="Chỉ in câu lệnh SQL, không chạy psql")
    args = parser.parse_args()

    email = args.email.strip().lower()
    name = args.name.strip()
    phone = args.phone.strip() if args.phone else None
    if not email or not name:
        sys.exit("--email và --name không được để trống")

    password = getpass.getpass("Nhập mật khẩu: ")
    if len(password) < 8:
        sys.exit("Mật khẩu phải có ít nhất 8 ký tự")
    confirm = getpass.getpass("Nhập lại mật khẩu: ")
    if password != confirm:
        sys.exit("Mật khẩu xác nhận không khớp")

    password_hash = hash_password(password)

    sql = (
        "INSERT INTO users (email, phone, full_name, password_hash, role, status) "
        "VALUES (:'email', NULLIF(:'phone', ''), :'name', :'hash', :'role', 'ACTIVE') "
        "ON CONFLICT (lower(email)) DO UPDATE SET "
        "full_name = EXCLUDED.full_name, "
        "phone = COALESCE(EXCLUDED.phone, users.phone), "
        "password_hash = EXCLUDED.password_hash, "
        "role = EXCLUDED.role, "
        "status = 'ACTIVE', "
        "locked_until = NULL, "
        "updated_at = now() "
        "RETURNING id, email, role, status;"
    )

    if args.print_sql_only:
        print(f"-- Hash Argon2id đã tạo cho {email} ({args.role})")
        print(
            "INSERT INTO users (email, phone, full_name, password_hash, role, status)\n"
            f"VALUES ({sql_literal(email)}, {sql_literal(phone)}, {sql_literal(name)}, "
            f"{sql_literal(password_hash)}, {sql_literal(args.role)}, 'ACTIVE')\n"
            "ON CONFLICT (lower(email)) DO UPDATE SET\n"
            "  full_name = EXCLUDED.full_name,\n"
            "  phone = COALESCE(EXCLUDED.phone, users.phone),\n"
            "  password_hash = EXCLUDED.password_hash,\n"
            "  role = EXCLUDED.role,\n"
            "  status = 'ACTIVE',\n"
            "  locked_until = NULL,\n"
            "  updated_at = now();"
        )
        return

    cmd = [
        "docker", "compose", "-f", str(ROOT / args.compose),
        "exec", "-T", "postgres",
        "psql", "-U", "vingo", "-d", "vingo",
        "-v", "ON_ERROR_STOP=1",
        "-v", f"email={email}",
        "-v", f"phone={phone or ''}",
        "-v", f"name={name}",
        "-v", f"hash={password_hash}",
        "-v", f"role={args.role}",
    ]
    res = subprocess.run(cmd, input=sql, text=True, check=False)
    if res.returncode != 0:
        sys.exit(res.returncode)


if __name__ == "__main__":
    main()
