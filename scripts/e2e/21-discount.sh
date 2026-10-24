#!/usr/bin/env bash
# ข้อ 21 — ส่วนลดพิเศษ / cost-check / สถานะออร์เดอร์ (docs/e2e-test-plan.md)
# 21.4 เปลี่ยนสถานะบิล SO-2026-0001 ของ DEMO แล้วคืนเป็น paid ตอนท้าย
E2E_STEP=21
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

std_company E21
login "$CODE" boss pass1234; T_BOSS=$TOKEN
login "$CODE" cash1 pass1234; T_CASH1=$TOKEN
num() { jq -r "$1 | if type == \"number\" then (. + 0 | tostring) else tostring end" <<<"$BODY"; }
at() { as "$1"; branch "$2"; terminal "${3:--}"; }
item() { jq -nc --argjson p "$1" --argjson q "${2:-1}" --argjson md "${3:-null}" '{productId:$p, factor:1, qty:$q, serial:null, manualDiscount:$md}'; }
md() { jq -nc --arg k "$1" --argjson v "$2" '{kind:$k, value:$v}'; }
sale() { # items total [billManual] [reason]
  call POST /sales "$(jq -nc --argjson i "[$1]" --argjson t "$2" --argjson bm "${3:-null}" --arg r "${4:-}" --argjson m "$PM_CASH" \
    '{items:$i, freeSerials:[], payments:[{methodId:$m, amount:$t, reference:""}], customer:"", expectedTotal:$t, billManualDiscount:$bm, manualDiscountReason:$r}')"
}
sales_count() { sql "SELECT count(*) FROM dt_sales WHERE tenant_id = $TENANT"; }

at "$T_CASH1" "$BR_HQ" 1
call POST /shifts/open '{"openingFloat":0,"openingDenominations":[]}'
at "$T_BOSS" "$BR_HQ" 2
call POST /shifts/open '{"openingFloat":0,"openingDenominations":[]}'

case_id 21.1 "cash1 ลดพิเศษ (เพดาน 5%)"
at "$T_CASH1" "$BR_HQ" 1
before=$(sales_count)
sale "$(item "$P_MS" 1 "$(md amount 20)")" 330 null "ลูกค้าประจำ"
expect_status 400
expect_msg "ส่วนลดพิเศษ MS-001 เกิน 5% — ให้ผู้ดูแลระบบทำรายการ"
sale "$(item "$P_MS" 1 "$(md amount 17.51)")" 332.49 null "ลูกค้าประจำ"
expect_msg "ส่วนลดพิเศษ MS-001 เกิน 5% — ให้ผู้ดูแลระบบทำรายการ"
sale "$(item "$P_MS" 1 "$(md amount 15)")" 335 null " "
expect_status 400
expect_msg "กรุณาระบุเหตุผลส่วนลดพิเศษ"
check "ไม่มีบิลใหม่" "$before" "$(sales_count)"
sale "$(item "$P_MS" 1 "$(md amount 15)")" 335 null " ลูกค้าประจำ "
expect_status 200
S1=$(jq -r .id <<<"$BODY")
check "total / manualDiscount" "335 15" "$(num .total) $(num .manualDiscount)"
expect_sql "SELECT manual_discount || ':' || manual_discount_by || ':' || manual_discount_reason FROM dt_sales WHERE id = $S1" "15.00:แคชเชียร์หนึ่ง:ลูกค้าประจำ"
expect_sql "SELECT manual_discount || ':' || amount FROM dt_sale_lines WHERE tenant_id = $TENANT AND sale_id = $S1" "15.00:335.00"
expect_sql "SELECT action || ':' || user_id || ':' || branch_id || ':' || detail FROM dt_audit_log WHERE tenant_id = $TENANT AND action = 'manual_discount'" \
  "manual_discount:$U_CASH1:$BR_HQ:฿15 — ลูกค้าประจำ"
sale "$(item "$P_MS" 1 "$(md amount 17.5)")" 332.5 null "พอดีเพดาน"
expect_status 200   # 5% ของ 350 พอดี

case_id 21.2 "boss ลดพิเศษ / ลดท้ายบิล / ค่าผิด"
at "$T_BOSS" "$BR_HQ" 2
sale "$(item "$P_MS" 1 "$(md amount 100)")" 250 null "admin ลด"
expect_status 200
check "boss ลด 100 (เกินเพดาน staff)" "250 100" "$(num .total) $(num .manualDiscount)"
sale "$(item "$P_MS"), $(item "$P_EXM")" 423 "$(md percent 10)" "ลดท้ายบิล"
expect_status 200
S3=$(jq -r .id <<<"$BODY")
check "ท้ายบิล 10% ของ 470 = 47" "423 47" "$(num .total) $(num .manualDiscount)"
expect_sql "SELECT string_agg(sku || ':' || manual_discount || ':' || amount, ',' ORDER BY line_index) FROM dt_sale_lines WHERE tenant_id = $TENANT AND sale_id = $S3" \
  "MS-001:35.00:315.00,EXM-001:12.00:108.00"
before=$(sales_count)
while IFS='|' read -r items bm msg; do
  sale "$items" 300 "$bm" "ค่าผิด"
  expect_status 400
  expect_msg "$msg"
done <<EOF
$(item "$P_MS" 1 "$(md percent 0)")|null|MS-001: ส่วนลดพิเศษต้องมากกว่า 0 และไม่เกิน 100%
$(item "$P_MS" 1 "$(md percent 150)")|null|MS-001: ส่วนลดพิเศษต้องมากกว่า 0 และไม่เกิน 100%
$(item "$P_MS")|$(md percent 0)|ส่วนลดท้ายบิล: ส่วนลดพิเศษต้องมากกว่า 0 และไม่เกิน 100%
$(item "$P_MS" 1 "$(md amount 10.555)")|null|MS-001: จำนวนเงินส่วนลดพิเศษไม่ถูกต้อง
$(item "$P_MS" 1 "$(md amount -5)")|null|MS-001: จำนวนเงินส่วนลดพิเศษไม่ถูกต้อง
EOF
check "ไม่มีบิลใหม่" "$before" "$(sales_count)"

case_id 21.3 "cost-check ตะกร้าที่ลดจนต่ำกว่าทุน"
at "$T_CASH1" "$BR_HQ"
before=$(sales_count)
call POST /sales/cost-check "$(jq -nc --argjson i "[$(item "$P_MS" 1 "$(md amount 200)"), $(item "$P_EXM")]" '{items:$i, freeSerials:[], payments:[], customer:"", expectedTotal:0}')"
expect_status 200
check "issues" '[{"cartIndex":0,"productId":'"$P_MS"',"message":"MS-001 ขายต่ำกว่าทุนหลังส่วนลดพิเศษ","blocking":false}]' "$(jq -c .issues <<<"$BODY")"
check "คำตอบมีแค่ตำแหน่ง / SKU / ข้อความ (ไม่มีทุน / ราคา)" "blocking,cartIndex,message,productId" "$(jq -r '[.issues[] | keys[]] | unique | join(",")' <<<"$BODY")"
call POST /sales/cost-check "$(jq -nc --argjson i "[$(item "$P_MS" 1 "$(md amount 10)")]" '{items:$i, freeSerials:[], payments:[], customer:"", expectedTotal:0}')"
check "ลดน้อย ไม่ต่ำกว่าทุน → ไม่มี issue" 0 "$(num '.issues | length')"
check "ไม่บันทึกอะไร" "$before" "$(sales_count)"

case_id 21.4 "PUT /sales/{id}/status"
login DEMO admin admin; T_DEMO=$TOKEN
SO=$(sql "SELECT id || ':' || status FROM dt_sales WHERE tenant_id = 1 AND order_no = 'SO-2026-0001'")
SO_ID=${SO%%:*}; SO_STATUS=${SO#*:}
as "$T_DEMO"; branch -; terminal -
call PUT "/sales/$SO_ID/status" '{"status":"pending"}'
expect_status 200
expect_json .status pending
expect_sql "SELECT status FROM dt_sales WHERE id = $SO_ID" pending
call PUT "/sales/$SO_ID/status" '{"status":"shipped"}'
expect_status 400
expect_msg "ข้อมูลที่ส่งมาไม่ถูกต้อง"
call @api2 PUT "/sales/$SO_ID/status" "{\"status\":\"$SO_STATUS\"}"
expect_status 200
expect_sql "SELECT status FROM dt_sales WHERE id = $SO_ID" "$SO_STATUS"
at "$T_BOSS" "$BR_HQ"
call PUT "/sales/$S1/status" '{"status":"cancelled"}'
expect_status 400
expect_msg "บิลขายหน้าร้านเปลี่ยนสถานะไม่ได้ (ใช้การยกเลิกบิล)"
expect_sql "SELECT status FROM dt_sales WHERE id = $S1" paid
as -; branch -; terminal -

finish
