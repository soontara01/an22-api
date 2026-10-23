#!/usr/bin/env bash
# ข้อ 12 — SKU (docs/e2e-test-plan.md)
# case ขอบเขต B2 (ค่าที่ DB มี CHECK แต่กฎไม่ตรวจ) / B3 (ทศนิยมใน Integer) ทำเพิ่มจากแผน — ดูหัวข้อ 7
E2E_STEP=12
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

tomorrow=$(TZ=Asia/Bangkok date -d tomorrow +%F)
std_company E12
login "$CODE" boss pass1234; T_BOSS=$TOKEN
login "$CODE" cash1 pass1234; T_CASH1=$TOKEN
sku() { # sku name categoryId [jq แก้]
  jq -nc --arg s "$1" --arg n "$2" --argjson c "$3" '{sku:$s, name:$n, categoryId:$c, unit:"ชิ้น", cost:10}' | jq -c "${4:-.}"
}
as "$T_BOSS"
call POST /suppliers '{"code":"SUP1","name":"ผู้จำหน่ายหนึ่ง","taxId":"0105550123451","active":true}'; SUP1=$(jq -r .id <<<"$BODY")
call POST /suppliers '{"code":"SUP2","name":"ผู้จำหน่ายสอง","taxId":"0105550123451","branchType":"branch","branchNo":"00001","active":true}'; SUP2=$(jq -r .id <<<"$BODY")
call POST /categories "{\"code\":\"OFF\",\"name\":\"ปิดใช้งาน\",\"parentId\":$CAT_IT,\"active\":false}"; CAT_OFF=$(jq -r .id <<<"$BODY")
branch "$BR_HQ"
call PUT "/products/$P_MS" '{"barcode":"8850000000011"}'
[[ "$STATUS" == 200 ]] || fail "ตั้งบาร์โค้ด MS → $STATUS"
products=$(sql "SELECT count(*) FROM dt_products WHERE tenant_id = $TENANT")

case_id 12.1 "สร้าง: สาขา / SKU ซ้ำ / หมวด"
branch -
call POST /products "$(sku TST-001 "ทดสอบ" "$CAT_MOUSE")"
expect_status 400
expect_msg "ไม่ได้เลือกสาขา กรุณาเลือกสาขาที่ทำงานก่อน"
branch "$BR_HQ"
while IFS='|' read -r body msg; do
  call POST /products "$body"
  expect_status 400
  expect_msg "$msg"
done <<EOF
$(sku TST-001 "ทดสอบ" "$CAT_MOUSE" '.itemType = "bundle"')|ประเภทสินค้าไม่ถูกต้อง
$(sku ms-001 "ซ้ำ" "$CAT_MOUSE")|รหัส SKU ms-001 ซ้ำ
$(sku TST-001 "ทดสอบ" null)|กรุณาเลือกหมวดหมู่
$(sku TST-001 "ทดสอบ" 99999999)|กรุณาเลือกหมวดหมู่
$(sku TST-001 "ทดสอบ" "$CAT_IT")|ต้องเลือกหมวดหมู่ย่อยสุดที่เปิดใช้งาน
$(sku TST-001 "ทดสอบ" "$CAT_OFF")|ต้องเลือกหมวดหมู่ย่อยสุดที่เปิดใช้งาน
$(sku TST-001 "ทดสอบ" "$CAT_MOUSE" '.shortName = "AAAAAAAAAAAAAAAAAAAAA"')|ชื่อย่อต้องไม่เกิน 20 ตัวอักษร
EOF

case_id 12.2 "หน่วยแพ็ก / บาร์โค้ด / min-max / ผู้จำหน่าย"
pack='{"unit":"กล่อง","factor":10,"barcode":""}'
link() { jq -nc --argjson id "$1" --argjson main "${2:-true}" --argjson cost "${3:-100}" '{supplierId:$id, supplierSku:"", cost:$cost, leadTimeDays:3, moq:1, isMain:$main}'; }
while IFS='|' read -r body msg; do
  call POST /products "$body"
  expect_status 400
  expect_msg "$msg"
done <<EOF
$(sku TST-002 "คุม serial" "$CAT_NB" ".serialControl = true | .serialPrefix = \"T\" | .packUnits = [$pack]")|สินค้าคุม Serial มีได้หน่วยเดียว
$(sku TST-002 "แพ็ก" "$CAT_CBL" '.packUnits = [{"unit":"กล่อง","factor":1,"barcode":""}]')|ตัวคูณของหน่วยต้องเป็นจำนวนเต็มมากกว่า 1
$(sku TST-002 "แพ็ก" "$CAT_CBL" '.packUnits = [{"unit":"กล่อง","factor":0,"barcode":""}]')|ตัวคูณของหน่วยต้องเป็นจำนวนเต็มมากกว่า 1
$(sku TST-002 "แพ็ก" "$CAT_CBL" '.packUnits = [{"unit":" ","factor":10,"barcode":""}]')|ชื่อหน่วยต้องไม่ว่าง ไม่ซ้ำกัน และไม่ซ้ำหน่วยฐาน
$(sku TST-002 "แพ็ก" "$CAT_CBL" '.packUnits = [{"unit":"ชิ้น","factor":10,"barcode":""}]')|ชื่อหน่วยต้องไม่ว่าง ไม่ซ้ำกัน และไม่ซ้ำหน่วยฐาน
$(sku TST-002 "แพ็ก" "$CAT_CBL" '.packUnits = [{"unit":"กล่อง","factor":10,"barcode":""},{"unit":"กล่อง","factor":20,"barcode":""}]')|ชื่อหน่วยต้องไม่ว่าง ไม่ซ้ำกัน และไม่ซ้ำหน่วยฐาน
$(sku TST-002 "บาร์โค้ด" "$CAT_CBL" '.barcode = "8850000000011"')|บาร์โค้ดซ้ำ: 8850000000011
$(sku TST-002 "บาร์โค้ด" "$CAT_CBL" '.barcode = "8850000000099" | .packUnits = [{"unit":"กล่อง","factor":10,"barcode":"8850000000099"}]')|บาร์โค้ดซ้ำ: 8850000000099
$(sku TST-002 "min-max" "$CAT_CBL" '.minStock = 10 | .maxStock = 5')|สต็อกสูงสุดต้องไม่น้อยกว่าจุดสั่งซื้อ
$(sku TST-002 "ผจ" "$CAT_CBL" ".suppliers = [$(link "$SUP1"), $(link "$SUP1" false)]")|ผู้จำหน่ายซ้ำกันใน SKU นี้
$(sku TST-002 "ผจ" "$CAT_CBL" ".suppliers = [$(link "$SUP1" false), $(link "$SUP2" false)]")|ต้องเลือกผู้จำหน่ายหลัก 1 ราย
$(sku TST-002 "ผจ" "$CAT_CBL" ".suppliers = [$(link "$SUP1"), $(link "$SUP2")]")|ต้องเลือกผู้จำหน่ายหลัก 1 ราย
$(sku TST-002 "ผจ" "$CAT_CBL" ".suppliers = [$(link 99999999)]")|ไม่พบผู้จำหน่าย
$(sku TST-002 "ผจ" "$CAT_CBL" ".suppliers = [$(link "$SUP1" true -1)]")|ราคาซื้อ / lead time / MOQ ต้องไม่ติดลบ
$(sku TST-002 "ผจ" "$CAT_CBL" ".suppliers = [$(link "$SUP1" | jq -c '.moq = -1')]")|ราคาซื้อ / lead time / MOQ ต้องไม่ติดลบ
EOF
# ขอบเขต B3: ตัวคูณทศนิยม (mock: Number.isInteger)
call POST /products "$(sku TST-003 "ตัวคูณทศนิยม" "$CAT_CBL" '.packUnits = [{"unit":"กล่อง","factor":2.5,"barcode":""}]')"
check "(B3) factor 2.5 → 400 ข้อความจำนวนเต็ม" "400 ตัวคูณของหน่วยต้องเป็นจำนวนเต็มมากกว่า 1" "$STATUS $(jq -r .message <<<"$BODY")"
sql "DELETE FROM dt_product_pack_units WHERE tenant_id = $TENANT AND product_id IN (SELECT id FROM dt_products WHERE tenant_id = $TENANT AND sku = 'TST-003');
  DELETE FROM dt_products WHERE tenant_id = $TENANT AND sku = 'TST-003'" >/dev/null
# ขอบเขต B2: ค่าที่ DB มี CHECK แต่กฎ (ทั้ง api และ mock) ไม่ตรวจ → ต้องไม่ 500
b2=0
for edit in '.sku = " "' '.saleStatus = "weird"' '.vatType = "weird"' '.cost = -1' '.warrantyMonths = -1' '.serialControl = true | .serialLength = 0'; do
  call POST /products "$(sku TST-004 "ค่าที่ DB ห้าม" "$CAT_CBL" "$edit")"
  [[ "$STATUS" =~ ^5 ]] && b2=$((b2 + 1))
  check "(B2) $edit → 4xx ไม่ใช่ 5xx" 4 "${STATUS:0:1} ($STATUS $(jq -r .message <<<"$BODY"))"
done
E2E_ALLOW_5XX=$b2
E2E_ALLOW_ERROR_LOG=$((b2 * 2))
expect_sql "SELECT count(*) FROM dt_products WHERE tenant_id = $TENANT" "$products"

case_id 12.3 "staff สร้าง / แก้ SKU พร้อมทุน (E44)"
as "$T_BOSS"
call PUT "/products/$P_MS" "{\"suppliers\":[$(link "$SUP1" true 180)]}"
expect_status 200
as "$T_CASH1"
call POST /products "$(sku STF-001 "staff สร้าง" "$CAT_CBL" ".cost = 999 | .suppliers = [$(link "$SUP1" true 500)]")"
expect_status 200
STF=$(jq -r .id <<<"$BODY")
expect_json '[.cost, .avgCost] | map(tostring) | join(" ")' "null null"   # staff มองไม่เห็นทุน (CostHidingAdvice)
expect_sql "SELECT p.cost || ':' || s.cost FROM dt_products p JOIN dt_product_suppliers s ON s.tenant_id = p.tenant_id AND s.product_id = p.id WHERE p.id = $STF" "0.0000:0.0000"
call PUT "/products/$P_MS" "{\"name\":\"เมาส์ (staff แก้)\",\"cost\":999,\"suppliers\":[$(link "$SUP1" true 1)]}"
expect_status 200
expect_sql "SELECT p.name || ':' || p.cost || ':' || s.cost FROM dt_products p JOIN dt_product_suppliers s ON s.tenant_id = p.tenant_id AND s.product_id = p.id WHERE p.id = $P_MS" \
  "เมาส์ (staff แก้):200.0000:180.0000"
as "$T_BOSS"
call GET "/products/$P_MS"
expect_json '[.cost, .suppliers[0].cost] | map(. + 0 | tostring) | join(" ")' "200 180"

case_id 12.4 "เปลี่ยนคุม serial / เป็นบริการ ขณะมีของ"
call PUT "/products/$P_CBL" '{"serialControl":true,"serialPrefix":"C","packUnits":[]}'
expect_status 400
expect_msg "ยังมีสต็อกคงเหลือ (สาขา HQ) เปลี่ยนการคุม Serial ไม่ได้"
call PUT "/products/$P_CBL" '{"itemType":"service"}'
expect_status 400
expect_msg "ยังมีสต็อกคงเหลือ (สาขา HQ) เปลี่ยนเป็นสินค้าบริการไม่ได้"
call PUT "/products/$P_MS" '{"itemType":"service"}'
expect_status 400
expect_msg "ยังมีสต็อกคงเหลือ (สาขา HQ, BN) เปลี่ยนเป็นสินค้าบริการไม่ได้"
call POST /transfers "{\"toBranchId\":$BR_BN,\"lines\":[{\"productId\":$P_EXM,\"qty\":10,\"serials\":[]}],\"note\":\"\"}"
expect_status 200
TR=$(jq -r .transferNo <<<"$BODY")
call PUT "/products/$P_EXM" '{"itemType":"service"}'
expect_status 400
expect_msg "ยังมีสต็อกคงเหลือ (ระหว่างโอน $TR) เปลี่ยนเป็นสินค้าบริการไม่ได้"
expect_sql "SELECT string_agg(sku || ':' || item_type || ':' || serial_control, ',' ORDER BY sku) FROM dt_products WHERE id IN ($P_CBL, $P_MS, $P_EXM)" \
  "CBL-001:stock:false,EXM-001:stock:false,MS-001:stock:false"

case_id 12.5 "ลบ SKU"
mk() { call POST /products "$(sku "$1" "$2" "$CAT_CBL")"; jq -r .id <<<"$BODY"; }
SOLD=$(mk DEL-SOLD "ขายแล้ว"); PROMO=$(mk DEL-PROMO "อยู่ในโปร"); EMPTY=$(mk DEL-EMPTY "ว่าง")
for p in "$SOLD" "$EMPTY"; do
  call POST /prices "{\"productId\":$p,\"price\":50,\"startDate\":\"2020-01-01\",\"endDate\":null,\"note\":\"\"}"
done
call PUT "/products/$SOLD/stock" '{"delta":1,"unitCost":10,"note":"รับ"}'
terminal 1
call POST /shifts/open '{"openingFloat":0,"openingDenominations":[]}'
call POST /sales "$(jq -nc --argjson p "$SOLD" --argjson m "$PM_CASH" '{items:[{productId:$p, factor:1, qty:1, serial:null}], freeSerials:[],
  payments:[{methodId:$m, amount:50, reference:""}], customer:"", expectedTotal:50}')"
expect_status 200
terminal -
call POST /promotions "{\"code\":\"PRO-DEL\",\"name\":\"โปรของ SKU\",\"type\":\"item_discount\",\"startDate\":\"$tomorrow\",\"endDate\":null,\"enabled\":true,
  \"scope\":{\"all\":false,\"productIds\":[$PROMO],\"categoryIds\":[]},\"minQty\":1,\"discount\":{\"kind\":\"percent\",\"value\":10,\"maxDiscount\":null}}"
expect_status 200
call DELETE "/products/$P_CBL"
expect_msg "ยังมีสต็อกคงเหลือ (สาขา HQ) ลบไม่ได้"
call DELETE "/products/$SOLD"
expect_status 400
expect_msg "SKU นี้มีประวัติการขาย ลบไม่ได้ (เปลี่ยนสถานะเป็นเลิกจำหน่ายแทน)"
call DELETE "/products/$PROMO"
expect_status 400
expect_msg "SKU นี้อยู่ในโปรโมชั่น PRO-DEL ลบไม่ได้"
expect_sql "SELECT count(*) FROM dt_sku_prices WHERE tenant_id = $TENANT AND product_id = $EMPTY" 1
call @api2 DELETE "/products/$EMPTY"
expect_status 200
expect_sql "SELECT (SELECT count(*) FROM dt_products WHERE id = $EMPTY) || ':' || (SELECT count(*) FROM dt_sku_prices WHERE tenant_id = $TENANT AND product_id = $EMPTY)" "0:0"
call GET "/products/$EMPTY"
expect_status 404

case_id 12.6 "GET /products ตาม X-Branch-Id"
for b in "$BR_HQ" "$BR_BN"; do
  branch "$b"
  call GET /products
  expect_status 200
  got=$(jq -r '[.[] | select(.itemType == "stock") | .sku + ":" + (.stock|tostring) + "/" + (.avgCost + 0|tostring)] | sort | join(",")' <<<"$BODY")
  want=$(sql "SELECT string_agg(p.sku || ':' || coalesce(s.stock, 0) || '/' || coalesce(trim_scale(s.avg_cost), 0), ',' ORDER BY p.sku)
    FROM dt_products p LEFT JOIN dt_branch_stock s ON s.tenant_id = p.tenant_id AND s.product_id = p.id AND s.branch_id = $b
    WHERE p.tenant_id = $TENANT AND p.item_type = 'stock'")
  check "สาขา #$b stock / avgCost = dt_branch_stock" "$want" "$got"
done
branch "$BR_BN"
call GET "/products/$P_MS"
expect_json '[.stock, .avgCost, .currentPrice] | map(. + 0 | tostring) | join(" ")' "5 220 350"
branch "$BR_HQ"
call @api2 GET "/products/$P_MS"
expect_json '[(.stock, .avgCost, .currentPrice | . + 0 | tostring), .categoryPath] | join(" ")' "20 200 350 ไอที > เมาส์"
call GET "/products/$P_SVC"
expect_json '[.itemType, (.stock, .currentPrice | . + 0 | tostring)] | join(" ")' "service 0 499.9"
as -

finish
