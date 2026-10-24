# CLAUDE.md — an22-api

API จริงของระบบ An22 (แทน mock ของหน้าบ้าน) + **เจ้าของ schema PostgreSQL** (Flyway) — Spring Boot ต่อ PostgreSQL ผ่าน PgBouncer
ระบบมี 3 repo (E47): **FrontEnd-An22** (spec + หน้าบ้าน), **an22-web** (BFF ถือ session, proxy `/api`), **an22-api** (repo นี้)

**อ่านก่อนทำงาน**: แผน `docs/plan-step.md` (ข้อตกลงทั้งหมด + สถานะทีละขั้น) และ spec `../FrontEnd-An22/docs/backend/README.md` (หัวข้อ api),
`api-contract.md`, `domain-rules.md`, `data-model.md`, `fixtures/README.md`, `../FrontEnd-An22/docs/decisions.md`

- spec ไม่ชัด → ถือพฤติกรรม mock (`../FrontEnd-An22/src/app/core/interceptors/mock-backend.interceptor.ts` + spec) เป็นคำตอบ
- spec ต้องแก้ → **บอกผู้ใช้ก่อน** แล้วแก้ docs + mock ใน `../FrontEnd-An22` commit เดียว; **ห้ามแก้ `../an22-web`** (จำเป็น → บอกผู้ใช้ก่อน); ห้ามแตะ `noom-test.txt`
- เปลี่ยนเครื่องมือ / การออกแบบที่ตกลงในแผน → ถามผู้ใช้ก่อน แล้วแก้ `docs/plan-step.md`
- เขียนเอกสาร / ข้อความถึงผู้ใช้เป็นภาษาไทย

## เวอร์ชัน

Java 25 (Temurin) · Spring Boot 4.1.1 (Spring Framework 7, **Jackson 3** = package `tools.jackson.*`, Flyway 12.4, Testcontainers 2.0) · Maven 3.9 ผ่าน `./mvnw`
PostgreSQL 18 (`postgres:18.6`) · PgBouncer 1.25.2 (`edoburu/pgbouncer:v1.25.2-p0` = เวอร์ชันเดียวกับ Azure) · production = **Azure Database for PostgreSQL Flexible Server** + PgBouncer ในตัว

## คำสั่ง

```sh
./mvnw verify                      # unit (*Test, surefire) + integration (*IT, failsafe) — Testcontainers เปิด postgres + pgbouncer ใหม่ทุกรอบ (ต้องมี docker)
./mvnw test                        # unit อย่างเดียว (ไม่ต้องมี docker)
cp .env.example .env               # ใส่ค่า (openssl rand -base64 32) — .env ไม่ commit
docker compose up -d               # dev DB: postgres (5432) + pgbouncer (6432) — initdb สร้าง role ครั้งแรก
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev   # profile dev อ่าน .env เอง, migrate + seed, LOG_BODY=all ถ้าไม่ตั้ง
curl -s localhost:8080/api/healthz # {"status":"ok"}; /api/readyz ping DB
docker compose down -v             # รีเซ็ต DB local
scripts/db-apply.sh [--roles] [--seed]   # รัน script DB ด้วยมือ (ทางที่ 2 / DBA) — db/README.md
docker build -t an22-api:$(git rev-parse --short HEAD) .   # image production
docker compose --profile api up -d --build                  # ลอง image กับ dev DB (port 8080; ไม่มี seed ใน image)
scripts/multi-instance-check.sh    # api 2 ตัวหลัง lb (compose.multi.yaml, project an22-multi แยกจาก dev): token ข้าม instance, ขายพร้อมกัน, job ครั้งเดียว — KEEP=1 เก็บ stack
scripts/e2e/reset.sh && scripts/e2e/NN-*.sh   # E2E ทีละข้อตาม docs/e2e-test-plan.md (stack an22-multi, curl + ตรวจ DB, secrets ใน .e2e/ ไม่ commit)
```

## Container (production)

`Dockerfile` 2 stage: build ด้วย `eclipse-temurin:25.0.4.1_1-jdk-noble` (`-DskipTests` — test อยู่ที่ `./mvnw verify`) → runtime `…-jre-noble`, layer ของ Boot (`jarmode=tools extract`)
- user `an22` (uid 10001) ไม่ใช่ root; `java` เป็น PID 1 (exec form) → SIGTERM = drain (`/readyz` 503 แต่ยังรับงาน `SHUTDOWN_DRAIN` วิ, ค่าเริ่ม 10 — `ShutdownDrain`, B7) แล้ว graceful shutdown
  (รอ request ≤ 30 วิ) — ตั้ง grace period ของ platform ≥ drain + 40 วิ (= 50 วิ; compose `stop_grace_period: 50s`) และ readiness probe ที่ `/readyz` (ไม่มี probe = drain ไม่ช่วย)
- image มีแค่ `db/migration` (ไม่มี `db/seed`, `.env`, test) — `.dockerignore` แบบ allowlist; ค่าทั้งหมดจาก env ตอนรัน
- ไม่มี `HEALTHCHECK` (ไม่มี curl ใน image) — ตั้ง probe ที่ platform: liveness `GET /api/healthz`, readiness `GET /api/readyz`
- `JAVA_TOOL_OPTIONS` = `-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError` + timezone

## โครง

```
db/              roles.sql, migration/ (V<n>__*.sql + afterMigrate.sql), seed/ (DEMO dev / test เท่านั้น), README.md (ติดตั้ง / Azure / กุญแจ)
docker/          initdb.sh (สร้าง azure_admin จำลอง server admin ของ Azure → roles.sql), pgbouncer/ (pgbouncer.ini, start.sh) — compose + Testcontainers ใช้ร่วม
scripts/         db-apply.sh
src/main/java/com/an22/api/
  config/        ApiProperties (env ทั้งหมด + check()), ClockConfig (Asia/Bangkok), DataSourceConfig (app / platform, appTx / platformTx, JdbcClient), FlywayConfig
  db/            TxActor (ผู้ทำของ request — ThreadLocal), TenantTransactionManager (ตั้ง app.* ทุก transaction), TxGuardDataSource, ActorDao,
                 TenantLocks (pg_advisory_xact_lock ต่อบริษัท — กฎที่นับทั้งบริษัท), DocNumbers (เลขเอกสาร dt_doc_counters — ออกหลังตรวจกฎผ่าน)
  security/      PiiCipher (+ PiiConfig), JwtService, SecurityConfig (3 chain), ShopSessionFilter, PlatformSessionFilter
  web/           ApiException, ErrorResponse (ข้อความกลาง), ErrorHandler, FallbackErrorController (/error), RequestLogFilter,
                 ClientIp (X-Real-IP), BranchContext, TerminalContext, RequestContextResolvers
  model/         record ของ JSON = interface TS (User, Branch, Company, Session, LoginResponse, BillingInvoice …)
  rules/         กฎที่ port จาก core/models — pure Java ไม่ผูก Spring, 1 class ต่อ 1 ไฟล์ TS (UserRules, CompanyRules, StoreInfoRules, MenuAccessRules,
                 PlanRules, CostVisibility, SubscriptionRules, CategoryRules, Suppliers, PaymentMethodRules, PosTerminalRules, Money, ProductRules,
                 Costing, SerialRules, PriceRules, PromotionRules, TransferRules, PosPricing, PosPayment — ผลตรง golden fixtures ผ่าน FixturesTest,
                 ListLimits, ShiftRules, SaleRules, TaxInvoiceRules, CreditNoteRules, ExchangeRules, AuditRules, SignupRules, CompanyDataRules, PlatformRules)
  health/        /healthz, /readyz, ShutdownDrain (SmartLifecycle ก่อน graceful — B7) (HealthService ใช้ TransactionTemplate ใน try — `@Transactional` ขอ connection ก่อนเข้า method จับ error ไม่ได้ → 500, B1)
  auth/          login / verify / me (AuthController → AuthService → ShopLoginTx / SessionService → SessionDao), LoginThrottle (+ Dao), ShopSession, PasswordResetTokens,
                 signup / forgot-password / reset-password (AccountService → AccountTx → AccountDao, platformTx)
  platform/      /platform/auth/login, /platform/me, /platform/companies/* + /platform/invoices/:id/pay (PlatformCompanyService → PlatformCompanyDao,
                 audit actor platform; purge ลบทุกตารางที่มี tenant_id ตาม FK — ไม่มี /platform/outbox)
  branch/        /branches
  masterdata/    /categories, /suppliers, /payment-methods (+ order)
  product/       /products (+ stock, movements, branch-stock, serials) — ProductService
  price/         /prices (ช่วงห้ามทับ — EXCLUDE + findOverlap ใต้ล็อก), PriceDao.current() = Product.currentPrice
  promotion/     /promotions (scope / สาขาเป็น bigint[], ส่วนลด / ของแถมเป็น jsonb ไม่มี FK), codesUsingProduct / codesUsingCategory (กันลบ)
  transfer/      /transfers (+ receive, cancel) — เลข TR-<สาขา>-YYYYMMDD-NNNN, ล็อกแถวใบโอนก่อนรับ / ยกเลิก
  stock/         StockLedger (จุดเดียวที่แก้สต็อก), StockDao (branch_stock + stock_movements), SerialDao
  pos/           /pos-terminals, /pos/held-bills (เข้ารหัสทั้งบิล)
  sale/          /sales (+ void, status, cost-check), ใบกำกับเต็มรูป + /tax-invoices — SaleService (ลำดับตรวจ 1–8), TaxInvoiceService.create()
                 ใบลดหนี้ (/credit-notes) + ใบเปลี่ยน — CreditNoteService / ExchangeService; SaleStock = เดินสต็อกต่อบรรทัดของเอกสารขาย
  shift/         /shifts/* (เจ้าของกะ = cashier_user_id; ยอดระบบจาก ShiftService.context()), ล็อกหน้าจอ E48
  company/       /settings/* (SettingsController → SettingsService → CompanyInfoDao / MenuAccessDao), /company/owner, PlanLimits,
                 /company/subscription|plan|onboarding (SubscriptionService), /company/export|deletion (CompanyDataService → CompanyExportDao / CompanyAccountDao)
  billing/       BillingService.catchUp (login / me / job — ออกใบ SUB- + ระงับ overdue), BillingJob (@Scheduled 00:05, ปิดได้ด้วย JOBS_ENABLED=false)
  mail/          Mailer (LoggingMailer = ยังไม่ส่งจริง, ไม่ log เนื้อหา)
  user/          /users (+ password); UserDao.SELECT / row ใช้ร่วมกับ SessionDao
  audit/         Audit (บันทึกเหตุการณ์ของผู้ใช้ร้านใน transaction ของ service — `recordOnPlatform` เมื่อ service เป็น platformTx), AuditDao, AuditEntry,
                 GET /audit-log (AuditLogController → AuditLogService)
  <feature>/     XxxController → XxxService → XxxDao
src/test/java/com/an22/api/
  support/       TestDatabase (singleton container), ApiTest (base: @SpringBootTest + MockMvc, get/post/delete ใส่ /api ให้, `login()` → token, `bearer()`),
                 ProbeController + ProbeService (endpoint ของ test — `/test-probe/session` ผ่าน ShopSessionFilter ครบ), RolesSqlIT,
                 TestCompanies (บริษัทใหม่ต่อ test: owner / boss / staff / gone รหัส 1234, สาขา HQ / BN / OLD — เปลี่ยนสถานะได้ไม่กระทบ DEMO),
                 TenantSql (รัน SQL เป็น an22_app ในนามบริษัท / ผู้ใช้ — `as` / `rolledBack` / `withoutTenant` / `platform`), TenantB (บริษัทที่ 2 สำหรับ test ข้าม tenant),
                 TestMailer (`@Primary` Mailer เก็บอีเมลที่ส่งให้ test อ่าน)
  db/            SchemaConventionsIT, RlsIT, RowStampIT, SeedIT
  web/           CrossTenantSweepIT (ทุก route ที่มี {id} ด้วย id ของบริษัทอื่น → 404 — route ใหม่ต้องเพิ่มในตาราง ROUTES)
```

## 3 ชั้น + transaction (บังคับ)

| ชั้น | ทำ | ห้าม |
| --- | --- | --- |
| controller | HTTP, header / path / body, ตรวจ role, เรียก service **1 method ต่อ request**, คืน DTO | SQL, `@Transactional`, กฎธุรกิจ |
| service | `@Transactional` (1 method = 1 use case = 1 transaction), เรียงตรวจกฎ (`rules/`), เรียก dao, audit, เลขเอกสาร | SQL, ของ HTTP |
| dao | SQL ผ่าน `JdbcClient`, map แถว | กฎธุรกิจ, `@Transactional` |

- tx manager: `appTx` (ค่าเริ่ม, role `an22_app` ใต้ RLS) / `platformTx` (`@Transactional("platformTx")`, `an22_platform` BYPASSRLS); JdbcClient: `appJdbc` (ค่าเริ่ม) / `platformJdbc`
- GET ใช้ `@Transactional(readOnly = true)` → PostgreSQL `READ ONLY` (enforceReadOnly เปิดแล้ว); ห้ามเรียก `@Transactional` method ใน class เดียวกัน; งานที่ต้องบันทึกแม้ request ล้ม = `REQUIRES_NEW`
- **ห้ามเรียก `REQUIRES_NEW` บน tx manager เดียวกันจากใน transaction ที่เปิดอยู่** (1 request ใช้ 2 connection จาก pool เดียวกัน → พร้อมกันเท่าขนาด pool = pool ติดตาย 30 วิ — B5):
  endpoint ที่นับ `LoginThrottle` / `BillingService.catchUp` (platformTx) ใช้ method ที่ไม่ `@Transactional` คุมลำดับ แล้วเรียก transaction สั้น ๆ ต่อกัน
  (`AuthService.login` → `ShopLoginTx`, `PlatformAuthService.login` → `PlatformLoginTx`, `AccountService.signup/forgotPassword` → `AccountTx`); ข้าม pool (appTx → platform) ได้ — test `ThrottleConcurrencyIT`
- ขั้น 2: `TenantTransactionManager` ตั้ง `app.tenant_id` / `app.user_id` / `app.actor_kind` (SET LOCAL) ทุก transaction, TxGuard (query นอก transaction → error)

## Request ของร้าน / platform (ขั้น 2)

- 3 chain: public (`/healthz`, `/readyz`, `POST /auth/login`, `POST /platform/auth/login`) · `/platform/**` (token aud `platform`) · ที่เหลือ = ร้าน (aud `shop`)
  — endpoint public ใหม่ (signup, ลืมรหัสผ่าน) ต้องเพิ่มใน `SecurityConfig.PUBLIC_PATHS`
- `ShopSessionFilter` อ่านผู้ใช้ + บริษัทจาก DB ทุก request (ไม่ผ่าน → 401), ตั้ง `TxActor` (ทุก transaction ของ request ได้ RLS ของบริษัทนี้), ตรวจรอลบ (403) / `X-Branch-Id` (403)
  — controller รับ `ShopSession`, `BranchContext`, `TerminalContext` (หรือ `PlatformAdmin`) เป็น parameter; งานเขียนสต็อก / ขาย / กะเรียก `branch.require()` (400)
- endpoint ของร้านที่แก้ตาราง platform (แพ็กเกจ, คำขอลบ) = `@Transactional("platformTx")` + ระบุบริษัทจาก `ShopSession` เอง (BYPASSRLS)
- tenant มาจาก token เท่านั้น — ห้ามรับ tenant จาก URL / query / body; สิทธิ์ / สาขาใช้ของ `ShopSession` (DB) ไม่ใช่ claims
- งานที่ต้องบันทึกแม้ request ล้ม (นับ login ผิด, audit `login_failed`) = service แยก `REQUIRES_NEW` (`LoginThrottle`) — เรียกนอก platformTx (ดูกติกา B5 ข้างบน)
- signup ต่อ IP 10 / forgot-password ต่อ IP 10 + ต่อบริษัท+อีเมล 3 ครั้งใน 15 นาที (นับทุกครั้ง atomic — `LoginThrottle.count`) → 429
- จำกัด login ผิด: บัญชี 5 / IP 30 ครั้งใน 15 นาที → ล็อก 15 นาที, 429 — endpoint ตรวจรหัสใหม่ใช้ `LoginThrottle.attempt()` (จองสิทธิ์ก่อนตรวจรหัส — atomic, B6)
  → ผิด `failed()` (สิทธิ์ที่จองนับเป็นความผิด) / ไม่ผิด `passed()` (คืนสิทธิ์; login สำเร็จล้างตัวนับบัญชี) ใน `finally` — ห้าม check แล้วค่อยนับ;
  สิทธิ์ที่จองระหว่างตรวจรหัสนับรวมด้วย (บัญชีเดียว login พร้อมกัน > 5 ได้ 429 ชั่วคราว) — test `ThrottleConcurrencyIT`
- รหัสผ่าน BCrypt (`PasswordEncoder` bean); ไม่มีผู้ใช้ก็ยังตรวจกับ hash หลอก (เวลาตอบไม่บอกว่ามีชื่อนี้)

## Endpoint ของร้าน — รูปแบบ (ขั้น 3 เป็นต้นไป)

- controller: `Permissions.requireAdmin(session, "<ข้อความ 403 ของ mock>")` / `requireCompanyAdmin(session)` ก่อนเรียก service; ลำดับตรวจตาม mock
  (`adminOnlyError()` → 404 → scope 403 → กฎ 400 → แพ็กเกจ 403)
- service: เขียน → `@Transactional`, อ่าน → `@Transactional(readOnly = true)`; กฎที่นับทั้งบริษัทเรียก `TenantLocks.lock(...)` ก่อนอ่าน; เพิ่ม / เปิดใช้งานกลับ
  record ที่แพ็กเกจนับ → `PlanLimits.check()` หลังกฎอื่นผ่าน; audit ผ่าน `Audit.record()` ใน transaction เดียวกัน (สำเร็จเท่านั้น)
- dao: SQL ไม่ใส่ `WHERE tenant_id` (RLS กรอง) แต่ INSERT ใส่ `tenant_id = app_tenant_id()`; id ของบริษัทอื่น = ไม่เจอ → 404 เอง
- PUT ทับเฉพาะ field ที่ส่ง (record ขาเข้า field เป็น nullable) เหมือน mock `{ ...current, ...body }` — ยกเว้นที่ mock แทนทั้งตัว (ผู้จำหน่าย / ช่องทางชำระ)
- **สต็อกเปลี่ยนผ่าน `StockLedger` เท่านั้น** (`receive` / `issue` / `recordSerials`): ล็อกแถว `branch_stock` FOR UPDATE → `Costing` → update → movement;
  เอกสารหลายบรรทัดเรียกเรียงตาม productId (กัน deadlock), เรียกหลังตรวจกฎผ่านแล้ว
- ทุน: `CostHidingAdvice` ซ่อนให้เองทุก response (E43) — ห้ามซ่อนเองทีละ endpoint; ขาเข้าจากคนที่เห็นทุนไม่ได้ไม่ใช้ทุนใน body (E44 — ดู `ProductService.withStoredCosts`)
- รายการ (list): ไม่มี paging — `rules/ListLimits`: รายการตามวันที่ใช้ `parseRange(from, to)` (บังคับ ≤ 366 วัน, `shiftId=` ยกเว้นตาม spec),
  dao `LIMIT ListLimits.QUERY_LIMIT` (กรองใน SQL ก่อน LIMIT) แล้ว `rowLimitError(rows.size(), dated)` → 400 (ไม่ตัดเงียบ); DB เกิน statement_timeout → 503 อัตโนมัติ
- test: `TestCompanies.create()` ต่อ test + `as(token, put(...), body)` / `message("…")`; ทุก endpoint มี test ข้าม tenant (`TenantB`) → 404

## PgBouncer (transaction pooling) — กติกาโค้ด

แอปต่อ PgBouncer เสมอ (`DB_URL`); Flyway **migrate** ต่อ PostgreSQL ตรง (`DB_MIGRATE_URL`) — แอปไม่ start ถ้าสอง URL เป็น host:port เดียวกัน
- ตั้งค่าได้แค่ `set_config(…, true)` / `SET LOCAL` — ห้าม `SET` แบบ session, `LISTEN` / `NOTIFY`, temp table, cursor / prepared statement แบบ SQL (`PREPARE`) ข้าม transaction
- advisory lock แบบ `_xact_` เท่านั้น; ทุก query อยู่ใน transaction
- prepared statement ระดับ protocol (pgJDBC ทำเองหลังซ้ำ 5 ครั้ง) ใช้ได้เพราะ `max_prepared_statements` > 0 — **Azure ต้องตั้ง `pgbouncer.max_prepared_statements` เอง** (ค่าเริ่ม 0)

## Schema (กติกาเต็มใน `docs/plan-step.md` หัวข้อ "Schema")

- ทุนเฉลี่ย `numeric(14,4)` (round4), ทุนต่อหน่วย `numeric(14,4)`, เงิน / มูลค่า / COGS `numeric(14,2)` (round2); ประวัติ (movement, serial, audit) ไม่มี FK ไป record ที่ลบได้

- migration = SQL ล้วนใน `db/migration` (ไม่มี placeholder, **ไม่แก้ไฟล์ที่ apply แล้ว**), รันได้ทั้งแอปและ `db-apply.sh`; ไม่มี JPA / `ddl-auto`
- ชื่อตาราง `dt_` + snake_case (ชื่อในแผน / `data-model.md` ไม่มี prefix); ทุกตารางข้อมูลมี `tenant_id` + `ENABLE` / `FORCE ROW LEVEL SECURITY` policy `current_setting('app.tenant_id', true)::bigint`
- FK รวม `(tenant_id, <ref>_id) → (tenant_id, id)` `ON DELETE RESTRICT` + index ฝั่งลูก; index / unique ขึ้นต้นด้วย `tenant_id`
- `created_at/_by/_by_kind`, `updated_*` ตั้งด้วย trigger `set_row_stamp()` (append-only มีแค่ `created_*`); ไม่ส่งออก API
- เงิน `numeric(14,2)`, เวลา `timestamptz`, วันธุรกิจ `date`, enum = `text` + `CHECK`; ห้ามลบเอกสาร
- role: `an22_owner` (เจ้าของ, Flyway), `an22_app` (ร้าน, ใต้ RLS, `audit_log` insert + select), `an22_platform` (BYPASSRLS) — `db/roles.sql` รันด้วย admin ที่**ไม่ใช่ superuser** ได้ (Azure); ห้ามใช้คำสั่งที่ต้อง superuser ใน migration / roles.sql
- extension ใหม่ → ต้องเปิดใน `azure.extensions` ของ Azure ก่อน (เขียนใน `db/README.md`)
- policy ใช้ `app_tenant_id()` (ค่าว่าง → null → 0 แถว, ไม่ error); ตารางใหม่ copy รูปแบบจาก `V1__base.sql` (คอลัมน์ stamp, trigger, RLS, grant)
  — `SchemaConventionsIT` ตรวจกติกาทั้งหมดนี้กับ DB จริง: ผิดกติกา test แดง (ตาราง platform ที่ an22_app แตะได้ต้องเพิ่มใน `APP_PLATFORM_GRANTS` อย่างตั้งใจ)
- an22_app อ่านตาราง platform ได้แค่ `dt_companies` — ใบแจ้งหนี้ / reset token / platform admin ใช้ `platformTx`
- seed (`db/seed`, version แทรกหลัง migration ที่ใช้) = `seedDb()` ของ mock: DEMO, HQ + BN, `admin`/`admin`, `somchai`/`1234`, platform `platform`/`platform` — dev / test เท่านั้น (profile `dev` / `test` เพิ่ม location)

## Config (env — ครบใน `.env.example`)

`DB_URL` (PgBouncer) · `DB_MIGRATE_URL` (ตรง) · `DB_APP_USER/PASSWORD` · `DB_PLATFORM_USER/PASSWORD` · `DB_OWNER_USER/PASSWORD` (ไม่ต้องเมื่อ `FLYWAY_MIGRATE=false`) ·
`DB_POOL_SIZE` (10) · `FLYWAY_MIGRATE` (`true` migrate / `false` validate ผ่าน PgBouncer) · `PII_KEYS` · `PII_HASH_KEY` · `JWT_SECRET` (`<kid>:<base64>,…`) · `JWT_TTL_DAYS` (7) ·
`LOG_BODY` (`off` / `error` / `all`) · `LOG_BODY_MAX` (4096) · `APP_BASE_URL` (ต้นลิงก์ในอีเมล — ไม่ใช้ `Host`) · `JOBS_ENABLED` (`true`; test = `false`) · `JOBS_CRON` (stack ทดสอบเท่านั้น) · `SHUTDOWN_DRAIN` (10 วิ, 0–60; test = 0)
— ค่าผิด / ไม่ตั้ง → ไม่ start ข้อความบอก**ชื่อตัวแปร ไม่บอกค่า** (`ApiProperties.check()`, `PiiCipher.fromEnv`) — ห้ามใช้ bean validation กับค่าลับ (error ของ binding พิมพ์ค่า)

## ความปลอดภัย (E48 / E49)

- api อยู่หลัง an22-web เท่านั้น: JWT stateless, **ไม่มี** cookie / session / CSRF / CORS / logout; IP ผู้ใช้ = `X-Real-IP`; ลิงก์อีเมลจาก base URL ที่ตั้งค่า (ไม่ใช่ `Host`); GET ห้ามแก้ข้อมูล
- **ไม่มีความลับใน repo / log / ข้อความ error** (รหัส DB, JWT secret, กุญแจ PII) — env เท่านั้น, `.env` ถูก ignore
- GitHub code scanning (CodeQL) `java/spring-disabled-csrf-protection` ที่ `SecurityConfig.stateless()` = **ตั้งใจปิด ห้ามเปิด**: api รับแค่ `Authorization: Bearer`
  (browser ไม่แนบเอง, ไม่มี cookie / session) — CSRF กันที่ an22-web (`XSRF-TOKEN`); เปิดที่ api = request ของ web พังทั้งหมด (alert #2 รอ dismiss "Won't fix")
- ค่าจาก query / body ห้ามตรวจด้วย regex ที่ซ้อน quantifier (ReDoS) และห้าม cast `double` → `long` ตรง ๆ — จำกัดความยาวก่อนแล้ว parse ด้วย `BigDecimal` (ดู `AuditRules.id`)
- ข้อมูลส่วนบุคคล: `PiiCipher` AES-256-GCM `[version][nonce][ct+tag]` + AAD `<ตาราง>.<ช่อง>:<tenant_id>` (`PiiCipher.aad`), ค้นเลขผู้เสียภาษีด้วย `hashTaxId(tenant, เลข)`;
  หมุนกุญแจ = เพิ่ม version ใหม่ใน `PII_KEYS` (เขียนด้วยตัวสูงสุด อ่านได้ทุกตัว) — **สำรองกุญแจแยกจาก backup ของ DB** (หาย = ข้อมูลผู้ซื้อหายถาวร)
- บัตรเครดิต: ห้ามเก็บเลขบัตรเต็ม (ขั้น 9)

## JSON / error

- context path `/api` (path ใน spec ไม่รวม `/api`), port 8080; JSON ไม่ละ `null` (`default-property-inclusion: always`), เวลา ISO-8601 (`Instant` → `…Z`)
- ทศนิยมเข้า field `Integer` / `Long` → 400 ข้อความกลาง (`accept-float-as-int: false`, ไม่ตัดเงียบ — B3); field ที่ mock มีกฎ "จำนวนเต็ม" ของตัวเอง
  ใช้ `Double` + ตรวจในกฎ หรือ `@JsonDeserialize(using = IntegerOrInvalid.class)` (ทศนิยม → ค่านอกช่วงของกฎ → ข้อความของ mock)
- เวลาท้องถิ่น Asia/Bangkok ทั้ง container + JVM (`Application.main`, `Dockerfile`, test) → log +07:00, session DB `TimeZone = Asia/Bangkok`;
  **กฎ / วันธุรกิจยังต้องใช้ `Clock` ที่ inject เสมอ** (test แทนด้วย Clock ปลอม) — ห้าม `LocalDate.now()` / `Instant.now()` ตรง ๆ
- error ทุกตัว `{ "message": "<ไทย>" }` ข้อความตามกฎ / mock ทุกตัวอักษร: `throw new ApiException(status, msg)` (หรือ `ApiException.badRequest/forbidden/notFound`)
- ข้อความกลาง (`ErrorResponse`): 404 / 405 `ไม่มี endpoint <METHOD> <path>`, 400 `ข้อมูลที่ส่งมาไม่ถูกต้อง`, 500 `ระบบขัดข้อง กรุณาลองใหม่` (ไม่มีรายละเอียด), 401 `เซสชันหมดอายุ กรุณาเข้าสู่ระบบใหม่`
- DB ปฏิเสธค่าที่กฎไม่ได้ตรวจ (CHECK 23514, ตัวเลขเกินคอลัมน์ 22003, NUL ใน text 22021) → `ErrorHandler` ตอบ 400 ข้อความกลาง + log WARN แค่ SQLSTATE (ไม่ log ข้อความ DB — มีค่าของแถว);
  เป็นตาข่ายเท่านั้น — กฎที่ต้องมีข้อความเฉพาะยังต้องอยู่ใน `rules/` ตาม spec

## Log

JSON 1 บรรทัดต่อ event ไป stdout (Spring Boot structured logging `logstash`, ไม่เขียนไฟล์), `requestId` อยู่ใน MDC ทุกบรรทัด
- `RequestLogFilter`: 1 บรรทัด `"type":"access"` ต่อ request (method, path, query ที่ mask, status, durationMs, tenantId / userId / branchId / terminalId จาก request attribute `RequestLogFilter.ATTR_*`, ip)
- body ตาม `LOG_BODY` (ค่าเริ่ม `error` = เฉพาะ 4xx / 5xx), mask key `password` `token` `approval` `buyer` `taxId` `address` `email` `reference` ทุกระดับ → `"***"`;
  ไม่ log body เลยที่ `/auth/**`, `/platform/auth/**`, `/shifts/*/unlock`, `/company/export`, `/pos/held-bills/**`; ไม่ log header
- health = debug (`LOGGING_LEVEL_ACCESS=debug` เพื่อดู); ห้าม log ค่าที่ถอดรหัสแล้ว / รหัสผ่าน / token

## การ port กฎ + fixtures (ขั้น 7 เป็นต้นไป)

กฎ `xxxError()` ใน `../FrontEnd-An22/src/app/core/models/*.model.ts` → class ใน `rules/` ผลตรงทุกตัวเลข / ข้อความ; เงินใช้ `double` + `Money.round2()` แบบ JS
(`Math.round(n * 100) / 100` — Java `Math.round(double)` ปัดแบบเดียวกัน; ตรวจค่าที่สงสัยกับ `node -e` ได้)
แปลงเป็น `BigDecimal` scale 2 ตอนเก็บ; test ตาม `*.model.spec.ts` + `FixturesTest` อ่าน `../FrontEnd-An22/docs/backend/fixtures/*.json` (ทับได้ด้วย `-Dfixtures.dir=…`)

## Test

- integration test สืบทอด `ApiTest` (context เดียวกันถูก cache — อย่าเพิ่ม `@TestPropertySource` / `@MockitoBean` โดยไม่จำเป็น: ได้ context + pool ใหม่)
- DB จริงเสมอผ่าน PgBouncer (จับ bug ของ transaction pooling); ทุกขั้นต้องมี test ข้าม tenant (token บริษัท A อ่าน / เขียนของ B → 404)
- `TestDatabase` เปิด container ใน `start()` (ไม่ใช่ static initializer — lambda ของ class ถูกเรียกจาก thread อื่น → deadlock)
- test ที่ login ผิดหลายครั้งต้องส่ง `X-Real-IP` ของตัวเอง (ตัวนับต่อ IP อยู่ใน DB ที่ทุก test ใช้ร่วม); เทียบ array ใน JSON ใช้ `jsonPath(path, Matchers.contains(…))`
  (`.value(List.of(…))` ไม่ match กับ JsonPath ใน Boot 4)

## Git

commit ทุกขั้นย่อย (ติ๊กสถานะใน `docs/plan-step.md` + อัปเดตไฟล์นี้ถ้ามีกติกาใหม่) — **ห้าม push โดยไม่ถาม**
