# แผนทำ an22-api (C2) — ทีละขั้นย่อย

สถานะ: แผนเสนอ 2026-10-20 — รอผู้ใช้อ่าน / แก้ก่อนเริ่มขั้น 0

## บริบท

- repo `an22-api` ยังว่าง (มีแค่ `noom-test.txt` ของผู้ใช้ — ไม่แตะ) ต้องเป็น API จริงแทน mock ของ FrontEnd-An22 และเป็นเจ้าของ schema PostgreSQL (Flyway, E47)
- spec: `../FrontEnd-An22/docs/backend/*` + mock `mock-backend.interceptor.ts` (5,596 บรรทัด) / spec ของมัน = reference implementation
- api อยู่หลัง `../an22-web` (BFF, E48 — เสร็จแล้ว **ห้ามแก้**): api เป็น JWT stateless, ไม่ทำ cookie / CSRF / CORS / logout, IP ผู้ใช้ = `X-Real-IP`
- เป้าหมายสุดท้าย: หน้าบ้าน build ด้วย `useMock: false` (C3) แล้วใช้งานได้เหมือน mock

## เครื่องมือ (ตกลงแล้ว 2026-10-20)

| เรื่อง | เลือก |
| --- | --- |
| ภาษา / framework | Java 25 (Temurin ที่มีในเครื่อง) + Spring Boot 4.x รุ่นล่าสุด (Spring Framework 7, Jackson 3) |
| build | Maven + `mvnw` (wrapper อยู่ใน repo) |
| DB access | Spring JDBC (`JdbcClient`) — SQL ตรง, ไม่มี JPA / Hibernate (จึงไม่มี `ddl-auto` เลย) |
| DB | PostgreSQL 18 — dev: `compose.yaml`, test: Testcontainers (image เดียวกัน) |
| migration | Flyway — SQL อยู่ที่ `db/` (root ของ repo) เป็น SQL ล้วน รันได้ทั้งจากแอปและด้วยมือ (หัวข้อ "script DB") |
| JWT | Spring Security + `oauth2-resource-server` (Nimbus) HS256, secret จาก env |
| เงินในกฎที่ port | `double` + `round2` แบบ JS (`Math.floor(x * 100 + 0.5) / 100`) ให้ตรง fixtures; แปลงเป็น `BigDecimal` scale 2 ตอนเก็บ `numeric(14,2)` |
| seed dev | ตาม `seedDb()` ของ mock ทั้งหมด (+ ผลของ migration ใน mock เช่นสต็อกยกมา) — `db/seed` เปิดเฉพาะ dev / test (ไม่ลง production) |
| อีเมล | เลื่อนไปทีหลัง — interface `Mailer` + ตัวที่ log ว่า "ส่งแล้ว" (ไม่ log ลิงก์ / token / อีเมลเต็ม) |
| test | JUnit 5 + AssertJ + Spring Boot Test (MockMvc) + Testcontainers |

## โครง repo / package (`com.an22.api`)

```
pom.xml, mvnw, .mvn/          compose.yaml (postgres:18 + `db/roles.sql`)   .env.example   CLAUDE.md   docs/plan-step.md
db/                           script DB ทั้งหมด (หัวข้อ "script DB")   scripts/db-apply.sh
src/main/java/com/an22/api/
  Application.java
  config/       ค่าจาก env (ApiProperties), Clock (Asia/Bangkok), Jackson (null ไม่ละ, ISO-8601)
  security/     JwtService (ออก / ตรวจ), SecurityConfig (chain ร้าน / platform), CurrentUser, ClientIp (X-Real-IP)
  db/           TenantTx — เปิด transaction + `set_config('app.tenant_id', ?, true)` (= SET LOCAL), DocNumbers (ตัวนับเลขเอกสาร)
  web/          ApiException(status, message), ErrorHandler → `{ message }`, BranchContext (X-Branch-Id), TerminalContext (X-Terminal-Id), CostHidingAdvice (hideCost จุดเดียว)
  rules/        **port จาก core/models — pure Java ไม่ผูก Spring** 1 class ต่อ 1 ไฟล์ TS: PosPricing, PosPayment, PaymentMethodRules, PromotionRules, PriceRules,
                Costing, SerialRules, ProductRules, CategoryRules, Suppliers, TransferRules, SaleRules, CreditNoteRules, ExchangeRules, TaxInvoiceRules,
                ShiftRules, PosTerminalRules, StoreInfoRules, MenuAccessRules, UserRules, CostVisibility, CompanyRules, AuditRules, PlanRules,
                SubscriptionRules, SignupRules, PlatformRules, CompanyDataRules, Money (round2, formatBaht `฿1,234.5`)
  auth/ company/ branch/ user/ masterdata/ product/ stock/ transfer/ sale/ shift/ pos/ audit/ billing/ platform/   ← แต่ละ feature: Controller + Service + Repository (JdbcClient)
src/main/resources/
  application.yml (context-path `/api`, port 8080) — Flyway อ่าน `filesystem:db/migration` (+ `db/seed` ใน dev / test); Docker image copy `db/` เข้าไป
src/test/java/com/an22/api/
  rules/        unit test ของกฎ + FixturesTest (อ่าน `../FrontEnd-An22/docs/backend/fixtures/*.json`)
  support/      PostgresContainer (singleton), ApiTest (MockMvc + login helper ร้าน / platform), TenantB (บริษัทที่ 2 สำหรับ test ข้าม tenant)
  <feature>/    integration test ต่อ endpoint
```

## script DB (ตกลงแล้ว 2026-10-20)

script ทั้งหมดอยู่ที่ `db/` ที่ root ของ repo — **ไฟล์เดียวกัน** ใช้ได้ทั้งแอป (Flyway ตอน start) และคนรันเองบน DB:

```
db/
  README.md                      ลำดับรัน + คำสั่ง (ติดตั้ง DB ใหม่ / อัปเกรด / dev)
  roles.sql                      สร้าง role an22_owner / an22_app / an22_platform + database — รันครั้งเดียวด้วย superuser
                                 รหัสผ่านส่งเป็นตัวแปร psql (`psql -v app_password=… -f db/roles.sql`) ไม่มีรหัสในไฟล์
  migration/V1__base.sql …       schema ทีละ version (ตาราง, index, RLS, grant) — SQL ล้วน ไม่ใช้ placeholder ของ Flyway, ไม่แก้ไฟล์ที่ใช้แล้ว
  seed/V1_1__seed_demo.sql …     ข้อมูลตัวอย่าง DEMO (dev / test เท่านั้น) — เลข version แทรกหลัง migration ที่มันต้องใช้
scripts/db-apply.sh              รันด้วยมือ: `roles.sql` (ถ้าสั่ง `--roles`) → Flyway CLI (Docker image `flyway/flyway`) migrate `db/migration` (+ `db/seed` เมื่อ `--seed`)
                                 ต่อ DB จาก env (`DB_URL`, `DB_OWNER_USER`, `DB_OWNER_PASSWORD`) — บันทึกลง `flyway_schema_history` เหมือนแอปรัน จึงไม่ซ้ำ / ไม่ข้ามไฟล์
```

- **ทางที่ 1 (ค่าเริ่ม)**: แอปรัน Flyway เองตอน start ด้วย role `an22_owner`
- **ทางที่ 2 (คนรันเอง / DBA)**: `scripts/db-apply.sh` แล้วตั้ง `FLYWAY_MIGRATE=false` ให้แอป — แอปแค่ `validate` (version ใน DB ไม่ตรงกับไฟล์ → ไม่ start) และไม่ต้องรู้รหัสของ `an22_owner`
- ไม่มี Docker / Flyway CLI: `psql -f` ทีละไฟล์ตามเลข version ได้บน **DB ว่าง** (ไฟล์เป็น SQL ล้วน) แต่ครั้งต่อไปต้องใช้ทางที่ 1 / 2 หลัง `flyway baseline` — เขียนวิธีไว้ใน `db/README.md`
- dev: `compose.yaml` ใส่ `docker/initdb.sh` ใน `docker-entrypoint-initdb.d` ซึ่งเรียก `psql -v … -f db/roles.sql` (รหัสจาก `.env`); test: Testcontainers รัน `roles.sql` แล้ว migrate + seed

## กติกาที่ใช้ทุกขั้น

### Schema
- ทุกตารางข้อมูล: `tenant_id bigint not null` (+ `branch_id` ตามระดับใน `data-model.md`), `ENABLE` + `FORCE ROW LEVEL SECURITY`,
  policy `USING / WITH CHECK (tenant_id = current_setting('app.tenant_id', true)::bigint)` — ไม่ตั้งค่า = ไม่เห็นอะไรเลย
- FK รวม `(tenant_id, id)` กันอ้างข้ามบริษัท; index / unique ขึ้นต้นด้วย `tenant_id`; ชื่อ snake_case
- เงิน `numeric(14,2)`, เวลา `timestamptz`, วันที่ธุรกิจ `date`, enum = `text` + `CHECK`; ห้ามลบเอกสาร (มีแต่เปลี่ยนสถานะ)
- ตาราง platform (`companies`, `billing_invoices`, `platform_admins`, `password_reset_tokens`, ตารางนับ login ผิด) ไม่อยู่ใต้ RLS
- DB role 3 ตัว (สร้างด้วย `db/roles.sql` — ต้องใช้ superuser จึงไม่อยู่ใน migration; รหัสผ่านจาก env / ตัวแปร psql — ไม่อยู่ใน repo; migration ทำ grant):
  - `an22_owner` — เจ้าของตาราง, Flyway ใช้ตัวนี้เท่านั้น
  - `an22_app` — แอปฝั่งร้าน: ไม่ใช่ owner, ไม่มี `BYPASSRLS`, `audit_log` ได้แค่ `INSERT` + `SELECT`, อ่าน `companies` ได้ (ตรวจบริษัทยังใช้งาน)
  - `an22_platform` — งาน platform + ก่อน login (login / signup / ลืมรหัสผ่าน หาบริษัทจากรหัส): `BYPASSRLS`
  - แอปมี DataSource 2 ตัว (app / platform); ห้ามใช้ `SET` แบบ session (connection pool)
- เลขเอกสาร: ตาราง `doc_counters (tenant_id, prefix, last_no)` + `INSERT … ON CONFLICT DO UPDATE … RETURNING` (ล็อกแถว ไม่ชนเมื่อขายพร้อมกัน); วันในเลข = วันท้องถิ่น Asia/Bangkok

### Request
- JWT: claims `sub`, `tenantId`, `role`, `branchIds` / `allBranches`; ร้าน vs platform แยกด้วย `aud` (`shop` / `platform`) — ใช้สลับกัน → 401; อายุ `JWT_TTL_DAYS` (ค่าเริ่ม 7)
- ทุก request ของร้าน: อ่านผู้ใช้ + บริษัทจาก DB (สิทธิ์สาขามีผลทันที) — บริษัทไม่ active / ผู้ใช้ปิด / ลบแล้ว → 401
- `X-Branch-Id`: ตรวจทุก request ยกเว้น `GET /me` (ไม่ใช่สาขาที่ใช้งาน / เข้าไม่ได้ → 403 "ไม่มีสิทธิ์ทำงานในสาขานี้"; งานเขียนสต็อก / ขาย / กะไม่มี → 400 ตาม E17 / E24)
- `X-Terminal-Id`: ตรวจว่าเป็นเครื่องของ tenant (audit `terminalId` = null ถ้าไม่ใช่)
- error ทุกตัว `{ "message": "<ไทย>" }` ข้อความตาม mock ทุกตัวอักษร; JSON ไม่ละ `null`; GET ห้ามแก้ข้อมูล
- ไม่มีความลับใน repo / log / ข้อความ error: `DB_*_PASSWORD`, `JWT_SECRET` (≥ 32 byte, ไม่ตั้ง = ไม่ start) จาก env; ไม่ log body ของ auth / header Authorization

### ทุกขั้นย่อย (Definition of done)
1. migration ใหม่ apply บน DB ว่างผ่าน (`./mvnw verify` ใช้ Testcontainers เริ่มจากศูนย์ทุกครั้ง)
2. test ผ่านทั้งหมด รวม **test ข้าม tenant** ของตาราง / endpoint ใหม่: token บริษัท A อ่าน / เขียนของบริษัท B ไม่ได้ (id ของ B ใน URL / body → 404)
3. กฎที่ port มี unit test ตาม `*.model.spec.ts` (ตัวอย่าง input → output ข้อความไทยตรงตัว)
4. อัปเดต `CLAUDE.md` (ถ้ามีคำสั่ง / กติกาใหม่) + ติ๊กสถานะในไฟล์นี้ → commit (ไม่ push จนกว่าผู้ใช้สั่ง)
5. เจอ spec ไม่ชัด → ถือ mock; spec ต้องแก้ → **บอกผู้ใช้ก่อน** แล้วแก้ docs + mock ใน `../FrontEnd-An22` commit เดียวกัน; ต้องแก้ an22-web → บอกผู้ใช้ก่อน

## ขั้นย่อย

### ขั้น 0 — โครงโปรเจกต์
- `mvnw` + `pom.xml` (Boot 4: web, security, oauth2-resource-server, jdbc, flyway + flyway-database-postgresql, postgresql, validation; test: testcontainers-postgresql)
- `db/README.md`, `db/roles.sql`, `scripts/db-apply.sh` (ทดสอบ: DB ว่าง → `--roles --seed` ผ่าน, รันซ้ำไม่ทำอะไร)
- `compose.yaml`: `postgres:18` + `docker/initdb.sh` → `db/roles.sql` สร้าง database + role 3 ตัว (รหัสจาก `.env`); `.env.example` (ไม่มีค่าจริง), `.gitignore`
- config `FLYWAY_MIGRATE` (`true` = migrate ตอน start, `false` = validate อย่างเดียว)
- `application.yml`: context path `/api`, port 8080, Jackson ไม่ละ null + ISO-8601, `TZ` ของกฎ = Asia/Bangkok, health `GET /api/healthz`
- `ApiException` + `ErrorHandler` (`{ message }`, 404 ทั่วไป, 400 JSON อ่านไม่ได้, 500 ไม่เปิดเผยรายละเอียด)
- test support: Postgres container singleton + Flyway migrate (รวม seed) + MockMvc
- `CLAUDE.md` ฉบับแรก (ตามหัวข้อ "สิ่งที่ CLAUDE.md ของแต่ละ repo ควรมี" ใน README: เวอร์ชัน, โครง package, คำสั่ง build / test / รัน / รีเซ็ต DB, กติกา schema, RLS, seed, ความปลอดภัย E49, JWT, การ port กฎ + fixtures, JSON)
- test: context ขึ้นได้, `/api/healthz`, error format

### ขั้น 1 — schema ฐาน + RLS + seed บริษัท / ผู้ใช้
- migration `db/migration/V1__base.sql`: grant ให้ role, ตาราง platform (`companies`, `platform_admins`, `billing_invoices`, `password_reset_tokens`), `company_info`, `branches`, `users` (+ `user_branches`, partial unique owner), `menu_access`, `audit_log` (insert-only), `doc_counters`
- seed (`db/seed/V1_1__seed_demo.sql`): DEMO, HQ (`00000`) + BN, `admin` / `admin` (owner ตาม `fillOwner()`), `somchai` / `1234`, ผู้ใช้อื่นใน `SEED_USERS`, `company_info` จาก `SEED_STORE_INFO`, menu access ค่าเริ่ม, platform `platform` / `platform` — รหัสผ่านเป็น BCrypt
- test: migrate บน DB ว่าง (ทั้งผ่านแอปและ `scripts/db-apply.sh`); RLS — ตั้ง tenant 1 ไม่เห็นแถวของ tenant 2 / ไม่ตั้ง = 0 แถว / INSERT ด้วย tenant อื่นถูกปฏิเสธ; `an22_app` UPDATE / DELETE `audit_log` ไม่ได้; `an22_app` ไม่มี BYPASSRLS และไม่ใช่ owner; unique รวม tenant (username ซ้ำได้ข้ามบริษัท)

### ขั้น 2 — auth / tenant / branch (แกนของทุก request)
- กฎ: `UserRules` (`canWorkIn`, `hasAdminRights`, `isCompanyAdmin`, …), `CompanyRules.normalizeCompanyCode`, `CostVisibility.canSeeCost`, `SubscriptionRules` (`subscriptionStatus`, `billingNotice`, ข้อความระงับ)
- `JwtService`, `SecurityConfig` (chain `/api/platform/**` ↔ ร้าน), `TenantTx`, `BranchContext`, `TerminalContext`, `ClientIp`
- endpoint: `POST /auth/login` (E3 ข้อความเดียว, ระงับ / รอลบ ตามลำดับ mock, audit `login` / `login_failed`), `GET /me`, `POST /auth/verify` (400 เสมอ), `POST /platform/auth/login`, `GET /platform/me`
- จำกัด login ผิด / `auth/verify` ต่อบัญชี + ต่อ `X-Real-IP` (ตาราง platform นับครั้ง) — **ต้องถามผู้ใช้ก่อนทำ: จำนวนครั้ง / ระยะเวลา / ข้อความ** แล้วเพิ่มใน `api-contract.md`
- test: login ถูก / ผิดทุกแบบ (ข้อความเดียว), token ร้านใช้กับ `/platform/**` ไม่ได้และกลับกัน, token หมดอายุ / ปลอม → 401, ปิดผู้ใช้แล้ว token เดิม → 401, `X-Branch-Id` ผิด → 403 (ยกเว้น `/me`), ไม่ log รหัสผ่าน

### ขั้น 3 — ตั้งค่าบริษัท / สาขา / ผู้ใช้
- กฎ: `CompanyRules` (`branchError`, `branchDetailsError`, `companyInfoError`, `storeInfoOf`, …), `StoreInfoRules`, `MenuAccessRules`, `UserRules` (`userChangeError`, `userIdentityError`, `userBranchError`, `userScopeError`, `passwordError`, `ownerRoleError`, `ownerTransferError`), `PlanRules` (`planLimitError` 403)
- endpoint: `/settings/store|company|branch|menu-access`, `/branches`, `/users` (+ `/password`), `POST /company/owner`
- audit: `company_settings`, `branch_settings`, `branch_change`, `menu_access`, `user_change`, `user_password`, `owner_transfer`
- test: สิทธิ์ 403 ตาม `saas-plan.md` หัวข้อ 17, admin สาขา (E6), แพ็กเกจเต็ม → 403, ข้าม tenant
- (`requireShiftChangeError` ต้องรู้ว่ามีกะเปิด — ตาราง `shifts` ยังไม่มีในขั้นนี้ → ใส่ตอนขั้น 7)

### ขั้น 4 — ข้อมูลหลัก
- migration: `categories`, `suppliers`, `payment_methods`, `pos_terminals`, `sku_prices` (`EXCLUDE USING gist` — ต้องมี extension `btree_gist`), `promotions` (+ สาขา / ของแถม)
- กฎ: `CategoryRules`, `Suppliers.isValidThaiTaxId`, `PaymentMethodRules`, `PosTerminalRules`, `PriceRules`, `PromotionRules` (+ `promotionBranchScopeError` 403)
- endpoint: `/categories`, `/suppliers`, `/payment-methods` (+ `order`), `/pos-terminals`, `/prices`, `/promotions`; audit `price_change`, `promotion_change`, `terminal_change`
- seed: หมวด / ผู้จำหน่าย / ช่องทางชำระ / เครื่อง POS 3 เครื่อง / ราคา / โปร ตาม mock

### ขั้น 5 — SKU / สต็อก
- **ถามผู้ใช้ก่อน**: ทุนเฉลี่ยเก็บ `numeric(14,2)` (ตรงกับ mock ที่ปัด 2 ตำแหน่ง) หรือ `numeric(14,4)` (`data-model.md` บอกให้ตกลงก่อนทำ)
- migration: `products`, `product_pack_units`, `product_suppliers`, `branch_stock`, `stock_movements`, `serial_numbers`
- กฎ: `ProductRules`, `Costing` (`movingAverage`, `buildStockCard`, …), `SerialRules`, `Money` (`moneyInputError`), กฎ SKU ที่อยู่ใน mock (`validateSku`, `receiptCostError`, การลบที่ถูกอ้าง)
- endpoint: `/products` (+ `stock`, `movements`, `branch-stock`, `serials`, `serials/remove`); audit `stock_out`, `serial_remove`
- **ซ่อนทุน (E43 / E44)**: `CostHidingAdvice` — ทุก response ของคนที่ `canSeeCost` = false: key ใน `COST_FIELDS` → `null` ทุกระดับ + `{qty, avgCost, value}` → `value: null`; ขาเข้า SKU ไม่ใช้ `cost` / `suppliers[].cost` (คงค่าเดิม / ใหม่ = 0)
- seed: สินค้า + สต็อกยกมา (`opening`) ของ HQ / BN ตามผลของ `migrateCosting` / `migrateBranchStock` / `syncSerials` ใน mock
- test: ทุนเฉลี่ยตรงตัวอย่าง spec, สต็อกต่อสาขา, `user` ได้ทุนเป็น null ทุก endpoint ของขั้นนี้, `staffSeeCost` = true แล้วเห็น

### ขั้น 6 — ใบโอนสินค้า
- migration: `transfers`, `transfer_lines`; กฎ `TransferRules`; endpoint `/transfers` (+ `receive`, `cancel`), เลข `TR-…`; audit `transfer_cancel`
- test: โอน non-serial / serial, รับขาด (E28), ยกเลิก (E29), สิทธิ์สาขาต้นทาง / ปลายทาง

### ขั้น 7 — กฎคำนวณ POS + fixtures (pure, ไม่มี DB)
- port `PosPricing` (`priceCart`, `cartError`, `freeSets`, `manualDiscountError`, `belowCostIssues`), `PosPayment` (`paymentSummary`, `paymentError`, `cashDueFor`), ส่วนที่เหลือของ `PaymentMethodRules` (`roundCash`, …)
- `FixturesTest` (`@TestFactory`): `price-cart.json` (293 เคส) + `payment.json` (200 เคส) เทียบทั้ง object ด้วยค่าตัวเลข + ข้อความตรงทุกตัวอักษร — **ต้องผ่านทุกเคสก่อนขั้น 9**
- path fixtures: `../FrontEnd-An22/docs/backend/fixtures` (ตั้งทับได้ด้วย system property `fixtures.dir` สำหรับ CI)

### ขั้น 8 — กะ + ล็อก POS + บิลพัก (E48)
- migration: `shifts` (+ `locked_at`, partial unique กะเปิด 1 กะต่อเครื่อง), `shift_counts`, `cash_movements`, `drawer_opens`, `shift_adjustments`, `held_bills` (`payload jsonb`)
- กฎ: `ShiftRules` ทั้งหมด (`shiftBlocker`, `openShiftError`, `countCheck`, `closeShiftError`, `shiftSummary`, `varianceReport`, …), `PosTerminalRules.terminalError`
- endpoint: `/shifts/*` ทั้งหมด รวม `lock` / `unlock` (คนเปิดกะหรือ admin, ข้อความตาม contract), `/pos/held-bills`; เติม `requireShiftChangeError` ให้ `PUT /settings/store|company`, ปิดสาขา / เครื่องที่มีกะเปิด
- audit `shift_close_other`, `shift_approve`, `shift_adjust`; test blind count (user ไม่เห็นยอด), P6 (user เห็นเฉพาะกะตัวเอง)

### ขั้น 9 — ขาย + ใบกำกับภาษี
- migration: `sales`, `sale_lines`, `sale_payments`, `tax_invoices`
- `POST /sales` ตามลำดับตรวจ 1–8 ใน `api-contract.md` → ตัดสต็อกพร้อม COGS (`issueStock`), เลข `POS-<สาขา>-<เครื่อง>-YYYYMMDD-NNNN`, snapshot; ขายระหว่างกะถูกล็อก → 400
- `POST /sales/cost-check` (E45), `GET /sales` (+ `?branchId=` ตาม `branchScopeError`), `GET /sales/:id`, `void` (approval ของ admin), `PUT /sales/:id/status`
- ใบกำกับ: `POST /sales/:id/tax-invoice`, `reissue`, `GET …/tax-invoice(s)`, `GET /tax-invoices`, `GET /tax-invoices/buyer` — กฎ `TaxInvoiceRules`, `SaleRules`
- audit `manual_discount`, `sale_void`, `tax_invoice_reissue`; seed บิลตัวอย่างตาม `SEED_SALES`
- test: ลำดับข้อความ error ตรง mock, ขายพร้อมกัน 2 thread เลขไม่ซ้ำ, COGS + movement, ทุนเป็น null สำหรับ user

### ขั้น 10 — ใบลดหนี้ + ใบเปลี่ยน
- migration: `credit_notes` (+ lines / deductions / refunds), `exchanges` (+ lines); กฎ `CreditNoteRules` (`draftCreditNote`, …), `ExchangeRules`
- endpoint: `/sales/:id/credit-notes`, `/credit-notes`, `/sales/:id/exchanges`; field derived ของ `Sale` (`returnStatus`, `creditedTotal`, `creditNoteNos`, `exchangeNos`); audit `credit_note`
- **ถามผู้ใช้**: ควรขอให้หน้าบ้านเพิ่ม `credit-note.json` ใน fixtures ก่อน (README ของ fixtures แนะนำไว้) หรือไม่

### ขั้น 11 — ส่วน SaaS ของบริษัท
- `GET /audit-log` (`AuditRules.filterAuditEvents`, `parseAuditQuery`), `GET /company/onboarding`, `GET /company/subscription`, `PUT /company/plan`
- billing: job รายวัน (`@Scheduled`) ออกใบแจ้งหนี้ `SUB-YYYYMM-NNNN` + ระงับเมื่อพ้นผ่อนผัน — และตรวจตอน login / `me` ด้วย (contract ให้มีผลทุก request); `actorKind: 'system'`
- `POST /auth/signup` (ใช้ role platform สร้างบริษัท; audit `signup`), `POST /auth/forgot-password` (ตอบข้อความเดียวเสมอ, token เก็บ hash, ลิงก์จาก `APP_BASE_URL` ไม่ใช่ `Host`), `POST /auth/reset-password` (audit `password_reset`)
- `GET /company/export` (ไม่มีรหัสผ่าน / token), `GET|POST|DELETE /company/deletion`; ระหว่างรอลบ: เฉพาะ owner, endpoint อื่น → 403
- test: billing ตาม `subscription.model.spec.ts` ด้วย `Clock` ปลอม, ลิงก์ไม่ใช้ header `Host`

### ขั้น 12 — platform admin
- endpoint: `/platform/companies` (+ `:id`, `plan`, `suspend`, `reactivate`, `owner-password`, `purge`), `/platform/invoices/:id/pay`; กฎ `PlatformRules`, `CompanyDataRules.purgeError`
- ทุกการกระทำลง `audit_log` ของบริษัทนั้น `actorKind: 'platform'`; purge ลบทุกตารางของ tenant (เหลือแถว `companies` + `deleted_at`)
- `GET /platform/outbox` ไม่ทำ (mock เท่านั้น) — **บอกผู้ใช้** ว่าหน้า platform จะได้ 404 ตรงนี้ เผื่อต้องแก้หน้าบ้าน
- test: token platform เห็นทุกบริษัท, token ร้าน → 401, purge แล้ว login / token เดิม → 401

### ขั้น 13 — ปิดงาน / พร้อม C3
- test กวาดข้าม tenant ทุก endpoint (ตาราง endpoint → อ่าน / เขียน id ของบริษัท B ด้วย token A → 404)
- `Dockerfile` (image ของ api) + `compose.yaml` profile รวม postgres + api (+ an22-web จาก image ของมัน — ไม่แก้ repo web)
- ทดลองกับหน้าบ้านจริง: build หน้าบ้านด้วย `useMock: false` ชั่วคราว (ไม่ commit ในหน้าบ้าน) ผ่าน an22-web → login DEMO, ขาย, ปิดกะ
- อัปเดต `../FrontEnd-An22/docs/backend/README.md` หัวข้อ api ("ทำแล้ว C2 …") + `next-steps-plan.md` — บอกผู้ใช้ก่อน
- C3 (สลับหน้าบ้านถาวร) เป็นงานแยก ไม่อยู่ในแผนนี้

## เรื่องที่ต้องถามผู้ใช้ระหว่างทาง (ไม่เดา)

1. ขั้น 2: จำกัด login ผิดต่อบัญชี / IP — กี่ครั้ง, กี่นาที, ข้อความอะไร (ต้องเพิ่มใน `api-contract.md`)
2. ขั้น 5: ทุนเฉลี่ย `numeric(14,2)` หรือ `(14,4)`
3. ขั้น 10: เพิ่ม fixtures `credit-note.json` ฝั่งหน้าบ้านก่อนหรือไม่
4. ขั้น 12: `GET /platform/outbox` ไม่มีใน api จริง — หน้า platform ต้องซ่อนเมนูนี้เมื่อไม่ใช่ mock หรือไม่
5. ระยะเก็บเอกสารภาษีก่อน purge (E39) — ยังรอนักบัญชี: purge ตามเดิมไปก่อน

## วิธีตรวจ (ทุกขั้น)

```sh
./mvnw verify                          # unit + integration (Testcontainers เปิด postgres:18 ว่างเอง)
scripts/db-apply.sh --roles --seed      # รัน script DB ด้วยมือ (ทางที่ 2) บน DB ตาม env
docker compose up -d db && ./mvnw spring-boot:run -Dspring-boot.run.profiles=dev   # dev DB + seed
docker compose down -v                 # รีเซ็ต DB local
curl -s localhost:8080/api/healthz
```
