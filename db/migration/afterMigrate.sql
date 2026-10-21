-- Flyway callback: รันหลัง migrate ทุกครั้ง (ทั้งแอปและ scripts/db-apply.sh) — คำสั่งต้องรันซ้ำได้
-- FLYWAY_MIGRATE=false (ทางที่ 2): แอป validate ผ่าน PgBouncer ด้วย an22_app → ต้องอ่านประวัติ migration ได้
GRANT SELECT ON flyway_schema_history TO an22_app;
