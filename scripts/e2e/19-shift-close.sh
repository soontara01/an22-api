#!/usr/bin/env bash
# ข้อ 19 — กะ: ตรวจยอด / ปิด / อนุมัติ / ปรับปรุง / รายงาน (docs/e2e-test-plan.md)
# กะ 1: เงินทอน 1,000 + เข้า 500 + ออก 200 + MS ×2 เงินสด (รับ 1,000 ทอน 300) + NB บัตร 25,000 + CBL 2 กล่อง QR 1,980
#       → คาด เงินสด 2,000 · บัตร 25,000 · QR 1,980 · โอนเงิน 0 (confirm)
E2E_STEP=19
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

today=$(TZ=Asia/Bangkok date +%F)
std_company E19
login "$CODE" boss pass1234; T_BOSS=$TOKEN
login "$CODE" cash1 pass1234; T_CASH1=$TOKEN
login "$CODE" cash2 pass1234; T_CASH2=$TOKEN
num() { jq -r "$1 | if type == \"number\" then (. + 0 | tostring) else tostring end" <<<"$BODY"; }
at() { as "$1"; branch "$2"; terminal "${3:--}"; }
counts() { # cash card qr [transferConfirmed] [cash reason code] [reason]
  jq -nc --argjson c "$PM_CASH" --argjson k "$PM_CARD" --argjson q "$PM_QR" --argjson t "$PM_TRANSFER" --argjson cash "$1" --argjson card "$2" --argjson qr "$3" \
    --argjson ok "${4:-true}" --arg code "${5:-}" --arg why "${6:-}" '[
    ({methodId:$c, counted:$cash} + (if $code == "" then {} else {reasonCode:$code, reason:$why} end)),
    {methodId:$k, counted:$card}, {methodId:$q, counted:$qr}, {methodId:$t, confirmed:$ok}]'
}
close_body() { # counts_json deposit [approval_json]
  jq -nc --argjson counts "$1" --argjson d "$2" --argjson a "${3:-null}" '{counts:$counts, cashDenominations:[], depositAmount:$d, note:"ปิดกะ e2e", approval:$a}'
}
open_shift() { call POST /shifts/open "{\"openingFloat\":$1,\"openingDenominations\":[]}"; jq -r .id <<<"$BODY"; }
sale() { # items_json total payments_json
  call POST /sales "$(jq -nc --argjson i "$1" --argjson t "$2" --argjson p "$3" '{items:$i, freeSerials:[], payments:$p, customer:"", expectedTotal:$t}')"
}

# ---------- กะ 1 ----------
at "$T_CASH1" "$BR_HQ" 1
SH1=$(open_shift 1000)
call POST "/shifts/$SH1/cash-movements" '{"type":"in","amount":500,"reason":"แลกเหรียญ"}'
call POST "/shifts/$SH1/cash-movements" '{"type":"out","amount":200,"reason":"ซื้อน้ำแข็ง"}'
sale "[{\"productId\":$P_MS,\"factor\":1,\"qty\":2,\"serial\":null}]" 700 "[{\"methodId\":$PM_CASH,\"amount\":1000,\"reference\":\"\"}]"
check "MS ×2 เงินสด รับ 1,000 ทอน 300" "200 700 300" "$STATUS $(num .total) $(num .change)"
sale "[{\"productId\":$P_NB,\"factor\":1,\"qty\":1,\"serial\":\"NB0001\"}]" 25000 "[{\"methodId\":$PM_CARD,\"amount\":25000,\"reference\":\"A12345\"}]"
check "NB บัตร 25,000" "200 25000" "$STATUS $(num .total)"
sale "[{\"productId\":$P_CBL,\"factor\":10,\"qty\":2,\"serial\":null}]" 1980 "[{\"methodId\":$PM_QR,\"amount\":1980,\"reference\":\"\"}]"
check "CBL 2 กล่อง QR 1,980" "200 1980" "$STATUS $(num .total)"

case_id 19.1 "summary: cash1 (blind) / boss"
call GET "/shifts/$SH1/summary"
expect_status 200
check "cash1 ไม่เห็นยอดต่อช่องทาง" "false 3 27680" "$(num 'has("lines")') $(num .billCount) $(num .salesTotal)"
at "$T_BOSS" "$BR_HQ"
call @api2 GET "/shifts/$SH1/summary"
check "boss เห็นยอดคาด" "เงินสด:2000,บัตรเครดิต:25000,QR พร้อมเพย์:1980,โอนเงิน:0" \
  "$(jq -r '[.lines[] | .name + ":" + (.expected + 0 | tostring)] | join(",")' <<<"$BODY")"
check "เงินเข้า / ออก" "500 200" "$(num .cashIn) $(num .cashOut)"

case_id 19.2 "count-check"
at "$T_CASH1" "$BR_HQ" 1
call POST "/shifts/$SH1/count-check" "{\"counts\":[{\"methodId\":$PM_CASH,\"counted\":1950}]}"
expect_status 400
expect_msg "กรุณากรอกยอดที่นับได้ของ บัตรเครดิต"
call POST "/shifts/$SH1/count-check" "{\"counts\":$(counts 1950 25000 1980 false)}"
expect_status 400
expect_msg 'กรุณาตรวจยอด โอนเงิน แล้วติ๊ก "ตรวจแล้ว"'
call POST "/shifts/$SH1/count-check" "{\"counts\":$(counts 1950 25000 1980)}"
expect_status 200
check "variance / needsApproval" "-50,0,0,0 false" "$(jq -r '[.lines[].variance + 0 | tostring] | join(",")' <<<"$BODY") $(num .needsApproval)"
expect_sql "SELECT (SELECT count(*) FROM dt_shift_counts WHERE tenant_id = $TENANT) || ':' || status FROM dt_shifts WHERE id = $SH1" "0:open"

case_id 19.3 "ปิดผิดกฎ"
while IFS='|' read -r body msg; do
  call POST "/shifts/$SH1/close" "$body"
  expect_status 400
  expect_msg "$msg"
done <<EOF
$(close_body "$(counts 1950 25000 1980)" 950)|เงินสด ขาด 50 บาท — กรุณาระบุสาเหตุ
$(close_body "$(counts 1950 25000 1980 true other " ")" 950)|กรุณาอธิบายสาเหตุส่วนต่างของ เงินสด
$(close_body "$(counts 1950 25000 1980 true wrong_change "")" 2000)|เงินนำส่งต้องอยู่ระหว่าง 0 ถึงยอดเงินสดที่นับได้
$(close_body "$(counts 1950 25000 1980 true wrong_change "")" -1)|เงินนำส่งต้องอยู่ระหว่าง 0 ถึงยอดเงินสดที่นับได้
EOF
at "$T_CASH2" "$BR_BN"
call POST "/shifts/$SH1/close" "$(close_body "$(counts 1950 25000 1980 true wrong_change "")" 950)"
expect_status 403
expect_msg "ปิดกะได้เฉพาะเจ้าของกะหรือผู้ดูแลระบบ"
expect_sql "SELECT status FROM dt_shifts WHERE id = $SH1" open

case_id 19.4 "ปิด (ขาด 50 wrong_change, นำส่ง 950)"
at "$T_CASH1" "$BR_HQ" 1
call POST "/shifts/$SH1/close" "$(close_body "$(counts 1950 25000 1980 true wrong_change "")" 950)"
expect_status 200
check "status / approval / floatLeft" "closed not_needed 1000" "$(num .status) $(num .approvalStatus) $(num .floatLeft)"
expect_sql "SELECT string_agg(name || ':' || expected || ':' || coalesce(counted::text, '-') || ':' || coalesce(reason_code, '-'), ',' ORDER BY method_id)
  FROM dt_shift_counts WHERE tenant_id = $TENANT AND shift_id = $SH1" \
  "$(sql "SELECT string_agg(x, ',' ORDER BY id) FROM (VALUES ($PM_CASH, 'เงินสด:2000.00:1950.00:wrong_change'), ($PM_CARD, 'บัตรเครดิต:25000.00:25000.00:-'),
    ($PM_QR, 'QR พร้อมเพย์:1980.00:1980.00:-'), ($PM_TRANSFER, 'โอนเงิน:0.00:-:-')) v(id, x)")"
expect_sql "SELECT float_left || ':' || deposit_amount || ':' || approval_status FROM dt_shifts WHERE id = $SH1" "1000.00:950.00:not_needed"
call GET /shifts/last-float
check "last-float เครื่อง 1" 1000 "$(num .floatLeft)"
call POST "/shifts/$SH1/close" "$(close_body "$(counts 1950 25000 1980 true wrong_change "")" 950)"
expect_status 400
expect_msg "กะนี้ปิดไปแล้ว"

case_id 19.5 "ขาด 200 (เกินเพดาน 100): ไม่มีผู้อนุมัติ / ผู้อนุมัติผิด / ไม่ใช่ admin / boss"
SH2=$(open_shift 1000)
call POST "/shifts/$SH2/close" "$(close_body "$(counts 800 0 0 true unknown "")" 0)"
expect_status 200
expect_json .approvalStatus pending
SH3=$(open_shift 1000)
while IFS='|' read -r who pw msg; do
  call POST "/shifts/$SH3/close" "$(close_body "$(counts 800 0 0 true unknown "")" 0 "{\"username\":\"$who\",\"password\":\"$pw\"}")"
  expect_status 400
  expect_msg "$msg"
done <<'EOF'
boss|wrong-pass|รหัสผ่านผู้อนุมัติไม่ถูกต้อง
cash2|pass1234|ผู้อนุมัติต้องเป็นผู้ดูแลระบบ
EOF
call @api2 POST "/shifts/$SH3/close" "$(close_body "$(counts 800 0 0 true unknown "")" 0 '{"username":"boss","password":"pass1234"}')"
expect_status 200
expect_sql "SELECT approval_status || ':' || approved_by FROM dt_shifts WHERE id = $SH3" "approved:คุณบอส"

case_id 19.6 "approve"
call POST "/shifts/$SH2/approve"
expect_status 403
expect_msg "อนุมัติส่วนต่างได้เฉพาะผู้ดูแลระบบ"
at "$T_BOSS" "$BR_HQ"
call POST "/shifts/$SH2/approve"
expect_status 200
expect_json .approvalStatus approved
call POST "/shifts/$SH1/approve"
expect_status 400
expect_msg "กะนี้ไม่ได้รออนุมัติ"
expect_sql "SELECT action || ':' || user_id || ':' || detail FROM dt_audit_log WHERE tenant_id = $TENANT AND action = 'shift_approve'" "shift_approve:$U_BOSS:กะของ แคชเชียร์หนึ่ง"

case_id 19.8 "ปรับปรุงกะ (ก่อน 19.7 เพื่อทดสอบกะเปิด)"
at "$T_CASH1" "$BR_HQ" 1
SH4=$(open_shift 1000)
at "$T_BOSS" "$BR_HQ"
adj() { call POST "/shifts/$1/adjustments" "$(jq -nc --argjson m "$2" --argjson a "$3" --arg c "$4" --arg r "$5" '{methodId:$m, amount:$a, reasonCode:(if $c == "" then null else $c end), reason:$r}')"; }
while IFS='|' read -r sh m a c r msg; do
  adj "$sh" "$m" "$a" "$c" "$r"
  expect_status 400
  expect_msg "$msg"
done <<EOF
$SH4|$PM_CASH|50|found_cash|เจอเงิน|ปรับปรุงได้เฉพาะกะที่ปิดแล้ว
$SH1|$PM_TRANSFER|50|found_cash|เจอเงิน|ปรับปรุงได้เฉพาะช่องทางที่นับตอนปิดกะ
$SH1|$PM_CASH|0|found_cash|เจอเงิน|จำนวนเงินต้องไม่เป็น 0 (ทศนิยมไม่เกิน 2 ตำแหน่ง)
$SH1|$PM_CASH|50.555|found_cash|เจอเงิน|จำนวนเงินต้องไม่เป็น 0 (ทศนิยมไม่เกิน 2 ตำแหน่ง)
$SH1|$PM_CASH|50||เจอเงิน|กรุณาเลือกสาเหตุ
$SH1|$PM_CASH|50|other| |กรุณาอธิบายสาเหตุ
EOF
adj "$SH1" "$PM_CASH" 50 found_cash " เจอเงินใต้ลิ้นชัก "
expect_status 200
expect_json '[(.amount + 0 | tostring), .reasonCode, .reason] | join(" ")' "50 found_cash เจอเงินใต้ลิ้นชัก"
at "$T_CASH1" "$BR_HQ"
adj "$SH1" "$PM_CASH" 10 found_cash "staff"
expect_status 403
expect_msg "ปรับปรุงกะได้เฉพาะผู้ดูแลระบบ"
expect_sql "SELECT count(*) || ':' || sum(amount) FROM dt_shift_adjustments WHERE tenant_id = $TENANT AND shift_id = $SH1" "1:50.00"
expect_sql "SELECT counted FROM dt_shift_counts WHERE tenant_id = $TENANT AND shift_id = $SH1 AND method_id = $PM_CASH" 1950.00
expect_sql "SELECT action || ':' || detail FROM dt_audit_log WHERE tenant_id = $TENANT AND action = 'shift_adjust'" "shift_adjust:+50 — เจอเงินใต้ลิ้นชัก"
call GET "/shifts/$SH1/adjustments"
expect_json 'length' 1

case_id 19.7 "boss ปิดกะของ cash1 แทน"
at "$T_BOSS" "$BR_HQ"
call POST "/shifts/$SH4/close" "$(close_body "$(counts 1000 0 0)" 0)"
expect_status 200
expect_json '[.status, .closedBy] | join(" ")' "closed คุณบอส"
expect_sql "SELECT action || ':' || user_id || ':' || detail FROM dt_audit_log WHERE tenant_id = $TENANT AND action = 'shift_close_other'" "shift_close_other:$U_BOSS:กะของ แคชเชียร์หนึ่ง"

case_id 19.9 "รายการกะ / variance-report / สิทธิ์ดู"
call POST /users "{\"username\":\"cash3\",\"name\":\"แคชเชียร์สาม\",\"email\":\"\",\"role\":\"user\",\"active\":true,\"allBranches\":false,\"branchIds\":[$BR_HQ],\"password\":\"pass1234\"}"
login "$CODE" cash3 pass1234; T_CASH3=$TOKEN
at "$T_CASH3" "$BR_HQ" 2
SH5=$(open_shift 0)
range="from=$today&to=$today"
call GET "/shifts?$range"
check "cash3 เห็นเฉพาะกะของตัวเอง" "$SH5" "$(jq -r '[.[].id] | map(tostring) | join(",")' <<<"$BODY")"
call GET "/shifts/$SH1"
expect_status 403
expect_msg "ดูได้เฉพาะกะของตัวเอง"
at "$T_CASH1" "$BR_HQ"
call GET "/shifts?$range"
check "cash1 เห็นกะของตัวเอง 4 กะ" "4 แคชเชียร์หนึ่ง" "$(num length) $(jq -r '[.[].cashier] | unique | join(",")' <<<"$BODY")"
at "$T_BOSS" "$BR_HQ"
call @api2 GET "/shifts?$range"
check "boss เห็นทุกกะ ใหม่สุดก่อน" "$SH5,$SH4,$SH3,$SH2,$SH1" "$(jq -r '[.[].id] | map(tostring) | join(",")' <<<"$BODY")"
call GET "/shifts?$range&openOnly=1"
check "openOnly=1 (spec / mock)" "$SH5" "$(jq -r '[.[].id] | map(tostring) | join(",")' <<<"$BODY")"
call GET "/shifts?$range&openOnly=true"
check "openOnly=true ไม่ใช่ค่าที่ spec ใช้ → ไม่กรอง (เหมือน mock)" 5 "$(num length)"
call GET /shifts
expect_status 400
expect_msg "กรุณาเลือกช่วงวันที่ (ไม่เกิน 366 วัน)"
call GET "/shifts/variance-report?$range"
expect_status 200
echo "     variance-report: $(jq -c '.' <<<"$BODY")"
check "variance-report แคชเชียร์หนึ่ง (กะ 1 ขาด 50 ปรับ +50 → 0, กะ 2 / 3 ขาด 200)" "แคชเชียร์หนึ่ง 4 2 0 400 0 0 0" \
  "$(jq -r '.[] | select(.cashier == "แคชเชียร์หนึ่ง") | [.cashier, .shifts, .shortCount, .overCount, (.shortTotal + 0), (.overTotal + 0), .pending, .drawerOpens] | map(tostring) | join(" ")' <<<"$BODY")"
as -; branch -; terminal -

finish
