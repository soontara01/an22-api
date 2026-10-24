#!/usr/bin/env bash
# ข้อ 27 — audit log (docs/e2e-test-plan.md)
E2E_STEP=27
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

today=$(TZ=Asia/Bangkok date +%F)
std_company E27
login "$CODE" boss pass1234; T_BOSS=$TOKEN
login "$CODE" mgr pass1234; T_MGR=$TOKEN
login "$CODE" cash1 pass1234; T_CASH1=$TOKEN
num() { jq -r "$1 | if type == \"number\" then (. + 0 | tostring) else tostring end" <<<"$BODY"; }
range="from=$today&to=$today"
# เหตุการณ์เพิ่ม: ตัดสต็อกที่ HQ / BN โดย boss
as "$T_BOSS"
for b in "$BR_HQ" "$BR_BN"; do branch "$b"; call PUT "/products/$P_MS/stock" '{"delta":-1,"note":"ทดสอบ audit"}'; done
branch -
db_ids() { sql "SELECT coalesce(string_agg(id::text, ',' ORDER BY created_at DESC, id DESC), '') FROM dt_audit_log WHERE tenant_id = $TENANT $1"; }
api_ids() { jq -r '[.[].id] | map(tostring) | join(",")' <<<"$BODY"; }

case_id 27.1 "สิทธิ์ / ช่วงวันที่"
as "$T_CASH1"
call GET "/audit-log?$range"
expect_status 403
expect_msg "ดูประวัติการใช้งานได้เฉพาะผู้ดูแลระบบ"
as "$T_OWNER"
call GET /audit-log
expect_status 400
expect_msg "กรุณาเลือกช่วงวันที่ (ไม่เกิน 366 วัน)"
call GET "/audit-log?from=2025-01-01&to=$today"
expect_status 400
expect_msg "ช่วงวันที่ต้องไม่เกิน 366 วัน"

case_id 27.2 "owner ดูทั้งหมด"
call @api2 GET "/audit-log?$range"
expect_status 200
check "ทุกเหตุการณ์ของบริษัทวันนี้ ใหม่สุดก่อน (= DB)" "$(db_ids "")" "$(api_ids)"
echo "     $(num length) เหตุการณ์: $(jq -r '[.[].action] | group_by(.) | map("\(.[0])=\(length)") | join(" ")' <<<"$BODY")"
check "ไม่มีเหตุการณ์ของบริษัทอื่น" 0 "$(sql "SELECT count(*) FROM dt_audit_log WHERE id = ANY('{$(api_ids)}'::bigint[]) AND tenant_id <> $TENANT")"
check "field ของเหตุการณ์" "action,actorKind,at,branchId,detail,id,ref,terminalId,userId,userName" "$(jq -r '.[0] | keys | join(",")' <<<"$BODY")"

case_id 27.3 "ตัวกรอง / ค่าแปลก"
call GET "/audit-log?$range&action=price_change"
check "action=price_change" "$(db_ids "AND action = 'price_change'")" "$(api_ids)"
call GET "/audit-log?$range&userId=$U_BOSS"
check "userId=boss" "$(db_ids "AND user_id = $U_BOSS")" "$(api_ids)"
call GET "/audit-log?$range&branchId=$BR_BN"
check "branchId=BN" "$(db_ids "AND branch_id = $BR_BN")" "$(api_ids)"
call GET "/audit-log?$range&action=stock_out&branchId=$BR_HQ&userId=$U_BOSS"
check "กรองพร้อมกัน 3 ตัว" "$(db_ids "AND action = 'stock_out' AND branch_id = $BR_HQ AND user_id = $U_BOSS")" "$(api_ids)"
all=$(db_ids "")
big=$(printf '9%.0s' $(seq 1 5000))
for q in "action=nope" "userId=1.5" "userId=abc" "branchId=-0.5" "userId=$big"; do
  t0=$(date +%s%N)
  call GET "/audit-log?$range&$q"
  ms=$(( ($(date +%s%N) - t0) / 1000000 ))
  check "ค่าแปลก ${q:0:20} → ไม่กรอง (${ms}ms)" "200 $all" "$STATUS $(api_ids)"
done
for v in "${U_BOSS}e0" "${U_BOSS}.0" "${U_BOSS}0e-1"; do
  call GET "/audit-log?$range&userId=$v"
  check "userId=$v = id $U_BOSS (แบบ Number())" "$(db_ids "AND user_id = $U_BOSS")" "$(api_ids)"
done
call GET "/audit-log?$range&userId=1e2"
check "userId=1e2 = id 100" "$(db_ids "AND user_id = 100")" "$(api_ids)"

case_id 27.4 "mgr (admin HQ)"
as "$T_MGR"
call GET "/audit-log?$range"
expect_status 200
check "เฉพาะ branch_id = HQ" "$(db_ids "AND branch_id = $BR_HQ")" "$(api_ids)"
check "ไม่มีระดับบริษัท (branch null) / BN" 0 "$(jq -r --argjson hq "$BR_HQ" '[.[] | select(.branchId != $hq)] | length' <<<"$BODY")"
call GET "/audit-log?$range&branchId=$BR_BN"
check "mgr ขอ branchId=BN → ว่าง" "" "$(api_ids)"

case_id 27.5 "ระหว่างรอลบ owner ดู audit-log"
as "$T_OWNER"
call POST /company/deletion "{\"companyCode\":\"$CODE\",\"password\":\"owner1234\",\"reason\":\"ทดสอบ e2e ข้อ 27.5\"}"
expect_status 200
call GET "/audit-log?$range&action=deletion_request"
expect_status 200
expect_json 'length' 1
as "$T_BOSS"
call GET "/audit-log?$range"
expect_status 401   # boss ถูกตัดระหว่างรอลบ
as "$T_OWNER"
call DELETE /company/deletion
expect_status 200
as -

finish
