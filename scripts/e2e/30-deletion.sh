#!/usr/bin/env bash
# ข้อ 30 — ขอลบข้อมูลบริษัท (docs/e2e-test-plan.md)
# 30.5 ตั้งใบแจ้งหนี้ค้างเกินผ่อนผันด้วย SQL (บริษัทที่ 2)
E2E_STEP=30
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

today=$(TZ=Asia/Bangkok date +%F)
plus30=$(TZ=Asia/Bangkok date -d "$today +30 day" +%F)
ym=$(TZ=Asia/Bangkok date +%Y%m)
std_company E30
login "$CODE" boss pass1234; T_BOSS=$TOKEN
num() { jq -r "$1 | if type == \"number\" then (. + 0 | tostring) else tostring end" <<<"$BODY"; }
del_body() { jq -nc --arg c "$1" --arg p "$2" --arg r "${3:-}" '{companyCode:$c, password:$p, reason:$r}'; }
company_row() { sql "SELECT active || ':' || coalesce(suspended_reason, '-') || ':' || (deletion_requested_at IS NOT NULL) || ':' || coalesce(deletion_requested_by, '-')
  || ':' || coalesce(deletion_reason, '-') || ':' || coalesce(purge_after::text, '-') FROM dt_companies WHERE id = $1"; }

case_id 30.1 "ไม่มีคำขอ"
as "$T_BOSS"
call GET /company/deletion
expect_status 403
expect_msg "ขอลบข้อมูลบริษัทได้เฉพาะเจ้าของบริษัท"
call POST /company/deletion "$(del_body "$CODE" pass1234)"
expect_status 403
as "$T_OWNER"
call GET /company/deletion
check "GET ไม่มีคำขอ → 200 ว่าง" "200 null" "$STATUS ${BODY:-null}"
call DELETE /company/deletion
expect_status 400
expect_msg "ไม่มีคำขอลบข้อมูล"

case_id 30.2 "POST"
while IFS='|' read -r body msg; do
  call POST /company/deletion "$body"
  expect_status 400
  expect_msg "$msg"
done <<EOF
$(del_body WRONG owner1234)|พิมพ์รหัสบริษัทให้ตรงเพื่อยืนยัน
$(del_body "$CODE" "")|กรุณากรอกรหัสผ่านเพื่อยืนยัน
$(del_body "$CODE" wrong-pass)|รหัสผ่านไม่ถูกต้อง
EOF
check "ยังไม่มีคำขอ" "true:-:false:-:-:-" "$(company_row "$TENANT")"
call @api2 POST /company/deletion "$(del_body " ${CODE,,} " owner1234 " ย้ายไประบบอื่น ")"
expect_status 200
check "purgeAfter / requestedBy / reason" "$plus30 คุณเจ้าของ ย้ายไประบบอื่น" "$(num .purgeAfter) $(num .requestedBy) $(num .reason)"
check "dt_companies" "false:deletion:true:คุณเจ้าของ:ย้ายไประบบอื่น:$plus30" "$(company_row "$TENANT")"
expect_sql "SELECT action || ':' || user_id || ':' || detail FROM dt_audit_log WHERE tenant_id = $TENANT AND action = 'deletion_request'" \
  "deletion_request:$U_OWNER:ลบได้ตั้งแต่ $plus30 — ย้ายไประบบอื่น"
call POST /company/deletion "$(del_body "$CODE" owner1234)"
expect_status 400
expect_msg "ขอลบข้อมูลไว้แล้ว"
call GET /company/deletion
expect_json .purgeAfter "$plus30"

case_id 30.3 "ระหว่างรอลบ"
login "$CODE" boss pass1234
expect_status 401
expect_msg "บริษัทนี้อยู่ระหว่างรอลบข้อมูล — ติดต่อเจ้าของบริษัท"
as "$T_BOSS"
call GET /me
expect_status 401
as "$T_OWNER"
call GET /me
expect_status 200
expect_json .company.deletionPurgeAfter "$plus30"
for p in /products /users /categories "/sales?from=$today&to=$today"; do
  call GET "$p"
  check "owner GET $p → 403" "403 บริษัทอยู่ระหว่างรอลบข้อมูล — ดาวน์โหลดข้อมูลหรือยกเลิกคำขอลบได้ที่เมนูบัญชีบริษัท" "$STATUS $(jq -r .message <<<"$BODY")"
done
call POST /categories '{"code":"X","name":"x","parentId":null,"active":true}'
expect_status 403
call @api2 GET /company/export
expect_status 200
call GET "/audit-log?from=$today&to=$today"
expect_status 200
call GET /company/subscription
expect_status 200
login "$CODE" owner owner1234
expect_status 200   # owner ยัง login ได้

case_id 30.4 "ยกเลิก"
call DELETE /company/deletion
expect_status 200
check "dt_companies กลับปกติ" "true:-:false:-:-:-" "$(company_row "$TENANT")"
expect_sql "SELECT action || ':' || user_id FROM dt_audit_log WHERE tenant_id = $TENANT AND action = 'deletion_cancel'" "deletion_cancel:$U_OWNER"
login "$CODE" boss pass1234
expect_status 200
as "$T_BOSS"
call @api2 GET /products
expect_status 200
as "$T_OWNER"
call GET /products
expect_status 200

case_id 30.5 "ยกเลิกขณะมีใบค้างเกินผ่อนผัน"
B_CODE="E30B$(openssl rand -hex 2 | tr a-f A-F)"
as -
call POST /auth/signup "$(jq -nc --arg c "$B_CODE" '{companyCode:$c, companyName:"บริษัทค้างชำระ", taxId:"", vatRegistered:false,
  branch:{code:"HQ", name:"สำนักงานใหญ่", branchNo:"00000", address:"กรุงเทพฯ", phone:""},
  owner:{name:"เจ้าของ B", username:"owner", email:("owner@" + ($c | ascii_downcase) + ".e2e.test"), password:"owner1234"}, planId:"pro"}')"
T_B=$(jq -r .token <<<"$BODY")
B=$(sql "SELECT id FROM dt_companies WHERE code = '$B_CODE'")
as "$T_B"
call POST /company/deletion "$(del_body "$B_CODE" owner1234)"
expect_status 200
sql "UPDATE dt_companies SET trial_ends_on = current_date - 30, next_invoice_on = current_date + 10 WHERE id = $B;
  INSERT INTO dt_billing_invoices (company_id, invoice_no, plan_id, period_start, period_end, amount, issued_on, due_date, status)
  VALUES ($B, 'SUB-$ym-7$((RANDOM % 900 + 100))', 'pro', current_date - 20, current_date + 10, 3490, current_date - 20, current_date - 13, 'open')" >/dev/null
call DELETE /company/deletion
expect_status 200
check "ถูกระงับ overdue ทันที (billing ตัดสินใหม่)" "false:overdue:false:-:-:-" "$(company_row "$B")"
login "$B_CODE" owner owner1234
expect_status 401
expect_msg "บริษัทนี้ถูกระงับการใช้งานเพราะค้างชำระค่าบริการ — เจ้าของบริษัทติดต่อผู้ให้บริการเพื่อชำระเงิน"
as -

finish
