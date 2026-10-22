-- ข้อมูลตัวอย่าง DEMO (dev / test เท่านั้น — ห้ามลง production) = seedDb() + loadPlatform() ของ mock
--   ../FrontEnd-An22/src/app/core/interceptors/mock-backend.interceptor.ts: DEMO_COMPANY, SEED_STORE_INFO, SEED_BRANCHES, SEED_USERS
-- login: DEMO / admin / admin (owner), somchai / 1234 (รหัสอื่นของ mock = 1234), platform: platform / platform
-- รหัสผ่านเป็น BCrypt (cost 10) — ไม่ใช้ pgcrypto

-- RLS บังคับ owner ด้วย (FORCE) → ตั้งบริษัทของ transaction นี้; ผู้ทำ = system
SELECT set_config('app.tenant_id', '1', true), set_config('app.actor_kind', 'system', true);

INSERT INTO dt_companies (id, code, active, plan_id, suspended_reason, trial_ends_on, next_invoice_on)
VALUES (1, 'DEMO', true, 'pro', NULL, NULL, '9999-12-31');

INSERT INTO dt_company_info (tenant_id, name, tax_id, vat_registered, tax_invoice_paper, exchange_days,
                             manual_discount_max_percent, require_shift, shift_variance_limit, shift_max_hours, staff_see_cost)
VALUES (1, 'บริษัท ไอทีดี คอมพิวเตอร์ จำกัด', '0105550123451', true, 'A4', 7, 5, true, 100, 12, false);

INSERT INTO dt_branches (id, tenant_id, code, name, branch_no, address, phone, receipt_footer, active)
VALUES
  (1, 1, 'HQ', 'ร้านไอทีดี คอมพิวเตอร์', '00000',
   '99/9 ถนนพหลโยธิน แขวงสามเสนใน เขตพญาไท กรุงเทพฯ 10400', '02-123-4567',
   'ขอบคุณที่ใช้บริการ · เปลี่ยน/คืนสินค้าภายใน 7 วันพร้อมใบเสร็จ', true),
  (2, 1, 'BN', 'ร้านไอทีดี คอมพิวเตอร์ สาขาบางนา', '00001',
   '1/23 ถนนบางนา-ตราด แขวงบางนาใต้ เขตบางนา กรุงเทพฯ 10260', '02-765-4321',
   'ขอบคุณที่ใช้บริการ · เปลี่ยน/คืนสินค้าภายใน 7 วันพร้อมใบเสร็จ', true);

-- admin = owner ตั้งแต่ seed (fillOwner() ของ mock ไม่ต้องทำ); created_at = User.createdAt ของ mock
INSERT INTO dt_users (id, tenant_id, username, name, email, role, active, all_branches, password_hash, created_at)
VALUES
  (1, 1, 'admin', 'Admin', 'admin@example.com', 'owner', true, true,
   '$2a$10$YWSfehvxUoWO4k.sPOIGNu3L.QZ3LXRjaS3Z98hSrkvrqI.m83YOm', '2026-01-05T09:00:00Z'),
  (2, 1, 'somchai', 'สมชาย ใจดี', 'somchai@example.com', 'user', true, false,
   '$2a$10$8wl1nqR4vbPGR7e/n5n7Ve.Cjc2JWSRCcX1xcyYLrwDZZRSXaQNCW', '2026-02-11T10:30:00Z'),
  (3, 1, 'somying', 'สมหญิง รักงาน', 'somying@example.com', 'user', false, false,
   '$2a$10$8wl1nqR4vbPGR7e/n5n7Ve.Cjc2JWSRCcX1xcyYLrwDZZRSXaQNCW', '2026-03-20T14:15:00Z'),
  (4, 1, 'john', 'John Doe', 'john@example.com', 'user', true, false,
   '$2a$10$8wl1nqR4vbPGR7e/n5n7Ve.Cjc2JWSRCcX1xcyYLrwDZZRSXaQNCW', '2026-05-02T08:45:00Z');

INSERT INTO dt_user_branches (tenant_id, user_id, branch_id)
VALUES (1, 2, 1), (1, 3, 1), (1, 4, 1);

-- DEFAULT_USER_MENUS
INSERT INTO dt_menu_access (tenant_id, user_menus)
VALUES (1, ARRAY['dashboard', 'pos', 'sales', 'settings']);

INSERT INTO dt_platform_admins (id, username, name, password_hash)
VALUES (1, 'platform', 'ผู้ดูแลแพลตฟอร์ม', '$2a$10$KY1dRMvKprsAaqjLod110.W1Vb7crBB3a0/SZ2j/fOkHO6DY/FxBW');

-- id ถัดไปต่อจากที่ใส่เอง
SELECT setval(pg_get_serial_sequence('dt_companies', 'id'), (SELECT max(id) FROM dt_companies));
SELECT setval(pg_get_serial_sequence('dt_branches', 'id'), (SELECT max(id) FROM dt_branches));
SELECT setval(pg_get_serial_sequence('dt_users', 'id'), (SELECT max(id) FROM dt_users));
SELECT setval(pg_get_serial_sequence('dt_platform_admins', 'id'), (SELECT max(id) FROM dt_platform_admins));
