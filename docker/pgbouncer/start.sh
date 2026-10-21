#!/bin/sh
# สร้าง userlist.txt ของ PgBouncer จาก env แล้วเปิด pgbouncer — รหัสไม่อยู่ใน repo / log
# env: DB_APP_USER, DB_APP_PASSWORD, DB_PLATFORM_USER, DB_PLATFORM_PASSWORD (รหัสห้ามมี ")
set -eu

: "${DB_APP_PASSWORD:?ต้องตั้ง DB_APP_PASSWORD}"
: "${DB_PLATFORM_PASSWORD:?ต้องตั้ง DB_PLATFORM_PASSWORD}"

umask 077
printf '"%s" "%s"\n"%s" "%s"\n' \
  "${DB_APP_USER:-an22_app}" "$DB_APP_PASSWORD" \
  "${DB_PLATFORM_USER:-an22_platform}" "$DB_PLATFORM_PASSWORD" > /tmp/userlist.txt

exec /usr/bin/pgbouncer "${PGBOUNCER_INI:-/an22/pgbouncer.ini}"
