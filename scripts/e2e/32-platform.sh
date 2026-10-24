#!/usr/bin/env bash
# ข้อ 32 — platform admin (docs/e2e-test-plan.md)
# บริษัทค้างชำระ: signup + ใบค้างเกินผ่อนผัน (SQL) แล้วให้ catch-up ตอน login ระงับเอง
E2E_STEP=32
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

ym=$(TZ=Asia/Bangkok date +%Y%m)
num() { jq -r "$1 | if type == \"number\" then (. + 0 | tostring) else tostring end" <<<"$BODY"; }
audit_last() { sql "SELECT action || ':' || actor_kind || ':' || coalesce(user_id::text, 'null') || ':' || user_name || ':' || coalesce(ref_label, '') || ':' || detail
  FROM dt_audit_log WHERE tenant_id = $1 AND action = '$2' ORDER BY id DESC LIMIT 1"; }
signup() { # tag → $S_CODE $S_ID $S_TOKEN
  S_CODE="E32$1$(openssl rand -hex 2 | tr a-f A-F)"
  local saved=$E2E_TOKEN; as -
  call POST /auth/signup "$(jq -nc --arg c "$S_CODE" '{companyCode:$c, companyName:("บริษัท " + $c), taxId:"", vatRegistered:false,
    branch:{code:"HQ", name:"สำนักงานใหญ่", branchNo:"00000", address:"กรุงเทพฯ", phone:""},
    owner:{name:"เจ้าของ", username:"owner", email:("owner@" + ($c | ascii_downcase) + ".e2e.test"), password:"owner1234"}, planId:"pro"}')"
  S_TOKEN=$(jq -r .token <<<"$BODY"); S_ID=$(sql "SELECT id FROM dt_companies WHERE code = '$S_CODE'"); E2E_TOKEN=$saved
}

std_company E32
login "$CODE" cash1 pass1234; T_CASH1=$TOKEN
as -
call POST /platform/auth/login '{"username":"platform","password":"platform"}'
T_P=$(jq -r .token <<<"$BODY")
P_NAME=$(jq -r .admin.name <<<"$BODY")

case_id 32.1 "token ร้าน / ไม่มี token"
call GET /platform/companies
expect_status 401
expect_msg "เซสชันผู้ให้บริการหมดอายุ กรุณาเข้าสู่ระบบใหม่"
as "$T_OWNER"
call GET /platform/companies
expect_status 401
expect_msg "เซสชันผู้ให้บริการหมดอายุ กรุณาเข้าสู่ระบบใหม่"

case_id 32.2 "รายการ / รายละเอียด"
as "$T_P"
call @api2 GET /platform/companies
expect_status 200
check "ทุกบริษัท (รวมที่ลบแล้ว)" "$(sql "SELECT count(*) || ' ' || count(*) FILTER (WHERE deleted_at IS NOT NULL) FROM dt_companies")" \
  "$(num length) $(jq -r '[.[] | select(.deletedAt != null)] | length' <<<"$BODY")"
call GET "/platform/companies/$TENANT"
expect_status 200
check "usage / openAmount / owner / status" "2 5 3 0 owner คุณเจ้าของ trial" \
  "$(num .usage.branches) $(num .usage.users) $(num .usage.terminals) $(num .openAmount) $(num .owner.username) $(num .owner.name) $(num .status)"
check "ไม่มี passwordHash" 0 "$(grep -ciE 'passwordhash|\$2a\$' <<<"$BODY" || true)"
call GET /platform/companies/99999999
expect_status 404
expect_msg "ไม่พบบริษัท"

case_id 32.3 "เปลี่ยนแพ็กเกจ"
call PUT "/platform/companies/$TENANT/plan" '{"planId":"standard"}'
expect_status 200
expect_json .planId standard
check "audit (actor platform, user_id null)" "plan_change:platform:null:$P_NAME:$CODE:ผู้ให้บริการเปลี่ยนเป็นแพ็กเกจ Standard" "$(audit_last "$TENANT" plan_change)"
call PUT "/platform/companies/$TENANT/plan" '{"planId":"gold"}'
expect_status 400
expect_msg "ไม่มีแพ็กเกจนี้"
expect_sql "SELECT plan_id FROM dt_companies WHERE id = $TENANT" standard

case_id 32.4 "ระงับ / เปิดคืน"
call POST "/platform/companies/$TENANT/suspend" '{"note":"  "}'
expect_status 400
expect_msg "กรุณากรอกเหตุผลที่ระงับ"
call POST "/platform/companies/$TENANT/suspend" '{"note":" ละเมิดข้อตกลง "}'
expect_status 200
check "active / reason / suspendNote" "false platform ละเมิดข้อตกลง" "$(num .active) $(num .suspendedReason) $(num .suspendNote)"
expect_sql "SELECT active || ':' || suspended_reason || ':' || suspend_note FROM dt_companies WHERE id = $TENANT" "false:platform:ละเมิดข้อตกลง"
check "audit company_suspend" "company_suspend:platform:null:$P_NAME:$CODE:ละเมิดข้อตกลง" "$(audit_last "$TENANT" company_suspend)"
call POST "/platform/companies/$TENANT/suspend" '{"note":"ซ้ำ"}'
expect_status 400
expect_msg "บริษัทนี้ถูกระงับอยู่แล้ว"
login "$CODE" cash1 pass1234
expect_status 401
expect_msg "บริษัทนี้ถูกผู้ให้บริการระงับการใช้งาน — เจ้าของบริษัทติดต่อผู้ให้บริการ"
as "$T_CASH1"
call GET /me
expect_status 401
as "$T_P"
call @api2 POST "/platform/companies/$TENANT/reactivate"
expect_status 200
expect_json '[.active, .suspendedReason, "[" + .suspendNote + "]"] | map(tostring) | join(" ")' "true null []"   # mock: suspendNote ?? ''
expect_sql "SELECT active || ':' || coalesce(suspended_reason, '-') || ':' || coalesce(suspend_note, '-') FROM dt_companies WHERE id = $TENANT" "true:-:-"
check "audit company_reactivate" "company_reactivate:platform:null:$P_NAME:$CODE:" "$(audit_last "$TENANT" company_reactivate)"
call POST "/platform/companies/$TENANT/reactivate"
expect_status 400
expect_msg "บริษัทนี้ใช้งานอยู่แล้ว"
login "$CODE" cash1 pass1234
expect_status 200
# บริษัทค้างชำระ
signup OD; OD=$S_ID; OD_CODE=$S_CODE
sql "UPDATE dt_companies SET trial_ends_on = current_date - 40, next_invoice_on = current_date + 10 WHERE id = $OD;
  INSERT INTO dt_billing_invoices (company_id, invoice_no, plan_id, period_start, period_end, amount, issued_on, due_date, status)
  VALUES ($OD, 'SUB-$ym-6$((RANDOM % 900 + 100))', 'pro', current_date - 20, current_date + 9, 3490, current_date - 20, current_date - 13, 'open')" >/dev/null
login "$OD_CODE" owner owner1234
expect_status 401   # catch-up ระงับ overdue
expect_sql "SELECT active || ':' || suspended_reason FROM dt_companies WHERE id = $OD" "false:overdue"
call POST "/platform/companies/$OD/reactivate"
expect_status 400
expect_msg "บริษัทค้างชำระ — บันทึกรับชำระใบแจ้งหนี้แทน"
call POST "/platform/companies/$OD/suspend" '{"note":"ระงับซ้อน"}'
expect_status 400
expect_msg "บริษัทนี้ถูกระงับอยู่แล้ว"
# บริษัทรอลบ
signup DL; DL=$S_ID; DL_CODE=$S_CODE
as "$S_TOKEN"
call POST /company/deletion "{\"companyCode\":\"$DL_CODE\",\"password\":\"owner1234\",\"reason\":\"\"}"
as "$T_P"
call POST "/platform/companies/$DL/reactivate"
expect_status 400
expect_msg "บริษัทอยู่ระหว่างรอลบข้อมูล — ให้เจ้าของบริษัทยกเลิกคำขอลบ"

case_id 32.5 "ตั้งรหัส owner"
E2E_EXTRA_SECRETS="newowner325"
call PUT "/platform/companies/$TENANT/owner-password" '{"password":"12"}'
expect_status 400
expect_msg "รหัสผ่านต้องมีอย่างน้อย 4 ตัว"
call PUT "/platform/companies/$TENANT/owner-password" '{"password":"newowner325"}'
expect_status 200
check "body ว่าง" "" "$BODY"
login "$CODE" owner newowner325
expect_status 200
login "$CODE" owner owner1234
expect_status 401
check "audit user_password" "user_password:platform:null:$P_NAME:owner:ผู้ให้บริการตั้งรหัสผ่านใหม่ให้เจ้าของบริษัท" "$(audit_last "$TENANT" user_password)"

case_id 32.6 "รับชำระใบค้าง"
INV_ID=$(sql "SELECT id FROM dt_billing_invoices WHERE company_id = $OD"); INV_NO=$(sql "SELECT invoice_no FROM dt_billing_invoices WHERE company_id = $OD")
call GET "/platform/companies/$OD"
check "ก่อนรับชำระ: openAmount / status" "3490 suspended" "$(num .openAmount) $(num .status)"
call @api2 POST "/platform/invoices/$INV_ID/pay"
expect_status 200
check "เปิดใช้งานเอง, openAmount 0" "true null 0" "$(num .active) $(num .suspendedReason) $(num .openAmount)"
expect_sql "SELECT status || ':' || (paid_at IS NOT NULL) FROM dt_billing_invoices WHERE id = $INV_ID" "paid:true"
check "audit invoice_paid" "invoice_paid:platform:null:$P_NAME:$INV_NO:฿3490" "$(audit_last "$OD" invoice_paid)"
login "$OD_CODE" owner owner1234
expect_status 200
call POST "/platform/invoices/$INV_ID/pay"
expect_status 400
expect_msg "ใบแจ้งหนี้นี้ชำระแล้ว"
call POST /platform/invoices/99999999/pay
expect_status 404
expect_msg "ไม่พบใบแจ้งหนี้"

case_id 32.7 "/platform/outbox"
call GET /platform/outbox
expect_status 404
expect_msg "ไม่มี endpoint GET /platform/outbox"
as -

finish
