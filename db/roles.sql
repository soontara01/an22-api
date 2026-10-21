-- สร้าง role + database ของ An22 — รันครั้งเดียวต่อ server (รันซ้ำได้: สร้างเฉพาะที่ยังไม่มี, ตั้งรหัส / ค่า timeout ใหม่ทุกครั้ง)
--
-- ต้องรันด้วย admin ที่มี CREATEDB + CREATEROLE + BYPASSRLS — ไม่ต้องเป็น superuser
-- (Azure Flexible Server: server admin; dev / test: role azure_admin ที่จำลองสิทธิ์เดียวกัน)
-- ต่อ database `postgres` แล้วส่งค่าเป็นตัวแปร psql — ไม่มีรหัสในไฟล์นี้:
--
--   psql "host=… dbname=postgres user=<admin> sslmode=…" \
--     -v db_name=an22 -v owner_password=… -v app_password=… -v platform_password=… \
--     [-v statement_timeout=30s] [-v idle_timeout=60s] -f db/roles.sql
--
-- รหัสผ่านไปถึง server เป็นข้อความใน SQL — อย่ารันตอนเปิด log_statement = all

\set ON_ERROR_STOP on
\set QUIET on

\if :{?db_name}
\else
  \set db_name an22
\endif
\if :{?statement_timeout}
\else
  \set statement_timeout 30s
\endif
\if :{?idle_timeout}
\else
  \set idle_timeout 60s
\endif

-- ตัวแปรบังคับ (ไม่ใส่ค่าใน error)
\if :{?owner_password}
\else
  \set owner_password ''
\endif
\if :{?app_password}
\else
  \set app_password ''
\endif
\if :{?platform_password}
\else
  \set platform_password ''
\endif
SELECT length(:'owner_password') = 0 AS missing_owner,
       length(:'app_password') = 0 AS missing_app,
       length(:'platform_password') = 0 AS missing_platform \gset
\if :missing_owner
  \warn 'ต้องส่ง -v owner_password=… (รหัสของ an22_owner)'
  SELECT 1 / 0 AS missing_variable;
\endif
\if :missing_app
  \warn 'ต้องส่ง -v app_password=… (รหัสของ an22_app)'
  SELECT 1 / 0 AS missing_variable;
\endif
\if :missing_platform
  \warn 'ต้องส่ง -v platform_password=… (รหัสของ an22_platform)'
  SELECT 1 / 0 AS missing_variable;
\endif

-- role 3 ตัว (ไม่ใช้ keyword SUPERUSER — admin ที่ไม่ใช่ superuser แตะไม่ได้แม้ตั้งเป็น NO)
SELECT format('CREATE ROLE %I LOGIN', r)
FROM unnest(ARRAY['an22_owner', 'an22_app', 'an22_platform']) AS r
WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = r) \gexec

-- an22_owner: เจ้าของ database / ตาราง (Flyway ใช้ตัวนี้เท่านั้น)
ALTER ROLE an22_owner LOGIN NOCREATEDB NOCREATEROLE NOBYPASSRLS PASSWORD :'owner_password';
-- an22_app: แอปฝั่งร้าน — ไม่ใช่ owner, อยู่ใต้ RLS
ALTER ROLE an22_app LOGIN NOCREATEDB NOCREATEROLE NOBYPASSRLS PASSWORD :'app_password';
-- an22_platform: งาน platform + ก่อน login — ข้าม RLS
ALTER ROLE an22_platform LOGIN NOCREATEDB NOCREATEROLE BYPASSRLS PASSWORD :'platform_password';

-- timeout ของแอป (an22_owner ไม่ตั้ง — migration อาจนาน); งานที่นานจริงใช้ SET LOCAL ใน transaction นั้น
ALTER ROLE an22_app SET statement_timeout = :'statement_timeout';
ALTER ROLE an22_app SET idle_in_transaction_session_timeout = :'idle_timeout';
ALTER ROLE an22_platform SET statement_timeout = :'statement_timeout';
ALTER ROLE an22_platform SET idle_in_transaction_session_timeout = :'idle_timeout';

-- CREATE DATABASE … OWNER ต้องมีสิทธิ์ SET role นั้น (PG16+: ผู้สร้างได้แค่ ADMIN)
SELECT 'GRANT an22_owner TO CURRENT_USER WITH INHERIT FALSE, SET TRUE'
WHERE NOT pg_has_role(CURRENT_USER, 'an22_owner', 'SET') \gexec

SELECT format('CREATE DATABASE %I OWNER an22_owner ENCODING ''UTF8'' TEMPLATE template0', :'db_name')
WHERE NOT EXISTS (SELECT 1 FROM pg_database WHERE datname = :'db_name') \gexec

-- เข้า database ได้เฉพาะ 3 role นี้ (ไม่มี TEMP — ห้าม temp table ข้าม transaction ใน PgBouncer)
-- ทำในนามเจ้าของ database (admin ไม่ใช่ owner จึง grant / revoke เองไม่ได้)
SET ROLE an22_owner;
SELECT format('REVOKE ALL ON DATABASE %I FROM PUBLIC', :'db_name') \gexec
SELECT format('GRANT CONNECT ON DATABASE %I TO an22_app, an22_platform', :'db_name') \gexec
RESET ROLE;

\echo 'roles.sql: เสร็จ'
