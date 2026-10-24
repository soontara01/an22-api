#!/usr/bin/env bash
# ข้อ 9 — ผู้ใช้ + โอนความเป็นเจ้าของ (docs/e2e-test-plan.md)
E2E_STEP=9
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

std_company E9
login "$CODE" boss pass1234; T_BOSS=$TOKEN
login "$CODE" mgr pass1234; T_MGR=$TOKEN
login "$CODE" cash1 pass1234; T_CASH1=$TOKEN
login "$CODE" cash2 pass1234; T_CASH2=$TOKEN
user_body() { # username [jq แก้]
  jq -nc --arg u "$1" --argjson hq "$BR_HQ" '{username:$u, name:("ผู้ใช้ " + $u), email:"", role:"user", active:true, allBranches:false,
    branchIds:[$hq], password:"pass1234"}' | jq -c "${2:-.}"
}
audit_last() { sql "SELECT action || ':' || user_id || ':' || ref_label || ':' || detail FROM dt_audit_log WHERE tenant_id = $TENANT AND action = '$1'
  ORDER BY id DESC LIMIT 1"; }

case_id 9.1 "GET /users ตามสิทธิ์"
as "$T_CASH1"
call GET /users
expect_status 403
expect_msg "จัดการผู้ใช้ได้เฉพาะผู้ดูแลระบบ"
call GET "/users/$U_CASH1"
expect_status 403
as "$T_MGR"
call GET /users
expect_status 200
expect_json 'length' 5
check "ไม่มีรหัสผ่าน / hash ใน body" 0 "$(grep -ciE 'password|\$2a\$' <<<"$BODY" || true)"

case_id 9.2 "สร้างผิดกฎ"
as "$T_BOSS"
users=$(sql "SELECT count(*) FROM dt_users WHERE tenant_id = $TENANT")
while IFS='|' read -r edit msg; do
  call POST /users "$(user_body newbie "$edit")"
  expect_status 400
  expect_msg "$msg"
done <<EOF
.username = " "|กรุณากรอกชื่อผู้ใช้
.username = "ab"|ชื่อผู้ใช้ใช้ได้เฉพาะ a-z, 0-9, . _ - ยาว 3–30 ตัว
.username = "ไทย"|ชื่อผู้ใช้ใช้ได้เฉพาะ a-z, 0-9, . _ - ยาว 3–30 ตัว
.username = "CASH1"|ชื่อผู้ใช้นี้มีอยู่แล้ว
.email = "bad@"|รูปแบบอีเมลไม่ถูกต้อง
.email = "boss@${CODE,,}.e2e.test"|อีเมลนี้มีผู้ใช้อื่นใช้แล้ว
.branchIds = []|กรุณาเลือกสาขาอย่างน้อย 1 สาขา
.branchIds = [99999999]|สาขาที่เลือกไม่มีอยู่หรือปิดใช้งานแล้ว
.role = "owner"|ตั้งเจ้าของบริษัทได้ด้วยการโอนความเป็นเจ้าของเท่านั้น
.role = "boss"|ข้อมูลที่ส่งมาไม่ถูกต้อง
.password = "123"|รหัสผ่านต้องมีอย่างน้อย 4 ตัว
EOF
expect_sql "SELECT count(*) FROM dt_users WHERE tenant_id = $TENANT" "$users"

case_id 9.3 "mgr (HQ) กับสาขาอื่น"
as "$T_MGR"
call POST /users "$(user_body bnuser ".branchIds = [$BR_BN]")"
expect_status 403
expect_msg "ให้สิทธิ์ทุกสาขาหรือสาขาที่ตัวเองไม่มีได้เฉพาะผู้ดูแลระบบระดับบริษัท"
call POST /users "$(user_body alluser '.allBranches = true | .branchIds = []')"
expect_status 403
expect_msg "ให้สิทธิ์ทุกสาขาหรือสาขาที่ตัวเองไม่มีได้เฉพาะผู้ดูแลระบบระดับบริษัท"
call PUT "/users/$U_CASH2" '{"name":"แก้โดย mgr"}'
expect_status 403
expect_msg "จัดการผู้ใช้ของสาขาอื่นหรือทุกสาขาได้เฉพาะผู้ดูแลระบบระดับบริษัท"
call PUT "/users/$U_CASH2/password" '{"password":"hack1234"}'
expect_status 403
expect_msg "จัดการผู้ใช้ของสาขาอื่นหรือทุกสาขาได้เฉพาะผู้ดูแลระบบระดับบริษัท"
call PUT "/users/$U_CASH1" '{"name":"แคชเชียร์หนึ่ง (mgr)"}'
expect_status 200   # สาขาเดียวกัน (HQ) แก้ได้

case_id 9.4 "กฎตัวเอง / owner"
as "$T_BOSS"
call DELETE "/users/$U_BOSS"
expect_msg "ลบบัญชีของตัวเองไม่ได้"
call PUT "/users/$U_BOSS" '{"role":"user"}'
expect_msg "ลดสิทธิ์ผู้ดูแลระบบของตัวเองไม่ได้"
call PUT "/users/$U_BOSS" '{"active":false}'
expect_msg "ปิดใช้งานบัญชีของตัวเองไม่ได้"
call DELETE "/users/$U_OWNER"
expect_msg "ลบเจ้าของบริษัทไม่ได้ — โอนความเป็นเจ้าของให้ผู้ใช้อื่นก่อน"
call PUT "/users/$U_OWNER" '{"active":false}'
expect_msg "ปิดใช้งานเจ้าของบริษัทไม่ได้ — โอนความเป็นเจ้าของให้ผู้ใช้อื่นก่อน"
call PUT "/users/$U_OWNER" '{"role":"admin"}'
expect_status 400
expect_msg "เปลี่ยนสิทธิ์ของเจ้าของบริษัทไม่ได้ — โอนความเป็นเจ้าของให้ผู้ใช้อื่นก่อน"
call PUT "/users/$U_CASH1" '{"role":"owner"}'
expect_status 400
expect_msg "เจ้าของบริษัทต้องมีอีเมล (ใช้กู้รหัสผ่านและแจ้งเรื่องการเก็บเงิน)"   # ลำดับของ mock: userIdentityError ก่อน userChangeError
call PUT "/users/$U_CASH1" "{\"role\":\"owner\",\"email\":\"cash1@${CODE,,}.e2e.test\"}"
expect_status 400
expect_msg "ตั้งเจ้าของบริษัทได้ด้วยการโอนความเป็นเจ้าของเท่านั้น"
expect_sql "SELECT string_agg(username || ':' || role || ':' || active, ',' ORDER BY id) FROM dt_users WHERE tenant_id = $TENANT AND username IN ('owner', 'boss', 'cash1')" \
  "owner:owner:true,boss:admin:true,cash1:user:true"

case_id 9.5 "สร้าง / แก้บางช่อง"
call POST /users "$(user_body " Cash3 " ".branchIds = [$BR_HQ, $BR_HQ]")"
expect_status 200
U_CASH3=$(jq -r .id <<<"$BODY")
expect_json '[.username, .role, (.branchIds | map(tostring) | join(",")), .allBranches] | map(tostring) | join(" ")' "cash3 user $BR_HQ false"
expect_sql "SELECT username || ':' || (password_hash LIKE '\$2a\$10\$%') || ':' || (SELECT count(*) FROM dt_user_branches b WHERE b.tenant_id = u.tenant_id AND b.user_id = u.id)
  FROM dt_users u WHERE id = $U_CASH3" "cash3:true:1"
check "audit user_change เพิ่ม" "user_change:$U_BOSS:cash3:เพิ่มผู้ใช้ — ผู้ใช้ทั่วไป · สาขา $BR_HQ" "$(audit_last user_change)"
call PUT "/users/$U_CASH3" '{"name":"แคชเชียร์สาม"}'
expect_status 200
expect_json '[.name, .username, .role, .email, (.branchIds | map(tostring) | join(",")), .active] | map(tostring) | join(" ")' "แคชเชียร์สาม cash3 user  $BR_HQ true"
call PUT "/users/$U_CASH3" "{\"allBranches\":true}"
expect_status 200
expect_sql "SELECT all_branches || ':' || (SELECT count(*) FROM dt_user_branches b WHERE b.tenant_id = u.tenant_id AND b.user_id = u.id) FROM dt_users u WHERE id = $U_CASH3" "true:0"
check "audit user_change แก้" "user_change:$U_BOSS:cash3:แก้ผู้ใช้ — ผู้ใช้ทั่วไป · ทุกสาขา" "$(audit_last user_change)"
call PUT "/users/$U_CASH3" "{\"allBranches\":false,\"branchIds\":[$BR_BN,$BR_HQ]}"
expect_status 200
expect_json '.branchIds | map(tostring) | join(",")' "$(printf '%s\n' "$BR_HQ" "$BR_BN" | sort -n | paste -sd,)"

case_id 9.6 "PUT /users/{id}/password"
call PUT "/users/$U_CASH3/password" '{"password":"12"}'
expect_status 400
expect_msg "รหัสผ่านต้องมีอย่างน้อย 4 ตัว"
E2E_EXTRA_SECRETS="newpass96"
call PUT "/users/$U_CASH3/password" '{"password":"newpass96"}'
expect_status 200
login "$CODE" cash3 newpass96
expect_status 200
login "$CODE" cash3 pass1234
expect_status 401
check "audit user_password" "user_password:$U_BOSS:cash3:ผู้ดูแลระบบตั้งรหัสผ่านใหม่ให้" "$(audit_last user_password)"

case_id 9.7 "ลบ cash2 (มีประวัติขาย + ลิงก์ reset ค้าง)"
as "$T_CASH2"; branch "$BR_BN"; terminal 3
call POST /shifts/open '{"openingFloat":0,"openingDenominations":[]}'
expect_status 200
shift2=$(jq -r .id <<<"$BODY")
call POST /sales "$(jq -nc --argjson p "$P_MS" --argjson m "$PM_CASH" '{items:[{productId:$p, factor:1, qty:1, serial:null}], freeSerials:[],
  payments:[{methodId:$m, amount:350, reference:""}], customer:"", expectedTotal:350}')"
expect_status 200
sale2=$(jq -r .id <<<"$BODY")
call POST "/shifts/$shift2/close" "{\"counts\":[{\"methodId\":$PM_CASH,\"counted\":350},{\"methodId\":$PM_CARD,\"counted\":0},
  {\"methodId\":$PM_QR,\"counted\":0},{\"methodId\":$PM_TRANSFER,\"confirmed\":true}],\"cashDenominations\":[],\"depositAmount\":0,\"note\":\"\"}"
expect_status 200
branch -; terminal -
sql "INSERT INTO dt_password_reset_tokens (company_id, user_id, token_hash, expires_at)
  VALUES ($TENANT, $U_CASH2, decode('$(hmac "e2e-9.7-$RANDOM")', 'hex'), now() + interval '1 hour')" >/dev/null
as "$T_BOSS"
call DELETE "/users/$U_CASH2"
expect_status 200
as "$T_CASH2"
call @api2 GET /me
expect_status 401
expect_sql "SELECT (SELECT count(*) FROM dt_users WHERE tenant_id = $TENANT AND id = $U_CASH2) || ':' ||
  (SELECT count(*) FROM dt_user_branches WHERE tenant_id = $TENANT AND user_id = $U_CASH2) || ':' ||
  (SELECT count(*) FROM dt_password_reset_tokens WHERE company_id = $TENANT AND user_id = $U_CASH2)" "0:0:0"
expect_sql "SELECT cashier FROM dt_sales WHERE tenant_id = $TENANT AND id = $sale2" "แคชเชียร์สอง"
as "$T_BOSS"
call GET "/sales/$sale2"
expect_status 200
expect_json .cashier "แคชเชียร์สอง"
check "audit ลบผู้ใช้" "user_change:$U_BOSS:cash2:ลบผู้ใช้" "$(audit_last user_change)"

case_id 9.8 "ลบผู้ใช้ที่มีกะเปิด (สังเกต — mock อนุญาต)"
as "$T_CASH1"; branch "$BR_HQ"; terminal 1
call POST /shifts/open '{"openingFloat":0,"openingDenominations":[]}'
expect_status 200
shift1=$(jq -r .id <<<"$BODY")
branch -; terminal -
as "$T_BOSS"
call DELETE "/users/$U_CASH1"
expect_status 200
expect_sql "SELECT status || ':' || cashier_user_id FROM dt_shifts WHERE tenant_id = $TENANT AND id = $shift1" "open:$U_CASH1"
call GET "/shifts/$shift1"
expect_status 200
expect_json .status open

case_id 9.9 "โอนความเป็นเจ้าของ"
call POST "/users" "$(user_body inactive1 ".active = false | .email = \"inactive1@${CODE,,}.e2e.test\"")"
expect_status 200
U_OFF=$(jq -r .id <<<"$BODY")
call POST /company/owner "{\"userId\":$U_BOSS,\"password\":\"pass1234\"}"
expect_status 403
expect_msg "โอนความเป็นเจ้าของได้เฉพาะเจ้าของบริษัท"
as "$T_OWNER"
while IFS='|' read -r target pw msg; do
  call POST /company/owner "{\"userId\":$target,\"password\":\"$pw\"}"
  expect_status 400
  expect_msg "$msg"
done <<EOF
$U_OFF|owner1234|ผู้รับโอนต้องเป็นผู้ใช้ที่ใช้งานอยู่
$U_MGR|owner1234|ผู้รับโอนต้องมีอีเมล — แก้ข้อมูลผู้ใช้ให้มีอีเมลก่อน
$U_OWNER|owner1234|เลือกผู้ใช้อื่นที่จะรับโอน
99999999|owner1234|ไม่พบผู้ใช้ที่จะรับโอน
$U_BOSS|wrong-pass|รหัสผ่านไม่ถูกต้อง
EOF
expect_sql "SELECT string_agg(username, ',') FROM dt_users WHERE tenant_id = $TENANT AND role = 'owner'" owner
call @api1 POST /company/owner "{\"userId\":$U_BOSS,\"password\":\"owner1234\"}"
expect_status 200
expect_json '[.[] | select(.role == "owner") | .username] | join(",")' boss
expect_sql "SELECT string_agg(username || ':' || role || ':' || all_branches, ',' ORDER BY id) FROM dt_users WHERE tenant_id = $TENANT AND username IN ('owner', 'boss')" \
  "owner:admin:true,boss:owner:true"
check "audit owner_transfer" "owner_transfer:$U_OWNER:boss:โอนความเป็นเจ้าของให้ คุณบอส" "$(audit_last owner_transfer)"
as "$T_BOSS"
call @api2 GET /me
expect_json .user.role owner
as "$T_OWNER"
call @api2 POST /company/owner "{\"userId\":$U_OWNER,\"password\":\"owner1234\"}"
expect_status 403
expect_msg "โอนความเป็นเจ้าของได้เฉพาะเจ้าของบริษัท"
as -

finish
