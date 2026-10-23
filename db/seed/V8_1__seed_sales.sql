-- ออร์เดอร์ก่อนมี POS ของ DEMO (dev / test เท่านั้น) = SEED_SALES ของ mock (ไม่มีบรรทัด / การชำระ; ค่าที่ไม่มี = SALE_DEFAULTS, subtotal = total)
-- สาขา = สาขาแรก (fixDocumentBranches ของ mock)
SELECT set_config('app.tenant_id', '1', true), set_config('app.actor_kind', 'system', true);

INSERT INTO dt_sales (id, tenant_id, order_no, customer, sold_at, item_count, subtotal, total, status, branch_id) VALUES
  (1, 1, 'SO-2026-0001', 'บริษัท เอ จำกัด', '2026-09-01T09:15:00Z', 3, 52370, 52370, 'paid', 1),
  (2, 1, 'SO-2026-0002', 'ร้านบีคอม', '2026-09-05T13:40:00Z', 10, 5900, 5900, 'paid', 1),
  (3, 1, 'SO-2026-0003', 'คุณสมศักดิ์', '2026-09-12T10:05:00Z', 1, 4990, 4990, 'cancelled', 1),
  (4, 1, 'SO-2026-0004', 'บริษัท ซี เทรดดิ้ง', '2026-09-20T16:20:00Z', 5, 12450, 12450, 'pending', 1),
  (5, 1, 'SO-2026-0005', 'คุณมาลี', '2026-09-28T11:00:00Z', 2, 2580, 2580, 'paid', 1),
  (6, 1, 'SO-2026-0006', 'ร้านดีไอที', '2026-10-01T08:30:00Z', 4, 8760, 8760, 'pending', 1),
  (7, 1, 'SO-2026-0007', 'บริษัท เอ จำกัด', '2026-10-02T14:45:00Z', 2, 49800, 49800, 'paid', 1);

SELECT setval(pg_get_serial_sequence('dt_sales', 'id'), (SELECT max(id) FROM dt_sales));
