#!/usr/bin/env bash
# ข้อ 37 — ความปลอดภัย / input แปลก / log (docs/e2e-test-plan.md)
# 37.1 ค่าแปลก → ไม่มี 5xx · 37.2 GET ทุก endpoint ไม่แก้ DB (snapshot md5 ทุกตาราง) · 37.3 500 ไม่มีรายละเอียด
# 37.4 ค้นความลับใน log ทุก container ตั้งแต่ stack เริ่ม · 37.5 header (ไม่มี cookie / CORS, 401 ไม่บอกเหตุ)
E2E_STEP=37
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

std_company E37
login "$CODE" boss pass1234; T_BOSS=$TOKEN
login "$CODE" cash1 pass1234; T_CASH1=$TOKEN
at() { as "$1"; branch "$2"; terminal "${3:--}"; }
BUYER="บริษัทผู้ซื้อลับสามสิบเจ็ด"
PAN="4111111111111111"
E2E_EXTRA_SECRETS="ผู้ซื้อลับสามสิบเจ็ด ที่อยู่ลับสามสิบเจ็ด $PAN"
today=$(TZ=Asia/Bangkok date +%F)
range="from=$(TZ=Asia/Bangkok date -d "$today -1 day" +%F)&to=$today"

# ---------- ข้อมูลให้ทุก GET มีของจริงให้อ่าน ----------
at "$T_BOSS" "$BR_HQ"
call POST /suppliers "$(jq -nc '{code:"SUP37", name:"ผู้จำหน่าย 37", taxId:"0105550123451", branchType:"head", contactName:"", phone:"", email:"", creditDays:30, note:"", active:true}')"
SUP=$(jq -r .id <<<"$BODY"); check "ผู้จำหน่าย" 200 "$STATUS"
call POST /promotions "$(jq -nc --arg s "$today" --argjson p "$P_SVC" '{code:"PRO37", name:"โปร 37", note:"", type:"item_discount", startDate:$s, endDate:null,
  enabled:true, priority:0, stackable:false, scope:{all:false, productIds:[$p], categoryIds:[]}, minQty:1, minAmount:0,
  discount:{kind:"percent", value:10, maxDiscount:null}, freeGoods:null, branchIds:[]}')"
PRO=$(jq -r .id <<<"$BODY"); check "โปรโมชั่น" 200 "$STATUS"
call POST /transfers "$(jq -nc --argjson to "$BR_BN" --argjson p "$P_CBL" '{toBranchId:$to, lines:[{productId:$p, qty:1, serials:[]}], note:"e2e"}')"
TR=$(jq -r .id <<<"$BODY"); check "ใบโอน" 200 "$STATUS"
at "$T_CASH1" "$BR_HQ" 1
call POST /shifts/open '{"openingFloat":0,"openingDenominations":[]}'
SHIFT=$(jq -r .id <<<"$BODY"); check "เปิดกะ" 200 "$STATUS"
call POST /sales "$(jq -nc --argjson p "$P_MS" --argjson m "$PM_CASH" --argjson c "$PM_CARD" --arg bn "$BUYER" '{items:[{productId:$p, factor:1, qty:2, serial:null}], freeSerials:[],
  payments:[{methodId:$m, amount:350, reference:""}, {methodId:$c, amount:350, reference:"1111"}], customer:"", expectedTotal:700,
  buyer:{name:$bn, idType:"tax_id", taxId:"0105550123451", branchType:"head", branchNo:"", address:"ที่อยู่ลับสามสิบเจ็ด"}}')"
SALE=$(jq -r .id <<<"$BODY"); check "ขาย + ใบกำกับเต็มรูป" "200 true" "$STATUS $(jq '.taxInvoiceNo != null' <<<"$BODY")"
# ใบลดหนี้ออกได้เฉพาะบิลก่อนวันนี้ (ข้อ 25) — เลื่อน sold_at เป็นเมื่อวาน; boss ออกที่เครื่อง 2 ในกะของ boss
sql "UPDATE dt_sales SET sold_at = sold_at - interval '1 day' WHERE id = $SALE" >/dev/null
at "$T_BOSS" "$BR_HQ" 2
call POST /shifts/open '{"openingFloat":1000,"openingDenominations":[]}'
call POST "/sales/$SALE/credit-notes" "$(jq -nc --argjson m "$PM_CASH" '{lines:[{saleLineIndex:0, qty:1, restock:true}], refunds:[{methodId:$m, amount:350, reference:""}], reason:"ชำรุด", expectedTotal:350}')"
check "ใบลดหนี้" 200 "$STATUS"
at "$T_CASH1" "$BR_HQ" 1
call POST /pos/held-bills "$(jq -nc --argjson p "$P_MS" --arg bn "$BUYER" '{customer:"", items:[{productId:$p, factor:1, qty:1, serial:null, manualDiscount:null}], freeSerials:[], total:350,
  buyer:{name:$bn, idType:"tax_id", taxId:"0105550123451", branchType:"head", branchNo:"", address:"ที่อยู่ลับสามสิบเจ็ด"}, billManual:null, manualReason:""}')"
check "บิลพัก" 200 "$STATUS"
call POST /sales "$(jq -nc --argjson p "$P_MS" --argjson c "$PM_CARD" --arg r "$PAN" '{items:[{productId:$p, factor:1, qty:1, serial:null}], freeSerials:[],
  payments:[{methodId:$c, amount:350, reference:$r}], customer:"", expectedTotal:350}')"
check "เลขบัตรเต็มในช่องอ้างอิง → 400" 400 "$STATUS"
check "ไม่เก็บเลขบัตรเต็ม" 0 "$(sql "SELECT count(*) FROM dt_sale_payments WHERE reference LIKE '%$PAN%'")"
call POST /platform/auth/login '{"username":"platform","password":"platform"}'
T_PLAT=$(jq -r .token <<<"$BODY"); check "platform login" 200 "$STATUS"
as -; branch -; terminal -

# snapshot → md5 ของทุกตารางใน public (1 บรรทัดต่อตาราง)
snapshot() {
  local q
  q=$(sql "SELECT string_agg(format('SELECT %L || '' '' || md5(coalesce(string_agg(t::text, '','' ORDER BY t::text), '''')) FROM %I t', tablename, tablename), ' UNION ALL ' ORDER BY tablename)
    FROM pg_tables WHERE schemaname = 'public'")
  sql "$q" | sort   # UNION ALL ไม่รับประกันลำดับ (parallel append)
}
changed() { diff <(echo "$1") <(echo "$2") | awk '/^>/{print $2}' | sort | tr '\n' ' ' | sed 's/ $//'; }

case_id 37.1 "ค่าแปลกใน path / query / body / header → ไม่มี 5xx"
before=$(snapshot)
cats0=$(sql "SELECT count(*) FROM dt_categories WHERE tenant_id = $TENANT")
INJ="' OR 1=1 --"
INJ_Q=$(jq -rn --arg s "$INJ" '$s | @uri')
EMOJI_Q=$(jq -rn '"😀🇹🇭" | @uri')
deep=$(printf '%.0s[' $(seq 1 5000))
results=()
probe() { # คำอธิบาย METHOD PATH [BODY] [HEADER…]
  local d=$1; shift
  call "$@"
  results+=("$STATUS $d")
  if [[ "$STATUS" =~ ^(400|401|403|404|405|413|415)$ || ( -n "${OK200:-}" && "$STATUS" == 200 ) ]]; then pass "$d → $STATUS"; else fail "$d → $STATUS ${BODY:0:200}"; fi
}
at "$T_BOSS" "$BR_HQ"
probe "path injection" GET "/products/$INJ_Q"
probe "path emoji" GET "/sales/$EMOJI_Q"
probe "path เลขยาวมาก" GET "/products/99999999999999999999999999999999"
probe "path ติดลบ" GET "/products/-1"
probe "path ทศนิยม" GET "/products/1.5"
probe "path 0" GET "/shifts/0"
probe "query injection (from)" GET "/sales?from=$INJ_Q&to=$today"
probe "query emoji (branchId)" GET "/sales?$range&branchId=$EMOJI_Q"
OK200=1 probe "query เลขยาวมาก (userId)" GET "/audit-log?$range&userId=99999999999999999999999999"
OK200=1 probe "query 1e308 (productId)" GET "/prices?productId=1e308"
OK200=1 probe "body injection (taxId)" POST /tax-invoices/buyer-lookup "$(jq -nc --arg s "$INJ" '{taxId:$s}')"
probe "query ซ้ำ (from 2 ค่า)" GET "/sales?from=$today&from=2020-01-01&to=$today"
probe "body ไม่ใช่ JSON" POST /categories '{"code":'
probe "body array แทน object" POST /categories '[{"code":"X1","name":"x"}]'
probe "body string แทน object" POST /categories '"x"'
probe "body null" POST /categories 'null'
probe "body ซ้อน 5,000 ชั้น" POST /categories "$deep"
probe "Content-Type text/plain" POST /categories '{"code":"X2","name":"x"}' "Content-Type: text/plain"
probe "stock delta null" PUT "/products/$P_MS/stock" '{"delta":null,"unitCost":10,"note":""}'
probe "stock delta string" PUT "/products/$P_MS/stock" '{"delta":"abc","unitCost":10,"note":""}'
probe "stock delta 1e30" PUT "/products/$P_MS/stock" '{"delta":1e30,"unitCost":10,"note":""}'
probe "stock delta -1e30" PUT "/products/$P_MS/stock" '{"delta":-1e30,"unitCost":10,"note":""}'
probe "stock delta 1e400 (เกิน double)" PUT "/products/$P_MS/stock" '{"delta":1e400,"unitCost":10,"note":""}'
probe "stock delta 2.5 (ไม่ใช่จำนวนเต็ม)" PUT "/products/$P_MS/stock" '{"delta":2.5,"unitCost":10,"note":""}'
probe "stock unitCost ติดลบ" PUT "/products/$P_MS/stock" '{"delta":1,"unitCost":-10,"note":""}'
probe "stock unitCost 1e20" PUT "/products/$P_MS/stock" '{"delta":1,"unitCost":1e20,"note":""}'
probe "category code injection" POST /categories "$(jq -nc --arg c "$INJ" '{code:$c, name:"x", parentId:null}')"
probe "category ชื่อมี NUL" POST /categories '{"code":"NUL37","name":"a\u0000b","parentId":null}'
probe "supplier ชื่อมี NUL" POST /suppliers "$(jq -nc '{code:"SUPNUL", name:"a\u0000b", taxId:"", branchType:"head", contactName:"", phone:"", email:"", creditDays:0, note:"", active:true}')"
probe "sale qty เลขยาวมาก" POST /sales "$(jq -nc --argjson p "$P_MS" --argjson m "$PM_CASH" '{items:[{productId:$p, factor:1, qty:99999999999999999999, serial:null}], freeSerials:[], payments:[{methodId:$m, amount:1, reference:""}], customer:"", expectedTotal:1}')" "X-Terminal-Id: 1"
probe "sale ไม่มี items" POST /sales '{"payments":[],"expectedTotal":0}' "X-Terminal-Id: 1"
probe "sale items null แทน array" POST /sales '{"items":null,"freeSerials":null,"payments":null,"customer":null,"expectedTotal":null}' "X-Terminal-Id: 1"
branch -; terminal -   # ไม่ส่ง header ซ้ำ 2 ตัว
probe "header X-Branch-Id emoji" GET /products - "X-Branch-Id: 😀"
OK200=1 probe "header X-Terminal-Id ติดลบ" GET /shifts/current - "X-Terminal-Id: -1"
as -; branch -
probe "login injection" POST /auth/login "$(jq -nc --arg s "$INJ" '{companyCode:$s, username:$s, password:$s}')"
probe "login ชื่อยาว 100,000 ตัว" POST /auth/login "$(jq -nc --arg s "$(head -c 100000 /dev/zero | tr '\0' a)" '{companyCode:"X", username:$s, password:"x"}')"
probe "Authorization injection" GET /me - "Authorization: Bearer $INJ"
# body 2 MB (เกิน ARG_MAX ของ call → curl ตรงจากไฟล์)
{ printf '{"code":"BIG37","name":"'; head -c 2000000 /dev/zero | tr '\0' a; printf '","parentId":null}'; } >"$E2E_WORK/big.json"
s=$(curl -s -m 60 -o "$E2E_WORK/big.out" -w '%{http_code}' -X POST -H 'Content-Type: application/json' -H "X-Real-IP: $(rand_ip)" -H "Authorization: Bearer $T_BOSS" \
  --data-binary "@$E2E_WORK/big.json" "$LB/categories" || echo 000)
results+=("$s body 2 MB")
check "body 2 MB → 4xx ไม่ใช่ 5xx ($s $(head -c 120 "$E2E_WORK/big.out"))" yes "$([[ "$s" =~ ^4 ]] && echo yes || echo no)"
after=$(snapshot)
echo "     ตารางที่เปลี่ยน: $(changed "$before" "$after")"
check "ไม่มีหมวดใหม่ (ทุกค่าแปลกถูกปฏิเสธ)" "$cats0" "$(sql "SELECT count(*) FROM dt_categories WHERE tenant_id = $TENANT")"
check "ไม่มีแถวที่มีค่าแปลก" 0 "$(sql "SELECT (SELECT count(*) FROM dt_categories WHERE code LIKE '%OR 1=1%' OR name LIKE 'aaaa%')
  + (SELECT count(*) FROM dt_suppliers WHERE code = 'SUPNUL') + (SELECT count(*) FROM dt_stock_movements WHERE tenant_id = $TENANT AND abs(qty) > 1000000)")"
check "สต็อก MS ไม่เปลี่ยนจากค่าแปลก" "$(sql "SELECT coalesce(sum(qty), 0) FROM dt_stock_movements WHERE tenant_id = $TENANT AND branch_id = $BR_HQ AND product_id = $P_MS")" \
  "$(sql "SELECT stock FROM dt_branch_stock WHERE tenant_id = $TENANT AND branch_id = $BR_HQ AND product_id = $P_MS")"

case_id 37.2 "GET ทุก endpoint ไม่แก้ DB"
at "$T_BOSS" "$BR_HQ" 1
PID=$P_MS
GETS=(/me /branches "/branches/$BR_HQ" /categories /suppliers "/suppliers/$SUP" /payment-methods /users "/users/$U_CASH1" /pos-terminals
  /products "/products/$PID" "/products/$PID/movements?$range" "/products/$PID/branch-stock" "/products/$P_NB/serials" "/prices?productId=$PID" /promotions "/promotions/$PRO"
  "/transfers?$range" "/transfers/$TR" /shifts/current "/shifts?$range" /shifts/last-float "/shifts/variance-report?$range" "/shifts/$SHIFT" "/shifts/$SHIFT/summary"
  "/shifts/$SHIFT/movements" "/shifts/$SHIFT/drawer-opens" "/shifts/$SHIFT/adjustments" "/sales?$range" "/sales/$SALE" "/sales/$SALE/tax-invoice"
  "/sales/$SALE/tax-invoices" "/sales/$SALE/credit-notes" "/sales/$SALE/exchanges" "/credit-notes?$range" "/tax-invoices?$range"
  /pos/held-bills "/audit-log?$range" /settings/store /settings/company /settings/branch /settings/menu-access /company/subscription /company/onboarding
  /healthz /readyz)
before=$(snapshot)
audit0=$(sql "SELECT count(*) FROM dt_audit_log WHERE tenant_id = $TENANT")
bad=""
for p in "${GETS[@]}"; do
  call GET "$p"
  [[ "$STATUS" == 200 ]] || bad+=" $p→$STATUS"
done
as "$T_OWNER"   # /company/deletion, /company/export = เจ้าของเท่านั้น
for p in /company/deletion /company/export; do
  call GET "$p"
  [[ "$STATUS" == 200 ]] || bad+=" $p→$STATUS"
done
as "$T_PLAT"; branch -; terminal -
for p in /platform/me /platform/companies "/platform/companies/$TENANT"; do
  call GET "$p"
  [[ "$STATUS" == 200 ]] || bad+=" $p→$STATUS"
done
as -
check "GET $(( ${#GETS[@]} + 5 )) endpoint ตอบ 200 ทุกตัว (ร้าน ${#GETS[@]} + เจ้าของ 2 + platform 3)" "" "$bad"
after=$(snapshot)
check "ตารางที่เปลี่ยน = audit อย่างเดียว" "dt_audit_log" "$(changed "$before" "$after")"
check "audit ใหม่ = company_export 1 แถว" "company_export" \
  "$(sql "SELECT string_agg(action, ',') FROM (SELECT action FROM dt_audit_log WHERE tenant_id = $TENANT ORDER BY id OFFSET $audit0) a")"

case_id 37.3 "500 ที่บังคับ (หยุด pgbouncer) ไม่มีรายละเอียด"
"${COMPOSE[@]}" stop pgbouncer >/dev/null 2>&1
for via in @api1 @api2; do
  as "$T_BOSS"
  call $via GET /categories
  check "$via GET /categories" '500 {"message":"ระบบขัดข้อง กรุณาลองใหม่"}' "$STATUS $(jq -c . <<<"$BODY")"
  check "$via ไม่มี stack / class / SQL ใน body + header" 0 "$(cat "$E2E_WORK/headers" "$E2E_WORK/body" | grep -ciE 'exception|com\.an22|java\.|org\.|hikari|postgres|pgbouncer|sql|stack|trace' || true)"
done
as -
"${COMPOSE[@]}" start pgbouncer >/dev/null 2>&1
for url in "$API1" "$API2" "$LB"; do for _ in $(seq 1 90); do ready "$url" && break; sleep 1; done; done
check "กลับมา /readyz 200" 200 "$(curl -s -o /dev/null -w '%{http_code}' "$LB/readyz")"

case_id 37.5 "header ของคำตอบ"
hdr_bad() { tr -d '\r' <"$E2E_WORK/headers" | grep -iE '^(set-cookie|access-control-allow-[a-z-]*|x-powered-by):' | tr '\n' ' ' || true; }
as "$T_BOSS"
call GET /me
check "200: ไม่มี Set-Cookie / CORS / X-Powered-By" "" "$(hdr_bad)"
call @api1 POST /auth/login "{\"companyCode\":\"$CODE\",\"username\":\"boss\",\"password\":\"pass1234\"}"
check "login 200 ตรง api: ไม่มี Set-Cookie" "200 " "$STATUS $(hdr_bad)"
U_BOSS_TOKEN=$(jq -r .token <<<"$BODY")
expired=$(jwt "$(shop_claims "$TENANT" "$U_BOSS" -60)")
# แก้ตัวที่ 10 ของลายเซ็น (ตัวสุดท้ายของ base64url มี padding bit — เปลี่ยนแล้วอาจได้ลายเซ็นเดิม)
sig=${U_BOSS_TOKEN##*.}; c=${sig:9:1}; [[ "$c" == A ]] && c=B || c=A
badsig="${U_BOSS_TOKEN%.*}.${sig:0:9}$c${sig:10}"
otheraud=$(jwt "$(shop_claims "$TENANT" "$U_BOSS" | jq -c '.aud = ["platform"]')")
first401=""
for t in "" "garbage" "$expired" "$badsig" "$otheraud" "$T_PLAT"; do
  as "${t:--}"
  call @api1 GET /products
  got="$STATUS $(jq -c . <<<"$BODY") $(tr -d '\r' <"$E2E_WORK/headers" | grep -i '^www-authenticate:' | tr '\n' ' ' || true)$(hdr_bad)"
  [[ -z "$first401" ]] && first401=$got
  check "401 เหมือนกันทุกเหตุ (token: ${t:0:12}…)" '401 {"message":"เซสชันหมดอายุ กรุณาเข้าสู่ระบบใหม่"} ' "$got"
done
as -
for via in @lb @api1; do
  call $via OPTIONS /products - "Origin: https://evil.example" "Access-Control-Request-Method: GET" "Access-Control-Request-Headers: authorization"
  check "$via preflight จาก origin อื่น: ไม่มี Access-Control-Allow-* ($STATUS)" "" "$(hdr_bad)"
  call $via GET /healthz - "Origin: https://evil.example"
  check "$via GET + Origin: ไม่มี Access-Control-Allow-*" "200 " "$STATUS $(hdr_bad)"
done

case_id 37.4 "ค้นความลับใน log ทุก container ตั้งแต่ stack เริ่ม"
as "$T_BOSS"; call POST /tax-invoices/buyer-lookup '{"taxId":"0105550123451"}'; as -   # ให้มีคำขอค้นผู้ซื้อใน log ของ lb / api
ALL="$E2E_WORK/all.log"
for svc in api1 api2 lb pgbouncer db; do docker logs "an22-multi-$svc-1" >>"$ALL" 2>&1; done
echo "     log ทั้งหมด $(grep -c . "$ALL") บรรทัด (api1 / api2 / lb / pgbouncer / db ตั้งแต่ $(docker inspect -f '{{.Created}}' an22-multi-api1-1 | cut -c1-19)Z)"
leak() { grep -cF -- "$1" "$ALL" || true; }
for v in DB_SUPERUSER_PASSWORD DB_ADMIN_PASSWORD DB_OWNER_PASSWORD DB_APP_PASSWORD DB_PLATFORM_PASSWORD PII_HASH_KEY; do
  check "ไม่มี $v" 0 "$(leak "$(env_get "$v")")"
done
for k in $(env_get PII_KEYS | tr ',' ' '); do check "ไม่มีกุญแจ PII v${k%%:*}" 0 "$(leak "${k#*:}")"; done
for k in $(env_get JWT_SECRET | tr ',' ' '); do check "ไม่มี JWT secret kid ${k%%:*}" 0 "$(leak "${k#*:}")"; done
check "ไม่มีรูปแบบ JWT (eyJ….eyJ)" 0 "$(grep -cE 'eyJ[A-Za-z0-9_-]{10,}\.eyJ' "$ALL" || true)"
cat "$E2E_DIR"/run/tokens-* 2>/dev/null | sort -u >"$E2E_WORK/tokens"
check "ไม่มี token ที่ออกในการทดสอบ ($(grep -c . "$E2E_WORK/tokens") ตัว)" 0 "$(grep -cF -f "$E2E_WORK/tokens" "$ALL" || true)"
for s in owner1234 pass1234 newpass96 newowner325 e2e-Secret-Pw-29 '"password":"platform"' "$PAN" 4111-1111-1111-1111 \
  "$BUYER" ที่อยู่ลับสามสิบเจ็ด ผู้ซื้อลับ ลูกค้าลับ ลับเฉพาะ ที่อยู่ลับ; do
  check "ไม่มี $s" 0 "$(leak "$s")"
done
# เลขผู้เสียภาษี: ค้นผู้ซื้อเป็น POST (B10 / E50) — ไม่มีใน URL ของ lb / api
check "ไม่มีเลขผู้เสียภาษี 0105550123451 ใน log ทุก container" 0 "$(grep -cF 0105550123451 "$ALL" || true)"
check "body ที่ log แล้ว mask ช่องอ่อนไหว (มี \"***\")" yes "$(grep -q '"\*\*\*"' "$ALL" && echo yes || echo no)"

# 37.3: 500 × 2 (api1 + api2) + bug ที่รู้แล้วใน 37.1 (B8 unitCost 1e20, B9 NUL) × 2 — access ERROR + unhandled ERROR ต่อ request
E2E_ALLOW_5XX=4
E2E_ALLOW_ERROR_LOG=8
finish
