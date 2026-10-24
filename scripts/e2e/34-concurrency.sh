#!/usr/bin/env bash
# ข้อ 34 — พร้อมกันข้าม instance (docs/e2e-test-plan.md)
# ยิงด้วย curl ใน background สลับ api1 / api2 แล้ว wait; ผลของแต่ละ request เก็บเป็นไฟล์ <prefix>.<i>.status / .body
E2E_STEP=34
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

today=$(TZ=Asia/Bangkok date +%F)
ymd=$(TZ=Asia/Bangkok date +%Y%m%d)
std_company E34
login "$CODE" boss pass1234; T_BOSS=$TOKEN
login "$CODE" mgr pass1234; T_MGR=$TOKEN
login "$CODE" cash1 pass1234; T_CASH1=$TOKEN
login "$CODE" cash2 pass1234; T_CASH2=$TOKEN

# fire PREFIX I METHOD PATH BODY TOKEN [HEADER…] — 1 request (เรียกพร้อม &); I คู่ = api1 / คี่ = api2
fire() {
  local prefix=$1 i=$2 method=$3 path=$4 body=$5 token=$6; shift 6
  local base=$API1; ((i % 2)) && base=$API2
  local args=(-s -m 120 -o "$E2E_WORK/$prefix.$i.body" -w '%{http_code}' -X "$method" -H "X-Real-IP: $(rand_ip)" -H "Authorization: Bearer $token")
  local h; for h in "$@"; do args+=(-H "$h"); done
  [[ "$body" != - ]] && args+=(-H 'Content-Type: application/json' --data-binary "$body")
  curl "${args[@]}" "$base$path" >"$E2E_WORK/$prefix.$i.status" 2>/dev/null || echo 000 >"$E2E_WORK/$prefix.$i.status"
}
dist() { local f; for f in "$E2E_WORK/$1".*.status; do cat "$f"; echo; done | sort | uniq -c | awk '{printf "%s×%s ", $2, $1}' | sed 's/ $//'; }
msgs() { for f in "$E2E_WORK/$1".*.body; do jq -r '.message // empty' "$f" 2>/dev/null; done | sort | uniq -c | awk '{c=$1; $1=""; printf "%s×%s;", substr($0, 2), c}'; }
ok_count() { grep -l '^200$' "$E2E_WORK/$1".*.status 2>/dev/null | grep -c . || true; }
stock() { sql "SELECT stock FROM dt_branch_stock WHERE tenant_id = $TENANT AND branch_id = $1 AND product_id = $2"; }
sale_body() { jq -nc --argjson p "$1" --argjson q "${2:-1}" --arg s "${3:-}" --argjson t "$4" --argjson m "$PM_CASH" \
  '{items:[{productId:$p, factor:1, qty:$q, serial:(if $s == "" then null else $s end)}], freeSerials:[], payments:[{methodId:$m, amount:$t, reference:""}], customer:"", expectedTotal:$t}'; }
HQ=("X-Branch-Id: $BR_HQ" "X-Terminal-Id: 1")
as "$T_CASH1"; branch "$BR_HQ"; terminal 1
call POST /shifts/open '{"openingFloat":0,"openingDenominations":[]}'
as -; branch -; terminal -

case_id 34.1 "ขาย 40 บิลพร้อมกันเครื่องเดียว"
cbl=$(stock "$BR_HQ" "$P_CBL")
body=$(sale_body "$P_CBL" 1 "" 99)
for i in $(seq 1 40); do fire s1 "$i" POST /sales "$body" "$T_CASH1" "${HQ[@]}" & done; wait
check "status" "200×40" "$(dist s1)"
nums=$(for f in "$E2E_WORK"/s1.*.body; do jq -r .orderNo "$f"; done | sort)
check "เลข 0001–0040 ไม่ซ้ำไม่ข้าม" "$(seq -f "POS-HQ-1-$ymd-%04g" 1 40 | tr '\n' ' ')" "$(tr '\n' ' ' <<<"$nums")"
check "CBL ลดครบ 40" "$((cbl - 40))" "$(stock "$BR_HQ" "$P_CBL")"
expect_sql "SELECT count(*) || ':' || count(DISTINCT balance_qty) || ':' || min(balance_qty) || ':' || max(balance_qty) FROM dt_stock_movements
  WHERE tenant_id = $TENANT AND product_id = $P_CBL AND type = 'issue'" "40:40:$((cbl - 40)):$((cbl - 1))"
expect_sql "SELECT count(*) FROM (SELECT balance_qty - lag(balance_qty) OVER (ORDER BY id) d FROM dt_stock_movements WHERE tenant_id = $TENANT AND product_id = $P_CBL) x WHERE d IS NOT NULL AND d <> -1" 0
expect_sql "SELECT last_no FROM dt_doc_counters WHERE tenant_id = $TENANT AND prefix = 'POS-HQ-1-$ymd-'" 40

case_id 34.2 "ขายชิ้นสุดท้าย 10 request พร้อมกัน"
as "$T_BOSS"; branch "$BR_HQ"
call POST /products "{\"sku\":\"ONE-001\",\"name\":\"ชิ้นสุดท้าย\",\"categoryId\":$CAT_CBL,\"unit\":\"ชิ้น\",\"cost\":10}"; ONE=$(jq -r .id <<<"$BODY")
call POST /prices "{\"productId\":$ONE,\"price\":100,\"startDate\":\"2020-01-01\",\"endDate\":null,\"note\":\"\"}"
call PUT "/products/$ONE/stock" '{"delta":1,"unitCost":10,"note":""}'
as -; branch -
body=$(sale_body "$ONE" 1 "" 100)
for i in $(seq 1 10); do fire s2 "$i" POST /sales "$body" "$T_CASH1" "${HQ[@]}" & done; wait
echo "     status: $(dist s2) · ข้อความ: $(msgs s2)"
check "สำเร็จ 1 ตัว ที่เหลือ 400" "200×1 400×9" "$(dist s2)"
check "ข้อความของที่ไม่ผ่าน = สต็อกไม่พอ (ledger หรือ ตะกร้า)" 0 "$(msgs s2 | tr ';' '\n' | grep -vE '^$|^สต็อกคงเหลือไม่พอ×|^ONE-001 สต็อกไม่พอ \(คงเหลือ 0 ชิ้น\)×' | grep -c . || true)"
check "stock 0 ไม่ติดลบ, บิล 1 ใบ" "0 1" "$(stock "$BR_HQ" "$ONE") $(sql "SELECT count(*) FROM dt_sale_lines WHERE tenant_id = $TENANT AND product_id = $ONE")"
expect_sql "SELECT last_no FROM dt_doc_counters WHERE tenant_id = $TENANT AND prefix = 'POS-HQ-1-$ymd-'" 41

case_id 34.3 "ขาย serial เดียวกันพร้อมกัน 2 instance"
body=$(sale_body "$P_NB" 1 NB0003 25000)
for i in 1 2 3 4; do fire s3 "$i" POST /sales "$body" "$T_CASH1" "${HQ[@]}" & done; wait
echo "     status: $(dist s3) · ข้อความ: $(msgs s3)"
check "สำเร็จ 1" 1 "$(ok_count s3)"
check "ไม่มี 5xx" 0 "$(grep -l '^5' "$E2E_WORK"/s3.*.status 2>/dev/null | grep -c . || true)"
expect_sql "SELECT (SELECT count(*) FROM dt_sale_lines WHERE tenant_id = $TENANT AND serial = 'NB0003') || ':' || (SELECT status FROM dt_serial_numbers WHERE tenant_id = $TENANT AND serial = 'NB0003')" "1:sold"

case_id 34.4 "เปิดกะ / รับใบโอน / ยกเลิกบิล / ใบลดหนี้ พร้อมกัน"
fire o4 0 POST /shifts/open '{"openingFloat":0,"openingDenominations":[]}' "$T_BOSS" "X-Branch-Id: $BR_HQ" "X-Terminal-Id: 2" &
fire o4 1 POST /shifts/open '{"openingFloat":0,"openingDenominations":[]}' "$T_MGR" "X-Branch-Id: $BR_HQ" "X-Terminal-Id: 2" &
wait
echo "     เปิดกะ: $(dist o4) · $(msgs o4)"
check "เปิดกะเครื่องเดียวกัน: สำเร็จ 1 + 400 อีกตัว" "200×1 400×1" "$(dist o4)"
expect_sql "SELECT count(*) FROM dt_shifts WHERE tenant_id = $TENANT AND terminal_id = 2 AND status = 'open'" 1
owner2=$T_MGR; grep -q '^200$' "$E2E_WORK/o4.0.status" && owner2=$T_BOSS   # คนที่เปิดกะเครื่อง 2 ได้
as "$T_BOSS"; branch "$BR_HQ"
call POST /transfers "{\"toBranchId\":$BR_BN,\"lines\":[{\"productId\":$P_MS,\"qty\":2,\"serials\":[]}],\"note\":\"\"}"; TR=$(jq -r .id <<<"$BODY")
as -; branch -
bn_ms=$(stock "$BR_BN" "$P_MS")
rcv="{\"lines\":[{\"productId\":$P_MS,\"receivedQty\":2,\"receivedSerials\":[]}],\"shortReason\":\"\"}"
fire r4 0 POST "/transfers/$TR/receive" "$rcv" "$T_CASH2" "X-Branch-Id: $BR_BN" &
fire r4 1 POST "/transfers/$TR/receive" "$rcv" "$T_BOSS" "X-Branch-Id: $BR_BN" &
wait
echo "     รับใบโอน: $(dist r4) · $(msgs r4)"
check "รับใบโอนเดียวกัน: สำเร็จ 1" "200×1 400×1" "$(dist r4)"
check "BN MS +2 ครั้งเดียว" "$((bn_ms + 2))" "$(stock "$BR_BN" "$P_MS")"
as "$T_CASH1"; branch "$BR_HQ"; terminal 1
call POST /sales "$(sale_body "$P_MS" 2 "" 700)"; VS=$(jq -r .id <<<"$BODY")
call POST /sales "$(sale_body "$P_MS" 2 "" 700)"; CS=$(jq -r .id <<<"$BODY")
sql "UPDATE dt_sales SET sold_at = sold_at - interval '1 day' WHERE id = $CS" >/dev/null
as -; branch -; terminal -
ms=$(stock "$BR_HQ" "$P_MS")
vbody='{"reason":"ยกเลิกพร้อมกัน","approval":{"username":"boss","password":"pass1234"}}'
for i in 0 1 2 3; do fire v4 "$i" POST "/sales/$VS/void" "$vbody" "$T_CASH1" "${HQ[@]}" & done; wait
echo "     ยกเลิกบิล: $(dist v4) · $(msgs v4)"
check "ยกเลิกบิลเดียวกัน: สำเร็จ 1" "200×1 400×3" "$(dist v4)"
check "MS คืน 2 ครั้งเดียว" "$((ms + 2))" "$(stock "$BR_HQ" "$P_MS")"
cn="{\"lines\":[{\"saleLineIndex\":0,\"qty\":2,\"restock\":true}],\"refunds\":[{\"methodId\":$PM_CASH,\"amount\":700,\"reference\":\"\"}],\"reason\":\"คืนพร้อมกัน\",\"expectedTotal\":700}"
for i in 0 1 2 3; do fire c4 "$i" POST "/sales/$CS/credit-notes" "$cn" "$owner2" "X-Branch-Id: $BR_HQ" "X-Terminal-Id: 2" & done; wait
echo "     ใบลดหนี้: $(dist c4) · $(msgs c4)"
check "ใบลดหนี้คืนจนเกิน: สำเร็จ 1" "200×1 400×3" "$(dist c4)"
expect_sql "SELECT count(*) || ':' || coalesce(sum(total), 0) FROM dt_credit_notes WHERE tenant_id = $TENANT AND sale_id = $CS" "1:700.00"

case_id 34.5 "starter: เพิ่มผู้ใช้ 5 request เมื่อเหลือที่ 1"
S_CODE="E34S$(openssl rand -hex 2 | tr a-f A-F)"
call POST /auth/signup "$(jq -nc --arg c "$S_CODE" '{companyCode:$c, companyName:"starter", taxId:"", vatRegistered:false,
  branch:{code:"HQ", name:"สำนักงานใหญ่", branchNo:"00000", address:"กรุงเทพฯ", phone:""},
  owner:{name:"เจ้าของ", username:"owner", email:("owner@" + ($c | ascii_downcase) + ".e2e.test"), password:"owner1234"}, planId:"starter"}')"
T_S=$(jq -r .token <<<"$BODY"); S_HQ=$(jq -r '.branches[0].id' <<<"$BODY"); S=$(sql "SELECT id FROM dt_companies WHERE code = '$S_CODE'")
as "$T_S"
for u in user1 user2 user3; do
  call POST /users "{\"username\":\"$u\",\"name\":\"$u\",\"email\":\"\",\"role\":\"user\",\"active\":true,\"allBranches\":false,\"branchIds\":[$S_HQ],\"password\":\"pass1234\"}"
done
as -
for i in 0 1 2 3 4; do
  fire u5 "$i" POST /users "{\"username\":\"race$i\",\"name\":\"race$i\",\"email\":\"\",\"role\":\"user\",\"active\":true,\"allBranches\":false,\"branchIds\":[$S_HQ],\"password\":\"pass1234\"}" "$T_S" &
done; wait
echo "     $(dist u5) · $(msgs u5)"
check "สำเร็จ 1 ที่เหลือ 403 เพดานแพ็กเกจ" "200×1 403×4" "$(dist u5)"
expect_sql "SELECT count(*) FROM dt_users WHERE tenant_id = $S AND active" 5

case_id 34.6 "ราคาช่วงทับกันพร้อมกัน 2 request"
as "$T_BOSS"; branch "$BR_HQ"
call POST /products "{\"sku\":\"PRC-001\",\"name\":\"ราคาพร้อมกัน\",\"categoryId\":$CAT_CBL,\"unit\":\"ชิ้น\",\"cost\":1}"; PRC=$(jq -r .id <<<"$BODY")
as -; branch -
for i in 0 1 2 3; do
  fire p6 "$i" POST /prices "{\"productId\":$PRC,\"price\":$((10 + i)),\"startDate\":\"2026-01-01\",\"endDate\":null,\"note\":\"\"}" "$T_BOSS" &
done; wait
echo "     $(dist p6) · $(msgs p6)"
check "สำเร็จ 1" 1 "$(ok_count p6)"
check "ที่เหลือ 400 ช่วงทับ (ไม่ใช่ 5xx)" 3 "$(for f in "$E2E_WORK"/p6.*.body; do jq -r '.message // empty' "$f"; done | grep -c '^ช่วงวันที่ทับกับราคา' || true)"
expect_sql "SELECT count(*) FROM dt_sku_prices WHERE tenant_id = $TENANT AND product_id = $PRC" 1

case_id 34.7 "login ผิดพร้อมกัน 20 request บัญชีเดียว"
for i in $(seq 1 20); do
  base=$API1; ((i % 2)) && base=$API2
  curl -s -m 60 -o "$E2E_WORK/l7.$i.body" -w '%{http_code}' -X POST -H 'Content-Type: application/json' -H "X-Real-IP: $(rand_ip)" \
    --data "{\"companyCode\":\"$CODE\",\"username\":\"mgr\",\"password\":\"wrong$i\"}" "$base/auth/login" >"$E2E_WORK/l7.$i.status" &
done; wait
echo "     $(dist l7) · แถวตัวนับ: $(sql "SELECT failures || ' locked=' || (locked_until IS NOT NULL) FROM dt_login_throttle WHERE key_hash = decode('$(hmac "shop:$CODE:mgr")', 'hex')")"
n401=$(grep -l '^401$' "$E2E_WORK"/l7.*.status | grep -c . || true)
row=$(sql "SELECT failures || ':' || (locked_until IS NOT NULL) FROM dt_login_throttle WHERE key_hash = decode('$(hmac "shop:$CODE:mgr")', 'hex')")
check "401 อย่างน้อย 5 ที่เหลือ 429" yes "$([[ $n401 -ge 5 && $(dist l7) =~ ^401×[0-9]+( 429×[0-9]+)?$ ]] && echo yes || echo "no ($(dist l7))")"
check "failures = จำนวน 401 (ตัวนับไม่หาย), ล็อกแล้ว" "$n401:true" "$row"
login "$CODE" mgr pass1234
expect_status 429

case_id 34.8 "โหลด 200 request พร้อมกัน (อ่าน + เขียน) ผ่าน lb"
t0=$(date +%s)
load() { # i
  local i=$1 args=(-s -m 120 -o "$E2E_WORK/l8.$1.body" -w '%{http_code}' -H "X-Real-IP: $(rand_ip)" -H "Authorization: Bearer $T_BOSS" -H "X-Branch-Id: $BR_HQ")
  case $((i % 4)) in
    0) args+=("$LB/products") ;;
    1) args+=("$LB/me") ;;
    2) args+=(-X POST -H 'Content-Type: application/json' --data-binary "{\"code\":\"L$i\",\"name\":\"load $i\",\"parentId\":$CAT_IT,\"active\":true}" "$LB/categories") ;;
    3) args+=("$LB/sales?from=$today&to=$today") ;;
  esac
  curl "${args[@]}" >"$E2E_WORK/l8.$i.status" 2>/dev/null || echo 000 >"$E2E_WORK/l8.$i.status"
}
for i in $(seq 1 200); do load "$i" & done; wait
echo "     $(dist l8) · $(( $(date +%s) - t0 )) วิ"
check "ทุกตัวตอบ 200 (ไม่มี 5xx / timeout)" "200×200" "$(dist l8)"
expect_sql "SELECT count(*) FROM dt_categories WHERE tenant_id = $TENANT AND code LIKE 'L%'" 50
check "pgbouncer / DB ไม่มี transaction ค้าง" 0 "$(sql "SELECT count(*) FROM pg_stat_activity WHERE state = 'idle in transaction'")"

finish
