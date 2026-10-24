#!/usr/bin/env bash
# ข้อ 5 — สมัครใช้งาน / ลืมรหัสผ่าน / ตั้งรหัสใหม่ (docs/e2e-test-plan.md)
# reset token สร้างเอง (สุ่ม + hmac + insert) เพราะ LoggingMailer ไม่ส่ง / ไม่ log ลิงก์; owner ปิดใช้งาน / หมดอายุ ตั้งด้วย SQL
E2E_STEP=5
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

CODE_TAKEN="รหัสบริษัทนี้ถูกใช้แล้ว"
TOO_MANY="พยายามบ่อยเกินไป กรุณารอสักครู่แล้วลองใหม่"
FORGOT="ถ้ารหัสบริษัทและอีเมลตรงกับเจ้าของบริษัท ระบบจะส่งลิงก์ตั้งรหัสผ่านใหม่ไปที่อีเมลนั้น (ใช้ได้ภายใน 1 ชั่วโมง)"
INVALID_LINK="ลิงก์ตั้งรหัสผ่านไม่ถูกต้อง หมดอายุ หรือใช้ไปแล้ว — ขอลิงก์ใหม่"
ip_base="10.5.$((RANDOM % 200 + 20))"
today=$(TZ=Asia/Bangkok date +%F)
plus() { TZ=Asia/Bangkok date -d "$today +$1 day" +%F; }
rand_code() { echo "E5$1$(openssl rand -hex 2 | tr a-f A-F)"; }

# signup_body CODE [jq แก้ payload] → JSON
signup_body() {
  jq -nc --arg c "$1" '{companyCode:$c, companyName:"บริษัท สมัครใหม่", taxId:"0105550123451", vatRegistered:true,
    branch:{code:"HQ", name:"สำนักงานใหญ่", branchNo:"00000", address:"กรุงเทพฯ", phone:""},
    owner:{name:"คุณเจ้าของ", username:"owner", email:("owner@" + ($c | gsub(" "; "") | ascii_downcase) + ".e2e.test"), password:"owner1234"},
    planId:"pro"}' | jq -c "${2:-.}"
}
new_token() { openssl rand -base64 32 | tr '+/' '-_' | tr -d '=\n'; }
insert_token() { # company user token [interval] → แถวใน dt_password_reset_tokens (expires_at = now() + interval)
  sql "INSERT INTO dt_password_reset_tokens (company_id, user_id, token_hash, expires_at)
    VALUES ($1, $2, decode('$(hmac "$3")', 'hex'), now() + interval '${4:-60 minutes}')" >/dev/null
}
reset() { # token password
  call POST /auth/reset-password "$(jq -nc --arg t "$1" --arg p "$2" '{token:$t, password:$p}')"
}

case_id 5.1 "signup"
raw_code=$(rand_code A)
M_CODE=$raw_code
M_EMAIL="owner@${raw_code,,}.e2e.test"
E2E_EXTRA_SECRETS="$M_EMAIL"
call POST /auth/signup "$(signup_body "  ${raw_code,,} " '.owner.username = " OWNER "')"
expect_status 200
expect_json '[.user.username, .user.role, .user.allBranches, .company.code] | join(" ")' "owner owner true $M_CODE"
T_M=$(jq -r .token <<<"$BODY")
M=$(sql "SELECT id FROM dt_companies WHERE code = '$M_CODE'")
M_OWNER=$(jq -r .user.id <<<"$BODY")
expect_sql "SELECT code || ':' || plan_id || ':' || active || ':' || trial_ends_on || ':' || next_invoice_on || ':' || coalesce(suspended_reason, '-')
  FROM dt_companies WHERE id = $M" "$M_CODE:pro:true:$(plus 14):$(plus 15):-"
expect_sql "SELECT name || ':' || tax_id || ':' || vat_registered || ':' || require_shift || ':' || exchange_days || ':' || staff_see_cost
  FROM dt_company_info WHERE tenant_id = $M" "บริษัท สมัครใหม่:0105550123451:true:true:7:false"
expect_sql "SELECT code || ':' || branch_no || ':' || receipt_footer || ':' || active FROM dt_branches WHERE tenant_id = $M" "HQ:00000:ขอบคุณที่ใช้บริการ:true"
expect_sql "SELECT username || ':' || role || ':' || all_branches || ':' || (password_hash LIKE '\$2a\$10\$%') || ':' || email
  FROM dt_users WHERE tenant_id = $M" "owner:owner:true:true:$M_EMAIL"
expect_sql "SELECT code || ':' || type || ':' || cash_rounding || ':' || close_check || ':' || sort_order FROM dt_payment_methods WHERE tenant_id = $M" \
  "CASH:cash:0.25:count:1"
expect_sql "SELECT action || ':' || user_id || ':' || user_name || ':' || actor_kind || ':' || detail FROM dt_audit_log WHERE tenant_id = $M" \
  "signup:$M_OWNER:คุณเจ้าของ:user:สมัครใช้งาน แพ็กเกจ Pro ทดลองใช้ถึง $(plus 14)"

case_id 5.2 "ผิดกฎทีละช่องตามลำดับ"
companies=$(sql "SELECT count(*) FROM dt_companies")
users=$(sql "SELECT count(*) FROM dt_users")
c=$(rand_code B)
while IFS='|' read -r edit msg; do
  call POST /auth/signup "$(signup_body "$c" "$edit")"
  expect_status 400
  expect_msg "$msg"
done <<'EOF'
.companyCode = ""|กรุณากรอกรหัสบริษัท
.companyCode = "A!"|รหัสบริษัทใช้ได้เฉพาะ A-Z, 0-9 ยาว 3–20 ตัว
.companyName = "  "|กรุณากรอกชื่อบริษัท
.taxId = "0105550123452"|เลขประจำตัวผู้เสียภาษีต้องเป็น 13 หลักที่ถูกต้อง
.branch.code = "H"|รหัสสาขาต้องเป็น A-Z หรือ 0-9 ยาว 2–10 ตัว
.branch.name = ""|กรุณากรอกชื่อสาขา
.owner.name = " "|กรุณากรอกชื่อเจ้าของบริษัท
.owner.email = ""|เจ้าของบริษัทต้องมีอีเมล (ใช้กู้รหัสผ่านและแจ้งเรื่องการเก็บเงิน)
.owner.password = "123"|รหัสผ่านต้องมีอย่างน้อย 4 ตัว
.planId = ""|กรุณาเลือกแพ็กเกจ
EOF
expect_sql "SELECT (count(*) - $companies) || ':' || ((SELECT count(*) FROM dt_users) - $users) FROM dt_companies" "0:0"

case_id 5.3 "รหัสซ้ำ / รหัสของบริษัทที่ purge แล้ว"
call POST /auth/signup "$(signup_body "${M_CODE,,}")"
expect_status 400
expect_msg "$CODE_TAKEN"
P_CODE=$(rand_code P)
call POST /auth/signup "$(signup_body "$P_CODE")"
expect_status 200
T_P=$(jq -r .token <<<"$BODY")
P=$(sql "SELECT id FROM dt_companies WHERE code = '$P_CODE'")
P_OWNER=$(jq -r .user.id <<<"$BODY")
P_TOKEN=$(new_token)          # ลิงก์ค้างของบริษัทที่จะ purge (ใช้ใน 5.9)
insert_token "$P" "$P_OWNER" "$P_TOKEN"
as "$T_P"
call POST /company/deletion "{\"companyCode\":\"$P_CODE\",\"password\":\"owner1234\",\"reason\":\"ทดสอบ e2e ข้อ 5.3\"}"
expect_status 200
as -
sql "UPDATE dt_companies SET purge_after = current_date WHERE id = $P" >/dev/null
call POST /platform/auth/login '{"username":"platform","password":"platform"}'
as "$(jq -r .token <<<"$BODY")"
call POST "/platform/companies/$P/purge"
expect_status 200
as -
expect_sql "SELECT (deleted_at IS NOT NULL) || ':' || (SELECT count(*) FROM dt_password_reset_tokens WHERE company_id = $P) FROM dt_companies WHERE id = $P" "true:0"
call POST /auth/signup "$(signup_body "$P_CODE")"
expect_status 400
expect_msg "$CODE_TAKEN"

case_id 5.4 "signup รหัสเดียวกันพร้อมกัน @api1 + @api2 (3 รอบ)"
for round in 1 2 3; do
  c=$(rand_code R$round)
  body=$(signup_body "$c")
  for n in 1 2; do
    base=$API1; ((n == 2)) && base=$API2
    curl -s -m 60 -o "$E2E_WORK/s$n.body" -w '%{http_code}' -X POST -H 'Content-Type: application/json' -H "X-Real-IP: $(rand_ip)" \
      --data-binary "$body" "$base/auth/signup" >"$E2E_WORK/s$n.status" &
  done
  wait
  tok=$(jq -r '.token // empty' "$E2E_WORK/s1.body" "$E2E_WORK/s2.body" 2>/dev/null)
  [[ -n "$tok" ]] && echo "$tok" >>"$E2E_TOKENS"
  result=$(for n in 1 2; do echo "$(cat "$E2E_WORK/s$n.status"):$(jq -r '.message // "ok"' "$E2E_WORK/s$n.body" 2>/dev/null)"; done | sort | tr '\n' ' ')
  check "รอบ $round: 200 1 ตัว + 400 รหัสซ้ำ 1 ตัว" "200:ok 400:$CODE_TAKEN " "$result"
  expect_sql "SELECT count(*) || ':' || (SELECT count(*) FROM dt_users u WHERE u.tenant_id = max(c.id)) FROM dt_companies c WHERE code = '$c'" "1:1"
done
expect_sql "SELECT count(*) FROM dt_companies c WHERE deleted_at IS NULL AND NOT EXISTS (SELECT 1 FROM dt_users u WHERE u.tenant_id = c.id)" 0
expect_sql "SELECT count(*) FROM dt_company_info i WHERE NOT EXISTS (SELECT 1 FROM dt_users u WHERE u.tenant_id = i.tenant_id)" 0

case_id 5.5 "signup 11 ครั้ง IP เดียว"
ip="$ip_base.55"
for i in $(seq 1 10); do
  call POST /auth/signup "$(signup_body "" )" "X-Real-IP: $ip"
  [[ "$STATUS" == 400 ]] || fail "ครั้งที่ $i ได้ $STATUS"
done
pass "10 ครั้งแรก 400 (ผิดกฎก็นับ)"
c=$(rand_code L)
call POST /auth/signup "$(signup_body "$c")" "X-Real-IP: $ip"
expect_status 429
expect_msg "$TOO_MANY"
expect_sql "SELECT count(*) FROM dt_companies WHERE code = '$c'" 0
call POST /auth/signup "$(signup_body "$c")" "X-Real-IP: $ip_base.56"
expect_status 200

case_id 5.6 "forgot-password ทุกกรณีตอบข้อความเดียว"
as "$T_M"
call POST /users "{\"username\":\"boss\",\"name\":\"คุณบอส\",\"email\":\"boss@${M_CODE,,}.e2e.test\",\"role\":\"admin\",\"active\":true,\"allBranches\":true,\"branchIds\":[],\"password\":\"pass1234\"}"
expect_status 200
as -
E2E_EXTRA_SECRETS+=" boss@${M_CODE,,}.e2e.test"
tokens() { sql "SELECT count(*) FROM dt_password_reset_tokens WHERE company_id = $M"; }
forgot() { call POST /auth/forgot-password "$(jq -nc --arg c "$1" --arg e "$2" '{companyCode:$c, email:$e}')" "${@:3}"; }
mail_from=$(now_utc)
forgot " ${M_CODE,,} " " ${M_EMAIL^^} "
expect_status 200
expect_json 'keys | join(",")' message
expect_msg "$FORGOT"
expect_sql "SELECT count(*) || ':' || min(length(token_hash)) || ':' || bool_and(used_at IS NULL) || ':' || min(user_id)
  || ':' || round(extract(epoch FROM min(expires_at) - now()) / 60) FROM dt_password_reset_tokens WHERE company_id = $M" "1:32:true:$M_OWNER:60"
for args in "$M_CODE|wrong@${M_CODE,,}.e2e.test|อีเมลผิด" "NOCOMPANY55|$M_EMAIL|ไม่มีบริษัท" "$M_CODE|boss@${M_CODE,,}.e2e.test|อีเมล admin ที่ไม่ใช่ owner"; do
  IFS='|' read -r c e label <<<"$args"
  forgot "$c" "$e"
  check "$label → 200 ข้อความเดียว" "200 $FORGOT" "$STATUS $(jq -r .message <<<"$BODY")"
done
sql "UPDATE dt_users SET active = false WHERE id = $M_OWNER AND tenant_id = $M" >/dev/null
forgot "$M_CODE" "$M_EMAIL"
check "owner ปิดใช้งาน → 200 ข้อความเดียว" "200 $FORGOT" "$STATUS $(jq -r .message <<<"$BODY")"
sql "UPDATE dt_users SET active = true WHERE id = $M_OWNER AND tenant_id = $M" >/dev/null
check "แถวลิงก์เพิ่มเฉพาะกรณีถูก" 1 "$(tokens)"
sleep 1
mail=$({ logs api1 "$mail_from"; logs api2 "$mail_from"; } | grep 'อีเมล (ยังไม่ส่งจริง)')
check "log อีเมล 1 บรรทัด: mask + หัวเรื่อง" \
  "1 อีเมล (ยังไม่ส่งจริง) ถึง o***@${M_CODE,,}.e2e.test เรื่อง \"ตั้งรหัสผ่านใหม่ — บริษัท $M_CODE\"" \
  "$(grep -c . <<<"$mail") $(jq -r .message <<<"$mail" | head -1)"
check "ไม่มีลิงก์ / token ใน log" 0 "$({ cat "$E2E_SAVED_LOGS"; logs api1 "$E2E_SINCE"; logs api2 "$E2E_SINCE"; } | grep -c 'reset-password?token' || true)"

case_id 5.7 "forgot ครั้งที่ 4 บริษัท + อีเมลเดียวกัน / ครั้งที่ 11 IP เดียว"
forgot "$M_CODE" "$M_EMAIL"
expect_status 200   # ครั้งที่ 3 (5.6 นับ 2 ครั้ง: ถูก + owner ปิด)
forgot "$M_CODE" "$M_EMAIL"
expect_status 429
expect_msg "$TOO_MANY"
ip="$ip_base.57"
for i in $(seq 1 10); do
  forgot "NOCOMPANY57" "x$i@e2e.test" "X-Real-IP: $ip"
  [[ "$STATUS" == 200 ]] || fail "ครั้งที่ $i ได้ $STATUS"
done
pass "10 ครั้งแรก 200"
forgot "NOCOMPANY57" "x11@e2e.test" "X-Real-IP: $ip"
expect_status 429
expect_msg "$TOO_MANY"

case_id 5.8 "reset ด้วย token ที่สร้างเอง"
R1=$(new_token)
E2E_EXTRA_SECRETS+=" $R1 newpass58"
insert_token "$M" "$M_OWNER" "$R1"
hash_before=$(sql "SELECT password_hash FROM dt_users WHERE id = $M_OWNER AND tenant_id = $M")
reset "$R1" newpass58
expect_status 200
check "body ว่าง" "" "$BODY"
login "$M_CODE" owner newpass58
expect_status 200
login "$M_CODE" owner owner1234
expect_status 401
expect_sql "SELECT (used_at IS NOT NULL) FROM dt_password_reset_tokens WHERE token_hash = decode('$(hmac "$R1")', 'hex')" t
check "password_hash เปลี่ยน" yes "$([[ $(sql "SELECT password_hash FROM dt_users WHERE id = $M_OWNER AND tenant_id = $M") != "$hash_before" ]] && echo yes || echo no)"
expect_sql "SELECT action || ':' || user_id || ':' || detail FROM dt_audit_log WHERE tenant_id = $M AND action = 'password_reset'" \
  "password_reset:$M_OWNER:ตั้งรหัสผ่านใหม่ด้วยลิงก์ทางอีเมล"

case_id 5.9 "ลิงก์ใช้ไม่ได้"
hash_before=$(sql "SELECT password_hash FROM dt_users WHERE id = $M_OWNER AND tenant_id = $M")
R_EXP=$(new_token); insert_token "$M" "$M_OWNER" "$R_EXP" "-1 minute"
R_OFF=$(new_token); insert_token "$M" "$M_OWNER" "$R_OFF"
for t in "$R1|ใช้ซ้ำ" "$(new_token)|token มั่ว" "|token ว่าง" "$R_EXP|หมดอายุ"; do
  IFS='|' read -r tok label <<<"$t"
  reset "$tok" newpass59
  check "$label" "400 $INVALID_LINK" "$STATUS $(jq -r .message <<<"$BODY")"
done
sql "UPDATE dt_users SET active = false WHERE id = $M_OWNER AND tenant_id = $M" >/dev/null
reset "$R_OFF" newpass59
check "owner ปิดใช้งาน" "400 $INVALID_LINK" "$STATUS $(jq -r .message <<<"$BODY")"
sql "UPDATE dt_users SET active = true WHERE id = $M_OWNER AND tenant_id = $M" >/dev/null
reset "$P_TOKEN" newpass59
check "บริษัท purge แล้ว" "400 $INVALID_LINK" "$STATUS $(jq -r .message <<<"$BODY")"
check "password_hash ไม่เปลี่ยน" yes "$([[ $(sql "SELECT password_hash FROM dt_users WHERE id = $M_OWNER AND tenant_id = $M") == "$hash_before" ]] && echo yes || echo no)"
expect_sql "SELECT count(*) FROM dt_password_reset_tokens WHERE company_id = $M AND used_at IS NOT NULL" 1
expect_sql "SELECT count(*) FROM dt_audit_log WHERE tenant_id = $M AND action = 'password_reset'" 1

case_id 5.10 "reset รหัสสั้น"
reset "$R_OFF" 12
expect_status 400
expect_msg "รหัสผ่านต้องมีอย่างน้อย 4 ตัว"
expect_sql "SELECT (used_at IS NULL) FROM dt_password_reset_tokens WHERE token_hash = decode('$(hmac "$R_OFF")', 'hex')" t
E2E_EXTRA_SECRETS+=" $R_OFF $R_EXP $P_TOKEN newpass510"
reset "$R_OFF" newpass510
expect_status 200
login "$M_CODE" owner newpass510
expect_status 200

finish
