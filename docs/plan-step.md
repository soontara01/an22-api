# แผนทำ an22-api (C2) — ทีละขั้นย่อย

สถานะ: ขั้น 11 เสร็จ 2026-10-23 — รอผู้ใช้ก่อนเริ่มขั้น 12

## บริบท

- repo `an22-api` ยังว่าง (มีแค่ `noom-test.txt` ของผู้ใช้ — ไม่แตะ) ต้องเป็น API จริงแทน mock ของ FrontEnd-An22 และเป็นเจ้าของ schema PostgreSQL (Flyway, E47)
- spec: `../FrontEnd-An22/docs/backend/*` + mock `mock-backend.interceptor.ts` (5,596 บรรทัด) / spec ของมัน = reference implementation
- api อยู่หลัง `../an22-web` (BFF, E48 — เสร็จแล้ว **ห้ามแก้**): api เป็น JWT stateless, ไม่ทำ cookie / CSRF / CORS / logout, IP ผู้ใช้ = `X-Real-IP`
- เป้าหมายสุดท้าย: หน้าบ้าน build ด้วย `useMock: false` (C3) แล้วใช้งานได้เหมือน mock

## เครื่องมือ (ตกลงแล้ว 2026-10-20)

| เรื่อง | เลือก |
| --- | --- |
| ภาษา / framework | Java 25 (Temurin ที่มีในเครื่อง) + **Spring Boot 4.1.1** (Spring Framework 7.0.9, Jackson 3.1.5, Flyway 12.4.0, Testcontainers 2.0.5, pgJDBC 42.7.13 — ตาม BOM; ตกลง 2026-10-21) |
| build | Maven 3.9.16 + `mvnw` (wrapper 3.3.4 อยู่ใน repo) |
| DB access | Spring JDBC (`JdbcClient`) — SQL ตรง, ไม่มี JPA / Hibernate (จึงไม่มี `ddl-auto` เลย); transaction ด้วย `@Transactional` ที่ service (หัวข้อ "Transaction") |
| DB | PostgreSQL 18 + **PgBouncer (บังคับ, transaction pooling)** — prod: Azure Flexible Server + PgBouncer ในตัว (หัวข้อ "Production: Azure"); dev: `compose.yaml`, test: Testcontainers — `postgres:18.6` + `edoburu/pgbouncer:v1.25.2-p0` (เวอร์ชันเดียวกับ Azure) |
| migration | Flyway — SQL อยู่ที่ `db/` (root ของ repo) เป็น SQL ล้วน รันได้ทั้งจากแอปและด้วยมือ (หัวข้อ "script DB") |
| JWT | Spring Security + `oauth2-resource-server` (Nimbus) HS256, secret จาก env |
| เงินในกฎที่ port | `double` + `round2` แบบ JS (`Math.floor(x * 100 + 0.5) / 100`) ให้ตรง fixtures; แปลงเป็น `BigDecimal` scale 2 ตอนเก็บ `numeric(14,2)` |
| seed dev | ตาม `seedDb()` ของ mock ทั้งหมด (+ ผลของ migration ใน mock เช่นสต็อกยกมา) — `db/seed` เปิดเฉพาะ dev / test (ไม่ลง production) |
| อีเมล | เลื่อนไปทีหลัง — interface `Mailer` + ตัวที่ log ว่า "ส่งแล้ว" (ไม่ log ลิงก์ / token / อีเมลเต็ม) |
| test | JUnit 5 + AssertJ + Spring Boot Test (MockMvc) + Testcontainers |
| ข้อมูลส่วนบุคคล | เข้ารหัสในแอป AES-256-GCM เฉพาะคอลัมน์อ่อนไหว + HMAC-SHA256 ไว้ค้นหา (หัวข้อ "ข้อมูลส่วนบุคคล") |

## โครง repo / package (`com.an22.api`)

```
pom.xml, mvnw, .mvn/          compose.yaml (postgres + pgbouncer)   .env.example   CLAUDE.md   docs/plan-step.md
db/                           script DB ทั้งหมด (หัวข้อ "script DB")   scripts/db-apply.sh
Dockerfile, .dockerignore      image ของ api (ขั้น 0 — ผู้ใช้ขอ 2026-10-22: prod รันเป็น container)
docker/                       initdb.sh (สร้าง admin จำลอง Azure → roles.sql), pgbouncer/ (pgbouncer.ini + start.sh) — ใช้ทั้ง compose และ Testcontainers
src/main/java/com/an22/api/
  Application.java
  config/       ค่าจาก env (ApiProperties + check()), Clock (Asia/Bangkok), DataSourceConfig (app / platform), FlywayConfig
  security/     PiiCipher, JwtService (ออก / ตรวจ), SecurityConfig (chain ร้าน / platform), CurrentUser, ClientIp (X-Real-IP)
  db/           TenantTransactionManager — ตั้ง `set_config('app.tenant_id' / 'app.user_id' / 'app.actor_kind', ?, true)` (= SET LOCAL) ทุกครั้งที่เริ่ม transaction,
                TxGuard (query นอก transaction → error), DocNumbers (ตัวนับเลขเอกสาร)
  health/       /healthz, /readyz (3 ชั้นเหมือน feature อื่น)
  web/          ApiException(status, message), ErrorHandler + FallbackErrorController → `{ message }`, RequestLogFilter, BranchContext (X-Branch-Id), TerminalContext (X-Terminal-Id), CostHidingAdvice (hideCost จุดเดียว)
  rules/        **port จาก core/models — pure Java ไม่ผูก Spring** 1 class ต่อ 1 ไฟล์ TS: PosPricing, PosPayment, PaymentMethodRules, PromotionRules, PriceRules,
                Costing, SerialRules, ProductRules, CategoryRules, Suppliers, TransferRules, SaleRules, CreditNoteRules, ExchangeRules, TaxInvoiceRules,
                ShiftRules, PosTerminalRules, StoreInfoRules, MenuAccessRules, UserRules, CostVisibility, CompanyRules, AuditRules, PlanRules,
                SubscriptionRules, SignupRules, PlatformRules, CompanyDataRules, Money (round2, formatBaht `฿1,234.5`)
  auth/ company/ branch/ user/ masterdata/ product/ stock/ transfer/ sale/ shift/ pos/ audit/ billing/ platform/   ← แต่ละ feature 3 ชั้น: `XxxController` → `XxxService` → `XxxDao` (JdbcClient) — หัวข้อ "3 ชั้น"
src/main/resources/
  application.yml (context-path `/api`, port 8080) — Flyway อ่าน `filesystem:db/migration` (+ `db/seed` ใน dev / test); Docker image copy `db/` เข้าไป
src/test/java/com/an22/api/
  rules/        unit test ของกฎ + FixturesTest (อ่าน `../FrontEnd-An22/docs/backend/fixtures/*.json`)
  support/      TestDatabase (postgres + pgbouncer singleton), ApiTest (MockMvc + login helper ร้าน / platform), ProbeController (endpoint ของ test), TenantB (บริษัทที่ 2 สำหรับ test ข้าม tenant)
                unit test = `*Test` (surefire), integration = `*IT` (failsafe) — `./mvnw verify` รันทั้งคู่
  <feature>/    integration test ต่อ endpoint
```

## script DB (ตกลงแล้ว 2026-10-20)

script ทั้งหมดอยู่ที่ `db/` ที่ root ของ repo — **ไฟล์เดียวกัน** ใช้ได้ทั้งแอป (Flyway ตอน start) และคนรันเองบน DB:

```
db/
  README.md                      ลำดับรัน + คำสั่ง (ติดตั้ง DB ใหม่ / อัปเกรด / dev)
  roles.sql                      สร้าง role an22_owner / an22_app / an22_platform + database — รันครั้งเดียวด้วย admin ที่ไม่ใช่ superuser ก็ได้ (CREATEDB CREATEROLE BYPASSRLS — Azure)
                                 รหัสผ่านส่งเป็นตัวแปร psql (`psql -v app_password=… -f db/roles.sql`) ไม่มีรหัสในไฟล์
  migration/V1__base.sql …       schema ทีละ version (ตาราง, index, RLS, grant) — SQL ล้วน ไม่ใช้ placeholder ของ Flyway, ไม่แก้ไฟล์ที่ใช้แล้ว
  migration/afterMigrate.sql     Flyway callback หลัง migrate ทุกครั้ง (รันซ้ำได้): `GRANT SELECT ON flyway_schema_history TO an22_app` (ให้ validate ทางที่ 2 อ่านได้)
  seed/V1_1__seed_demo.sql …     ข้อมูลตัวอย่าง DEMO (dev / test เท่านั้น) — เลข version แทรกหลัง migration ที่มันต้องใช้
scripts/db-apply.sh              รันด้วยมือ (ต้องมี docker): `roles.sql` ด้วย psql ของ image postgres (ถ้าสั่ง `--roles`, env `DB_ADMIN_URL` / `DB_ADMIN_USER` / `DB_ADMIN_PASSWORD`)
                                 → Flyway CLI (`flyway/flyway:12.4.0`) migrate `db/migration` (+ `db/seed` เมื่อ `--seed`) ด้วย `DB_MIGRATE_URL`, `DB_OWNER_USER`, `DB_OWNER_PASSWORD`
                                 — env หรือ `.env` (ไม่ทับค่าที่ตั้งแล้ว); บันทึกลง `flyway_schema_history` เหมือนแอปรัน จึงไม่ซ้ำ / ไม่ข้ามไฟล์
```

- **Flyway migrate ต่อ PostgreSQL ตรง ไม่ผ่าน PgBouncer เสมอ** (ทั้งแอปและ `db-apply.sh`) — Flyway ใช้ advisory lock แบบ session ซึ่งพังใน transaction pooling; แอปจึงมี `DB_MIGRATE_URL` แยกจาก `DB_URL`
  (ตกลง 2026-10-21: **validate** ของทางที่ 2 อ่าน `flyway_schema_history` อย่างเดียว ไม่มี lock → ใช้ DataSource ของแอป `an22_app` ผ่าน PgBouncer ได้ — แอปไม่ต้องรู้รหัส owner)
- **ทางที่ 1 (ค่าเริ่ม)**: แอปรัน Flyway เองตอน start ด้วย role `an22_owner`
- **ทางที่ 2 (คนรันเอง / DBA)**: `scripts/db-apply.sh` แล้วตั้ง `FLYWAY_MIGRATE=false` ให้แอป — แอปแค่ `validate` (version ใน DB ไม่ตรงกับไฟล์ → ไม่ start) และไม่ต้องรู้รหัสของ `an22_owner`
- ไม่มี Docker / Flyway CLI: `psql -f` ทีละไฟล์ตามเลข version ได้บน **DB ว่าง** (ไฟล์เป็น SQL ล้วน) แต่ครั้งต่อไปต้องใช้ทางที่ 1 / 2 หลัง `flyway baseline` — เขียนวิธีไว้ใน `db/README.md`
- dev: `compose.yaml` ใส่ `docker/initdb.sh` ใน `docker-entrypoint-initdb.d` ซึ่งสร้าง `azure_admin` (NOSUPERUSER CREATEDB CREATEROLE BYPASSRLS — จำลอง server admin ของ Azure) แล้ว `psql -U azure_admin -v … -f db/roles.sql` (รหัสจาก `.env`);
  test: Testcontainers ใช้ `initdb.sh` / `roles.sql` / `pgbouncer.ini` ไฟล์เดียวกัน แล้วแอป migrate + seed (ตรงเข้า postgres) — แอปใน test ต่อผ่าน pgbouncer

## กติกาที่ใช้ทุกขั้น

### Schema
- ทุกตารางข้อมูล: `tenant_id bigint not null` (+ `branch_id` ตามระดับใน `data-model.md`), `ENABLE` + `FORCE ROW LEVEL SECURITY`,
  policy `USING / WITH CHECK (tenant_id = current_setting('app.tenant_id', true)::bigint)` — ไม่ตั้งค่า = ไม่เห็นอะไรเลย
- **ชื่อตาราง** (ตกลงแล้ว 2026-10-21): ทุกตารางขึ้นต้นด้วย `dt_` ตัวเล็ก snake_case (`dt_sales`, `dt_sale_lines`, `dt_companies`, `dt_audit_log`, …)
  ไม่ใส่ `"..."` ใน SQL (เขียน `DT_SALES` ก็ได้ผลเดียวกัน); ยกเว้น `flyway_schema_history` ของ Flyway —
  **ชื่อตารางในไฟล์นี้และใน `data-model.md` เขียนแบบไม่มี prefix: ชื่อจริงใน DB = `dt_` + ชื่อนั้น**; คอลัมน์ / function / trigger / role ไม่มี prefix
- index / unique ขึ้นต้นด้วย `tenant_id`; ชื่อ snake_case
- **FK** (ตกลงแล้ว 2026-10-20):
  - **มี FK** ทุกความสัมพันธ์หลัก (เช่น `sale_lines → sales / products`, `branch_stock → branches`, `shifts → pos_terminals`) แบบรวม `(tenant_id, <ref>_id) → (tenant_id, id)`
    — RLS ไม่ตรวจว่า id ที่อ้างเป็นของบริษัทไหน FK รวม tenant ให้ DB ปฏิเสธการอ้างข้ามบริษัทเอง (ตารางแม่มี `UNIQUE (tenant_id, id)`)
  - `ON DELETE RESTRICT` (ไม่ cascade) — แอปตรวจ "มีเอกสารอ้าง → ลบไม่ได้" ก่อนเพื่อตอบข้อความไทย, FK เป็นตาข่ายชั้นสุดท้าย
  - **ทุก FK ต้องมี index ฝั่งตารางลูก** (PostgreSQL ไม่สร้างให้) ขึ้นต้นด้วย `tenant_id`
  - **ไม่มี FK (index ธรรมดา)**: `created_by` / `updated_by`, `audit_log.user_id` / ref (ประวัติต้องอยู่แม้ record ถูกลบ),
    snapshot ในเอกสาร (ชื่อสินค้า, ชื่อช่องทาง, `pos_id`, ชื่อผู้ขาย), ข้างใน `held_bills.payload` (jsonb)
  - purge บริษัท (ขั้น 12) ลบตามลำดับลูก → แม่ (รายการตารางเรียงไว้ในโค้ดที่เดียว + test ว่าครบทุกตารางที่มี `tenant_id`)
- เงิน `numeric(14,2)`, เวลา `timestamptz`, วันที่ธุรกิจ `date`, enum = `text` + `CHECK`; ห้ามลบเอกสาร (มีแต่เปลี่ยนสถานะ)
- **คอลัมน์ผู้ทำ / เวลา ทุกตาราง** (ตกลงแล้ว 2026-10-20):
  - `created_at timestamptz not null`, `created_by bigint` = ผู้ insert ครั้งแรก
  - `updated_at timestamptz not null`, `updated_by bigint` = ผู้แก้ล่าสุด (ตอน insert = ค่าเดียวกับ created)
  - ตาราง append-only (`audit_log`, `stock_movements`, `shift_adjustments`, `cash_movements`, `drawer_opens`) มีแค่ `created_*` (แก้ไม่ได้อยู่แล้ว)
  - ตั้งด้วย **trigger** `set_row_stamp()` (BEFORE INSERT / UPDATE) จาก `now()` + `current_setting('app.user_id', true)` — แอปลืมใส่ไม่ได้;
    UPDATE ทับ `created_*` ไม่ได้ (trigger คืนค่าเดิม)
  - `*_by` ไม่ทำ FK (ผู้ใช้ถูกลบได้ แต่ประวัติต้องอยู่) และบอกชนิดผู้ทำใน `created_by_kind` / `updated_by_kind` (`text` + `CHECK`, จาก `app.actor_kind`):
    `user` → `*_by` = `users.id` · `platform` → id ของ `platform_admins` · `system` (job ของ api, seed) → `*_by` = `null`
  - ตาราง platform (`companies`, `billing_invoices`, …) ใช้กติกาเดียวกัน (`*_by` = ผู้ใช้ร้านตอนสมัคร / เปลี่ยนแพ็กเกจ หรือ platform admin)
  - **ไม่ส่งออก API** (interface TS ไม่มี field นี้ — ใช้ดูใน DB / export บริษัท); ไม่แทน field ธุรกิจเดิม (`cashier`, `voidedBy`, `createdBy` ของบิลพัก ฯลฯ ยังเก็บตาม spec)
- ตาราง platform (`companies`, `billing_invoices`, `platform_admins`, `password_reset_tokens`, ตารางนับ login ผิด) ไม่อยู่ใต้ RLS
- DB role 3 ตัว (สร้างด้วย `db/roles.sql` — ต้องใช้ admin ระดับ server (CREATEROLE) จึงไม่อยู่ใน migration; รหัสผ่านจาก env / ตัวแปร psql — ไม่อยู่ใน repo; migration ทำ grant):
  - `an22_owner` — เจ้าของตาราง, Flyway ใช้ตัวนี้เท่านั้น
  - `an22_app` — แอปฝั่งร้าน: ไม่ใช่ owner, ไม่มี `BYPASSRLS`, `audit_log` ได้แค่ `INSERT` + `SELECT`, อ่าน `companies` ได้ (ตรวจบริษัทยังใช้งาน)
  - `an22_platform` — งาน platform + ก่อน login (login / signup / ลืมรหัสผ่าน หาบริษัทจากรหัส): `BYPASSRLS`
  - แอปมี DataSource 2 ตัว (app / platform); ห้ามใช้ `SET` แบบ session (connection pool)
- เลขเอกสาร (`db/DocNumbers`, เรียกจาก service): ตาราง `doc_counters (tenant_id, prefix, last_no)` PK `(tenant_id, prefix)` + `INSERT … ON CONFLICT DO UPDATE SET last_no = last_no + 1 … RETURNING`
  - อยู่ใน transaction เดียวกับเอกสาร → rollback แล้วเลขไม่ข้าม (ไม่ใช้ `SEQUENCE` — เลขหายเมื่อ rollback); prefix ใหม่ = เริ่ม 1 เอง
  - ล็อกแถวตัวนับจน commit → ไม่ซ้ำแม้หลาย instance / ผ่าน PgBouncer; **ออกเลขหลังตรวจกฎผ่านหมดแล้ว** (ถือล็อกสั้นที่สุด)
  - วันในเลข = วันท้องถิ่น Asia/Bangkok (`Clock`); รหัสสาขา = `Branch.code` ณ ตอนออก (ใบกำกับใช้สาขาของบิล E25)
  - `SUB-YYYYMM-NNNN` (ใบแจ้งหนี้ SaaS ไม่ซ้ำทั้งแพลตฟอร์ม) ใช้ตัวนับฝั่ง platform `platform_doc_counters (prefix, last_no)` (ไม่อยู่ใต้ RLS, role platform) วิธีเดียวกัน
  - test: ออกเลขพร้อมกันหลาย thread prefix เดียวกัน → ไม่ซ้ำ ไม่ข้าม; rollback → เลขถัดไปไม่ข้าม

### ข้อมูลส่วนบุคคล (ตกลงแล้ว 2026-10-20)
- **เข้ารหัสในแอป** (`security/PiiCipher`, AES-256-GCM, nonce สุ่มต่อค่า) เก็บเป็น `bytea` = `[version กุญแจ 1 byte][nonce 12 byte][ciphertext + tag]` (หมุนกุญแจได้: อ่านได้ทุก version, เขียนด้วยตัวล่าสุด)
  - **AAD** = `<ตาราง>.<ช่อง>:<tenant_id>` (เช่น `tax_invoices.buyer:1`) — copy ค่าที่เข้ารหัสไปแถวของบริษัทอื่น / ช่องอื่นแล้วถอดไม่ได้
  - ผู้ซื้อในใบกำกับ / ใบลดหนี้ (`TaxInvoiceBuyer`: ชื่อ, `idType`, เลขผู้เสียภาษี / บัตรประชาชน / passport, สาขา, ที่อยู่) → `buyer_enc bytea` ทั้งก้อน
    + `buyer_tax_id_hash bytea` = HMAC-SHA256 (กุญแจแยก) ของ `<tenant_id>:<เลขที่ normalize แล้ว>` (ตัดช่องว่าง / `-`, ตัวใหญ่) + index `(tenant_id, buyer_tax_id_hash)`
    — มี tenant ใน hash: คนเดียวกันซื้อต่างบริษัทได้ hash ต่างกัน (DB หลุดก็จับคู่ข้ามบริษัทไม่ได้) — ใช้กับ `GET /tax-invoices/buyer?taxId=` (ค้นได้แบบตรงทั้งเลขเท่านั้น)
  - `held_bills.payload` → `payload_enc bytea` ทั้งก้อน (ไม่ query ข้างใน)
- กุญแจจาก env (`PII_KEYS` = `<version>:<base64 32 byte>,…`, `PII_HASH_KEY`; สร้างด้วย `openssl rand -base64 32`; ทุก instance ต้องได้ชุดเดียวกัน — หมุน: deploy ให้ทุกตัวอ่านกุญแจใหม่ได้ก่อน แล้วค่อยเขียนด้วยกุญแจใหม่) — ไม่อยู่ใน DB / repo / log; ไม่ตั้ง = ไม่ start; **สำรองกุญแจแยกจาก backup ของ DB** (กุญแจหาย = ข้อมูลผู้ซื้อหาย) — เขียนไว้ใน `CLAUDE.md` / `db/README.md`
- **ไม่ใช้** pgcrypto (กุญแจผ่าน SQL → อาจลง log / `pg_stat_statements`), ไม่ hash อย่างเดียว (ต้องพิมพ์ใบกำกับได้)
- **เก็บ text ตามเดิม**: อีเมล / ชื่อผู้ใช้ (ต้อง unique + login), ข้อมูลบริษัท / สาขา / ผู้จำหน่าย (ข้อมูลธุรกิจที่พิมพ์บนเอกสาร) — ป้องกันด้วย RLS + เข้ารหัส disk / backup + ไม่ log
- **บัตรเครดิต: ไม่เก็บเลขบัตรเต็มเด็ดขาด** (PCI DSS) — `SalePayment.reference` ที่เป็นตัวเลข 13–19 หลัก (ตัด ช่องว่าง / `-`) และผ่าน Luhn → 400; เก็บได้แค่เลขอนุมัติ / 4 หลักท้าย
  — **เปลี่ยน spec**: แก้ใน `../FrontEnd-An22` ก่อนขั้น 9 (กฎใน `core/models` + mock + spec + `api-contract.md` / `domain-rules.md` + ช่องกรอกที่ POS ตรวจเหมือนกัน, commit เดียว) ข้อความ error ยืนยันกับผู้ใช้ตอนนั้น
- ค่าที่ถอดรหัสแล้วไม่ลง log / ข้อความ error; export บริษัท (owner) ถอดรหัสให้

### 3 ชั้น: controller → service → dao (ตกลงแล้ว 2026-10-21)

| ชั้น | ทำ | ห้าม |
| --- | --- | --- |
| **controller** | รับ HTTP, อ่าน header / path / body, ตรวจ role, เรียก service **1 method ต่อ request**, คืน DTO | SQL, `@Transactional`, กฎธุรกิจ |
| **service** | `@Transactional` (1 method = 1 use case = 1 transaction), เรียงลำดับตรวจกฎ (`rules/`), เรียก dao, audit, เลขเอกสาร | SQL ตรง, ของ HTTP (`HttpServletRequest`) |
| **dao** | SQL ผ่าน `JdbcClient`, map แถว ↔ object | กฎธุรกิจ, `@Transactional` (ใช้ transaction ของ service) |

- controller เรียก service → เปิด transaction, service return → commit, throw → rollback
- **controller เรียก service แค่ 1 method** — เรียก 2 method = 2 transaction (ตัวแรก commit ไปแล้ว ตัวหลังล้มย้อนไม่ได้); งานที่ต้องสำเร็จ / ล้มพร้อมกันอยู่ใน service method เดียว
- service เรียก service อื่น = ร่วม transaction เดิม (`REQUIRED`); เปิดใหม่เฉพาะที่ตั้งใจ (`REQUIRES_NEW` — `login_failed`)
- ห้ามเรียก `@Transactional` method ใน class เดียวกันเอง (ไม่ผ่าน proxy); `ApiException` เป็น `RuntimeException` (rollback อัตโนมัติ)
- `rules/` (pure) ถูกเรียกจาก service เท่านั้น

### Transaction (ตกลงแล้ว 2026-10-21)
- ใช้ `@Transactional` ของ Spring ตามปกติที่ชั้น service (1 request = 1 transaction) — `JdbcClient` ใช้ connection ของ transaction ปัจจุบันเอง
- 2 DataSource = 2 transaction manager: `appTx` (`an22_app`, ค่าเริ่ม `@Primary`) และ `platformTx` (`an22_platform` — ใช้ `@Transactional("platformTx")`)
- `TenantTransactionManager` (subclass `DataSourceTransactionManager`, override `prepareTransactionalConnection`) ตั้ง `app.tenant_id` / `app.user_id` / `app.actor_kind`
  จาก `CurrentUser` ของ request ทันทีที่เปิด transaction → service ไม่ต้องจำตั้งเอง; ค่าเป็น SET LOCAL หายเมื่อจบ transaction (connection pool ปลอดภัย)
- **TxGuard**: DataSource ของแอปห่อให้ query นอก transaction → error ทันที (กันลืม `@Transactional` — ถึงหลุดไป RLS ก็ให้ 0 แถว แต่จะกลายเป็นบั๊กเงียบ)
- GET ใช้ `@Transactional(readOnly = true)` + `enforceReadOnly` → PostgreSQL `READ ONLY` — ชั้นที่สองของกฎ "GET ห้ามแก้ข้อมูล" (E49)
- งานที่ต้องบันทึกแม้ request ล้ม (audit `login_failed`, นับ login ผิด) ใช้ `REQUIRES_NEW`
- เลขเอกสาร (`doc_counters`) อยู่ใน transaction เดียวกับเอกสาร — rollback แล้วเลขไม่หาย / ไม่ข้าม
- test: transaction ซ้อนไม่รั่ว tenant ข้าม request (connection เดิมจาก pool ไม่มีค่า tenant ค้าง), query นอก transaction → error, GET เขียนไม่ได้

### Stateless / scale แนวนอน (ตกลงแล้ว 2026-10-21)
api รันได้หลาย instance หลัง load balancer โดยไม่ต้อง sticky session — **ไม่มี state ใน memory / disk ของ instance**; state ทั้งหมดอยู่ใน PostgreSQL
- **auth**: JWT ตรวจได้ทุก instance (`JWT_SECRET` เดียวกัน, header `kid` เพื่อหมุน secret ได้: ตรวจได้ทั้งตัวเก่า / ใหม่ช่วงเปลี่ยน); สิทธิ์ / บริษัทอ่านจาก DB ทุก request (ไม่ cache ใน instance)
- **ตัวนับ login ผิด, เลขเอกสาร, บิลพัก, ล็อก POS** อยู่ใน DB (ไม่ใช่ memory) — instance ไหนรับ request ก็เห็นค่าเดียวกัน
- **job ตามเวลา** (`@Scheduled(cron, zone = "Asia/Bangkok")`, รายวัน): ออกใบแจ้งหนี้ที่ถึง `nextInvoiceOn`, ระงับบริษัทที่ค้างเกินผ่อนผัน, ล้าง token ตั้งรหัสผ่าน / ตัวนับ login ผิดที่หมดอายุ
  — **purge ไม่ใช่ job** (platform admin สั่งเองที่ `POST /platform/companies/:id/purge`)
  - ชั้น 1: ทุก instance ตื่นพร้อมกัน → ทำงานจริงตัวเดียวด้วย `pg_try_advisory_xact_lock(<เลขประจำ job>)` (ไม่ได้ = ออกทันที; ปล่อยเองตอนจบ transaction / instance ตาย)
  - ชั้น 2: unique `(company_id, period_start)` + `INSERT … ON CONFLICT DO NOTHING` → ออกใบซ้ำไม่ได้ (รันซ้ำได้ผลเดิม); เลข `SUB-` ออกเมื่อ insert สำเร็จเท่านั้น
  - `POST /auth/login` / `GET /me` เรียก `catchUp(บริษัทนั้น)` ฟังก์ชันเดียวกับ job (ไม่ใช้ lock รวม — พึ่งชั้น 2) → ถึงรอบก่อน job รันก็ถูกต้อง; endpoint อื่นไม่ตรวจ billing
- **Flyway ตอน start หลาย instance พร้อมกัน**: Flyway ล็อก `flyway_schema_history` เอง (รอกัน ไม่รันซ้ำ); production แนะนำ "ทางที่ 2" (รัน migrate ครั้งเดียวก่อน deploy, แอป `FLYWAY_MIGRATE=false`)
- **ไฟล์**: ไม่เขียนไฟล์ลง disk ของ instance — export บริษัทตอบเป็น stream ตรง; ถ้าทำเป็น job + ลิงก์ดาวน์โหลดภายหลัง ไฟล์ต้องอยู่ที่ storage กลาง (DB / object storage)
- **connection**: แอปต่อผ่าน PgBouncer เสมอ (หัวข้อ "PgBouncer") — pool ของ instance (Hikari `DB_POOL_SIZE`) ต่อ PgBouncer, PgBouncer คุมจำนวน connection จริงเข้า PostgreSQL (`default_pool_size` × user ≤ `max_connections`)
- **ปิดเครื่องนุ่มนวล**: `server.shutdown=graceful` (รอ request ที่ค้างให้จบ) + health แยก `GET /api/healthz` (liveness, ไม่แตะ DB) / `GET /api/readyz` (readiness, ping DB) ให้ load balancer
- **log** เป็น JSON ไป stdout (ไม่เขียนไฟล์), มี request id (รับ `X-Request-Id` จาก web ถ้ารูปแบบถูก ไม่งั้นสุ่ม) ไว้ตามข้าม instance — รายละเอียดหัวข้อ "Log ต่อ request"
- **เวลา** (เปลี่ยน 2026-10-22 ตามผู้ใช้ — เดิม instance ใช้ UTC): container + JVM ใช้เวลาท้องถิ่น **Asia/Bangkok** (`Dockerfile` `TZ`, `Application.main` ตั้ง default เอง ไม่พึ่งเครื่อง)
  → log เป็น +07:00, session DB ได้ `TimeZone = Asia/Bangkok` (pgJDBC ส่งตาม JVM) — กฎ / วันธุรกิจยังคิดด้วย `Clock` ในโค้ดเสมอ, JSON เวลาเป็น `Instant` (`…Z`) เหมือนเดิม; test รันด้วย Asia/Bangkok

### PgBouncer (บังคับ — ตกลงแล้ว 2026-10-21)
- `pool_mode = transaction`; แอป (role `an22_app` / `an22_platform`) ต่อผ่าน PgBouncer เท่านั้น; `an22_owner` (Flyway) ต่อ PostgreSQL ตรง
- PgBouncer **≥ 1.21** + `max_prepared_statements` > 0 (pgJDBC ใช้ server-side prepared statement หลังรันซ้ำ 5 ครั้ง — รุ่นเก่ากว่านี้ต้อง `prepareThreshold=0`)
- auth `scram-sha-256`; dev / test: รายชื่อผู้ใช้ / รหัสสร้างจาก env ตอน container start (`docker/pgbouncer/start.sh`, ไม่มีรหัสใน repo); prod: PgBouncer ของ Azure ตรวจกับ PostgreSQL เอง
- ~~PostgreSQL รับ connection ของ app / platform จาก PgBouncer เท่านั้น (`pg_hba`)~~ — **ตัดออก 2026-10-21** (Azure แก้ pg_hba ไม่ได้ และ PgBouncer อยู่เครื่องเดียวกับ DB):
  แทนด้วย network (private access / firewall) + แอปไม่ start ถ้า `DB_URL` เป็น host:port เดียวกับ `DB_MIGRATE_URL`
- **กติกาโค้ดที่ต้องตามเพราะ transaction pooling** (connection จริงเปลี่ยนตัวได้ทุก transaction):
  - ตั้งค่าได้แค่ `set_config(…, true)` / `SET LOCAL` — ห้าม `SET` แบบ session, ห้าม `LISTEN` / `NOTIFY`, ห้าม temp table ข้าม transaction, ห้าม cursor ค้างข้าม transaction
  - advisory lock ใช้แบบ `_xact_` เท่านั้น (`pg_try_advisory_xact_lock`) — แบบ session ห้าม
  - read-only ใช้ `BEGIN READ ONLY` ต่อ transaction (pgJDBC `readOnlyMode=transaction` ค่าเริ่ม) ไม่ใช่ session characteristics
  - ทุก query อยู่ใน transaction อยู่แล้ว (TxGuard) — query เดี่ยวนอก transaction ใน PgBouncer จะได้ connection ที่ไม่มี tenant
- image: `edoburu/pgbouncer:v1.25.2-p0` (ตกลง 2026-10-21 — เวอร์ชันเดียวกับ PgBouncer ในตัวของ Azure); `pgbouncer.ini` ตั้งเฉพาะค่าที่ Azure ตั้งได้
- test ต้องวิ่งผ่าน PgBouncer จริง (จับ bug ที่เกิดเฉพาะ transaction pooling): RLS / tenant ไม่ค้างข้าม transaction เมื่อ PgBouncer สลับ connection, prepared statement ซ้ำหลายรอบผ่าน, advisory lock ของ job

### Log ต่อ request (ตกลงแล้ว 2026-10-21)
`web/RequestLogFilter` (filter แรกสุด) เขียน **1 บรรทัด JSON ต่อ request** หลังตอบเสร็จ:
```json
{"type":"access","requestId":"a1b2…","method":"POST","path":"/api/sales","query":"from=2026-10-01",
 "status":201,"durationMs":184,"tenantId":1,"userId":2,"branchId":1,"terminalId":1,"ip":"203.0.113.7",
 "requestBody":{…},"responseBody":{…}}
```
- `durationMs` = เวลาตั้งแต่ request เข้า filter จนตอบเสร็จ (รวม DB + serialize); 5xx = ระดับ error, 4xx = warn, อื่น = info; `/api/healthz`, `/api/readyz` = debug
- **body ตาม env `LOG_BODY`** = `off` | `error` (**ค่าเริ่ม** — ใส่ body เฉพาะ 4xx / 5xx) | `all` (dev / ไล่ปัญหาชั่วคราว)
  - อ่านด้วย `ContentCachingRequestWrapper` / `ContentCachingResponseWrapper`; เฉพาะ `application/json`; ตัดที่ `LOG_BODY_MAX` (ค่าเริ่ม 4 KB) + `"truncated": true`
- **mask ก่อน log เสมอ** (ทุกระดับความลึกของ JSON, เทียบชื่อ key ไม่สนตัวพิมพ์) → `"***"`:
  `password`, `token`, `approval`, `buyer` (ทั้งก้อน — PII), `taxId`, `address`, `email`, `reference` (อาจเป็นเลขบัตร)
- **ไม่ log body เลย** (แม้ `all`): `/api/auth/**`, `/api/platform/auth/**`, `/api/shifts/*/unlock`, `/api/company/export`, `/api/pos/held-bills` (บิลพักมีข้อมูลผู้ซื้อ)
- query: ค่าของ `token` / `code` / `password` / `taxId` → `***` (แบบเดียวกับ an22-web)
- ไม่ log header (มี `Authorization`); ทุนที่ซ่อนจาก user ไม่เกี่ยว (log ไม่ถึงผู้ใช้) แต่ response ที่ log = ค่าหลังซ่อนแล้ว
- test: บรรทัด access มีครบทุก field + `durationMs`, `LOG_BODY=error` ไม่มี body ตอน 2xx / มีตอน 400, mask ทุกชื่อในรายการ (ซ้อนลึก), path ที่ห้ามไม่มี body แม้ `all`, ตัดความยาว

### จำกัดขนาดคำตอบ (ตกลงแล้ว 2026-10-21 — เปลี่ยน spec)
ปัญหา: รายการทุกหน้าไม่มี paging (หน้าบ้านกรอง / เรียงเอง) และหน้าการขาย / กะมี preset "ทั้งหมด" → ร้านขาย 300 บิล / วัน 1 ปี ≈ 110,000 บิล ≈ 100–200 MB ใน request เดียว
- **ช่วงวันที่บังคับ ≤ 366 วัน** (`from` และ `to` ต้องมีทั้งคู่) ที่ `GET /sales`, `/shifts`, `/shifts/variance-report`, `/credit-notes`, `/tax-invoices`, `/audit-log`, `/products/:id/movements`
  — ไม่มี / เกิน → 400; query ด้วย `shiftId=` (รายการของกะเดียว) ไม่ต้องมีช่วงวันที่
- **เพดานจำนวนแถว** `MAX_LIST_ROWS` (ค่าเริ่มเสนอ 20,000) ทุก list endpoint (รวม `/transfers`, `/products`): query `LIMIT max + 1` → เกิน = 400 บอกให้ลดช่วง (ไม่ตัดเงียบ ๆ — รายงานจะผิดโดยไม่รู้ตัว)
- DB timeout (กัน query / transaction ค้าง):
  - หลัก: `db/roles.sql` — `ALTER ROLE an22_app / an22_platform SET statement_timeout = :'statement_timeout'` (เสนอ 30 วิ) + `idle_in_transaction_session_timeout` (เสนอ 60 วิ);
    `an22_owner` ไม่ตั้ง (migration อาจนาน) — ไม่ใส่ใน JDBC URL (`options` ไม่ผ่าน PgBouncer) และไม่ใส่ใน migration (ต้องสิทธิ์ superuser)
  - งานที่นานจริง (export, job billing): `SET LOCAL statement_timeout = '…'` ใน transaction นั้นเท่านั้น
  - ~~ตาข่ายสุดท้าย: PgBouncer `query_timeout` / `idle_transaction_timeout`~~ — PgBouncer ของ Azure ไม่มีให้ตั้ง (2026-10-21): ตาข่ายคือค่าของ role ข้างบน
  - เกินเวลา → PostgreSQL `57014` → rollback → `ErrorHandler` ตอบ 503 `{ message }` (ข้อความยืนยันพร้อมข้อ 6 ของคำถามระหว่างทาง)
- **แก้ spec ใน `../FrontEnd-An22` ก่อนขั้น 8** (endpoint แรกที่โดน; commit เดียว): `api-contract.md` + mock (ตรวจช่วง / เพดานแบบเดียวกัน) + spec ของ mock
  + หน้าบ้านเอา preset "ทั้งหมด" ออกจากหน้าการขาย / กะ และบังคับเลือกช่วงวันที่ — **ข้อความ error + ตัวเลข ยืนยันกับผู้ใช้ตอนนั้น**
- paging จริง (server กรอง / เรียง / export) = งานแยกหลัง C2
- **ยืนยันกับผู้ใช้แล้ว (2026-10-23)**: ช่วงวันที่ ≤ **366 วัน**, `MAX_LIST_ROWS` = **20,000**, timeout ของ role 30 วิ / 60 วิ (ตามเดิม); ข้อความ:
  - ไม่ส่ง `from` / `to` → 400 "กรุณาเลือกช่วงวันที่ (ไม่เกิน 366 วัน)"; เกิน → 400 "ช่วงวันที่ต้องไม่เกิน 366 วัน"
  - แถวเกิน → 400 "ข้อมูลมากกว่า 20,000 รายการ กรุณาเลือกช่วงวันที่ให้สั้นลง" (list ที่ไม่มีช่วงวันที่: "ข้อมูลมากกว่า 20,000 รายการ กรุณากรองให้แคบลง")
  - DB เกินเวลา (57014) → 503 "ระบบใช้เวลานานเกินไป กรุณาลองใหม่หรือลดช่วงข้อมูล"
  - หน้าบ้าน: เอา preset "ทั้งหมด" ออกจากหน้าการขาย / กะ บังคับเลือกช่วง — แก้พร้อม docs + mock ใน commit เดียว
- **ทำแล้ว (2026-10-23)**: spec หน้าบ้าน `0e0e458` (branch `docs/api-response-limits` ของ FrontEnd-An22 — ยังไม่ push): `list-limit.model.ts`, mock ตรวจช่วง / เพดาน,
  เอา preset "ทั้งหมด" ออก 4 หน้า (การขาย / กะ / ประวัติการใช้งาน / บัตรสต็อก), ประวัติ serial ย้อนหลัง 366 วัน;
  api: `rules/ListLimits` (`parseRange`, `rowLimitError`, `QUERY_LIMIT` = 20,001 ใน SQL), ใช้แล้วกับ `/products`, `/transfers` (กรองสาขา / ทิศใน SQL),
  `/products/:id/movements` (ช่วงบังคับ); `ErrorHandler`: SQLSTATE 57014 → 503 — endpoint ที่มาทีหลัง (`/sales`, `/shifts`, …) ใช้ตัวเดียวกัน

### Production: Azure Database for PostgreSQL Flexible Server (ผู้ใช้แจ้ง 2026-10-21)
- PgBouncer = **ตัวในตัวของ Azure** (1.25.2, port 6432 host เดียวกับ server, ตามไปตอน HA failover; ไม่มีบน tier Burstable) — ตั้ง `pgbouncer.enabled`, `pool_mode = transaction`,
  **`max_prepared_statements` > 0** (ค่าเริ่ม Azure = 0), `default_pool_size` ตามสูตร
- **ไม่มี superuser**: server admin = NOSUPERUSER CREATEDB CREATEROLE (+ `azure_pg_admin`) → `db/roles.sql` เขียนให้รันด้วย admin แบบนี้ (test จำลองด้วย `azure_admin`)
  - `an22_platform BYPASSRLS` บน PG16+ ผู้ตั้งต้องมี BYPASSRLS — Microsoft Q&A บอกว่า admin ทำได้ (ไม่ใช่เอกสารทางการ) → **ต้องลองบน Azure จริงก่อนขึ้น prod**;
    ถ้าไม่ได้ ทางสำรอง: policy `TO an22_platform USING (true)` แทน BYPASSRLS (ถามผู้ใช้ตอนนั้น)
- ไม่มี pg_hba → network (private access / firewall); TLS บังคับ → URL `sslmode=verify-full&sslrootcert=<CA ของ Azure>`; extension ต้องเปิดใน `azure.extensions` ก่อน migrate (`btree_gist` ขั้น 5b)
- รายการที่ต้องลองบน Azure จริง: `db/README.md` หัวข้อ "Production: Azure"

### Request
- JWT: claims `sub`, `tenantId`, `role`, `branchIds` / `allBranches`; ร้าน vs platform แยกด้วย `aud` (`shop` / `platform`) — ใช้สลับกัน → 401; อายุ `JWT_TTL_DAYS` (ค่าเริ่ม 7)
- ทุก request ของร้าน: อ่านผู้ใช้ + บริษัทจาก DB (สิทธิ์สาขามีผลทันที) — บริษัทไม่ active / ผู้ใช้ปิด / ลบแล้ว → 401
- `X-Branch-Id`: ตรวจทุก request ยกเว้น `GET /me` (ไม่ใช่สาขาที่ใช้งาน / เข้าไม่ได้ → 403 "ไม่มีสิทธิ์ทำงานในสาขานี้"; งานเขียนสต็อก / ขาย / กะไม่มี → 400 ตาม E17 / E24)
- `X-Terminal-Id`: ตรวจว่าเป็นเครื่องของ tenant (audit `terminalId` = null ถ้าไม่ใช่)
- error ทุกตัว `{ "message": "<ไทย>" }` ข้อความตาม mock ทุกตัวอักษร; JSON ไม่ละ `null`; GET ห้ามแก้ข้อมูล
- error ทั่วไป (ตกลง 2026-10-21): path ไม่มี / method ไม่รองรับ → 404 `ไม่มี endpoint <METHOD> <path ไม่รวม /api>` (เหมือน mock ไม่มี "Mock:");
  JSON อ่านไม่ได้ / ชนิดข้อมูลผิด → 400 `ข้อมูลที่ส่งมาไม่ถูกต้อง`; อื่น → 500 `ระบบขัดข้อง กรุณาลองใหม่` (stack trace อยู่ใน log เท่านั้น); ไม่มี token → 401 `เซสชันหมดอายุ กรุณาเข้าสู่ระบบใหม่` (`SESSION_EXPIRED` ของ mock)
- ไม่มีความลับใน repo / log / ข้อความ error: `DB_*_PASSWORD`, `JWT_SECRET`, `PII_KEYS`, `PII_HASH_KEY` (≥ 32 byte, ไม่ตั้ง = ไม่ start) จาก env; ไม่ log body ของ auth / header Authorization

### ทุกขั้นย่อย (Definition of done)
1. migration ใหม่ apply บน DB ว่างผ่าน (`./mvnw verify` ใช้ Testcontainers เริ่มจากศูนย์ทุกครั้ง)
2. test ผ่านทั้งหมด รวม **test ข้าม tenant** ของตาราง / endpoint ใหม่: token บริษัท A อ่าน / เขียนของบริษัท B ไม่ได้ (id ของ B ใน URL / body → 404)
3. กฎที่ port มี unit test ตาม `*.model.spec.ts` (ตัวอย่าง input → output ข้อความไทยตรงตัว)
4. อัปเดต `CLAUDE.md` (ถ้ามีคำสั่ง / กติกาใหม่) + ติ๊กสถานะในไฟล์นี้ → commit (ไม่ push จนกว่าผู้ใช้สั่ง)
5. เจอ spec ไม่ชัด → ถือ mock; spec ต้องแก้ → **บอกผู้ใช้ก่อน** แล้วแก้ docs + mock ใน `../FrontEnd-An22` commit เดียวกัน; ต้องแก้ an22-web → บอกผู้ใช้ก่อน

## ขั้นย่อย

### ขั้น 0 — โครงโปรเจกต์ ✅ เสร็จ 2026-10-21
- `mvnw` + `pom.xml` (Boot 4: web, security, oauth2-resource-server, jdbc, flyway + flyway-database-postgresql, postgresql, validation; test: testcontainers-postgresql)
- `db/README.md`, `db/roles.sql`, `scripts/db-apply.sh` (ทดสอบ: DB ว่าง → `--roles --seed` ผ่าน, รันซ้ำไม่ทำอะไร — ทำด้วยมือแล้ว 2026-10-21 ด้วย admin ที่ไม่ใช่ superuser)
- `compose.yaml`: `postgres:18.6` + `pgbouncer` (transaction pooling, แอปต่อ port ของ pgbouncer) + `docker/initdb.sh` → `db/roles.sql` สร้าง database + role 3 ตัว (รหัสจาก `.env`); `.env.example` (ไม่มีค่าจริง), `.gitignore`
- config `FLYWAY_MIGRATE` (`true` = migrate ตอน start, `false` = validate อย่างเดียว)
- `PiiCipher` (เข้ารหัส / ถอด / HMAC, หลาย version) + test: ถอดกลับได้, ค่าเดิม 2 ครั้งได้ byte ต่างกัน, แก้ byte เดียว → ถอดไม่ได้, AAD คนละ tenant → ถอดไม่ได้,
  กุญแจเก่ายังอ่านได้หลังเพิ่ม version ใหม่, hash ของ `1-2345-67890-12-3` = `1234567890123` และต่างกันเมื่อ tenant ต่าง, ไม่ตั้ง env → ไม่ start (ข้อความไม่มีค่ากุญแจ)
- `application.yml`: context path `/api`, port 8080, Jackson ไม่ละ null + ISO-8601, `TZ` ของกฎ = Asia/Bangkok, health `GET /api/healthz` (liveness) + `GET /api/readyz` (ping DB), `server.shutdown=graceful`, log JSON → stdout + request id, `RequestLogFilter` (หัวข้อ "Log ต่อ request")
- `ApiException` + `ErrorHandler` (`{ message }`, 404 ทั่วไป, 400 JSON อ่านไม่ได้, 500 ไม่เปิดเผยรายละเอียด)
- test support: Postgres + PgBouncer container (network เดียวกัน, singleton) — Flyway migrate (รวม seed) ตรงเข้า postgres, DataSource ของแอปผ่าน pgbouncer + MockMvc
- `DB_URL` (ผ่าน pgbouncer) / `DB_MIGRATE_URL` (ตรง) แยกกัน
- `CLAUDE.md` ฉบับแรก (ตามหัวข้อ "สิ่งที่ CLAUDE.md ของแต่ละ repo ควรมี" ใน README: เวอร์ชัน, โครง package, คำสั่ง build / test / รัน / รีเซ็ต DB, กติกา schema, RLS, seed, ความปลอดภัย E49, JWT, การ port กฎ + fixtures, JSON)
- test: context ขึ้นได้, `/api/healthz`, error format
- (เพิ่ม 2026-10-22) `Dockerfile` + `.dockerignore` + compose profile `api`; เวลาท้องถิ่น Asia/Bangkok ทั้ง container / JVM (หัวข้อ "Stateless / scale" ข้อ เวลา)
- ผล: `./mvnw verify` ผ่าน 41 test (unit 21 + integration 20: PiiCipher, ApiProperties, RequestLogFilter, roles.sql ด้วย admin จำลอง Azure, ผ่าน PgBouncer, prepared statement ซ้ำ 30 รอบ,
  read-only ไม่ค้าง, `FLYWAY_MIGRATE=false`, error format, access log JSON); dev: compose + `spring-boot:run` → healthz / readyz / 401 ตรง, `DB_URL` = `DB_MIGRATE_URL` → ไม่ start

### ขั้น 1 — schema ฐาน + RLS + seed บริษัท / ผู้ใช้ ✅ เสร็จ 2026-10-22
- migration `db/migration/V1__base.sql`: grant ให้ role (`flyway_schema_history` ได้จาก `afterMigrate.sql` แล้ว), ตาราง platform (`companies`, `platform_admins`, `billing_invoices`, `password_reset_tokens`), `company_info`, `branches`, `users` (+ `user_branches`, partial unique owner), `menu_access`, `audit_log` (insert-only), `doc_counters`
- seed (`db/seed/V1_1__seed_demo.sql`): DEMO, HQ (`00000`) + BN, `admin` / `admin` (owner ตาม `fillOwner()`), `somchai` / `1234`, ผู้ใช้อื่นใน `SEED_USERS`, `company_info` จาก `SEED_STORE_INFO`, menu access ค่าเริ่ม, platform `platform` / `platform` — รหัสผ่านเป็น BCrypt
- ผลที่ต่างจาก / เพิ่มจากข้างบน (ทำแล้ว):
  - เพิ่ม `dt_platform_doc_counters` ใน V1 ด้วย (ตัวนับ `SUB-` — หัวข้อ "เลขเอกสาร")
  - policy ใช้ function `app_tenant_id()` = `nullif(current_setting('app.tenant_id', true), '')::bigint` — ค่าว่าง (ตัวแปรเคยถูกตั้งใน session) ต้องไม่ error แต่ได้ 0 แถว
  - `set_row_stamp()`: actor `system` (seed / import / job) ใส่ `created_at` เองได้ (seed ใช้ `User.createdAt` ของ mock); `user` / `platform` ได้ `now()` เสมอ — ตาราง append-only ใช้ `set_row_stamp('append_only')`
  - `User.createdAt` = `dt_users.created_at`, `AuditEvent.at` = `dt_audit_log.created_at` (ไม่มีคอลัมน์ซ้ำ); `audit_log.branch_id` มี FK (สาขาไม่ถูกลบ), `terminal_id` / `user_id` / ref ไม่มี
  - an22_app: DML บนตารางบริษัท, `dt_audit_log` INSERT + SELECT, `dt_doc_counters` ไม่มี DELETE, ตาราง platform อ่านได้แค่ `dt_companies` (+ `flyway_schema_history`) — ใบแจ้งหนี้ / token / platform admin ใช้ `platformTx` เท่านั้น
  - seed: admin เป็น owner ตั้งแต่ seed (ไม่ต้อง `fillOwner()`), รหัสผ่านของผู้ใช้อื่นทั้งหมด `1234` ตาม mock, BCrypt cost 10
  - `SchemaConventionsIT` ตรวจกติกา schema กับ DB จริงทุกครั้ง (ชื่อ `dt_`, owner, created_* / updated_* + trigger, RLS ENABLE + FORCE + policy, FK RESTRICT + รวม tenant + มี index, grant ของ an22_app) — migration ใหม่ที่ผิดกติกา test แดงเอง
  - test ใช้ `support/TenantSql` (ตั้ง app.* แบบ SET LOCAL เหมือน TenantTransactionManager ขั้น 2) + `support/TenantB` (บริษัทที่ 2)
- test: migrate บน DB ว่าง (ทั้งผ่านแอปและ `scripts/db-apply.sh`); RLS — ตั้ง tenant 1 ไม่เห็นแถวของ tenant 2 / ไม่ตั้ง = 0 แถว / INSERT ด้วย tenant อื่นถูกปฏิเสธ; `an22_app` UPDATE / DELETE `audit_log` ไม่ได้; `an22_app` ไม่มี BYPASSRLS และไม่ใช่ owner; unique รวม tenant (username ซ้ำได้ข้ามบริษัท); FK รวม tenant — `user_branches` อ้างสาขาของบริษัทอื่นไม่ได้; trigger ตั้ง `created_*` / `updated_*` ถูก (insert / update คนละผู้ใช้, แก้ `created_*` ทับไม่ได้)

### ขั้น 2 — auth / tenant / branch (แกนของทุก request) ✅ เสร็จ 2026-10-22
- กฎ: `UserRules` (`canWorkIn`, `hasAdminRights`, `isCompanyAdmin`, …), `CompanyRules.normalizeCompanyCode`, `CostVisibility.canSeeCost`, `SubscriptionRules` (`subscriptionStatus`, `billingNotice`, ข้อความระงับ)
- `JwtService`, `SecurityConfig` (chain `/api/platform/**` ↔ ร้าน), `TenantTransactionManager`, `BranchContext`, `TerminalContext`, `ClientIp`
- endpoint: `POST /auth/login` (E3 ข้อความเดียว, ระงับ / รอลบ ตามลำดับ mock, audit `login` / `login_failed`), `GET /me`, `POST /auth/verify` (400 เสมอ), `POST /platform/auth/login`, `GET /platform/me`
- จำกัด login ผิด / `auth/verify` ต่อบัญชี + ต่อ `X-Real-IP` (ตาราง platform นับครั้ง) — **ตกลง 2026-10-22**:
  - ต่อบัญชี (บริษัท + username ที่พิมพ์ — นับแม้ไม่มีบัญชีนี้จริง ไม่บอกว่ามีหรือไม่): ผิดเกิน 5 ครั้งใน 15 นาที → ล็อก 15 นาที; ต่อ `X-Real-IP`: 30 ครั้ง / 15 นาที
  - ใช้กับ `POST /auth/login`, `/auth/verify`, `/platform/auth/login`, `/shifts/:id/unlock` (ขั้น 8)
  - เกิน → **429** "พยายามบ่อยเกินไป กรุณารอสักครู่แล้วลองใหม่" (ข้อความเดียวกับ an22-web; ไม่ใช่ 401 จึงไม่ logout คนหน้าเครื่อง POS)
  - เพิ่มใน `../FrontEnd-An22/docs/backend/api-contract.md` (+ mock ถ้าจำเป็น) commit เดียวตอนทำขั้น 2
- `dt_companies` เปิด RLS (**ตกลง 2026-10-22**, migration V2): policy `id = app_tenant_id()` — an22_app เห็นเฉพาะบริษัทตัวเอง, an22_platform (BYPASSRLS) เห็นทั้งหมด;
  login / signup / ลืมรหัสผ่าน (ก่อนรู้บริษัท) ใช้ `platformTx`
- ผลที่ทำ (รายละเอียดที่แผนไม่ได้ระบุ):
  - Security 3 chain: public (`/healthz`, `/readyz`, `/error`, `POST /auth/login`, `POST /platform/auth/login`), `/platform/**` (aud `platform`), ร้าน (aud `shop`)
    — `auth/verify` อยู่ใน chain ร้าน แต่ไม่มี / ไม่รู้จัก token / session ไม่ผ่าน → 400 ข้อความเดียว (ไม่ใช่ 401)
  - `ShopSessionFilter` (หลังตรวจ JWT): `TxActor` = บริษัท + ผู้ใช้ของ token → `SessionService.load()` อ่านบริษัท / ผู้ใช้ / สาขาจาก DB ทุก request → รอลบ 403 → `X-Branch-Id` 403
    → `X-Terminal-Id`; controller รับ `ShopSession` / `BranchContext` (`require()` → 400 ไม่ได้เลือกสาขา) / `TerminalContext` เป็น parameter
  - `TxActor` (ThreadLocal) → `TenantTransactionManager` ตั้ง app.* ทุก transaction (ทั้ง appTx / platformTx); รู้ผู้ทำกลาง transaction ใช้ `ActorDao`; job (ขั้น 11) ตั้ง `TxActor` เอง
  - `TxGuardDataSource`: JdbcClient ขอ connection นอก transaction → error
  - `JWT_SECRET` = `<kid ตัวเลข>:<base64 ≥ 32 byte>,…` เซ็นด้วย kid สูงสุด (แบบเดียวกับ `PII_KEYS`), `JWT_TTL_DAYS` ≥ 7, claims ตามแผน + `iss = an22-api`
  - V2: RLS ของ `dt_companies` (ตามที่ตกลง) + **`dt_billing_invoices` ใต้ RLS (`company_id = app_tenant_id()`) และให้ an22_app อ่านได้** (แถบแจ้งเตือนใน `/me` อ่านด้วย appTx แทน platformTx — ตัดสินใจเองในขั้นนี้)
    + `dt_login_throttle` (key = HMAC ด้วย `PII_HASH_KEY` — ไม่เก็บ username / IP ตรง ๆ)
  - ตอบ 403 ของ Spring Security แบบข้อความกลางที่ตั้งเองในขั้น 0 ถูกเอาออก — 403 มาจาก `ApiException` ตาม mock เท่านั้น
  - audit `login`: `terminalId` = null จนกว่าจะมี `dt_pos_terminals` (ขั้น 4 ตรวจว่าเป็นเครื่องของบริษัท); billing ของ login / me อ่านใบแจ้งหนี้ที่มี — ออกใบ / ระงับ (`catchUp`) เป็นขั้น 11
  - record ของ JSON (ตรงกับ interface TS) อยู่ใน package `model/`; กฎที่ port อยู่ `rules/` (`UserRules`, `CompanyRules`, `CostVisibility`, `SubscriptionRules`)
  - spec: `../FrontEnd-An22` branch `docs/api-login-throttle` (commit `dc9b0fb`) เพิ่มกติกาจำกัด login + 429 ใน `api-contract.md` — mock ไม่จำลอง, ยังไม่ merge / push
- test: login ถูก / ผิดทุกแบบ (ข้อความเดียว), token ร้านใช้กับ `/platform/**` ไม่ได้และกลับกัน, token หมดอายุ / ปลอม → 401, ปิดผู้ใช้แล้ว token เดิม → 401, `X-Branch-Id` ผิด → 403 (ยกเว้น `/me`), ไม่ log รหัสผ่าน

### ขั้น 3 — ตั้งค่าบริษัท / สาขา / ผู้ใช้ ✅ เสร็จ 2026-10-22
- กฎ: `CompanyRules` (`branchError`, `branchDetailsError`, `companyInfoError`, `storeInfoOf`, …), `StoreInfoRules`, `MenuAccessRules`, `UserRules` (`userChangeError`, `userIdentityError`, `userBranchError`, `userScopeError`, `passwordError`, `ownerRoleError`, `ownerTransferError`), `PlanRules` (`planLimitError` 403)
- endpoint: `/settings/store|company|branch|menu-access`, `/branches`, `/users` (+ `/password`), `POST /company/owner`
- audit: `company_settings`, `branch_settings`, `branch_change`, `menu_access`, `user_change`, `user_password`, `owner_transfer`
- test: สิทธิ์ 403 ตาม `saas-plan.md` หัวข้อ 17, admin สาขา (E6), แพ็กเกจเต็ม → 403, ข้าม tenant
- (`requireShiftChangeError` ต้องรู้ว่ามีกะเปิด — ตาราง `shifts` ยังไม่มีในขั้นนี้ → ใส่ตอนขั้น 8 พร้อม "ปิดสาขาที่มีกะเปิด → 400")
- ผลที่ทำ (รายละเอียดที่แผนไม่ได้ระบุ):
  - package `branch/`, `company/` (settings + `POST /company/owner` + `PlanLimits`), `user/`; ตรวจ role ที่ controller ด้วย `web/Permissions` (ข้อความ 403 ตาม `adminOnlyError()` ของ mock)
  - กฎที่นับทั้งบริษัท (เพดานแพ็กเกจ, admin ที่ใช้งาน ≥ 1, สาขาที่ใช้งาน ≥ 1, owner 1 คน) ล็อกด้วย `TenantLocks.lock(ACCOUNTS)` (`pg_advisory_xact_lock`) — request พร้อมกันไม่หลุดกฎ
  - **`PUT /settings/branch` ไม่ส่ง `X-Branch-Id`**: mock แก้สาขาแรกแม้ admin สาขาไม่มีสิทธิ์ในสาขานั้น — api ตรวจว่าทำงานในสาขานั้นได้ (ไม่ได้ → 403 "ไม่มีสิทธิ์ทำงานในสาขานี้") (ตัดสินใจเอง: ปลอดภัยกว่า, หน้าบ้านส่ง header เสมอ)
  - role นอก owner / admin / user ใน `/users` → 400 "ข้อมูลที่ส่งมาไม่ถูกต้อง" (mock ไม่ตรวจ — DB มี CHECK)
  - id ใน path อ่านไม่ได้ (`/users/abc`) → 404 "ไม่มี endpoint …" เหมือน mock (เดิม 400)
  - เพดานแพ็กเกจของเครื่อง POS นับ 0 จนกว่าจะมี `dt_pos_terminals` (ขั้น 4)
  - ลบผู้ใช้: ลบ token ตั้งรหัสผ่านของผู้ใช้นั้นก่อน (ตาราง platform, FK RESTRICT) ด้วย platformTx
  - `MenuAccessRules.MENU` ลอกรายการเมนู + ป้ายจาก `core/navigation/menu.config.ts` — หน้าบ้านเพิ่ม / เปลี่ยนเมนูต้องแก้ที่นี่ด้วย
  - JSON: BigDecimal เป็นตัวเลขธรรมดา (`JacksonConfig`), ตัวเลขของ CompanyInfo ไม่มีทศนิยมเกิน (5 ไม่ใช่ 5.00)

### ขั้น 4 — ข้อมูลหลัก (ไม่ขึ้นกับ SKU) ✅ เสร็จ 2026-10-22
- **แบ่งขั้นใหม่ (ตกลง 2026-10-22)**: ราคา + โปรโมชั่นต้องอ้าง SKU (ตาราง products มาในขั้น 5) → ย้ายไป **ขั้น 5b**;
  ในขั้นนี้ `Category.productCount` / `Supplier.productCount` = 0 และยังไม่ตรวจ "มี SKU อ้าง → ลบไม่ได้" (เติมในขั้น 5)
- migration: `categories`, `suppliers`, `payment_methods`, `pos_terminals`
- กฎ: `CategoryRules`, `Suppliers`, `PaymentMethodRules`, `PosTerminalRules`, `Money.moneyInputError` (ที่ช่องทางชำระใช้)
- endpoint: `/categories`, `/suppliers`, `/payment-methods` (+ `order`), `/pos-terminals`; audit `terminal_change`
- เครื่อง POS: เพดานแพ็กเกจนับเครื่องที่ใช้งาน (เดิมนับ 0), audit `terminalId` = `X-Terminal-Id` เมื่อเป็นเครื่องของบริษัท
- seed: หมวด / ผู้จำหน่าย / ช่องทางชำระ / เครื่อง POS 3 เครื่อง ตาม mock
- ผลที่ทำ (รายละเอียดที่แผนไม่ได้ระบุ):
  - package `masterdata/` (หมวด / ผู้จำหน่าย / ช่องทางชำระ — `MasterDataController` → `MasterDataService` → Dao ต่อตาราง), `pos/` (เครื่อง POS)
  - PUT ตาม mock: หมวดแก้ได้แค่ code / name / active, ผู้จำหน่าย / ช่องทางชำระ **แทนทั้งตัว** (field ที่ไม่ส่ง = ค่าเริ่ม), เครื่อง POS ทับเฉพาะที่ส่ง (เลขเครื่องไม่เปลี่ยน)
  - `dt_pos_terminals` PK `(tenant_id, id)` (เลขเครื่องผู้ใช้กำหนด ไม่ใช่ identity); `close_check` เก็บค่าที่ `closeCheckOf()` คิดแล้วเสมอ (mock ไม่เก็บ = ค่าเริ่มของประเภท)
  - `ShopSession.terminalId` = `X-Terminal-Id` เมื่อเป็นเครื่องของบริษัท (ตรวจทุก request ใน `SessionService.load`) → audit ใช้ค่านี้; `TerminalContext` ยังเป็นค่าดิบ (POS / กะตรวจด้วย `terminalError()`)
  - `PaymentMethod` ตอบตัวเลขเป็น double (`300.0`) — JS อ่านเป็นตัวเลขเดียวกัน
  - **ยังไม่ทำ (รอตารางของขั้นถัดไป)**: productCount ของหมวด / ผู้จำหน่าย + ลบที่มี SKU อ้าง (ขั้น 5), ลบหมวดที่มีโปรอ้าง (5b), ลบช่องทางที่มีการขายอ้าง (9),
    ปิดเครื่องที่มีกะเปิด (8), ลบเครื่องที่มีกะ / เอกสาร (8–10)

### ขั้น 5 — SKU / สต็อก ✅ เสร็จ 2026-10-22
- **ตกลง 2026-10-22**: ทุนเฉลี่ย (`branch_stock.avg_cost`, `stock_movements.balance_avg_cost`) `numeric(14,4)` = ตรงกับ mock (`movingAverage()` ปัด `round4`);
  มูลค่า / COGS / เงินอื่น `numeric(14,2)` (`round2`) — แก้ `data-model.md` ของหน้าบ้านให้ตรงโค้ดแล้ว (เดิมบอก 2 ตำแหน่ง)
- migration: `products`, `product_pack_units`, `product_suppliers`, `branch_stock`, `stock_movements`, `serial_numbers`
- กฎ: `ProductRules`, `Costing` (`movingAverage`, `buildStockCard`, …), `SerialRules`, `Money` (`moneyInputError`), กฎ SKU ที่อยู่ใน mock (`validateSku`, `receiptCostError`, การลบที่ถูกอ้าง)
- endpoint: `/products` (+ `stock`, `movements`, `branch-stock`, `serials`, `serials/remove`); audit `stock_out`, `serial_remove`
- **`StockLedger`** (service ภายใน — จุดเดียวที่แก้สต็อก; ขาย / void / ใบลดหนี้ / ใบเปลี่ยน / โอน / รับเข้า / ปรับ / serial เรียกตัวนี้):
  1. `INSERT branch_stock … ON CONFLICT DO NOTHING` (ไม่มีแถว = 0) → 2. `SELECT … FOR UPDATE` → 3. คิดในแอปด้วย `Costing` (รับเข้า `movingAverage()`, ตัดออก avg คงเดิม + COGS)
  → 4. `UPDATE branch_stock` → 5. `INSERT stock_movements` พร้อมยอดคงเหลือหลังรายการ
  - ไม่ใช้ `UPDATE … SET stock = stock - :qty` ตรง ๆ เพราะทุนเฉลี่ยต้องคิดด้วยกฎที่ port มา (ตรงหน้าบ้าน) และ movement ต้องรู้ยอดหลังรายการ
  - ตรวจไม่พอหลังล็อก (400 ข้อความตาม mock) + `CHECK (stock >= 0)`; บิลหลายบรรทัดล็อก**เรียงตาม `product_id`** (กัน deadlock); pack unit แปลงเป็นหน่วยฐานก่อน
  - ล็อกหลังตรวจกฎผ่านแล้ว (คู่กับออกเลขเอกสาร) ปล่อยตอน commit
  - serial: `UPDATE serial_numbers SET status = … WHERE id = … AND status = 'in_stock' AND branch_id = …` ต้องได้ 1 แถว (0 = ถูกขายตัดหน้า → 400); `stock` / `avg_cost` ของ SKU serial = นับ / เฉลี่ย serial `in_stock` ของสาขา
  - บริการ: ไม่มีสต็อก / movement, COGS = `cost` × qty
  - test: ขายสินค้าเดียวกันพร้อมกันหลาย thread → ยอดถูก ไม่ติดลบ; 2 บิลสินค้าสลับลำดับพร้อมกัน → ไม่ deadlock; serial เดียวกันขายพร้อมกัน → สำเร็จ 1
- **ซ่อนทุน (E43 / E44)**: `CostHidingAdvice` — ทุก response ของคนที่ `canSeeCost` = false: key ใน `COST_FIELDS` → `null` ทุกระดับ + `{qty, avgCost, value}` → `value: null`; ขาเข้า SKU ไม่ใช้ `cost` / `suppliers[].cost` (คงค่าเดิม / ใหม่ = 0)
- seed: สินค้า + สต็อกยกมา (`opening`) ของ HQ / BN ตามผลของ `migrateCosting` / `migrateBranchStock` / `syncSerials` ใน mock
- test: ทุนเฉลี่ยตรงตัวอย่าง spec, สต็อกต่อสาขา, `user` ได้ทุนเป็น null ทุก endpoint ของขั้นนี้, `staffSeeCost` = true แล้วเห็น
- ผลที่ทำ (รายละเอียดที่แผนไม่ได้ระบุ):
  - package `product/` (SKU + สต็อก + serial — `ProductController` → `ProductService` → `ProductDao`), `stock/` (`StockLedger`, `StockDao`, `SerialDao`)
  - **ประวัติไม่มี FK ไป products**: `stock_movements.product_id`, `serial_numbers.product_id` (แบบเดียวกับ audit_log — ลบ SKU ที่หมดของแล้วได้ตาม mock แต่ประวัติอยู่ต่อ);
    ลบ SKU = ลบแพ็ก / ผู้จำหน่าย / แถวสต็อกต่อสาขา (เป็น 0) ก่อน
  - `dt_stock_movements` append-only (an22_app INSERT + SELECT); `StockMovement.date` = `created_at`
  - SKU serial: `branch_stock.stock` / `avg_cost` เก็บค่าที่นับ / เฉลี่ยจาก serial ในคลังทุกครั้งที่เดิน (อ่านทางเดียวกับ non-serial); ไม่เหลือ = ทุนเฉลี่ยเดิม (เหมือน mock)
  - ทุนต่อหน่วย (ทุนมาตรฐาน, ผู้จำหน่าย, serial) `numeric(14,4)`; บัตรสต็อก unit / total / value `numeric(14,2)` (`recordMovement()` ปัด round2)
  - PUT /products ทับเฉพาะที่ส่ง (mock `{ ...current, ...payload }`); POST ค่าที่ไม่ส่ง = `PRODUCT_DEFAULTS`
  - `CostHidingAdvice` (ResponseBodyAdvice) = `hideCost()` ทุก response ของผู้ใช้ร้านที่เห็นทุนไม่ได้; productCount ของหมวด / ผู้จำหน่ายนับจริงแล้ว + ลบที่มี SKU อ้างไม่ได้
  - รับ serial ที่ `in_transit` → 400 "`<serial>`: อยู่ระหว่างโอน" (ข้อความใหม่ — mock ไม่ตรวจและย้าย serial ระหว่างโอนได้ ซึ่งผิด); ใบโอนจริงในขั้น 6
  - `PUT /products/:id/stock` `delta` ไม่ใช่จำนวนเต็ม (1.5) → 400 "จำนวนไม่ถูกต้อง" (ไม่ตัดทศนิยมเงียบ)
  - test พร้อมกัน: ตัดสต็อกเดียวกัน 8 thread → ไม่ติดลบ (สำเร็จ 5 / 400 อีก 3), serial เดียวกันตัดออก 4 thread → สำเร็จ 1;
    "2 บิลสลับลำดับไม่ deadlock" ทำในขั้น 9 (ยังไม่มีเอกสารหลายบรรทัด — `StockLedger` ให้ผู้เรียกเรียงตาม productId)
  - ยังไม่ทำ: ระหว่างโอนนับเป็นสต็อก (ขั้น 6), ลบ SKU ที่มีการขาย (9) / อยู่ในโปร (5b), `currentPrice` (5b)

### ขั้น 5b — ราคาขาย + โปรโมชั่น (ย้ายจากขั้น 4 — ตกลง 2026-10-22) ✅ เสร็จ 2026-10-23
- migration: `sku_prices` (`EXCLUDE USING gist` — ต้องมี extension `btree_gist`; Azure: เปิดใน `azure.extensions` ก่อน), `promotions` (+ สาขา / ของแถม)
- กฎ: `PriceRules`, `PromotionRules` (+ `promotionBranchScopeError` 403)
- endpoint: `/prices`, `/promotions`; audit `price_change`, `promotion_change`; `Product.currentPrice` จากราคา
- เติม: ลบหมวดที่มีโปรอ้างไม่ได้ / ลบ SKU ที่อยู่ในโปรที่ยังไม่หมดอายุไม่ได้
- seed: ราคา / โปร ตาม mock
- ผลที่ทำ (รายละเอียดที่แผนไม่ได้ระบุ):
  - package `price/` (`/prices`), `promotion/` (`/promotions`); เขียนเฉพาะ admin (403 ตาม mock), อ่านได้ทุกคน; audit ระดับบริษัท (branchId null) ข้อความตาม `recordAudit()` / `deletedLabel()`
  - `btree_gist` เป็น extension แบบ trusted → `an22_owner` (ไม่ใช่ superuser) สร้างใน V5 ได้; `EXCLUDE` กันช่วงทับเป็นตาข่ายสุดท้าย — แอปตรวจ `findOverlap()` ใต้ล็อก `prices` ก่อนเพื่อข้อความไทย
  - `dt_promotions`: scope / สาขาเป็น `bigint[]`, `discount` / `free_goods` เป็น `jsonb` รูปเดียวกับ TS — **ไม่มี FK** (โปรที่หมดอายุเป็นประวัติ อ้าง SKU / หมวดที่ลบแล้วได้);
    รหัสไม่ซ้ำตรวจใต้ล็อก `promotions` + unique `(tenant_id, code)`
  - ตัวเลขของโปร (priority, minQty, qty, maxSets) เป็น `Double` ใน JSON ให้กฎ "จำนวนเต็ม" ทำงาน (Jackson ตัดทศนิยมเงียบถ้าเป็น Integer) — ส่งออกเป็น `10.0` (ค่าเท่ากันใน JS)
  - normalize ตาม mock; ต่างจาก mock: Java แยก "ไม่ส่ง" กับ `null` ไม่ได้ → ค่าที่เป็น null ใช้ค่าเริ่ม ยกเว้น `discount` (null คงเป็น null เมื่อเป็นของแถม / มี freeGoods);
    `type` / `discount.kind` ที่ไม่รู้จัก → 400 "ข้อมูลที่ส่งมาไม่ถูกต้อง" (mock รับไว้เงียบ ๆ แต่ตารางเก็บไม่ได้)
  - วันที่ของราคา / โปรต้องเป็น 'YYYY-MM-DD' ที่เป็นวันจริง (mock ไม่ตรวจของราคา) → 400 "รูปแบบวันที่ไม่ถูกต้อง" (ข้อความเดียวกับ `promotionError()`)
  - `Product.currentPrice` = ราคาของช่วงที่มีผลวันนี้ (Asia/Bangkok) คำนวณตอนอ่าน; ลบ SKU = ลบช่วงราคาของมันด้วย (mock ปล่อยค้าง)
  - ลบ SKU / หมวดที่โปรที่ยังไม่หมดอายุอ้าง (รวมโปรที่ปิดใช้งาน และของแถม) → 400 พร้อมรหัสโปรตามลำดับ id
  - ยังไม่ทำ: ส่วนคิดราคาของโปร (`discountAmount`, `inScope`, `promotionsForProduct`) → ขั้น 7 / 9

### ขั้น 6 — ใบโอนสินค้า ✅ เสร็จ 2026-10-23
- migration: `transfers`, `transfer_lines`; กฎ `TransferRules`; endpoint `/transfers` (+ `receive`, `cancel`), เลข `TR-…`; audit `transfer_cancel`
- test: โอน non-serial / serial, รับขาด (E28), ยกเลิก (E29), สิทธิ์สาขาต้นทาง / ปลายทาง
- ผลที่ทำ (รายละเอียดที่แผนไม่ได้ระบุ):
  - `db/DocNumbers` (ตัวนับ `dt_doc_counters` ตามหัวข้อ "เลขเอกสาร") ทำในขั้นนี้ — `DocNumbersIT`: 16 thread ไม่ซ้ำไม่ข้าม, rollback ไม่ข้าม, แยกบริษัท
  - V6: `dt_transfers` + `dt_transfer_lines` (PK `(tenant_id, transfer_id, position)`); an22_app ไม่มี DELETE (เอกสาร); บรรทัดเก็บ sku / name / unit ณ ตอนโอน,
    `product_id` ไม่มี FK (ประวัติ — ใบที่ยังไม่รับกันลบ SKU ในแอปแทน, E18); `createdAt` = `created_at`, ผู้ทำเก็บเป็นชื่อ ณ ตอนนั้น (`*_by_name`)
  - เลขออกหลังตรวจกฎผ่าน แต่ก่อนเดินสต็อก (หมายเหตุบัตรสต็อกมีเลขใบ); สต็อกเดินผ่าน `StockLedger` เรียงตาม productId; `StockLedger.issue` คืน `Issued(avgCost, cogs)`
  - รับ / ยกเลิก: `SELECT … FOR UPDATE` แถวใบโอนก่อนตรวจสถานะ → พร้อมกัน 4 request สำเร็จ 1 (test)
  - สร้าง / รับ / ยกเลิกต้องมี `X-Branch-Id` (400 "ไม่ได้เลือกสาขา…" — mock ใช้สาขาแรก); รายการ / อ่านไม่ส่ง = สาขาที่ทำงาน (header หรือสาขาแรก)
  - ยกเลิก: ลำดับตรวจตาม mock (404 → มองเห็น 403 → admin 403 → กฎ 400) จึงตรวจ admin ใน service ไม่ใช่ controller
  - qty / receivedQty เป็น Double ใน payload (กฎจำนวนเต็มทำงาน — แบบเดียวกับ `delta`)
  - ลบ SKU / เปลี่ยนการคุม serial / ประเภท: ไม่มีของสาขาใดแต่อยู่ในใบที่ยังไม่รับ → "ยังมีสต็อกคงเหลือ (ระหว่างโอน TR-…) …" (`stockedBranches || inTransitNote` ของ mock)
  - เพดานแถวของ `GET /transfers` ทำแล้วพร้อมหัวข้อ "จำกัดขนาดคำตอบ" (2026-10-23)

### ขั้น 7 — กฎคำนวณ POS + fixtures (pure, ไม่มี DB) ✅ เสร็จ 2026-10-23
- port `PosPricing` (`priceCart`, `cartError`, `freeSets`, `manualDiscountError`, `belowCostIssues`), `PosPayment` (`paymentSummary`, `paymentError`, `cashDueFor`), ส่วนที่เหลือของ `PaymentMethodRules` (`roundCash`, …)
- `FixturesTest` (`@TestFactory`): `price-cart.json` (293 เคส) + `payment.json` (200 เคส) เทียบทั้ง object ด้วยค่าตัวเลข + ข้อความตรงทุกตัวอักษร — **ต้องผ่านทุกเคสก่อนขั้น 9**
- path fixtures: `../FrontEnd-An22/docs/backend/fixtures` (ตั้งทับได้ด้วย system property `fixtures.dir` สำหรับ CI)
- ผลที่ทำ (รายละเอียดที่แผนไม่ได้ระบุ):
  - fixtures จริงมี **35 เคส** (`price-cart.json`) + **22 เคส** (`payment.json`) ไม่ใช่ 293 / 200 ตามที่แผนเขียน — ผ่านครบทั้ง 57 เคส
    (เทียบทั้งต้นไม้ JSON: key ครบเท่ากัน, ตัวเลขเทียบค่า, ข้อความตรงตัว); ลองแก้โค้ดให้ผิด (VAT, เศษจากการเฉลี่ย) แล้ว test แดงจริง
  - `rules/PosPricing` (`priceCart`, `cartError`, `freeSets`, `manualDiscountError`, `manualDiscountValueError` / `Baht`, `belowCostIssues`),
    `rules/PosPayment` (`paymentSummary`, `paymentError`, `cashDueFor`, `baht()` = en-US ทศนิยมไม่เกิน 2 ปัดครึ่งออกจากศูนย์);
    `PromotionRules` เพิ่ม `discountAmount` / `inScope`; `roundCash` มีใน `PaymentMethodRules` แล้วตั้งแต่ขั้น 4
  - model ของ sale ที่กฎใช้: `CartItem`, `ManualDiscount`, `FreeSerial`, `PaymentInput`, `SalePayment` (ตัวเลขขาเข้าเป็น Double ให้กฎจำนวนเต็มทำงาน);
    `PricedLine` / `PricedCart` / `CartIssue` / `PaymentSummary` เป็น record ใน class ของกฎ
  - spec ของ pos-pricing / pos-payment ไม่ได้ port ทีละข้อ เพราะ golden fixtures สร้างจากกรณีเดียวกันครบ — เพิ่ม `PosRulesTest` สำหรับตัวช่วยที่ fixtures ไม่เรียกตรง

### ขั้น 8 — กะ + ล็อก POS + บิลพัก (E48) ✅ เสร็จ 2026-10-23
- **ก่อนเริ่ม**: แก้ spec ใน `../FrontEnd-An22` เรื่องจำกัดช่วงวันที่ / เพดานแถว (หัวข้อ "จำกัดขนาดคำตอบ") — ถามข้อความ + ตัวเลขกับผู้ใช้ก่อน
- migration: `shifts` (+ `locked_at`, partial unique กะเปิด 1 กะต่อเครื่อง), `shift_counts`, `cash_movements`, `drawer_opens`, `shift_adjustments`, `held_bills` (`payload_enc bytea` — เข้ารหัส)
- กฎ: `ShiftRules` ทั้งหมด (`shiftBlocker`, `openShiftError`, `countCheck`, `closeShiftError`, `shiftSummary`, `varianceReport`, …), `PosTerminalRules.terminalError`
- endpoint: `/shifts/*` ทั้งหมด รวม `lock` / `unlock` (คนเปิดกะหรือ admin, ข้อความตาม contract), `/pos/held-bills`; เติม `requireShiftChangeError` ให้ `PUT /settings/store|company`, ปิดสาขา / เครื่องที่มีกะเปิด
- audit `shift_close_other`, `shift_approve`, `shift_adjust`; test blind count (user ไม่เห็นยอด), P6 (user เห็นเฉพาะกะตัวเอง)
- ผลที่ทำ (รายละเอียดที่แผนไม่ได้ระบุ):
  - ก่อนเริ่ม: ตกลงตัวเลข / ข้อความกับผู้ใช้ + แก้ spec หน้าบ้าน + `ListLimits` (หัวข้อ "จำกัดขนาดคำตอบ") — commit แยก
  - V7: `dt_shifts` (partial unique กะเปิด 1 ต่อเครื่อง **และ 1 ต่อคน**, `locked_at`), `dt_shift_counts` / `dt_cash_movements` / `dt_drawer_opens` /
    `dt_shift_adjustments` (append-only), `dt_held_bills` (`payload_enc` = ทั้งบิลเข้ารหัสด้วย PiiCipher, AAD `dt_held_bills.payload:<tenant>`);
    ยอดแบงก์ / เหรียญเป็น `jsonb`; `CashMovement.at` ฯลฯ = `created_at`
  - **เจ้าของกะ = `cashier_user_id`** (เทียบด้วย id ไม่ใช่ชื่อแบบ mock — เปลี่ยนชื่อผู้ใช้แล้วกะไม่หลุดมือ); `cashier` = ชื่อ snapshot สำหรับแสดง / รายงาน
  - `rules/ShiftRules` port ทั้งไฟล์ (ยกเว้น normalizeShift / shiftDay / shiftOpenTooLong ที่เป็นของหน้าจอ) + test ตาม spec เกือบทุกข้อ;
    `Money.enNumber()` = `toLocaleString('en-US', max 2)` ใช้ร่วมกับ `PosPayment.baht()`
  - ยอดระบบตอนนี้ = เงินทอนตั้งต้น + เงินเข้าออก — การขาย / void / ใบลดหนี้ / ใบเปลี่ยนเติมใน `ShiftService.context()` ขั้น 9 / 10
  - เลขกะ `SH-<สาขาของเครื่อง>-YYYYMMDD-NN` (DocNumbers); `branch_id` ของกะ = สาขาของเครื่อง (E24)
  - ปลดล็อก / ผู้อนุมัติตรวจรหัสผ่าน `AuthService.credentials()` (นับใน LoginThrottle เหมือน auth/verify — ล็อกอยู่ → 429)
  - เปิดกะ / ปิดระบบกะ / ปิดสาขา-เครื่องที่มีกะเปิด ใช้ล็อก `TenantLocks.SHIFTS` (ถือหลัง ACCOUNTS เสมอ); ลบเครื่องที่มีกะ → 400
  - `GET /shifts/current` ไม่มีกะ = body ว่าง (HttpClient อ่านเป็น null — เหมือน endpoint อื่นที่ mock ตอบ `null`)
  - count-check ไม่บันทึกอะไร (เหมือน mock — contract แนะนำให้ log การนับครั้งแรก: ยังไม่ทำ)
  - ยังไม่ทำ: ขายระหว่างกะถูกล็อก → 400 "หน้าจอถูกล็อก — ปลดล็อกก่อน" และ `shiftBlocker` ของการขาย / void / คืน (ขั้น 9 / 10)

### ขั้น 9 — ขาย + ใบกำกับภาษี ✅ เสร็จ 2026-10-23
- **ก่อนเริ่ม**: แก้ spec ใน `../FrontEnd-An22` เรื่องห้ามเลขบัตรเต็มใน `reference` (หัวข้อ "ข้อมูลส่วนบุคคล") — ถามข้อความ error กับผู้ใช้ก่อน
- migration: `sales`, `sale_lines`, `sale_payments`, `tax_invoices` (`buyer_enc` + `buyer_tax_id_hash`)
- `POST /sales` ตามลำดับตรวจ 1–8 ใน `api-contract.md` → ตัดสต็อกพร้อม COGS (`issueStock`), เลข `POS-<สาขา>-<เครื่อง>-YYYYMMDD-NNNN`, snapshot; ขายระหว่างกะถูกล็อก → 400
- `POST /sales/cost-check` (E45), `GET /sales` (+ `?branchId=` ตาม `branchScopeError`), `GET /sales/:id`, `void` (approval ของ admin), `PUT /sales/:id/status`
- ใบกำกับ: `POST /sales/:id/tax-invoice`, `reissue`, `GET …/tax-invoice(s)`, `GET /tax-invoices`, `GET /tax-invoices/buyer` — กฎ `TaxInvoiceRules`, `SaleRules`
- audit `manual_discount`, `sale_void`, `tax_invoice_reissue`; seed บิลตัวอย่างตาม `SEED_SALES`
- test: ลำดับข้อความ error ตรง mock, ขายพร้อมกัน 2 thread เลขไม่ซ้ำ, COGS + movement, ทุนเป็น null สำหรับ user
- ผลที่ทำ (รายละเอียดที่แผนไม่ได้ระบุ):
  - ก่อนเริ่ม: ข้อความเลขบัตรเต็มตกลงแล้ว → `isFullCardNumber()` ใน `paymentError()` (FrontEnd-An22 `59e52fe` branch `docs/card-number-reference`
    ยังไม่ push: model + spec + fixtures payment.json +2 เคส + api-contract) — api port ตามแล้ว (fixtures 59 เคสผ่าน)
  - V8: `dt_sales` (+ `dt_sale_lines` / `dt_sale_payments` append-only, product_id / method_id ไม่มี FK), `dt_tax_invoices`
    (`buyer_enc` PiiCipher AAD `dt_tax_invoices.buyer:<tenant>`, `buyer_tax_id_hash` = `hashTaxId`, partial unique 1 ใบที่ใช้ได้ต่อบิล); seed `SEED_SALES` 7 ออร์เดอร์ก่อน POS
  - `POST /sales` ลำดับ 1–8 ตาม contract + ล็อกหน้าจอ (หลังข้อ 1) — ตะกร้าคิดด้วย SKU ที่ตะกร้า / ของแถมของโปรอ้างเท่านั้น (ไม่โหลดทั้งร้าน);
    ตัดสต็อกผ่าน `StockLedger` เรียงตาม productId, serial ตัดด้วย `takeOut(… 'sold')` (ถูกขายตัดหน้า → 400); เลข `POS-<สาขา>-<เครื่อง>-YYYYMMDD-NNNN`
  - void: ไม่ใช่ admin ไม่มีผู้อนุมัติ → 403, รหัสผิด → 400 (นับใน LoginThrottle); คืนสต็อกที่สาขาที่ขาย (serial `returnSold`, อื่นที่ COGS / หน่วย)
  - ใบกำกับ: `TaxInvoiceService.create()` ใช้ทั้งตอนขายและออกทีหลัง; ออกใหม่ = ยกเลิกใบเดิม (เหตุผล + replacedByNo) ก่อน insert ใบใหม่
  - `GET /sales/:id/tax-invoice` ไม่มีใบ / `GET /tax-invoices/buyer` ไม่พบ = body ว่าง (null)
  - ยอดระบบของกะรวมบิลที่ขาย / void ในกะแล้ว (`SaleDao.shiftRefs`); ลบ SKU ที่เคยขาย / ช่องทางที่ใช้แล้ว / เครื่องที่มีบิล → 400
  - returnStatus / creditedTotal / creditNoteNos / exchangeNos ยังตอบค่าว่าง และ `currentSerial()` ยังไม่ดูใบเปลี่ยน — ขั้น 10

### ขั้น 10 — ใบลดหนี้ + ใบเปลี่ยน ✅ เสร็จ 2026-10-23
- migration: `credit_notes` (`buyer_enc` เหมือนใบกำกับ; + lines / deductions / refunds), `exchanges` (+ lines); กฎ `CreditNoteRules` (`draftCreditNote`, …), `ExchangeRules`
- endpoint: `/sales/:id/credit-notes`, `/credit-notes`, `/sales/:id/exchanges`; field derived ของ `Sale` (`returnStatus`, `creditedTotal`, `creditNoteNos`, `exchangeNos`); audit `credit_note`
- **ถามผู้ใช้**: ควรขอให้หน้าบ้านเพิ่ม `credit-note.json` ใน fixtures ก่อน (README ของ fixtures แนะนำไว้) หรือไม่
- ผลที่ทำ (รายละเอียดที่แผนไม่ได้ระบุ):
  - ตอบแล้ว: เพิ่ม fixtures ก่อน + (ถามเพิ่ม) ตรวจเลขบัตรเต็มใน reference ของการคืนเงินด้วย → FrontEnd-An22 `cdb0be7` branch `docs/credit-note-fixtures`
    (ยังไม่ push): `credit-note.json` 27 เคส (draft / error / refundOptions / toRefunds / returnSummary), `creditNoteError()` + spec, README fixtures
  - FixturesTest ผ่านครบ 86 เคส (price-cart 35, payment 24, credit-note 27) ตั้งแต่รอบแรก
  - V9: `dt_credit_notes` (+ lines / deductions / refunds) และ `dt_exchanges` (+ lines) — append-only ทั้งใบ; ผู้ซื้อของใบลดหนี้ = ของใบกำกับตอนออก
    เข้ารหัส (AAD `dt_credit_notes.buyer:<tenant>`)
  - `rules/CreditNoteRules`, `rules/ExchangeRules`, `SaleRules.currentSerial`; `sale/SaleStock` = การเดินสต็อกต่อบรรทัดของ บิล / void / ใบลดหนี้ /
    ใบเปลี่ยน (ขาย, คืนเข้าคลังที่ COGS, ตัดเสีย serial)
  - field derived ของ Sale (returnStatus / creditedTotal / creditNoteNos / exchangeNos) คำนวณใน `SaleDao` ทุกครั้งที่อ่าน; void คืน serial ที่ลูกค้าถืออยู่
  - ยอดระบบของกะรวมการคืนเงินของใบลดหนี้ + นับใบเปลี่ยน; ลบเครื่องที่มีใบลดหนี้ / ใบเปลี่ยน → 400
  - ใบเปลี่ยนไม่มี audit (เหมือน mock)

### ขั้น 11 — ส่วน SaaS ของบริษัท ✅ เสร็จ 2026-10-23
- `GET /audit-log` (`AuditRules.filterAuditEvents`, `parseAuditQuery`), `GET /company/onboarding`, `GET /company/subscription`, `PUT /company/plan`
- billing: job รายวัน + `catchUp()` ตอน login / `me` (หัวข้อ "Stateless / scale") ออกใบแจ้งหนี้ `SUB-YYYYMM-NNNN` + ระงับเมื่อพ้นผ่อนผัน; `actorKind: 'system'`
  — test: job 2 ตัวพร้อมกันออกใบเดียว, login ก่อน job ได้ใบแล้ว job ไม่ออกซ้ำ
- `POST /auth/signup` (ใช้ role platform สร้างบริษัท; audit `signup`), `POST /auth/forgot-password` (ตอบข้อความเดียวเสมอ, token เก็บ hash, ลิงก์จาก `APP_BASE_URL` ไม่ใช่ `Host`), `POST /auth/reset-password` (audit `password_reset`)
- `GET /company/export` (ไม่มีรหัสผ่าน / token; ข้อมูลผู้ซื้อถอดรหัสแล้ว), `GET|POST|DELETE /company/deletion`; ระหว่างรอลบ: เฉพาะ owner, endpoint อื่น → 403
- test: billing ตาม `subscription.model.spec.ts` ด้วย `Clock` ปลอม, ลิงก์ไม่ใช้ header `Host`
- ผลที่ทำ (รายละเอียดที่แผนไม่ได้ระบุ):
  - env ใหม่: `APP_BASE_URL` (บังคับ, `http(s)://…` — ต้นลิงก์ในอีเมล), `JOBS_ENABLED` (ค่าเริ่ม `true`; test ปิด job แล้วเรียกเอง)
  - `billing/`: `BillingService.catchUp` (platformTx `REQUIRES_NEW`, ล็อกแถวบริษัท FOR UPDATE → ออกทุกรอบที่ถึง ราคาแพ็กเกจ ณ วันออก, ครบกำหนด +7 วัน → ระงับ `overdue`
    เมื่อพ้นผ่อนผัน), `runDaily` (job 00:05 Asia/Bangkok, `pg_try_advisory_xact_lock` + ล้าง reset token / ตัวนับ login ผิดที่หมดอายุเกิน 1 วัน), `BillingJob`;
    login / `me` เรียก `catchUp` ก่อนตรวจสถานะ (ถูกระงับระหว่างนั้น → login ได้ข้อความระงับ, `me` → 401)
  - `mail/Mailer` + `LoggingMailer` (ยังไม่ส่งจริง — log ผู้รับแบบ mask + หัวเรื่องเท่านั้น ไม่ log ลิงก์ / token); test ใช้ `support/TestMailer` (`@Primary`)
  - `GET /audit-log` (`audit/AuditLogController` → `AuditLogService` → `AuditDao.list`): ช่วงวันที่บังคับ ≤ 366 วัน (เหมือน mock `dateRangeError`), วันท้องถิ่นของ `at`,
    admin สาขาเห็นเฉพาะเหตุการณ์ที่ `branch_id` อยู่ใน `branchIds` ของตัวเอง (รวมสาขาที่ปิดแล้ว — `canWorkIn` ของ mock ส่ง `active: true`), เพดาน 20,000 แถว
  - `/company/subscription` · `/company/onboarding` (นับทุก record รวมที่ปิด เหมือน mock; prices = `dt_sku_prices`) · `PUT /company/plan` (platformTx, ลดได้แม้ใช้เกิน)
  - `POST /auth/signup` (platformTx, `auth/AccountService` + `AccountDao`): บริษัท (trial = วันนี้ + 14, `nextInvoiceOn` = trial + 1) → owner (stamp `system`)
    → company_info (ค่าเริ่มของ column = `COMPANY_INFO_DEFAULTS`) / สาขาแรก / ช่องทาง `CASH` (stamp owner) → audit `signup` → `LoginResponse`;
    รหัสซ้ำ (รวมบริษัทที่ลบแล้ว / สมัครพร้อมกัน — `ON CONFLICT (code)`) → 400 `รหัสบริษัทนี้ถูกใช้แล้ว`
  - `POST /auth/forgot-password`: token สุ่ม 32 byte (base64url) เก็บ `PiiCipher.hmac(token)` อายุ 60 นาที, ส่งเฉพาะ owner ที่ใช้งานอยู่ + อีเมลตรง (ไม่สนตัวพิมพ์);
    `POST /auth/reset-password`: ล็อกแถว token (ใช้ได้ครั้งเดียวแม้กดพร้อมกัน) → บริษัทยังไม่ถูกลบ + ผู้ใช้ยังใช้งาน → `passwordError` → audit `password_reset`
  - `GET /company/export`: **ทุกตารางที่มี `tenant_id`** ที่ an22_app อ่านได้ (ใต้ RLS) + `dt_billing_invoices` ยกเว้น `dt_doc_counters` — ชื่อ collection = ชื่อตารางไม่มี `dt_`
    แบบ camelCase (`users`, `heldBills`, `auditLog` …), field = column แบบ camelCase (`to_jsonb`), ไม่มี `tenant_id` / `*_hash` (รหัสผ่าน, hash เลขภาษี),
    `*_enc` ถอดรหัสเป็น JSON ในชื่อไม่มี `_enc` (`heldBills[].payload`, `taxInvoices[].buyer`); `statement_timeout` 120 วิ (SET LOCAL); ประกอบใน memory แล้วตอบ
    (ไม่เขียน disk) — บริษัทใหญ่มากค่อยทำเป็น job ทีหลัง
  - `/company/deletion`: GET (ไม่มี = body ว่าง), POST (ล็อกแถวบริษัท, ระงับ `deletion` แทนการระงับเดิม, `purgeAfter` = วันนี้ + 30), DELETE (กลับมาใช้งาน แล้ว
    `catchUpIn` ตัดสิน billing ซ้ำใน transaction เดียวกัน); endpoint ที่แก้ตาราง platform ใช้ `Audit.recordOnPlatform`
  - **จำกัดครั้ง signup / forgot-password** (เสนอแล้วผู้ใช้ให้ทำ 2026-10-23 — ไม่มีใน mock): ใช้ `LoginThrottle` เดิม (ตาราง `dt_login_throttle`, หน้าต่าง 15 นาที
    → ล็อก 15 นาที, 429 ข้อความเดียวกับ login) แต่**นับทุกครั้ง**ไม่ว่าผล (`LoginThrottle.count`, REQUIRES_NEW): signup ต่อ IP 10 ครั้ง (กันสร้างบริษัทรัว /
    ไล่เดารหัสบริษัท), forgot-password ต่อ IP 10 + ต่อรหัสบริษัท + อีเมลที่พิมพ์ 3 ครั้ง (กันยิงอีเมลใส่ owner — นับแม้ไม่มีจริง จึงไม่บอกว่ามีหรือไม่);
    key แยกจากของ login (`signup:` / `forgot:`); reset-password ไม่จำกัด (token สุ่ม 256 บิต)

### ขั้น 12 — platform admin
- endpoint: `/platform/companies` (+ `:id`, `plan`, `suspend`, `reactivate`, `owner-password`, `purge`), `/platform/invoices/:id/pay`; กฎ `PlatformRules`, `CompanyDataRules.purgeError`
- ทุกการกระทำลง `audit_log` ของบริษัทนั้น `actorKind: 'platform'`; purge ลบทุกตารางของ tenant (เหลือแถว `companies` + `deleted_at`)
- `GET /platform/outbox` ไม่ทำ (mock เท่านั้น) — **บอกผู้ใช้** ว่าหน้า platform จะได้ 404 ตรงนี้ เผื่อต้องแก้หน้าบ้าน
- test: token platform เห็นทุกบริษัท, token ร้าน → 401, purge แล้ว login / token เดิม → 401

### ขั้น 13 — ปิดงาน / พร้อม C3
- test กวาดข้าม tenant ทุก endpoint (ตาราง endpoint → อ่าน / เขียน id ของบริษัท B ด้วย token A → 404)
- **test หลาย instance**: compose เปิด api 2 ตัวหลัง load balancer ง่าย ๆ → login ตัวหนึ่ง ใช้ token กับอีกตัว, ขายพร้อมกันจาก 2 ตัวเลขบิลไม่ซ้ำ, job billing ทำงานครั้งเดียว
- ~~`Dockerfile` (image ของ api) + `compose.yaml` profile รวม postgres + api~~ ทำแล้วในขั้น 0 (2026-10-22) — เหลือ: an22-web จาก image ของมันในตัว compose (ไม่แก้ repo web)
- ทดลองกับหน้าบ้านจริง: build หน้าบ้านด้วย `useMock: false` ชั่วคราว (ไม่ commit ในหน้าบ้าน) ผ่าน an22-web → login DEMO, ขาย, ปิดกะ
- อัปเดต `../FrontEnd-An22/docs/backend/README.md` หัวข้อ api ("ทำแล้ว C2 …") + `next-steps-plan.md` — บอกผู้ใช้ก่อน
- C3 (สลับหน้าบ้านถาวร) เป็นงานแยก ไม่อยู่ในแผนนี้

## เรื่องที่ต้องถามผู้ใช้ระหว่างทาง (ไม่เดา)

1. ~~ขั้น 2: จำกัด login ผิดต่อบัญชี / IP~~ — ตอบแล้ว 2026-10-22 (ขั้น 2)
2. ~~ขั้น 5: ทุนเฉลี่ย~~ — ตอบแล้ว 2026-10-22: `numeric(14,4)`
3. ~~ขั้น 10: เพิ่ม fixtures `credit-note.json` ฝั่งหน้าบ้านก่อนหรือไม่~~ — ตอบแล้ว 2026-10-23: เพิ่มก่อน (golden-fixtures.ts ของหน้าบ้าน)
4. ขั้น 12: `GET /platform/outbox` ไม่มีใน api จริง — หน้า platform ต้องซ่อนเมนูนี้เมื่อไม่ใช่ mock หรือไม่
5. ~~ขั้น 9: ข้อความ error เมื่อ `reference` เป็นเลขบัตรเต็ม~~ — ตอบแล้ว 2026-10-23: "<ช่องทาง>: ห้ามกรอกเลขบัตรเต็ม — กรอกเฉพาะเลขอนุมัติหรือเลขบัตร 4 หลักท้าย" ใน `paymentError()` (หน้าบ้าน + server ใช้กฎเดียวกัน, เพิ่มเคสใน fixtures)
6. ก่อนขั้น 8: ข้อความ error + ตัวเลข (ช่วงวันที่สูงสุด, `MAX_LIST_ROWS`, `statement_timeout`) ของการจำกัดขนาดคำตอบ (แก้ spec หน้าบ้านก่อน)
7. ระยะเก็บเอกสารภาษีก่อน purge (E39) — ยังรอนักบัญชี: purge ตามเดิมไปก่อน

## วิธีตรวจ (ทุกขั้น)

```sh
./mvnw verify                          # unit + integration (Testcontainers เปิด postgres:18 ว่างเอง)
scripts/db-apply.sh --roles --seed      # รัน script DB ด้วยมือ (ทางที่ 2) บน DB ตาม env
docker compose up -d && ./mvnw spring-boot:run -Dspring-boot.run.profiles=dev   # dev DB (postgres + pgbouncer) + seed
docker compose down -v                 # รีเซ็ต DB local
curl -s localhost:8080/api/healthz
```
