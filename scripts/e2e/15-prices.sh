#!/usr/bin/env bash
# ข้อ 15 — ราคาขาย (docs/e2e-test-plan.md)
E2E_STEP=15
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

today=$(TZ=Asia/Bangkok date +%F)
day() { TZ=Asia/Bangkok date -d "$today $1 day" +%F; }
yesterday=$(day -1)
thai() { local m=(ม.ค. ก.พ. มี.ค. เม.ย. พ.ค. มิ.ย. ก.ค. ส.ค. ก.ย. ต.ค. พ.ย. ธ.ค.); echo "$(date -d "$1" +%-d) ${m[$(( $(date -d "$1" +%-m) - 1 ))]} $(( $(date -d "$1" +%Y) + 543 ))"; }
std_company E15
login "$CODE" boss pass1234; T_BOSS=$TOKEN
login "$CODE" cash1 pass1234; T_CASH1=$TOKEN
price() { jq -nc --argjson p "$1" --argjson v "$2" --arg s "$3" --arg e "${4:-}" '{productId:$p, price:$v, startDate:$s, endDate:(if $e == "" then null else $e end), note:"e2e"}'; }
price_id() { sql "SELECT id FROM dt_sku_prices WHERE tenant_id = $TENANT AND product_id = $1 AND start_date = '$2'"; }
current() { branch "$BR_HQ"; call GET "/products/$1"; branch -; jq -r '.currentPrice + 0 | tostring' <<<"$BODY"; }
audit_last() { sql "SELECT action || ':' || user_id || ':' || ref_label || ':' || detail FROM dt_audit_log WHERE tenant_id = $TENANT AND action = 'price_change' ORDER BY id DESC LIMIT 1"; }
as "$T_BOSS"
MS_OLD=$(price_id "$P_MS" 2020-01-01)

case_id 15.1 "ช่วงทับ"
call POST /prices "$(price "$P_MS" 390 "$today")"
expect_status 400
expect_msg "ช่วงวันที่ทับกับราคา ฿350 (1 ม.ค. 2563 – ไม่กำหนด)"
call POST /prices "$(price "$P_MS" 390 2019-06-01 2019-12-31)"
expect_status 200   # ก่อนช่วงเดิมได้
call POST /prices "$(price "$P_MS" 390 2019-11-01 2019-11-30)"
expect_status 400
expect_msg "ช่วงวันที่ทับกับราคา ฿390 (1 มิ.ย. 2562 – 31 ธ.ค. 2562)"

case_id 15.2 "ปิดช่วงเดิมเมื่อวาน แล้วเพิ่ม ฿390 วันนี้"
call PUT "/prices/$MS_OLD" "$(price "$P_MS" 350 2020-01-01 "$yesterday")"
expect_status 200
check "audit แก้ช่วงราคา" "price_change:$U_BOSS:MS-001 ฿350:แก้ช่วงราคา 2020-01-01 – $yesterday" "$(audit_last)"
call @api2 POST /prices "$(price "$P_MS" 390 "$today")"
expect_status 200
check "audit เพิ่มช่วงราคา" "price_change:$U_BOSS:MS-001 ฿390:เพิ่มช่วงราคา $today – ไม่มีกำหนด" "$(audit_last)"
check "currentPrice MS" 390 "$(current "$P_MS")"
expect_sql "SELECT string_agg(price || ':' || start_date || ':' || coalesce(end_date::text, '-'), ',' ORDER BY start_date) FROM dt_sku_prices WHERE tenant_id = $TENANT AND product_id = $P_MS" \
  "390.00:2019-06-01:2019-12-31,350.00:2020-01-01:$yesterday,390.00:$today:-"
call GET "/prices?productId=$P_MS"
expect_json 'length' 3

case_id 15.3 "ค่าผิด"
count=$(sql "SELECT count(*) FROM dt_sku_prices WHERE tenant_id = $TENANT")
while IFS='|' read -r body msg; do
  call POST /prices "$body"
  expect_status 400
  expect_msg "$msg"
done <<EOF
$(price "$P_CBL" -1 2030-01-01)|ราคาต้องไม่ติดลบ
$(price "$P_CBL" 1.234 2030-01-01)|ราคาทศนิยมไม่เกิน 2 ตำแหน่ง
$(price "$P_CBL" null 2030-01-01)|กรุณากรอกราคา
$(price "$P_CBL" 10 "")|กรุณาระบุวันที่เริ่ม
$(price "$P_CBL" 10 2030/01/01)|รูปแบบวันที่ไม่ถูกต้อง
$(price "$P_CBL" 10 2030-02-30)|รูปแบบวันที่ไม่ถูกต้อง
$(price "$P_CBL" 10 2030-01-01 2030-1-5)|รูปแบบวันที่ไม่ถูกต้อง
$(price "$P_CBL" 10 2030-01-10 2030-01-09)|วันที่สิ้นสุดต้องไม่ก่อนวันที่เริ่ม
$(price 99999999 10 2030-01-01)|ไม่พบ SKU
$(price "$P_CBL" 10 2030-01-01 | jq -c 'del(.productId)')|ไม่พบ SKU
EOF
call PUT "/prices/$MS_OLD" "$(price "$P_MS" 350 2020-01-01)"
expect_status 400
expect_msg "ช่วงวันที่ทับกับราคา ฿390 ($(thai "$today") – ไม่กำหนด)"
call PUT /prices/99999999 "$(price "$P_MS" 350 2020-01-01)"
expect_status 404
expect_msg "ไม่พบช่วงราคา"
as "$T_CASH1"
call POST /prices "$(price "$P_CBL" 10 2030-01-01)"
expect_status 403
expect_msg "แก้ไขราคาขายได้เฉพาะผู้ดูแลระบบ"
call PUT "/prices/$MS_OLD" "$(price "$P_MS" 1 2020-01-01 "$yesterday")"
expect_status 403
call DELETE "/prices/$MS_OLD"
expect_status 403
call GET /prices
expect_status 200   # อ่านได้ทุกคน
as "$T_BOSS"
expect_sql "SELECT count(*) FROM dt_sku_prices WHERE tenant_id = $TENANT" "$count"

case_id 15.4 "insert ช่วงทับตรง ๆ ด้วย SQL (ตาข่ายสุดท้าย)"
out=$(sql "INSERT INTO dt_sku_prices (tenant_id, product_id, price, start_date, end_date, note) VALUES ($TENANT, $P_MS, 1, '$today', NULL, 'ทับ')" 2>&1 || true)
check "ERROR exclusion constraint dt_sku_prices_no_overlap" yes \
  "$(grep -q 'conflicting key value violates exclusion constraint "dt_sku_prices_no_overlap"' <<<"$out" && echo yes || echo "no: $out")"
out=$(as_app "$TENANT" "INSERT INTO dt_sku_prices (tenant_id, product_id, price, start_date, end_date, note) VALUES ($TENANT, $P_MS, 1, '2019-07-01', '2019-07-31', 'ทับ')" || true)
check "an22_app ก็ชน constraint เดียวกัน" yes "$(grep -q 'dt_sku_prices_no_overlap' <<<"$out" && echo yes || echo "no: $out")"
expect_sql "SELECT count(*) FROM dt_sku_prices WHERE tenant_id = $TENANT AND note = 'ทับ'" 0

case_id 15.5 "ราคาในอนาคต / ลบช่วง"
EXM_OLD=$(price_id "$P_EXM" 2020-01-01)
call PUT "/prices/$EXM_OLD" "$(price "$P_EXM" 120 2020-01-01 "$(day 9)")"
expect_status 200
call POST /prices "$(price "$P_EXM" 150 "$(day 10)")"
expect_status 200
FUTURE=$(jq -r .id <<<"$BODY")
check "currentPrice ยังเป็นราคาวันนี้" 120 "$(current "$P_EXM")"
call DELETE "/prices/$FUTURE"
expect_status 200
check "audit ลบช่วงราคา" "price_change:$U_BOSS:EXM-001 ฿150 ตั้งแต่ $(day 10):ลบช่วงราคา" "$(audit_last)"
expect_sql "SELECT count(*) FROM dt_sku_prices WHERE id = $FUTURE" 0
call @api2 DELETE "/prices/$FUTURE"
expect_status 404
expect_msg "ไม่พบช่วงราคา"
call PUT "/prices/$EXM_OLD" "$(price "$P_EXM" 120 2020-01-01 "$yesterday")"
expect_status 200
check "ไม่มีช่วงที่ใช้วันนี้ → currentPrice null" null "$(branch "$BR_HQ"; call GET "/products/$P_EXM"; jq -r '.currentPrice | tostring' <<<"$BODY")"
as -

finish
