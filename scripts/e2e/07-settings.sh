#!/usr/bin/env bash
# ข้อ 7 — ตั้งค่าบริษัท / สาขาที่ทำงาน / StoreInfo / สิทธิ์เมนู (docs/e2e-test-plan.md)
E2E_STEP=7
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

std_company E7
login "$CODE" boss pass1234; T_BOSS=$TOKEN
login "$CODE" mgr pass1234; T_MGR=$TOKEN
login "$CODE" cash1 pass1234; T_CASH1=$TOKEN
company_body() { # [jq แก้] → payload เต็มของ PUT /settings/company
  jq -nc --arg n "บริษัท ทดสอบ $CODE" '{name:$n, taxId:"0105550123451", vatRegistered:true, taxInvoicePaper:"A4", exchangeDays:7,
    manualDiscountMaxPercent:5, requireShift:true, shiftVarianceLimit:100, shiftMaxHours:12, staffSeeCost:false}' | jq -c "${1:-.}"
}
info_row() { sql "SELECT name || ':' || tax_id || ':' || vat_registered || ':' || tax_invoice_paper || ':' || exchange_days || ':' || manual_discount_max_percent
  || ':' || require_shift || ':' || shift_variance_limit || ':' || shift_max_hours || ':' || staff_see_cost FROM dt_company_info WHERE tenant_id = $TENANT"; }
audit_last() { sql "SELECT action || ':' || coalesce(user_id::text, '-') || ':' || coalesce(branch_id::text, '-') || ':' || detail FROM dt_audit_log
  WHERE tenant_id = $TENANT AND action = '$1' ORDER BY id DESC LIMIT 1"; }

case_id 7.1 "PUT /settings/company ตามสิทธิ์"
as "$T_CASH1"
call PUT /settings/company "$(company_body)"
expect_status 403
expect_msg "แก้ข้อมูลร้านได้เฉพาะผู้ดูแลระบบ"
as "$T_MGR"
call PUT /settings/company "$(company_body)"
expect_status 403
expect_msg "ทำได้เฉพาะผู้ดูแลระบบระดับบริษัท (ทุกสาขา)"
as "$T_BOSS"
call PUT /settings/company "$(company_body '.taxInvoicePaper = "80mm" | .exchangeDays = 10 | .staffSeeCost = true')"
expect_status 200
expect_json '[.taxInvoicePaper, .exchangeDays, .staffSeeCost] | map(tostring) | join(" ")' "80mm 10 true"
check "dt_company_info" "บริษัท ทดสอบ $CODE:0105550123451:true:80mm:10:5.00:true:100.00:12:true" "$(info_row)"
check "audit company_settings" "company_settings:$U_BOSS:-:VAT จด · ระบบกะ เปิด · ส่วนลดพนักงานสูงสุด 5%" "$(audit_last company_settings)"
call PUT /settings/company "$(company_body)"
expect_status 200

case_id 7.2 "ค่าผิดทีละช่อง"
before=$(info_row)
while IFS='|' read -r edit msg; do
  call PUT /settings/company "$(company_body "$edit")"
  expect_status 400
  expect_msg "$msg"
done <<'EOF'
.name = "  "|กรุณากรอกชื่อร้าน
.taxId = "0105550123452"|เลขประจำตัวผู้เสียภาษีต้องเป็น 13 หลักที่ถูกต้อง
.taxInvoicePaper = "A5"|ขนาดกระดาษใบกำกับภาษีไม่ถูกต้อง
.exchangeDays = 1.5|จำนวนวันเปลี่ยนสินค้าต้องเป็นจำนวนเต็ม 0–365
.exchangeDays = 366|จำนวนวันเปลี่ยนสินค้าต้องเป็นจำนวนเต็ม 0–365
.manualDiscountMaxPercent = 100.5|เพดานส่วนลดพิเศษต้องอยู่ระหว่าง 0–100%
.shiftVarianceLimit = 100001|เกณฑ์ส่วนต่างปิดกะต้องอยู่ระหว่าง 0–100,000 บาท
.shiftMaxHours = 0|ชั่วโมงเตือนกะเปิดค้างต้องเป็นจำนวนเต็ม 1–48
.shiftMaxHours = 49|ชั่วโมงเตือนกะเปิดค้างต้องเป็นจำนวนเต็ม 1–48
EOF
check "dt_company_info ไม่เปลี่ยน" "$before" "$(info_row)"

case_id 7.3 "ปิดระบบกะขณะมีกะเปิด / หลังปิดกะ"
branch "$BR_HQ"; terminal 1
call POST /shifts/open '{"openingFloat":0,"openingDenominations":[]}'
expect_status 200
shift=$(jq -r .id <<<"$BODY")
branch -; terminal -
call PUT /settings/company "$(company_body '.requireShift = false')"
expect_status 400
expect_msg "ยังมีกะที่เปิดอยู่ 1 กะ — ปิดกะทั้งหมดก่อนปิดการใช้ระบบกะ"
expect_sql "SELECT require_shift FROM dt_company_info WHERE tenant_id = $TENANT" t
branch "$BR_HQ"; terminal 1
call POST "/shifts/$shift/close" "{\"counts\":[{\"methodId\":$PM_CASH,\"counted\":0},{\"methodId\":$PM_CARD,\"counted\":0},
  {\"methodId\":$PM_QR,\"counted\":0},{\"methodId\":$PM_TRANSFER,\"confirmed\":true}],\"cashDenominations\":[],\"depositAmount\":0,\"note\":\"\"}"
expect_status 200
branch -; terminal -
call PUT /settings/company "$(company_body '.requireShift = false')"
expect_status 200
expect_sql "SELECT require_shift FROM dt_company_info WHERE tenant_id = $TENANT" f
check "audit company_settings (ระบบกะ ปิด)" "company_settings:$U_BOSS:-:VAT จด · ระบบกะ ปิด · ส่วนลดพนักงานสูงสุด 5%" "$(audit_last company_settings)"
call PUT /settings/company "$(company_body)"
expect_status 200

case_id 7.4 "/settings/branch"
branch_row() { sql "SELECT code || ':' || branch_no || ':' || name || ':' || address || ':' || phone || ':' || receipt_footer FROM dt_branches WHERE id = $1"; }
before=$(branch_row "$BR_BN")
as "$T_MGR"; branch "$BR_BN"
call PUT /settings/branch '{"name":"บางนา (mgr)","address":"x","phone":"","receiptFooter":""}'
expect_status 403
expect_msg "ไม่มีสิทธิ์ทำงานในสาขานี้"
as "$T_BOSS"
while IFS='|' read -r body msg; do
  call PUT /settings/branch "$body"
  expect_status 400
  expect_msg "$msg"
done <<EOF
{"name":"  ","address":"ที่อยู่","phone":"","receiptFooter":""}|กรุณากรอกชื่อสาขา
{"name":"บางนา","address":" ","phone":"","receiptFooter":""}|กรุณากรอกที่อยู่
{"name":"บางนา","address":"ที่อยู่","phone":"","receiptFooter":"$(printf 'ก%.0s' $(seq 1 201))"}|ข้อความท้ายใบเสร็จต้องไม่เกิน 200 ตัวอักษร
EOF
check "สาขา BN ไม่เปลี่ยนหลังค่าผิด" "$before" "$(branch_row "$BR_BN")"
call PUT /settings/branch '{"code":"ZZ","branchNo":"99999","name":" สาขาบางนา ใหม่ ","address":"99 บางนา","phone":"021111111","receiptFooter":"ขอบคุณ BN","active":false}'
expect_status 200
expect_json '[.code, .branchNo, .name, .active] | map(tostring) | join(" ")' "BN 00001 สาขาบางนา ใหม่ true"
check "dt_branches BN: เปลี่ยนเฉพาะชื่อ / ที่อยู่ / โทร / footer" "BN:00001:สาขาบางนา ใหม่:99 บางนา:021111111:ขอบคุณ BN" "$(branch_row "$BR_BN")"
expect_sql "SELECT active FROM dt_branches WHERE id = $BR_BN" t
check "audit branch_settings" "branch_settings:$U_BOSS:$BR_BN:แก้ชื่อสถานประกอบการ / ที่อยู่ / โทร / ท้ายใบเสร็จ" "$(audit_last branch_settings)"
call GET /settings/branch
expect_json .name "สาขาบางนา ใหม่"
branch -

case_id 7.5 "/settings/store"
demo_branch=$(sql "SELECT min(id) FROM dt_branches WHERE tenant_id = 1")
call GET "/settings/store?branchId=$demo_branch"
expect_status 404
expect_msg "ไม่พบสาขา"
call GET "/settings/store?branchId=$BR_BN"
expect_status 200
expect_json '[.placeName, .branchNo, .address] | join(" ")' "สาขาบางนา ใหม่ 00001 99 บางนา"
as "$T_MGR"
call PUT /settings/store '{"name":"x"}'
expect_status 403
expect_msg "ทำได้เฉพาะผู้ดูแลระบบระดับบริษัท (ทุกสาขา)"
as "$T_BOSS"; branch "$BR_HQ"
call PUT /settings/store "$(jq -nc --arg n "ร้าน $CODE ใหม่" '{name:$n, placeName:"สำนักงานใหญ่ ใหม่", vatRegistered:true, taxId:"0105550123451",
  branchType:"head", branchNo:"12345", address:"1 สุขุมวิท ใหม่", phone:"020000000", receiptFooter:"ขอบคุณ HQ", taxInvoicePaper:"A4",
  exchangeDays:7, manualDiscountMaxPercent:5, requireShift:true, shiftVarianceLimit:100, shiftMaxHours:12, staffSeeCost:false}')"
expect_status 200
expect_json '[.name, .placeName, .branchType, "[" + .branchNo + "]"] | join(" ")' "ร้าน $CODE ใหม่ สำนักงานใหญ่ ใหม่ head []"   # storeInfoOf ของ mock: สำนักงานใหญ่ branchNo ว่าง
expect_sql "SELECT name FROM dt_company_info WHERE tenant_id = $TENANT" "ร้าน $CODE ใหม่"
check "สาขาที่ทำงาน (HQ) เปลี่ยน, เลขสาขาไม่เปลี่ยน" "HQ:00000:สำนักงานใหญ่ ใหม่:1 สุขุมวิท ใหม่:020000000:ขอบคุณ HQ" "$(branch_row "$BR_HQ")"
check "สาขา BN ไม่ถูกแตะ" "BN:00001:สาขาบางนา ใหม่:99 บางนา:021111111:ขอบคุณ BN" "$(branch_row "$BR_BN")"
branch -

case_id 7.6 "สิทธิ์เมนู"
call GET /settings/menu-access
expect_json '.userMenus | join(",")' "dashboard,pos,sales,settings"
while IFS='|' read -r body msg; do
  call PUT /settings/menu-access "$body"
  expect_status 400
  expect_msg "$msg"
done <<'EOF'
{"userMenus":[]}|ต้องเลือกอย่างน้อย 1 เมนูให้ผู้ใช้ทั่วไป
{"userMenus":["users"]}|เมนูผู้ใช้งาน / บัญชีบริษัท เป็นของผู้ดูแลระบบเท่านั้น
{"userMenus":["pos","account"]}|เมนูผู้ใช้งาน / บัญชีบริษัท เป็นของผู้ดูแลระบบเท่านั้น
{"userMenus":["x"]}|ไม่มีเมนู x
{"userMenus":["pos","pos"]}|เลือกเมนูซ้ำ
EOF
as "$T_CASH1"
call PUT /settings/menu-access '{"userMenus":["pos"]}'
expect_status 403
expect_msg "กำหนดสิทธิ์เมนูได้เฉพาะผู้ดูแลระบบ"
expect_sql "SELECT count(*) FROM dt_menu_access WHERE tenant_id = $TENANT" 0
as "$T_BOSS"
call PUT /settings/menu-access '{"userMenus":["settings","inventory","pos","dashboard"]}'
expect_status 200
expect_json '.userMenus | join(",")' "dashboard,pos,inventory,settings"
expect_sql "SELECT array_to_string(user_menus, ',') FROM dt_menu_access WHERE tenant_id = $TENANT" "dashboard,pos,inventory,settings"
check "audit menu_access" "menu_access:$U_BOSS:-:ผู้ใช้ทั่วไปเห็น: แดชบอร์ด, ขายหน้าร้าน, คลังสินค้า, ตั้งค่า" "$(audit_last menu_access)"
as "$T_CASH1"
call @api2 GET /settings/menu-access
expect_json '.userMenus | join(",")' "dashboard,pos,inventory,settings"
as -

finish
