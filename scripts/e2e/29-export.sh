#!/usr/bin/env bash
# ข้อ 29 — export ข้อมูลทั้งบริษัท (docs/e2e-test-plan.md)
# A = std + กะ / บิลพร้อมใบกำกับ / บิลพัก / ใบโอน / ผู้จำหน่าย + ใบแจ้งหนี้ (SQL); B = บริษัทอื่นที่มีใบแจ้งหนี้ (ต้องไม่หลุดมาใน export ของ A)
E2E_STEP=29
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

ym=$(TZ=Asia/Bangkok date +%Y%m)
today=$(TZ=Asia/Bangkok date +%F)
num() { jq -r "$1 | if type == \"number\" then (. + 0 | tostring) else tostring end" <<<"$BODY"; }
at() { as "$1"; branch "$2"; terminal "${3:--}"; }
invoice() { sql "INSERT INTO dt_billing_invoices (company_id, invoice_no, plan_id, period_start, period_end, amount, issued_on, due_date, status)
  VALUES ($1, 'SUB-$ym-$2', 'pro', current_date, current_date + 30, 3490, current_date, current_date + 7, 'open')" >/dev/null; }

std_company E29B
B=$TENANT; B_CODE=$CODE
invoice "$B" "9$((RANDOM % 900 + 100))"
std_company E29
login "$CODE" boss pass1234; T_BOSS=$TOKEN
login "$CODE" cash1 pass1234; T_CASH1=$TOKEN
invoice "$TENANT" "8$((RANDOM % 900 + 100))"
BUYER_NAME="บริษัท ผู้ซื้อลับยี่สิบเก้า จำกัด"
CUSTOMER="ลูกค้าลับยี่สิบเก้า"
E2E_EXTRA_SECRETS="ผู้ซื้อลับยี่สิบเก้า ลูกค้าลับยี่สิบเก้า"
BUYER="{\"name\":\"$BUYER_NAME\",\"idType\":\"tax_id\",\"taxId\":\"0105550123451\",\"branchType\":\"head\",\"branchNo\":\"\",\"address\":\"กรุงเทพฯ\"}"
at "$T_CASH1" "$BR_HQ" 1
call POST /shifts/open '{"openingFloat":0,"openingDenominations":[]}'
call POST /sales "$(jq -nc --argjson p "$P_MS" --argjson m "$PM_CASH" --argjson b "$BUYER" '{items:[{productId:$p, factor:1, qty:1, serial:null}], freeSerials:[],
  payments:[{methodId:$m, amount:350, reference:""}], customer:"", expectedTotal:350, buyer:$b}')"
call POST /pos/held-bills "$(jq -nc --arg c "$CUSTOMER" --argjson p "$P_MS" --argjson b "$BUYER" '{customer:$c, items:[{productId:$p, factor:1, qty:1, serial:null}], freeSerials:[], total:350, buyer:$b}')"
call POST /transfers "{\"toBranchId\":$BR_BN,\"lines\":[{\"productId\":$P_MS,\"qty\":1,\"serials\":[]}],\"note\":\"\"}"
at "$T_BOSS" "$BR_HQ"
call POST /suppliers '{"code":"SUP1","name":"ผู้จำหน่าย","taxId":"0105550123451","active":true}'
pass "เตรียมข้อมูล A (tenant $TENANT) + B (tenant $B)"

case_id 29.1 "สิทธิ์ + หัวไฟล์"
call GET /company/export
expect_status 403
expect_msg "ดาวน์โหลดข้อมูลทั้งบริษัทได้เฉพาะเจ้าของบริษัท"
as "$T_OWNER"
call @api2 GET /company/export
expect_status 200
EXPORT=$BODY
printf '%s' "$EXPORT" >"$E2E_WORK/export.json"
check "schemaVersion / company" "1 $TENANT $CODE บริษัท ทดสอบ $CODE 0105550123451" \
  "$(num .schemaVersion) $(num .company.id) $(num .company.code) $(num .company.name) $(num .company.taxId)"
check "exportedAt" yes "$(jq -r '.exportedAt | test("^[0-9]{4}-[0-9]{2}-[0-9]{2}T.*Z$")' <<<"$EXPORT" | sed 's/true/yes/')"
expect_sql "SELECT action || ':' || user_id || ':' || detail FROM dt_audit_log WHERE tenant_id = $TENANT AND action = 'company_export'" \
  "company_export:$U_OWNER:ดาวน์โหลดข้อมูลทั้งบริษัท (JSON)"

case_id 29.2 "เนื้อหา"
tables=$(sql "SELECT string_agg(DISTINCT table_name, ' ') FROM information_schema.columns WHERE table_schema = 'public' AND column_name = 'tenant_id'
  AND table_name LIKE 'dt\_%' AND table_name <> 'dt_doc_counters'")
camel() { sed -E 's/^dt_//; s/_([a-z0-9])/\U\1/g' <<<"$1"; }
want="" ; got=""
for t in $tables; do
  extra=""; [[ "$t" == dt_audit_log ]] && extra=" AND action <> 'company_export'"   # audit ของการ export บันทึกหลังอ่านข้อมูล
  want+="$(camel "$t")=$(sql "SELECT count(*) FROM $t WHERE tenant_id = $TENANT$extra") "
  got+="$(camel "$t")=$(jq -r --arg k "$(camel "$t")" '.data[$k] | length' <<<"$EXPORT") "
done
check "ทุกตารางที่มี tenant_id: จำนวนแถว = DB ($(wc -w <<<"$tables") ตาราง)" "$want" "$got"
check "billingInvoices = ของบริษัทนี้เท่านั้น" "$(sql "SELECT count(*) FROM dt_billing_invoices WHERE company_id = $TENANT") 1" \
  "$(jq -r '.data.billingInvoices | length' <<<"$EXPORT") $(sql "SELECT count(*) FROM dt_billing_invoices WHERE company_id = $TENANT")"
check "ไม่มี docCounters / tenantId" "false 0" "$(jq -r '.data | has("docCounters")' <<<"$EXPORT") $(grep -o '"tenantId"' "$E2E_WORK/export.json" | grep -c . || true)"
check "camelCase (ไม่มี key แบบ snake_case)" 0 "$(jq -r '[.data[][] | keys[]] | unique | map(select(test("_"))) | length' <<<"$EXPORT")"
check "heldBills[].payload ถอดรหัสแล้ว" "$CUSTOMER $BUYER_NAME" "$(jq -r '.data.heldBills[0].payload | [.customer, .buyer.name] | join(" ")' <<<"$EXPORT")"
check "taxInvoices[].buyer ถอดรหัสแล้ว" "$BUYER_NAME 0105550123451" "$(jq -r '.data.taxInvoices[0].buyer | [.name, .taxId] | join(" ")' <<<"$EXPORT")"
check "ไม่มี key ที่ลงท้าย _enc / Enc" 0 "$(jq -r '[.data[][] | keys[]] | map(select(test("[Ee]nc$"))) | length' <<<"$EXPORT")"

case_id 29.3 "ค้นความลับในไฟล์"
check 'ไม่มี $2a$ / passwordHash / *Hash' "0 0 0" \
  "$(grep -c '\$2a\$' "$E2E_WORK/export.json" || true) $(grep -o 'passwordHash' "$E2E_WORK/export.json" | grep -c . || true) $(jq -r '[.data[][] | keys[]] | map(select(test("[Hh]ash$"))) | length' <<<"$EXPORT")"
check "ไม่มีรหัสของบริษัท B / ใบแจ้งหนี้ของ B" "0 0" \
  "$(grep -o "$B_CODE" "$E2E_WORK/export.json" | grep -c . || true) $(jq -r --argjson b "$B" '[.data.billingInvoices[] | select(.companyId == $b)] | length' <<<"$EXPORT")"
check "id ของผู้ใช้ทั้งหมด = ผู้ใช้ของบริษัทนี้" "$(sql "SELECT string_agg(id::text, ',' ORDER BY id) FROM dt_users WHERE tenant_id = $TENANT")" \
  "$(jq -r '[.data.users[].id] | sort | map(tostring) | join(",")' <<<"$EXPORT")"
check "ไม่มีกุญแจ / token ในไฟล์" 0 "$(grep -cF -e "$(env_get PII_HASH_KEY)" -e "$(env_get JWT_SECRET | cut -d: -f2)" -e "$T_OWNER" "$E2E_WORK/export.json" || true)"

case_id 29.4 "access log"
sleep 1
lines=$({ logs api1 "$E2E_SINCE"; logs api2 "$E2E_SINCE"; } | grep '"type":"access"' | grep '"/api/company/export"')
check "มี access log 2 บรรทัด (403 + 200) ไม่มี body" "2 0" "$(grep -c . <<<"$lines") $(jq -r 'select(.requestBody != null or .responseBody != null) | .path' <<<"$lines" | grep -c . || true)"
as -; branch -; terminal -

finish
