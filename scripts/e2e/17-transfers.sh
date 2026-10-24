#!/usr/bin/env bash
# ข้อ 17 — ใบโอนสินค้า (docs/e2e-test-plan.md)
E2E_STEP=17
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

ymd=$(TZ=Asia/Bangkok date +%Y%m%d)
std_company E17
login "$CODE" boss pass1234; T_BOSS=$TOKEN
login "$CODE" mgr pass1234; T_MGR=$TOKEN
login "$CODE" cash1 pass1234; T_CASH1=$TOKEN
login "$CODE" cash2 pass1234; T_CASH2=$TOKEN
stock() { sql "SELECT coalesce((SELECT stock || '/' || avg_cost FROM dt_branch_stock WHERE tenant_id = $TENANT AND branch_id = $1 AND product_id = $2), '0/-')"; }
line() { jq -nc --argjson p "$1" --argjson q "$2" --argjson s "${3:-[]}" '{productId:$p, qty:$q, serials:$s}'; }
tr_body() { jq -nc --argjson to "$1" --argjson lines "$2" '{toBranchId:$to, lines:$lines, note:"e2e"}'; }
got() { jq -nc --argjson p "$1" --argjson q "$2" --argjson s "${3:-[]}" '{productId:$p, receivedQty:$q, receivedSerials:$s}'; }
rcv_body() { jq -nc --argjson lines "$1" --arg r "${2:-}" '{lines:$lines, shortReason:$r}'; }
call_as() { as "$1"; branch "$2"; shift 2; call "$@"; }

case_id 17.1 "cash1 โอน HQ→BN: MS 3 + NB0002"
call_as "$T_CASH1" "$BR_HQ" POST /transfers "$(tr_body "$BR_BN" "[$(line "$P_MS" 3), $(line "$P_NB" 1 '["NB0002"]')]")"
expect_status 200
TR1=$(jq -r .id <<<"$BODY")
expect_json '[.transferNo, .status] | join(" ")' "TR-HQ-$ymd-0001 in_transit"
check "HQ MS 20→17 (ทุน 200 คงเดิม)" "17/200.0000" "$(stock "$BR_HQ" "$P_MS")"
check "HQ NB 5→4" "4/20000.0000" "$(stock "$BR_HQ" "$P_NB")"
expect_sql "SELECT status || ':' || branch_id FROM dt_serial_numbers WHERE tenant_id = $TENANT AND serial = 'NB0002'" "in_transit:$BR_HQ"
expect_sql "SELECT string_agg(type || ':' || qty || ':' || unit_cost || ':' || total_cost || ':' || array_to_string(serials, ','), ',' ORDER BY product_id)
  FROM dt_stock_movements WHERE tenant_id = $TENANT AND branch_id = $BR_HQ AND type = 'transfer_out'" "transfer_out:-3:200.00:-600.00:,transfer_out:-1:20000.00:-20000.00:NB0002"
expect_sql "SELECT string_agg(qty || ':' || unit_cost || ':' || array_to_string(serials, ',') || ':' || coalesce(received_qty::text, 'null'), ',' ORDER BY product_id)
  FROM dt_transfer_lines WHERE tenant_id = $TENANT AND transfer_id = $TR1" "3:200.0000::null,1:20000.0000:NB0002:null"
expect_sql "SELECT created_by_name || ':' || from_branch_id || ':' || to_branch_id FROM dt_transfers WHERE id = $TR1" "แคชเชียร์หนึ่ง:$BR_HQ:$BR_BN"

case_id 17.2 "ผิดกฎ"
call_as "$T_BOSS" "$BR_HQ" POST /branches '{"code":"OLD","name":"สาขาปิด","branchNo":"00002","address":"กรุงเทพฯ","phone":"","receiptFooter":"","active":false}'
BR_OLD=$(jq -r .id <<<"$BODY")
transfers=$(sql "SELECT count(*) FROM dt_transfers WHERE tenant_id = $TENANT")
as "$T_CASH1"
while IFS='|' read -r body msg; do
  call POST /transfers "$body"
  expect_status 400
  expect_msg "$msg"
done <<EOF
$(tr_body null "[$(line "$P_MS" 1)]")|กรุณาเลือกสาขาปลายทาง
$(tr_body "$BR_HQ" "[$(line "$P_MS" 1)]")|สาขาปลายทางต้องไม่ใช่สาขาต้นทาง
$(tr_body "$BR_OLD" "[$(line "$P_MS" 1)]")|สาขา OLD ปิดใช้งานแล้ว
$(tr_body "$BR_BN" "[]")|ยังไม่มีรายการสินค้าที่จะโอน
$(tr_body "$BR_BN" "[$(line 99999999 1)]")|ไม่พบ SKU ในรายการโอน
$(tr_body "$BR_BN" "[$(line "$P_MS" 1), $(line "$P_MS" 1)]")|MS-001 ซ้ำในรายการ
$(tr_body "$BR_BN" "[$(line "$P_MS" 18)]")|MS-001 มีสต็อกไม่พอ (คงเหลือ 17 ชิ้น)
$(tr_body "$BR_BN" "[$(line "$P_MS" 0)]")|MS-001: จำนวนต้องเป็นจำนวนเต็มมากกว่า 0
$(tr_body "$BR_BN" "[$(line "$P_MS" 1.5)]")|MS-001: จำนวนต้องเป็นจำนวนเต็มมากกว่า 0
$(tr_body "$BR_BN" "[$(line "$P_MS" 1 '["X1"]')]")|MS-001 ไม่ได้ควบคุม Serial
$(tr_body "$BR_BN" "[$(line "$P_NB" 1)]")|NB-001: กรุณาเลือก Serial ที่จะโอน
$(tr_body "$BR_BN" "[$(line "$P_NB" 2 '["NB0003","NB0003"]')]")|NB-001: Serial ซ้ำในรายการ
$(tr_body "$BR_BN" "[$(line "$P_NB" 2 '["NB0003"]')]")|NB-001: จำนวนต้องเท่ากับจำนวน Serial
$(tr_body "$BR_BN" "[$(line "$P_NB" 1 '["NB0002"]')]")|ซีเรียล NB0002 ไม่อยู่ในคลังของ NB-001 ที่สาขานี้
$(tr_body "$BR_BN" "[$(line "$P_SVC" 1)]")|SVC-001 เป็นสินค้าบริการ ไม่มีสต็อก
EOF
branch -
call POST /transfers "$(tr_body "$BR_BN" "[$(line "$P_MS" 1)]")"
expect_status 400
expect_msg "ไม่ได้เลือกสาขา กรุณาเลือกสาขาที่ทำงานก่อน"
expect_sql "SELECT count(*) FROM dt_transfers WHERE tenant_id = $TENANT" "$transfers"
check "HQ MS ไม่เปลี่ยน" "17/200.0000" "$(stock "$BR_HQ" "$P_MS")"

case_id 17.3 "รับผิด"
call_as "$T_CASH1" "$BR_HQ" POST "/transfers/$TR1/receive" "$(rcv_body "[$(got "$P_MS" 3), $(got "$P_NB" 1 '["NB0002"]')]")"
expect_status 400
expect_msg "รับของได้ที่สาขาปลายทางเท่านั้น"
while IFS='|' read -r body msg; do
  call_as "$T_CASH2" "$BR_BN" POST "/transfers/$TR1/receive" "$body"
  expect_status 400
  expect_msg "$msg"
done <<EOF
$(rcv_body "[$(got "$P_MS" 4), $(got "$P_NB" 1 '["NB0002"]')]")|MS-001: จำนวนที่รับต้องอยู่ระหว่าง 0–3
$(rcv_body "[$(got "$P_MS" -1), $(got "$P_NB" 1 '["NB0002"]')]")|MS-001: จำนวนที่รับต้องอยู่ระหว่าง 0–3
$(rcv_body "[$(got "$P_NB" 1 '["NB0002"]')]")|MS-001: ไม่ได้ระบุจำนวนที่รับ
$(rcv_body "[$(got "$P_MS" 3), $(got "$P_NB" 1 '["NB0005"]')]")|ซีเรียล NB0005 ไม่อยู่ในใบโอนนี้
$(rcv_body "[$(got "$P_MS" 3), $(got "$P_NB" 1 '[]')]")|NB-001: จำนวนที่รับต้องเท่ากับจำนวน Serial ที่ติ๊ก
$(rcv_body "[$(got "$P_MS" 2), $(got "$P_NB" 1 '["NB0002"]')]" " ")|กรุณาระบุเหตุผลของสินค้าที่ขาด / เสียหาย
EOF
expect_sql "SELECT status FROM dt_transfers WHERE id = $TR1" in_transit

case_id 17.4 "cash2 รับที่ BN: MS 2 (ขาด 1) + NB0002"
call_as "$T_CASH2" "$BR_BN" @api2 POST "/transfers/$TR1/receive" "$(rcv_body "[$(got "$P_MS" 2), $(got "$P_NB" 1 '["NB0002"]')]" "กล่องเสียหาย")"
expect_status 200
expect_json .status received
check "BN MS: 5@220 + 3@200 → 212.5 แล้วตัดขาด 1 → 7" "7/212.5000" "$(stock "$BR_BN" "$P_MS")"
check "BN NB" "1/20000.0000" "$(stock "$BR_BN" "$P_NB")"
expect_sql "SELECT status || ':' || branch_id FROM dt_serial_numbers WHERE tenant_id = $TENANT AND serial = 'NB0002'" "in_stock:$BR_BN"
expect_sql "SELECT string_agg(type || ':' || qty || ':' || unit_cost || ':' || balance_qty || ':' || balance_avg_cost || ':' || note, ' | ' ORDER BY id)
  FROM dt_stock_movements WHERE tenant_id = $TENANT AND branch_id = $BR_BN AND product_id = $P_MS AND type <> 'receive'" \
  "transfer_in:3:200.00:8:212.5000:รับโอนจากสาขา HQ TR-HQ-$ymd-0001 | issue:-1:212.50:7:212.5000:ขาด/เสียหายจากการโอน TR-HQ-$ymd-0001 · กล่องเสียหาย"
expect_sql "SELECT status || ':' || received_by_name || ':' || short_reason || ':' || (received_at IS NOT NULL) FROM dt_transfers WHERE id = $TR1" \
  "received:แคชเชียร์สอง:กล่องเสียหาย:true"
expect_sql "SELECT string_agg(received_qty || ':' || array_to_string(received_serials, ','), ',' ORDER BY product_id) FROM dt_transfer_lines WHERE transfer_id = $TR1" "2:,1:NB0002"

case_id 17.5 "ใบที่ 2 (MS 1): ยกเลิก"
call_as "$T_CASH1" "$BR_HQ" POST /transfers "$(tr_body "$BR_BN" "[$(line "$P_MS" 1)]")"
expect_status 200
TR2=$(jq -r .id <<<"$BODY")
expect_json .transferNo "TR-HQ-$ymd-0002"   # ค่าผิดใน 17.2 ไม่กินเลข
check "HQ MS 17→16" "16/200.0000" "$(stock "$BR_HQ" "$P_MS")"
call POST "/transfers/$TR2/cancel" '{"reason":"cash1 ยกเลิก"}'
expect_status 403
expect_msg "ยกเลิกใบโอนได้เฉพาะผู้ดูแลระบบ"
call_as "$T_BOSS" "$BR_BN" POST "/transfers/$TR2/cancel" '{"reason":"boss ที่ BN"}'
expect_status 400
expect_msg "ยกเลิกได้ที่สาขาต้นทางเท่านั้น"
call_as "$T_MGR" "$BR_HQ" POST "/transfers/$TR2/cancel" '{"reason":"  "}'
expect_status 400
expect_msg "กรุณาระบุเหตุผลการยกเลิก"
call @api2 POST "/transfers/$TR2/cancel" '{"reason":" ลูกค้ายกเลิก "}'
expect_status 200
expect_json .status cancelled
check "HQ MS คืน 17" "17/200.0000" "$(stock "$BR_HQ" "$P_MS")"
expect_sql "SELECT cancelled_by_name || ':' || cancel_reason || ':' || (cancelled_at IS NOT NULL) FROM dt_transfers WHERE id = $TR2" "คุณผู้จัดการ:ลูกค้ายกเลิก:true"
expect_sql "SELECT action || ':' || user_id || ':' || branch_id || ':' || ref_label || ':' || detail FROM dt_audit_log WHERE tenant_id = $TENANT AND action = 'transfer_cancel'" \
  "transfer_cancel:$U_MGR:$BR_HQ:TR-HQ-$ymd-0002:ลูกค้ายกเลิก"
expect_sql "SELECT type || ':' || qty || ':' || note FROM dt_stock_movements WHERE tenant_id = $TENANT AND branch_id = $BR_HQ AND product_id = $P_MS ORDER BY id DESC LIMIT 1" \
  "transfer_in:1:ยกเลิกใบโอน TR-HQ-$ymd-0002"

case_id 17.6 "ยกเลิก / รับใบที่จบแล้ว"
call_as "$T_MGR" "$BR_HQ" POST "/transfers/$TR1/cancel" '{"reason":"ยกเลิกใบที่รับแล้ว"}'
expect_status 400
expect_msg "ยกเลิกได้เฉพาะใบโอนที่ยังไม่ได้รับ"
call_as "$T_CASH2" "$BR_BN" POST "/transfers/$TR2/receive" "$(rcv_body "[$(got "$P_MS" 1)]")"
expect_status 400
expect_msg "ใบโอนนี้รับหรือยกเลิกไปแล้ว"
call POST "/transfers/$TR1/receive" "$(rcv_body "[$(got "$P_MS" 3), $(got "$P_NB" 1 '["NB0002"]')]")"
expect_status 400
expect_msg "ใบโอนนี้รับหรือยกเลิกไปแล้ว"
check "BN MS ไม่เปลี่ยน" "7/212.5000" "$(stock "$BR_BN" "$P_MS")"

case_id 17.7 "สาขาที่ 3 CM: cash2 (BN) เปิดดูใบ HQ→CM"
call_as "$T_BOSS" "$BR_HQ" POST /branches '{"code":"CM","name":"สาขาเชียงใหม่","branchNo":"00003","address":"เชียงใหม่","phone":"","receiptFooter":"","active":true}'
BR_CM=$(jq -r .id <<<"$BODY")
call POST /transfers "$(tr_body "$BR_CM" "[$(line "$P_MS" 1)]")"
expect_status 200
TR3=$(jq -r .id <<<"$BODY")
call_as "$T_CASH2" "$BR_BN" GET "/transfers/$TR3"
expect_status 403
expect_msg "ไม่มีสิทธิ์ดูใบโอนของสาขานี้"
call POST "/transfers/$TR3/receive" "$(rcv_body "[$(got "$P_MS" 1)]")"
expect_status 403
expect_msg "ไม่มีสิทธิ์ดูใบโอนของสาขานี้"
call GET /transfers
check "รายการของ cash2 ไม่มีใบ HQ→CM" "0 $(sql "SELECT count(*) FROM dt_transfers WHERE tenant_id = $TENANT AND (from_branch_id = $BR_BN OR to_branch_id = $BR_BN)")" \
  "$(jq -r --argjson t "$TR3" '[.[] | select(.id == $t)] | length' <<<"$BODY") $(jq length <<<"$BODY")"
call GET "/transfers/$TR1"
expect_status 200   # ใบที่ BN เกี่ยวข้อง
call_as "$T_BOSS" "$BR_HQ" GET /transfers
expect_json 'length' 3
as -; branch -

finish
