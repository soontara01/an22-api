#!/usr/bin/env bash
# ข้อ 36 — เพดานขนาดคำตอบ (20,000 แถว) / statement_timeout → 503 (docs/e2e-test-plan.md)
# แถวสังเคราะห์ (บิลไม่มีบรรทัด / audit) ใส่ด้วย SQL ในวันที่อดีตวันเดียว (ไม่ปนข้อมูลจริง) แล้วลบทิ้งท้ายข้อ — ข้อ 38 ตรวจทั้ง DB
E2E_STEP=36
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

DAY=2025-06-15
std_company E36
login "$CODE" boss pass1234; T_BOSS=$TOKEN

# add_sales FROM TO — บิลสังเคราะห์ order_no E36-SYN-<n> (n = FROM..TO) สาขา HQ เวลา 12:00 ของ $DAY
add_sales() {
  sql "INSERT INTO dt_sales (tenant_id, order_no, sold_at, subtotal, total, status, branch_id)
    SELECT $TENANT, 'E36-SYN-' || g, TIMESTAMPTZ '$DAY 12:00+07' + g * INTERVAL '1 ms', 100, 100, 'paid', $BR_HQ FROM generate_series($1, $2) g"
}
# add_audit FROM TO — audit สังเคราะห์ (system) ของ $DAY — created_at คงค่าที่ใส่เมื่อ actor = system (set_row_stamp)
add_audit() {
  sql "INSERT INTO dt_audit_log (tenant_id, actor_kind, user_name, action, detail, created_at)
    SELECT $TENANT, 'system', 'e2e', 'stock_out', 'E36-SYN-' || g, TIMESTAMPTZ '$DAY 12:00+07' + g * INTERVAL '1 ms' FROM generate_series($1, $2) g"
}
cleanup() {
  sql "DELETE FROM dt_sales WHERE tenant_id = $TENANT AND order_no LIKE 'E36-SYN-%'" >/dev/null
  sql "DELETE FROM dt_audit_log WHERE tenant_id = $TENANT AND detail LIKE 'E36-SYN-%'" >/dev/null
}
range="from=$DAY&to=$DAY"
# timed METHOD PATH → call + $MS = เวลาที่ใช้ (ms)
timed() { local t; t=$(date +%s%3N); call "$@"; MS=$(( $(date +%s%3N) - t )); }

case_id 36.2 "20,000 แถวพอดี → 200 ครบ"
add_sales 1 20000
add_audit 1 20000
as "$T_BOSS"
timed GET "/sales?$range"
expect_status 200
check "GET /sales 20,000 รายการ" 20000 "$(jq length <<<"$BODY")"
echo "     GET /sales 20,000 แถว: ${MS} ms, $(wc -c <"$E2E_WORK/body") bytes"
timed @api2 GET "/audit-log?$range"
expect_status 200
check "GET /audit-log 20,000 รายการ" 20000 "$(jq length <<<"$BODY")"
echo "     GET /audit-log 20,000 แถว: ${MS} ms, $(wc -c <"$E2E_WORK/body") bytes"

case_id 36.1 "20,001 แถว → 400 (ไม่ตัดเงียบ)"
add_sales 20001 20001
add_audit 20001 20001
call GET "/sales?$range"
expect_status 400
expect_msg "ข้อมูลมากกว่า 20,000 รายการ กรุณาเลือกช่วงวันที่ให้สั้นลง"
call @api2 GET "/audit-log?$range"
expect_status 400
expect_msg "ข้อมูลมากกว่า 20,000 รายการ กรุณาเลือกช่วงวันที่ให้สั้นลง"
call GET "/audit-log?$range&action=price_change"
expect_status 200   # กรองใน SQL ก่อน LIMIT — กรองแล้วไม่เกิน = ได้
check "กรอง action แล้วเหลือ 0" 0 "$(jq length <<<"$BODY")"
call GET "/sales?from=2025-06-16&to=2025-06-16"
expect_status 200
check "วันอื่นไม่เกิน" 0 "$(jq length <<<"$BODY")"
cleanup
check "ลบแถวสังเคราะห์แล้ว" "0 0" "$(sql "SELECT (SELECT count(*) FROM dt_sales WHERE tenant_id = $TENANT AND order_no LIKE 'E36-SYN-%') || ' ' ||
  (SELECT count(*) FROM dt_audit_log WHERE tenant_id = $TENANT AND detail LIKE 'E36-SYN-%')")"

case_id 36.3 "statement_timeout 3s + ล็อก dt_sales → 503"
# ตั้งที่ role แล้วตัด connection ของ an22_app → pgbouncer ต่อใหม่ได้ค่าใหม่
reset_app_conns() { sql "SELECT count(pg_terminate_backend(pid)) FROM pg_stat_activity WHERE usename = 'an22_app'" >/dev/null; sleep 2; }
sql "ALTER ROLE an22_app SET statement_timeout = '3s'"
reset_app_conns
call GET "/products"
expect_status 200   # connection ใหม่ใช้ได้
LOCK_FILE="$E2E_WORK/36.lock"
"${COMPOSE[@]}" exec -T db psql -qAtX -U postgres -d an22 -c "BEGIN; LOCK TABLE dt_sales IN ACCESS EXCLUSIVE MODE; SELECT pg_sleep(30); COMMIT;" </dev/null >"$LOCK_FILE" 2>&1 &
LOCK_PID=$!
for _ in $(seq 1 50); do
  [[ "$(sql "SELECT count(*) FROM pg_locks l JOIN pg_class c ON c.oid = l.relation WHERE c.relname = 'dt_sales' AND l.mode = 'AccessExclusiveLock' AND l.granted")" == 1 ]] && break
  sleep 0.2
done
for via in @api1 @api2; do
  timed $via GET "/sales?from=$(TZ=Asia/Bangkok date +%F)&to=$(TZ=Asia/Bangkok date +%F)"
  expect_status 503
  expect_msg "ระบบใช้เวลานานเกินไป กรุณาลองใหม่หรือลดช่วงข้อมูล"
  check "$via รอ ~3 วิ (${MS} ms)" yes "$([[ $MS -ge 2800 && $MS -le 6000 ]] && echo yes || echo no)"
done
call GET "/products"
expect_status 200   # endpoint ที่ไม่แตะ dt_sales ปกติระหว่างล็อก
call GET /me
expect_status 200
sql "ALTER ROLE an22_app SET statement_timeout = '30s'"
sql "SELECT pg_cancel_backend(pid) FROM pg_stat_activity WHERE query LIKE '%pg_sleep(30)%' AND pid <> pg_backend_pid()" >/dev/null
wait "$LOCK_PID" || true
reset_app_conns
check "คืนค่า statement_timeout ของ an22_app" "statement_timeout=30s" \
  "$(sql "SELECT c FROM pg_db_role_setting s JOIN pg_roles r ON r.oid = s.setrole, unnest(s.setconfig) c WHERE r.rolname = 'an22_app' AND c LIKE 'statement_timeout=%'")"
call GET "/sales?from=$(TZ=Asia/Bangkok date +%F)&to=$(TZ=Asia/Bangkok date +%F)"
expect_status 200
check "ไม่มี transaction ค้าง (idle in transaction)" 0 "$(sql "SELECT count(*) FROM pg_stat_activity WHERE state LIKE 'idle in transaction%'")"
check "log warn db timeout 2 บรรทัด (ไม่ใช่ ERROR)" 2 "$({ logs api1 "$E2E_SINCE"; logs api2 "$E2E_SINCE"; } | grep -c 'db timeout GET' || true)"
as -

E2E_ALLOW_5XX=2
E2E_ALLOW_ERROR_LOG=2   # access log ของ 5xx = ERROR (RequestLogFilter); ErrorHandler log db timeout เป็น warn
finish
