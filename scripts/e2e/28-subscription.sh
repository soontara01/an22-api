#!/usr/bin/env bash
# ข้อ 28 — แพ็กเกจ / onboarding (docs/e2e-test-plan.md)
# 28.4 เลื่อน trial_ends_on / next_invoice_on ด้วย SQL (ไม่แก้นาฬิกา)
E2E_STEP=28
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

today=$(TZ=Asia/Bangkok date +%F)
day() { TZ=Asia/Bangkok date -d "$today $1 day" +%F; }
thai() { echo "$(date -d "$1" +%-d)/$(date -d "$1" +%-m)/$(( $(date -d "$1" +%Y) + 543 ))"; }
std_company E28
login "$CODE" boss pass1234; T_BOSS=$TOKEN
login "$CODE" cash1 pass1234; T_CASH1=$TOKEN
num() { jq -r "$1 | if type == \"number\" then (. + 0 | tostring) else tostring end" <<<"$BODY"; }
usage_db() { sql "SELECT (SELECT count(*) FROM dt_branches WHERE tenant_id = $1 AND active) || ' ' || (SELECT count(*) FROM dt_users WHERE tenant_id = $1 AND active)
  || ' ' || (SELECT count(*) FROM dt_pos_terminals WHERE tenant_id = $1 AND active)"; }

case_id 28.1 "subscription"
as "$T_CASH1"
call GET /company/subscription
expect_status 403
expect_msg "ดูแพ็กเกจได้เฉพาะผู้ดูแลระบบ"
as "$T_BOSS"
call @api2 GET /company/subscription
expect_status 200
check "planId / status / invoices" "pro trial 0" "$(num .planId) $(num .status) $(num '.invoices | length')"
check "usage = นับที่ใช้งานใน DB" "2 5 3 $(usage_db "$TENANT")" "$(num .usage.branches) $(num .usage.users) $(num .usage.terminals) $(usage_db "$TENANT")"
check "subscription (trial +14 / invoice +15)" "$(day 14) $(day 15)" "$(num .subscription.trialEndsOn) $(num .subscription.nextInvoiceOn)"
call PUT "/users/$U_CASH2" '{"active":false}'
call GET /company/subscription
check "usage ไม่นับผู้ใช้ที่ปิด" 4 "$(num .usage.users)"
call PUT "/users/$U_CASH2" '{"active":true}'

case_id 28.2 "เปลี่ยนแพ็กเกจ"
call PUT /company/plan '{"planId":"starter"}'
expect_status 403
expect_msg "เปลี่ยนแพ็กเกจได้เฉพาะเจ้าของบริษัท"
as "$T_OWNER"
call PUT /company/plan '{"planId":"gold"}'
expect_status 400
expect_msg "ไม่มีแพ็กเกจนี้"
expect_sql "SELECT plan_id FROM dt_companies WHERE id = $TENANT" pro
call PUT /company/plan '{"planId":"starter"}'
expect_status 200
check "ลดเป็น starter แม้ใช้เกิน (สาขา 2 / เครื่อง 3)" "starter 2 3" "$(num .planId) $(num .usage.branches) $(num .usage.terminals)"
expect_sql "SELECT plan_id FROM dt_companies WHERE id = $TENANT" starter
expect_sql "SELECT action || ':' || user_id || ':' || actor_kind || ':' || detail FROM dt_audit_log WHERE tenant_id = $TENANT AND action = 'plan_change'" \
  "plan_change:$U_OWNER:user:เปลี่ยนเป็นแพ็กเกจ Starter (฿590 / เดือน)"
as "$T_BOSS"
call POST /branches '{"code":"CM","name":"สาขาเชียงใหม่","branchNo":"00002","address":"เชียงใหม่","phone":"","receiptFooter":"","active":true}'
expect_status 403
expect_msg "แพ็กเกจ Starter ใช้สาขาได้ 1 สาขา — เปลี่ยนแพ็กเกจได้ที่เมนูบัญชีบริษัท"
call @api2 GET /company/subscription
expect_json .planId starter   # instance อื่นเห็นทันที
as "$T_OWNER"
call PUT /company/plan '{"planId":"standard"}'
expect_status 200
call PUT /company/plan '{"planId":"pro"}'
expect_status 200

case_id 28.3 "onboarding"
as "$T_CASH1"
call GET /company/onboarding
expect_status 403
expect_msg "ดูขั้นตอนเริ่มต้นใช้งานได้เฉพาะผู้ดูแลระบบ"
as "$T_BOSS"
call GET /company/onboarding
check "std (รวมที่ปิด) = DB" "$(sql "SELECT (SELECT count(*) FROM dt_pos_terminals WHERE tenant_id = $TENANT) || ' ' || (SELECT count(*) FROM dt_categories WHERE tenant_id = $TENANT)
  || ' ' || (SELECT count(*) FROM dt_products WHERE tenant_id = $TENANT) || ' ' || (SELECT count(*) FROM dt_sku_prices WHERE tenant_id = $TENANT)
  || ' ' || (SELECT count(*) FROM dt_users WHERE tenant_id = $TENANT)")" "$(num .terminals) $(num .categories) $(num .products) $(num .prices) $(num .users)"
check "std = 3 5 5 5 5" "3 5 5 5 5" "$(num .terminals) $(num .categories) $(num .products) $(num .prices) $(num .users)"
N_CODE="E28N$(openssl rand -hex 2 | tr a-f A-F)"
as -
call POST /auth/signup "$(jq -nc --arg c "$N_CODE" '{companyCode:$c, companyName:"บริษัทใหม่", taxId:"", vatRegistered:false,
  branch:{code:"HQ", name:"สำนักงานใหญ่", branchNo:"00000", address:"กรุงเทพฯ", phone:""},
  owner:{name:"เจ้าของ", username:"owner", email:("owner@" + ($c | ascii_downcase) + ".e2e.test"), password:"owner1234"}, planId:"starter"}')"
as "$(jq -r .token <<<"$BODY")"
call GET /company/onboarding
check "บริษัทใหม่" '{"terminals":0,"categories":0,"products":0,"prices":0,"users":1}' "$(jq -c . <<<"$BODY")"

case_id 28.4 "/me แจ้งเตือนช่วงทดลอง"
sql "UPDATE dt_companies SET trial_ends_on = '$(day 3)', next_invoice_on = '$(day 4)' WHERE id = $TENANT" >/dev/null
as "$T_BOSS"
call GET /me
check "admin: warn เหลือ 3 วัน" "warn ทดลองใช้เหลืออีก 3 วัน (ถึง $(thai "$(day 3)")) — ใบแจ้งหนี้แรกออกวันที่ $(thai "$(day 4)")" "$(num .billing.level) $(num .billing.text)"
as "$T_OWNER"
call @api2 GET /me
expect_json .billing.level warn
as "$T_CASH1"
call GET /me
check "staff: billing null" null "$(num .billing)"
sql "UPDATE dt_companies SET trial_ends_on = '$(day 4)', next_invoice_on = '$(day 5)' WHERE id = $TENANT" >/dev/null
as "$T_BOSS"
call GET /me
check "เหลือ 4 วัน → ไม่เตือน" null "$(num .billing)"
sql "UPDATE dt_companies SET trial_ends_on = '$today', next_invoice_on = '$(day 1)' WHERE id = $TENANT" >/dev/null
call GET /me
check "วันสุดท้าย" "warn ทดลองใช้เหลืออีก 0 วัน (ถึง $(thai "$today")) — ใบแจ้งหนี้แรกออกวันที่ $(thai "$(day 1)")" "$(num .billing.level) $(num .billing.text)"
expect_sql "SELECT count(*) FROM dt_billing_invoices WHERE company_id = $TENANT" 0
as -

finish
