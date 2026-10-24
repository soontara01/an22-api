#!/usr/bin/env bash
# ข้อ 8 — สาขา + เพดานแพ็กเกจ (docs/e2e-test-plan.md)
# 8.1–8.3 = std_company (pro); 8.4–8.6 = บริษัท starter จาก signup (สาขา 1 / ผู้ใช้ 5 / เครื่อง 2 — std_company ใช้ไม่ได้เพราะมี 2 สาขา)
E2E_STEP=8
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

COMPANY_ADMIN="ทำได้เฉพาะผู้ดูแลระบบระดับบริษัท (ทุกสาขา)"
branch_body() { # code name branchNo [active]
  jq -nc --arg c "$1" --arg n "$2" --arg no "$3" --argjson a "${4:-true}" '{code:$c, name:$n, branchNo:$no, address:"กรุงเทพฯ", phone:"", receiptFooter:"", active:$a}'
}

std_company E8
login "$CODE" boss pass1234; T_BOSS=$TOKEN
login "$CODE" mgr pass1234; T_MGR=$TOKEN
login "$CODE" cash1 pass1234; T_CASH1=$TOKEN

case_id 8.1 "POST /branches ตามสิทธิ์"
as "$T_CASH1"
call POST /branches "$(branch_body CM "สาขาเชียงใหม่" 00002)"
expect_status 403
expect_msg "จัดการสาขาได้เฉพาะผู้ดูแลระบบ"
as "$T_MGR"
call POST /branches "$(branch_body CM "สาขาเชียงใหม่" 00002)"
expect_status 403
expect_msg "$COMPANY_ADMIN"
call PUT "/branches/$BR_HQ" '{"name":"แก้โดย mgr"}'
expect_status 403
expect_msg "$COMPANY_ADMIN"
as "$T_BOSS"
call POST /branches "$(branch_body " cm " "สาขาเชียงใหม่" 00002)"
expect_status 200
BR_CM=$(jq -r .id <<<"$BODY")
expect_json '[.code, .branchNo, .active] | map(tostring) | join(" ")' "CM 00002 true"
expect_sql "SELECT action || ':' || user_id || ':' || branch_id || ':' || ref_label || ':' || detail FROM dt_audit_log
  WHERE tenant_id = $TENANT AND action = 'branch_change' ORDER BY id DESC LIMIT 1" "branch_change:$U_BOSS:$BR_CM:CM:เพิ่มสาขา สาขาเชียงใหม่"
call @api2 GET "/branches/$BR_CM"
expect_status 200

case_id 8.2 "ค่าผิด"
count=$(sql "SELECT count(*) FROM dt_branches WHERE tenant_id = $TENANT")
while IFS='|' read -r c n no msg; do
  call POST /branches "$(branch_body "$c" "$n" "$no")"
  expect_status 400
  expect_msg "$msg"
done <<'EOF'
B|สาขาบี|00009|รหัสสาขาต้องเป็น A-Z หรือ 0-9 ยาว 2–10 ตัว
B-1|สาขาบี|00009|รหัสสาขาต้องเป็น A-Z หรือ 0-9 ยาว 2–10 ตัว
ABCDEFGHIJK|สาขาบี|00009|รหัสสาขาต้องเป็น A-Z หรือ 0-9 ยาว 2–10 ตัว
bn|สาขาบี|00009|รหัสสาขานี้มีอยู่แล้ว
XX|สาขาบี|123|เลขที่สาขาต้องเป็นตัวเลข 5 หลัก (สำนักงานใหญ่ = 00000)
XX|สาขาบี|0000a|เลขที่สาขาต้องเป็นตัวเลข 5 หลัก (สำนักงานใหญ่ = 00000)
XX|สาขาบี|00001|เลขที่สาขานี้มีอยู่แล้ว
XX| |00009|กรุณากรอกชื่อสาขา
EOF
call PUT "/branches/$BR_CM" '{"code":"HQ"}'
expect_status 400
expect_msg "รหัสสาขานี้มีอยู่แล้ว"
expect_sql "SELECT count(*) FROM dt_branches WHERE tenant_id = $TENANT" "$count"

case_id 8.3 "ปิดสาขาสุดท้าย / ปิดสาขาที่มีกะเปิด"
branch "$BR_HQ"; terminal 1
call POST /shifts/open '{"openingFloat":0,"openingDenominations":[]}'
expect_status 200
branch -; terminal -
call PUT "/branches/$BR_HQ" '{"active":false}'
expect_status 400
expect_msg "สาขา HQ มีกะที่เปิดอยู่ ปิดใช้งานไม่ได้"
call PUT "/branches/$BR_BN" '{"active":false}'
expect_status 200
call PUT "/branches/$BR_CM" '{"active":false}'
expect_status 200
expect_json .active false
call PUT "/branches/$BR_HQ" '{"active":false}'
expect_status 400
expect_msg "ต้องมีสาขาที่ใช้งานอยู่อย่างน้อย 1 สาขา"
expect_sql "SELECT string_agg(code || ':' || active, ',' ORDER BY code) FROM dt_branches WHERE tenant_id = $TENANT" "BN:false,CM:false,HQ:true"
expect_sql "SELECT detail FROM dt_audit_log WHERE tenant_id = $TENANT AND action = 'branch_change' ORDER BY id DESC LIMIT 1" "แก้สาขา สาขาเชียงใหม่ (ปิดใช้งาน)"
as -

# ---------- starter ----------
S_CODE="E8S$(openssl rand -hex 2 | tr a-f A-F)"
call POST /auth/signup "$(jq -nc --arg c "$S_CODE" '{companyCode:$c, companyName:"บริษัท starter", taxId:"", vatRegistered:false,
  branch:{code:"HQ", name:"สำนักงานใหญ่", branchNo:"00000", address:"กรุงเทพฯ", phone:""},
  owner:{name:"เจ้าของ starter", username:"owner", email:("owner@" + ($c | ascii_downcase) + ".e2e.test"), password:"owner1234"}, planId:"starter"}')"
[[ "$STATUS" == 200 ]] || { fail "signup starter → $STATUS $BODY"; finish; exit 1; }
T_S=$(jq -r .token <<<"$BODY")
S_HQ=$(jq -r '.branches[0].id' <<<"$BODY")
S=$(sql "SELECT id FROM dt_companies WHERE code = '$S_CODE'")
pass "signup $S_CODE (starter, tenant $S)"
as "$T_S"

case_id 8.4 "starter: สาขา"
call POST /branches "$(branch_body BN "สาขาสอง" 00001)"
expect_status 403
expect_msg "แพ็กเกจ Starter ใช้สาขาได้ 1 สาขา — เปลี่ยนแพ็กเกจได้ที่เมนูบัญชีบริษัท"
call POST /branches "$(branch_body BN "สาขาสอง" 00001 false)"
expect_status 200
S_BN=$(jq -r .id <<<"$BODY")
call PUT "/branches/$S_BN" '{"active":true}'
expect_status 403
expect_msg "แพ็กเกจ Starter ใช้สาขาได้ 1 สาขา — เปลี่ยนแพ็กเกจได้ที่เมนูบัญชีบริษัท"
call PUT "/branches/$S_BN" '{"name":"สาขาสอง (แก้ชื่อ)"}'
expect_status 200   # แก้สาขาที่ปิดอยู่โดยไม่เปิด = ไม่นับ
expect_sql "SELECT active || ':' || name FROM dt_branches WHERE id = $S_BN" "false:สาขาสอง (แก้ชื่อ)"

case_id 8.5 "starter: ผู้ใช้ที่ใช้งาน"
user_body() { jq -nc --arg u "$1" --argjson a "${2:-true}" --argjson b "$S_HQ" '{username:$u, name:("ผู้ใช้ " + $u), email:"", role:"user", active:$a,
  allBranches:false, branchIds:[$b], password:"pass1234"}'; }
for u in user1 user2 user3 user4; do
  call POST /users "$(user_body $u)"
  expect_status 200
done
call POST /users "$(user_body user5)"
expect_status 403
expect_msg "แพ็กเกจ Starter ใช้ผู้ใช้ที่ใช้งานได้ 5 คน — เปลี่ยนแพ็กเกจได้ที่เมนูบัญชีบริษัท"
call POST /users "$(user_body user5 false)"
expect_status 200
U5=$(jq -r .id <<<"$BODY")
call PUT "/users/$U5" '{"active":true}'
expect_status 403
expect_msg "แพ็กเกจ Starter ใช้ผู้ใช้ที่ใช้งานได้ 5 คน — เปลี่ยนแพ็กเกจได้ที่เมนูบัญชีบริษัท"
expect_sql "SELECT count(*) FILTER (WHERE active) || ':' || count(*) FILTER (WHERE NOT active) FROM dt_users WHERE tenant_id = $S" "5:1"

case_id 8.6 "starter: เครื่อง POS ที่ใช้งาน"
term_body() { jq -nc --argjson id "$1" --argjson a "${2:-true}" --argjson b "$S_HQ" '{id:$id, name:("เครื่อง " + ($id|tostring)), posId:"", branchId:$b, active:$a}'; }
for t in 1 2; do
  call POST /pos-terminals "$(term_body $t)"
  expect_status 200
done
call POST /pos-terminals "$(term_body 3)"
expect_status 403
expect_msg "แพ็กเกจ Starter ใช้เครื่อง POS ที่ใช้งานได้ 2 เครื่อง — เปลี่ยนแพ็กเกจได้ที่เมนูบัญชีบริษัท"
call POST /pos-terminals "$(term_body 3 false)"
expect_status 200
call PUT /pos-terminals/3 "$(term_body 3 true)"
expect_status 403
expect_msg "แพ็กเกจ Starter ใช้เครื่อง POS ที่ใช้งานได้ 2 เครื่อง — เปลี่ยนแพ็กเกจได้ที่เมนูบัญชีบริษัท"
expect_sql "SELECT string_agg(id || ':' || active, ',' ORDER BY id) FROM dt_pos_terminals WHERE tenant_id = $S" "1:true,2:true,3:false"
as -

finish
