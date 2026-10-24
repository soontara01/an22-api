#!/usr/bin/env bash
# ข้อ 22 — ยกเลิกบิล (docs/e2e-test-plan.md)
E2E_STEP=22
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

std_company E22
login "$CODE" boss pass1234; T_BOSS=$TOKEN
login "$CODE" cash1 pass1234; T_CASH1=$TOKEN
login "$CODE" cash2 pass1234; T_CASH2=$TOKEN
num() { jq -r "$1 | if type == \"number\" then (. + 0 | tostring) else tostring end" <<<"$BODY"; }
at() { as "$1"; branch "$2"; terminal "${3:--}"; }
item() { jq -nc --argjson p "$1" --argjson q "${2:-1}" --arg s "${3:-}" '{productId:$p, factor:1, qty:$q, serial:(if $s == "" then null else $s end)}'; }
sale() { # items total [buyer]
  call POST /sales "$(jq -nc --argjson i "[$1]" --argjson t "$2" --argjson b "${3:-null}" --argjson m "$PM_CASH" \
    '{items:$i, freeSerials:[], payments:[{methodId:$m, amount:$t, reference:""}], customer:"", expectedTotal:$t, buyer:$b}')"
  SALE_ID=$(jq -r .id <<<"$BODY")
}
void_body() { jq -nc --arg r "$1" --argjson a "${2:-null}" '{reason:$r, approval:$a}'; }
approval() { jq -nc --arg u "$1" --arg p "$2" '{username:$u, password:$p}'; }
stock() { sql "SELECT stock || '/' || avg_cost FROM dt_branch_stock WHERE tenant_id = $TENANT AND branch_id = $BR_HQ AND product_id = $1"; }
BUYER='{"name":"บริษัท ลูกค้าลับยี่สิบสอง จำกัด","idType":"tax_id","taxId":"0105550123451","branchType":"head","branchNo":"","address":"9 ถนนพระราม 9 กรุงเทพฯ"}'
E2E_EXTRA_SECRETS="ลูกค้าลับยี่สิบสอง"   # คำเดียว (lib แยกคำตามช่องว่าง)

at "$T_CASH1" "$BR_HQ" 1
call POST /shifts/open '{"openingFloat":1000,"openingDenominations":[]}'
SHIFT=$(jq -r .id <<<"$BODY")
sale "$(item "$P_MS" 2), $(item "$P_NB" 1 NB0001)" 25700 "$BUYER"; SALE=$SALE_ID
check "บิล MS ×2 + NB0001 พร้อมใบกำกับ" "200 25700" "$STATUS $(num .total)"
INV=$(num .taxInvoiceNo)
sale "$(item "$P_EXM")" 120; OLD=$SALE_ID
sql "UPDATE dt_sales SET sold_at = sold_at - interval '1 day' WHERE id = $OLD" >/dev/null

case_id 22.1 "cash1 ยกเลิก: ไม่มีผู้อนุมัติ / รหัสผิด / ไม่ใช่ admin"
call POST "/sales/$SALE/void" "$(void_body "ลูกค้าเปลี่ยนใจ")"
expect_status 403
expect_msg "ยกเลิกบิลต้องให้ผู้ดูแลระบบอนุมัติ"
call POST "/sales/$SALE/void" "$(void_body "ลูกค้าเปลี่ยนใจ" "$(approval boss wrong-pass)")"
expect_status 400
expect_msg "รหัสผ่านผู้อนุมัติไม่ถูกต้อง"
call POST "/sales/$SALE/void" "$(void_body "ลูกค้าเปลี่ยนใจ" "$(approval cash2 pass1234)")"
expect_status 400
expect_msg "ผู้อนุมัติต้องเป็นผู้ดูแลระบบ"
expect_sql "SELECT status FROM dt_sales WHERE id = $SALE" paid

case_id 22.2 "ไม่มีเหตุผล / ที่สาขา BN / บิลเมื่อวาน"
call POST "/sales/$SALE/void" "$(void_body " " "$(approval boss pass1234)")"
expect_status 400
expect_msg "กรุณาระบุเหตุผลการยกเลิก"
at "$T_BOSS" "$BR_BN" 3
call POST "/sales/$SALE/void" "$(void_body "ยกเลิกที่ BN")"
expect_status 400
expect_msg "บิลนี้ขายที่สาขา HQ — ยกเลิกได้ที่สาขานั้น"
at "$T_CASH1" "$BR_HQ" 1
call POST "/sales/$OLD/void" "$(void_body "บิลเมื่อวาน" "$(approval boss pass1234)")"
expect_status 400
expect_msg "ยกเลิกได้เฉพาะบิลของวันนี้ บิลข้ามวันต้องออกใบลดหนี้"
call POST /sales/99999999/void "$(void_body "ไม่มีบิล" "$(approval boss pass1234)")"
expect_status 404
expect_sql "SELECT count(*) FILTER (WHERE status = 'paid') FROM dt_sales WHERE id IN ($SALE, $OLD)" 2

case_id 22.3 "cash1 ยกเลิก (boss อนุมัติด้วยรหัส)"
check "ก่อน: MS / NB" "18/200.0000 4/20000.0000" "$(stock "$P_MS") $(stock "$P_NB")"
call @api2 POST "/sales/$SALE/void" "$(void_body " ลูกค้าเปลี่ยนใจ " "$(approval boss pass1234)")"
expect_status 200
expect_json '[.status, .voidReason, .voidedBy, .voidApprovedBy, (.voidShiftId | tostring)] | join(" ")' "cancelled ลูกค้าเปลี่ยนใจ แคชเชียร์หนึ่ง คุณบอส $SHIFT"
check "หลัง: MS กลับ 20 ที่ทุนเดิม / NB 5" "20/200.0000 5/20000.0000" "$(stock "$P_MS") $(stock "$P_NB")"
expect_sql "SELECT status || ':' || branch_id FROM dt_serial_numbers WHERE tenant_id = $TENANT AND serial = 'NB0001'" "in_stock:$BR_HQ"
expect_sql "SELECT string_agg(type || ':' || qty || ':' || total_cost || ':' || note, ',' ORDER BY product_id) FROM dt_stock_movements
  WHERE tenant_id = $TENANT AND note = 'ยกเลิกบิล ' || (SELECT order_no FROM dt_sales WHERE id = $SALE)" \
  "$(sql "SELECT string_agg(x, ',' ORDER BY p) FROM (VALUES ($P_MS, 'receive:2:400.00:ยกเลิกบิล ' || (SELECT order_no FROM dt_sales WHERE id = $SALE)),
    ($P_NB, 'receive:1:20000.00:ยกเลิกบิล ' || (SELECT order_no FROM dt_sales WHERE id = $SALE))) v(p, x)")"
expect_sql "SELECT voided_by || ':' || void_approved_by || ':' || void_shift_id || ':' || void_reason || ':' || (voided_at IS NOT NULL) FROM dt_sales WHERE id = $SALE" \
  "แคชเชียร์หนึ่ง:คุณบอส:$SHIFT:ลูกค้าเปลี่ยนใจ:true"
expect_sql "SELECT invoice_no || ':' || (cancelled_at IS NOT NULL) || ':' || cancel_reason FROM dt_tax_invoices WHERE tenant_id = $TENANT AND sale_id = $SALE" \
  "$INV:true:ยกเลิกบิล: ลูกค้าเปลี่ยนใจ"
expect_sql "SELECT action || ':' || user_id || ':' || branch_id || ':' || detail FROM dt_audit_log WHERE tenant_id = $TENANT AND action = 'sale_void'" \
  "sale_void:$U_CASH1:$BR_HQ:฿25700 — ลูกค้าเปลี่ยนใจ (อนุมัติโดย คุณบอส)"

case_id 22.4 "ยกเลิกซ้ำ"
call POST "/sales/$SALE/void" "$(void_body "ซ้ำ" "$(approval boss pass1234)")"
expect_status 400
expect_msg "ยกเลิกได้เฉพาะบิลที่ชำระแล้ว"
check "สต็อกไม่คืนซ้ำ" "20/200.0000 5/20000.0000" "$(stock "$P_MS") $(stock "$P_NB")"

case_id 22.5 "ยอดของกะหลังยกเลิก"
at "$T_BOSS" "$BR_HQ"
call GET "/shifts/$SHIFT/summary"
expect_status 200
check "voidCount / voidTotal / billCount" "1 25700 2" "$(num .voidCount) $(num .voidTotal) $(num .billCount)"
check "เงินสดคาด = 1,000 + 120 (บิลที่ยกเลิกไม่นับ)" 1120 "$(jq -r '.lines[] | select(.type == "cash") | .expected + 0' <<<"$BODY")"
echo "     summary: $(jq -c '{billCount, salesTotal, voidCount, voidTotal, netSales}' <<<"$BODY")"
call GET "/sales?shiftId=$SHIFT"
check "บิลของกะรวมบิลที่ยกเลิก" "cancelled paid" "$(jq -r '[.[].status] | sort | join(" ")' <<<"$BODY")"
as -; branch -; terminal -

finish
