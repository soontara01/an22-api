#!/usr/bin/env bash
# ข้อ 6 — แยกบริษัท (tenant / RLS) (docs/e2e-test-plan.md)
# A + B = std_company + เอกสาร (กะ, บิลเมื่อวาน + ใบลดหนี้, บิลวันนี้, ใบโอน, บิลพัก, ผู้จำหน่าย, โปร) → token A แตะ id ของ B
E2E_STEP=6
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

today=$(TZ=Asia/Bangkok date +%F)
yesterday=$(TZ=Asia/Bangkok date -d "$today -1 day" +%F)
tomorrow=$(TZ=Asia/Bangkok date -d "$today +1 day" +%F)
need() { if [[ "$STATUS" != 200 ]]; then fail "เตรียมข้อมูล: $1 → $STATUS ${BODY:0:300}"; finish; exit 1; fi; }

# docs PREFIX → เอกสารของบริษัทที่ std_company เพิ่งสร้าง: ตัวแปร <PREFIX>_SHIFT _SALE_OLD _SALE _CN _TR _HELD _SUP _PRO _PRICE _SN
docs() {
  local x=$1
  as "$T_OWNER"; branch "$BR_HQ"; terminal 1
  call POST /suppliers '{"code":"SUP1","name":"ผู้จำหน่ายหนึ่ง","taxId":"0105550123451","active":true}'; need "ผู้จำหน่าย"
  printf -v "${x}_SUP" %s "$(jq -r .id <<<"$BODY")"
  call POST /promotions "{\"code\":\"PRO-$x\",\"name\":\"โปร $x\",\"type\":\"item_discount\",\"startDate\":\"$tomorrow\",\"endDate\":null,\"enabled\":true,
    \"scope\":{\"all\":false,\"productIds\":[$P_MS],\"categoryIds\":[]},\"minQty\":1,\"discount\":{\"kind\":\"percent\",\"value\":10,\"maxDiscount\":null}}"
  need "โปร"
  printf -v "${x}_PRO" %s "$(jq -r .id <<<"$BODY")"
  printf -v "${x}_PRICE" %s "$(sql "SELECT id FROM dt_sku_prices WHERE tenant_id = $TENANT AND product_id = $P_MS")"
  printf -v "${x}_SN" %s "$(sql "SELECT id FROM dt_serial_numbers WHERE tenant_id = $TENANT AND serial = 'NB0005'")"
  call POST /shifts/open '{"openingFloat":1000,"openingDenominations":[]}'; need "เปิดกะ"
  printf -v "${x}_SHIFT" %s "$(jq -r .id <<<"$BODY")"
  local sale
  sale=$(jq -nc --argjson p "$P_MS" --argjson m "$PM_CASH" '{items:[{productId:$p, factor:1, qty:1, serial:null}], freeSerials:[],
    payments:[{methodId:$m, amount:350, reference:""}], customer:"", expectedTotal:350}')
  call POST /sales "$sale"; need "บิล (จะเลื่อนเป็นเมื่อวาน)"
  printf -v "${x}_SALE_OLD" %s "$(jq -r .id <<<"$BODY")"
  sql "UPDATE dt_sales SET sold_at = sold_at - interval '1 day' WHERE tenant_id = $TENANT AND id = $(jq -r .id <<<"$BODY")" >/dev/null
  call POST "/sales/$(jq -r .id <<<"$BODY")/credit-notes" "{\"lines\":[{\"saleLineIndex\":0,\"qty\":1,\"restock\":true}],
    \"refunds\":[{\"methodId\":$PM_CASH,\"amount\":350,\"reference\":\"\"}],\"reason\":\"ลูกค้าคืน\",\"expectedTotal\":350}"
  need "ใบลดหนี้"
  printf -v "${x}_CN" %s "$(jq -r .id <<<"$BODY")"
  call POST /sales "$sale"; need "บิลวันนี้"
  printf -v "${x}_SALE" %s "$(jq -r .id <<<"$BODY")"
  call POST /transfers "{\"toBranchId\":$BR_BN,\"lines\":[{\"productId\":$P_MS,\"qty\":1,\"serials\":[]}],\"note\":\"\"}"; need "ใบโอน"
  printf -v "${x}_TR" %s "$(jq -r .id <<<"$BODY")"
  call POST /pos/held-bills "{\"customer\":\"ลูกค้า $x\",\"items\":[{\"productId\":$P_MS,\"factor\":1,\"qty\":1,\"serial\":null}],\"freeSerials\":[],\"total\":350}"
  need "บิลพัก"
  printf -v "${x}_HELD" %s "$(jq -r .id <<<"$BODY")"
  as -; branch -; terminal -
}

# snapshot TENANT → count + max(stamp) ของทุกตารางที่มี tenant_id
snapshot() {
  local q
  q=$(sql "SELECT string_agg(format('SELECT %L || '':'' || count(*) || '':'' || coalesce(max(%s)::text, ''-'') FROM %I WHERE tenant_id = $1',
      c.table_name, CASE WHEN EXISTS (SELECT 1 FROM information_schema.columns u WHERE u.table_name = c.table_name AND u.column_name = 'updated_at')
        THEN 'greatest(created_at, updated_at)' ELSE 'created_at' END, c.table_name), ' UNION ALL ' ORDER BY c.table_name)
    FROM information_schema.columns c JOIN information_schema.tables t ON t.table_name = c.table_name AND t.table_schema = c.table_schema
    WHERE c.table_schema = 'public' AND c.column_name = 'tenant_id' AND t.table_type = 'BASE TABLE'")
  sql "$q" | sort
}

std_company E6A
A_CODE=$CODE; A=$TENANT; T_A=$T_OWNER; A_HQ=$BR_HQ; A_BN=$BR_BN; A_MS=$P_MS; A_NB=$P_NB; A_CASH=$PM_CASH; A_CASH1=$U_CASH1
docs A
std_company E6B
B=$TENANT; T_B=$T_OWNER; B_HQ=$BR_HQ; B_MS=$P_MS; B_NB=$P_NB; B_CASH=$PM_CASH; B_CARD=$PM_CARD; B_CAT=$CAT_MOUSE; B_CASH1=$U_CASH1
docs B
as "$T_B"
call POST /pos-terminals "{\"id\":7,\"name\":\"เครื่อง 7\",\"posId\":\"P-B-7\",\"branchId\":$B_HQ,\"active\":true}"; need "เครื่อง 7 ของ B"
as -
pass "เตรียม A (tenant $A) + B (tenant $B): กะ, บิลเมื่อวาน + ใบลดหนี้, บิลวันนี้, ใบโอน, บิลพัก, ผู้จำหน่าย, โปร"

case_id 6.1 "token A แตะ id ของ B"
before=$(snapshot "$B")
n=0
while IFS='|' read -r method path body; do
  [[ -z "$method" ]] && continue
  path=$(eval "echo \"$path\"")
  body=$(eval "echo \"$body\"")
  as "$T_A"; branch "$A_HQ"; terminal 1
  call "$method" "$path" "${body:--}"
  n=$((n + 1))
  if [[ "$STATUS" == 404 ]]; then pass "$method $path → 404 $(jq -r .message <<<"$BODY")"; else fail "$method $path คาด 404 ได้ $STATUS ${BODY:0:200}"; fi
done <<'EOF'
GET|/branches/$B_HQ|
PUT|/branches/$B_HQ|{\"name\":\"แฮก\"}
GET|/users/$B_CASH1|
PUT|/users/$B_CASH1|{\"name\":\"แฮก\"}
PUT|/users/$B_CASH1/password|{\"password\":\"hack1234\"}
DELETE|/users/$B_CASH1|
PUT|/categories/$B_CAT|{\"name\":\"แฮก\"}
DELETE|/categories/$B_CAT|
GET|/suppliers/$B_SUP|
PUT|/suppliers/$B_SUP|{\"code\":\"SUP1\",\"name\":\"แฮก\",\"taxId\":\"0105550123451\",\"active\":true}
DELETE|/suppliers/$B_SUP|
PUT|/payment-methods/$B_CARD|{\"code\":\"CARD\",\"name\":\"แฮก\",\"type\":\"card\",\"active\":true}
DELETE|/payment-methods/$B_CARD|
GET|/products/$B_MS|
PUT|/products/$B_MS|{\"name\":\"แฮก\"}
DELETE|/products/$B_MS|
PUT|/products/$B_MS/stock|{\"delta\":1,\"unitCost\":1,\"note\":\"แฮก\"}
GET|/products/$B_MS/movements?from=$today&to=$today|
GET|/products/$B_MS/branch-stock|
GET|/products/$B_NB/serials|
POST|/products/$B_NB/serials|{\"serials\":[\"NB9999\"],\"unitCost\":1,\"note\":\"\"}
POST|/products/$B_NB/serials/remove|{\"ids\":[$B_SN],\"status\":\"damaged\",\"note\":\"แฮก\"}
PUT|/prices/$B_PRICE|{\"price\":1}
DELETE|/prices/$B_PRICE|
GET|/promotions/$B_PRO|
PUT|/promotions/$B_PRO|{\"name\":\"แฮก\"}
DELETE|/promotions/$B_PRO|
GET|/transfers/$B_TR|
POST|/transfers/$B_TR/receive|{\"lines\":[{\"productId\":$B_MS,\"receivedQty\":1,\"receivedSerials\":[]}],\"shortReason\":\"\"}
POST|/transfers/$B_TR/cancel|{\"reason\":\"แฮก\"}
GET|/shifts/$B_SHIFT|
GET|/shifts/$B_SHIFT/summary|
GET|/shifts/$B_SHIFT/movements|
GET|/shifts/$B_SHIFT/drawer-opens|
GET|/shifts/$B_SHIFT/adjustments|
POST|/shifts/$B_SHIFT/cash-movements|{\"type\":\"in\",\"amount\":1,\"reason\":\"แฮก\"}
POST|/shifts/$B_SHIFT/drawer-opens|{\"reason\":\"แฮก\"}
POST|/shifts/$B_SHIFT/lock|
POST|/shifts/$B_SHIFT/unlock|{\"username\":\"owner\",\"password\":\"owner1234\"}
POST|/shifts/$B_SHIFT/count-check|{\"counts\":[]}
POST|/shifts/$B_SHIFT/close|{\"counts\":[],\"cashDenominations\":[],\"depositAmount\":0,\"note\":\"\"}
POST|/shifts/$B_SHIFT/approve|
POST|/shifts/$B_SHIFT/adjustments|{\"methodId\":$B_CASH,\"amount\":1,\"reasonCode\":\"found_cash\",\"reason\":\"แฮก\"}
GET|/sales/$B_SALE|
POST|/sales/$B_SALE/void|{\"reason\":\"แฮก\"}
PUT|/sales/$B_SALE/status|{\"status\":\"shipped\"}
GET|/sales/$B_SALE_OLD/credit-notes|
POST|/sales/$B_SALE_OLD/credit-notes|{\"lines\":[],\"refunds\":[],\"reason\":\"แฮก\",\"expectedTotal\":0}
GET|/sales/$B_SALE/exchanges|
POST|/sales/$B_SALE/exchanges|{\"lines\":[]}
GET|/sales/$B_SALE/tax-invoice|
GET|/sales/$B_SALE/tax-invoices|
POST|/sales/$B_SALE/tax-invoice|{\"buyer\":{\"name\":\"แฮก\",\"taxId\":\"0105550123451\",\"branchType\":\"head\",\"branchNo\":\"\",\"address\":\"กรุงเทพฯ\"}}
POST|/sales/$B_SALE/tax-invoice/reissue|{\"reason\":\"แฮก\"}
DELETE|/pos/held-bills/$B_HELD|
EOF
as -; branch -; terminal -
echo "     ยิง $n route"
after=$(snapshot "$B")
echo "     snapshot B: $(grep -c . <<<"$before") ตาราง, แถวรวม $(awk -F: '{s += $2} END {print s}' <<<"$before")"
check "ข้อมูล B (count + max stamp ทุกตารางที่มี tenant_id) ก่อน = หลัง (md5)" "$(md5sum <<<"$before" | cut -c1-12)" "$(md5sum <<<"$after" | cut -c1-12)"
[[ "$before" == "$after" ]] || diff <(echo "$before") <(echo "$after") | head

case_id 6.2 "id ของ B ใน body"
a_counts() { sql "SELECT (SELECT count(*) FROM dt_sales WHERE tenant_id = $A) || ':' || (SELECT count(*) FROM dt_sku_prices WHERE tenant_id = $A)
  || ':' || (SELECT count(*) FROM dt_transfers WHERE tenant_id = $A) || ':' || (SELECT count(*) FROM dt_shifts WHERE tenant_id = $A)"; }
a_before=$(a_counts)
as "$T_A"; branch "$A_HQ"; terminal 1
call POST /sales "$(jq -nc --argjson p "$B_MS" --argjson m "$A_CASH" '{items:[{productId:$p, factor:1, qty:1, serial:null}], freeSerials:[],
  payments:[{methodId:$m, amount:350, reference:""}], customer:"", expectedTotal:350}')"
expect_status 400
expect_msg "ไม่พบสินค้า #$B_MS"
call POST /sales "$(jq -nc --argjson p "$A_MS" --argjson m "$B_CASH" '{items:[{productId:$p, factor:1, qty:1, serial:null}], freeSerials:[],
  payments:[{methodId:$m, amount:350, reference:""}], customer:"", expectedTotal:350}')"
expect_status 400
expect_msg "ไม่พบช่องทางชำระเงิน #$B_CASH"
call POST /prices "{\"productId\":$B_MS,\"price\":1,\"startDate\":\"$tomorrow\",\"endDate\":null,\"note\":\"\"}"
expect_status 400
expect_msg "ไม่พบ SKU"
call POST /transfers "{\"toBranchId\":$A_BN,\"lines\":[{\"productId\":$B_MS,\"qty\":1,\"serials\":[]}],\"note\":\"\"}"
expect_status 400
expect_msg "ไม่พบ SKU ในรายการโอน"
terminal 7
call POST /shifts/open '{"openingFloat":0,"openingDenominations":[]}'
expect_status 400
expect_msg "ไม่พบเครื่อง 7 ในทะเบียนเครื่อง POS กรุณาให้ผู้ดูแลตั้งค่าใหม่"
call POST /sales "$(jq -nc --argjson p "$A_MS" --argjson m "$A_CASH" '{items:[{productId:$p, factor:1, qty:1, serial:null}], freeSerials:[],
  payments:[{methodId:$m, amount:350, reference:""}], customer:"", expectedTotal:350}')"
expect_status 400
expect_msg "ไม่พบเครื่อง 7 ในทะเบียนเครื่อง POS กรุณาให้ผู้ดูแลตั้งค่าใหม่"
as -; branch -; terminal -
check "ไม่มีแถวใหม่ของ A (บิล:ราคา:ใบโอน:กะ)" "$a_before" "$(a_counts)"

case_id 6.3 "รายการของ A ไม่มีของ B"
as "$T_A"; branch "$A_HQ"; terminal 1
while IFS='|' read -r path table; do
  call GET "$(eval "echo \"$path\"")"
  if [[ "$STATUS" != 200 ]]; then fail "GET $path → $STATUS ${BODY:0:200}"; continue; fi
  ids=$(jq -r '[.[].id] | map(tostring) | join(",")' <<<"$BODY")
  total=$(jq 'length' <<<"$BODY")
  mine=$(sql "SELECT count(*) FROM $table WHERE tenant_id = $A AND id = ANY('{${ids}}'::bigint[])")
  if [[ "$total" -gt 0 && "$mine" == "$total" ]]; then pass "GET $path: $total รายการ ของ A ทั้งหมด"; else fail "GET $path: $total รายการ ของ A $mine"; fi
done <<'EOF'
/branches|dt_branches
/users|dt_users
/categories|dt_categories
/suppliers|dt_suppliers
/payment-methods|dt_payment_methods
/products|dt_products
/prices|dt_sku_prices
/promotions|dt_promotions
/transfers|dt_transfers
/shifts?from=$yesterday&to=$today|dt_shifts
/sales?from=$yesterday&to=$today|dt_sales
/credit-notes?from=$yesterday&to=$today|dt_credit_notes
/pos/held-bills|dt_held_bills
/audit-log?from=$yesterday&to=$today|dt_audit_log
EOF
call GET /pos-terminals
a_branches=$(sql "SELECT string_agg(id::text, ',') FROM dt_branches WHERE tenant_id = $A")
check "GET /pos-terminals: 3 เครื่อง, เครื่องที่สาขาไม่ใช่ของ A, มีเครื่อง 7 ไหม" "3 0 ไม่มี" \
  "$(jq -r --arg b "$a_branches" '($b | split(",") | map(tonumber)) as $mine | [length, ([.[] | select(.branchId as $x | $mine | index($x) | not)] | length),
     (if any(.[]; .id == 7) then "มี" else "ไม่มี" end)] | map(tostring) | join(" ")' <<<"$BODY")"
call GET "/products/$A_MS/branch-stock"
check "branch-stock ของ A: สาขาของ A เท่านั้น" "$(sql "SELECT count(*) FROM dt_branches WHERE tenant_id = $A AND active")" \
  "$(sql "SELECT count(*) FROM dt_branches WHERE tenant_id = $A AND id = ANY('{$(jq -r '[.[].branchId] | map(tostring) | join(",")' <<<"$BODY")}'::bigint[])")"
as -; branch -; terminal -

case_id 6.4 "as_app (an22_app ผ่าน pgbouncer)"
check "ไม่ตั้ง tenant → 0 แถว" 0 "$(as_app "" "SELECT count(*) FROM dt_products")"
check "tenant A → เห็นเฉพาะ A" "5:0" "$(as_app "$A" "SELECT count(*) || ':' || count(*) FILTER (WHERE tenant_id <> $A) FROM dt_products")"
out=$(as_app "$A" "INSERT INTO dt_categories (tenant_id, code, name, parent_id, level, active) VALUES ($B, 'RLS', 'แฮก', NULL, 1, true)" || true)
check "INSERT tenant_id B ใต้ A → ERROR RLS" yes "$(grep -q 'new row violates row-level security policy' <<<"$out" && echo yes || echo "no: $out")"
check "UPDATE แถวของ B ใต้ A → 0 แถว" 0 "$(as_app "$A" "WITH u AS (UPDATE dt_products SET name = 'แฮก' WHERE tenant_id = $B RETURNING 1) SELECT count(*) FROM u")"
check "DELETE แถวของ B ใต้ A → 0 แถว" 0 "$(as_app "$A" "WITH d AS (DELETE FROM dt_held_bills WHERE tenant_id = $B RETURNING 1) SELECT count(*) FROM d")"
expect_sql "SELECT count(*) || ':' || count(*) FILTER (WHERE name = 'แฮก') FROM dt_products WHERE tenant_id = $B" "5:0"
expect_sql "SELECT count(*) FROM dt_categories WHERE code = 'RLS'" 0

case_id 6.5 "as_app: ตารางที่ห้าม"
for q in "SELECT count(*) FROM dt_password_reset_tokens" "UPDATE dt_audit_log SET detail = 'แฮก' WHERE tenant_id = $A" "DELETE FROM dt_audit_log WHERE tenant_id = $A"; do
  out=$(as_app "$A" "$q" || true)
  check "$q → permission denied" yes "$(grep -q 'permission denied' <<<"$out" && echo yes || echo "no: $out")"
done
expect_sql "SELECT count(*) FROM dt_audit_log WHERE detail = 'แฮก'" 0

finish
