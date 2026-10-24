#!/usr/bin/env bash
# ข้อ 33 — purge ข้อมูลบริษัท (docs/e2e-test-plan.md)
# A = std + บิล (ใบกำกับ), กะ, ใบโอน, ใบลดหนี้, ใบเปลี่ยน, บิลพัก, ผู้จำหน่าย, โปร, ลิงก์ reset, ใบแจ้งหนี้; B = std (ต้องไม่ถูกแตะ)
E2E_STEP=33
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

ym=$(TZ=Asia/Bangkok date +%Y%m)
today=$(TZ=Asia/Bangkok date +%F)
tomorrow=$(TZ=Asia/Bangkok date -d tomorrow +%F)
plus30=$(TZ=Asia/Bangkok date -d "$today +30 day" +%F)
num() { jq -r "$1 | if type == \"number\" then (. + 0 | tostring) else tostring end" <<<"$BODY"; }
at() { as "$1"; branch "$2"; terminal "${3:--}"; }
need() { [[ "$STATUS" == 200 ]] || fail "เตรียมข้อมูล: $1 → $STATUS ${BODY:0:200}"; }
snapshot() { # tenant → count + max stamp ทุกตารางที่มี tenant_id
  local q
  q=$(sql "SELECT string_agg(format('SELECT %L || '':'' || count(*) || '':'' || coalesce(max(%s)::text, ''-'') FROM %I WHERE tenant_id = $1',
      c.table_name, CASE WHEN EXISTS (SELECT 1 FROM information_schema.columns u WHERE u.table_name = c.table_name AND u.column_name = 'updated_at')
        THEN 'greatest(created_at, updated_at)' ELSE 'created_at' END, c.table_name), ' UNION ALL ' ORDER BY c.table_name)
    FROM information_schema.columns c JOIN information_schema.tables t ON t.table_name = c.table_name AND t.table_schema = c.table_schema
    WHERE c.table_schema = 'public' AND c.column_name = 'tenant_id' AND t.table_type = 'BASE TABLE'")
  sql "$q" | sort
}
tenant_rows() { # tenant → ผลรวมแถวทุกตารางที่มี tenant_id + ตารางที่ยังมีแถว
  snapshot "$1" | awk -F: '{s += $2; if ($2 > 0) t = t $1 "=" $2 " "} END {print s + 0 " " t}' | sed 's/ $//'
}

std_company E33B
B=$TENANT; T_B=$T_OWNER
std_company E33
A=$TENANT; A_CODE=$CODE; A_NAME="บริษัท ทดสอบ $CODE"
login "$CODE" boss pass1234; T_BOSS=$TOKEN
login "$CODE" cash1 pass1234; T_CASH1=$TOKEN
BUYER='{"name":"ผู้ซื้อ purge","idType":"tax_id","taxId":"0105550123451","branchType":"head","branchNo":"","address":"กรุงเทพฯ"}'
sale() { jq -nc --argjson i "$1" --argjson t "$2" --argjson m "$PM_CASH" --argjson b "${3:-null}" '{items:$i, freeSerials:[], payments:[{methodId:$m, amount:$t, reference:""}], customer:"", expectedTotal:$t, buyer:$b}'; }
at "$T_CASH1" "$BR_HQ" 1
call POST /shifts/open '{"openingFloat":0,"openingDenominations":[]}'; need กะ
call POST /sales "$(sale "[{\"productId\":$P_MS,\"factor\":1,\"qty\":2,\"serial\":null},{\"productId\":$P_NB,\"factor\":1,\"qty\":1,\"serial\":\"NB0001\"}]" 25700 "$BUYER")"; need "บิล + ใบกำกับ"
SA=$(jq -r .id <<<"$BODY")
call POST "/sales/$SA/exchanges" "{\"lines\":[{\"saleLineIndex\":1,\"qty\":1,\"newSerial\":\"NB0002\",\"restock\":true}],\"reason\":\"เปลี่ยน\"}"; need ใบเปลี่ยน
call POST /sales "$(sale "[{\"productId\":$P_MS,\"factor\":1,\"qty\":1,\"serial\":null}]" 350)"; need "บิลเมื่อวาน"
SY=$(jq -r .id <<<"$BODY"); sql "UPDATE dt_sales SET sold_at = sold_at - interval '1 day' WHERE id = $SY" >/dev/null
call POST /pos/held-bills "{\"customer\":\"ลูกค้า\",\"items\":[{\"productId\":$P_MS,\"factor\":1,\"qty\":1,\"serial\":null}],\"freeSerials\":[],\"total\":350}"; need บิลพัก
call POST /transfers "{\"toBranchId\":$BR_BN,\"lines\":[{\"productId\":$P_MS,\"qty\":1,\"serials\":[]}],\"note\":\"\"}"; need ใบโอน
at "$T_BOSS" "$BR_HQ" 2
call POST /shifts/open '{"openingFloat":1000,"openingDenominations":[]}'; need "กะ boss"
call POST "/sales/$SY/credit-notes" "{\"lines\":[{\"saleLineIndex\":0,\"qty\":1,\"restock\":true}],\"refunds\":[{\"methodId\":$PM_CASH,\"amount\":350,\"reference\":\"\"}],\"reason\":\"คืน\",\"expectedTotal\":350}"; need ใบลดหนี้
call POST /suppliers '{"code":"SUP1","name":"ผู้จำหน่าย","taxId":"0105550123451","active":true}'; need ผู้จำหน่าย
call POST /promotions "{\"code\":\"PRO-P\",\"name\":\"โปร\",\"type\":\"item_discount\",\"startDate\":\"$tomorrow\",\"endDate\":null,\"enabled\":true,
  \"scope\":{\"all\":false,\"productIds\":[$P_MS],\"categoryIds\":[]},\"minQty\":1,\"discount\":{\"kind\":\"percent\",\"value\":10,\"maxDiscount\":null}}"; need โปร
call PUT /settings/menu-access '{"userMenus":["pos"]}'; need สิทธิ์เมนู
sql "INSERT INTO dt_password_reset_tokens (company_id, user_id, token_hash, expires_at) VALUES ($A, $U_OWNER, decode('$(hmac "e2e-33-$RANDOM")', 'hex'), now() + interval '1 hour');
  INSERT INTO dt_billing_invoices (company_id, invoice_no, plan_id, period_start, period_end, amount, issued_on, due_date, status, paid_at)
  VALUES ($A, 'SUB-$ym-5$((RANDOM % 900 + 100))', 'pro', current_date, current_date + 30, 3490, current_date, current_date + 7, 'paid', now())" >/dev/null
read -r a_rows a_tables <<<"$(tenant_rows "$A")"
echo "     ข้อมูล A ก่อน purge: $a_rows แถว · $a_tables"
check "เตรียม: ทุกชนิดมีแถว" "ok" "$(for t in dt_sales dt_tax_invoices dt_shifts dt_transfers dt_credit_notes dt_exchanges dt_held_bills dt_suppliers dt_promotions dt_audit_log dt_menu_access dt_stock_movements; do
  [[ " $a_tables " == *" $t="* ]] || echo -n "$t "; done; echo -n ok)"
expect_sql "SELECT (SELECT count(*) FROM dt_password_reset_tokens WHERE company_id = $A) || ':' || (SELECT count(*) FROM dt_billing_invoices WHERE company_id = $A)" "1:1"
as -; call POST /platform/auth/login '{"username":"platform","password":"platform"}'; T_P=$(jq -r .token <<<"$BODY")
B_BEFORE=$(snapshot "$B")

case_id 33.1 "purge ไม่มีคำขอ / ก่อนครบ 30 วัน"
as "$T_P"
call POST "/platform/companies/$A/purge"
expect_status 400
expect_msg "บริษัทนี้ไม่ได้ขอลบข้อมูล"
as "$T_OWNER"
call POST /company/deletion "{\"companyCode\":\"$A_CODE\",\"password\":\"owner1234\",\"reason\":\"ปิดกิจการ\"}"
expect_status 200
as "$T_P"
call POST "/platform/companies/$A/purge"
expect_status 400
expect_msg "ลบข้อมูลได้ตั้งแต่วันที่ $(date -d "$plus30" +%-d)/$(date -d "$plus30" +%-m)/$(( $(date -d "$plus30" +%Y) + 543 ))"
check "ข้อมูล A ยังอยู่ (+ audit deletion_request 1 แถว)" "$((a_rows + 1))" "$(tenant_rows "$A" | cut -d' ' -f1)"

case_id 33.2 "purge"
sql "UPDATE dt_companies SET purge_after = current_date WHERE id = $A" >/dev/null
call @api2 POST "/platform/companies/$A/purge"
expect_status 200
check "deletedAt / name / owner / usage" "yes $A_NAME null 0 0 0" \
  "$(jq -r '.deletedAt != null' <<<"$BODY" | sed 's/true/yes/') $(num .name) $(num .owner) $(num .usage.branches) $(num .usage.users) $(num .usage.terminals)"
check "ทุกตารางที่มี tenant_id = 0 แถว" "0" "$(tenant_rows "$A")"
expect_sql "SELECT count(*) FROM dt_password_reset_tokens WHERE company_id = $A" 0
expect_sql "SELECT (deleted_at IS NOT NULL) || ':' || deleted_name || ':' || active || ':' || suspended_reason || ':' || code FROM dt_companies WHERE id = $A" \
  "true:$A_NAME:false:deletion:$A_CODE"
expect_sql "SELECT count(*) FROM dt_billing_invoices WHERE company_id = $A" 1
check "บริษัท B (count + max stamp ทุกตาราง) ไม่เปลี่ยน" "$(md5sum <<<"$B_BEFORE" | cut -c1-12)" "$(md5sum <<<"$(snapshot "$B")" | cut -c1-12)"

case_id 33.3 "หลัง purge"
login "$A_CODE" owner owner1234
expect_status 401
expect_msg "รหัสบริษัท ชื่อผู้ใช้ หรือรหัสผ่านไม่ถูกต้อง"
for t in "$T_OWNER" "$T_BOSS"; do
  as "$t"; call GET /me
  expect_status 401
done
as -
call POST /auth/signup "$(jq -nc --arg c "${A_CODE,,}" '{companyCode:$c, companyName:"ใหม่", taxId:"", vatRegistered:false,
  branch:{code:"HQ", name:"สำนักงานใหญ่", branchNo:"00000", address:"กรุงเทพฯ", phone:""},
  owner:{name:"เจ้าของ", username:"owner", email:"owner@new.e2e.test", password:"owner1234"}, planId:"pro"}')"
expect_status 400
expect_msg "รหัสบริษัทนี้ถูกใช้แล้ว"
as "$T_P"
for req in "PUT|/platform/companies/$A/plan|{\"planId\":\"pro\"}" "POST|/platform/companies/$A/suspend|{\"note\":\"x\"}" \
  "POST|/platform/companies/$A/reactivate|-" "PUT|/platform/companies/$A/owner-password|{\"password\":\"abcd1234\"}" "POST|/platform/companies/$A/purge|-"; do
  IFS='|' read -r m p b <<<"$req"
  call "$m" "$p" "$b"
  check "$m ${p#/platform/companies/$A} → 400 ลบแล้ว" "400 บริษัทนี้ถูกลบข้อมูลแล้ว" "$STATUS $(jq -r .message <<<"$BODY")"
done
call GET "/platform/companies/$A"
expect_status 200
expect_json '[(.deletedAt != null), .status, (.invoices | length)] | map(tostring) | join(" ")' "true $(num .status) 1"
echo "     detail หลัง purge: status=$(num .status), invoices=$(num '.invoices | length')"
as "$T_B"
call GET /me
expect_status 200   # บริษัทอื่นใช้งานปกติ
as -

finish
