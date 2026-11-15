-- V12 (E57): ผู้ใช้ร้านทุกคนต้อง login ด้วย Microsoft Authenticator เสมอ — ไม่มีตัวเลือกบังคับของบริษัทอีก
--   (audit เดิมที่บันทึกการเปลี่ยนค่านี้ยังอยู่ — เป็นข้อความ; action mfa_disable คงไว้ใน CHECK เพื่อประวัติเดิม)
ALTER TABLE dt_company_info DROP COLUMN mfa_required;
