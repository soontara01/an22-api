#!/usr/bin/env bash
# ข้อ 4 — token / JWT (สร้าง token เองด้วย secret ของ stack) (docs/e2e-test-plan.md)
# 4.6 recreate api1 / api2 3 รอบ (เพิ่ม kid 2 → ตัด kid 1 → คืน JWT_SECRET เดิม — ตกลงกับผู้ใช้)
E2E_STEP=4
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

EXPIRED="เซสชันหมดอายุ กรุณาเข้าสู่ระบบใหม่"
PLATFORM_EXPIRED="เซสชันผู้ให้บริการหมดอายุ กรุณาเข้าสู่ระบบใหม่"
me_with() { # token [@api] → STATUS / BODY ของ GET /me
  as "$1"
  call "${2:-@lb}" GET /me
  as -
}

std_company E4
login "$CODE" owner owner1234
T_A=$TOKEN

case_id 4.1 "แก้ payload / alg none / issuer ผิด"
head=$(cut -d. -f1 <<<"$T_A")
sig=$(cut -d. -f3 <<<"$T_A")
tampered=$(jwt_part "$T_A" 1 | jq -c '.sub = "999999"' | b64url)
me_with "$head.$tampered.$sig"
expect_status 401
expect_msg "$EXPIRED"
none_head=$(printf '{"alg":"none","typ":"JWT"}' | b64url)
me_with "$none_head.$(jwt_part "$T_A" 1 | b64url)."
expect_status 401
expect_msg "$EXPIRED"
me_with "$(jwt "$(shop_claims "$TENANT" "$U_OWNER" | jq -c '.iss = "evil"')")"
expect_status 401
expect_msg "$EXPIRED"
me_with "$(jwt "$(shop_claims "$TENANT" "$U_OWNER")")"
expect_status 200   # คู่เทียบ: claims เดียวกันแต่ issuer ถูก

case_id 4.2 "exp"
me_with "$(jwt "$(shop_claims "$TENANT" "$U_OWNER" -120)")"
expect_status 401
expect_msg "$EXPIRED"
check "token ปกติ exp − iat (วินาที) = 7 วัน" 604800 "$(jwt_part "$T_A" 1 | jq '.exp - .iat')"
check "claims ของ token ปกติ" "an22-api shop $U_OWNER $TENANT owner true" \
  "$(jwt_part "$T_A" 1 | jq -r '[.iss, (.aud | if type == "array" then .[0] else . end), .sub, .tenantId, .role, .allBranches] | join(" ")')"

case_id 4.3 "token ผิดฝั่ง"
call POST /platform/auth/login '{"username":"platform","password":"platform"}'
expect_status 200
T_PLATFORM=$(jq -r .token <<<"$BODY")
me_with "$T_PLATFORM"
expect_status 401
expect_msg "$EXPIRED"
as "$T_A"
call GET /platform/me
expect_status 401
expect_msg "$PLATFORM_EXPIRED"
as -

case_id 4.4 "tenantId = B, sub = ผู้ใช้ของ A"
B_CODE="E4B$(openssl rand -hex 2 | tr a-f A-F)"
call POST /auth/signup "$(jq -nc --arg c "$B_CODE" '{companyCode:$c, companyName:"บริษัท B", taxId:"", vatRegistered:false,
  branch:{code:"HQ", name:"สำนักงานใหญ่", branchNo:"00000", address:"กรุงเทพฯ", phone:""},
  owner:{name:"เจ้าของ B", username:"owner", email:("owner@" + ($c|ascii_downcase) + ".e2e.test"), password:"owner1234"}, planId:"pro"}')"
expect_status 200
T_B=$(jq -r .token <<<"$BODY")
B_TENANT=$(sql "SELECT id FROM dt_companies WHERE code = '$B_CODE'")
me_with "$(jwt "$(shop_claims "$B_TENANT" "$U_OWNER")")"
expect_status 401
expect_msg "$EXPIRED"
me_with "$(jwt "$(shop_claims "$B_TENANT" "$U_BOSS")")" @api2
expect_status 401

case_id 4.5 "ผู้ใช้ถูกลบ / บริษัทถูก purge"
login "$CODE" boss pass1234
T_BOSS=$TOKEN
me_with "$T_BOSS"
expect_status 200
as "$T_OWNER"
call DELETE "/users/$U_BOSS"
expect_status 200
me_with "$T_BOSS" @api1
expect_status 401
expect_msg "$EXPIRED"
as "$T_B"
call POST /company/deletion "{\"companyCode\":\"$B_CODE\",\"password\":\"owner1234\",\"reason\":\"ทดสอบ e2e ข้อ 4.5\"}"
expect_status 200
me_with "$T_B"
expect_status 200   # ระหว่างรอลบ owner ยังใช้ได้
sql "UPDATE dt_companies SET purge_after = current_date WHERE id = $B_TENANT" >/dev/null
as "$T_PLATFORM"
call POST "/platform/companies/$B_TENANT/purge"
expect_status 200
as -
expect_sql "SELECT (deleted_at IS NOT NULL) || ':' || (SELECT count(*) FROM dt_users WHERE tenant_id = $B_TENANT) FROM dt_companies WHERE id = $B_TENANT" "true:0"
me_with "$T_B" @api2
expect_status 401
expect_msg "$EXPIRED"

case_id 4.6 "หมุน kid"
orig=$(env_get JWT_SECRET)
new_secret=$(openssl rand -base64 32 | tr -d '\n')
E2E_EXTRA_SECRETS="$new_secret"
check "token เดิม header kid" 1 "$(jwt_part "$T_A" 0 | jq -r .kid)"
env_set JWT_SECRET "$orig,2:$new_secret"
recreate_api
pass "recreate api1 / api2 ด้วย JWT_SECRET=1:<เดิม>,2:<ใหม่>"
me_with "$T_A" @api1
expect_status 200
me_with "$T_A" @api2
expect_status 200
login "$CODE" owner owner1234
T_K2=$TOKEN
check "token ใหม่ header kid" 2 "$(jwt_part "$T_K2" 0 | jq -r .kid)"
me_with "$T_K2" @api1
expect_status 200
me_with "$T_K2" @api2
expect_status 200
env_set JWT_SECRET "2:$new_secret"
recreate_api
pass "recreate api1 / api2 ด้วย JWT_SECRET=2:<ใหม่> (ตัด kid 1)"
me_with "$T_A" @api1
expect_status 401
expect_msg "$EXPIRED"
me_with "$T_A" @api2
expect_status 401
me_with "$T_K2"
expect_status 200
# คืนค่าเดิม (ตกลงกับผู้ใช้) — token kid 1 กลับมาใช้ได้, kid 2 ใช้ไม่ได้
env_set JWT_SECRET "$orig"
recreate_api
pass "คืน JWT_SECRET เดิม + recreate"
check ".e2e/env JWT_SECRET กลับเป็นค่าเดิม" yes "$([[ $(env_get JWT_SECRET) == "$orig" ]] && echo yes || echo no)"
me_with "$T_A"
expect_status 200
me_with "$T_K2"
expect_status 401
for svc in api1 api2; do
  check "$svc 'flyway validate: ผ่าน' หลัง recreate" 1 "$(logs "$svc" 0 | grep -c 'flyway validate: ผ่าน (FLYWAY_MIGRATE=false)' || true)"
done

finish
