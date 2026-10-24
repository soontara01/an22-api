#!/usr/bin/env bash
# ข้อ 16 — โปรโมชั่น (+ ยอดบิลที่ใช้โปร) (docs/e2e-test-plan.md)
E2E_STEP=16
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

today=$(TZ=Asia/Bangkok date +%F)
day() { TZ=Asia/Bangkok date -d "$today $1 day" +%F; }
std_company E16
login "$CODE" boss pass1234; T_BOSS=$TOKEN
login "$CODE" mgr pass1234; T_MGR=$TOKEN
login "$CODE" cash1 pass1234; T_CASH1=$TOKEN
num() { jq -r "$1 | if type == \"number\" then (. + 0 | tostring) else tostring end" <<<"$BODY"; }
promo() { # code type [jq แก้] — ค่าเริ่ม: ลด 10% SKU MS เริ่มวันนี้
  jq -nc --arg c "$1" --arg t "$2" --arg s "$today" --argjson ms "$P_MS" '{code:$c, name:("โปร " + $c), note:"", type:$t, startDate:$s, endDate:null,
    enabled:true, priority:0, stackable:false, scope:{all:false, productIds:[$ms], categoryIds:[]}, minQty:1, minAmount:0,
    discount:{kind:"percent", value:10, maxDiscount:null}, freeGoods:null, branchIds:[]}' | jq -c "${3:-.}"
}
sale_body() { # items_json expectedTotal payments_json
  jq -nc --argjson items "$1" --argjson t "$2" --argjson pay "$3" '{items:$items, freeSerials:[], payments:$pay, customer:"", expectedTotal:$t}'
}
cash() { jq -nc --argjson m "$PM_CASH" --argjson a "$1" '[{methodId:$m, amount:$a, reference:""}]'; }

case_id 16.1 "สร้างโปร 3 ชนิด"
as "$T_BOSS"
call POST /promotions "$(promo pro-ms10 item_discount ".scope = {all:false, productIds:[], categoryIds:[$CAT_MOUSE]} | .startDate = \"$(day -5)\" | .endDate = \"$(day 30)\"")"
expect_status 200
MS10=$(jq -r .id <<<"$BODY")
expect_json '[.code, .type, (.scope.categoryIds | map(tostring) | join(",")), .discount.kind, (.discount.value + 0 | tostring)] | join(" ")' "PRO-MS10 item_discount $CAT_MOUSE percent 10"
call POST /promotions "$(promo PRO-BILL300 bill_discount '.minAmount = 5000 | .discount = {kind:"amount", value:300, maxDiscount:null}')"
expect_status 200
BILL=$(jq -r .id <<<"$BODY")
expect_json '.scope.all' true   # bill_discount ใช้ทั้งบิล
call @api2 POST /promotions "$(promo PRO-GIFT free_goods ".scope.productIds = [$P_NB] | .discount = null | .freeGoods = {items:[{productId:$P_CBL, qty:1}], repeat:false, maxSets:null}")"
expect_status 200
GIFT=$(jq -r .id <<<"$BODY")
expect_sql "SELECT string_agg(code || ':' || type || ':' || coalesce((discount->>'kind') || '/' || trim_scale((discount->>'value')::numeric) || '/' || coalesce(discount->>'maxDiscount', '-'), '-')
  || ':' || coalesce((free_goods->'items'->0->>'productId') || 'x' || trim_scale((free_goods->'items'->0->>'qty')::numeric) || '/' || (free_goods->>'repeat'), '-'), ' | ' ORDER BY id) FROM dt_promotions WHERE tenant_id = $TENANT" \
  "PRO-MS10:item_discount:percent/10/-:- | PRO-BILL300:bill_discount:amount/300/-:- | PRO-GIFT:free_goods:-:${P_CBL}x1/false"
expect_sql "SELECT count(*) || ':' || min(user_id) || ':' || max(user_id) FROM dt_audit_log WHERE tenant_id = $TENANT AND action = 'promotion_change'" "3:$U_BOSS:$U_BOSS"
echo "     audit: $(sql "SELECT ref_label || ' / ' || detail FROM dt_audit_log WHERE tenant_id = $TENANT AND action = 'promotion_change' ORDER BY id LIMIT 1")"

case_id 16.2 "ค่าผิด"
count=$(sql "SELECT count(*) FROM dt_promotions WHERE tenant_id = $TENANT")
while IFS='|' read -r body msg; do
  call POST /promotions "$body"
  expect_status 400
  expect_msg "$msg"
done <<EOF
$(promo "a b" item_discount)|รหัสโปรใช้ได้เฉพาะ A-Z, 0-9 และ - (ไม่เกิน 30 ตัว)
$(promo pro-ms10 item_discount)|รหัสโปร PRO-MS10 ซ้ำ
$(promo PRO-X1 item_discount '.name = " "')|กรุณากรอกชื่อโปรโมชั่น
$(promo PRO-X2 item_discount '.startDate = "2026/01/01"')|รูปแบบวันที่ไม่ถูกต้อง
$(promo PRO-X3 item_discount ".endDate = \"$(day -1)\"")|วันที่สิ้นสุดต้องไม่ก่อนวันที่เริ่ม
$(promo PRO-X4 item_discount '.priority = 1000')|ลำดับความสำคัญต้องเป็นจำนวนเต็ม 0 - 999
$(promo PRO-X5 item_discount '.scope = {all:false, productIds:[], categoryIds:[]}')|กรุณาเลือกสินค้าหรือหมวดหมู่ที่ร่วมรายการ
$(promo PRO-X6 item_discount '.scope.productIds = [99999999]')|ไม่พบ SKU #99999999
$(promo PRO-X7 item_discount '.scope = {all:false, productIds:[], categoryIds:[99999999]}')|ไม่พบหมวดหมู่ #99999999
$(promo PRO-X8 item_discount '.discount.value = 0')|ส่วนลด % ต้องมากกว่า 0 และไม่เกิน 100
$(promo PRO-X9 item_discount '.discount.value = 100.5')|ส่วนลด % ต้องมากกว่า 0 และไม่เกิน 100
$(promo PRO-X10 item_discount '.discount = {kind:"amount", value:0, maxDiscount:null}')|ส่วนลดต้องมากกว่า 0 บาท
$(promo PRO-X12 free_goods ".freeGoods = {items:[{productId:$P_CBL, qty:1}], repeat:false, maxSets:null}")|โปรของแถมต้องไม่มีส่วนลด
$(promo PRO-X13 free_goods '.discount = null | .freeGoods = {items:[], repeat:false, maxSets:null}')|กรุณาเลือกสินค้าแถมอย่างน้อย 1 รายการ
$(promo PRO-X14 free_goods '.discount = null | .freeGoods = {items:[{productId:99999999, qty:1}], repeat:false, maxSets:null}')|ไม่พบสินค้าแถม #99999999
$(promo PRO-X15 free_goods ".discount = null | .freeGoods = {items:[{productId:$P_CBL, qty:0}], repeat:false, maxSets:null}")|จำนวนของแถมต้องเป็นจำนวนเต็ม ≥ 1
$(promo PRO-X16 item_discount ".freeGoods = {items:[{productId:$P_CBL, qty:1}], repeat:false, maxSets:null}")|โปรส่วนลดต้องไม่มีของแถม
$(promo PRO-X17 item_discount ".branchIds = [$BR_HQ, $BR_HQ]")|เลือกสาขาซ้ำกัน
$(promo PRO-X18 item_discount '.branchIds = [99999999]')|สาขาที่เลือกไม่มีอยู่หรือปิดใช้งานแล้ว
$(promo PRO-X19 weird)|ข้อมูลที่ส่งมาไม่ถูกต้อง
$(promo PRO-X20 item_discount '.discount.kind = "weird"')|ข้อมูลที่ส่งมาไม่ถูกต้อง
EOF
expect_sql "SELECT count(*) FROM dt_promotions WHERE tenant_id = $TENANT" "$count"
# ข้อสังเกต: item_discount ส่ง discount: null → mock 400 "กรุณากำหนดส่วนลด"; api ใช้ส่วนลดค่าเริ่ม (PromotionRules.normalize — ตั้งใจ ตาม comment ในโค้ด)
call POST /promotions "$(promo PRO-NULL item_discount '.discount = null')"
echo "     (สังเกต) discount null → $STATUS $(jq -c '{discount} // .' <<<"$BODY")"
sql "DELETE FROM dt_promotions WHERE tenant_id = $TENANT AND code = 'PRO-NULL'; DELETE FROM dt_audit_log WHERE false" >/dev/null

case_id 16.3 "ขาย MS ×2 (PRO-MS10)"
as "$T_CASH1"; branch "$BR_HQ"; terminal 1
call POST /shifts/open '{"openingFloat":0,"openingDenominations":[]}'
expect_status 200
call POST /sales "$(sale_body "[{\"productId\":$P_MS,\"factor\":1,\"qty\":2,\"serial\":null}]" 700 "$(cash 700)")"
expect_status 400
expect_msg "ราคาหรือโปรโมชั่นมีการเปลี่ยนแปลง กรุณาตรวจสอบบิลอีกครั้ง"   # ยอดก่อนโปร
call @api2 POST /sales "$(sale_body "[{\"productId\":$P_MS,\"factor\":1,\"qty\":2,\"serial\":null}]" 630 "$(cash 630)")"
expect_status 200
S1=$(jq -r .id <<<"$BODY")
check "total / itemDiscount / vat" "630 70 41.21" "$(num .total) $(num .itemDiscount) $(num .vat)"
expect_sql "SELECT item_discount || ':' || amount || ':' || array_to_string(promotion_ids, ',') FROM dt_sale_lines WHERE tenant_id = $TENANT AND sale_id = $S1" "70.00:630.00:$MS10"
expect_sql "SELECT total || ':' || item_discount || ':' || vat || ':' || array_to_string(bill_promotion_ids, ',') FROM dt_sales WHERE id = $S1" "630.00:70.00:41.21:"

case_id 16.4 "ขาย NB0001 (ของแถม CBL + ท้ายบิล 300)"
cbl_before=$(sql "SELECT stock FROM dt_branch_stock WHERE tenant_id = $TENANT AND branch_id = $BR_HQ AND product_id = $P_CBL")
call POST /sales "$(sale_body "[{\"productId\":$P_NB,\"factor\":1,\"qty\":1,\"serial\":\"NB0001\"}]" 24700 "$(cash 24700)")"
expect_status 200
S2=$(jq -r .id <<<"$BODY")
check "total / billDiscount / vat" "24700 300 1615.89" "$(num .total) $(num .billDiscount) $(num .vat)"
expect_json "[.lines[] | .sku + \":\" + (.amount + 0 | tostring) + \":\" + ((.freeOfPromotionId // 0) | tostring)] | join(\",\")" "NB-001:24700:0,CBL-001:0:$GIFT"
expect_sql "SELECT string_agg(sku || ':' || qty || ':' || amount || ':' || coalesce(free_of_promotion_id::text, '-') || ':' || cogs, ',' ORDER BY line_index)
  FROM dt_sale_lines WHERE tenant_id = $TENANT AND sale_id = $S2" "NB-001:1:24700.00:-:20000.00,CBL-001:1:0.00:$GIFT:40.00"
expect_sql "SELECT array_to_string(bill_promotion_ids, ',') || ':' || bill_discount FROM dt_sales WHERE id = $S2" "$BILL:300.00"
check "CBL stock −1" "$((cbl_before - 1))" "$(sql "SELECT stock FROM dt_branch_stock WHERE tenant_id = $TENANT AND branch_id = $BR_HQ AND product_id = $P_CBL")"
expect_sql "SELECT status FROM dt_serial_numbers WHERE tenant_id = $TENANT AND serial = 'NB0001'" sold

case_id 16.5 "โปรเฉพาะ BN / เริ่มพรุ่งนี้ / ปิดใช้งาน ไม่คิดที่ HQ วันนี้"
as "$T_BOSS"
for p in "PRO-BN|.scope.productIds = [$P_EXM] | .branchIds = [$BR_BN] | .discount.value = 20" "PRO-TMR|.scope.productIds = [$P_EXM] | .startDate = \"$(day 1)\" | .discount.value = 30" \
  "PRO-OFF|.scope.productIds = [$P_EXM] | .enabled = false | .discount.value = 40"; do
  IFS='|' read -r c edit <<<"$p"
  call POST /promotions "$(promo "$c" item_discount "$edit")"
  expect_status 200
done
TMR=$(sql "SELECT id FROM dt_promotions WHERE tenant_id = $TENANT AND code = 'PRO-TMR'")
as "$T_CASH1"
call POST /sales "$(sale_body "[{\"productId\":$P_EXM,\"factor\":1,\"qty\":1,\"serial\":null}]" 120 "$(cash 120)")"
expect_status 200
check "EXM ไม่มีส่วนลด" "120 0 0" "$(num .total) $(num .itemDiscount) $(num '.lines[0].promotionIds | length')"
call POST /sales/cost-check "$(sale_body "[{\"productId\":$P_EXM,\"factor\":1,\"qty\":1,\"serial\":null}]" 120 "$(cash 120)")"
check "cost-check ไม่มีปัญหา" "200 0" "$STATUS $(num '.issues | length')"

case_id 16.6 "แก้ / ลบโปรที่เริ่มแล้ว"
as "$T_BOSS"
call GET "/promotions/$MS10"
cur=$(jq -c 'del(.id)' <<<"$BODY")
put() { call PUT "/promotions/$MS10" "$(jq -c "$1" <<<"$cur")"; }
while IFS='|' read -r edit msg; do
  put "$edit"
  expect_status 400
  expect_msg "$msg"
done <<EOF
.discount.value = 20|โปรที่เริ่มแล้วแก้ได้เฉพาะวันสิ้นสุด (เลื่อนให้เร็วขึ้น) การเปิด/ปิดใช้งาน และหมายเหตุ
.name = "ชื่อใหม่"|โปรที่เริ่มแล้วแก้ได้เฉพาะวันสิ้นสุด (เลื่อนให้เร็วขึ้น) การเปิด/ปิดใช้งาน และหมายเหตุ
.endDate = "$(day 60)"|โปรที่เริ่มแล้วเลื่อนวันสิ้นสุดให้ช้าลงไม่ได้ (สร้างโปรใหม่แทน)
.endDate = null|โปรที่เริ่มแล้วขยายเป็นไม่กำหนดวันสิ้นสุดไม่ได้
.endDate = "$(day -1)"|วันสิ้นสุดใหม่ต้องไม่ก่อนวันนี้ (ต้องการหยุดทันทีให้ปิดใช้งาน)
EOF
put ".endDate = \"$(day 10)\" | .note = \"เลื่อนจบเร็วขึ้น\""
expect_status 200
put ".endDate = \"$(day 10)\" | .note = \"เลื่อนจบเร็วขึ้น\" | .enabled = false"
expect_status 200
expect_sql "SELECT end_date || ':' || enabled || ':' || note || ':' || trim_scale((discount->>'value')::numeric) FROM dt_promotions WHERE id = $MS10" "$(day 10):false:เลื่อนจบเร็วขึ้น:10"
call DELETE "/promotions/$MS10"
expect_status 400
expect_msg "โปรที่เริ่มแล้วลบไม่ได้ (ปิดใช้งานแทน)"
call @api2 DELETE "/promotions/$TMR"
expect_status 200   # ยังไม่เริ่ม → ลบได้
expect_sql "SELECT detail FROM dt_audit_log WHERE tenant_id = $TENANT AND action = 'promotion_change' ORDER BY id DESC LIMIT 1" "ลบโปรโมชั่น"
call GET /promotions
expect_json '[.[].code] | join(",")' "PRO-MS10,PRO-BILL300,PRO-GIFT,PRO-BN,PRO-OFF"

case_id 16.7 "สิทธิ์"
as "$T_MGR"
call POST /promotions "$(promo PRO-HQ item_discount ".branchIds = [$BR_HQ]")"
expect_status 403
expect_msg "โปรที่จำกัดสาขาสร้าง / แก้ได้เฉพาะผู้ดูแลระบบระดับบริษัท"
BN=$(sql "SELECT id FROM dt_promotions WHERE tenant_id = $TENANT AND code = 'PRO-BN'")
call GET "/promotions/$BN"
call PUT "/promotions/$BN" "$(jq -c 'del(.id) | .note = "mgr"' <<<"$BODY")"
expect_status 403
expect_msg "โปรที่จำกัดสาขาสร้าง / แก้ได้เฉพาะผู้ดูแลระบบระดับบริษัท"
call POST /promotions "$(promo PRO-MGR item_discount ".startDate = \"$(day 5)\"")"
expect_status 200   # admin สาขาสร้างโปรไม่จำกัดสาขาได้
as "$T_CASH1"
call POST /promotions "$(promo PRO-STAFF item_discount)"
expect_status 403
expect_msg "แก้ไขโปรโมชั่นได้เฉพาะผู้ดูแลระบบ"
call DELETE "/promotions/$BN"
expect_status 403
call GET /promotions
expect_status 200   # อ่านได้ทุกคน
as -; branch -; terminal -

finish
