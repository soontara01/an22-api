-- V11 (E56): platform admin ต้อง login ด้วย Microsoft Authenticator เสมอ
--   dt_platform_admin_mfa: 1 แถว / admin — secret base32 เข้ารหัสด้วย PiiCipher (AAD dt_platform_admin_mfa.secret:<admin id>),
--   confirmed_at null = ผูกยังไม่เสร็จ, last_step = ช่วง 30 วิ ที่ใช้ล่าสุด (กันรหัสซ้ำ)
--   ตารางของ platform (ไม่มี tenant_id) — an22_app ไม่มีสิทธิ์; มือถือหาย = scripts/platform-mfa-reset.sh ลบแถว
CREATE TABLE dt_platform_admin_mfa (
  admin_id        bigint PRIMARY KEY REFERENCES dt_platform_admins (id) ON DELETE RESTRICT,
  secret_enc      bytea NOT NULL,
  confirmed_at    timestamptz,
  last_step       bigint,
  created_at      timestamptz NOT NULL,
  created_by      bigint,
  created_by_kind text NOT NULL CHECK (created_by_kind IN ('user', 'platform', 'system')),
  updated_at      timestamptz NOT NULL,
  updated_by      bigint,
  updated_by_kind text NOT NULL CHECK (updated_by_kind IN ('user', 'platform', 'system'))
);

CREATE TRIGGER set_row_stamp BEFORE INSERT OR UPDATE ON dt_platform_admin_mfa FOR EACH ROW EXECUTE FUNCTION set_row_stamp();

GRANT SELECT, INSERT, UPDATE, DELETE ON dt_platform_admin_mfa TO an22_platform;
