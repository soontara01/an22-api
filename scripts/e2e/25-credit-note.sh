#!/usr/bin/env bash
# ข้อ 25 — ใบลดหนี้ (docs/e2e-test-plan.md)
# บิล MS ×2 (700) + NB0001 (25,000) เงินสด พร้อมใบกำกับ → เลื่อน sold_at เป็นเมื่อวาน; boss ออกใบลดหนี้ที่เครื่อง 2 (กะของ boss)
E2E_STEP=25
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

today=$(TZ=Asia/Bangkok date +%F)
yesterday=$(TZ=Asia/Bangkok date -d "$today -1 day" +%F)
ymd=$(TZ=Asia/Bangkok date +%Y%m%d)
std_company E25
login "$CODE" boss pass1234; T_BOSS=$TOKEN
login "$CODE" cash1 pass1234; T_CASH1=$TOKEN
num() { jq -r "$1 | if type == \"number\" then (. + 0 | tostring) else tostring end" <<<"$BODY"; }
at() { as "$1"; branch "$2"; terminal "${3:--}"; }
stock() { sql "SELECT stock || '/' || avg_cost FROM dt_branch_stock WHERE tenant_id = $TENANT AND branch_id = $BR_HQ AND product_id = $1"; }
line() { jq -nc --argjson i "$1" --argjson q "$2" --argjson r "${3:-true}" '{saleLineIndex:$i, qty:$q, restock:$r}'; }
refund() { jq -nc --argjson m "$1" --argjson a "$2" --arg r "${3:-}" '{methodId:$m, amount:$a, reference:$r}'; }
cn() { # sale lines refunds reason expectedTotal
  call POST "/sales/$1/credit-notes" "$(jq -nc --argjson l "[$2]" --argjson f "[$3]" --arg r "$4" --argjson t "$5" '{lines:$l, refunds:$f, reason:$r, expectedTotal:$t}')"
}
BUYER_NAME="บริษัท ผู้ซื้อลับยี่สิบห้า จำกัด"
E2E_EXTRA_SECRETS="ผู้ซื้อลับยี่สิบห้า"
cnt() { sql "SELECT count(*) FROM dt_credit_notes WHERE tenant_id = $TENANT"; }

at "$T_CASH1" "$BR_HQ" 1
call POST /shifts/open '{"openingFloat":0,"openingDenominations":[]}'
sale_body() { jq -nc --argjson ms "$P_MS" --argjson nb "$P_NB" --argjson m "$PM_CASH" --arg bn "$BUYER_NAME" --argjson withBuyer "$1" '{items:[{productId:$ms, factor:1, qty:2, serial:null},
  {productId:$nb, factor:1, qty:1, serial:"NB0001"}], freeSerials:[], payments:[{methodId:$m, amount:25700, reference:""}], customer:"", expectedTotal:25700,
  buyer:(if $withBuyer then {name:$bn, idType:"tax_id", taxId:"0105550123451", branchType:"head", branchNo:"", address:"กรุงเทพฯ"} else null end)}'; }
call POST /sales "$(sale_body true)"
SALE=$(jq -r .id <<<"$BODY"); ORDER=$(jq -r .orderNo <<<"$BODY"); INV=$(jq -r .taxInvoiceNo <<<"$BODY")
sql "UPDATE dt_sales SET sold_at = sold_at - interval '1 day' WHERE id = $SALE" >/dev/null
call POST /sales "$(jq -nc --argjson p "$P_EXM" --argjson m "$PM_CASH" '{items:[{productId:$p, factor:1, qty:1, serial:null}], freeSerials:[], payments:[{methodId:$m, amount:120, reference:""}], customer:"", expectedTotal:120}')"
TODAY_SALE=$(jq -r .id <<<"$BODY")
at "$T_BOSS" "$BR_HQ" 2
call POST /shifts/open '{"openingFloat":1000,"openingDenominations":[]}'
BSHIFT=$(jq -r .id <<<"$BODY")
check "เตรียม: บิลเมื่อวาน + ใบกำกับ, กะของ boss" "200 $INV" "$STATUS $(sql "SELECT tax_invoice_no FROM dt_sales WHERE id = $SALE")"

case_id 25.1 "บิลของวันนี้ / staff / ไม่มีเหตุผล / ยอดไม่ตรง"
cn "$TODAY_SALE" "$(line 0 1)" "$(refund "$PM_CASH" 120)" "คืน" 120
expect_status 400
expect_msg "บิลของวันนี้ให้ยกเลิกบิลแทนการออกใบลดหนี้"
at "$T_CASH1" "$BR_HQ" 1
cn "$SALE" "$(line 0 1)" "$(refund "$PM_CASH" 350)" "คืน" 350
expect_status 403
expect_msg "ออกใบลดหนี้ได้เฉพาะผู้ดูแลระบบ"
at "$T_BOSS" "$BR_HQ" 2
cn "$SALE" "$(line 0 1)" "$(refund "$PM_CASH" 350)" " " 350
expect_status 400
expect_msg "กรุณาระบุเหตุผลการลดหนี้"
cn "$SALE" "$(line 0 1)" "$(refund "$PM_CASH" 349)" "คืน" 349
expect_status 400
expect_msg "ยอดลดหนี้เปลี่ยน กรุณาตรวจสอบอีกครั้ง"
cn "$SALE" "$(line 5 1)" "$(refund "$PM_CASH" 350)" "คืน" 350
expect_msg "ไม่พบรายการที่ 6 ในบิล"
check "ไม่มีใบลดหนี้" 0 "$(cnt)"

case_id 25.2 "คืน MS 1 เข้าคลัง คืนเงินสด 350"
cn "$SALE" "$(line 0 1)" "$(refund "$PM_CASH" 350)" " สินค้าไม่ตรงสเปก " 350
expect_status 200
CN1=$(jq -r .id <<<"$BODY")
check "เลข / total / vat / shift" "CN-HQ-$ymd-0001 350 22.9 $BSHIFT" "$(num .cnNo) $(num .total) $(num .vat) $(num .shiftId)"
check "ใบกำกับ / ผู้ซื้อของใบลดหนี้ = ของใบกำกับตอนออก" "$INV $BUYER_NAME" "$(num .taxInvoiceNo) $(num .buyer.name)"
check "HQ MS 18→19 ทุนคง 200" "19/200.0000" "$(stock "$P_MS")"
expect_sql "SELECT sku || ':' || qty || ':' || amount || ':' || restock || ':' || cogs FROM dt_credit_note_lines WHERE tenant_id = $TENANT AND credit_note_id = $CN1" "MS-001:1:350.00:true:200.00"
expect_sql "SELECT string_agg(name || ':' || amount, ',') FROM dt_credit_note_refunds WHERE tenant_id = $TENANT AND credit_note_id = $CN1" "เงินสด:350.00"
expect_sql "SELECT shift_id || ':' || reason || ':' || order_no FROM dt_credit_notes WHERE id = $CN1" "$BSHIFT:สินค้าไม่ตรงสเปก:$ORDER"
expect_sql "SELECT position(convert_to('ผู้ซื้อลับยี่สิบห้า', 'UTF8') in buyer_enc) || ':' || (buyer_enc IS NOT NULL) FROM dt_credit_notes WHERE id = $CN1" "0:true"
call @api2 GET "/sales/$SALE"
check "returnStatus / creditedTotal / creditNoteNos" "partial 350 CN-HQ-$ymd-0001" "$(num .returnStatus) $(num .creditedTotal) $(num '.creditNoteNos | join(",")')"
expect_sql "SELECT action || ':' || user_id || ':' || ref_label || ':' || detail FROM dt_audit_log WHERE tenant_id = $TENANT AND action = 'credit_note'" \
  "credit_note:$U_BOSS:CN-HQ-$ymd-0001:บิล $ORDER คืนเงิน ฿350"
expect_sql "SELECT type || ':' || qty || ':' || total_cost FROM dt_stock_movements WHERE tenant_id = $TENANT AND product_id = $P_MS ORDER BY id DESC LIMIT 1" "receive:1:200.00"

case_id 25.3 "ผิดกฎ"
before=$(cnt)
while IFS='|' read -r lines refunds total msg; do
  cn "$SALE" "$lines" "$refunds" "คืน" "$total"
  expect_status 400
  expect_msg "$msg"
done <<EOF
$(line 0 2)|$(refund "$PM_CASH" 700)|700|MS-001 คืนได้ไม่เกิน 1 ชิ้น
$(line 0 1)|$(refund "$PM_CASH" 350 "4111 1111 1111 1111")|350|เงินสด: ห้ามกรอกเลขบัตรเต็ม — กรอกเฉพาะเลขอนุมัติหรือเลขบัตร 4 หลักท้าย
$(line 0 1)|$(refund "$PM_CARD" 350 A1)|350|คืนเงินได้เฉพาะเงินสดหรือช่องทางที่ลูกค้าชำระบิลนี้
$(line 0 1)|$(refund "$PM_CASH" 300)|350|ยอดคืนเงิน 300 ไม่เท่ายอดลดหนี้ 350
$(line 0 1)|$(refund "$PM_CASH" 200), $(refund "$PM_CASH" 150)|350|เงินสด ซ้ำ
EOF
check "ไม่มีใบลดหนี้ใหม่" "$before" "$(cnt)"
check "สต็อก MS ไม่เปลี่ยน" "19/200.0000" "$(stock "$P_MS")"

case_id 25.4 "คืน NB0001 ไม่เข้าคลัง (เสีย) + MS ที่เหลือ"
nb_before=$(stock "$P_NB")
cn "$SALE" "$(line 1 1 false), $(line 0 1)" "$(refund "$PM_CASH" 25350)" "เครื่องเสีย" 25350
expect_status 200
CN2=$(jq -r .id <<<"$BODY")
check "เลข / total" "CN-HQ-$ymd-0002 25350" "$(num .cnNo) $(num .total)"
expect_sql "SELECT status || ':' || branch_id FROM dt_serial_numbers WHERE tenant_id = $TENANT AND serial = 'NB0001'" "damaged:$BR_HQ"
check "HQ NB ไม่เพิ่ม (ไม่เข้าคลัง)" "$nb_before" "$(stock "$P_NB")"
check "HQ MS +1" "20/200.0000" "$(stock "$P_MS")"
call GET "/sales/$SALE"
check "returnStatus full / creditedTotal = total บิล" "full 25700 25700" "$(num .returnStatus) $(num .creditedTotal) $(num .total)"
cn "$SALE" "$(line 0 1)" "$(refund "$PM_CASH" 350)" "คืนเพิ่ม" 350
expect_status 400
expect_msg "MS-001 คืนได้ไม่เกิน 0 ชิ้น"

case_id 25.5 "ยอดของกะ / รายการใบลดหนี้"
call GET "/shifts/$BSHIFT/summary"
check "refundCount / refundTotal ของกะ boss" "2 25700" "$(num .refundCount) $(num .refundTotal)"
check "เงินสดคาด = 1,000 − 25,700" -24700 "$(jq -r '.lines[] | select(.type == "cash") | .expected + 0' <<<"$BODY")"
call GET "/credit-notes?from=$today&to=$today"
expect_status 200
check "รายการวันนี้" "CN-HQ-$ymd-0001,CN-HQ-$ymd-0002" "$(jq -r '[.[].cnNo] | sort | join(",")' <<<"$BODY")"
call GET "/credit-notes?from=$yesterday&to=$yesterday"
check "เมื่อวานไม่มี (วันที่ใบลดหนี้ = วันออก)" 0 "$(num length)"
call @api2 GET "/credit-notes?shiftId=$BSHIFT"
check "?shiftId=" 2 "$(num length)"
call GET /credit-notes
expect_status 400
call GET "/sales/$SALE/credit-notes"
check "/sales/{id}/credit-notes" "CN-HQ-$ymd-0001,CN-HQ-$ymd-0002" "$(jq -r '[.[].cnNo] | join(",")' <<<"$BODY")"
expect_sql "SELECT count(*) FROM dt_credit_notes WHERE tenant_id = $TENANT AND buyer_enc IS NOT NULL AND tax_invoice_no = '$INV'" 2
as -; branch -; terminal -

finish
