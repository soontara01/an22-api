# script DB ของ An22

ไฟล์ชุดเดียวใช้ได้ทั้งแอป (Flyway ตอน start) และคนรันเอง — SQL ล้วน ไม่มี placeholder ของ Flyway, **ห้ามแก้ไฟล์ migration ที่ apply แล้ว** (เพิ่ม version ใหม่แทน)

```
db/
  roles.sql                  role an22_owner / an22_app / an22_platform + database — รันครั้งเดียวต่อ server ด้วย admin
  migration/V<n>__<desc>.sql schema (ตาราง, index, RLS, grant, trigger)
  seed/V<n>_<m>__<desc>.sql  ข้อมูลตัวอย่าง DEMO — dev / test เท่านั้น (ห้ามลง production)
```

| role | ใช้ทำ | ต่อทาง |
| --- | --- | --- |
| `an22_owner` | เจ้าของ database / ตาราง — Flyway migrate เท่านั้น | PostgreSQL ตรง (port 5432) |
| `an22_app` | แอปฝั่งร้าน — ไม่ใช่ owner, อยู่ใต้ RLS | PgBouncer (port 6432) |
| `an22_platform` | งาน platform + ก่อน login — `BYPASSRLS` | PgBouncer (port 6432) |

`an22_app` / `an22_platform` ได้ `statement_timeout` (ค่าเริ่ม 30s) + `idle_in_transaction_session_timeout` (60s) จาก `roles.sql`; `an22_owner` ไม่ตั้ง (migration อาจนาน)

## ติดตั้ง server ใหม่

1. **roles** — ต่อ database `postgres` ด้วย admin (Azure: server admin ที่ตั้งตอนสร้าง server — ไม่ใช่ superuser ก็พอ: ต้องมี `CREATEDB` `CREATEROLE` `BYPASSRLS`)

   ```sh
   psql "host=<server> port=5432 dbname=postgres user=<admin> sslmode=verify-full sslrootcert=<CA>" \
     -v db_name=an22 -v owner_password=… -v app_password=… -v platform_password=… \
     [-v statement_timeout=30s] [-v idle_timeout=60s] -f db/roles.sql
   ```

   รันซ้ำได้ (สร้างเฉพาะที่ยังไม่มี, ตั้งรหัส / timeout ใหม่) — รหัสถึง server เป็นข้อความใน SQL: **อย่ารันตอนเปิด `log_statement = all`**

2. **schema** เลือกทางเดียว
   - **ทางที่ 1 (ค่าเริ่ม)**: แอป migrate เองตอน start (`FLYWAY_MIGRATE=true`, ต้องมี `DB_OWNER_PASSWORD`) — หลาย instance พร้อมกันได้ (Flyway ล็อกรอกัน)
   - **ทางที่ 2 (แนะนำ production)**: รัน migrate ครั้งเดียวก่อน deploy แล้วแอป `FLYWAY_MIGRATE=false` — แอปแค่ validate (version ใน DB ไม่ตรงไฟล์ → ไม่ start) และไม่ต้องรู้รหัสของ `an22_owner`

     ```sh
     scripts/db-apply.sh [--roles] [--seed]     # ต้องมี docker; env ตาม .env.example (หัวข้อ db-apply.sh)
     ```

     ใช้ Flyway CLI (`flyway/flyway:12.4.0`) บันทึกลง `flyway_schema_history` เหมือนแอป → รันซ้ำไม่ทำอะไร ไม่ข้ามไฟล์
   - ไม่มี docker / Flyway CLI: `psql -f` ทีละไฟล์ตามเลข version ได้เฉพาะ **DB ว่าง** (ด้วย `an22_owner`) แล้วต้อง
     `flyway baseline -baselineVersion=<version ล่าสุดที่รัน>` ก่อนใช้ทางที่ 1 / 2 ครั้งต่อไป (ไม่งั้น Flyway จะรันซ้ำตั้งแต่ V1)

Flyway ต่อ PostgreSQL ตรงเสมอตอน **migrate** (advisory lock แบบ session ใช้ผ่าน PgBouncer transaction pooling ไม่ได้);
validate ของทางที่ 2 อ่าน `flyway_schema_history` อย่างเดียว จึงวิ่งผ่าน PgBouncer ด้วย `an22_app` ได้

## dev / test

- `docker compose up -d` — `docker/initdb.sh` สร้าง `azure_admin` (สิทธิ์เหมือน server admin ของ Azure ไม่ใช่ superuser) แล้วรัน `roles.sql` ด้วย role นั้น; seed มาจากแอป profile `dev`
- รีเซ็ต: `docker compose down -v`
- `./mvnw verify` — Testcontainers เปิด postgres + pgbouncer ใหม่ทุกรอบ ด้วย `initdb.sh` / `roles.sql` / `pgbouncer.ini` ชุดเดียวกัน

## Production: Azure Database for PostgreSQL Flexible Server

- **PgBouncer ในตัวของ Azure** (1.25.2, port 6432 host เดียวกับ server) — แอป `DB_URL` = port 6432, `DB_MIGRATE_URL` = port 5432; ตั้ง server parameter:
  `pgbouncer.enabled = true`, `pgbouncer.pool_mode = transaction`, **`pgbouncer.max_prepared_statements` > 0** (ค่าเริ่ม 0 — pgJDBC จะพังเมื่อ statement ซ้ำ ≥ 5 ครั้ง),
  `pgbouncer.default_pool_size` × จำนวน user ≤ `max_connections`; ใช้ไม่ได้กับ compute tier Burstable
- Azure ไม่มี `query_timeout` / `idle_transaction_timeout` ของ PgBouncer — ตาข่าย timeout คือค่าของ role จาก `roles.sql`
- **TLS บังคับ** (`require_secure_transport`): JDBC URL ใส่ `sslmode=verify-full&sslrootcert=<ไฟล์ CA ของ Azure>`; `db-apply.sh` mount ไฟล์ CA ด้วย `DB_CA_FILE`
- **network แทน pg_hba** (แก้ pg_hba ของ Azure ไม่ได้): private access / firewall ให้เฉพาะ subnet ของ api + เครื่อง DBA; แอปตรวจตอน start ว่า `DB_URL` ≠ host:port ของ `DB_MIGRATE_URL`
- extension: ต้องเปิดใน server parameter `azure.extensions` ก่อน migrate ที่ใช้ — ตอนนี้มี **`BTREE_GIST`** (V5: กันช่วงราคาทับ); เป็น extension แบบ trusted จึงให้ `an22_owner` สร้างเองใน migration ได้
- **ต้องลองบน Azure จริงก่อนขึ้น production** (ทดสอบในเครื่องได้แค่จำลอง):
  1. `roles.sql` ด้วย server admin — โดยเฉพาะ `an22_platform BYPASSRLS` (PG16+ ผู้ตั้งต้องมี BYPASSRLS เอง)
  2. แอปผ่าน port 6432 + `max_prepared_statements` > 0: statement ซ้ำหลายรอบไม่ error
  3. TLS `verify-full` ทั้ง 5432 / 6432

## กุญแจข้อมูลส่วนบุคคล (`PII_KEYS`, `PII_HASH_KEY`)

ข้อมูลผู้ซื้อในใบกำกับ / ใบลดหนี้ และบิลพักเข้ารหัสในแอป (AES-256-GCM) — กุญแจ **ไม่อยู่ใน DB** จึง **ต้องสำรองแยกจาก backup ของ DB**
(เช่น Azure Key Vault): backup DB อย่างเดียวกู้ข้อมูลผู้ซื้อไม่ได้, กุญแจหาย = ข้อมูลผู้ซื้อหายถาวร; ทุก instance ต้องได้กุญแจชุดเดียวกัน
