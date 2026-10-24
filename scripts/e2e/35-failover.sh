#!/usr/bin/env bash
# ข้อ 35 — instance ล่ม / restart / graceful shutdown (docs/e2e-test-plan.md)
# worker ยิงขายผ่าน lb ต่อเนื่อง; 35.1 docker stop api1 (SIGTERM → graceful ≤ 30 วิ), 35.2 docker kill api2 (SIGKILL), 35.3 start ทั้งสองตัว
E2E_STEP=35
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

std_company E35
login "$CODE" boss pass1234; T_BOSS=$TOKEN
login "$CODE" cash1 pass1234; T_CASH1=$TOKEN
as "$T_BOSS"; branch "$BR_HQ"
call PUT "/products/$P_CBL/stock" '{"delta":5000,"unitCost":40,"note":"เติมของ"}'
as "$T_CASH1"; terminal 1
call POST /shifts/open '{"openingFloat":0,"openingDenominations":[]}'
as -; branch -; terminal -
SALE=$(jq -nc --argjson p "$P_CBL" --argjson m "$PM_CASH" '{items:[{productId:$p, factor:1, qty:1, serial:null}], freeSerials:[], payments:[{methodId:$m, amount:99, reference:""}], customer:"", expectedTotal:99}')
WORKERS=6

# worker PREFIX ID STOPFILE — ขายผ่าน lb จนมีไฟล์ STOPFILE; บันทึก "<เวลา ms> <status>" ต่อ request
worker() {
  local out="$E2E_WORK/$1.$2"
  while [[ ! -f "$3" ]]; do
    local t s
    t=$(date +%s%3N)
    s=$(curl -s -m 60 -o /dev/null -w '%{http_code}' -X POST -H 'Content-Type: application/json' -H "X-Real-IP: $(rand_ip)" -H "Authorization: Bearer $T_CASH1" \
      -H "X-Branch-Id: $BR_HQ" -H "X-Terminal-Id: 1" --data-binary "$SALE" "$LB/sales" || echo 000)
    echo "$t $s" >>"$out"
  done
}
run_load() { # PREFIX ACTION… — เริ่ม worker, รอ 3 วิ, ทำ ACTION, ยิงต่อ 6 วิ, หยุด
  local prefix=$1; shift
  rm -f "$E2E_WORK/$prefix.stop"
  for w in $(seq 1 $WORKERS); do worker "$prefix" "$w" "$E2E_WORK/$prefix.stop" & done
  sleep 3
  ACTION_AT=$(date +%s%3N)
  "$@"
  ACTION_DONE=$(date +%s%3N)
  sleep 6
  touch "$E2E_WORK/$prefix.stop"
  wait
}
dist() { cat "$E2E_WORK/$1".* | awk '{print $2}' | sort | uniq -c | awk '{printf "%s×%s ", $2, $1}' | sed 's/ $//'; }
consistency() { # → ปัญหาของข้อมูลขาย / สต็อก (ว่าง = ครบ)
  sql "SELECT 'บิลไม่มีบรรทัด ' || count(*) FROM dt_sales s WHERE tenant_id = $TENANT AND NOT EXISTS (SELECT 1 FROM dt_sale_lines l WHERE l.tenant_id = s.tenant_id AND l.sale_id = s.id) HAVING count(*) > 0
    UNION ALL SELECT 'บิลไม่มีการชำระ ' || count(*) FROM dt_sales s WHERE tenant_id = $TENANT AND NOT EXISTS (SELECT 1 FROM dt_sale_payments p WHERE p.tenant_id = s.tenant_id AND p.sale_id = s.id) HAVING count(*) > 0
    UNION ALL SELECT 'ยอดชำระไม่เท่า ' || count(*) FROM dt_sales s WHERE tenant_id = $TENANT AND total + rounding <> (SELECT sum(amount) FROM dt_sale_payments p WHERE p.tenant_id = s.tenant_id AND p.sale_id = s.id) HAVING count(*) > 0
    UNION ALL SELECT 'สต็อก ≠ ผลรวม movement ' || p.sku FROM dt_branch_stock b JOIN dt_products p ON p.tenant_id = b.tenant_id AND p.id = b.product_id
      WHERE b.tenant_id = $TENANT AND b.stock <> (SELECT coalesce(sum(qty), 0) FROM dt_stock_movements m WHERE m.tenant_id = b.tenant_id AND m.branch_id = b.branch_id AND m.product_id = b.product_id)
    UNION ALL SELECT 'บิลขาย CBL ≠ movement issue' WHERE (SELECT count(*) FROM dt_sale_lines WHERE tenant_id = $TENANT AND product_id = $P_CBL)
      <> (SELECT count(*) FROM dt_stock_movements WHERE tenant_id = $TENANT AND product_id = $P_CBL AND type = 'issue')
    UNION ALL SELECT 'เลขบิลข้าม / ซ้ำ' WHERE (SELECT count(*) FROM dt_sales WHERE tenant_id = $TENANT) <> (SELECT max(right(order_no, 4)::int) FROM dt_sales WHERE tenant_id = $TENANT)"
}
ok_bills() { cat "$E2E_WORK/$1".* | awk '$2 == 200' | grep -c . || true; }

case_id 35.1 "docker stop api1 ระหว่างยิงขาย"
started1=$(docker inspect -f '{{.State.StartedAt}}' an22-multi-api1-1)
bills0=$(sql "SELECT count(*) FROM dt_sales WHERE tenant_id = $TENANT")
STOP_FROM=$(now_utc)
# poll /readyz ของ api1 ทุก 0.2 วิ ระหว่าง stop — drain (B7) ต้องตอบ 503 ก่อน container ปิด
( while [[ ! -f "$E2E_WORK/p1.stop" ]]; do echo "$(curl -s -m 1 -o /dev/null -w '%{http_code}' "$API1/readyz")" >>"$E2E_WORK/ready1"; sleep 0.2; done ) &
run_load p1 docker stop an22-multi-api1-1
echo "     stop ใช้ $(( (ACTION_DONE - ACTION_AT) )) ms · status ผ่าน lb: $(dist p1)"
check "api1 หยุดแล้ว (exit 143 = SIGTERM)" "exited 143" "$(docker inspect -f '{{.State.Status}} {{.State.ExitCode}}' an22-multi-api1-1)"
L1=$(docker logs --since "$STOP_FROM" an22-multi-api1-1 2>&1)
echo "     /readyz ของ api1 ระหว่าง stop: $(uniq -c "$E2E_WORK/ready1" | awk '{printf "%s×%s ", $2, $1}')"
check "drain: /readyz 503 ระหว่าง stop (B7)" yes "$(grep -qx 503 "$E2E_WORK/ready1" && echo yes || echo no)"
check "drain: log ก่อน graceful (B7)" yes "$(grep -q 'shutdown: /readyz 503' <<<"$L1" && echo yes || echo no)"
check "stop ใช้ ≥ SHUTDOWN_DRAIN (10 วิ) และไม่ถูก kill" yes "$([[ $((ACTION_DONE - ACTION_AT)) -ge 10000 ]] && echo yes || echo no)"
check "api1 graceful shutdown (log)" yes "$(grep -q 'Commencing graceful shutdown' <<<"$L1" && grep -q 'Graceful shutdown complete' <<<"$L1" && echo yes || echo no)"
inflight=$(grep '"type":"access"' <<<"$L1" | jq -r 'select(.path == "/api/sales") | .status' | sort | uniq -c | tr -s ' ' | tr '\n' ';')
echo "     api1 access log หลังสั่ง stop: $inflight"
check "request ที่ค้างที่ api1 ตอบจบ (ไม่มี 5xx ใน access log ของ api1)" 0 "$(grep '"type":"access"' <<<"$L1" | grep -cE '"status":5' || true)"
# B7: drain ช่วยเมื่อ lb probe /readyz — nginx ของ stack ทดสอบไม่ probe (passive เท่านั้น) จึงยังได้ 502 ของ connection reset ได้ ≤ 2
# (request ไม่ถึง controller — ไม่มีใน access log ของ api, ไม่มีบิล); platform จริงต้องตั้ง readiness probe
lb_bad=$(cat "$E2E_WORK"/p1.* | awk '$2 != 200 {print $2}' | sort | uniq -c | awk '{printf "%s×%s ", $2, $1}')
check "lb: ที่ไม่ใช่ 200 มีแค่ 502 ≤ 2 (lb ทดสอบไม่ probe — ข้อจำกัด B7) [${lb_bad:-ไม่มี}]" yes \
  "$(cat "$E2E_WORK"/p1.* | awk '$2 != 200 {n++; if ($2 != 502) bad=1} END {print (n <= 2 && !bad) ? "yes" : "no"}')"
check "บิลที่ได้ 200 = บิลใน DB" "$(ok_bills p1)" "$(( $(sql "SELECT count(*) FROM dt_sales WHERE tenant_id = $TENANT") - bills0 ))"
check "ข้อมูลขาย / สต็อกครบ" "" "$(consistency)"
as "$T_CASH1"
call GET /me
expect_status 200   # token เดิมใช้ต่อที่ api2
check "lb ส่งไป api2 อย่างเดียว" "$(docker inspect -f '{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}' an22-multi-api2-1):8080" "$(header X-Upstream)"
as -

case_id 35.2 "docker kill api2 (ไม่ graceful) ระหว่างยิงขาย"
docker start an22-multi-api1-1 >/dev/null
for _ in $(seq 1 120); do ready "$API1" && break; sleep 1; done
check "api1 กลับมา /readyz 200" 200 "$(curl -s -o /dev/null -w '%{http_code}' "$API1/readyz")"
sleep 2
bills0=$(sql "SELECT count(*) FROM dt_sales WHERE tenant_id = $TENANT")
run_load p2 docker kill an22-multi-api2-1
echo "     status ผ่าน lb: $(dist p2)"
check "api2 ถูก kill (exit 137)" "exited 137" "$(docker inspect -f '{{.State.Status}} {{.State.ExitCode}}' an22-multi-api2-1)"
db_new=$(( $(sql "SELECT count(*) FROM dt_sales WHERE tenant_id = $TENANT") - bills0 )); ok2=$(ok_bills p2); bad2=$(cat "$E2E_WORK"/p2.* | awk '$2 != 200' | grep -c . || true)
echo "     บิลใหม่ใน DB $db_new · 200 $ok2 · ไม่ใช่ 200 $bad2"
check "บิลที่ได้ 200 อยู่ใน DB ครบ และบิลที่เกินมาจาก request ที่ล้มกลางทางเท่านั้น" yes "$([[ $db_new -ge $ok2 && $db_new -le $((ok2 + bad2)) ]] && echo yes || echo no)"
check "ข้อมูลขาย / สต็อกครบ (transaction ที่ค้าง rollback ไม่มีบิลครึ่ง ๆ)" "" "$(consistency)"
check "ไม่มี transaction ค้างใน DB" 0 "$(sql "SELECT count(*) FROM pg_stat_activity WHERE state LIKE 'idle in transaction%'")"
as "$T_BOSS"
call GET /me
expect_status 200
as -

case_id 35.3 "start ใหม่ทั้งสองตัว"
# docker logs เก็บ log ข้าม start / restart ของ container เดิม — ไม่ต้อง save_logs (ซ้ำ)
docker start an22-multi-api2-1 >/dev/null
docker restart an22-multi-api1-1 >/dev/null
for url in "$API1" "$API2" "$LB"; do for _ in $(seq 1 120); do ready "$url" && break; sleep 1; done; done
for base in "$API1" "$API2" "$LB"; do
  check "$base/readyz" 200 "$(curl -s -o /dev/null -w '%{http_code}' "$base/readyz")"
done
for svc in api1 api2; do
  check "$svc flyway validate ผ่านหลัง start (log ล่าสุด)" yes \
    "$(docker logs --tail 400 "an22-multi-$svc-1" 2>&1 | grep -q 'flyway validate: ผ่าน (FLYWAY_MIGRATE=false)' && echo yes || echo no)"
done
as "$T_CASH1"; branch "$BR_HQ"; terminal 1
call @api1 POST /sales "$SALE"
expect_status 200
call @api2 POST /sales "$SALE"
expect_status 200
as -; branch -; terminal -
check "ข้อมูลขาย / สต็อกครบ" "" "$(consistency)"
E2E_ALLOW_ERROR_LOG=${KILL_ERRORS:-0}

finish
