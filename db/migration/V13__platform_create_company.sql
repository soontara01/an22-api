-- V13 (E59): บริษัทใหม่สร้างได้ทางเดียวคือ platform (ไม่มี POST /auth/signup แล้ว)
--   owner ของบริษัทที่ platform สร้างยังไม่มีรหัสผ่าน (null = login ไม่ผ่าน) จนกว่าจะใช้ลิงก์ตั้งรหัสผ่าน
ALTER TABLE dt_users ALTER COLUMN password_hash DROP NOT NULL;

--   ลิงก์ตั้งรหัสผ่าน: reset = ลืมรหัสผ่าน (E41, 1 ชม.), setup = ลิงก์จาก platform (E59, 72 ชม.)
ALTER TABLE dt_password_reset_tokens
  ADD COLUMN kind text NOT NULL DEFAULT 'reset' CHECK (kind IN ('reset', 'setup'));

--   audit ใหม่: company_create / owner_setup_link (platform), password_set (owner ตั้งรหัสด้วยลิงก์ setup); signup คงไว้เพื่อประวัติเดิม
ALTER TABLE dt_audit_log DROP CONSTRAINT dt_audit_log_action_check;
ALTER TABLE dt_audit_log ADD CONSTRAINT dt_audit_log_action_check CHECK (action IN (
  'login', 'login_failed', 'sale_void', 'manual_discount', 'credit_note', 'tax_invoice_reissue',
  'stock_out', 'serial_remove', 'transfer_cancel', 'price_change', 'promotion_change',
  'user_change', 'user_password', 'owner_transfer', 'plan_change', 'signup', 'company_suspend',
  'company_reactivate', 'invoice_paid', 'company_export', 'deletion_request', 'deletion_cancel',
  'password_reset', 'menu_access', 'company_settings', 'branch_settings', 'branch_change',
  'terminal_change', 'shift_close_other', 'shift_approve', 'shift_adjust',
  'mfa_enable', 'mfa_disable', 'mfa_reset',
  'company_create', 'owner_setup_link', 'password_set'));
