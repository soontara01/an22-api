#!/usr/bin/env bash
# ข้อ 13 — สต็อก / ทุนเฉลี่ย / บัตรสต็อก / การซ่อนทุน (docs/e2e-test-plan.md)
# บัตรสต็อก: เลื่อน created_at ของ movement แรกเป็นเมื่อวาน (SQL) ให้มียอดยกมา
E2E_STEP=13
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

today=$(TZ=Asia/Bangkok date +%F)
std_company E13
login "$CODE" boss pass1234; T_BOSS=$TOKEN
login "$CODE" mgr pass1234; T_MGR=$TOKEN
login "$CODE" cash1 pass1234; T_CASH1=$TOKEN
stock_row() { sql "SELECT stock || '/' || avg_cost FROM dt_branch_stock WHERE tenant_id = $TENANT AND branch_id = $BR_HQ AND product_id = $TST"; }
moves() { sql "SELECT string_agg(type || ':' || qty || ':' || unit_cost || ':' || total_cost || ':' || balance_qty || ':' || balance_avg_cost || ':' || balance_value, ',' ORDER BY id)
  FROM dt_stock_movements WHERE tenant_id = $TENANT AND branch_id = $BR_HQ AND product_id = $TST"; }
adjust() { call PUT "/products/$1/stock" "$2"; }
num() { jq -r "$1 | if type == \"number\" then (. + 0 | tostring) else tostring end" <<<"$BODY"; }

as "$T_BOSS"; branch "$BR_HQ"
call POST /products "{\"sku\":\"TST-001\",\"name\":\"สินค้าทดสอบทุน\",\"categoryId\":$CAT_MOUSE,\"unit\":\"ชิ้น\",\"cost\":100}"
TST=$(jq -r .id <<<"$BODY")
call POST /prices "{\"productId\":$TST,\"price\":300,\"startDate\":\"2020-01-01\",\"endDate\":null,\"note\":\"\"}"

case_id 13.1 "รับ 10 @200 → 10 @80"
adjust "$TST" '{"delta":10,"unitCost":200,"note":"ล็อตแรก"}'
expect_status 200
adjust "$TST" '{"delta":10,"unitCost":80,"note":"ล็อตสอง"}'
expect_status 200
check "stock / avgCost" "20 140" "$(num .stock) $(num .avgCost)"
check "dt_branch_stock" "20/140.0000" "$(stock_row)"
check "movements" "receive:10:200.00:2000.00:10:200.0000:2000.00,receive:10:80.00:800.00:20:140.0000:2800.00" "$(moves)"

case_id 13.2 "ตัดออก −5 ชำรุด (boss)"
adjust "$TST" '{"delta":-5,"note":" ชำรุด "}'
expect_status 200
check "stock / avgCost" "15 140" "$(num .stock) $(num .avgCost)"
expect_sql "SELECT type || ':' || qty || ':' || total_cost || ':' || balance_value || ':' || note FROM dt_stock_movements WHERE tenant_id = $TENANT AND product_id = $TST ORDER BY id DESC LIMIT 1" \
  "issue:-5:-700.00:2100.00: ชำรุด "
expect_sql "SELECT action || ':' || user_id || ':' || branch_id || ':' || ref_label || ':' || detail FROM dt_audit_log WHERE tenant_id = $TENANT AND action = 'stock_out'" \
  "stock_out:$U_BOSS:$BR_HQ:TST-001:-5 — ชำรุด"

case_id 13.3 "รับ +5 ไม่ส่ง unitCost (ใช้ทุนสินค้า 100)"
adjust "$TST" '{"delta":5,"note":"ไม่ระบุทุน"}'
expect_status 200
check "stock / avgCost" "20 130" "$(num .stock) $(num .avgCost)"
check "dt_branch_stock" "20/130.0000" "$(stock_row)"
expect_sql "SELECT unit_cost || ':' || total_cost || ':' || balance_avg_cost FROM dt_stock_movements WHERE tenant_id = $TENANT AND product_id = $TST ORDER BY id DESC LIMIT 1" \
  "100.00:500.00:130.0000"

case_id 13.4 "ค่าผิด"
before="$(stock_row) $(moves)"
while IFS='|' read -r pid body msg; do
  adjust "$pid" "$body"
  expect_status 400
  expect_msg "$msg"
done <<EOF
$TST|{"delta":0,"unitCost":1,"note":""}|จำนวนไม่ถูกต้อง
$TST|{"delta":1.5,"unitCost":1,"note":""}|จำนวนไม่ถูกต้อง
$TST|{"unitCost":1,"note":""}|จำนวนไม่ถูกต้อง
$TST|{"delta":1,"unitCost":-1,"note":""}|ต้นทุนต้องไม่ติดลบ
$P_NB|{"delta":1,"unitCost":1,"note":""}|สินค้าคุม Serial ต้องรับเข้า/ตัดออกด้วย Serial
$P_SVC|{"delta":1,"unitCost":1,"note":""}|สินค้าบริการไม่มีสต็อก
$TST|{"delta":-21,"note":"ตัดเกิน"}|สต็อกคงเหลือไม่พอ
EOF
as "$T_CASH1"
adjust "$TST" '{"delta":-1,"note":"staff"}'
expect_status 403
expect_msg "ตัดสต็อกออกได้เฉพาะผู้ดูแลระบบ"
adjust "$TST" '{"delta":1,"unitCost":130,"note":"staff รับ"}'
expect_status 200   # รับเข้าไม่ใช่งาน admin
adjust "$TST" '{"delta":-1,"note":"คืนค่า"}'
expect_status 403
as "$T_BOSS"
adjust "$TST" '{"delta":-1,"note":"คืนค่า"}'
expect_status 200
call PUT "/products/$TST" '{"saleStatus":"no_purchase"}'
expect_status 200
adjust "$TST" '{"delta":1,"unitCost":1,"note":""}'
expect_status 400
expect_msg 'สถานะ "ห้ามสั่งซื้อ" ห้ามรับเข้าสินค้า'
call PUT "/products/$TST" '{"saleStatus":"active"}'
check "stock / avgCost ไม่เปลี่ยน (รับ 1 @130 แล้วตัด 1)" "20/130.0000" "$(stock_row)"
expect_sql "SELECT count(*) FROM dt_stock_movements WHERE tenant_id = $TENANT AND product_id = $TST" 6

case_id 13.5 "บัตรสต็อก"
sql "UPDATE dt_stock_movements SET created_at = created_at - interval '1 day' WHERE id = (SELECT min(id) FROM dt_stock_movements WHERE tenant_id = $TENANT AND product_id = $TST)" >/dev/null
call GET "/products/$TST/movements"
expect_status 400
expect_msg "กรุณาเลือกช่วงวันที่ (ไม่เกิน 366 วัน)"
call GET "/products/$TST/movements?from=2025-01-01&to=$today"
expect_status 400
expect_msg "ช่วงวันที่ต้องไม่เกิน 366 วัน"
call GET "/products/$TST/movements?from=2026-1-1&to=$today"
expect_status 400
expect_msg "รูปแบบวันที่ต้องเป็น YYYY-MM-DD"
as "$T_MGR"
call GET "/products/$TST/movements?from=$today&to=$today&branchId=$BR_BN"
expect_status 403
expect_msg "ไม่มีสิทธิ์ดูบัตรสต็อกของสาขานี้"
as "$T_BOSS"
call @api2 GET "/products/$TST/movements?from=$today&to=$today"
expect_status 200
check "ยอดยกมา" "10 200 2000" "$(num .opening.qty) $(num .opening.avgCost) $(num .opening.value)"
check "จำนวนรายการในช่วง" 5 "$(num '.movements | length')"
check "ยอดคงเหลือ" "20 130 2600" "$(num .closing.qty) $(num .closing.avgCost) $(num .closing.value)"
check "รวมรับ / ตัด" "16 1430 6 830" "$(num .totals.inQty) $(num .totals.inCost) $(num .totals.outQty) $(num .totals.outCost)"
check "รายการ = dt_stock_movements (ไม่รวมยกมา)" \
  "$(sql "SELECT string_agg(id || ':' || type || ':' || qty || ':' || balance_qty, ',' ORDER BY id) FROM dt_stock_movements WHERE tenant_id = $TENANT AND product_id = $TST AND created_at >= current_date")" \
  "$(jq -r '[.movements[] | "\(.id):\(.type):\(.qty):\(.balanceQty)"] | join(",")' <<<"$BODY")"
call GET "/products/$TST/movements?from=$today&to=$today&branchId=$BR_BN"
expect_status 200
check "บัตรสต็อก BN (ไม่มีรายการ)" "0 0" "$(num .opening.qty) $(num '.movements | length')"

case_id 13.6 "branch-stock"
call POST /branches '{"code":"OLD","name":"สาขาปิด","branchNo":"00002","address":"กรุงเทพฯ","phone":"","receiptFooter":"","active":false}'
call GET "/products/$P_MS/branch-stock"
expect_status 200
check "ทุกสาขาที่ใช้งาน (ไม่มีสาขาปิด)" "$BR_HQ:20:200,$BR_BN:5:220" "$(jq -r '[.[] | "\(.branchId):\(.stock):\(.avgCost + 0)"] | join(",")' <<<"$BODY")"
call GET "/products/$TST/branch-stock"
check "ไม่มีแถว = 0" "$BR_HQ:20:130,$BR_BN:0:0" "$(jq -r '[.[] | "\(.branchId):\(.stock):\(.avgCost + 0)"] | join(",")' <<<"$BODY")"

case_id 13.7 "ซ่อนทุนเมื่อ staffSeeCost=false → เปิด true"
as "$T_CASH1"; terminal 1
call POST /shifts/open '{"openingFloat":0,"openingDenominations":[]}'
call POST /sales "$(jq -nc --argjson p "$TST" --argjson m "$PM_CASH" '{items:[{productId:$p, factor:1, qty:1, serial:null}], freeSerials:[],
  payments:[{methodId:$m, amount:300, reference:""}], customer:"", expectedTotal:300}')"
expect_status 200
SALE=$(jq -r .id <<<"$BODY")
terminal -
view() { # → ค่าทุนที่ cash1 เห็นใน สินค้า / บัตรสต็อก / branch-stock / บิล
  call GET "/products/$TST"; local p="$(num .cost) $(num .avgCost) $(num .stock)"
  call GET "/products/$TST/movements?from=$today&to=$today"
  local c="$(jq -r '[.opening.avgCost, .opening.value, .closing.value, .totals.inCost, .totals.outCost, (.movements[0] | .unitCost, .totalCost, .balanceAvgCost, .balanceValue)]
    | map(if type == "number" then (. + 0 | tostring) else tostring end) | join(",")' <<<"$BODY") qty=$(num .closing.qty)"
  call GET "/products/$TST/branch-stock"; local b="$(jq -r '[.[] | (if .avgCost == null then "null" else (.avgCost + 0 | tostring) end) + "/" + (.stock | tostring)] | join(",")' <<<"$BODY")"
  call GET "/sales/$SALE"; local s="$(jq -r '[.lines[0].cogs] | map(if type == "number" then (. + 0 | tostring) else tostring end) | join(",")' <<<"$BODY")"
  echo "สินค้า[$p] บัตร[$c] สาขา[$b] บิล[$s]"
}
check "staff มองไม่เห็นทุน (จำนวนยังเห็น)" \
  "สินค้า[null null 19] บัตร[null,null,null,null,null,null,null,null,null qty=19] สาขา[null/19,null/0] บิล[null]" "$(view)"
as "$T_BOSS"
call PUT /settings/company "$(jq -nc --arg n "บริษัท ทดสอบ $CODE" '{name:$n, taxId:"0105550123451", vatRegistered:true, taxInvoicePaper:"A4", exchangeDays:7,
  manualDiscountMaxPercent:5, requireShift:true, shiftVarianceLimit:100, shiftMaxHours:12, staffSeeCost:true}')"
expect_status 200
as "$T_CASH1"
check "staffSeeCost=true → staff เห็นทุน" \
  "สินค้า[100 130 19] บัตร[200,2000,2470,1430,960,80,800,140,2800 qty=19] สาขา[130/19,0/0] บิล[130]" "$(view)"
as "$T_BOSS"
call PUT /settings/company "$(jq -nc --arg n "บริษัท ทดสอบ $CODE" '{name:$n, taxId:"0105550123451", vatRegistered:true, taxInvoicePaper:"A4", exchangeDays:7,
  manualDiscountMaxPercent:5, requireShift:true, shiftVarianceLimit:100, shiftMaxHours:12, staffSeeCost:false}')"
expect_status 200

case_id 13.8 "staff ส่ง cost ใน PUT (E44)"
as "$T_CASH1"; branch "$BR_HQ"
call PUT "/products/$TST" '{"name":"แก้โดย staff","cost":999}'
expect_status 200
expect_json .cost null
expect_sql "SELECT name || ':' || cost FROM dt_products WHERE id = $TST" "แก้โดย staff:100.0000"
as -; branch -

finish
