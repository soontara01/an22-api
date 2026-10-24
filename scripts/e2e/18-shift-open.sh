#!/usr/bin/env bash
# ข้อ 18 — กะ: เปิด / เงินเข้าออก / ลิ้นชัก / ล็อกหน้าจอ (docs/e2e-test-plan.md)
# 18.7 ล็อกบัญชี cash1 ของบริษัทนี้ 15 นาที (ปลดล็อกผิด 5 ครั้ง) — token ที่ถืออยู่ยังใช้ได้
E2E_STEP=18
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

ymd=$(TZ=Asia/Bangkok date +%Y%m%d)
std_company E18
login "$CODE" boss pass1234; T_BOSS=$TOKEN
login "$CODE" cash1 pass1234; T_CASH1=$TOKEN
login "$CODE" cash2 pass1234; T_CASH2=$TOKEN
num() { jq -r "$1 | if type == \"number\" then (. + 0 | tostring) else tostring end" <<<"$BODY"; }
at() { as "$1"; branch "$2"; terminal "${3:--}"; }
sale_ms() { jq -nc --argjson p "$P_MS" --argjson m "$PM_CASH" '{items:[{productId:$p, factor:1, qty:1, serial:null}], freeSerials:[],
  payments:[{methodId:$m, amount:350, reference:""}], customer:"", expectedTotal:350}'; }

case_id 18.1 "เปิดกะ: เครื่อง / ยอดแบงก์ผิด"
at "$T_CASH1" "$BR_HQ"
call POST /shifts/open '{"openingFloat":1000,"openingDenominations":[]}'
expect_status 400
expect_msg "เครื่องนี้ยังไม่ได้ตั้งเลขเครื่อง POS กรุณาให้ผู้ดูแลตั้งค่าที่เมนูตั้งค่า"
while IFS='|' read -r t body msg; do
  terminal "$t"
  call POST /shifts/open "$body"
  expect_status 400
  expect_msg "$msg"
done <<'EOF'
9|{"openingFloat":1000,"openingDenominations":[]}|ไม่พบเครื่อง 9 ในทะเบียนเครื่อง POS กรุณาให้ผู้ดูแลตั้งค่าใหม่
3|{"openingFloat":1000,"openingDenominations":[]}|เครื่อง 3 เป็นเครื่องของสาขา BN · สาขาบางนา — สลับไปสาขานั้น หรือให้ผู้ดูแลตั้งเครื่องนี้ใหม่
1|{"openingFloat":1000,"openingDenominations":[{"value":500,"qty":1}]}|ยอดรวมแบงก์/เหรียญไม่ตรงกับเงินทอนตั้งต้น
1|{"openingFloat":1000,"openingDenominations":[{"value":300,"qty":1}]}|จำนวนแบงก์/เหรียญไม่ถูกต้อง
1|{"openingFloat":1000,"openingDenominations":[{"value":500,"qty":1.5}]}|จำนวนแบงก์/เหรียญไม่ถูกต้อง
1|{"terminalId":2,"openingFloat":1000,"openingDenominations":[]}|เลขเครื่องไม่ตรงกับเครื่องนี้ (เครื่อง 1)
EOF
expect_sql "SELECT count(*) FROM dt_shifts WHERE tenant_id = $TENANT" 0

case_id 18.2 "cash1 เปิดกะเครื่อง 1 เงินทอน 1,000"
at "$T_CASH1" "$BR_HQ" 1
call POST /shifts/open '{"openingFloat":1000,"openingDenominations":[{"value":1000,"qty":1}]}'
expect_status 200
SH=$(jq -r .id <<<"$BODY")
expect_json '[.shiftNo, .status, .cashier, (.openingFloat + 0 | tostring), (.terminalId | tostring)] | join(" ")' "SH-HQ-$ymd-01 open แคชเชียร์หนึ่ง 1000 1"
expect_sql "SELECT status || ':' || cashier_user_id || ':' || branch_id || ':' || terminal_id || ':' || opening_float FROM dt_shifts WHERE id = $SH" "open:$U_CASH1:$BR_HQ:1:1000.00"

case_id 18.3 "เปิดซ้ำ"
at "$T_BOSS" "$BR_HQ" 1
call POST /shifts/open '{"openingFloat":0,"openingDenominations":[]}'
expect_status 400
expect_msg "เครื่องนี้มีกะที่เปิดอยู่แล้ว"
at "$T_CASH1" "$BR_HQ" 2
call @api2 POST /shifts/open '{"openingFloat":0,"openingDenominations":[]}'
expect_status 400
expect_msg "คุณมีกะที่เปิดอยู่ที่เครื่องอื่น — ปิดกะนั้นก่อน"
expect_sql "SELECT string_agg(indexname, ',' ORDER BY indexname) FROM pg_indexes WHERE tablename = 'dt_shifts' AND indexdef LIKE '%UNIQUE%' AND indexdef LIKE '%status = ''open''%'" \
  "dt_shifts_open_cashier_key,dt_shifts_open_terminal_key"
out=$(sql "INSERT INTO dt_shifts (tenant_id, shift_no, terminal_id, branch_id, cashier_user_id, cashier, opening_float, status)
  SELECT tenant_id, 'SH-DUP', terminal_id, branch_id, $U_BOSS, 'ซ้ำ', 0, 'open' FROM dt_shifts WHERE id = $SH" 2>&1 || true)
check "DB กันกะเปิดซ้ำต่อเครื่อง (unique)" yes "$(grep -q 'dt_shifts_open_terminal_key' <<<"$out" && echo yes || echo "no: ${out:0:200}")"

case_id 18.4 "ขายโดยไม่มีกะ / admin ขายบนกะของคนอื่น"
at "$T_BOSS" "$BR_HQ" 2
call POST /sales "$(sale_ms)"
expect_status 400
expect_msg "ยังไม่ได้เปิดกะที่เครื่องนี้"
terminal 1
call POST /sales "$(sale_ms)"
expect_status 400
expect_msg "กะนี้เป็นของ แคชเชียร์หนึ่ง — ต้องปิดกะนั้นก่อนจึงจะขายได้"

case_id 18.4b "cash3 (user HQ) นำเงินเข้าในกะของ cash1"
call POST /users "{\"username\":\"cash3\",\"name\":\"แคชเชียร์สาม\",\"email\":\"\",\"role\":\"user\",\"active\":true,\"allBranches\":false,\"branchIds\":[$BR_HQ],\"password\":\"pass1234\"}"
expect_status 200
login "$CODE" cash3 pass1234; T_CASH3=$TOKEN
at "$T_CASH3" "$BR_HQ" 1
call POST "/shifts/$SH/cash-movements" '{"type":"in","amount":100,"reason":"ไม่ใช่กะของฉัน"}'
expect_status 400
expect_msg "กะนี้เป็นของ แคชเชียร์หนึ่ง — ทำรายการนี้ได้เฉพาะเจ้าของกะหรือผู้ดูแลระบบ"
call POST "/shifts/$SH/drawer-opens" '{"reason":"ไม่ใช่กะของฉัน"}'
expect_status 400
expect_msg "กะนี้เป็นของ แคชเชียร์หนึ่ง — ทำรายการนี้ได้เฉพาะเจ้าของกะหรือผู้ดูแลระบบ"
call GET "/shifts/$SH"
expect_status 403
expect_msg "ดูได้เฉพาะกะของตัวเอง"

case_id 18.5 "เงินเข้า / ออก"
at "$T_CASH1" "$BR_HQ" 1
call POST "/shifts/$SH/cash-movements" '{"type":"in","amount":500,"reason":" แลกเหรียญ "}'
expect_status 200
expect_json '[.type, (.amount + 0 | tostring), .reason, .by] | join(" ")' "in 500 แลกเหรียญ แคชเชียร์หนึ่ง"
call @api2 POST "/shifts/$SH/cash-movements" '{"type":"out","amount":200,"reason":"ซื้อน้ำแข็ง"}'
expect_status 200
while IFS='|' read -r body msg; do
  call POST "/shifts/$SH/cash-movements" "$body"
  expect_status 400
  expect_msg "$msg"
done <<'EOF'
{"type":"out","amount":5000,"reason":"ถอนเกิน"}|เงินสดในลิ้นชักไม่พอ
{"type":"in","amount":100,"reason":"  "}|กรุณาระบุเหตุผล
{"type":"move","amount":100,"reason":"x"}|ประเภทรายการไม่ถูกต้อง
{"type":"in","amount":0,"reason":"x"}|จำนวนเงินต้องมากกว่า 0 (ทศนิยมไม่เกิน 2 ตำแหน่ง)
{"type":"in","amount":10.555,"reason":"x"}|จำนวนเงินต้องมากกว่า 0 (ทศนิยมไม่เกิน 2 ตำแหน่ง)
EOF
terminal 2
call POST "/shifts/$SH/cash-movements" '{"type":"in","amount":100,"reason":"ผิดเครื่อง"}'
expect_status 400
expect_msg "กะนี้ไม่ได้เปิดที่เครื่องนี้"
at "$T_BOSS" "$BR_HQ" 1
call POST "/shifts/$SH/cash-movements" '{"type":"out","amount":5000,"reason":"ถอนเกิน"}'
expect_status 400
expect_msg "เงินสดในลิ้นชักไม่พอ (คาดว่ามี 1,300 บาท)"
expect_sql "SELECT string_agg(type || ':' || amount || ':' || reason || ':' || by_name, ',' ORDER BY id) FROM dt_cash_movements WHERE tenant_id = $TENANT AND shift_id = $SH" \
  "in:500.00:แลกเหรียญ:แคชเชียร์หนึ่ง,out:200.00:ซื้อน้ำแข็ง:แคชเชียร์หนึ่ง"
call GET "/shifts/$SH/movements"
expect_json 'length' 2

case_id 18.6 "เปิดลิ้นชักเปล่า"
at "$T_CASH1" "$BR_HQ" 1
call POST "/shifts/$SH/drawer-opens" '{"reason":" "}'
expect_status 400
expect_msg "กรุณาระบุเหตุผลที่เปิดลิ้นชัก"
call POST "/shifts/$SH/drawer-opens" '{"reason":"ลูกค้าขอแลกแบงก์"}'
expect_status 200
expect_sql "SELECT count(*) || ':' || min(reason) || ':' || min(by_name) FROM dt_drawer_opens WHERE tenant_id = $TENANT AND shift_id = $SH" "1:ลูกค้าขอแลกแบงก์:แคชเชียร์หนึ่ง"
call GET "/shifts/$SH/drawer-opens"
expect_json 'length' 1

case_id 18.7 "ล็อกหน้าจอ"
ip="10.18.$((RANDOM % 200 + 20)).7"
call POST "/shifts/$SH/lock"
expect_status 200
expect_sql "SELECT locked_at IS NOT NULL FROM dt_shifts WHERE id = $SH" t
call @api2 POST /sales "$(sale_ms)"
expect_status 400
expect_msg "หน้าจอถูกล็อก — ปลดล็อกก่อน"
for i in 1 2 3 4 5; do
  call POST "/shifts/$SH/unlock" "{\"username\":\"cash1\",\"password\":\"wrong$i\"}" "X-Real-IP: $ip"
  [[ "$STATUS $(jq -r .message <<<"$BODY")" == "400 ชื่อผู้ใช้หรือรหัสผ่านไม่ถูกต้อง" ]] || fail "ผิดครั้งที่ $i → $STATUS $BODY"
done
pass "ปลดล็อกรหัสผิด 5 ครั้ง → 400 ชื่อผู้ใช้หรือรหัสผ่านไม่ถูกต้อง"
call POST "/shifts/$SH/unlock" '{"username":"cash1","password":"pass1234"}' "X-Real-IP: $ip"
expect_status 429
expect_msg "พยายามบ่อยเกินไป กรุณารอสักครู่แล้วลองใหม่"
call POST "/shifts/$SH/unlock" '{"username":"cash2","password":"pass1234"}'
expect_status 400
expect_msg "ปลดล็อกได้เฉพาะ แคชเชียร์หนึ่ง หรือผู้ดูแลระบบ"
expect_sql "SELECT locked_at IS NOT NULL FROM dt_shifts WHERE id = $SH" t
call POST "/shifts/$SH/unlock" '{"username":"boss","password":"pass1234"}'
expect_status 200
expect_sql "SELECT locked_at IS NULL FROM dt_shifts WHERE id = $SH" t
call POST /sales "$(sale_ms)"
expect_status 200   # ปลดล็อกแล้วขายได้
check "access log ไม่มี body ของ /unlock" 0 \
  "$({ logs api1 "$E2E_SINCE"; logs api2 "$E2E_SINCE"; } | grep '"type":"access"' | grep '/unlock' | jq -r 'select(.requestBody != null or .responseBody != null) | .path' | grep -c . || true)"
terminal 2
call POST "/shifts/$SH/lock"
expect_status 400
expect_msg "กะนี้ไม่ได้เปิดที่เครื่องนี้"

case_id 18.8 "/shifts/current, /shifts/last-float"
at "$T_CASH1" "$BR_HQ" 1
call GET /shifts/current
expect_json '[(.id | tostring), .status] | join(" ")' "$SH open"
terminal 2
call @api2 GET /shifts/current
check "เครื่อง 2 ไม่มีกะ" "200 null" "$STATUS ${BODY:-null}"
terminal 1
call GET /shifts/last-float
check "last-float (ยังไม่มีกะปิด)" "200 0" "$STATUS $(jq '.floatLeft + 0' <<<"$BODY")"
as -; branch -; terminal -

finish
