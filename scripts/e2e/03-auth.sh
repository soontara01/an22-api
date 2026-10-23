#!/usr/bin/env bash
# ข้อ 3 — login ร้าน / session / throttle (ตัวนับใช้ร่วมข้าม instance) (docs/e2e-test-plan.md)
# DEMO: อ่านอย่างเดียว (3.1 admin, 3.7 somying); 3.13 ล็อกบัญชี platform แล้วลบแถวตัวนับของบัญชีนั้นคืน (ข้อ 32–33 ใช้ต่อได้)
E2E_STEP=3
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

WRONG="รหัสบริษัท ชื่อผู้ใช้ หรือรหัสผ่านไม่ถูกต้อง"
TOO_MANY="พยายามบ่อยเกินไป กรุณารอสักครู่แล้วลองใหม่"
ip_base="10.3.$((RANDOM % 200 + 20))"   # IP คงที่ของ case ที่นับต่อ IP (ต่างกันทุกรอบที่รัน)
throttle_row() { # value → "scope:failures:locked(นาทีที่เหลือ)" หรือว่าง
  sql "SELECT scope || ':' || failures || ':' || coalesce(round(extract(epoch FROM locked_until - now()) / 60)::text, '-')
    FROM dt_login_throttle WHERE key_hash = decode('$(hmac "$1")', 'hex')"
}

std_company E3
as -

case_id 3.1 "login DEMO ตัดช่องว่าง / ตัวพิมพ์"
before=$(sql "SELECT count(*) FROM dt_audit_log WHERE tenant_id = 1 AND action = 'login' AND user_id = 1 AND actor_kind = 'user'")
call @api1 POST /auth/login '{"companyCode":" demo ","username":" Admin ","password":"admin"}'
expect_status 200
DEMO_TOKEN=$(jq -r .token <<<"$BODY")
expect_json '.token | split(".") | length' 3
expect_json '[.user.username, .user.role, .user.allBranches] | join(" ")' "admin owner true"
expect_json .company.code DEMO
expect_json '.branches | length' 2
check "ไม่มี passwordHash / password ใน body" 0 "$(grep -ciE 'passwordhash|"password"|\$2a\$' <<<"$BODY" || true)"
expect_sql "SELECT count(*) - $before FROM dt_audit_log WHERE tenant_id = 1 AND action = 'login' AND user_id = 1 AND actor_kind = 'user'" 1

case_id 3.2 "token ข้าม instance"
as "$DEMO_TOKEN"
call @api2 GET /me
expect_status 200
me2=$(jq -cS 'del(.billing)' <<<"$BODY")
expect_json .user.id 1
call @api1 GET /me
check "/me ที่ api1 = ที่ api2" "$me2" "$(jq -cS 'del(.billing)' <<<"$BODY")"
as -

case_id 3.3 "login ผิดทุกแบบ ข้อความเดียว"
before=$(sql "SELECT count(*) FROM dt_audit_log WHERE action = 'login_failed'")
for body in \
  "{\"companyCode\":\"$CODE\",\"username\":\"owner\",\"password\":\"wrong\"}" \
  "{\"companyCode\":\"$CODE\",\"username\":\"ghost33\",\"password\":\"wrong\"}" \
  "{\"companyCode\":\"NOCOMPANY33\",\"username\":\"nocompany33\",\"password\":\"wrong\"}" \
  '{"companyCode":"","username":"","password":""}'; do
  call POST /auth/login "$body"
  expect_status 401
  expect_msg "$WRONG"
done
expect_sql "SELECT string_agg(coalesce(user_id::text, 'null') || ':' || user_name || ':' || actor_kind, ',' ORDER BY id)
  FROM dt_audit_log WHERE tenant_id = $TENANT AND action = 'login_failed'" "null:owner:user,null:ghost33:user"
expect_sql "SELECT count(*) - $before FROM dt_audit_log WHERE action = 'login_failed'" 2
expect_sql "SELECT count(*) FROM dt_audit_log WHERE user_name IN ('nocompany33', '')" 0
expect_sql "SELECT count(*) FILTER (WHERE length(key_hash) <> 32) || ':' || count(*) FILTER (WHERE scope NOT IN ('account', 'ip'))
  FROM dt_login_throttle" "0:0"
expect_sql "SELECT string_agg(column_name, ',' ORDER BY ordinal_position) FROM information_schema.columns
  WHERE table_name = 'dt_login_throttle' AND data_type IN ('text', 'character varying') AND column_name NOT LIKE '%_kind'" "scope"
check "ตัวนับ ghost33 (account) ไม่เก็บชื่อจริง แต่ตรง hmac" "account:1:-" "$(throttle_row "shop:$CODE:ghost33")"

case_id 3.4 "ผิด 5 ครั้งสลับ api1 / api2 → ล็อก"
for i in 1 2 3 4 5; do
  base=@api1; ((i % 2 == 0)) && base=@api2
  call "$base" POST /auth/login "{\"companyCode\":\"$CODE\",\"username\":\"cash2\",\"password\":\"wrong$i\"}"
  expect_status 401
done
login "$CODE" cash2 pass1234
expect_status 429
expect_msg "$TOO_MANY"
check "แถว account ของ cash2 (failures 5, ล็อก ~15 นาที)" "account:5:15" "$(throttle_row "shop:$CODE:cash2")"

case_id 3.5 "ผิด 30 ครั้ง IP เดียว (คนละบัญชี)"
ip="$ip_base.5"
for i in $(seq 1 30); do
  call POST /auth/login "{\"companyCode\":\"$CODE\",\"username\":\"nobody$i\",\"password\":\"x\"}" "X-Real-IP: $ip"
  [[ "$STATUS" == 401 ]] || fail "ครั้งที่ $i ได้ $STATUS"
done
pass "30 ครั้งแรก 401"
login "$CODE" owner owner1234 "X-Real-IP: $ip"
expect_status 429
expect_msg "$TOO_MANY"
check "แถว ip (failures 30, ล็อก ~15 นาที)" "ip:30:15" "$(throttle_row "ip:$ip")"
login "$CODE" owner owner1234 "X-Real-IP: $ip_base.50"
expect_status 200

case_id 3.6 "ผิด 4 ครั้งแล้วถูก"
ip="$ip_base.6"
for i in 1 2 3 4; do
  call POST /auth/login "{\"companyCode\":\"$CODE\",\"username\":\"mgr\",\"password\":\"wrong$i\"}" "X-Real-IP: $ip"
done
check "ก่อนถูก: แถว account mgr" "account:4:-" "$(throttle_row "shop:$CODE:mgr")"
login "$CODE" mgr pass1234 "X-Real-IP: $ip"
expect_status 200
check "แถว account ถูกลบ" "" "$(throttle_row "shop:$CODE:mgr")"
check "แถว ip คงอยู่" "ip:4:-" "$(throttle_row "ip:$ip")"

case_id 3.7 "ผู้ใช้ปิดใช้งาน (DEMO somying รหัสถูก)"
expect_sql "SELECT active FROM dt_users WHERE tenant_id = 1 AND username = 'somying'" f
login DEMO somying 1234
expect_status 401
expect_msg "$WRONG"

case_id 3.8 "บริษัทถูกระงับ / รอลบ"
setc() { sql "UPDATE dt_companies SET active = $1, suspended_reason = $2 WHERE id = $TENANT" >/dev/null; }
setc false "'overdue'"
login "$CODE" owner owner1234
expect_status 401
expect_msg "บริษัทนี้ถูกระงับการใช้งานเพราะค้างชำระค่าบริการ — เจ้าของบริษัทติดต่อผู้ให้บริการเพื่อชำระเงิน"
login "$CODE" owner wrong
expect_msg "$WRONG"
setc false "'platform'"
login "$CODE" boss pass1234
expect_status 401
expect_msg "บริษัทนี้ถูกผู้ให้บริการระงับการใช้งาน — เจ้าของบริษัทติดต่อผู้ให้บริการ"
login "$CODE" boss wrong
expect_msg "$WRONG"
setc false "'deletion'"
login "$CODE" boss pass1234
expect_status 401
expect_msg "บริษัทนี้อยู่ระหว่างรอลบข้อมูล — ติดต่อเจ้าของบริษัท"
login "$CODE" owner owner1234
expect_status 200
expect_json .user.role owner
setc true null
expect_sql "SELECT active || ':' || coalesce(suspended_reason, '-') FROM dt_companies WHERE id = $TENANT" "true:-"

case_id 3.9 "ปิด / เปิดผู้ใช้ขณะถือ token"
login "$CODE" cash1 pass1234
T_CASH1=$TOKEN
as "$T_OWNER"
call PUT "/users/$U_CASH1" '{"active":false}'
expect_status 200
as "$T_CASH1"
call @api1 GET /me
expect_status 401
expect_msg "เซสชันหมดอายุ กรุณาเข้าสู่ระบบใหม่"
as "$T_OWNER"
call PUT "/users/$U_CASH1" '{"active":true}'
expect_status 200
as "$T_CASH1"
call @api2 GET /me
expect_status 200
expect_json .user.username cash1

case_id 3.10 "เลื่อนเป็น admin ขณะถือ token"
call GET /users
expect_status 403
expect_msg "จัดการผู้ใช้ได้เฉพาะผู้ดูแลระบบ"
as "$T_OWNER"
call PUT "/users/$U_CASH1" '{"role":"admin"}'
expect_status 200
as "$T_CASH1"
call GET /users
expect_status 200
as "$T_OWNER"
call PUT "/users/$U_CASH1" '{"role":"user"}'
expect_status 200

case_id 3.11 "X-Branch-Id"
call POST /branches '{"code":"OLD","name":"สาขาเก่า","branchNo":"00002","address":"กรุงเทพฯ","phone":"","receiptFooter":"","active":true}'
expect_status 200
BR_OLD=$(jq -r .id <<<"$BODY")
call PUT "/branches/$BR_OLD" '{"active":false}'
expect_status 200
demo_branch=$(sql "SELECT min(id) FROM dt_branches WHERE tenant_id = 1")
for t in "$T_CASH1 $BR_BN สาขาที่ไม่มีสิทธิ์" "$T_OWNER $BR_OLD สาขาปิด" "$T_OWNER $demo_branch สาขาบริษัทอื่น" "$T_OWNER abc ไม่ใช่ตัวเลข"; do
  set -- $t
  as "$1"
  call GET /products - "X-Branch-Id: $2"
  expect_status 403
  expect_msg "ไม่มีสิทธิ์ทำงานในสาขานี้"
done
as "$T_CASH1"
call GET /products - "X-Branch-Id: $BR_HQ"
expect_status 200
call GET /me - "X-Branch-Id: $BR_BN"
expect_status 200
expect_json .user.username cash1

case_id 3.12 "POST /auth/verify"
ip="$ip_base.12"
boss_before=$(throttle_row "shop:$CODE:boss" | cut -d: -f2)   # 3.8 login boss รหัสผิดไปแล้ว 1 ครั้ง
as "$T_CASH1"
call POST /auth/verify '{"username":"Boss","password":"pass1234"}' "X-Real-IP: $ip"
expect_status 200
expect_json '[.user.username, (.user | has("passwordHash"))] | join(" ")' "boss false"
expect_json 'keys | join(",")' user
call POST /auth/verify '{"username":"boss","password":"wrong"}' "X-Real-IP: $ip"
expect_status 400
expect_msg "ชื่อผู้ใช้หรือรหัสผ่านไม่ถูกต้อง"
as "$T_OWNER"
call PUT "/users/$U_MGR" '{"active":false}'
expect_status 200
as "$T_CASH1"
call POST /auth/verify '{"username":"mgr","password":"pass1234"}' "X-Real-IP: $ip"
expect_status 400
expect_msg "ชื่อผู้ใช้หรือรหัสผ่านไม่ถูกต้อง"
check "นับ throttle: account boss +1 (ก่อนหน้า ${boss_before:-0})" "account:$(( ${boss_before:-0} + 1 )):-" "$(throttle_row "shop:$CODE:boss")"
check "นับ throttle: account mgr" "account:1:-" "$(throttle_row "shop:$CODE:mgr")"
check "นับ throttle: ip" "ip:2:-" "$(throttle_row "ip:$ip")"
as -

case_id 3.13 "platform login"
call @api1 POST /platform/auth/login '{"username":"platform","password":"platform"}'
expect_status 200
T_PLATFORM=$(jq -r .token <<<"$BODY")
expect_json 'keys | join(",")' "admin,token"
expect_json '.admin.username' platform
check "ไม่มี passwordHash ใน body" 0 "$(grep -ciE 'passwordhash|\$2a\$' <<<"$BODY" || true)"
call POST /platform/auth/login '{"username":"platform","password":"wrong"}'
expect_status 401
expect_msg "ชื่อผู้ใช้หรือรหัสผ่านไม่ถูกต้อง"
for i in 2 3 4 5; do
  base=@api1; ((i % 2 == 0)) && base=@api2
  call "$base" POST /platform/auth/login "{\"username\":\"platform\",\"password\":\"wrong$i\"}"
  expect_status 401
done
call POST /platform/auth/login '{"username":"platform","password":"platform"}'
expect_status 429
expect_msg "$TOO_MANY"
check "แถว account platform (failures 5, ล็อก ~15 นาที)" "account:5:15" "$(throttle_row "platform:platform")"
as "$T_PLATFORM"
call @api2 GET /platform/me
expect_status 200
expect_json .username platform
as -
# คืนสภาพ: ปลดล็อกบัญชี platform ให้ข้อ 32–33 (ตกลงกับผู้ใช้)
sql "DELETE FROM dt_login_throttle WHERE key_hash = decode('$(hmac "platform:platform")', 'hex')" >/dev/null
call POST /platform/auth/login '{"username":"platform","password":"platform"}'
expect_status 200

finish
