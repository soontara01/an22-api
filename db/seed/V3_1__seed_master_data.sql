-- ข้อมูลหลักตัวอย่างของ DEMO (dev / test เท่านั้น) = SEED_CATEGORIES, SEED_SUPPLIERS, SEED_PAYMENT_METHODS, SEED_POS_TERMINALS ของ mock
SELECT set_config('app.tenant_id', '1', true), set_config('app.actor_kind', 'system', true);

INSERT INTO dt_categories (id, tenant_id, code, name, parent_id, level) VALUES
  (1, 1, 'IT', 'ไอที', NULL, 1),
  (2, 1, 'IT-COM', 'คอมพิวเตอร์', 1, 2),
  (3, 1, 'IT-COM-NB', 'โน้ตบุ๊ก', 2, 3),
  (4, 1, 'IT-COM-MON', 'จอภาพ', 2, 3),
  (5, 1, 'IT-ACC', 'อุปกรณ์เสริม', 1, 2),
  (6, 1, 'IT-ACC-MKB', 'เมาส์และคีย์บอร์ด', 5, 3),
  (7, 1, 'IT-ACC-CBL', 'สายและอะแดปเตอร์', 5, 3),
  (8, 1, 'IT-STO', 'อุปกรณ์จัดเก็บข้อมูล', 1, 2),
  (9, 1, 'IT-STO-SSD', 'SSD', 8, 3),
  (10, 1, 'EA', 'เครื่องใช้ไฟฟ้า', NULL, 1),
  (11, 1, 'EA-AUD', 'เครื่องเสียง', 10, 2),
  (12, 1, 'EA-AUD-HP', 'หูฟัง', 11, 3),
  (13, 1, 'SV', 'บริการ', NULL, 1),
  (14, 1, 'SV-IT', 'บริการไอที', 13, 2),
  (15, 1, 'SV-IT-SET', 'ติดตั้งและตั้งค่า', 14, 3),
  (16, 1, 'SV-MOB', 'บริการมือถือ', 13, 2),
  (17, 1, 'SV-MOB-ESIM', 'eSIM', 16, 3);

INSERT INTO dt_suppliers (id, tenant_id, code, name, tax_id, branch_type, branch_no, address, contact_name, phone, email,
                          credit_days, bank_name, bank_account_no, bank_account_name, note) VALUES
  (1, 1, 'SUP-ITD', 'บริษัท ไอทีดิสทริบิวชั่น จำกัด', '0105550123451', 'head', '',
   '99 ถนนพระราม 9 แขวงห้วยขวาง เขตห้วยขวาง กรุงเทพฯ 10310', 'คุณวิภา', '02-000-1111', 'sales@itd.example.com',
   30, 'กสิกรไทย', '123-4-56789-0', 'บจก. ไอทีดิสทริบิวชั่น', ''),
  (2, 1, 'SUP-CPS', 'บริษัท คอมพาร์ทซัพพลาย จำกัด', '0105560789011', 'branch', '00001',
   '15 ถนนบางนา-ตราด แขวงบางนา เขตบางนา กรุงเทพฯ 10260', 'คุณธนา', '02-000-2222', 'order@cps.example.com',
   60, 'กรุงเทพ', '234-5-67890-1', 'บจก. คอมพาร์ทซัพพลาย', ''),
  (3, 1, 'SUP-ACC', 'ห้างหุ้นส่วนจำกัด แอคเซสเซอรี่ เทรดดิ้ง', '0105540345672', 'head', '',
   '8 ถนนเพชรบุรี แขวงทุ่งพญาไท เขตราชเทวี กรุงเทพฯ 10400', 'คุณมานพ', '081-000-3333', '',
   0, '', '', '', 'จ่ายเงินสดเมื่อรับของ'),
  (4, 1, 'SUP-AV', 'บริษัท ออดิโอวิชั่น จำกัด', '0105550987651', 'head', '',
   '120 ถนนรัชดาภิเษก แขวงดินแดง เขตดินแดง กรุงเทพฯ 10400', 'คุณสุดา', '02-000-4444', 'b2b@av.example.com',
   30, '', '', '', '');

-- close_check = closeCheckOf() ของแต่ละประเภท (mock ไม่ได้เก็บ — ค่าเดียวกับที่หน้าบ้านคิด)
INSERT INTO dt_payment_methods (id, tenant_id, code, name, type, sort_order, require_reference, reference_label, min_amount,
                                fee_percent, cash_rounding, prompt_pay_id, bank_account, installment_months, close_check, note) VALUES
  (1, 1, 'CASH', 'เงินสด', 'cash', 1, false, '', 0, 0, '0.25', '', '', '{}', 'count', ''),
  (2, 1, 'CARD', 'บัตรเครดิต/เดบิต', 'card', 2, true, 'เลขอนุมัติ', 300, 1.6, 'none', '', '', '{}', 'count', ''),
  (3, 1, 'QR-PP', 'QR พร้อมเพย์', 'qr', 3, false, '', 0, 0, 'none', '0105550123451', 'กสิกรไทย 123-4-56789-0', '{}', 'count', ''),
  (4, 1, 'TRANSFER', 'โอนผ่านธนาคาร', 'transfer', 4, true, 'เลขอ้างอิงการโอน', 0, 0, 'none', '', 'กสิกรไทย 123-4-56789-0', '{}',
   'confirm', ''),
  (5, 1, 'TMW', 'TrueMoney Wallet', 'e_wallet', 5, false, '', 0, 1.5, 'none', '', '', '{}', 'confirm', ''),
  (6, 1, 'INST-0', 'ผ่อน 0%', 'installment', 6, true, 'เลขอนุมัติ', 3000, 3, 'none', '', '', '{3,6,10}', 'count',
   'ผ่อนผ่านบัตรเครดิตที่ร่วมรายการ');

INSERT INTO dt_pos_terminals (tenant_id, id, name, pos_id, branch_id) VALUES
  (1, 1, 'แคชเชียร์หน้าร้าน', 'E051234567890', 1),
  (1, 2, 'แคชเชียร์โซนมือถือ', 'E051234567891', 1),
  (1, 3, 'แคชเชียร์สาขาบางนา', 'E051234567892', 2);

SELECT setval(pg_get_serial_sequence('dt_categories', 'id'), (SELECT max(id) FROM dt_categories));
SELECT setval(pg_get_serial_sequence('dt_suppliers', 'id'), (SELECT max(id) FROM dt_suppliers));
SELECT setval(pg_get_serial_sequence('dt_payment_methods', 'id'), (SELECT max(id) FROM dt_payment_methods));
