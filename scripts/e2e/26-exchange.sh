#!/usr/bin/env bash
# ข้อ 26 — ใบเปลี่ยนสินค้า (docs/e2e-test-plan.md)
# บิล A (วันนี้): MS ×2 + NB0001 + EXM ×1 เงินสด; บิล B: MS ×1 เลื่อนเป็น 8 วันก่อน; NB0005 โอนไปอยู่ BN
E2E_STEP=26
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

ymd=$(TZ=Asia/Bangkok date +%Y%m%d)
std_company E26
login "$CODE" boss pass1234; T_BOSS=$TOKEN
login "$CODE" cash1 pass1234; T_CASH1=$TOKEN
num() { jq -r "$1 | if type == \"number\" then (. + 0 | tostring) else tostring end" <<<"$BODY"; }
at() { as "$1"; branch "$2"; terminal "${3:--}"; }
stock() { sql "SELECT stock FROM dt_branch_stock WHERE tenant_id = $TENANT AND branch_id = $BR_HQ AND product_id = $1"; }
sn() { sql "SELECT status || ':' || branch_id FROM dt_serial_numbers WHERE tenant_id = $TENANT AND serial = '$1'"; }
line() { jq -nc --argjson i "$1" --argjson q "${2:-1}" --arg s "${3:-}" --argjson r "${4:-true}" '{saleLineIndex:$i, qty:$q, newSerial:(if $s == "" then null else $s end), restock:$r}'; }
ex() { call POST "/sales/$1/exchanges" "$(jq -nc --argjson l "[$2]" --arg r "${3:-ลูกค้าขอเปลี่ยน}" '{lines:$l, reason:$r}')"; }
cnt() { sql "SELECT count(*) FROM dt_exchanges WHERE tenant_id = $TENANT"; }

# NB0005 → BN
at "$T_BOSS" "$BR_HQ"
call POST /transfers "{\"toBranchId\":$BR_BN,\"lines\":[{\"productId\":$P_NB,\"qty\":1,\"serials\":[\"NB0005\"]}],\"note\":\"\"}"
TR=$(jq -r .id <<<"$BODY"); branch "$BR_BN"
call POST "/transfers/$TR/receive" "{\"lines\":[{\"productId\":$P_NB,\"receivedQty\":1,\"receivedSerials\":[\"NB0005\"]}],\"shortReason\":\"\"}"
at "$T_CASH1" "$BR_HQ" 1
call POST /shifts/open '{"openingFloat":0,"openingDenominations":[]}'
SHIFT=$(jq -r .id <<<"$BODY")
call POST /sales "$(jq -nc --argjson ms "$P_MS" --argjson nb "$P_NB" --argjson ex "$P_EXM" --argjson m "$PM_CASH" '{items:[{productId:$ms, factor:1, qty:2, serial:null},
  {productId:$nb, factor:1, qty:1, serial:"NB0001"}, {productId:$ex, factor:1, qty:1, serial:null}], freeSerials:[], payments:[{methodId:$m, amount:25820, reference:""}],
  customer:"", expectedTotal:25820}')"
A=$(jq -r .id <<<"$BODY")
call POST /sales "$(jq -nc --argjson ms "$P_MS" --argjson m "$PM_CASH" '{items:[{productId:$ms, factor:1, qty:1, serial:null}], freeSerials:[], payments:[{methodId:$m, amount:350, reference:""}], customer:"", expectedTotal:350}')"
B=$(jq -r .id <<<"$BODY")
sql "UPDATE dt_sales SET sold_at = sold_at - interval '8 days' WHERE id = $B" >/dev/null
check "เตรียม: บิล A / B, NB0005 ที่ BN" "in_stock:$BR_BN" "$(sn NB0005)"

case_id 26.1 "cash1 เปลี่ยน NB0001 → NB0002 (คืนเข้าคลัง)"
nb=$(stock "$P_NB")
ex "$A" "$(line 1 1 NB0002)"
expect_status 200
EX1=$(jq -r .id <<<"$BODY")
check "เลข / shift / cashier" "EX-HQ-$ymd-0001 แคชเชียร์หนึ่ง" "$(num .exNo) $(num .cashier)"
check "NB0001 in_stock / NB0002 sold" "in_stock:$BR_HQ sold:$BR_HQ" "$(sn NB0001) $(sn NB0002)"
check "HQ NB ไม่เปลี่ยน (เข้า 1 ออก 1)" "$nb" "$(stock "$P_NB")"
expect_sql "SELECT sku || ':' || qty || ':' || old_serial || ':' || new_serial || ':' || restock || ':' || cost_in || ':' || cost_out FROM dt_exchange_lines WHERE tenant_id = $TENANT AND exchange_id = $EX1" \
  "NB-001:1:NB0001:NB0002:true:20000.00:20000.00"   # costIn / costOut = ทุนรวมของหน่วย (แบบ COGS) numeric(14,2)
expect_sql "SELECT coalesce(shift_id::text, '-') || ':' || reason FROM dt_exchanges WHERE id = $EX1" "$SHIFT:ลูกค้าขอเปลี่ยน"
call GET "/sales/$A"
check "บิล A: exchangeNos / ยอดไม่เปลี่ยน (ไม่มีเงินเดิน)" "EX-HQ-$ymd-0001 25820" "$(num '.exchangeNos | join(",")') $(num .total)"
as "$T_BOSS"; call GET "/shifts/$SHIFT/summary"; as "$T_CASH1"   # staff ไม่เห็น lines ระหว่างกะเปิด (blind count)
check "กะ: exchangeCount 1, เงินสดคาด = ยอดขาย (ไม่มีเงินเดิน)" "1 26170" "$(num .exchangeCount) $(jq -r '.lines[] | select(.type == "cash") | .expected + 0' <<<"$BODY")"

case_id 26.2 "serial ใหม่ผิด"
before=$(cnt)
while IFS='|' read -r lines msg; do
  ex "$A" "$lines"
  expect_status 400
  expect_msg "$msg"
done <<EOF
$(line 1 1 NB0002)|NB-001: ซีเรียลใหม่ต้องไม่ใช่เครื่องเดิม
$(line 1 1)|NB-001: กรุณาเลือกซีเรียลเครื่องใหม่
$(line 1 1 NB0005)|ซีเรียล NB0005 อยู่ที่สาขา BN — เปลี่ยนให้ลูกค้าที่สาขานี้ไม่ได้
$(line 1 1 NB0003), $(line 1 1 NB0003)|เลือกรายการซ้ำ
$(line 1 1 NB0009)|ซีเรียล NB0009 ไม่อยู่ในคลังของ NB-001
$(line 9 1)|ไม่พบรายการที่ 10 ในบิล
|กรุณาเลือกสินค้าที่เปลี่ยน
EOF
check "ไม่มีใบใหม่" "$before" "$(cnt)"

case_id 26.3 "MS เปลี่ยน 1 (ของเสียไม่เข้าคลัง) / เกินจำนวน / สต็อกไม่พอ"
ms=$(stock "$P_MS")
ex "$A" "$(line 0 1 "" false)" "ของชำรุด"
expect_status 200
check "MS −1 (ของใหม่ออก, ของเดิมไม่เข้าคลัง)" "$((ms - 1))" "$(stock "$P_MS")"
expect_sql "SELECT string_agg(type || ':' || qty, ',' ORDER BY id) FROM dt_stock_movements WHERE tenant_id = $TENANT AND product_id = $P_MS AND note LIKE '%EX-HQ-$ymd-0002%'" "issue:-1"
echo "     movements ของ EX-0002: $(sql "SELECT string_agg(type || ' ' || qty || ' ' || note, ' | ' ORDER BY id) FROM dt_stock_movements WHERE tenant_id = $TENANT AND note LIKE '%EX-HQ-$ymd-0002%'")"
ex "$A" "$(line 0 3)"
expect_status 400
expect_msg "MS-001 เปลี่ยนได้ไม่เกิน 2 ชิ้น"
as "$T_BOSS"; branch "$BR_HQ"
call PUT "/products/$P_EXM/stock" "{\"delta\":-$(stock "$P_EXM"),\"note\":\"ให้สต็อกหมด\"}"
as "$T_CASH1"; branch "$BR_HQ"; terminal 1
ex "$A" "$(line 2 1)"
expect_status 400
expect_msg "EXM-001 สต็อกไม่พอเปลี่ยน (คงเหลือ 0 เล่ม)"

case_id 26.4 "บิลเกิน 7 วัน: staff / boss"
ex "$B" "$(line 0 1)"
expect_status 400
expect_msg "เกินกำหนดเปลี่ยนสินค้า 7 วัน — ให้ผู้ดูแลระบบทำรายการ"
at "$T_BOSS" "$BR_HQ" 2
ex "$B" "$(line 0 1)" "admin เปลี่ยนให้"
expect_status 200
expect_json .cashier "คุณบอส"
check "boss ไม่มีกะที่เครื่อง 2 ก็เปลี่ยนได้ (ไม่มีเงินเดิน)" "-" "$(sql "SELECT coalesce(shift_id::text, '-') FROM dt_exchanges WHERE tenant_id = $TENANT AND sale_id = $B")"
at "$T_BOSS" "$BR_BN"
ex "$A" "$(line 0 1)"
expect_status 400
expect_msg "บิลนี้ขายที่สาขา HQ — เปลี่ยนสินค้าได้ที่สาขานั้น"

case_id 26.5 "ยกเลิกบิลวันเดียวกันหลังเปลี่ยน serial"
at "$T_CASH1" "$BR_HQ" 1
call @api2 POST "/sales/$A/void" '{"reason":"ยกเลิกหลังเปลี่ยน","approval":{"username":"boss","password":"pass1234"}}'
expect_status 200
check "คืน serial ที่ลูกค้าถืออยู่ (NB0002) — NB0001 ไม่ถูกคืนซ้ำ" "in_stock:$BR_HQ in_stock:$BR_HQ" "$(sn NB0002) $(sn NB0001)"
expect_sql "SELECT array_to_string(serials, ',') FROM dt_stock_movements WHERE tenant_id = $TENANT AND product_id = $P_NB AND note LIKE 'ยกเลิกบิล %'" NB0002
call GET "/sales/$A/exchanges"
check "/sales/{id}/exchanges" "EX-HQ-$ymd-0001,EX-HQ-$ymd-0002" "$(jq -r '[.[].exNo] | join(",")' <<<"$BODY")"
as -; branch -; terminal -

finish
