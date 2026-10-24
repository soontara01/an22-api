#!/usr/bin/env bash
# ข้อ 24 — บิลพัก (docs/e2e-test-plan.md)
E2E_STEP=24
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

std_company E24
login "$CODE" cash1 pass1234; T_CASH1=$TOKEN
login "$CODE" cash2 pass1234; T_CASH2=$TOKEN
at() { as "$1"; branch "$2"; terminal "${3:--}"; }
CUSTOMER="นายลับเฉพาะยี่สิบสี่"
BUYER_NAME="บริษัท ผู้ซื้อลับยี่สิบสี่ จำกัด"
E2E_EXTRA_SECRETS="ลับเฉพาะยี่สิบสี่ ผู้ซื้อลับยี่สิบสี่"
held() { # customer items_json
  jq -nc --arg c "$1" --argjson i "$2" --arg bn "$BUYER_NAME" '{customer:$c, items:$i, freeSerials:[], total:700,
    buyer:{name:$bn, idType:"tax_id", taxId:"0105550123451", branchType:"head", branchNo:"", address:"กรุงเทพฯ"},
    billManual:null, manualReason:""}'
}
ITEMS="[{\"productId\":$P_MS,\"factor\":1,\"qty\":2,\"serial\":null,\"manualDiscount\":null}]"
rows() { sql "SELECT count(*) FROM dt_held_bills WHERE tenant_id = $TENANT"; }

case_id 24.1 "พักบิล"
at "$T_CASH1" "$BR_HQ" 1
call POST /pos/held-bills "$(held "$CUSTOMER" "$ITEMS")"
expect_status 200
H1=$(jq -r .id <<<"$BODY")
expect_json '[.customer, (.items | length | tostring), .buyer.name, (.heldAt != null | tostring)] | join(" ")' "$CUSTOMER 1 $BUYER_NAME true"
expect_sql "SELECT branch_id || ':' || terminal_id || ':' || (length(payload_enc) > 100) FROM dt_held_bills WHERE id = $H1" "$BR_HQ:1:true"
expect_sql "SELECT position(convert_to('ลับเฉพาะยี่สิบสี่', 'UTF8') in payload_enc) || ':' || position(convert_to('ผู้ซื้อลับยี่สิบสี่', 'UTF8') in payload_enc)
  || ':' || position(convert_to('0105550123451', 'UTF8') in payload_enc) FROM dt_held_bills WHERE id = $H1" "0:0:0"
before=$(rows)
call POST /pos/held-bills "$(held "$CUSTOMER" '[]')"
expect_status 400
expect_msg "ไม่มีรายการในบิลที่จะพัก"
terminal -
call POST /pos/held-bills "$(held "$CUSTOMER" "$ITEMS")"
expect_status 400
expect_msg "เครื่องนี้ยังไม่ได้ตั้งเลขเครื่อง POS กรุณาให้ผู้ดูแลตั้งค่าที่เมนูตั้งค่า"
terminal 3
call POST /pos/held-bills "$(held "$CUSTOMER" "$ITEMS")"
expect_status 400
expect_msg "เครื่อง 3 เป็นเครื่องของสาขา BN · สาขาบางนา — สลับไปสาขานั้น หรือให้ผู้ดูแลตั้งเครื่องนี้ใหม่"
check "ไม่มีแถวใหม่" "$before" "$(rows)"

case_id 24.2 "รายการตามเครื่อง"
terminal 1
call @api2 GET /pos/held-bills
expect_status 200
check "เครื่อง 1 เห็น 1 (ถอดรหัสแล้ว)" "1 $H1 $CUSTOMER" "$(jq -r '[length, .[0].id, .[0].customer] | map(tostring) | join(" ")' <<<"$BODY")"
terminal 2
call GET /pos/held-bills
check "เครื่อง 2 เห็น 0" 0 "$(jq length <<<"$BODY")"
at "$T_CASH2" "$BR_BN" 3
call GET /pos/held-bills
check "เครื่อง 3 (BN) เห็น 0" 0 "$(jq length <<<"$BODY")"

case_id 24.3 "ลบ"
at "$T_CASH1" "$BR_HQ" 2
call DELETE "/pos/held-bills/$H1"
expect_status 404
expect_msg "ไม่พบบิลที่พักไว้"
at "$T_CASH2" "$BR_BN" 3
call DELETE "/pos/held-bills/$H1"
expect_status 404
expect_sql "SELECT count(*) FROM dt_held_bills WHERE id = $H1" 1
at "$T_CASH1" "$BR_HQ" 1
call DELETE "/pos/held-bills/$H1"
expect_status 200
expect_sql "SELECT count(*) FROM dt_held_bills WHERE id = $H1" 0
call @api2 DELETE "/pos/held-bills/$H1"
expect_status 404
expect_msg "ไม่พบบิลที่พักไว้"
for bad in abc 1.5 99999999; do
  call DELETE "/pos/held-bills/$bad"
  check "id แปลก $bad → 404" "404 ไม่พบบิลที่พักไว้" "$STATUS $(jq -r .message <<<"$BODY")"
done

case_id 24.4 "access log ไม่มี body ของ /pos/held-bills"
sleep 1
lines=$({ logs api1 "$E2E_SINCE"; logs api2 "$E2E_SINCE"; } | grep '"type":"access"' | grep '/api/pos/held-bills')
check "มี access log ของ held-bills (รวม 4xx)" yes "$([[ $(grep -c . <<<"$lines") -ge 10 ]] && grep -q '"status":400' <<<"$lines" && echo yes || echo no)"
check "ไม่มี requestBody / responseBody" 0 "$(jq -r 'select(.requestBody != null or .responseBody != null) | .path' <<<"$lines" | grep -c . || true)"
as -; branch -; terminal -

finish
