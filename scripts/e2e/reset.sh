#!/usr/bin/env bash
# ล้าง stack E2E (project an22-multi) แล้วเปิดใหม่จาก DB ว่าง — docs/e2e-test-plan.md หัวข้อ 2
#
#   scripts/e2e/reset.sh            down -v → up db pgbouncer → db-apply.sh --seed → up api1 api2 lb → รอ /readyz ทั้ง 3
#   BUILD=1 scripts/e2e/reset.sh    build image an22-api:local จาก working tree ก่อน (ไม่มี image = build เอง)
#   JOBS_CRON='*/10 * * * * *' …    เปิด job (ค่าเริ่ม - = ปิด; ข้อ 31 เท่านั้น)
#
# รหัส / กุญแจสุ่มใหม่ทุกครั้ง → .e2e/env (ignore แล้ว, ไม่ commit); commit ของ image → .e2e/image-commit
# ไม่แตะ DB dev (project an22-api) — ใช้ network / volume ของ an22-multi เท่านั้น
set -euo pipefail

repo="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$repo"
dir="$repo/.e2e"
env_file="$dir/env"
compose=(docker compose -p an22-multi -f compose.multi.yaml --env-file "$env_file")
mkdir -p "$dir"
chmod 700 "$dir"

secret() { openssl rand -base64 32 | tr -d '\n'; }

umask 077
cat >"$env_file" <<EOF
DB_NAME=an22
DB_SUPERUSER_PASSWORD=$(secret)
DB_ADMIN_PASSWORD=$(secret)
DB_OWNER_PASSWORD=$(secret)
DB_APP_PASSWORD=$(secret)
DB_PLATFORM_PASSWORD=$(secret)
PII_KEYS=1:$(secret)
PII_HASH_KEY=$(secret)
JWT_SECRET=1:$(secret)
SESSION_SECRET=$(secret)
JOBS_CRON=${JOBS_CRON:--}
EOF
umask 022

image_missing=false
docker image inspect an22-api:local >/dev/null 2>&1 || image_missing=true
if [[ "${BUILD:-}" == 1 ]] || $image_missing; then
  commit="$(git rev-parse --short HEAD)"
  [[ -z "$(git status --porcelain -- src pom.xml db Dockerfile)" ]] || commit="$commit+dirty"
  echo "== build image an22-api:local ($commit)"
  docker build -q -t an22-api:local --label "an22.commit=$commit" . >/dev/null
fi
docker image inspect -f '{{ index .Config.Labels "an22.commit" }}' an22-api:local >"$dir/image-commit"

echo "== DB ว่าง (initdb สร้าง role ด้วย admin จำลอง Azure)"
"${compose[@]}" down -v --remove-orphans >/dev/null 2>&1 || true
"${compose[@]}" up -d --wait db pgbouncer >/dev/null

echo "== migrate + seed ด้วย scripts/db-apply.sh (ทางที่ 2 — แอป FLYWAY_MIGRATE=false)"
(
  set -a; source "$env_file"; set +a
  DB_MIGRATE_URL="jdbc:postgresql://db:5432/an22" DB_OWNER_USER=an22_owner DB_APPLY_NETWORK=an22-multi_default \
    scripts/db-apply.sh --seed >/dev/null
)

echo "== api1 + api2 + lb"
"${compose[@]}" up -d api1 api2 lb >/dev/null
for url in http://127.0.0.1:18081/api http://127.0.0.1:18082/api http://127.0.0.1:18080/api; do
  for _ in $(seq 1 120); do
    curl -fs "$url/readyz" >/dev/null 2>&1 && break
    sleep 1
  done
  curl -fs "$url/readyz" >/dev/null || { echo "$url ไม่พร้อม"; "${compose[@]}" logs api1 api2 | tail -40; exit 1; }
done
echo "พร้อม — image $(cat "$dir/image-commit"), JOBS_CRON=$(grep '^JOBS_CRON=' "$env_file" | cut -d= -f2-)"
