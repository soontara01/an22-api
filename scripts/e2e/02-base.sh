#!/usr/bin/env bash
# ข้อ 2 — โครงพื้นฐาน 2 instance + load balancer (docs/e2e-test-plan.md)
# 2.4 / 2.5 หยุด pgbouncer / restart postgres ชั่วคราว — 5xx / log ERROR ที่ตั้งใจนับเฉพาะช่วงนั้น
E2E_STEP=2
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

# raw OUT METHOD URL [curl args…] → OUT.status / OUT.body / OUT.time (ใช้ยิงพร้อมกันใน background)
raw() {
  local out=$1 method=$2 url=$3
  shift 3
  curl -s -m 90 -o "$out.body" -w '%{http_code} %{time_total}' -X "$method" -H "X-Real-IP: $(rand_ip)" "$@" "$url" >"$out.meta" || true
  cut -d' ' -f1 "$out.meta" >"$out.status"
  cut -d' ' -f2 "$out.meta" >"$out.time"
}
started() { docker inspect -f '{{.State.StartedAt}} {{.RestartCount}}' "an22-multi-$1-1"; }
wait_ready() { # วินาทีสูงสุด → จำนวนวินาทีที่รอจน api1 + api2 พร้อม (หรือ timeout)
  local i
  for i in $(seq 0 "$1"); do
    ready "$API1" && ready "$API2" && { echo "$i"; return; }
    sleep 1
  done
  echo timeout
}
log_line() { # requestId → บรรทัด access ของ request นั้น (api1 + api2)
  { logs api1 "$E2E_SINCE"; logs api2 "$E2E_SINCE"; } | grep '"type":"access"' | grep -F "\"requestId\":\"$1\""
}

case_id 2.1 "healthz / readyz ทุกตัว"
for base in "$API1" "$API2" "$LB"; do
  for p in healthz readyz; do
    raw "$E2E_WORK/h" GET "$base/$p"
    check "GET $base/$p" '200 {"status":"ok"}' "$(cat "$E2E_WORK/h.status") $(jq -c . "$E2E_WORK/h.body")"
  done
done

case_id 2.2 "lb สลับ api1 / api2"
declare -A NAME_OF
for svc in api1 api2; do
  NAME_OF["$(docker inspect -f '{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}' "an22-multi-$svc-1"):8080"]=$svc
done
seq_names=""
for _ in 1 2 3 4 5 6; do
  call GET /healthz
  seq_names+="${NAME_OF[$(header X-Upstream)]:-?$(header X-Upstream)} "
done
seq_names=${seq_names% }
echo "     X-Upstream: $seq_names"
alternating=yes
read -ra arr <<<"$seq_names"
for i in 1 2 3 4 5; do [[ "${arr[$i]}" != "${arr[$((i - 1))]}" ]] || alternating=no; done
check "ลำดับ upstream สลับทุกครั้ง" yes "$alternating"
check "upstream ที่เห็น" "api1 api2" "$(tr ' ' '\n' <<<"$seq_names" | sort -u | tr '\n' ' ' | sed 's/ $//')"

case_id 2.3 "เขียนที่ api1 อ่านที่ api2"
std_company E2
as "$T_OWNER"
call @api1 POST /categories '{"code":"LB23","name":"หมวดข้าม instance","parentId":null,"active":true}'
expect_status 200
cat_id=$(jq -r .id <<<"$BODY")
call @api2 GET /categories
expect_status 200
expect_json "[.[] | select(.id == $cat_id) | .code] | join(\",\")" LB23
expect_sql "SELECT count(*) FROM dt_categories WHERE tenant_id = $TENANT AND code = 'LB23'" 1

case_id 2.4 "หยุด pgbouncer"
before1=$(started api1)
before2=$(started api2)
W_FROM=$(now_utc)
"${COMPOSE[@]}" stop pgbouncer >/dev/null 2>&1
auth=(-H "Authorization: Bearer $T_OWNER")
raw "$E2E_WORK/r1" GET "$API1/readyz" &
raw "$E2E_WORK/r2" GET "$API2/readyz" &
raw "$E2E_WORK/z1" GET "$API1/healthz" &
raw "$E2E_WORK/z2" GET "$API2/healthz" &
raw "$E2E_WORK/d1" GET "$API1/categories" "${auth[@]}" &
raw "$E2E_WORK/d2" GET "$API2/categories" "${auth[@]}" &
wait
for n in 1 2; do
  check "api$n /readyz" '503 {"message":"ระบบยังไม่พร้อม กรุณาลองใหม่"}' "$(cat "$E2E_WORK/r$n.status") $(jq -c . "$E2E_WORK/r$n.body" 2>/dev/null)"
  echo "     api$n /readyz ใช้ $(cat "$E2E_WORK/r$n.time") วิ"
  check "api$n /healthz" '200 {"status":"ok"}' "$(cat "$E2E_WORK/z$n.status") $(jq -c . "$E2E_WORK/z$n.body" 2>/dev/null)"
  check "api$n /healthz ตอบเร็ว (< 2 วิ)" yes "$(awk -v t="$(cat "$E2E_WORK/z$n.time")" 'BEGIN{print (t < 2) ? "yes" : "no"}')"
  check "api$n GET /categories (ใช้ DB)" '500 {"message":"ระบบขัดข้อง กรุณาลองใหม่"}' "$(cat "$E2E_WORK/d$n.status") $(jq -c . "$E2E_WORK/d$n.body" 2>/dev/null)"
  echo "     api$n GET /categories ใช้ $(cat "$E2E_WORK/d$n.time") วิ"
done
"${COMPOSE[@]}" start pgbouncer >/dev/null 2>&1
waited=$(wait_ready 90)
check "เปิด pgbouncer คืน → /readyz 200 ทั้งสองตัว (รอ ${waited} วิ)" yes "$([[ $waited != timeout ]] && echo yes || echo no)"
for base in @api1 @api2; do
  call "$base" GET /categories
  expect_status 200
done
check "api1 ไม่ถูก restart" "$before1" "$(started api1)"
check "api2 ไม่ถูก restart" "$before2" "$(started api2)"

case_id 2.5 "restart postgres"
"${COMPOSE[@]}" restart db >/dev/null 2>&1
waited=$(wait_ready 120)
check "หลัง postgres ขึ้นใหม่ /readyz 200 เอง (รอ ${waited} วิ)" yes "$([[ $waited != timeout ]] && echo yes || echo no)"
codes=""
for i in 1 2 3 4 5 6 7 8; do
  base=@api1; ((i % 2 == 0)) && base=@api2
  call "$base" GET /categories
  codes+="$STATUS "
done
check "request หลัง restart 8 ตัว (api1 / api2)" "200 200 200 200 200 200 200 200 " "$codes"
check "api1 ไม่ถูก restart" "$before1" "$(started api1)"
check "api2 ไม่ถูก restart" "$before2" "$(started api2)"
W_TO=$(now_utc)
win=$({ logs api1 "$W_FROM" "$W_TO"; logs api2 "$W_FROM" "$W_TO"; })
w5xx=$(grep '"type":"access"' <<<"$win" | grep -cE '"status":5[0-9][0-9]' || true)
werr=$(grep -c '"level":"ERROR"' <<<"$win" || true)
check "5xx ใน access log ช่วง 2.4–2.5 (= GET /categories 2 ตัวของ 2.4)" 2 "$w5xx"
echo "     log ERROR ช่วง 2.4–2.5: $werr บรรทัด — $(grep '"level":"ERROR"' <<<"$win" | jq -r '.logger_name + " " + (.message | .[0:60])' | sort | uniq -c | tr '\n' ';')"
check "log ERROR ช่วง 2.4–2.5 = access 5xx หรือ stack ของการต่อ DB ไม่ได้ (CannotCreateTransactionException) เท่านั้น" 0 \
  "$(grep '"level":"ERROR"' <<<"$win" | jq -r 'select(.type != "access" and ((.stack_trace // "") | test("CannotCreateTransactionException") | not)) | .message' | grep -c . || true)"
E2E_ALLOW_5XX=$w5xx
E2E_ALLOW_ERROR_LOG=$werr

case_id 2.6 "path / method / id / JSON / content-type / token ผิด"
as "$T_OWNER"
call GET /nope
expect_status 404
expect_msg "ไม่มี endpoint GET /nope"
call DELETE /healthz
expect_status 404
expect_msg "ไม่มี endpoint DELETE /healthz"
call GET /users/abc
expect_status 404
expect_msg "ไม่มี endpoint GET /users/abc"
call POST /categories '{"code":"BAD",'
expect_status 400
expect_msg "ข้อมูลที่ส่งมาไม่ถูกต้อง"
call POST /categories '{"code":"BAD","name":"x","parentId":null,"active":true}' "Content-Type: text/plain"
expect_status 400
expect_msg "ข้อมูลที่ส่งมาไม่ถูกต้อง"
as -
call GET /users
expect_status 401
expect_msg "เซสชันหมดอายุ กรุณาเข้าสู่ระบบใหม่"
expect_sql "SELECT count(*) FROM dt_categories WHERE tenant_id = $TENANT AND code = 'BAD'" 0

case_id 2.7 "X-Request-Id"
call GET /healthz - "X-Request-Id: e2e-req.1"
expect_header X-Request-Id e2e-req.1
call GET /healthz - "X-Request-Id: bad id!"
rid=$(header X-Request-Id)
check "X-Request-Id 'bad id!' → UUID ใหม่" yes \
  "$([[ $rid =~ ^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$ ]] && echo yes || echo "no ($rid)")"

case_id 2.8 "access log"
rid="e2e-2-8-$RANDOM$RANDOM"
as "$T_OWNER"; branch "$BR_HQ"; terminal 1
call GET /shifts/current - "X-Request-Id: $rid" "X-Real-IP: 10.2.88.8"
st=$STATUS
as -; branch -; terminal -
sleep 1
line=$(log_line "$rid")
check "จำนวนบรรทัด access ของ request (api1 + api2)" 1 "$(grep -c . <<<"$line" || true)"
check "field ของ access log" \
  "access GET /api/shifts/current $st number $TENANT $U_OWNER $BR_HQ 1 10.2.88.8 INFO" \
  "$(jq -r '[.type, .method, .path, (.status|tostring), (.durationMs|type), (.tenantId|tostring), (.userId|tostring),
     (.branchId|tostring), (.terminalId|tostring), .ip, .level] | join(" ")' <<<"$line" 2>/dev/null)"
rid_h="e2e-2-8h-$RANDOM$RANDOM"
call GET /healthz - "X-Request-Id: $rid_h"
call GET /readyz - "X-Request-Id: $rid_h"
sleep 1
check "health ไม่ขึ้นที่ระดับ info" 0 "$(log_line "$rid_h" | grep -c . || true)"

case_id 2.9 "mask body ใน log"
E2E_EXTRA_SECRETS="e2e-Secret-Pw-29 leak29@e2e.test 0105550123451 ผู้ซื้อลับ29 ที่อยู่ลับ29 REF29-4111"
rid="e2e-2-9-$RANDOM$RANDOM"
as "$T_OWNER"
call POST /users '{"username":"x","name":"ทดสอบ log","role":"user","active":true,"allBranches":true,"branchIds":[],
  "password":"e2e-Secret-Pw-29","email":"leak29@e2e.test","taxId":"0105550123451","buyer":{"name":"ผู้ซื้อลับ29"},
  "nested":{"Password":"e2e-Secret-Pw-29","address":"ที่อยู่ลับ29","list":[{"reference":"REF29-4111"}]}}' "X-Request-Id: $rid"
expect_status 400
expect_msg "ชื่อผู้ใช้ใช้ได้เฉพาะ a-z, 0-9, . _ - ยาว 3–30 ตัว"
as -
sleep 1
line=$(log_line "$rid")
check "requestBody ถูก mask" '*** *** *** *** *** *** *** x' \
  "$(jq -r '.requestBody | [.password, .email, .taxId, .buyer, .nested.Password, .nested.address, .nested.list[0].reference, .username] | join(" ")' <<<"$line" 2>/dev/null)"
check "responseBody ของ 4xx ถูก log" "ชื่อผู้ใช้ใช้ได้เฉพาะ a-z, 0-9, . _ - ยาว 3–30 ตัว" "$(jq -r .responseBody.message <<<"$line" 2>/dev/null)"
rid="e2e-2-9a-$RANDOM$RANDOM"
call POST /auth/login '{"companyCode":"'"$CODE"'","username":"owner","password":"e2e-Secret-Pw-29"}' "X-Request-Id: $rid"
expect_status 401
sleep 1
check "/auth/** ไม่ log body (request / response)" "null null" "$(jq -r '[(.requestBody|tostring), (.responseBody|tostring)] | join(" ")' <<<"$(log_line "$rid")" 2>/dev/null)"
check "ไม่ log header (key Authorization / X-Real-IP / X-Branch-Id, ค่า Bearer …)" 0 \
  "$({ logs api1 "$E2E_SINCE"; logs api2 "$E2E_SINCE"; } | grep -ciE '"(authorization|x-real-ip|x-branch-id|x-terminal-id)"|bearer [a-z0-9]' || true)"

case_id 2.10 "role ของ DB"
expect_sql "SELECT string_agg(rolname || ':super=' || rolsuper || ':bypassrls=' || rolbypassrls, ',' ORDER BY rolname)
  FROM pg_roles WHERE rolname IN ('an22_app', 'an22_platform')" "an22_app:super=false:bypassrls=false,an22_platform:super=false:bypassrls=true"
expect_sql "SELECT count(*) FROM pg_class c JOIN pg_roles r ON r.oid = c.relowner WHERE r.rolname IN ('an22_app', 'an22_platform')" 0
for role in an22_app an22_platform; do
  conf=$(sql "SELECT string_agg(x, ',' ORDER BY x) FROM pg_db_role_setting s JOIN pg_roles r ON r.oid = s.setrole, unnest(s.setconfig) x
    WHERE r.rolname = '$role'")
  echo "     $role: $conf"
  check "$role statement_timeout / idle_in_transaction_session_timeout" "statement_timeout=30s idle_in_transaction_session_timeout=60s" \
    "$(tr ',' '\n' <<<"$conf" | grep -oE '^statement_timeout=.*$' | head -1) $(tr ',' '\n' <<<"$conf" | grep -oE '^idle_in_transaction_session_timeout=.*$' | head -1)"
done

case_id 2.11 "log start"
for svc in api1 api2; do
  check "$svc 'flyway validate: ผ่าน (FLYWAY_MIGRATE=false)' (start ครั้งเดียว)" 1 \
    "$(logs "$svc" 0 | grep -c 'flyway validate: ผ่าน (FLYWAY_MIGRATE=false)' || true)"
done

finish
