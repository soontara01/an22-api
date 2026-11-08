#!/usr/bin/env bash
# สร้างเอกสาร DB (ER diagram + data dictionary) จาก db/migration ของ repo นี้
#
#   docs/db-manual/build.sh            DB ชั่วคราว → build/schema.json → build/db-manual.html → an22-db-manual.pdf
#   docs/db-manual/build.sh --check    ตรวจว่าทุกตาราง / คอลัมน์มีคำอธิบายใน descriptions.mjs (ไม่สร้าง PDF) — ขาด / เกิน → exit 1
#   docs/db-manual/build.sh --no-db    ใช้ build/schema.json เดิม (แก้แค่คำอธิบาย / หน้าตา)
#
# ต้องมี: docker (image postgres:18.6, flyway/flyway:12.4.0 — ชุดเดียวกับ scripts/db-apply.sh), Node 24, google-chrome,
# ฟอนต์ Noto Sans Thai — ไม่แตะ DB / stack อื่น: container + network ชั่วคราวถูกลบเสมอ, รหัสสุ่มใหม่ทุกรอบ
set -euo pipefail

dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo="$(cd "$dir/../.." && pwd)"
check=false
use_db=true
for arg in "$@"; do
  case "$arg" in
    --check) check=true ;;
    --no-db) use_db=false ;;
    -h | --help) sed -n '2,10p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "ไม่รู้จัก option: $arg (ใช้ --check / --no-db)" >&2; exit 2 ;;
  esac
done
mkdir -p "$dir/build"

if $use_db; then
  run="an22-dbdoc-$$"
  cleanup() {
    docker rm -f "$run-pg" >/dev/null 2>&1 || true
    docker network rm "$run" >/dev/null 2>&1 || true
  }
  trap cleanup EXIT
  docker network create "$run" >/dev/null

  # รหัสใช้ครั้งเดียว — DB นี้ไม่มีข้อมูลและถูกลบเมื่อจบ
  pw() { openssl rand -hex 16; }
  export POSTGRES_PASSWORD="$(pw)" DB_ADMIN_PASSWORD="$(pw)" DB_OWNER_PASSWORD="$(pw)" DB_APP_PASSWORD="$(pw)" \
    DB_PLATFORM_PASSWORD="$(pw)"
  echo "== PostgreSQL ชั่วคราว (roles.sql ผ่าน docker/initdb.sh)"
  docker run -d --name "$run-pg" --network "$run" \
    -e POSTGRES_PASSWORD -e DB_ADMIN_USER=azure_admin -e DB_ADMIN_PASSWORD -e DB_NAME=an22 \
    -e DB_OWNER_PASSWORD -e DB_APP_PASSWORD -e DB_PLATFORM_PASSWORD -e TZ=UTC \
    -v "$repo/db:/db:ro" -v "$repo/docker/initdb.sh:/docker-entrypoint-initdb.d/10-an22.sh:ro" \
    postgres:18.6 >/dev/null
  for _ in $(seq 1 60); do
    # TCP: ระหว่าง initdb server ฟังแค่ socket — พร้อมจริงเมื่อ TCP ตอบ
    docker exec "$run-pg" pg_isready -h 127.0.0.1 -U postgres -d an22 >/dev/null 2>&1 && break
    sleep 1
  done
  docker exec "$run-pg" pg_isready -h 127.0.0.1 -U postgres -d an22 >/dev/null

  echo "== flyway migrate (db/migration — ไม่มี seed)"
  FLYWAY_PASSWORD="$DB_OWNER_PASSWORD" docker run --rm --network "$run" \
    -e FLYWAY_URL="jdbc:postgresql://$run-pg:5432/an22" -e FLYWAY_USER=an22_owner -e FLYWAY_PASSWORD \
    -e FLYWAY_LOCATIONS=filesystem:/an22-db/migration -e FLYWAY_PLACEHOLDER_REPLACEMENT=false \
    -e FLYWAY_CLEAN_DISABLED=true -e REDGATE_DISABLE_TELEMETRY=true \
    -v "$repo/db:/an22-db:ro" flyway/flyway:12.4.0 migrate -q

  echo "== อ่าน catalog → build/schema.json"
  docker exec -i "$run-pg" psql -X -q -At -v ON_ERROR_STOP=1 -U postgres -d an22 <"$dir/catalog.sql" >"$dir/build/schema.json"
  cleanup
  trap - EXIT
elif [[ ! -f "$dir/build/schema.json" ]]; then
  echo "ยังไม่มี build/schema.json — รันครั้งแรกโดยไม่ใส่ --no-db" >&2
  exit 2
fi

if [[ ! -d "$dir/tools/node_modules/@viz-js/viz" ]]; then
  (cd "$dir/tools" && npm ci --ignore-scripts --no-audit --no-fund)
fi

if $check; then
  node "$dir/gen.mjs" --check
  exit 0
fi

echo "== สร้าง build/db-manual.html"
node "$dir/gen.mjs"

echo "== สร้าง an22-db-manual.pdf"
chrome="$(command -v google-chrome || command -v google-chrome-stable || command -v chromium)"
"$chrome" --headless=new --disable-gpu --no-pdf-header-footer --allow-file-access-from-files \
  --virtual-time-budget=10000 --print-to-pdf="$dir/an22-db-manual.pdf" "file://$dir/build/db-manual.html" 2>/dev/null
ls -l "$dir/an22-db-manual.pdf"
