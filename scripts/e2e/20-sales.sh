#!/usr/bin/env bash
# ข้อ 20 — ขาย: ราคา / VAT / ปัดเศษ / การชำระ (ไม่มีโปร) (docs/e2e-test-plan.md)
E2E_STEP=20
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

today=$(TZ=Asia/Bangkok date +%F)
ymd=$(TZ=Asia/Bangkok date +%Y%m%d)
std_company E20
login "$CODE" boss pass1234; T_BOSS=$TOKEN
login "$CODE" cash1 pass1234; T_CASH1=$TOKEN
num() { jq -r "$1 | if type == \"number\" then (. + 0 | tostring) else tostring end" <<<"$BODY"; }
at() { as "$1"; branch "$2"; terminal "${3:--}"; }
item() { jq -nc --argjson p "$1" --argjson q "${2:-1}" --argjson f "${3:-1}" --arg s "${4:-}" '{productId:$p, factor:$f, qty:$q, serial:(if $s == "" then null else $s end)}'; }
pay() { jq -nc --argjson m "$1" --argjson a "$2" --arg r "${3:-}" '{methodId:$m, amount:$a, reference:$r}'; }
sale() { # items total payments
  call POST /sales "$(jq -nc --argjson i "[$1]" --argjson t "$2" --argjson p "[$3]" '{items:$i, freeSerials:[], payments:$p, customer:"", expectedTotal:$t}')"
}
stock() { sql "SELECT stock FROM dt_branch_stock WHERE tenant_id = $TENANT AND branch_id = $BR_HQ AND product_id = $1"; }
sale_row() { sql "SELECT order_no || ':' || total || ':' || vat || ':' || rounding || ':' || change || ':' || (shift_id = $SHIFT) FROM dt_sales WHERE id = $1"; }
pays() { sql "SELECT string_agg(name || ':' || amount || '/' || tendered || ':' || reference, ',' ORDER BY position) FROM dt_sale_payments WHERE tenant_id = $TENANT AND sale_id = $1"; }
counter() { sql "SELECT count(*) || ':' || (SELECT coalesce(max(last_no), 0) FROM dt_doc_counters WHERE tenant_id = $TENANT AND prefix LIKE 'POS-%') FROM dt_sales WHERE tenant_id = $TENANT"; }
no() { echo "POS-HQ-1-$ymd-$(printf %04d "$1")"; }

at "$T_CASH1" "$BR_HQ" 1
call POST /shifts/open '{"openingFloat":1000,"openingDenominations":[]}'
SHIFT=$(jq -r .id <<<"$BODY")

case_id 20.1 "MS ×2 เงินสดรับ 1,000"
sale "$(item "$P_MS" 2)" 700 "$(pay "$PM_CASH" 1000)"
expect_status 200
S1=$(jq -r .id <<<"$BODY")
check "เลข / total / vat / change" "$(no 1) 700 45.79 300" "$(num .orderNo) $(num .total) $(num .vat) $(num .change)"
check "dt_sales" "$(no 1):700.00:45.79:0.00:300.00:true" "$(sale_row "$S1")"
check "payment" "เงินสด:700.00/1000.00:" "$(pays "$S1")"
expect_sql "SELECT qty || ':' || unit_price || ':' || amount || ':' || vat || ':' || cogs FROM dt_sale_lines WHERE tenant_id = $TENANT AND sale_id = $S1" "2:350.00:700.00:45.79:400.00"
check "HQ MS 20→18" 18 "$(stock "$P_MS")"
expect_sql "SELECT type || ':' || qty || ':' || total_cost || ':' || note FROM dt_stock_movements WHERE tenant_id = $TENANT AND product_id = $P_MS ORDER BY id DESC LIMIT 1" \
  "issue:-2:-400.00:ขาย $(no 1)"

case_id 20.2 "SVC เงินสดรับ 500 (ปัด 0.25)"
moves=$(sql "SELECT count(*) FROM dt_stock_movements WHERE tenant_id = $TENANT AND product_id = $P_SVC")
call @api2 POST /sales "$(jq -nc --argjson i "[$(item "$P_SVC")]" --argjson p "[$(pay "$PM_CASH" 500)]" '{items:$i, freeSerials:[], payments:$p, customer:"", expectedTotal:499.9}')"
expect_status 200
S2=$(jq -r .id <<<"$BODY")
check "total / vat / rounding / change" "499.9 32.7 0.1 0" "$(num .total) $(num .vat) $(num .rounding) $(num .change)"
check "payment amount 500 (total + rounding)" "เงินสด:500.00/500.00:" "$(pays "$S2")"
expect_sql "SELECT cogs || ':' || item_type FROM dt_sale_lines WHERE tenant_id = $TENANT AND sale_id = $S2" "100.00:service"
expect_sql "SELECT count(*) FROM dt_stock_movements WHERE tenant_id = $TENANT AND product_id = $P_SVC" "$moves"

case_id 20.3 "NB0001 บัตร 25,000 ref A12345"
sale "$(item "$P_NB" 1 1 NB0001)" 25000 "$(pay "$PM_CARD" 25000 A12345)"
expect_status 200
S3=$(jq -r .id <<<"$BODY")
check "total / vat" "25000 1635.51" "$(num .total) $(num .vat)"
check "payment" "บัตรเครดิต:25000.00/25000.00:A12345" "$(pays "$S3")"
expect_sql "SELECT serial || ':' || cogs FROM dt_sale_lines WHERE tenant_id = $TENANT AND sale_id = $S3" "NB0001:20000.00"
expect_sql "SELECT status FROM dt_serial_numbers WHERE tenant_id = $TENANT AND serial = 'NB0001'" sold
check "HQ NB 5→4" 4 "$(stock "$P_NB")"
expect_sql "SELECT array_to_string(serials, ',') FROM dt_stock_movements WHERE tenant_id = $TENANT AND product_id = $P_NB ORDER BY id DESC LIMIT 1" NB0001

case_id 20.4 "CBL 2 กล่อง (×10) QR 1,980"
sale "$(item "$P_CBL" 2 10)" 1980 "$(pay "$PM_QR" 1980)"
expect_status 200
S4=$(jq -r .id <<<"$BODY")
check "total / vat / unit" "1980 129.53 กล่อง" "$(num .total) $(num .vat) $(num '.lines[0].unit')"
expect_sql "SELECT qty || ':' || factor || ':' || unit || ':' || unit_price || ':' || cogs FROM dt_sale_lines WHERE tenant_id = $TENANT AND sale_id = $S4" "2:10:กล่อง:990.00:800.00"
check "HQ CBL 100→80" 80 "$(stock "$P_CBL")"

case_id 20.5 "EXM + MS เงินสด 470"
sale "$(item "$P_EXM"), $(item "$P_MS")" 470 "$(pay "$PM_CASH" 470)"
expect_status 200
S5=$(jq -r .id <<<"$BODY")
check "total / vat" "470 22.9" "$(num .total) $(num .vat)"
expect_sql "SELECT string_agg(sku || ':' || vat_type || ':' || vat, ',' ORDER BY line_index) FROM dt_sale_lines WHERE tenant_id = $TENANT AND sale_id = $S5" \
  "EXM-001:exempt:0.00,MS-001:vat7:22.90"

case_id 20.6 "MS ×2: บัตร 500 + เงินสด 500"
sale "$(item "$P_MS" 2)" 700 "$(pay "$PM_CARD" 500 B2222), $(pay "$PM_CASH" 500)"
expect_status 200
S6=$(jq -r .id <<<"$BODY")
check "change" 300 "$(num .change)"
check "payments" "บัตรเครดิต:500.00/500.00:B2222,เงินสด:200.00/500.00:" "$(pays "$S6")"

case_id 20.7 "ยอดไม่ตรง"
before="$(counter) $(stock "$P_MS")"
sale "$(item "$P_MS" 2)" 699 "$(pay "$PM_CASH" 700)"
expect_status 400
expect_msg "ราคาหรือโปรโมชั่นมีการเปลี่ยนแปลง กรุณาตรวจสอบบิลอีกครั้ง"
check "ไม่มีบิล / เลข / สต็อกไม่เปลี่ยน" "$before" "$(counter) $(stock "$P_MS")"

case_id 20.8 "ชำระผิด"
as "$T_BOSS"; call PUT "/payment-methods/$PM_TRANSFER" '{"code":"TRANSFER","name":"โอนเงิน","type":"transfer","active":false}'; as "$T_CASH1"
before="$(counter)"
while IFS='|' read -r pays_json msg; do
  sale "$(item "$P_MS")" 350 "$pays_json"
  expect_status 400
  expect_msg "$msg"
done <<EOF
$(pay "$PM_CARD" 350 "4111 1111 1111 1111")|บัตรเครดิต: ห้ามกรอกเลขบัตรเต็ม — กรอกเฉพาะเลขอนุมัติหรือเลขบัตร 4 หลักท้าย
$(pay "$PM_CARD" 350 "4111-1111-1111-1111")|บัตรเครดิต: ห้ามกรอกเลขบัตรเต็ม — กรอกเฉพาะเลขอนุมัติหรือเลขบัตร 4 หลักท้าย
$(pay "$PM_CARD" 350 " ")|บัตรเครดิต: กรุณากรอกเลขอนุมัติ
$(pay "$PM_CARD" 200 A1), $(pay "$PM_CASH" 150)|บัตรเครดิต: ขั้นต่ำ ฿300 ต่อรายการ
$(pay "$PM_CASH" 250)|ยอดชำระยังไม่ครบ (ขาด ฿100)
$(pay "$PM_QR" 400)|ยอดชำระที่ไม่ใช่เงินสดเกินยอดบิล (ทอนได้เฉพาะเงินสด)
$(pay "$PM_CASH" 200), $(pay "$PM_CASH" 150)|รับเงินสดได้บรรทัดเดียว
$(pay "$PM_TRANSFER" 350)|โอนเงิน ปิดใช้งาน
$(pay 99999999 350)|ไม่พบช่องทางชำระเงิน #99999999
$(pay "$PM_CASH" -350)|เงินสด: จำนวนเงินไม่ถูกต้อง
$(pay "$PM_QR" 0), $(pay "$PM_CASH" 350)|QR พร้อมเพย์: จำนวนเงินต้องมากกว่า 0
$(pay "$PM_CASH" 0)|ยอดชำระยังไม่ครบ (ขาด ฿350)
EOF
call POST /sales "$(jq -nc --argjson i "[$(item "$P_MS")]" '{items:$i, freeSerials:[], payments:[], customer:"", expectedTotal:350}')"
expect_status 400
expect_msg "กรุณาเลือกช่องทางชำระเงิน"
check "ไม่มีบิลใหม่" "$before" "$(counter)"
sale "$(item "$P_MS")" 350 "$(pay "$PM_CARD" 350 1234567812345678)"
expect_status 200   # 16 หลักที่ไม่ผ่าน Luhn = เลขอนุมัติได้
S8=$(jq -r .id <<<"$BODY")

case_id 20.9 "ตะกร้าผิด"
as "$T_BOSS"
call POST /products "{\"sku\":\"NOP-001\",\"name\":\"ไม่มีราคา\",\"categoryId\":$CAT_CBL,\"unit\":\"ชิ้น\",\"cost\":1}"; NOP=$(jq -r .id <<<"$BODY")
call PUT "/products/$NOP/stock" '{"delta":5,"unitCost":1,"note":""}'
call POST /products "{\"sku\":\"NOS-001\",\"name\":\"ห้ามขาย\",\"categoryId\":$CAT_CBL,\"unit\":\"ชิ้น\",\"cost\":1,\"saleStatus\":\"no_sale\"}"; NOS=$(jq -r .id <<<"$BODY")
call POST /prices "{\"productId\":$NOS,\"price\":10,\"startDate\":\"2020-01-01\",\"endDate\":null,\"note\":\"\"}"
as "$T_CASH1"
before="$(counter)"
while IFS='|' read -r items msg; do
  call POST /sales "$(jq -nc --argjson i "[$items]" --argjson p "[$(pay "$PM_CASH" 100000)]" '{items:$i, freeSerials:[], payments:$p, customer:"", expectedTotal:1}')"
  expect_status 400
  expect_msg "$msg"
done <<EOF
|ยังไม่มีสินค้าในบิล
$(item "$P_MS" 1.5)|MS-001 จำนวนต้องเป็นจำนวนเต็ม ≥ 1
$(item "$P_MS" 0)|MS-001 จำนวนต้องเป็นจำนวนเต็ม ≥ 1
$(item "$P_NB")|NB-001 ต้องระบุซีเรียล
$(item "$P_NB" 2 1 NB0002)|NB-001 ขายทีละ 1 ซีเรียล
$(item "$P_NB" 1 1 NB0002), $(item "$P_NB" 1 1 NB0002)|ซีเรียล NB0002 ซ้ำในบิล
$(item "$NOP")|NOP-001 ยังไม่กำหนดราคาขาย
$(item "$NOS")|NOS-001 ห้ามขาย
$(item "$P_MS" 1 10)|MS-001 ไม่มีหน่วยขายนี้
EOF
sale "$(item "$P_NB" 1 1 NB0001)" 25000 "$(pay "$PM_CASH" 25000)"
expect_status 400
expect_msg "ซีเรียล NB0001 ไม่อยู่ในคลังของ NB-001"
check "ไม่มีบิลใหม่" "$before" "$(counter)"

case_id 20.10 "EXM เกินสต็อก → rollback ทั้งบิล"
exm=$(stock "$P_EXM")
before="$(counter)"
sale "$(item "$P_MS"), $(item "$P_EXM" $((exm + 1)))" "$(( 350 + (exm + 1) * 120 ))" "$(pay "$PM_CASH" 100000)"
expect_status 400
expect_msg "EXM-001 สต็อกไม่พอ (คงเหลือ $exm เล่ม)"   # ตะกร้าของ mock (pos-pricing) ตรวจก่อน — "สต็อกคงเหลือไม่พอ" ของ StockLedger เกิดตอนแย่งกัน (ข้อ 34.2)
check "เลข / บิล / สต็อก MS ไม่เปลี่ยน (rollback)" "$before $(stock "$P_EXM")" "$(counter) $exm"
sale "$(item "$P_MS")" 350 "$(pay "$PM_CASH" 350)"
expect_status 200
N=$(sql "SELECT count(*) FROM dt_sales WHERE tenant_id = $TENANT")
expect_json .orderNo "$(no "$N")"
expect_sql "SELECT string_agg(right(order_no, 4), ',' ORDER BY order_no) FROM dt_sales WHERE tenant_id = $TENANT" "$(seq -f %04g -s, 1 "$N")"

case_id 20.11 "ไม่มี X-Branch-Id / เครื่องของสาขาอื่น"
branch -
sale "$(item "$P_MS")" 350 "$(pay "$PM_CASH" 350)"
expect_status 400
expect_msg "ไม่ได้เลือกสาขา กรุณาเลือกสาขาที่ทำงานก่อน"
at "$T_BOSS" "$BR_HQ" 3
sale "$(item "$P_MS")" 350 "$(pay "$PM_CASH" 350)"
expect_status 400
expect_msg "เครื่อง 3 เป็นเครื่องของสาขา BN · สาขาบางนา — สลับไปสาขานั้น หรือให้ผู้ดูแลตั้งเครื่องนี้ใหม่"

case_id 20.12 "GET /sales"
at "$T_CASH1" "$BR_HQ" 1
call GET "/sales?from=$today&to=$today"
expect_status 200
check "ใหม่สุดก่อน + ครบทุกบิล" "$(sql "SELECT string_agg(id::text, ',' ORDER BY sold_at DESC, id DESC) FROM dt_sales WHERE tenant_id = $TENANT")" \
  "$(jq -r '[.[].id] | map(tostring) | join(",")' <<<"$BODY")"
call @api2 GET "/sales?shiftId=$SHIFT"
check "?shiftId= (ไม่ต้องมีช่วง)" "$N" "$(num length)"
call GET "/sales?from=$today&to=$today&branchId=all"
expect_status 403
expect_msg "ดูทุกสาขาได้เฉพาะผู้ดูแลระบบระดับบริษัท"
call GET /sales
expect_status 400
expect_msg "กรุณาเลือกช่วงวันที่ (ไม่เกิน 366 วัน)"
at "$T_BOSS" "$BR_BN"
call GET "/sales?from=$today&to=$today&branchId=all"
check "boss ?branchId=all" "200 $N" "$STATUS $(num length)"
call GET "/sales?from=$today&to=$today"
check "boss ที่ BN (สาขาของ request) ไม่มีบิล" "200 0" "$STATUS $(num length)"
call GET "/sales/$S1"
expect_json '[.orderNo, .cashier, (.payments[0].tendered + 0 | tostring)] | join(" ")' "$(no 1) แคชเชียร์หนึ่ง 1000"
as -; branch -; terminal -

finish
