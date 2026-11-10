#!/usr/bin/env bash
# stack บนเครื่องนี้: api + web (container) ต่อ PostgreSQL + PgBouncer ที่เตรียมไว้แล้ว (docs/db-init.md)
#
#   scripts/local-stack.sh [up] [--build]   build (ถ้าสั่ง) → รัน api → รัน web → เช็ค
#   scripts/local-stack.sh down              หยุด + ลบ container api / web (DB / MongoDB ไม่แตะ — session ยังอยู่)
#   scripts/local-stack.sh status            สถานะ + เช็คซ้ำ
#     --build   build image ใหม่: an22-api:local, หน้าบ้าน (npm run build) + an22-web:latest (npm run docker:build)
#
# env (หรือ .env ที่ root ของ repo — ค่าที่ตั้งใน env แล้วไม่ถูกทับ):
#   DB_APP_PASSWORD, DB_PLATFORM_PASSWORD, PII_KEYS, PII_HASH_KEY, JWT_SECRET (บังคับ)
#   SESSION_SECRET (บังคับ, ≥ 32 ตัว — openssl rand -base64 48; เข้ารหัส session ใน MongoDB ต้องคงเดิม เปลี่ยน = ทุกคน login ใหม่)
#   STACK_NETWORK (an22-pg-net), STACK_DB (an22-pg), STACK_PGBOUNCER (an22-pgbouncer), DB_NAME (an22)
#   STACK_MONGO (an22-mongo — ไม่มีจะสร้างให้, volume STACK_MONGO_VOLUME = an22-mongo-data, ไม่เปิด port ออกนอก network)
#   WEB_PORT (14000), WEB_BIND (127.0.0.1 — ตั้ง 0.0.0.0 ให้เครื่องอื่นใน LAN เข้าได้)
#   FRONTEND_DIR (../FrontEnd-An22), WEB_DIR (../an22-web)
# DB ต้อง migrate แล้ว (scripts/db-apply.sh) — api validate อย่างเดียว (FLYWAY_MIGRATE=false), ไม่มี db/seed
set -euo pipefail

repo="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cmd=up
build=false
for arg in "$@"; do
  case "$arg" in
    up | down | status) cmd=$arg ;;
    --build) build=true ;;
    -h | --help) sed -n '2,17p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "ไม่รู้จัก: $arg (ใช้ up / down / status / --build)" >&2; exit 2 ;;
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

network=${STACK_NETWORK:-an22-pg-net}
db_host=${STACK_DB:-an22-pg}
pgb_host=${STACK_PGBOUNCER:-an22-pgbouncer}
mongo_host=${STACK_MONGO:-an22-mongo}
mongo_volume=${STACK_MONGO_VOLUME:-an22-mongo-data}
mongo_image=mongo:8.2 # 8.0 เปิดไม่ได้บน Linux kernel ≥ 6.19 (เหมือน compose.yaml ของ an22-web)
db_name=${DB_NAME:-an22}
web_port=${WEB_PORT:-14000}
web_bind=${WEB_BIND:-127.0.0.1}
frontend_dir=$(cd "${FRONTEND_DIR:-$repo/../FrontEnd-An22}" && pwd)
web_dir=$(cd "${WEB_DIR:-$repo/../an22-web}" && pwd)
base_url="http://localhost:$web_port"

# เงียบเมื่อผ่าน — พังแล้วแสดง output ท้าย
quiet() {
  local out
  if ! out=$("$@" 2>&1); then
    printf '%s\n' "$out" | tail -20 >&2
    return 1
  fi
}

running() { [[ "$(docker inspect -f '{{.State.Running}}' "$1" 2>/dev/null)" == true ]]; }

mongo_ok() { [[ "$(docker exec "$mongo_host" mongosh --quiet --eval 'db.runCommand({ ping: 1 }).ok' 2>/dev/null)" == 1 ]]; }

check() {
  local ok=true
  # /healthz กับ /api/readyz ไม่แตะ session — MongoDB ล่มแล้วยังผ่าน จึงต้องเช็คตรง ๆ
  if mongo_ok; then
    echo "✔ mongo: ping"
  else
    echo "✘ mongo: $mongo_host ไม่ตอบ (ทุก request ของคนที่ login อยู่จะได้ 500)"; ok=false
  fi
  if docker logs an22-api 2>&1 | grep -q 'flyway validate: ผ่าน'; then
    echo "✔ api: flyway validate ผ่าน"
  else
    echo "✘ api: ไม่เจอ flyway validate ผ่าน ใน log"; ok=false
  fi
  if [[ "$(curl -fs "http://127.0.0.1:$web_port/healthz" -o /dev/null -w '%{http_code}' 2>/dev/null)" == 200 ]]; then
    echo "✔ web: /healthz"
  else
    echo "✘ web: /healthz ไม่ตอบ"; ok=false
  fi
  local ready
  ready=$(curl -s "http://127.0.0.1:$web_port/api/readyz" 2>/dev/null || true)
  if [[ "$ready" == *'"ok"'* ]]; then
    echo "✔ web → api: /api/readyz $ready"
  else
    echo "✘ web → api: /api/readyz ตอบ '${ready:-ไม่ตอบ}'"; ok=false
  fi
  if $ok; then
    echo
    echo "พร้อมใช้: $base_url/platform/login (ผู้ดูแลแพลตฟอร์ม) · $base_url/auth/signup (สมัครบริษัทแรก)"
  fi
  $ok
}

case "$cmd" in
  down)
    docker rm -f an22-web an22-api >/dev/null 2>&1 || true
    echo "หยุด an22-web, an22-api แล้ว (DB / PgBouncer / MongoDB ยังเปิดอยู่)"
    exit 0
    ;;
  status)
    docker ps -a --filter name="^an22-api$" --filter name="^an22-web$" --filter name="^$db_host$" --filter name="^$pgb_host$" --filter name="^$mongo_host$" \
      --format 'table {{.Names}}\t{{.Status}}\t{{.Ports}}'
    echo
    check
    exit
    ;;
esac

# ---------- up ----------
missing=()
for name in DB_APP_PASSWORD DB_PLATFORM_PASSWORD PII_KEYS PII_HASH_KEY JWT_SECRET SESSION_SECRET; do
  [[ -n "${!name:-}" ]] || missing+=("$name")
done
if ((${#missing[@]})); then
  echo "ต้องตั้ง env: ${missing[*]}" >&2
  exit 2
fi
docker network inspect "$network" >/dev/null 2>&1 || { echo "ไม่มี docker network $network (STACK_NETWORK)" >&2; exit 1; }
for c in "$db_host" "$pgb_host"; do
  running "$c" || { echo "container $c ไม่ได้รันอยู่ — docker start $c" >&2; exit 1; }
done

if $build; then
  echo "== build an22-api:local"
  quiet docker build -t an22-api:local "$repo"
  echo "== build หน้าบ้าน ($frontend_dir)"
  (cd "$frontend_dir" && quiet npm run build)
  echo "== build an22-web:latest"
  (cd "$web_dir" && quiet npm run docker:build)
fi
for image in an22-api:local an22-web:latest; do
  docker image inspect "$image" >/dev/null 2>&1 || { echo "ไม่มี image $image — รันด้วย --build" >&2; exit 1; }
done

echo "== api"
docker rm -f an22-web an22-api >/dev/null 2>&1 || true
# ความลับส่งเป็น env ของ container โดยชื่อ (-e NAME) — ไม่อยู่ใน command line
export DB_APP_USER=${DB_APP_USER:-an22_app} DB_PLATFORM_USER=${DB_PLATFORM_USER:-an22_platform}
optional=()
for name in JWT_TTL_DAYS DB_POOL_SIZE JOBS_ENABLED SHUTDOWN_DRAIN LOG_BODY LOG_BODY_MAX; do
  [[ -n "${!name:-}" ]] && optional+=(-e "$name")
done
docker run -d --name an22-api --network "$network" --stop-timeout 50 \
  -e DB_URL="jdbc:postgresql://$pgb_host:6432/$db_name" \
  -e DB_MIGRATE_URL="jdbc:postgresql://$db_host:5432/$db_name" \
  -e FLYWAY_MIGRATE=false -e APP_BASE_URL="$base_url" \
  -e DB_APP_USER -e DB_APP_PASSWORD -e DB_PLATFORM_USER -e DB_PLATFORM_PASSWORD \
  -e PII_KEYS -e PII_HASH_KEY -e JWT_SECRET "${optional[@]}" \
  an22-api:local >/dev/null
for _ in $(seq 1 90); do
  running an22-api || break
  docker logs an22-api 2>&1 | grep -q 'Started Application' && break
  sleep 1
done
if ! running an22-api || ! docker logs an22-api 2>&1 | grep -q 'Started Application'; then
  # สาเหตุต้นทาง = Caused by ตัวสุดท้าย (ไม่มี = message ของ log ท้าย)
  cause=$(docker logs an22-api 2>&1 | grep -oE 'Caused by: [^\\]{0,300}' | tail -1 || true)
  [[ -n "$cause" ]] || cause=$(docker logs --tail 1 an22-api 2>&1 | grep -oE '"message":"[^"]{0,300}' || true)
  echo "✘ api ไม่ขึ้น: ${cause:-ดู docker logs an22-api}" >&2
  echo "  (log เต็ม: docker logs an22-api)" >&2
  exit 1
fi

echo "== mongo ($mongo_host)"
if ! running "$mongo_host"; then
  if docker inspect "$mongo_host" >/dev/null 2>&1; then
    docker start "$mongo_host" >/dev/null
  else
    # ไม่เปิด port และไม่ตั้งรหัสผ่าน: เข้าถึงได้จากใน docker network นี้เท่านั้น
    docker run -d --name "$mongo_host" --network "$network" -v "$mongo_volume:/data/db" "$mongo_image" >/dev/null
  fi
fi
for _ in $(seq 1 30); do
  mongo_ok && break
  sleep 1
done
mongo_ok || { echo "✘ mongo ไม่ขึ้น: ดู docker logs $mongo_host" >&2; exit 1; }

echo "== web ($web_bind:$web_port)"
docker run -d --name an22-web --network "$network" \
  -e API_URL=http://an22-api:8080 -e SESSION_SECRET -e SESSION_STORE=mongodb \
  -e MONGODB_URI="mongodb://$mongo_host:27017/an22" -e COOKIE_SECURE=false \
  -p "$web_bind:$web_port:4000" an22-web:latest >/dev/null
for _ in $(seq 1 30); do
  curl -fs "http://127.0.0.1:$web_port/healthz" >/dev/null 2>&1 && break
  sleep 1
done

echo "== เช็ค"
check
