#!/usr/bin/env bash
# ข้อ 31 — การเก็บเงิน (billing) + job บน 2 instance (docs/e2e-test-plan.md)
# 31.1–31.4 job ปิด (catch-up ตอน login / me); 31.5–31.6 recreate api ด้วย JOBS_CRON ทุก 10 วิ แล้วคืนเป็น - (ปิด) ตอนจบ
E2E_STEP=31
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

today=$(TZ=Asia/Bangkok date +%F)
day() { TZ=Asia/Bangkok date -d "$today $1 day" +%F; }
thai() { echo "$(date -d "$1" +%-d)/$(date -d "$1" +%-m)/$(( $(date -d "$1" +%Y) + 543 ))"; }
num() { jq -r "$1 | if type == \"number\" then (. + 0 | tostring) else tostring end" <<<"$BODY"; }
invoices() { sql "SELECT coalesce(string_agg(invoice_no || ':' || plan_id || ':' || amount || ':' || period_start || ':' || due_date || ':' || status || ':' || created_by_kind, ',' ORDER BY period_start), '')
  FROM dt_billing_invoices WHERE company_id = $1"; }
due_now() { sql "UPDATE dt_companies SET trial_ends_on = current_date - 1, next_invoice_on = current_date WHERE id = $1" >/dev/null; }
signup() { # tag → $S_CODE $S_ID $S_TOKEN
  S_CODE="E31$1$(openssl rand -hex 2 | tr a-f A-F)"
  local saved=$E2E_TOKEN; as -
  call POST /auth/signup "$(jq -nc --arg c "$S_CODE" '{companyCode:$c, companyName:("บริษัท " + $c), taxId:"", vatRegistered:false,
    branch:{code:"HQ", name:"สำนักงานใหญ่", branchNo:"00000", address:"กรุงเทพฯ", phone:""},
    owner:{name:"เจ้าของ", username:"owner", email:("owner@" + ($c | ascii_downcase) + ".e2e.test"), password:"owner1234"}, planId:"pro"}')"
  S_TOKEN=$(jq -r .token <<<"$BODY"); S_ID=$(sql "SELECT id FROM dt_companies WHERE code = '$S_CODE'"); E2E_TOKEN=$saved
}
others_before=$(sql "SELECT count(*) FROM dt_billing_invoices")

std_company E31
A=$TENANT; A_CODE=$CODE
login "$CODE" boss pass1234; T_BOSS=$TOKEN
login "$CODE" cash1 pass1234; T_CASH1=$TOKEN

case_id 31.1 "(job ปิด) ถึงรอบแล้ว login → ออกใบ"
check "JOBS_CRON ของ stack = - (ปิด)" "-" "$(env_get JOBS_CRON)"
due_now "$A"
login "$A_CODE" owner owner1234
expect_status 200
INV1=$(sql "SELECT invoice_no FROM dt_billing_invoices WHERE company_id = $A")
check "ใบ SUB 1 ใบ ราคา pro ครบกำหนด +7 ผู้สร้าง system" "$INV1:pro:3490.00:$today:$(day 7):open:system" "$(invoices "$A")"
check "รูปแบบเลข SUB-YYYYMM-NNNN" yes "$([[ $INV1 =~ ^SUB-$(TZ=Asia/Bangkok date +%Y%m)-[0-9]{4}$ ]] && echo yes || echo "no ($INV1)")"
expect_sql "SELECT next_invoice_on FROM dt_companies WHERE id = $A" "$(TZ=Asia/Bangkok date -d "$today +1 month" +%F)"
login "$A_CODE" owner owner1234
check "login ซ้ำไม่ออกใบเพิ่ม" 1 "$(sql "SELECT count(*) FROM dt_billing_invoices WHERE company_id = $A")"

case_id 31.2 "/me หลังมีใบ / ค้างเกินกำหนด"
as "$T_BOSS"
call GET /me
check "info" "info ใบแจ้งหนี้ $INV1 ฿3,490 ครบกำหนดชำระ $(thai "$(day 7)")" "$(num .billing.level) $(num .billing.text)"
as "$T_CASH1"
call GET /me
check "staff ไม่เห็น" null "$(num .billing)"
sql "UPDATE dt_billing_invoices SET due_date = current_date - 2 WHERE company_id = $A" >/dev/null
as "$T_BOSS"
call @api2 GET /me
check "error ค้างชำระ (ผ่อนผัน 7 วัน)" "error ค้างชำระ $INV1 ฿3,490 (ครบกำหนด $(thai "$(day -2)")) — จะระงับการใช้งานหลังวันที่ $(thai "$(day 5)") (อีก 5 วัน)" \
  "$(num .billing.level) $(num .billing.text)"
call GET /company/subscription
expect_json .status past_due

case_id 31.3 "ย้อน next_invoice_on 3 เดือน → ออกครบ 3 รอบ ไม่ซ้ำ"
signup B; B=$S_ID; T_B=$S_TOKEN
sql "UPDATE dt_companies SET trial_ends_on = current_date - 100, next_invoice_on = (current_date + 15) - interval '3 months' WHERE id = $B" >/dev/null
as "$T_B"
call GET /me
echo "     /me → $STATUS (ใบเก่าสุดเลยผ่อนผัน → ระงับ overdue ได้)"
check "3 ใบ period ต่อเนื่อง" 3 "$(sql "SELECT count(*) FROM dt_billing_invoices WHERE company_id = $B")"
expect_sql "SELECT string_agg(period_start::text, ',' ORDER BY period_start) FROM dt_billing_invoices WHERE company_id = $B" \
  "$(sql "SELECT string_agg((((current_date + 15) - interval '3 months') + make_interval(months => g))::date::text, ',' ORDER BY g) FROM generate_series(0, 2) g")"
call GET /me
login "$S_CODE" owner owner1234
check "เรียกซ้ำ (me + login) ไม่ออกเพิ่ม" 3 "$(sql "SELECT count(*) FROM dt_billing_invoices WHERE company_id = $B")"
expect_sql "SELECT count(*) FROM pg_constraint WHERE conrelid = 'dt_billing_invoices'::regclass AND contype = 'u' AND pg_get_constraintdef(oid) LIKE '%company_id, period_start%'" 1
expect_sql "SELECT count(DISTINCT invoice_no) FROM dt_billing_invoices WHERE company_id = $B" 3

case_id 31.4 "พ้นผ่อนผัน → ระงับ"
sql "UPDATE dt_billing_invoices SET due_date = current_date - 8 WHERE company_id = $A" >/dev/null
login "$A_CODE" boss pass1234
expect_status 401
expect_msg "บริษัทนี้ถูกระงับการใช้งานเพราะค้างชำระค่าบริการ — เจ้าของบริษัทติดต่อผู้ให้บริการเพื่อชำระเงิน"
expect_sql "SELECT active || ':' || suspended_reason FROM dt_companies WHERE id = $A" "false:overdue"
as "$T_BOSS"
call @api2 GET /me
expect_status 401

case_id 31.5 "เปิด job ทั้ง 2 ตัว + 5 บริษัทถึงรอบ + login พร้อมกัน"
ids=(); codes=()
for i in 1 2 3 4 5; do signup "J$i"; ids+=("$S_ID"); codes+=("$S_CODE"); done
others_mid=$(sql "SELECT count(*) FROM dt_billing_invoices WHERE company_id NOT IN ($A, $B)")
# 31.6 เตรียม: ลิงก์ reset + ตัวนับ เก่า (> 1 วัน) / ใหม่
OLD_T=$(hmac "e2e-31-old-$RANDOM"); NEW_T=$(hmac "e2e-31-new-$RANDOM"); OLD_K=$(hmac "e2e-31-oldk-$RANDOM"); NEW_K=$(hmac "e2e-31-newk-$RANDOM")
owner_id=$(sql "SELECT id FROM dt_users WHERE tenant_id = ${ids[0]} AND role = 'owner'")
sql "INSERT INTO dt_password_reset_tokens (company_id, user_id, token_hash, expires_at) VALUES
  (${ids[0]}, $owner_id, decode('$OLD_T', 'hex'), now() - interval '2 days'), (${ids[0]}, $owner_id, decode('$NEW_T', 'hex'), now() + interval '1 hour');
  INSERT INTO dt_login_throttle (key_hash, scope, window_start, failures) VALUES (decode('$OLD_K', 'hex'), 'ip', now() - interval '2 days', 3), (decode('$NEW_K', 'hex'), 'ip', now(), 1)" >/dev/null
for id in "${ids[@]}"; do due_now "$id"; done
quiet=()   # ถึงรอบแต่ไม่มีใคร login → job ต้องออกใบเอง
for i in 1 2; do signup "Q$i"; quiet+=("$S_ID"); due_now "$S_ID"; done
env_set JOBS_CRON '*/10 * * * * *'
recreate_api
JOB_FROM=$(now_utc)
pass "recreate api1 / api2 ด้วย JOBS_CRON=*/10 * * * * *"
for i in 0 1 2 3 4; do
  base=$API1; ((i % 2)) && base=$API2
  for n in 1 2; do
    curl -s -o /dev/null -w '%{http_code}\n' -X POST -H 'Content-Type: application/json' -H "X-Real-IP: $(rand_ip)" \
      --data "{\"companyCode\":\"${codes[$i]}\",\"username\":\"owner\",\"password\":\"owner1234\"}" "$base/auth/login" >>"$E2E_WORK/logins" &
  done
done
wait
check "login พร้อมกัน 10 request" "10 200" "$(sort "$E2E_WORK/logins" | uniq -c | tr -s ' ' | sed 's/^ //')"
sleep 32
for i in 0 1 2 3 4; do
  check "${codes[$i]}: ใบเดียว" 1 "$(sql "SELECT count(*) FROM dt_billing_invoices WHERE company_id = ${ids[$i]}")"
done
for id in "${quiet[@]}"; do
  check "บริษัท #$id (ไม่มีใคร login): job ออกใบเอง 1 ใบ" "1 system" "$(sql "SELECT count(*) || ' ' || min(created_by_kind) FROM dt_billing_invoices WHERE company_id = $id")"
done
check "บริษัทอื่นไม่มีใบเพิ่มจาก job" "$others_mid" "$(sql "SELECT count(*) FROM dt_billing_invoices WHERE company_id NOT IN ($A, $B) AND NOT (company_id = ANY('{$(IFS=,; echo "${ids[*]},${quiet[*]}" | tr ' ' ,)}'::bigint[]))")"
check "ไม่มีใบซ้ำทั้ง stack (company + period_start)" 0 "$(sql "SELECT count(*) FROM (SELECT company_id, period_start FROM dt_billing_invoices GROUP BY 1, 2 HAVING count(*) > 1) d")"
JOB_TO=$(now_utc)
ran_total=0; skip_total=0
for svc in api1 api2; do
  L=$(logs "$svc" "$JOB_FROM" "$JOB_TO")
  ran=$(grep -c 'billing job: บริษัทที่เปลี่ยน' <<<"$L" || true); skip=$(grep -c 'billing job: instance อื่นกำลังทำ' <<<"$L" || true)
  ran_total=$((ran_total + ran)); skip_total=$((skip_total + skip))
  echo "     $svc: ทำงาน $ran รอบ · ข้าม $skip รอบ"
done
ticks=$({ logs api1 "$JOB_FROM" "$JOB_TO"; logs api2 "$JOB_FROM" "$JOB_TO"; } | grep 'billing job:' | jq -r '.["@timestamp"][0:18]' | sort -u | grep -c . || true)
per_tick=$({ logs api1 "$JOB_FROM" "$JOB_TO"; logs api2 "$JOB_FROM" "$JOB_TO"; } | grep 'billing job: บริษัทที่เปลี่ยน' | jq -r '.["@timestamp"][0:18]' | sort | uniq -c | awk '$1 > 1' | grep -c . || true)
echo "     รอบ (วินาทีที่มี log ของ job): $ticks · ทำงานรวม $ran_total · ข้ามรวม $skip_total"
check "มี job ทำงานจริงทุกรอบ" yes "$([[ $ran_total -ge 2 && $ran_total -ge $ticks ]] && echo yes || echo "no (ran $ran_total ticks $ticks)")"
check "ไม่มีรอบที่ทำงานจริงพร้อมกัน 2 ตัว (วินาทีเดียวกัน)" 0 "$per_tick"
changed=$({ logs api1 "$JOB_FROM" "$JOB_TO"; logs api2 "$JOB_FROM" "$JOB_TO"; } | grep 'billing job: บริษัทที่เปลี่ยน' | jq -r '.message' | sed -E 's/.*เปลี่ยน ([0-9]+) .*/\1/' | awk '{s += $1} END {print s + 0}')
check "job เปลี่ยนบริษัทรวม = 2 (บริษัทที่ไม่มีใคร login)" 2 "$changed"
echo "     log ของรอบที่เปลี่ยนบริษัท: $({ logs api1 "$JOB_FROM" "$JOB_TO"; logs api2 "$JOB_FROM" "$JOB_TO"; } | grep 'billing job: บริษัทที่เปลี่ยน' | jq -r '.message' | sort | uniq -c | tr '\n' ';')"

case_id 31.6 "job ล้างของหมดอายุ"
check "ลิงก์ reset: เก่าหาย ใหม่อยู่" "0 1" "$(sql "SELECT count(*) FROM dt_password_reset_tokens WHERE token_hash = decode('$OLD_T', 'hex')") $(sql "SELECT count(*) FROM dt_password_reset_tokens WHERE token_hash = decode('$NEW_T', 'hex')")"
check "ตัวนับ: เก่าหาย ใหม่อยู่" "0 1" "$(sql "SELECT count(*) FROM dt_login_throttle WHERE key_hash = decode('$OLD_K', 'hex')") $(sql "SELECT count(*) FROM dt_login_throttle WHERE key_hash = decode('$NEW_K', 'hex')")"

# คืน JOBS_CRON = - (ปิด) ให้ข้ออื่น
env_set JOBS_CRON -
recreate_api
pass "คืน JOBS_CRON=- + recreate"
check ".e2e/env JOBS_CRON" "-" "$(env_get JOBS_CRON)"
as -

finish
