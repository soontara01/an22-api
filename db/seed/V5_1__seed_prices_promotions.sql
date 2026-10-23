-- ราคา / โปรตัวอย่างของ DEMO (dev / test เท่านั้น) = SEED_PRICES, SEED_PROMOTIONS ของ mock (ค่าเริ่มของโปร = PROMOTION_DEFAULTS)
SELECT set_config('app.tenant_id', '1', true), set_config('app.actor_kind', 'system', true);

INSERT INTO dt_sku_prices (id, tenant_id, product_id, price, start_date, end_date, note) VALUES
  (1, 1, 1, 26900, '2026-01-01', '2026-06-30', 'ราคาเปิดตัว'),
  (2, 1, 1, 24900, '2026-07-01', '2026-12-31', ''),
  (3, 1, 1, 23900, '2027-01-01', NULL, 'ปรับราคาปีใหม่'),
  (4, 1, 2, 590, '2026-01-01', NULL, ''),
  (5, 1, 3, 2490, '2026-01-01', NULL, ''),
  (6, 1, 4, 4990, '2026-01-01', '2026-10-31', ''),
  (7, 1, 4, 4590, '2026-11-01', '2026-11-30', 'โปร 11.11'),
  (8, 1, 5, 1290, '2026-01-01', NULL, ''),
  (9, 1, 6, 2190, '2026-03-01', NULL, ''),
  (10, 1, 7, 199, '2026-01-01', '2026-09-30', 'รอราคาใหม่'),
  (11, 1, 8, 199, '2026-01-01', NULL, ''),
  (12, 1, 9, 500, '2026-01-01', NULL, '');

INSERT INTO dt_promotions (id, tenant_id, code, name, type, start_date, end_date, priority, stackable, scope_all, scope_product_ids,
                           scope_category_ids, min_qty, min_amount, discount, free_goods) VALUES
  (1, 1, 'PRO-MSKB10', 'เมาส์และคีย์บอร์ดลด 10%', 'item_discount', '2026-10-01', '2026-10-31', 10, false, false, '{}', '{6}', 1, 0,
   '{"kind":"percent","value":10,"maxDiscount":null}', NULL),
  (2, 1, 'PRO-BILL5K', 'ซื้อครบ ฿5,000 ลด ฿300', 'bill_discount', '2026-10-01', '2026-12-31', 0, true, true, '{}', '{}', 0, 5000,
   '{"kind":"amount","value":300,"maxDiscount":null}', NULL),
  (3, 1, 'PRO-NB-GIFT', 'ซื้อโน้ตบุ๊ก แถมเมาส์ + ลงโปรแกรมฟรี', 'free_goods', '2026-09-15', NULL, 20, true, false, '{1}', '{}', 1, 0,
   NULL, '{"items":[{"productId":2,"qty":1},{"productId":9,"qty":1}],"repeat":true,"maxSets":null}'),
  (4, 1, 'PRO-USB-2F1', 'สาย USB-C ซื้อ 2 แถม 1', 'free_goods', '2026-09-15', NULL, 0, false, false, '{7}', '{}', 2, 0,
   NULL, '{"items":[{"productId":7,"qty":1}],"repeat":true,"maxSets":5}'),
  (5, 1, 'PRO-1111', '11.11 หูฟังลด 15% (สูงสุด ฿300)', 'item_discount', '2026-11-11', '2026-11-11', 30, false, false, '{5}', '{}', 1, 0,
   '{"kind":"percent","value":15,"maxDiscount":300}', NULL),
  (6, 1, 'PRO-SUMMER', 'ซัมเมอร์ จอ 24 นิ้วลด ฿500', 'item_discount', '2026-04-01', '2026-05-31', 0, false, false, '{4}', '{}', 1, 0,
   '{"kind":"amount","value":500,"maxDiscount":null}', NULL);

SELECT setval(pg_get_serial_sequence('dt_sku_prices', 'id'), (SELECT max(id) FROM dt_sku_prices));
SELECT setval(pg_get_serial_sequence('dt_promotions', 'id'), (SELECT max(id) FROM dt_promotions));
