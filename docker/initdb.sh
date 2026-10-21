#!/bin/sh
# docker-entrypoint-initdb.d ของ postgres (compose) — รันครั้งแรกที่ volume ว่างเท่านั้น
# 1) สร้าง admin ที่จำลองสิทธิ์ server admin ของ Azure (ไม่ใช่ superuser) 2) ใช้ admin นั้นรัน db/roles.sql
# รหัสจาก env (.env ของ compose) — ไม่อยู่ใน repo
set -eu

: "${DB_ADMIN_USER:?ต้องตั้ง DB_ADMIN_USER}"
: "${DB_ADMIN_PASSWORD:?ต้องตั้ง DB_ADMIN_PASSWORD}"

psql -v ON_ERROR_STOP=1 -q -U "$POSTGRES_USER" -d postgres \
  -v admin_user="$DB_ADMIN_USER" -v admin_password="$DB_ADMIN_PASSWORD" <<'SQL'
SELECT format('CREATE ROLE %I LOGIN CREATEDB CREATEROLE BYPASSRLS PASSWORD %L', :'admin_user', :'admin_password') \gexec
SQL

psql -v ON_ERROR_STOP=1 -q -U "$DB_ADMIN_USER" -d postgres \
  -v db_name="${DB_NAME:-an22}" \
  -v owner_password="${DB_OWNER_PASSWORD:-}" \
  -v app_password="${DB_APP_PASSWORD:-}" \
  -v platform_password="${DB_PLATFORM_PASSWORD:-}" \
  -f /db/roles.sql
