#!/usr/bin/env bash
# ข้อ 38 — ตรวจความสอดคล้องทั้ง DB หลังรันทุกข้อ (ทุกบริษัท) (docs/e2e-test-plan.md)
# แต่ละ check = query ที่คืน "แถวที่ผิด" (ว่าง = ผ่าน) — แสดงตัวอย่างไม่เกิน 5 แถวเมื่อไม่ผ่าน
E2E_STEP=38
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

# none "คำอธิบาย" "<sql ที่คืนแถวที่ผิด>" — นับแถว + ตัวอย่าง
none() {
  local rows n
  rows=$(sql "$2")
  n=$(grep -c . <<<"$rows" || true)
  if ((n == 0)); then pass "$1"; else fail "$1 — ผิด $n แถว: $(head -5 <<<"$rows" | tr '\n' ';')"; fi
}
info() { echo "     $1: $(sql "$2")"; }

case_id 38.0 "ขนาดข้อมูลที่ตรวจ"
info "บริษัท" "SELECT count(*) FROM dt_companies"
info "บิล / บรรทัด / การชำระ" "SELECT (SELECT count(*) FROM dt_sales) || ' / ' || (SELECT count(*) FROM dt_sale_lines) || ' / ' || (SELECT count(*) FROM dt_sale_payments)"
info "ใบกำกับ / ใบลดหนี้ / ใบเปลี่ยน / ใบโอน / กะ" "SELECT (SELECT count(*) FROM dt_tax_invoices) || ' / ' || (SELECT count(*) FROM dt_credit_notes) || ' / ' || (SELECT count(*) FROM dt_exchanges)
  || ' / ' || (SELECT count(*) FROM dt_transfers) || ' / ' || (SELECT count(*) FROM dt_shifts)"
info "branch_stock / movement / serial" "SELECT (SELECT count(*) FROM dt_branch_stock) || ' / ' || (SELECT count(*) FROM dt_stock_movements) || ' / ' || (SELECT count(*) FROM dt_serial_numbers)"
info "audit" "SELECT count(*) FROM dt_audit_log"

case_id 38.1 "สต็อก = ผลรวม movement = balance ของ movement ล่าสุด"
none "branch_stock.stock = ผลรวม movement.qty (ต่อบริษัท / สาขา / SKU)" "
  SELECT b.tenant_id, b.branch_id, b.product_id, b.stock, coalesce(m.total, 0) FROM dt_branch_stock b
  LEFT JOIN (SELECT tenant_id, branch_id, product_id, sum(qty) total FROM dt_stock_movements GROUP BY 1, 2, 3) m USING (tenant_id, branch_id, product_id)
  WHERE b.stock <> coalesce(m.total, 0)"
none "movement ที่ไม่มีแถว branch_stock" "
  SELECT DISTINCT m.tenant_id, m.branch_id, m.product_id FROM dt_stock_movements m
  WHERE NOT EXISTS (SELECT 1 FROM dt_branch_stock b WHERE b.tenant_id = m.tenant_id AND b.branch_id = m.branch_id AND b.product_id = m.product_id)"
none "branch_stock.stock = balance_qty ของ movement ล่าสุด" "
  SELECT b.tenant_id, b.branch_id, b.product_id, b.stock, l.balance_qty FROM dt_branch_stock b
  JOIN (SELECT DISTINCT ON (tenant_id, branch_id, product_id) tenant_id, branch_id, product_id, balance_qty FROM dt_stock_movements
        ORDER BY tenant_id, branch_id, product_id, id DESC) l USING (tenant_id, branch_id, product_id)
  WHERE b.stock <> l.balance_qty"
none "balance_qty ต่อเนื่องทุก movement (balance ก่อนหน้า + qty)" "
  SELECT tenant_id, branch_id, product_id, id FROM (
    SELECT *, lag(balance_qty, 1, 0) OVER (PARTITION BY tenant_id, branch_id, product_id ORDER BY id) prev FROM dt_stock_movements) x
  WHERE prev + qty <> balance_qty"
none "สต็อกไม่ติดลบ" "SELECT tenant_id, branch_id, product_id, stock FROM dt_branch_stock WHERE stock < 0"

case_id 38.2 "SKU คุม serial: stock = จำนวน serial in_stock ของสาขา"
none "stock = count(serial in_stock) ต่อสาขา" "
  WITH s AS (SELECT tenant_id, branch_id, product_id, count(*) n FROM dt_serial_numbers WHERE status = 'in_stock' GROUP BY 1, 2, 3),
       b AS (SELECT b.tenant_id, b.branch_id, b.product_id, b.stock FROM dt_branch_stock b JOIN dt_products p ON p.tenant_id = b.tenant_id AND p.id = b.product_id
             WHERE p.serial_control)
  SELECT tenant_id, branch_id, product_id, coalesce(b.stock, 0), coalesce(s.n, 0) FROM b FULL JOIN s USING (tenant_id, branch_id, product_id)
  WHERE coalesce(b.stock, 0) <> coalesce(s.n, 0)"
none "serial ไม่ซ้ำในบริษัท" "SELECT tenant_id, serial, count(*) FROM dt_serial_numbers GROUP BY 1, 2 HAVING count(*) > 1"

case_id 38.3 "บิล paid: ผลรวมบรรทัด / VAT / การชำระ"
none "ผลรวม amount ของบรรทัด = total" "
  SELECT s.tenant_id, s.order_no, s.total, sum(l.amount) FROM dt_sales s JOIN dt_sale_lines l ON l.tenant_id = s.tenant_id AND l.sale_id = s.id
  WHERE s.status = 'paid' GROUP BY s.tenant_id, s.id HAVING sum(l.amount) <> s.total"
none "ผลรวม vat ของบรรทัด = vat" "
  SELECT s.tenant_id, s.order_no, s.vat, sum(l.vat) FROM dt_sales s JOIN dt_sale_lines l ON l.tenant_id = s.tenant_id AND l.sale_id = s.id
  WHERE s.status = 'paid' GROUP BY s.tenant_id, s.id HAVING sum(l.vat) <> s.vat"
none "ผลรวมการชำระ = total + rounding" "
  SELECT s.tenant_id, s.order_no, s.total + s.rounding, sum(p.amount) FROM dt_sales s JOIN dt_sale_payments p ON p.tenant_id = s.tenant_id AND p.sale_id = s.id
  WHERE s.status = 'paid' AND EXISTS (SELECT 1 FROM dt_sale_lines l WHERE l.tenant_id = s.tenant_id AND l.sale_id = s.id)
  GROUP BY s.tenant_id, s.id HAVING sum(p.amount) <> s.total + s.rounding"
# SEED (DEMO, SO-…) = ออร์เดอร์ก่อนมี POS ของ mock — ไม่มีบรรทัด / การชำระโดยตั้งใจ (db/seed/V8_1)
SEED="NOT (s.tenant_id = 1 AND s.order_no LIKE 'SO-%')"
info "บิล seed ของ DEMO ที่ไม่ตรวจบรรทัด / เลข" "SELECT count(*) FROM dt_sales s WHERE NOT ($SEED)"
none "บิลทุกใบมีบรรทัดและการชำระ (ยกเว้น seed)" "
  SELECT s.tenant_id, s.order_no FROM dt_sales s WHERE $SEED AND (NOT EXISTS (SELECT 1 FROM dt_sale_lines l WHERE l.tenant_id = s.tenant_id AND l.sale_id = s.id)
     OR NOT EXISTS (SELECT 1 FROM dt_sale_payments p WHERE p.tenant_id = s.tenant_id AND p.sale_id = s.id))"

case_id 38.4 "ใบลดหนี้ / ใบกำกับของบิล"
none "ผลรวมใบลดหนี้ต่อบิล ≤ total ของบิล" "
  SELECT s.tenant_id, s.order_no, s.total, sum(c.total) FROM dt_sales s JOIN dt_credit_notes c ON c.tenant_id = s.tenant_id AND c.sale_id = s.id
  GROUP BY s.tenant_id, s.id HAVING sum(c.total) > s.total"
none "dt_sales.tax_invoice_no ของบิลที่ใช้ได้ชี้ใบที่ยังไม่ยกเลิกของบิลนั้น" "
  SELECT s.tenant_id, s.order_no, s.tax_invoice_no FROM dt_sales s WHERE s.status <> 'cancelled' AND s.tax_invoice_no IS NOT NULL AND NOT EXISTS (
    SELECT 1 FROM dt_tax_invoices t WHERE t.tenant_id = s.tenant_id AND t.sale_id = s.id AND t.invoice_no = s.tax_invoice_no AND t.cancelled_at IS NULL)"
# บิลที่ยกเลิกคงเลขใบที่ถูกยกเลิกพร้อมบิล (= voidSale ของ mock)
none "dt_sales.tax_invoice_no ของบิลที่ยกเลิกชี้ใบของบิลนั้น (ยกเลิกแล้ว)" "
  SELECT s.tenant_id, s.order_no, s.tax_invoice_no FROM dt_sales s WHERE s.status = 'cancelled' AND s.tax_invoice_no IS NOT NULL AND NOT EXISTS (
    SELECT 1 FROM dt_tax_invoices t WHERE t.tenant_id = s.tenant_id AND t.sale_id = s.id AND t.invoice_no = s.tax_invoice_no AND t.cancelled_at IS NOT NULL)"
none "บิลที่ยังใช้ได้มีใบกำกับใช้งานได้ไม่เกิน 1 ใบ" "
  SELECT tenant_id, sale_id, count(*) FROM dt_tax_invoices WHERE cancelled_at IS NULL GROUP BY 1, 2 HAVING count(*) > 1"
none "บิลที่ยกเลิกไม่มีใบกำกับที่ยังใช้ได้" "
  SELECT s.tenant_id, s.order_no FROM dt_sales s JOIN dt_tax_invoices t ON t.tenant_id = s.tenant_id AND t.sale_id = s.id
  WHERE s.status = 'cancelled' AND t.cancelled_at IS NULL"

case_id 38.5 "เลขเอกสาร: dt_doc_counters.last_no = เลขสูงสุด, ไม่ข้าม / ซ้ำ"
# เลข = prefix + ตัวเลข (SH- 2 หลัก, อื่น 4 หลัก — DocNumbers.next(prefix, width)); ไม่นับบิล seed
DOCS="SELECT tenant_id, order_no no FROM dt_sales s WHERE $SEED UNION ALL SELECT tenant_id, invoice_no FROM dt_tax_invoices
  UNION ALL SELECT tenant_id, cn_no FROM dt_credit_notes UNION ALL SELECT tenant_id, ex_no FROM dt_exchanges
  UNION ALL SELECT tenant_id, shift_no FROM dt_shifts UNION ALL SELECT tenant_id, transfer_no FROM dt_transfers"
none "ทุกเลขเอกสารมี counter ของ prefix นั้น" "
  SELECT d.tenant_id, d.no FROM ($DOCS) d WHERE NOT EXISTS (
    SELECT 1 FROM dt_doc_counters c WHERE c.tenant_id = d.tenant_id AND left(d.no, length(c.prefix)) = c.prefix
      AND substr(d.no, length(c.prefix) + 1) ~ '^[0-9]{2,4}\$')"
none "last_no = เลขสูงสุด = จำนวนเอกสาร (ไม่ข้าม / ซ้ำ)" "
  SELECT c.tenant_id, c.prefix, c.last_no, max(substr(d.no, length(c.prefix) + 1)::int), count(d.no), count(DISTINCT d.no) FROM dt_doc_counters c
  LEFT JOIN ($DOCS) d ON d.tenant_id = c.tenant_id AND left(d.no, length(c.prefix)) = c.prefix AND substr(d.no, length(c.prefix) + 1) ~ '^[0-9]{2,4}\$'
  GROUP BY c.tenant_id, c.prefix, c.last_no
  HAVING c.last_no <> coalesce(max(substr(d.no, length(c.prefix) + 1)::int), 0) OR c.last_no <> count(d.no) OR count(d.no) <> count(DISTINCT d.no)"
# ใบ SUB- ที่ script ข้อ 29 / 30 / 32 / 33 ใส่เองด้วย SQL (เลข 5xxx–9xxx นอก counter) ไม่ได้ออกโดย api — ไม่นับ (แสดงให้เห็น)
info "SUB- ที่ script ใส่ด้วย SQL (ไม่นับ)" "SELECT coalesce(string_agg(i.invoice_no, ', ' ORDER BY i.invoice_no), '-') FROM dt_billing_invoices i JOIN dt_platform_doc_counters c
  ON left(i.invoice_no, length(c.prefix)) = c.prefix WHERE right(i.invoice_no, 4)::int > c.last_no"
none "ใบแจ้งหนี้ platform (SUB-) ที่ api ออก: last_no = เลขสูงสุด = จำนวน (ไม่ข้าม / ซ้ำ)" "
  SELECT c.prefix, c.last_no, max(right(i.invoice_no, 4)::int), count(i.id) FROM dt_platform_doc_counters c
  LEFT JOIN dt_billing_invoices i ON left(i.invoice_no, length(c.prefix)) = c.prefix AND length(i.invoice_no) = length(c.prefix) + 4
    AND right(i.invoice_no, 4)::int <= c.last_no
  GROUP BY c.prefix, c.last_no HAVING c.last_no <> coalesce(max(right(i.invoice_no, 4)::int), 0) OR c.last_no <> count(i.id)"
info "counter / เอกสารที่ตรวจ" "SELECT (SELECT count(*) FROM dt_doc_counters) || ' / ' || (SELECT count(*) FROM ($DOCS) d) || ' (+ platform ' || (SELECT count(*) FROM dt_platform_doc_counters) || ' / ' || (SELECT count(*) FROM dt_billing_invoices) || ')'"

case_id 38.6 "audit"
ACTIONS=$(sql "SELECT string_agg(a, ' ') FROM (SELECT unnest(regexp_matches(pg_get_constraintdef(oid), '''([a-z_]+)''', 'g')) a FROM pg_constraint WHERE conname = 'dt_audit_log_action_check') x")
n_all=$(wc -w <<<"$ACTIONS")
missing=$(sql "SELECT coalesce(string_agg(a, ', ' ORDER BY a), '') FROM unnest(string_to_array('$ACTIONS', ' ')) a WHERE NOT EXISTS (SELECT 1 FROM dt_audit_log l WHERE l.action = a)")
check "action ที่เกิดขึ้นครบทั้ง $n_all ชนิด (จากทุกข้อรวมกัน)" "" "$missing"
info "จำนวนต่อ action" "SELECT string_agg(action || ' ' || n, ', ' ORDER BY action) FROM (SELECT action, count(*) n FROM dt_audit_log GROUP BY 1) x"
none "user_id ของ audit (actor user) ไม่เป็นผู้ใช้ของบริษัทอื่น" "
  SELECT a.tenant_id, a.id, a.user_id FROM dt_audit_log a WHERE a.actor_kind = 'user' AND a.user_id IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM dt_users u WHERE u.tenant_id = a.tenant_id AND u.id = a.user_id)
  AND EXISTS (SELECT 1 FROM dt_users u WHERE u.id = a.user_id)"
info "audit ที่ user_id ไม่มีในตารางผู้ใช้แล้ว (ผู้ใช้ถูกลบ — ประวัติไม่มี FK)" "SELECT count(*) FROM dt_audit_log a WHERE a.actor_kind = 'user' AND a.user_id IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM dt_users u WHERE u.tenant_id = a.tenant_id AND u.id = a.user_id)"
none "audit ของบริษัทที่ไม่มีอยู่ (purge ลบครบ)" "SELECT DISTINCT a.tenant_id FROM dt_audit_log a WHERE NOT EXISTS (SELECT 1 FROM dt_companies c WHERE c.id = a.tenant_id)"

case_id 38.7 "ทั่วไป"
none "ไม่มี transaction ค้าง" "SELECT pid, usename, state FROM pg_stat_activity WHERE state LIKE 'idle in transaction%'"
none "ตารางที่มี tenant_id: ไม่มีแถวของบริษัทที่ไม่มีอยู่ (purge)" "$(sql "SELECT string_agg(format('SELECT %L, tenant_id FROM %I x WHERE NOT EXISTS (SELECT 1 FROM dt_companies c WHERE c.id = x.tenant_id) GROUP BY tenant_id', table_name, table_name), ' UNION ALL ')
  FROM information_schema.columns WHERE table_schema = 'public' AND column_name = 'tenant_id' AND table_name NOT IN ('dt_audit_log')")"

finish
