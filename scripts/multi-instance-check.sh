#!/usr/bin/env bash
# ทดสอบหลาย instance (แผนขั้น 13) — postgres + pgbouncer + api 2 ตัวหลัง load balancer (compose.multi.yaml, project an22-multi)
#
#   scripts/multi-instance-check.sh            สร้าง image api → DB ว่าง (initdb สร้าง role) → db-apply.sh migrate + seed → api1/api2/lb → ตรวจ → ลบทิ้ง
#   KEEP=1 scripts/multi-instance-check.sh     ไม่ลบ stack ตอนจบ (ลบเอง: docker compose -p an22-multi -f compose.multi.yaml down -v)
#   SKIP_BUILD=1 …                             ใช้ image an22-api:local ที่มีอยู่
#
# ตรวจ: 1) token ที่ออกโดย api1 ใช้กับ api2 ได้ 2) ขายพร้อมกัน 20 บิลจาก 2 instance (เครื่องเดียวกัน) → เลขบิลไม่ซ้ำ เรียงครบ
#       3) job billing (JOBS_CRON ทุก 10 วิ ทั้ง 2 ตัว) ออกใบแจ้งหนี้ใบเดียว + ดูว่าแต่ละรอบทำงานจริงกี่ตัว
# ต้องมี docker, curl, jq, openssl — รหัส / กุญแจสุ่มใหม่ทุกรอบ (ไฟล์ env ชั่วคราว ลบเมื่อจบ)
set -euo pipefail

repo="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo"
work="$(mktemp -d)"
env_file="$work/multi.env"
compose=(docker compose -p an22-multi -f compose.multi.yaml --env-file "$env_file")
LB=http://127.0.0.1:18080/api
API1=http://127.0.0.1:18081/api
API2=http://127.0.0.1:18082/api
fail=0

cleanup() {
  if [[ "${KEEP:-}" != 1 ]]; then
    "${compose[@]}" down -v --remove-orphans >/dev/null 2>&1 || true
  else
    cp "$env_file" "$repo/.multi.env.keep" 2>/dev/null && echo "เก็บ stack ไว้ — env อยู่ที่ .multi.env.keep (ไม่ commit)"
  fi
  rm -rf "$work"
}
trap cleanup EXIT

ok() { echo "  ✔ $*"; }
bad() { echo "  ✘ $*"; fail=1; }
secret() { openssl rand -base64 32 | tr -d '\n'; }

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
EOF

if [[ "${SKIP_BUILD:-}" != 1 ]]; then
  echo "== build image an22-api:local"
  docker build -q -t an22-api:local . >/dev/null
fi

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
for url in "$API1" "$API2" "$LB"; do
  for _ in $(seq 1 90); do
    curl -fs "$url/readyz" >/dev/null 2>&1 && break
    sleep 1
  done
  curl -fs "$url/readyz" >/dev/null || { echo "$url ไม่พร้อม"; "${compose[@]}" logs api1 api2 | tail -40; exit 1; }
done

# call <base> <method> <path> [token] [body] [extra curl args…] → body ของคำตอบ; status อยู่ใน $status_file
status_file="$work/status"
call() {
  local base=$1 method=$2 path=$3 token=${4:-} body=${5:-}
  shift 5 2>/dev/null || shift $#
  local args=(-s -o "$work/out" -w '%{http_code}' -X "$method" -H "X-Real-IP: 198.18.50.1")
  [[ -n "$token" ]] && args+=(-H "Authorization: Bearer $token")
  [[ -n "$body" ]] && args+=(-H 'Content-Type: application/json' --data "$body")
  curl "${args[@]}" "$@" "$base$path" >"$status_file"
  cat "$work/out"
}

echo "== 1) token ข้าม instance"
token=$(call "$API1" POST /auth/login "" '{"companyCode":"DEMO","username":"admin","password":"admin"}' | jq -r .token)
me=$(call "$API2" GET /me "$token")
if [[ "$(cat "$status_file")" == 200 && "$(jq -r .company.code <<<"$me")" == DEMO ]]; then
  ok "login ที่ api1 → GET /me ที่ api2 = 200 (DEMO)"
else
  bad "GET /me ที่ api2 ได้ $(cat "$status_file"): $me"
fi
upstreams=$(for _ in 1 2 3 4; do curl -s -o /dev/null -D - "$LB/healthz" | tr -d '\r' | awk -F': ' 'tolower($1)=="x-upstream"{print $2}'; done | sort -u | wc -l)
[[ "$upstreams" == 2 ]] && ok "load balancer สลับไปทั้ง 2 instance" || bad "load balancer ใช้ $upstreams instance"

echo "== 2) ขายพร้อมกันจาก 2 instance"
code="M$(openssl rand -hex 5 | tr a-f A-F)"
signup=$(jq -n --arg code "$code" '{companyCode:$code, companyName:"บริษัท ทดสอบหลายเครื่อง", taxId:"", vatRegistered:false,
  branch:{code:"HQ", name:"สำนักงานใหญ่", branchNo:"00000", address:"กรุงเทพ", phone:""},
  owner:{name:"เจ้าของ", username:"owner", email:"owner@multi.test", password:"secret"}, planId:"pro"}')
res=$(call "$LB" POST /auth/signup "" "$signup")
owner=$(jq -r .token <<<"$res")
branch=$(jq -r '.branches[0].id' <<<"$res")
[[ "$owner" != null ]] || { bad "signup ไม่ผ่าน: $res"; exit 1; }
call "$API1" POST /pos-terminals "$owner" "{\"id\":1,\"name\":\"เครื่อง 1\",\"posId\":\"\",\"branchId\":$branch,\"active\":true}" >/dev/null
cat=$(call "$API2" POST /categories "$owner" '{"code":"C","name":"หมวด","active":true}' | jq -r .id)
product=$(call "$API1" POST /products "$owner" "{\"sku\":\"P1\",\"name\":\"สินค้า\",\"categoryId\":$cat,\"unit\":\"ชิ้น\",\"serialControl\":false,\"serialPrefix\":\"\"}" \
  -H "X-Branch-Id: $branch" | jq -r .id)
call "$API2" POST /prices "$owner" "{\"productId\":$product,\"price\":100,\"startDate\":\"2020-01-01\",\"endDate\":null,\"note\":\"\"}" >/dev/null
call "$API1" PUT "/products/$product/stock" "$owner" '{"delta":100,"unitCost":60,"note":"รับ"}' -H "X-Branch-Id: $branch" -H "X-Terminal-Id: 1" >/dev/null
call "$API2" POST /shifts/open "$owner" '{"openingFloat":1000}' -H "X-Branch-Id: $branch" -H "X-Terminal-Id: 1" >/dev/null
cash=$(call "$API1" GET /payment-methods "$owner" | jq -r '.[0].id')
sale=$(jq -nc --argjson p "$product" --argjson m "$cash" '{items:[{productId:$p,factor:1,qty:1,serial:null}], freeSerials:[],
  payments:[{methodId:$m,amount:100,reference:""}], customer:"", expectedTotal:100}')
for i in $(seq 1 20); do
  base=$API1; (( i % 2 == 0 )) && base=$API2
  curl -s -o "$work/sale-$i" -w '%{http_code}\n' -X POST -H "Authorization: Bearer $owner" -H 'Content-Type: application/json' \
    -H "X-Branch-Id: $branch" -H "X-Terminal-Id: 1" --data "$sale" "$base/sales" >"$work/sale-$i.status" &
done
wait
codes=$(cat "$work"/sale-*.status | sort | uniq -c | tr -s ' ')
numbers=$(for i in $(seq 1 20); do jq -r .orderNo "$work/sale-$i"; done | sort)
unique=$(sort -u <<<"$numbers" | grep -c . || true)
if [[ "$codes" == " 20 200" && "$unique" == 20 ]]; then
  ok "20 บิลพร้อมกัน (api1 10 / api2 10) สำเร็จทั้งหมด เลขไม่ซ้ำ: $(head -1 <<<"$numbers") … $(tail -1 <<<"$numbers")"
else
  bad "ขายพร้อมกัน: status [$codes] เลขไม่ซ้ำ $unique/20"
fi
expected=$(seq -f '%04g' 1 20 | tr '\n' ' ')
got=$(sed 's/.*-//' <<<"$numbers" | tr '\n' ' ')
[[ "$got" == "$expected" ]] && ok "เลขต่อเนื่อง 0001–0020 ไม่มีข้าม" || bad "เลขบิล: $got"
stock=$(call "$API2" GET "/products/$product" "$owner" "" -H "X-Branch-Id: $branch" | jq -r .stock)
[[ "$stock" == 80 ]] && ok "สต็อกเหลือ 80 (ตัดครบ 20)" || bad "สต็อกเหลือ $stock"

echo "== 3) job billing ทั้ง 2 instance (ทุก 10 วิ)"
"${compose[@]}" exec -T db psql -q -U postgres -d an22 -c \
  "UPDATE dt_companies SET trial_ends_on = current_date - 1, next_invoice_on = current_date WHERE code = '$code'" >/dev/null
since=$(date -u +%Y-%m-%dT%H:%M:%SZ)
sleep 32
invoices=$("${compose[@]}" exec -T db psql -qAt -U postgres -d an22 -c \
  "SELECT count(*) FROM dt_billing_invoices i JOIN dt_companies c ON c.id = i.company_id WHERE c.code = '$code'")
[[ "$invoices" == 1 ]] && ok "ออกใบแจ้งหนี้ 1 ใบ (ไม่ซ้ำ)" || bad "ใบแจ้งหนี้ $invoices ใบ"
for svc in api1 api2; do
  logs=$("${compose[@]}" logs --since "$since" --no-log-prefix "$svc" 2>/dev/null)
  ran=$(grep -c 'billing job: บริษัทที่เปลี่ยน' <<<"$logs" || true)
  skipped=$(grep -c 'billing job: instance อื่นกำลังทำ' <<<"$logs" || true)
  echo "     $svc: ทำงาน $ran รอบ · ข้ามเพราะอีกตัวถือล็อก $skipped รอบ"
done

echo
if [[ $fail == 0 ]]; then echo "ผ่านทั้งหมด"; else echo "มีข้อที่ไม่ผ่าน"; exit 1; fi
