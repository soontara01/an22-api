#!/usr/bin/env bash
# ข้อ 11 — เครื่อง POS (docs/e2e-test-plan.md)
E2E_STEP=11
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

std_company E11
A=$TENANT; A_CODE=$CODE
login "$CODE" boss pass1234; T_BOSS=$TOKEN
login "$CODE" mgr pass1234; T_MGR=$TOKEN
login "$CODE" cash1 pass1234; T_CASH1=$TOKEN
term() { jq -nc --argjson id "$1" --arg n "$2" --arg p "$3" --argjson b "$4" --argjson a "${5:-true}" '{id:$id, name:$n, posId:$p, branchId:$b, active:$a}'; }
audit_last() { sql "SELECT action || ':' || user_id || ':' || coalesce(branch_id::text, '-') || ':' || ref_label || ':' || detail FROM dt_audit_log
  WHERE tenant_id = $TENANT AND action = 'terminal_change' ORDER BY id DESC LIMIT 1"; }

case_id 11.1 "สร้าง / ค่าผิด"
as "$T_CASH1"
call POST /pos-terminals "$(term 4 "เครื่อง 4" "" "$BR_HQ")"
expect_status 403
expect_msg "แก้ทะเบียนเครื่อง POS ได้เฉพาะผู้ดูแลระบบ"
call PUT /pos-terminals/1 '{"name":"แก้โดย staff"}'
expect_status 403
call DELETE /pos-terminals/2
expect_status 403
as "$T_BOSS"
call POST /branches '{"code":"OLD","name":"สาขาเก่า","branchNo":"00002","address":"กรุงเทพฯ","phone":"","receiptFooter":"","active":false}'
expect_status 200
BR_OLD=$(jq -r .id <<<"$BODY")
count=$(sql "SELECT count(*) FROM dt_pos_terminals WHERE tenant_id = $TENANT")
while IFS='|' read -r body msg; do
  call POST /pos-terminals "$body"
  expect_status 400
  expect_msg "$msg"
done <<EOF
$(term 0 "เครื่อง 0" "" "$BR_HQ")|เลขเครื่องต้องเป็นจำนวนเต็ม 1–999
$(term 1000 "เครื่อง 1000" "" "$BR_HQ")|เลขเครื่องต้องเป็นจำนวนเต็ม 1–999
$(term -1 "เครื่อง -1" "" "$BR_HQ")|เลขเครื่องต้องเป็นจำนวนเต็ม 1–999
$(term 1 "เครื่องซ้ำ" "" "$BR_HQ")|มีเครื่อง 1 อยู่แล้ว
$(term 4 "  " "" "$BR_HQ")|กรุณากรอกชื่อเครื่อง
$(term 4 "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA" "" "$BR_HQ")|ชื่อเครื่องต้องไม่เกิน 40 ตัวอักษร
$(term 4 "เครื่อง 4" "P-HQ-1" "$BR_HQ")|POS ID P-HQ-1 ใช้กับเครื่องอื่นแล้ว
$(term 4 "เครื่อง 4" "" 99999999)|กรุณาเลือกสาขาของเครื่อง
$(term 4 "เครื่อง 4" "" "$BR_OLD")|สาขานี้ปิดใช้งานแล้ว เครื่องที่ใช้งานต้องอยู่สาขาที่ใช้งาน
EOF
# เลขเครื่องทศนิยม — mock / pos-terminal.model.spec.ts: id 1.5 → ข้อความจำนวนเต็ม (B3: Jackson ตัดทศนิยมเป็น Integer เงียบ ๆ)
call POST /pos-terminals '{"id":1.5,"name":"เครื่อง","posId":"","branchId":'"$BR_HQ"',"active":true}'
check "เลขเครื่อง 1.5 → 400 ข้อความจำนวนเต็ม" "400 เลขเครื่องต้องเป็นจำนวนเต็ม 1–999" "$STATUS $(jq -r .message <<<"$BODY")"
call POST /pos-terminals '{"id":7.9,"name":"เครื่องทศนิยม","posId":"","branchId":'"$BR_HQ"',"active":false}'
check "เลขเครื่อง 7.9 → 400 ข้อความจำนวนเต็ม" "400 เลขเครื่องต้องเป็นจำนวนเต็ม 1–999" "$STATUS $(jq -r .message <<<"$BODY")"
sql "DELETE FROM dt_pos_terminals WHERE tenant_id = $TENANT AND name = 'เครื่องทศนิยม'" >/dev/null   # ถ้า B3 สร้างไว้ — ไม่ให้กระทบ case ถัดไป
expect_sql "SELECT count(*) FROM dt_pos_terminals WHERE tenant_id = $TENANT" "$count"
call POST /pos-terminals "$(term 4 " เครื่องสำรอง " "P-OLD-4" "$BR_OLD" false)"
expect_status 200   # เครื่องที่ปิดอยู่สาขาที่ปิดได้
expect_json '[.id, .name, .posId, .active] | map(tostring) | join(" ")' "4 เครื่องสำรอง P-OLD-4 false"
check "audit terminal_change เพิ่ม" "terminal_change:$U_BOSS:$BR_OLD:เครื่อง 4:เพิ่มเครื่อง เครื่องสำรอง · POS ID P-OLD-4 (ปิดใช้งาน)" "$(audit_last)"
call PUT /pos-terminals/4 '{"active":true}'
expect_status 400
expect_msg "สาขานี้ปิดใช้งานแล้ว เครื่องที่ใช้งานต้องอยู่สาขาที่ใช้งาน"
call PUT /pos-terminals/4 "{\"id\":9,\"branchId\":$BR_HQ,\"active\":true,\"posId\":\"\"}"
expect_status 200
expect_json '[.id, .branchId, .active] | map(tostring) | join(" ")' "4 $BR_HQ true"   # เลขเครื่องเปลี่ยนไม่ได้
expect_sql "SELECT string_agg(id::text, ',' ORDER BY id) FROM dt_pos_terminals WHERE tenant_id = $TENANT" "1,2,3,4"
check "audit terminal_change แก้" "terminal_change:$U_BOSS:$BR_HQ:เครื่อง 4:แก้เครื่อง เครื่องสำรอง · POS ID -" "$(audit_last)"
call PUT /pos-terminals/99 '{"name":"x"}'
expect_status 404
expect_msg "ไม่พบเครื่อง POS"

case_id 11.2 "เลขเครื่องเดียวกันคนละบริษัท"
B_CODE="E11B$(openssl rand -hex 2 | tr a-f A-F)"
as -
call POST /auth/signup "$(jq -nc --arg c "$B_CODE" '{companyCode:$c, companyName:"บริษัท B", taxId:"", vatRegistered:false,
  branch:{code:"HQ", name:"สำนักงานใหญ่", branchNo:"00000", address:"กรุงเทพฯ", phone:""},
  owner:{name:"เจ้าของ B", username:"owner", email:("owner@" + ($c | ascii_downcase) + ".e2e.test"), password:"owner1234"}, planId:"pro"}')"
expect_status 200
T_B=$(jq -r .token <<<"$BODY")
B_HQ=$(jq -r '.branches[0].id' <<<"$BODY")
B=$(sql "SELECT id FROM dt_companies WHERE code = '$B_CODE'")
as "$T_B"
call @api2 POST /pos-terminals "$(term 1 "เครื่อง 1 ของ B" "P-HQ-1" "$B_HQ")"
expect_status 200   # เลขเครื่อง + POS ID เดียวกับ A ได้
expect_sql "SELECT string_agg(tenant_id || ':' || id || ':' || pos_id, ',' ORDER BY tenant_id) FROM dt_pos_terminals WHERE tenant_id IN ($A, $B) AND id = 1" \
  "$A:1:P-HQ-1,$B:1:P-HQ-1"
expect_sql "SELECT string_agg(a.attname, ',' ORDER BY array_position(i.indkey, a.attnum)) FROM pg_index i
  JOIN pg_attribute a ON a.attrelid = i.indrelid AND a.attnum = ANY(i.indkey) WHERE i.indrelid = 'dt_pos_terminals'::regclass AND i.indisprimary" "tenant_id,id"
call GET /pos-terminals
expect_json '[.[] | .name] | join(",")' "เครื่อง 1 ของ B"

case_id 11.3 "mgr (HQ) กับเครื่องของสาขาอื่น"
as "$T_MGR"
call PUT /pos-terminals/3 '{"name":"แก้โดย mgr"}'
expect_status 403
expect_msg "แก้เครื่อง POS ได้เฉพาะสาขาของตัวเอง"
call PUT /pos-terminals/1 "{\"branchId\":$BR_BN}"
expect_status 403
expect_msg "แก้เครื่อง POS ได้เฉพาะสาขาของตัวเอง"
call POST /pos-terminals "$(term 5 "เครื่อง 5" "" "$BR_BN")"
expect_status 403
expect_msg "แก้เครื่อง POS ได้เฉพาะสาขาของตัวเอง"
call DELETE /pos-terminals/3
expect_status 403
expect_msg "แก้เครื่อง POS ได้เฉพาะสาขาของตัวเอง"
call PUT /pos-terminals/2 '{"name":"เครื่อง 2 (mgr)"}'
expect_status 200   # เครื่องของ HQ แก้ได้
expect_sql "SELECT string_agg(id || ':' || name || ':' || branch_id, ',' ORDER BY id) FROM dt_pos_terminals WHERE tenant_id = $TENANT AND id IN (1, 3)" \
  "1:เครื่อง 1:$BR_HQ,3:เครื่อง 3:$BR_BN"

case_id 11.4 "ปิด / ลบเครื่องที่มีกะหรือเอกสาร"
as "$T_CASH1"; branch "$BR_HQ"; terminal 1
call POST /shifts/open '{"openingFloat":0,"openingDenominations":[]}'
expect_status 200
branch -; terminal -
as "$T_BOSS"
call PUT /pos-terminals/1 '{"active":false}'
expect_status 400
expect_msg "เครื่อง 1 มีกะที่เปิดอยู่ ปิดใช้งานไม่ได้"
call DELETE /pos-terminals/1
expect_status 400
expect_msg "เครื่อง 1 มีกะหรือเอกสารแล้ว ลบไม่ได้ (ปิดใช้งานแทน)"
branch "$BR_HQ"; terminal 2
call POST /shifts/open '{"openingFloat":0,"openingDenominations":[]}'
expect_status 200
shift2=$(jq -r .id <<<"$BODY")
call POST "/shifts/$shift2/close" "{\"counts\":[{\"methodId\":$PM_CASH,\"counted\":0},{\"methodId\":$PM_CARD,\"counted\":0},
  {\"methodId\":$PM_QR,\"counted\":0},{\"methodId\":$PM_TRANSFER,\"confirmed\":true}],\"cashDenominations\":[],\"depositAmount\":0,\"note\":\"\"}"
expect_status 200
branch -; terminal -
call DELETE /pos-terminals/2
expect_status 400
expect_msg "เครื่อง 2 มีกะหรือเอกสารแล้ว ลบไม่ได้ (ปิดใช้งานแทน)"
call PUT /pos-terminals/2 '{"active":false}'
expect_status 200   # กะปิดแล้ว → ปิดใช้งานได้
call @api2 DELETE /pos-terminals/4
expect_status 200
expect_sql "SELECT string_agg(id || ':' || active, ',' ORDER BY id) FROM dt_pos_terminals WHERE tenant_id = $TENANT" "1:true,2:false,3:true"
expect_sql "SELECT ref_label || ':' || detail FROM dt_audit_log WHERE tenant_id = $TENANT AND action = 'terminal_change' ORDER BY id DESC LIMIT 1" "เครื่อง 4:ลบเครื่อง"
as -

finish
