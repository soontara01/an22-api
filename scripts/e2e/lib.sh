# shellcheck shell=bash
# เครื่องมือร่วมของ scripts/e2e/NN-*.sh — docs/e2e-test-plan.md หัวข้อ 2
#
#   E2E_STEP=<ข้อ>; source "$(dirname "$0")/lib.sh"
#   case_id 3.1 "คำอธิบาย"                 ตั้ง case ปัจจุบัน (ทุก check พิมพ์ ✔ / ✘ พร้อม id)
#   call [@api1|@api2] METHOD PATH [BODY|-] [HEADER…]
#                                          → $STATUS, $BODY, $E2E_WORK/headers; ใส่ token / X-Branch-Id / X-Terminal-Id / X-Real-IP ให้
#   login CODE USER PASS [HEADER…]         → $TOKEN (ว่างเมื่อไม่ผ่าน), $STATUS — ไม่พิมพ์ (เรียกใน \$(…) แล้ว STATUS หาย)
#   as TOKEN|-  · branch ID|-  · terminal N|-   ค่าที่ใส่ใน call ถัดไป (- = ไม่ใส่)
#   expect_status N · expect_json '<jq>' ค่า · expect_msg "ข้อความ" · expect_header ชื่อ ค่า
#   sql "<query>" · expect_sql "<query>" ค่า · as_app TENANT "<sql>" (an22_app ผ่าน pgbouncer, TENANT ว่าง = ไม่ตั้ง)
#   std_company TAG [plan]                 ชุดข้อมูลมาตรฐาน (หัวข้อ 3) → ตัวแปร CODE TENANT BR_HQ … P_MS …, token T_OWNER
#   hmac VALUE (hex) · jwt CLAIMS_JSON [kid] · shop_claims TENANT USER_ID [ttl วินาที] · logs SVC SINCE
#   finish                                 ตรวจร่วม (5xx / ERROR / ความลับใน log / รูปแบบ error) + สรุป, exit ≠ 0 เมื่อมี ✘
#
# ต้องมี docker, curl, jq, openssl; stack ไม่ทำงาน → เปิดเอง (ไม่ล้าง DB — ล้างด้วย reset.sh)

set -euo pipefail

E2E_REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
E2E_DIR="$E2E_REPO/.e2e"
E2E_ENV="$E2E_DIR/env"
E2E_STEP="${E2E_STEP:-0}"
LB=http://127.0.0.1:18080/api
API1=http://127.0.0.1:18081/api
API2=http://127.0.0.1:18082/api
COMPOSE=(docker compose -p an22-multi -f "$E2E_REPO/compose.multi.yaml" --env-file "$E2E_ENV")

[[ -f "$E2E_ENV" ]] || { echo "ไม่มี .e2e/env — รัน scripts/e2e/reset.sh ก่อน" >&2; exit 2; }
E2E_WORK="$(mktemp -d)"
mkdir -p "$E2E_DIR/run"
E2E_TOKENS="$E2E_DIR/run/tokens-$E2E_STEP"   # token ที่ออกในรอบนี้ — finish ค้นใน log
: >"$E2E_TOKENS"
chmod 600 "$E2E_TOKENS"
trap 'rm -rf "$E2E_WORK"' EXIT

E2E_PASS=0
E2E_FAIL=0
E2E_CASE="-"
E2E_TOKEN=""
E2E_BRANCH=""
E2E_TERMINAL=""
E2E_ALLOW_5XX=${E2E_ALLOW_5XX:-0}   # จำนวน 5xx ที่ตั้งใจให้เกิดในข้อนี้
E2E_ALLOW_ERROR_LOG=${E2E_ALLOW_ERROR_LOG:-0}
E2E_SHAPE_BAD=0
STATUS=""
BODY=""
TOKEN=""

env_get() { grep -m1 "^$1=" "$E2E_ENV" | cut -d= -f2-; }

# ---------- stack ----------

ready() { curl -fs -m 3 "$1/readyz" >/dev/null 2>&1; }

ensure_stack() {
  if ready "$API1" && ready "$API2" && ready "$LB"; then
    return
  fi
  echo "== stack ไม่พร้อม — เปิด (ไม่ล้าง DB)"
  "${COMPOSE[@]}" up -d --wait db pgbouncer >/dev/null
  "${COMPOSE[@]}" up -d api1 api2 lb >/dev/null
  local url
  for url in "$API1" "$API2" "$LB"; do
    for _ in $(seq 1 120); do ready "$url" && break; sleep 1; done
    ready "$url" || { echo "$url ไม่พร้อม" >&2; exit 2; }
  done
}

ensure_stack
E2E_SINCE="$(date -u +%Y-%m-%dT%H:%M:%S.%NZ)"
E2E_IMAGE_COMMIT="$(cat "$E2E_DIR/image-commit" 2>/dev/null || echo '?')"
echo "== ข้อ $E2E_STEP · image $E2E_IMAGE_COMMIT · เริ่ม $(date '+%F %T %z')"

# ---------- ผล ----------

case_id() {
  E2E_CASE="$1"
  [[ $# -gt 1 ]] && echo "-- $1 ${*:2}"
  return 0
}
pass() { E2E_PASS=$((E2E_PASS + 1)); echo "  ✔ $E2E_CASE $*"; }
fail() { E2E_FAIL=$((E2E_FAIL + 1)); echo "  ✘ $E2E_CASE $*"; }
# check "คำอธิบาย" คาด ได้
check() {
  if [[ "$2" == "$3" ]]; then pass "$1 = $3"; else fail "$1 คาด [$2] ได้ [$3]"; fi
}

# ---------- HTTP ----------

as() { if [[ "$1" == - ]]; then E2E_TOKEN=""; else E2E_TOKEN="$1"; fi; }
branch() { if [[ "$1" == - ]]; then E2E_BRANCH=""; else E2E_BRANCH="$1"; fi; }
terminal() { if [[ "$1" == - ]]; then E2E_TERMINAL=""; else E2E_TERMINAL="$1"; fi; }

rand_ip() { echo "10.$((E2E_STEP % 256)).$((RANDOM % 256)).$((RANDOM % 254 + 1))"; }

# call [@api1|@api2] METHOD PATH [BODY|-] [HEADER…]
call() {
  local base=$LB
  case "${1:-}" in
    @api1) base=$API1; shift ;;
    @api2) base=$API2; shift ;;
    @lb) shift ;;
  esac
  local method=$1 path=$2 body=${3:--}
  shift 2
  [[ $# -gt 0 ]] && shift
  local args=(-s -m 60 -o "$E2E_WORK/body" -D "$E2E_WORK/headers" -w '%{http_code}' -X "$method")
  local h has_ip=false
  for h in "$@"; do
    [[ "${h,,}" == x-real-ip:* ]] && has_ip=true
    args+=(-H "$h")
  done
  $has_ip || args+=(-H "X-Real-IP: $(rand_ip)")
  [[ -n "$E2E_TOKEN" ]] && args+=(-H "Authorization: Bearer $E2E_TOKEN")
  [[ -n "$E2E_BRANCH" ]] && args+=(-H "X-Branch-Id: $E2E_BRANCH")
  [[ -n "$E2E_TERMINAL" ]] && args+=(-H "X-Terminal-Id: $E2E_TERMINAL")
  [[ "$body" != - ]] && args+=(-H 'Content-Type: application/json' --data-binary "$body")
  STATUS=$(curl "${args[@]}" "$base$path" || true)
  BODY=$(cat "$E2E_WORK/body" 2>/dev/null || true)
  # token ที่ออกให้ (signup / login) — finish ค้นว่าไม่หลุดไป log
  local tok
  tok=$(jq -r 'objects | .token // empty' <<<"$BODY" 2>/dev/null || true)
  [[ -n "$tok" ]] && echo "$tok" >>"$E2E_TOKENS"
  # ทุก error ของ api = {"message": "<ข้อความ>"} อย่างเดียว
  if [[ "$STATUS" =~ ^[45] ]] && ! jq -e 'type == "object" and keys == ["message"] and (.message | type == "string")' <<<"$BODY" >/dev/null 2>&1; then
    E2E_SHAPE_BAD=$((E2E_SHAPE_BAD + 1))
    echo "     (error ไม่ใช่ {message}: $method $path → $STATUS ${BODY:0:200})"
  fi
  return 0
}

# login CODE USER PASS [HEADER…] → $TOKEN (ว่าง = ไม่ผ่าน), $STATUS / $BODY ของ /auth/login
login() {
  local saved=$E2E_TOKEN
  E2E_TOKEN=""
  call POST /auth/login "$(jq -nc --arg c "$1" --arg u "$2" --arg p "$3" '{companyCode:$c, username:$u, password:$p}')" "${@:4}"
  E2E_TOKEN=$saved
  TOKEN=$(jq -r '.token // empty' <<<"$BODY" 2>/dev/null || true)
}

expect_status() {
  if [[ "$STATUS" == "$1" ]]; then pass "status $1"; else fail "status คาด $1 ได้ $STATUS: ${BODY:0:300}"; fi
}
expect_json() {
  local got
  got=$(jq -r "$1" <<<"$BODY" 2>/dev/null || echo "<jq error>")
  if [[ "$got" == "$2" ]]; then pass "$1 = $2"; else fail "$1 คาด [$2] ได้ [$got]"; fi
}
expect_msg() {
  local got
  got=$(jq -r '.message // empty' <<<"$BODY" 2>/dev/null || true)
  if [[ "$got" == "$1" ]]; then pass "ข้อความ \"$1\""; else fail "ข้อความคาด [$1] ได้ [$got] (status $STATUS)"; fi
}
expect_header() {
  local got
  got=$(tr -d '\r' <"$E2E_WORK/headers" | awk -v n="${1,,}" -F': ' 'tolower($1)==n{sub(/^[^:]*: /,""); print; exit}')
  if [[ "$got" == "$2" ]]; then pass "header $1 = $2"; else fail "header $1 คาด [$2] ได้ [$got]"; fi
}
header() { tr -d '\r' <"$E2E_WORK/headers" | awk -v n="${1,,}" -F': ' 'tolower($1)==n{sub(/^[^:]*: /,""); print; exit}'; }

# ---------- DB ----------

# sql "<query>" → ผลแบบ unaligned, คั่นด้วย | (superuser ต่อตรง ใน container db)
sql() { "${COMPOSE[@]}" exec -T db psql -qAtX -v ON_ERROR_STOP=1 -U postgres -d an22 -c "$1"; }
expect_sql() {
  local got
  got=$(sql "$1" 2>&1 || true)
  if [[ "$got" == "$2" ]]; then pass "sql = $2"; else fail "sql คาด [$2] ได้ [$got] — $1"; fi
}
# as_app TENANT "<sql>" → ผล + error (stderr) ของ an22_app ผ่าน pgbouncer ใน transaction เดียว; exit ≠ 0 เมื่อ error
as_app() {
  local tenant=$1 q=$2 setup=""
  [[ -n "$tenant" ]] && setup="\\o /dev/null
SELECT set_config('app.tenant_id', '$tenant', true);
\\o"
  printf 'BEGIN;\n%s\n%s;\nCOMMIT;\n' "$setup" "$q" |
    "${COMPOSE[@]}" exec -T -e PGPASSWORD="$(env_get DB_APP_PASSWORD)" db \
      psql -qAtX -v ON_ERROR_STOP=1 -h pgbouncer -p 6432 -U an22_app -d an22 2>&1
}

# ---------- กุญแจ ----------

b64_hex() { base64 -d <<<"$1" | od -An -tx1 -v | tr -d ' \n'; }
b64url() { base64 -w0 | tr '+/' '-_' | tr -d '='; }
# hmac VALUE → hex ของ HMAC-SHA256 ด้วย PII_HASH_KEY (= PiiCipher.hmac)
hmac() {
  printf '%s' "$1" | openssl dgst -sha256 -mac HMAC -macopt "hexkey:$(b64_hex "$(env_get PII_HASH_KEY)")" -binary | od -An -tx1 -v | tr -d ' \n'
}
# jwt CLAIMS_JSON [kid] → token HS256 เซ็นด้วย JWT_SECRET ของ stack (kid เริ่ม = ตัวสูงสุด)
jwt() {
  local claims=$1 kid=${2:-} secrets secret
  secrets=$(env_get JWT_SECRET)
  [[ -n "$kid" ]] || kid=$(tr ',' '\n' <<<"$secrets" | cut -d: -f1 | sort -n | tail -1)
  secret=$(tr ',' '\n' <<<"$secrets" | awk -F: -v k="$kid" '$1==k{sub(/^[^:]*:/,""); print}')
  local head body sig
  head=$(jq -nc --arg kid "$kid" '{kid:$kid, alg:"HS256"}' | b64url)
  body=$(jq -c . <<<"$claims" | tr -d '\n' | b64url)
  sig=$(printf '%s.%s' "$head" "$body" | openssl dgst -sha256 -mac HMAC -macopt "hexkey:$(b64_hex "$secret")" -binary | b64url)
  echo "$head.$body.$sig"
}
# shop_claims TENANT USER_ID [ttl วินาที] → claims แบบที่ JwtService.issueShop ออก
shop_claims() {
  local now
  now=$(date +%s)
  jq -nc --argjson t "$1" --arg sub "$2" --argjson now "$now" --argjson ttl "${3:-3600}" --arg jti "$(cat /proc/sys/kernel/random/uuid)" \
    '{iss:"an22-api", aud:["shop"], sub:$sub, iat:$now, exp:($now + $ttl), jti:$jti, tenantId:$t, role:"owner", allBranches:true, branchIds:[]}'
}

logs() { "${COMPOSE[@]}" logs --since "$2" --no-log-prefix "$1" 2>/dev/null; }

# ---------- ชุดข้อมูลมาตรฐาน (หัวข้อ 3) ----------

std_ok() {
  if [[ "$STATUS" != 200 ]]; then
    fail "std_company: $1 → $STATUS ${BODY:0:300}"
    return 1
  fi
  pass "std_company: $1"
}

# std_company TAG [plan] → CODE TENANT BR_HQ BR_BN U_OWNER U_BOSS U_MGR U_CASH1 U_CASH2 PM_CASH PM_CARD PM_QR PM_TRANSFER
#   CAT_IT CAT_MOUSE CAT_NB CAT_CBL CAT_SVC P_MS P_NB P_CBL P_SVC P_EXM, T_OWNER (token ของ signup); รหัส owner1234 / pass1234
std_company() {
  local tag=$1 plan=${2:-pro} saved_token=$E2E_TOKEN saved_branch=$E2E_BRANCH saved_terminal=$E2E_TERMINAL
  CODE="E${tag#E}$(openssl rand -hex 2 | tr a-f A-F)"
  local lower=${CODE,,}
  E2E_TOKEN="" E2E_BRANCH="" E2E_TERMINAL=""
  call POST /auth/signup "$(jq -nc --arg code "$CODE" --arg plan "$plan" --arg email "owner@$lower.e2e.test" '{
    companyCode:$code, companyName:("บริษัท ทดสอบ " + $code), taxId:"0105550123451", vatRegistered:true,
    branch:{code:"HQ", name:"สำนักงานใหญ่", branchNo:"00000", address:"1 ถนนสุขุมวิท กรุงเทพฯ", phone:"021234567"},
    owner:{name:"คุณเจ้าของ", username:"owner", email:$email, password:"owner1234"}, planId:$plan}')"
  std_ok "signup $CODE ($plan)" || return 1
  T_OWNER=$(jq -r .token <<<"$BODY")
  U_OWNER=$(jq -r .user.id <<<"$BODY")
  BR_HQ=$(jq -r '.branches[0].id' <<<"$BODY")
  TENANT=$(sql "SELECT id FROM dt_companies WHERE code = '$CODE'")
  as "$T_OWNER"

  call POST /branches '{"code":"BN","name":"สาขาบางนา","branchNo":"00001","address":"2 ถนนบางนา-ตราด กรุงเทพฯ","phone":"","receiptFooter":"","active":true}'
  std_ok "สาขา BN" || return 1
  BR_BN=$(jq -r .id <<<"$BODY")

  local u
  user() { # username name role allBranches branchIds email
    call POST /users "$(jq -nc --arg u "$1" --arg n "$2" --arg r "$3" --argjson all "$4" --argjson b "$5" --arg e "$6" \
      '{username:$u, name:$n, email:$e, role:$r, active:true, allBranches:$all, branchIds:$b, password:"pass1234"}')"
    std_ok "ผู้ใช้ $1" && u=$(jq -r .id <<<"$BODY")
  }
  user boss "คุณบอส" admin true '[]' "boss@$lower.e2e.test" || return 1; U_BOSS=$u
  user mgr "คุณผู้จัดการ" admin false "[$BR_HQ]" "" || return 1; U_MGR=$u
  user cash1 "แคชเชียร์หนึ่ง" user false "[$BR_HQ]" "" || return 1; U_CASH1=$u
  user cash2 "แคชเชียร์สอง" user false "[$BR_BN]" "" || return 1; U_CASH2=$u

  local t
  for t in "1 P-HQ-1 $BR_HQ" "2 P-HQ-2 $BR_HQ" "3 P-BN-3 $BR_BN"; do
    set -- $t
    call POST /pos-terminals "$(jq -nc --argjson id "$1" --arg pos "$2" --argjson b "$3" '{id:$id, name:("เครื่อง " + ($id|tostring)), posId:$pos, branchId:$b, active:true}')"
    std_ok "เครื่อง POS $1" || return 1
  done

  call GET /payment-methods
  PM_CASH=$(jq -r '.[] | select(.type == "cash") | .id' <<<"$BODY")
  call POST /payment-methods '{"code":"CARD","name":"บัตรเครดิต","type":"card","active":true,"requireReference":true,"referenceLabel":"เลขอนุมัติ","minAmount":300,"feePercent":1.6}'
  std_ok "ช่องทาง บัตรเครดิต" || return 1; PM_CARD=$(jq -r .id <<<"$BODY")
  call POST /payment-methods '{"code":"QR","name":"QR พร้อมเพย์","type":"qr","active":true,"promptPayId":"0812345678"}'
  std_ok "ช่องทาง QR" || return 1; PM_QR=$(jq -r .id <<<"$BODY")
  call POST /payment-methods '{"code":"TRANSFER","name":"โอนเงิน","type":"transfer","active":true}'
  std_ok "ช่องทาง โอนเงิน" || return 1; PM_TRANSFER=$(jq -r .id <<<"$BODY")

  cat_() { # code name parentId → id
    call POST /categories "$(jq -nc --arg c "$1" --arg n "$2" --argjson p "$3" '{code:$c, name:$n, parentId:$p, active:true}')"
    std_ok "หมวด $1" && u=$(jq -r .id <<<"$BODY")
  }
  cat_ IT "ไอที" null || return 1; CAT_IT=$u
  cat_ MOUSE "เมาส์" "$CAT_IT" || return 1; CAT_MOUSE=$u
  cat_ NB "โน้ตบุ๊ก" "$CAT_IT" || return 1; CAT_NB=$u
  cat_ CBL "สายเชื่อมต่อ" "$CAT_IT" || return 1; CAT_CBL=$u
  cat_ SVC "บริการ" "$CAT_IT" || return 1; CAT_SVC=$u

  branch "$BR_HQ"
  product() { # json → id
    call POST /products "$1"
    std_ok "สินค้า $(jq -r .sku <<<"$1")" && u=$(jq -r .id <<<"$BODY")
  }
  product "{\"sku\":\"MS-001\",\"name\":\"เมาส์\",\"categoryId\":$CAT_MOUSE,\"unit\":\"ชิ้น\",\"cost\":200}" || return 1; P_MS=$u
  product "{\"sku\":\"NB-001\",\"name\":\"โน้ตบุ๊ก\",\"categoryId\":$CAT_NB,\"unit\":\"เครื่อง\",\"cost\":20000,\"serialControl\":true,\"serialPrefix\":\"NB\"}" || return 1; P_NB=$u
  product "{\"sku\":\"CBL-001\",\"name\":\"สาย USB-C\",\"categoryId\":$CAT_CBL,\"unit\":\"เส้น\",\"cost\":40,\"packUnits\":[{\"unit\":\"กล่อง\",\"factor\":10,\"barcode\":\"\"}]}" || return 1; P_CBL=$u
  product "{\"sku\":\"SVC-001\",\"name\":\"ติดตั้งโปรแกรม\",\"categoryId\":$CAT_SVC,\"unit\":\"ครั้ง\",\"cost\":100,\"itemType\":\"service\"}" || return 1; P_SVC=$u
  product "{\"sku\":\"EXM-001\",\"name\":\"คู่มือ\",\"categoryId\":$CAT_CBL,\"unit\":\"เล่ม\",\"cost\":50,\"vatType\":\"exempt\"}" || return 1; P_EXM=$u

  local pp
  for pp in "$P_MS 350" "$P_NB 25000" "$P_CBL 99" "$P_SVC 499.90" "$P_EXM 120"; do
    set -- $pp
    call POST /prices "{\"productId\":$1,\"price\":$2,\"startDate\":\"2020-01-01\",\"endDate\":null,\"note\":\"\"}"
    std_ok "ราคา #$1 ฿$2" || return 1
  done

  stock_in() { # branch product qty cost
    branch "$1"
    call PUT "/products/$2/stock" "{\"delta\":$3,\"unitCost\":$4,\"note\":\"ยอดยกมา\"}"
    std_ok "รับเข้า #$2 $3 @$4 (สาขา #$1)"
  }
  stock_in "$BR_HQ" "$P_MS" 20 200 || return 1
  stock_in "$BR_HQ" "$P_CBL" 100 40 || return 1
  stock_in "$BR_HQ" "$P_EXM" 10 50 || return 1
  branch "$BR_HQ"
  call POST "/products/$P_NB/serials" '{"serials":["NB0001","NB0002","NB0003","NB0004","NB0005"],"unitCost":20000,"note":""}'
  std_ok "รับ serial NB0001–NB0005" || return 1
  stock_in "$BR_BN" "$P_MS" 5 220 || return 1

  E2E_TOKEN=$saved_token E2E_BRANCH=$saved_branch E2E_TERMINAL=$saved_terminal
  unset -f user cat_ product stock_in
  {
    declare -p CODE TENANT BR_HQ BR_BN U_OWNER U_BOSS U_MGR U_CASH1 U_CASH2 PM_CASH PM_CARD PM_QR PM_TRANSFER \
      CAT_IT CAT_MOUSE CAT_NB CAT_CBL CAT_SVC P_MS P_NB P_CBL P_SVC P_EXM
  } >"$E2E_DIR/run/std-$CODE.env"
  echo "     std_company $CODE (tenant $TENANT) → .e2e/run/std-$CODE.env"
}

# ---------- ตรวจร่วมท้ายทุก script ----------

finish() {
  case_id "ร่วม" "ตรวจร่วม: log / ความลับ / รูปแบบ error"
  local svc all="" n
  for svc in api1 api2; do all+="$(logs "$svc" "$E2E_SINCE")"$'\n'; done
  n=$(grep '"type":"access"' <<<"$all" | grep -cE '"status":"?5[0-9][0-9]' || true)
  if ((n <= E2E_ALLOW_5XX)); then pass "access log 5xx $n (อนุญาต $E2E_ALLOW_5XX)"; else fail "access log 5xx $n (อนุญาต $E2E_ALLOW_5XX)"; fi
  n=$(grep -c '"level":"ERROR"' <<<"$all" || true)
  if ((n <= E2E_ALLOW_ERROR_LOG)); then pass "log ERROR $n (อนุญาต $E2E_ALLOW_ERROR_LOG)"; else
    fail "log ERROR $n (อนุญาต $E2E_ALLOW_ERROR_LOG)"; grep '"level":"ERROR"' <<<"$all" | head -3 | cut -c1-300; fi
  # ความลับ: กุญแจ / รหัส DB / รหัสผู้ใช้ที่ใช้ทดสอบ / token ที่ออกในรอบนี้
  local secrets=() v
  for v in DB_SUPERUSER_PASSWORD DB_ADMIN_PASSWORD DB_OWNER_PASSWORD DB_APP_PASSWORD DB_PLATFORM_PASSWORD PII_HASH_KEY; do
    secrets+=("$(env_get "$v")")
  done
  secrets+=("$(env_get PII_KEYS | cut -d: -f2-)" "$(env_get JWT_SECRET | cut -d: -f2-)" owner1234 pass1234 ${E2E_EXTRA_SECRETS:-})
  local hits=0 s
  for s in "${secrets[@]}"; do [[ -n "$s" ]] && grep -qF -- "$s" <<<"$all" && hits=$((hits + 1)); done
  if [[ -s "$E2E_TOKENS" ]] && grep -qF -f "$E2E_TOKENS" <<<"$all"; then hits=$((hits + 1)); fi
  grep -qE 'eyJ[A-Za-z0-9_-]{10,}\.eyJ' <<<"$all" && hits=$((hits + 1))
  if ((hits == 0)); then pass "ไม่มีรหัสผ่าน / token / กุญแจใน log ($(grep -c . <<<"$all") บรรทัด)"; else fail "พบความลับใน log $hits ชนิด"; fi
  if ((E2E_SHAPE_BAD == 0)); then pass "ทุก error เป็น {\"message\": …}"; else fail "error ที่ไม่ใช่ {message} $E2E_SHAPE_BAD ตัว"; fi

  echo
  echo "== ข้อ $E2E_STEP · image $E2E_IMAGE_COMMIT · จบ $(date '+%F %T %z') · ✔ $E2E_PASS · ✘ $E2E_FAIL"
  ((E2E_FAIL == 0))
}
