#!/usr/bin/env bash
# รัน script DB ด้วยมือ (ทางที่ 2 / DBA) — ไฟล์ชุดเดียวกับที่แอปรันตอน start (db/)
#
#   scripts/db-apply.sh [--roles] [--seed]
#     --roles   รัน db/roles.sql ด้วย admin ก่อน (ครั้งแรกของ server / เปลี่ยนรหัส role)
#     --seed    รวม db/seed (ข้อมูล DEMO — dev / test เท่านั้น ห้ามใช้กับ production)
#
# ต้องมี docker (psql จาก image postgres, Flyway CLI จาก image flyway/flyway) — ต่อ DB ตรง ไม่ผ่าน PgBouncer
# env (หรือ .env ที่ root ของ repo — ค่าที่ตั้งใน env แล้วไม่ถูกทับ):
#   --roles: DB_ADMIN_URL (libpq URI ไม่มี user / รหัส), DB_ADMIN_USER, DB_ADMIN_PASSWORD,
#            DB_NAME, DB_OWNER_PASSWORD, DB_APP_PASSWORD, DB_PLATFORM_PASSWORD
#   migrate: DB_MIGRATE_URL (jdbc), DB_OWNER_USER, DB_OWNER_PASSWORD
#   DB_CA_FILE (ไม่บังคับ) = ไฟล์ CA ที่ URL อ้าง (sslrootcert=…) — mount เข้า container ที่ path เดียวกัน
#   DB_APPLY_NETWORK (ค่าเริ่ม host) = docker network ที่ใช้ต่อ DB
# บันทึกลง flyway_schema_history เหมือนแอปรัน → รันซ้ำไม่ทำอะไร, ไม่ข้ามไฟล์
set -euo pipefail

repo="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
roles=false
seed=false
for arg in "$@"; do
  case "$arg" in
    --roles) roles=true ;;
    --seed) seed=true ;;
    -h | --help) sed -n '2,17p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "ไม่รู้จัก option: $arg (ใช้ --roles / --seed)" >&2; exit 2 ;;
  esac
done

# อ่าน .env โดยไม่ทับค่าที่ตั้งไว้แล้ว (ไม่ eval — ค่าอาจมีอักขระพิเศษ)
if [[ -f "$repo/.env" ]]; then
  while IFS= read -r line || [[ -n "$line" ]]; do
    [[ "$line" =~ ^[A-Za-z_][A-Za-z0-9_]*= ]] || continue
    key="${line%%=*}"
    [[ -n "${!key+x}" ]] || export "$key=${line#*=}"
  done < "$repo/.env"
fi

need() {
  local missing=()
  for name in "$@"; do [[ -n "${!name:-}" ]] || missing+=("$name"); done
  if ((${#missing[@]})); then
    echo "ต้องตั้ง env: ${missing[*]}" >&2
    exit 2
  fi
}

docker_args=(--rm --network "${DB_APPLY_NETWORK:-host}")
if [[ -n "${DB_CA_FILE:-}" ]]; then
  docker_args+=(-v "$DB_CA_FILE:$DB_CA_FILE:ro")
fi

if $roles; then
  need DB_ADMIN_URL DB_ADMIN_USER DB_ADMIN_PASSWORD DB_OWNER_PASSWORD DB_APP_PASSWORD DB_PLATFORM_PASSWORD
  echo "== db/roles.sql (${DB_ADMIN_USER})"
  # รหัสส่งเป็น env ของ container (ไม่อยู่ใน command line)
  PGUSER="$DB_ADMIN_USER" PGPASSWORD="$DB_ADMIN_PASSWORD" \
    docker run "${docker_args[@]}" -i -e PGUSER -e PGPASSWORD \
    -e OWNER_PASSWORD="$DB_OWNER_PASSWORD" -e APP_PASSWORD="$DB_APP_PASSWORD" -e PLATFORM_PASSWORD="$DB_PLATFORM_PASSWORD" \
    -v "$repo/db:/db:ro" postgres:18.6 \
    sh -c 'exec psql "$0" -X -q -v db_name="$1" -v owner_password="$OWNER_PASSWORD" \
      -v app_password="$APP_PASSWORD" -v platform_password="$PLATFORM_PASSWORD" -f /db/roles.sql' \
    "$DB_ADMIN_URL" "${DB_NAME:-an22}"
fi

need DB_MIGRATE_URL DB_OWNER_USER DB_OWNER_PASSWORD
locations="filesystem:/an22-db/migration"
if $seed; then
  locations+=",filesystem:/an22-db/seed"
fi
echo "== flyway migrate (${DB_OWNER_USER}) ${locations}"
# ค่าเดียวกับ config/FlywayConfig ของแอป
FLYWAY_URL="$DB_MIGRATE_URL" FLYWAY_USER="$DB_OWNER_USER" FLYWAY_PASSWORD="$DB_OWNER_PASSWORD" \
  docker run "${docker_args[@]}" -e FLYWAY_URL -e FLYWAY_USER -e FLYWAY_PASSWORD \
  -e FLYWAY_LOCATIONS="$locations" -e FLYWAY_PLACEHOLDER_REPLACEMENT=false -e FLYWAY_CLEAN_DISABLED=true \
  -e REDGATE_DISABLE_TELEMETRY=true \
  -v "$repo/db:/an22-db:ro" flyway/flyway:12.4.0 migrate
