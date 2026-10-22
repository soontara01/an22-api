-- SKU ตัวอย่างของ DEMO (dev / test เท่านั้น) = SEED_PRODUCTS ของ mock + ผลของ migrateBranchStock / syncSerials / migrateCosting /
-- seedBranchSample: สต็อกยกมา (opening) ของ HQ + BN, serial ตัวอย่าง (ชื่อตาม sampleSerial()), ทุนเฉลี่ย = ทุนมาตรฐาน (HQ) / ค่าตัวอย่าง (BN)
-- เวลาของยอดยกมา / serial = 2026-01-01 (mock ใช้เวลาที่โหลดครั้งแรก)
SELECT set_config('app.tenant_id', '1', true), set_config('app.actor_kind', 'system', true);

INSERT INTO dt_products (id, tenant_id, sku, name, short_name, category_id, unit, barcode, brand, model, item_type, cost,
                         warranty_months, serial_control, serial_prefix, serial_length, sale_status) VALUES
  (1, 1, 'NB-001', 'โน้ตบุ๊ก 14 นิ้ว', 'NB Lenovo Slim5 14"', 3, 'ชิ้น', '8850000000010', 'Lenovo', 'IdeaPad Slim 5', 'stock', 19900,
   24, true, 'NB', 12, 'active'),
  (2, 1, 'MS-010', 'เมาส์ไร้สาย', 'เมาส์ไร้สาย M240', 6, 'ชิ้น', '8850000000027', 'Logitech', 'M240', 'stock', 350,
   12, false, '', NULL, 'active'),
  (3, 1, 'KB-020', 'คีย์บอร์ดแมคคานิคอล', 'คีย์บอร์ด K2', 6, 'ชิ้น', '8850000000034', 'Keychron', 'K2', 'stock', 1650,
   12, false, '', NULL, 'no_purchase'),
  (4, 1, 'MN-024', 'จอมอนิเตอร์ 24 นิ้ว', 'จอ Dell 24" P2422H', 4, 'ชิ้น', '8850000000041', 'Dell', 'P2422H', 'stock', 3900,
   36, true, 'MN', 10, 'active'),
  (5, 1, 'HD-100', 'หูฟังบลูทูธ', 'หูฟัง Sony CH520', 12, 'ชิ้น', '8850000000058', 'Sony', 'WH-CH520', 'stock', 850,
   12, true, 'HD', NULL, 'active'),
  (6, 1, 'SSD-1T', 'SSD 1TB', 'SSD 1TB 990EVO', 9, 'ชิ้น', '8850000000065', 'Samsung', '990 EVO', 'stock', 1590,
   60, true, 'S', 15, 'active'),
  (7, 1, 'CB-USB', 'สาย USB-C', 'สาย USB-C', 7, 'เส้น', '8850000000072', '', '', 'stock', 80,
   0, false, '', NULL, 'active'),
  (8, 1, 'SV-ESIM', 'บริการเปิดเบอร์ eSIM', 'บริการ eSIM', 17, 'ครั้ง', '', '', '', 'service', 50,
   0, false, '', NULL, 'active'),
  (9, 1, 'SV-SETUP', 'บริการติดตั้งโปรแกรมและตั้งค่าเครื่อง', 'ค่าติดตั้งโปรแกรม', 15, 'ครั้ง', '', '', '', 'service', 0,
   1, false, '', NULL, 'active');

INSERT INTO dt_product_pack_units (tenant_id, product_id, position, unit, factor, barcode) VALUES
  (1, 2, 0, 'กล่อง', 20, '8850000000126'),
  (1, 7, 0, 'แพ็ค', 10, '8850000000133');

INSERT INTO dt_product_suppliers (tenant_id, product_id, supplier_id, position, supplier_sku, cost, lead_time_days, moq, is_main) VALUES
  (1, 1, 1, 0, 'LN-IPS5-14', 19500, 7, 5, true),
  (1, 1, 2, 1, 'NB14-LEN', 19900, 10, 1, false),
  (1, 2, 3, 0, 'LG-M240', 330, 3, 20, true),
  (1, 3, 3, 0, 'KC-K2', 1600, 5, 5, true),
  (1, 4, 2, 0, 'DL-P2422H', 3850, 7, 2, true),
  (1, 5, 4, 0, 'SN-CH520', 820, 5, 10, true),
  (1, 6, 1, 0, 'SS-990E-1T', 1550, 3, 5, true),
  (1, 6, 2, 1, 'SSD1T-SAM', 1590, 5, 1, false),
  (1, 7, 3, 0, 'USBC-1M', 75, 2, 100, true);

-- สต็อกต่อสาขา: HQ (1) = สต็อกเดิมของร้าน, BN (2) = ตัวอย่างของ seedBranchSample()
INSERT INTO dt_branch_stock (tenant_id, branch_id, product_id, stock, avg_cost, min_stock, max_stock) VALUES
  (1, 1, 1, 12, 19900, 5, 20),
  (1, 1, 2, 3, 350, 10, 40),
  (1, 1, 3, 18, 1650, 5, 20),
  (1, 1, 4, 0, 0, 3, 10),
  (1, 1, 5, 25, 850, 8, 40),
  (1, 1, 6, 7, 1590, 6, 15),
  (1, 1, 7, 60, 80, 20, 100),
  (1, 2, 1, 2, 20500, 1, 4),
  (1, 2, 2, 8, 380, 5, 20),
  (1, 2, 3, 4, 1600, 2, 8);

-- serial ตัวอย่าง (sampleSerial()): NB-1000000NN, HD-5NNNN, S-6NNNNNNNNNNNN
INSERT INTO dt_serial_numbers (tenant_id, product_id, branch_id, serial, status, received_at, note, cost, created_at)
SELECT 1, 1, CASE WHEN n <= 12 THEN 1 ELSE 2 END, 'NB-1' || lpad(n::text, 8, '0'), 'in_stock', TIMESTAMPTZ '2026-01-01 00:00:00+07',
       'ข้อมูลตัวอย่าง', CASE WHEN n <= 12 THEN 19900 ELSE 20500 END, TIMESTAMPTZ '2026-01-01 00:00:00+07'
FROM generate_series(1, 14) AS n;
INSERT INTO dt_serial_numbers (tenant_id, product_id, branch_id, serial, status, received_at, note, cost, created_at)
SELECT 1, 5, 1, 'HD-5' || lpad(n::text, 4, '0'), 'in_stock', TIMESTAMPTZ '2026-01-01 00:00:00+07', 'ข้อมูลตัวอย่าง', 850,
       TIMESTAMPTZ '2026-01-01 00:00:00+07'
FROM generate_series(1, 25) AS n;
INSERT INTO dt_serial_numbers (tenant_id, product_id, branch_id, serial, status, received_at, note, cost, created_at)
SELECT 1, 6, 1, 'S-6' || lpad(n::text, 12, '0'), 'in_stock', TIMESTAMPTZ '2026-01-01 00:00:00+07', 'ข้อมูลตัวอย่าง', 1590,
       TIMESTAMPTZ '2026-01-01 00:00:00+07'
FROM generate_series(1, 7) AS n;

-- ยอดยกมา (opening, B17) ของทุก (สาขา, SKU) ที่มีสต็อก: ราคา = ทุนเฉลี่ย, มูลค่า = round2(จำนวน × ทุน)
INSERT INTO dt_stock_movements (tenant_id, branch_id, product_id, type, qty, unit_cost, total_cost, balance_qty, balance_avg_cost,
                                balance_value, serials, note, created_at)
SELECT 1, s.branch_id, s.product_id, 'opening', s.stock, s.avg_cost, round(s.stock * s.avg_cost, 2), s.stock, s.avg_cost,
       round(s.stock * s.avg_cost, 2),
       coalesce((SELECT array_agg(n.serial ORDER BY n.serial) FROM dt_serial_numbers n
                 WHERE n.product_id = s.product_id AND n.branch_id = s.branch_id AND n.status = 'in_stock'), '{}'),
       'ยอดยกมา', TIMESTAMPTZ '2026-01-01 00:00:00+07'
FROM dt_branch_stock s
WHERE s.stock > 0
ORDER BY s.branch_id, s.product_id;

SELECT setval(pg_get_serial_sequence('dt_products', 'id'), (SELECT max(id) FROM dt_products));
