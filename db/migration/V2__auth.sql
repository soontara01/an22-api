-- V2: auth (ขั้น 2) — RLS ของตาราง platform ที่ an22_app อ่านได้ + ตัวนับ login ผิด

-- an22_app เห็นเฉพาะบริษัทของ transaction (ตกลง 2026-10-22) — an22_platform (BYPASSRLS) เห็นทั้งหมด;
-- login / signup ที่ยังไม่รู้บริษัทใช้ platformTx
ALTER TABLE dt_companies ENABLE ROW LEVEL SECURITY;
ALTER TABLE dt_companies FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON dt_companies
  USING (id = app_tenant_id()) WITH CHECK (id = app_tenant_id());

-- ใบแจ้งหนี้ของบริษัทตัวเอง (แถบแจ้งเตือนใน login / me, GET /company/subscription) — an22_app อ่านอย่างเดียว
ALTER TABLE dt_billing_invoices ENABLE ROW LEVEL SECURITY;
ALTER TABLE dt_billing_invoices FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON dt_billing_invoices
  USING (company_id = app_tenant_id()) WITH CHECK (company_id = app_tenant_id());
GRANT SELECT ON dt_billing_invoices TO an22_app;

-- จำกัด login ผิด (ตกลง 2026-10-22): ต่อบัญชี (บริษัท + username ที่พิมพ์) และต่อ X-Real-IP
--   key_hash = HMAC (PII_HASH_KEY) ของ key — ไม่เก็บ username / IP ตรง ๆ
--   ผิดครบ max ภายใน window → locked_until = now() + lock; สำเร็จ → ลบแถวของบัญชี
--   แถวที่หมดอายุลบด้วย job (ขั้น 11)
CREATE TABLE dt_login_throttle (
  key_hash        bytea PRIMARY KEY,
  scope           text NOT NULL CHECK (scope IN ('account', 'ip')),
  window_start    timestamptz NOT NULL,
  failures        integer NOT NULL CHECK (failures >= 0),
  locked_until    timestamptz,
  created_at      timestamptz NOT NULL,
  created_by      bigint,
  created_by_kind text NOT NULL CHECK (created_by_kind IN ('user', 'platform', 'system')),
  updated_at      timestamptz NOT NULL,
  updated_by      bigint,
  updated_by_kind text NOT NULL CHECK (updated_by_kind IN ('user', 'platform', 'system'))
);
CREATE INDEX dt_login_throttle_updated_idx ON dt_login_throttle (updated_at);
CREATE TRIGGER set_row_stamp BEFORE INSERT OR UPDATE ON dt_login_throttle FOR EACH ROW EXECUTE FUNCTION set_row_stamp();
GRANT SELECT, INSERT, UPDATE, DELETE ON dt_login_throttle TO an22_platform;
