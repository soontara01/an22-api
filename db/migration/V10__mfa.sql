-- V10 (E55): login สองขั้นด้วย Microsoft Authenticator (TOTP RFC 6238)
--   dt_user_mfa: 1 แถว / ผู้ใช้ — secret base32 เข้ารหัสด้วย PiiCipher (AAD dt_user_mfa.secret:<tenant>),
--   confirmed_at null = รอยืนยัน (ผูกยังไม่เสร็จ), last_step = ช่วง 30 วิ ที่ใช้ล่าสุด (กันรหัสซ้ำ);
--   ลบแถว = ปิด / admin ล้างให้ · ไม่อยู่ใน GET /company/export (CompanyExportDao.SKIP)
--   dt_company_info.mfa_required: none / admins (owner + admin) / all
CREATE TABLE dt_user_mfa (
  tenant_id       bigint NOT NULL,
  user_id         bigint NOT NULL,
  secret_enc      bytea NOT NULL,
  confirmed_at    timestamptz,
  last_step       bigint,
  created_at      timestamptz NOT NULL,
  created_by      bigint,
  created_by_kind text NOT NULL CHECK (created_by_kind IN ('user', 'platform', 'system')),
  updated_at      timestamptz NOT NULL,
  updated_by      bigint,
  updated_by_kind text NOT NULL CHECK (updated_by_kind IN ('user', 'platform', 'system')),
  PRIMARY KEY (tenant_id, user_id),
  CONSTRAINT dt_user_mfa_user_fkey FOREIGN KEY (tenant_id, user_id) REFERENCES dt_users (tenant_id, id) ON DELETE RESTRICT
);

CREATE TRIGGER set_row_stamp BEFORE INSERT OR UPDATE ON dt_user_mfa FOR EACH ROW EXECUTE FUNCTION set_row_stamp();

ALTER TABLE dt_user_mfa ENABLE ROW LEVEL SECURITY;
ALTER TABLE dt_user_mfa FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON dt_user_mfa USING (tenant_id = app_tenant_id()) WITH CHECK (tenant_id = app_tenant_id());

GRANT SELECT, INSERT, UPDATE, DELETE ON dt_user_mfa TO an22_app, an22_platform;

ALTER TABLE dt_company_info
  ADD COLUMN mfa_required text NOT NULL DEFAULT 'none' CHECK (mfa_required IN ('none', 'admins', 'all'));

ALTER TABLE dt_audit_log DROP CONSTRAINT dt_audit_log_action_check;
ALTER TABLE dt_audit_log ADD CONSTRAINT dt_audit_log_action_check CHECK (action IN (
  'login', 'login_failed', 'sale_void', 'manual_discount', 'credit_note', 'tax_invoice_reissue',
  'stock_out', 'serial_remove', 'transfer_cancel', 'price_change', 'promotion_change',
  'user_change', 'user_password', 'owner_transfer', 'plan_change', 'signup', 'company_suspend',
  'company_reactivate', 'invoice_paid', 'company_export', 'deletion_request', 'deletion_cancel',
  'password_reset', 'menu_access', 'company_settings', 'branch_settings', 'branch_change',
  'terminal_change', 'shift_close_other', 'shift_approve', 'shift_adjust',
  'mfa_enable', 'mfa_disable', 'mfa_reset'));
