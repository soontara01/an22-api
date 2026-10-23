#!/usr/bin/env bash
# ข้อ 0 — เตรียมเครื่องมือ + stack + ชุดข้อมูลมาตรฐาน (docs/e2e-test-plan.md)
# ก่อนรัน: BUILD=1 scripts/e2e/reset.sh
E2E_STEP=0
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

case_id 0.1 "stack + migration"
for base in "$API1" "$API2" "$LB"; do
  check "GET $base/readyz" 200 "$(curl -s -o /dev/null -w '%{http_code}' "$base/readyz")"
done
expect_sql "SELECT string_agg(version, ',' ORDER BY installed_rank) FROM flyway_schema_history WHERE version IS NOT NULL" \
  "1,1.1,2,3,3.1,4,4.1,5,5.1,6,7,8,8.1,9"
expect_sql "SELECT count(*) FILTER (WHERE NOT success) FROM flyway_schema_history" 0
got=$(as_app "" "SELECT count(*) > 0 FROM flyway_schema_history" || true)
check "an22_app อ่าน flyway_schema_history (grant ของ afterMigrate)" t "$got"
for svc in api1 api2; do
  n=$(logs "$svc" 0 | grep -c 'flyway validate: ผ่าน (FLYWAY_MIGRATE=false)' || true)
  check "$svc log 'flyway validate: ผ่าน (FLYWAY_MIGRATE=false)'" 1 "$n"
done

case_id 0.2 "std_company E0"
std_company E0
t=$TENANT
expect_sql "SELECT count(*) FROM dt_users WHERE tenant_id = $t" 5
expect_sql "SELECT count(*) FROM dt_user_branches WHERE tenant_id = $t" 3
expect_sql "SELECT count(*) FROM dt_branches WHERE tenant_id = $t" 2
expect_sql "SELECT count(*) FROM dt_pos_terminals WHERE tenant_id = $t" 3
expect_sql "SELECT string_agg(code || ':' || type || ':' || sort_order || ':' || close_check, ',' ORDER BY sort_order)
  FROM dt_payment_methods WHERE tenant_id = $t" "CASH:cash:1:count,CARD:card:2:count,QR:qr:3:count,TRANSFER:transfer:4:confirm"
expect_sql "SELECT cash_rounding || ':' || (SELECT require_reference || ':' || reference_label || ':' || min_amount || ':' || fee_percent
  FROM dt_payment_methods WHERE tenant_id = $t AND code = 'CARD') FROM dt_payment_methods WHERE tenant_id = $t AND code = 'CASH'" \
  "0.25:true:เลขอนุมัติ:300.00:1.60"
expect_sql "SELECT string_agg(code || ':' || level, ',' ORDER BY level, code) FROM dt_categories WHERE tenant_id = $t" \
  "IT:1,CBL:2,MOUSE:2,NB:2,SVC:2"
expect_sql "SELECT count(*) FROM dt_products WHERE tenant_id = $t" 5
expect_sql "SELECT string_agg(sku || ':' || item_type || ':' || vat_type || ':' || serial_control || ':' || cost, ',' ORDER BY sku)
  FROM dt_products WHERE tenant_id = $t" \
  "CBL-001:stock:vat7:false:40.0000,EXM-001:stock:exempt:false:50.0000,MS-001:stock:vat7:false:200.0000,NB-001:stock:vat7:true:20000.0000,SVC-001:service:vat7:false:100.0000"
expect_sql "SELECT string_agg(u.unit || ':' || u.factor, ',') FROM dt_product_pack_units u WHERE u.tenant_id = $t" "กล่อง:10"
expect_sql "SELECT string_agg(p.sku || ':' || s.price || ':' || s.start_date || ':' || coalesce(s.end_date::text, '-'), ',' ORDER BY p.sku)
  FROM dt_sku_prices s JOIN dt_products p ON p.tenant_id = s.tenant_id AND p.id = s.product_id WHERE s.tenant_id = $t" \
  "CBL-001:99.00:2020-01-01:-,EXM-001:120.00:2020-01-01:-,MS-001:350.00:2020-01-01:-,NB-001:25000.00:2020-01-01:-,SVC-001:499.90:2020-01-01:-"
expect_sql "SELECT string_agg(b.code || ':' || p.sku || ':' || s.stock || '/' || s.avg_cost, ',' ORDER BY b.code, p.sku)
  FROM dt_branch_stock s JOIN dt_branches b ON b.tenant_id = s.tenant_id AND b.id = s.branch_id
  JOIN dt_products p ON p.tenant_id = s.tenant_id AND p.id = s.product_id WHERE s.tenant_id = $t" \
  "BN:MS-001:5/220.0000,HQ:CBL-001:100/40.0000,HQ:EXM-001:10/50.0000,HQ:MS-001:20/200.0000,HQ:NB-001:5/20000.0000"
expect_sql "SELECT count(*) || ':' || string_agg(DISTINCT status, ',') || ':' || string_agg(DISTINCT cost::text, ',')
  FROM dt_serial_numbers WHERE tenant_id = $t" "5:in_stock:20000.0000"
expect_sql "SELECT count(*) || ':' || string_agg(DISTINCT type, ',') FROM dt_stock_movements WHERE tenant_id = $t" "5:receive"
expect_sql "SELECT string_agg(action || '=' || n, ',' ORDER BY action) FROM (SELECT action, count(*) n FROM dt_audit_log
  WHERE tenant_id = $t GROUP BY action) a" "branch_change=1,price_change=5,signup=1,terminal_change=3,user_change=4"
expect_sql "SELECT string_agg(username || ':' || role || ':' || all_branches, ',' ORDER BY id) FROM dt_users WHERE tenant_id = $t" \
  "owner:owner:true,boss:admin:true,mgr:admin:false,cash1:user:false,cash2:user:false"
expect_sql "SELECT vat_registered || ':' || require_shift || ':' || manual_discount_max_percent || ':' || exchange_days || ':'
  || shift_variance_limit || ':' || staff_see_cost FROM dt_company_info WHERE tenant_id = $t" "true:true:5.00:7:100.00:false"
expect_sql "SELECT plan_id || ':' || active FROM dt_companies WHERE id = $t" "pro:true"

case_id 0.3 "lib ตรวจตัวเอง (hmac / jwt)"
ip="10.0.250.$((RANDOM % 250 + 1))"
login "$CODE" owner wrong-pass "X-Real-IP: $ip"
expect_status 401
expect_msg "รหัสบริษัท ชื่อผู้ใช้ หรือรหัสผ่านไม่ถูกต้อง"
expect_sql "SELECT encode(key_hash, 'hex') FROM dt_login_throttle WHERE key_hash = decode('$(hmac "shop:$CODE:owner")', 'hex') AND scope = 'account'" \
  "$(hmac "shop:$CODE:owner")"
expect_sql "SELECT failures FROM dt_login_throttle WHERE key_hash = decode('$(hmac "ip:$ip")', 'hex') AND scope = 'ip'" 1
expect_sql "SELECT length(key_hash) FROM dt_login_throttle WHERE key_hash = decode('$(hmac "ip:$ip")', 'hex')" 32
as "$(jwt "$(shop_claims "$TENANT" "$U_OWNER")")"
call @api2 GET /me
expect_status 200
expect_json .user.username owner
expect_json .company.code "$CODE"
forged=$(jwt "$(shop_claims "$TENANT" "$U_OWNER")")
as "${forged%.*}.$(printf 'x' | b64url)"
call GET /me
expect_status 401
login "$CODE" owner owner1234
expect_status 200
as "$TOKEN"
call @api1 GET /me
expect_status 200
expect_json .user.id "$U_OWNER"
as -

finish
