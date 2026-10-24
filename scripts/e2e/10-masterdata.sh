#!/usr/bin/env bash
# ข้อ 10 — หมวดหมู่ / ผู้จำหน่าย / ช่องทางชำระเงิน (docs/e2e-test-plan.md)
E2E_STEP=10
# B2 แก้แล้ว (ErrorHandler → 400) — ไม่ยอมให้มี 5xx
E2E_ALLOW_5XX=0
E2E_ALLOW_ERROR_LOG=0
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

tomorrow=$(TZ=Asia/Bangkok date -d tomorrow +%F)
std_company E10
login "$CODE" boss pass1234; T_BOSS=$TOKEN
login "$CODE" cash1 pass1234; T_CASH1=$TOKEN
cat_body() { jq -nc --arg c "$1" --arg n "$2" --argjson p "${3:-null}" '{code:$c, name:$n, parentId:$p, active:true}'; }

case_id 10.1 "หมวดหมู่: สร้าง"
as "$T_CASH1"
call POST /categories "$(cat_body ACC "อุปกรณ์เสริม")"
expect_status 403
expect_msg "แก้ไขหมวดหมู่ได้เฉพาะผู้ดูแลระบบ"
call PUT "/categories/$CAT_MOUSE" "$(cat_body MOUSE "เมาส์ (staff)")"
expect_status 403
call DELETE "/categories/$CAT_SVC"
expect_status 403
as "$T_BOSS"
count=$(sql "SELECT count(*) FROM dt_categories WHERE tenant_id = $TENANT")
while IFS='|' read -r body msg; do
  call POST /categories "$body"
  expect_status 400
  expect_msg "$msg"
done <<EOF
$(cat_body "a b" "หมวด")|รหัสใช้ได้เฉพาะ A-Z, 0-9 และ -
$(cat_body "acc" "หมวด")|รหัสใช้ได้เฉพาะ A-Z, 0-9 และ -
$(cat_body "" "หมวด")|รหัสใช้ได้เฉพาะ A-Z, 0-9 และ -
$(cat_body MOUSE "หมวด")|รหัส MOUSE ซ้ำ
$(cat_body ACC "  ")|กรุณากรอกชื่อหมวดหมู่
$(cat_body ACC "หมวด" 99999999)|ไม่พบหมวดหมู่แม่
$(cat_body ACC "หมวด" "$CAT_MOUSE")|เมาส์ มี SKU อยู่ 1 รายการ แบ่งหมวดย่อยไม่ได้
EOF
expect_sql "SELECT count(*) FROM dt_categories WHERE tenant_id = $TENANT" "$count"
call POST /categories "$(cat_body ACC "อุปกรณ์เสริม" "$CAT_IT")"
expect_status 200
CAT_ACC=$(jq -r .id <<<"$BODY")
call POST /categories "$(cat_body ACC-USB "USB" "$CAT_ACC")"
expect_status 200
CAT_L3=$(jq -r .id <<<"$BODY")
call POST /categories "$(cat_body ACC-USB-C "USB-C" "$CAT_L3")"
expect_status 400
expect_msg "แบ่งหมวดหมู่ได้ไม่เกิน 3 ระดับ"
expect_sql "SELECT string_agg(code || ':' || level, ',' ORDER BY level, code) FROM dt_categories WHERE tenant_id = $TENANT AND code LIKE 'ACC%' OR id = $CAT_IT" \
  "IT:1,ACC:2,ACC-USB:3"
call PUT "/categories/$CAT_L3" "$(cat_body ACC-USB " USB (แก้) " "$CAT_IT")"
expect_status 200
expect_json '[.name, .parentId, .level] | map(tostring) | join(" ")' "USB (แก้) $CAT_ACC 3"   # ย้ายแม่ไม่ได้
call PUT "/categories/$CAT_L3" "$(cat_body MOUSE "USB")"
expect_status 400
expect_msg "รหัส MOUSE ซ้ำ"

case_id 10.2 "หมวดหมู่: ลบ"
call POST /categories "$(cat_body PROMO "หมวดในโปร" "$CAT_IT")"
CAT_PROMO=$(jq -r .id <<<"$BODY")
call POST /promotions "{\"code\":\"PRO-C10\",\"name\":\"ลด 10% หมวดในโปร\",\"type\":\"item_discount\",\"startDate\":\"$tomorrow\",\"endDate\":null,\"enabled\":true,
  \"scope\":{\"all\":false,\"productIds\":[],\"categoryIds\":[$CAT_PROMO]},\"minQty\":1,\"discount\":{\"kind\":\"percent\",\"value\":10,\"maxDiscount\":null}}"
expect_status 200
call DELETE "/categories/$CAT_ACC"
expect_status 400
expect_msg "ยังมีหมวดหมู่ย่อย ลบไม่ได้"
call DELETE "/categories/$CAT_MOUSE"
expect_status 400
expect_msg "ยังมี SKU 1 รายการในหมวดนี้ ลบไม่ได้"
call DELETE "/categories/$CAT_PROMO"
expect_status 400
expect_msg "หมวดหมู่นี้อยู่ในโปรโมชั่น PRO-C10 ลบไม่ได้"
call DELETE "/categories/$CAT_L3"
expect_status 200
call DELETE "/categories/$CAT_ACC"
expect_status 200   # ลูกถูกลบแล้ว
expect_sql "SELECT count(*) FROM dt_categories WHERE tenant_id = $TENANT AND id IN ($CAT_ACC, $CAT_L3)" 0
call @api2 DELETE "/categories/$CAT_ACC"
expect_status 404
expect_msg "ไม่พบหมวดหมู่"

case_id 10.3 "ผู้จำหน่าย"
sup_body() { jq -nc --arg c "$1" --arg t "${2:-0105550123451}" '{code:$c, name:("ผู้จำหน่าย " + $c), taxId:$t, branchType:"head", contactName:"คุณติดต่อ",
  phone:"021234567", email:"sales@sup.test", creditDays:45, note:"หมายเหตุ", active:true}' | jq -c "${3:-.}"; }
as "$T_CASH1"
call POST /suppliers "$(sup_body SUP1)"
expect_status 403
expect_msg "แก้ไขผู้จำหน่ายได้เฉพาะผู้ดูแลระบบ"
as "$T_BOSS"
call POST /suppliers "$(sup_body SUP1)"
expect_status 200
SUP1=$(jq -r .id <<<"$BODY")
expect_json '[.contactName, .creditDays, .branchNo] | map(tostring) | join(" ")' "คุณติดต่อ 45 "
while IFS='|' read -r body msg; do
  call POST /suppliers "$body"
  expect_status 400
  expect_msg "$msg"
done <<EOF
$(sup_body sup2)|รหัสใช้ได้เฉพาะ A-Z, 0-9 และ -
$(sup_body SUP2 '' '.name = " "')|กรุณากรอกชื่อผู้จำหน่าย
$(sup_body SUP2 0105550123452)|เลขประจำตัวผู้เสียภาษีไม่ถูกต้อง
$(sup_body SUP2 '' '.taxId = ""')|เลขประจำตัวผู้เสียภาษีไม่ถูกต้อง
$(sup_body SUP2 '' '.branchType = "branch" | .branchNo = "12"')|เลขสาขาต้องเป็นตัวเลข 5 หลัก
$(sup_body SUP2 '' '.email = "bad"')|อีเมลไม่ถูกต้อง
$(sup_body SUP2 '' '.creditDays = -1')|เครดิตเทอมต้องไม่ติดลบ
$(sup_body SUP1)|รหัส SUP1 ซ้ำ
$(sup_body SUP2)|เลขผู้เสียภาษีและสาขานี้มีอยู่แล้ว
EOF
call POST /suppliers "$(sup_body SUP2 '' '.branchType = "branch" | .branchNo = "00001"')"
expect_status 200   # เลขภาษีเดียวกันคนละสาขาได้
SUP2=$(jq -r .id <<<"$BODY")
call @api2 PUT "/suppliers/$SUP1" '{"code":"SUP1","name":"ผู้จำหน่าย SUP1 ใหม่","taxId":"0105550123451"}'
expect_status 200
expect_json '[.name, "[" + .contactName + "]", "[" + .phone + "]", .creditDays, .active] | map(tostring) | join(" ")' "ผู้จำหน่าย SUP1 ใหม่ [] [] 30 true"
expect_sql "SELECT contact_name || ':' || credit_days || ':' || note FROM dt_suppliers WHERE id = $SUP1" ":30:"
branch "$BR_HQ"
call PUT "/products/$P_MS" "{\"suppliers\":[{\"supplierId\":$SUP1,\"supplierSku\":\"S-MS\",\"cost\":180,\"leadTimeDays\":7,\"moq\":1,\"isMain\":true}]}"
expect_status 200
branch -
call DELETE "/suppliers/$SUP1"
expect_status 400
expect_msg "ยังผูกกับ SKU 1 รายการ ลบไม่ได้ (ปิดใช้งานแทน)"
call GET "/suppliers/$SUP1"
expect_json .productCount 1
call DELETE "/suppliers/$SUP2"
expect_status 200

case_id 10.4 "ช่องทางชำระเงิน: ทุกประเภท + ค่าผิด"
pm_body() { jq -nc --arg c "$1" --arg t "$2" '{code:$c, name:("ช่องทาง " + $c), type:$t, active:true}' | jq -c "${3:-.}"; }
as "$T_CASH1"
call POST /payment-methods "$(pm_body EW e_wallet)"
expect_status 403
expect_msg "แก้ไขช่องทางชำระเงินได้เฉพาะผู้ดูแลระบบ"
as "$T_BOSS"
count=$(sql "SELECT count(*) FROM dt_payment_methods WHERE tenant_id = $TENANT")
while IFS='|' read -r body msg; do
  call POST /payment-methods "$body"
  expect_status 400
  expect_msg "$msg"
done <<EOF
$(pm_body "A B" card)|รหัสใช้ได้เฉพาะ A-Z, 0-9 และ - (ไม่เกิน 20 ตัว)
$(pm_body ABCDEFGHIJKLMNOPQRSTU card)|รหัสใช้ได้เฉพาะ A-Z, 0-9 และ - (ไม่เกิน 20 ตัว)
$(pm_body card card)|รหัส CARD ซ้ำ
$(pm_body EW e_wallet '.name = " "')|กรุณากรอกชื่อช่องทางชำระเงิน
$(pm_body EW e_wallet '.name = "AAAAAAAAAAAAAAAAAAAAA"')|ชื่อต้องไม่เกิน 20 ตัวอักษร
$(pm_body EW bitcoin)|ประเภทไม่ถูกต้อง
$(pm_body EW e_wallet '.minAmount = 100 | .maxAmount = 50')|ยอดสูงสุดต้องมากกว่า 0 และไม่น้อยกว่ายอดขั้นต่ำ
$(pm_body EW e_wallet '.feePercent = 10.5')|ค่าธรรมเนียมต้องอยู่ระหว่าง 0 - 10%
$(pm_body EW e_wallet '.feePercent = -1')|ค่าธรรมเนียมต้องอยู่ระหว่าง 0 - 10%
$(pm_body EW e_wallet '.requireReference = true | .referenceLabel = " "')|กรุณาระบุชื่อช่องเลขอ้างอิง
$(pm_body QR2 qr '.promptPayId = "12345"')|PromptPay ID ต้องเป็นเบอร์มือถือ 10 หลัก หรือเลขผู้เสียภาษี 13 หลักที่ถูกต้อง
$(pm_body QR2 qr '.promptPayId = "0105550123452"')|PromptPay ID ต้องเป็นเบอร์มือถือ 10 หลัก หรือเลขผู้เสียภาษี 13 หลักที่ถูกต้อง
$(pm_body INST installment '.installmentMonths = []')|กรุณาระบุจำนวนเดือนที่ผ่อนได้อย่างน้อย 1 ตัวเลือก
$(pm_body INST installment '.installmentMonths = [1]')|จำนวนเดือนผ่อนต้องเป็นจำนวนเต็ม 2 - 60
$(pm_body INST installment '.installmentMonths = [3, 61]')|จำนวนเดือนผ่อนต้องเป็นจำนวนเต็ม 2 - 60
EOF
expect_sql "SELECT count(*) FROM dt_payment_methods WHERE tenant_id = $TENANT" "$count"
# cashRounding นอกค่าที่รู้จัก — กฎไม่ตรวจ, DB มี CHECK ('none', '0.25', '1') → ต้องได้ 400 ข้อความกลาง ไม่ใช่ 500
call POST /payment-methods "$(pm_body CASH9 cash '.cashRounding = "0.5"')"
check "cashRounding 0.5 → 400 ข้อความกลาง" "400 ข้อมูลที่ส่งมาไม่ถูกต้อง" "$STATUS $(jq -r .message <<<"$BODY" 2>/dev/null)"
call POST /payment-methods "$(pm_body CARD9 card '.closeCheck = "weird"')"
check "closeCheck weird → 400 ข้อความกลาง" "400 ข้อมูลที่ส่งมาไม่ถูกต้อง" "$STATUS $(jq -r .message <<<"$BODY" 2>/dev/null)"
call POST /suppliers "$(sup_body SUP9 '' '.branchType = "weird"')"
check "ผู้จำหน่าย branchType weird → 400 ข้อความกลาง" "400 ข้อมูลที่ส่งมาไม่ถูกต้อง" "$STATUS $(jq -r .message <<<"$BODY" 2>/dev/null)"
for t in "EW e_wallet ." "INST installment .installmentMonths=[10,3,3]" "QR2 qr .promptPayId=\"010-555-0123451\"" "OTH other ." "CASH2 cash .cashRounding=\"1\"|.feePercent=5"; do
  set -- $t
  call POST /payment-methods "$(pm_body "$1" "$2" "$3")"
  expect_status 200
done
expect_sql "SELECT string_agg(code || ':' || type || ':' || close_check || ':' || coalesce(array_to_string(installment_months, '/'), '') || ':' || prompt_pay_id || ':' || fee_percent
  || ':' || cash_rounding, ',' ORDER BY sort_order) FROM dt_payment_methods WHERE tenant_id = $TENANT AND sort_order > 4" \
  "EW:e_wallet:confirm:::0.00:none,INST:installment:count:3/10::0.00:none,QR2:qr:count::0105550123451:0.00:none,OTH:other:confirm:::0.00:none,CASH2:cash:count:::0.00:1"
expect_sql "SELECT string_agg(code || ':' || close_check, ',' ORDER BY sort_order) FROM dt_payment_methods WHERE tenant_id = $TENANT AND sort_order <= 4" \
  "CASH:count,CARD:count,QR:count,TRANSFER:confirm"

case_id 10.5 "เรียงลำดับ"
call GET /payment-methods
ids=$(jq -c '[.[].id]' <<<"$BODY")
rev=$(jq -c 'reverse' <<<"$ids")
call PUT /payment-methods/order "{\"ids\":$rev}"
expect_status 200
expect_json '[.[].id] | map(tostring) | join(",")' "$(jq -r 'map(tostring) | join(",")' <<<"$rev")"
expect_sql "SELECT string_agg(sort_order::text, ',' ORDER BY array_position('$(jq -r 'map(tostring) | join(",") | "{" + . + "}"' <<<"$rev")'::bigint[], id))
  FROM dt_payment_methods WHERE tenant_id = $TENANT" "$(seq -s, 1 "$(jq length <<<"$rev")")"
before=$(sql "SELECT string_agg(id || ':' || sort_order, ',' ORDER BY id) FROM dt_payment_methods WHERE tenant_id = $TENANT")
for body in "{\"ids\":$(jq -c '.[1:]' <<<"$ids")}" "{\"ids\":$(jq -c '.[1:] + [99999999]' <<<"$ids")}" "{\"ids\":$(jq -c '.[1:] + [.[1]]' <<<"$ids")}" '{"ids":[]}' '{}'; do
  call PUT /payment-methods/order "$body"
  expect_status 400
  expect_msg "ลำดับไม่ครบหรือมีรายการที่ไม่รู้จัก กรุณาโหลดใหม่"
done
expect_sql "SELECT string_agg(id || ':' || sort_order, ',' ORDER BY id) FROM dt_payment_methods WHERE tenant_id = $TENANT" "$before"

case_id 10.6 "ลบ / ปิดเงินสด, ลบช่องทางที่มีบิล"
CASH2=$(sql "SELECT id FROM dt_payment_methods WHERE tenant_id = $TENANT AND code = 'CASH2'")
call DELETE "/payment-methods/$CASH2"
expect_status 200   # ยังมี CASH ที่ใช้งาน
call DELETE "/payment-methods/$PM_CASH"
expect_status 400
expect_msg "ต้องมีช่องทางเงินสดที่เปิดใช้งานอย่างน้อย 1 ช่องทาง ลบไม่ได้"
call PUT "/payment-methods/$PM_CASH" '{"code":"CASH","name":"เงินสด","type":"cash","active":false}'
expect_status 400
expect_msg "ต้องมีช่องทางเงินสดที่เปิดใช้งานอย่างน้อย 1 ช่องทาง"
call PUT "/payment-methods/$PM_CASH" '{"code":"CASH","name":"เงินสด","type":"card","active":true}'
expect_status 400
expect_msg "ต้องมีช่องทางเงินสดที่เปิดใช้งานอย่างน้อย 1 ช่องทาง"
expect_sql "SELECT type || ':' || active FROM dt_payment_methods WHERE id = $PM_CASH" "cash:true"
branch "$BR_HQ"; terminal 1
call POST /shifts/open '{"openingFloat":0,"openingDenominations":[]}'
expect_status 200
call POST /sales "$(jq -nc --argjson p "$P_MS" --argjson m "$PM_CARD" '{items:[{productId:$p, factor:1, qty:1, serial:null}], freeSerials:[],
  payments:[{methodId:$m, amount:350, reference:"A10601"}], customer:"", expectedTotal:350}')"
expect_status 200
branch -; terminal -
call DELETE "/payment-methods/$PM_CARD"
expect_status 400
expect_msg "มีการขายที่ใช้ช่องทางนี้แล้ว ลบไม่ได้ (ปิดใช้งานแทน)"
call PUT "/payment-methods/$PM_CARD" '{"code":"CARD","name":"บัตรเครดิต","type":"card","active":false}'
expect_status 200
expect_json '[.active, .requireReference, (.minAmount == 0)] | map(tostring) | join(" ")' "false false true"   # PUT แทนทั้งตัว
EW=$(sql "SELECT id FROM dt_payment_methods WHERE tenant_id = $TENANT AND code = 'EW'")
call @api2 DELETE "/payment-methods/$EW"
expect_status 200
expect_sql "SELECT count(*) FROM dt_payment_methods WHERE tenant_id = $TENANT AND id IN ($CASH2, $EW)" 0
as -

finish
