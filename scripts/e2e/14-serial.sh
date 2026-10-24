#!/usr/bin/env bash
# ข้อ 14 — Serial (docs/e2e-test-plan.md)
# 14.1 รับ NB0006 (@23,000) กับ NB0007 (@20,000) คนละ call (ทุนต่างกัน) → movement 2 แถว
E2E_STEP=14
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

std_company E14
login "$CODE" boss pass1234; T_BOSS=$TOKEN
login "$CODE" cash1 pass1234; T_CASH1=$TOKEN
num() { jq -r "$1 | if type == \"number\" then (. + 0 | tostring) else tostring end" <<<"$BODY"; }
sn() { sql "SELECT id FROM dt_serial_numbers WHERE tenant_id = $TENANT AND serial = '$1'"; }
sn_row() { sql "SELECT id || ':' || branch_id || ':' || status || ':' || cost || ':' || (removed_at IS NOT NULL) FROM dt_serial_numbers WHERE tenant_id = $TENANT AND serial = '$1'"; }
nb_stock() { sql "SELECT coalesce((SELECT stock || '/' || avg_cost FROM dt_branch_stock WHERE tenant_id = $TENANT AND branch_id = $1 AND product_id = $P_NB), '0/-')"; }
receive() { call POST "/products/$P_NB/serials" "$(jq -nc --argjson s "$1" --argjson c "${2:-null}" '{serials:$s, unitCost:$c, note:""}')"; }
last_move() { sql "SELECT type || ':' || qty || ':' || unit_cost || ':' || total_cost || ':' || balance_qty || ':' || array_to_string(serials, ',') || ':' || note
  FROM dt_stock_movements WHERE tenant_id = $TENANT AND product_id = $P_NB AND branch_id = $1 ORDER BY id DESC LIMIT 1"; }
as "$T_BOSS"; branch "$BR_HQ"

case_id 14.1 "รับ NB0006 @23,000 + ' nb0007 ' @20,000"
receive '["NB0006"]' 23000
expect_status 200
check "movement NB0006" "receive:1:23000.00:23000.00:6:NB0006:" "$(last_move "$BR_HQ")"
receive '[" nb0007 "]' 20000
expect_status 200
check "stock / avgCost" "7 20428.5714" "$(num .product.stock) $(num .product.avgCost)"
expect_json '[.received[] | .serial + ":" + .status] | join(",")' "NB0007:in_stock"
expect_json '.fromOtherBranch | length' 0
check "movement NB0007" "receive:1:20000.00:20000.00:7:NB0007:" "$(last_move "$BR_HQ")"
expect_sql "SELECT string_agg(serial || ':' || cost, ',' ORDER BY serial) FROM dt_serial_numbers WHERE tenant_id = $TENANT AND serial IN ('NB0006', 'NB0007')" \
  "NB0006:23000.0000,NB0007:20000.0000"
check "dt_branch_stock HQ" "7/20428.5714" "$(nb_stock "$BR_HQ")"
call GET "/products/$P_NB/serials"
expect_json '[.[] | select(.status == "in_stock") | .serial] | length' 7

case_id 14.2 "รับผิด"
# เตรียม: NB0003 ไปอยู่ BN (โอน + รับ), NB0002 ระหว่างโอน
call POST /transfers "{\"toBranchId\":$BR_BN,\"lines\":[{\"productId\":$P_NB,\"qty\":1,\"serials\":[\"NB0003\"]}],\"note\":\"\"}"
expect_status 200
TR1=$(jq -r .id <<<"$BODY")
branch "$BR_BN"
call POST "/transfers/$TR1/receive" "{\"lines\":[{\"productId\":$P_NB,\"receivedQty\":1,\"receivedSerials\":[\"NB0003\"]}],\"shortReason\":\"\"}"
expect_status 200
branch "$BR_HQ"
call POST /transfers "{\"toBranchId\":$BR_BN,\"lines\":[{\"productId\":$P_NB,\"qty\":1,\"serials\":[\"NB0002\"]}],\"note\":\"\"}"
expect_status 200
check "เตรียม: NB0003 / NB0002" "$(sn NB0003):$BR_BN:in_stock $(sn NB0002):$BR_HQ:in_transit" \
  "$(sn_row NB0003 | cut -d: -f1-3) $(sn_row NB0002 | cut -d: -f1-3)"
serials=$(sql "SELECT count(*) FROM dt_serial_numbers WHERE tenant_id = $TENANT")
moves=$(sql "SELECT count(*) FROM dt_stock_movements WHERE tenant_id = $TENANT")
while IFS='|' read -r list msg; do
  receive "$list" 1000
  expect_status 400
  expect_msg "$msg"
done <<'EOF'
[]|ไม่มี Serial ที่จะรับเข้า
[" "]|รับเข้าไม่ได้ — : ใช้ได้เฉพาะ A-Z, 0-9 และ -
["NB0010","nb0010"]|รับเข้าไม่ได้ — NB0010: ซ้ำในรายการ
["XX01"]|รับเข้าไม่ได้ — XX01: ต้องขึ้นต้นด้วย NB
["NB 0011"]|รับเข้าไม่ได้ — NB 0011: ใช้ได้เฉพาะ A-Z, 0-9 และ -
["NB0001"]|รับเข้าไม่ได้ — NB0001: มีอยู่ในคลังแล้ว
["NB0003"]|รับเข้าไม่ได้ — NB0003: อยู่ในคลังสาขา BN
["NB0002"]|รับเข้าไม่ได้ — NB0002: อยู่ระหว่างโอน
["NB0012","NB0001","XX02"]|รับเข้าไม่ได้ — NB0001: มีอยู่ในคลังแล้ว, XX02: ต้องขึ้นต้นด้วย NB
EOF
receive '["NB0013"]' -1
expect_msg "ต้นทุนต้องไม่ติดลบ"
expect_sql "SELECT (SELECT count(*) FROM dt_serial_numbers WHERE tenant_id = $TENANT) || ':' || (SELECT count(*) FROM dt_stock_movements WHERE tenant_id = $TENANT)" "$serials:$moves"
call POST "/products/$P_MS/serials" '{"serials":["MS1"],"unitCost":1,"note":""}'
expect_status 400
check "สินค้าไม่คุม serial" 400 "$STATUS"
echo "     (ข้อความ: $(jq -r .message <<<"$BODY"))"

case_id 14.3 "ตัดออก NB0006 damaged"
SN6=$(sn NB0006)
before_hq=$(nb_stock "$BR_HQ")
as "$T_CASH1"
call POST "/products/$P_NB/serials/remove" "{\"ids\":[$SN6],\"status\":\"damaged\",\"note\":\"จอแตก\"}"
expect_status 403
expect_msg "ตัดสต็อกออกได้เฉพาะผู้ดูแลระบบ"
as "$T_BOSS"
call POST "/products/$P_NB/serials/remove" "{\"ids\":[$SN6],\"status\":\"lost\",\"note\":\"x\"}"
expect_status 400
expect_msg "ข้อมูลที่ส่งมาไม่ถูกต้อง"
call POST "/products/$P_NB/serials/remove" '{"ids":[],"status":"damaged","note":"x"}'
expect_status 400
expect_msg "ไม่ได้เลือก Serial"
check "ยังไม่ถูกตัด" "$before_hq" "$(nb_stock "$BR_HQ")"
call POST "/products/$P_NB/serials/remove" "{\"ids\":[$SN6],\"status\":\"damaged\",\"note\":\" จอแตก \"}"
expect_status 200
check "stock (7 − โอน NB0003 − โอน NB0002 − ตัด NB0006)" 4 "$(num .stock)"
check "serial NB0006" "$SN6:$BR_HQ:damaged:23000.0000:true" "$(sn_row NB0006)"
check "movement issue" "issue:-1:23000.00:-23000.00:$(num .stock):NB0006:ชำรุด/เสียหาย ·  จอแตก " "$(last_move "$BR_HQ")"
expect_sql "SELECT action || ':' || user_id || ':' || branch_id || ':' || ref_label || ':' || detail FROM dt_audit_log WHERE tenant_id = $TENANT AND action = 'serial_remove'" \
  "serial_remove:$U_BOSS:$BR_HQ:NB-001:NB0006 → damaged — จอแตก"
call @api2 POST "/products/$P_NB/serials/remove" "{\"ids\":[$SN6],\"status\":\"damaged\",\"note\":\"ซ้ำ\"}"
expect_status 400
expect_msg "มี Serial ที่ไม่อยู่ในคลังแล้ว กรุณาโหลดใหม่"
call POST "/products/$P_NB/serials/remove" "{\"ids\":[$(sn NB0003)],\"status\":\"other\",\"note\":\"อยู่ BN\"}"
expect_status 400
expect_msg "มี Serial ที่ไม่อยู่ในคลังแล้ว กรุณาโหลดใหม่"

case_id 14.4 "รับ NB0006 กลับ @21,000"
receive '["NB0006"]' 21000
expect_status 200
expect_json '.fromOtherBranch | length' 0
check "แถวเดิม in_stock ทุน 21000" "$SN6:$BR_HQ:in_stock:21000.0000:false" "$(sn_row NB0006)"
check "movement รับคืน" "receive:1:21000.00:21000.00:$(num .product.stock):NB0006:รับคืนเข้าคลัง" "$(last_move "$BR_HQ")"

case_id 14.5 "ตัด NB0007 ที่ HQ (other) แล้วรับที่ BN"
SN7=$(sn NB0007)
hq_before=$(nb_stock "$BR_HQ" | cut -d/ -f1)
bn_before=$(nb_stock "$BR_BN" | cut -d/ -f1)
call POST "/products/$P_NB/serials/remove" "{\"ids\":[$SN7],\"status\":\"other\",\"note\":\"ย้ายไปบางนา\"}"
expect_status 200
branch "$BR_BN"
receive '["NB0007"]' 20000
expect_status 200
expect_json '[.fromOtherBranch[] | .serial + ":" + .branchCode] | join(",")' "NB0007:HQ"
check "แถวเดิม ย้ายไป BN in_stock" "$SN7:$BR_BN:in_stock:20000.0000:false" "$(sn_row NB0007)"
check "HQ NB −1 / BN NB +1" "$((hq_before - 1)) $((bn_before + 1))" "$(nb_stock "$BR_HQ" | cut -d/ -f1) $(nb_stock "$BR_BN" | cut -d/ -f1)"
check "stock HQ = serial in_stock ของ HQ" "$(sql "SELECT count(*) FROM dt_serial_numbers WHERE tenant_id = $TENANT AND branch_id = $BR_HQ AND status = 'in_stock'")" \
  "$(nb_stock "$BR_HQ" | cut -d/ -f1)"
check "stock BN = serial in_stock ของ BN" "$(sql "SELECT count(*) FROM dt_serial_numbers WHERE tenant_id = $TENANT AND branch_id = $BR_BN AND status = 'in_stock'")" \
  "$(nb_stock "$BR_BN" | cut -d/ -f1)"
check "avgCost HQ = เฉลี่ยทุนเครื่องในคลัง (ปัด 4)" \
  "$(sql "SELECT round(avg(cost), 4) FROM dt_serial_numbers WHERE tenant_id = $TENANT AND branch_id = $BR_HQ AND status = 'in_stock'")" \
  "$(nb_stock "$BR_HQ" | cut -d/ -f2)"
as -; branch -

finish
