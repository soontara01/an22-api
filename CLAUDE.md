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
```

## โครง

```
db/              roles.sql, migration/ (V<n>__*.sql + afterMigrate.sql), seed/ (DEMO dev / test เท่านั้น), README.md (ติดตั้ง / Azure / กุญแจ)
docker/          initdb.sh (สร้าง azure_admin จำลอง server admin ของ Azure → roles.sql), pgbouncer/ (pgbouncer.ini, start.sh) — compose + Testcontainers ใช้ร่วม
scripts/         db-apply.sh
src/main/java/com/an22/api/
  config/        ApiProperties (env ทั้งหมด + check()), ClockConfig (Asia/Bangkok), DataSourceConfig (app / platform, appTx / platformTx, JdbcClient), FlywayConfig
  security/      PiiCipher (+ PiiConfig), SecurityConfig — ขั้น 2: JwtService, CurrentUser, ClientIp
  web/           ApiException, ErrorResponse (ข้อความกลาง), ErrorHandler, FallbackErrorController (/error), RequestLogFilter
  health/        /healthz, /readyz
  rules/         (ขั้นถัดไป) กฎที่ port จาก core/models — pure Java ไม่ผูก Spring, 1 class ต่อ 1 ไฟล์ TS
  <feature>/     XxxController → XxxService → XxxDao
src/test/java/com/an22/api/
  support/       TestDatabase (singleton container), ApiTest (base: @SpringBootTest + MockMvc, get/post/delete ใส่ /api ให้), ProbeController (endpoint ของ test), RolesSqlIT
```

## 3 ชั้น + transaction (บังคับ)

| ชั้น | ทำ | ห้าม |
| --- | --- | --- |
| controller | HTTP, header / path / body, ตรวจ role, เรียก service **1 method ต่อ request**, คืน DTO | SQL, `@Transactional`, กฎธุรกิจ |
| service | `@Transactional` (1 method = 1 use case = 1 transaction), เรียงตรวจกฎ (`rules/`), เรียก dao, audit, เลขเอกสาร | SQL, ของ HTTP |
| dao | SQL ผ่าน `JdbcClient`, map แถว | กฎธุรกิจ, `@Transactional` |

- tx manager: `appTx` (ค่าเริ่ม, role `an22_app` ใต้ RLS) / `platformTx` (`@Transactional("platformTx")`, `an22_platform` BYPASSRLS); JdbcClient: `appJdbc` (ค่าเริ่ม) / `platformJdbc`
- GET ใช้ `@Transactional(readOnly = true)` → PostgreSQL `READ ONLY` (enforceReadOnly เปิดแล้ว); ห้ามเรียก `@Transactional` method ใน class เดียวกัน; งานที่ต้องบันทึกแม้ request ล้ม = `REQUIRES_NEW`
- ขั้น 2: `TenantTransactionManager` ตั้ง `app.tenant_id` / `app.user_id` / `app.actor_kind` (SET LOCAL) ทุก transaction, TxGuard (query นอก transaction → error)

## PgBouncer (transaction pooling) — กติกาโค้ด

แอปต่อ PgBouncer เสมอ (`DB_URL`); Flyway **migrate** ต่อ PostgreSQL ตรง (`DB_MIGRATE_URL`) — แอปไม่ start ถ้าสอง URL เป็น host:port เดียวกัน
- ตั้งค่าได้แค่ `set_config(…, true)` / `SET LOCAL` — ห้าม `SET` แบบ session, `LISTEN` / `NOTIFY`, temp table, cursor / prepared statement แบบ SQL (`PREPARE`) ข้าม transaction
- advisory lock แบบ `_xact_` เท่านั้น; ทุก query อยู่ใน transaction
- prepared statement ระดับ protocol (pgJDBC ทำเองหลังซ้ำ 5 ครั้ง) ใช้ได้เพราะ `max_prepared_statements` > 0 — **Azure ต้องตั้ง `pgbouncer.max_prepared_statements` เอง** (ค่าเริ่ม 0)

## Schema (กติกาเต็มใน `docs/plan-step.md` หัวข้อ "Schema")

- migration = SQL ล้วนใน `db/migration` (ไม่มี placeholder, **ไม่แก้ไฟล์ที่ apply แล้ว**), รันได้ทั้งแอปและ `db-apply.sh`; ไม่มี JPA / `ddl-auto`
- ชื่อตาราง `dt_` + snake_case (ชื่อในแผน / `data-model.md` ไม่มี prefix); ทุกตารางข้อมูลมี `tenant_id` + `ENABLE` / `FORCE ROW LEVEL SECURITY` policy `current_setting('app.tenant_id', true)::bigint`
- FK รวม `(tenant_id, <ref>_id) → (tenant_id, id)` `ON DELETE RESTRICT` + index ฝั่งลูก; index / unique ขึ้นต้นด้วย `tenant_id`
- `created_at/_by/_by_kind`, `updated_*` ตั้งด้วย trigger `set_row_stamp()` (append-only มีแค่ `created_*`); ไม่ส่งออก API
- เงิน `numeric(14,2)`, เวลา `timestamptz`, วันธุรกิจ `date`, enum = `text` + `CHECK`; ห้ามลบเอกสาร
- role: `an22_owner` (เจ้าของ, Flyway), `an22_app` (ร้าน, ใต้ RLS, `audit_log` insert + select), `an22_platform` (BYPASSRLS) — `db/roles.sql` รันด้วย admin ที่**ไม่ใช่ superuser** ได้ (Azure); ห้ามใช้คำสั่งที่ต้อง superuser ใน migration / roles.sql
- extension ใหม่ → ต้องเปิดใน `azure.extensions` ของ Azure ก่อน (เขียนใน `db/README.md`)
- seed (`db/seed`, version แทรกหลัง migration ที่ใช้) = `seedDb()` ของ mock: DEMO, HQ + BN, `admin`/`admin`, `somchai`/`1234`, platform `platform`/`platform` — dev / test เท่านั้น (profile `dev` / `test` เพิ่ม location)

## Config (env — ครบใน `.env.example`)

`DB_URL` (PgBouncer) · `DB_MIGRATE_URL` (ตรง) · `DB_APP_USER/PASSWORD` · `DB_PLATFORM_USER/PASSWORD` · `DB_OWNER_USER/PASSWORD` (ไม่ต้องเมื่อ `FLYWAY_MIGRATE=false`) ·
`DB_POOL_SIZE` (10) · `FLYWAY_MIGRATE` (`true` migrate / `false` validate ผ่าน PgBouncer) · `PII_KEYS` · `PII_HASH_KEY` · `LOG_BODY` (`off` / `error` / `all`) · `LOG_BODY_MAX` (4096)
— ค่าผิด / ไม่ตั้ง → ไม่ start ข้อความบอก**ชื่อตัวแปร ไม่บอกค่า** (`ApiProperties.check()`, `PiiCipher.fromEnv`) — ห้ามใช้ bean validation กับค่าลับ (error ของ binding พิมพ์ค่า)

## ความปลอดภัย (E48 / E49)

- api อยู่หลัง an22-web เท่านั้น: JWT stateless, **ไม่มี** cookie / session / CSRF / CORS / logout; IP ผู้ใช้ = `X-Real-IP`; ลิงก์อีเมลจาก base URL ที่ตั้งค่า (ไม่ใช่ `Host`); GET ห้ามแก้ข้อมูล
- **ไม่มีความลับใน repo / log / ข้อความ error** (รหัส DB, JWT secret, กุญแจ PII) — env เท่านั้น, `.env` ถูก ignore
- ข้อมูลส่วนบุคคล: `PiiCipher` AES-256-GCM `[version][nonce][ct+tag]` + AAD `<ตาราง>.<ช่อง>:<tenant_id>` (`PiiCipher.aad`), ค้นเลขผู้เสียภาษีด้วย `hashTaxId(tenant, เลข)`;
  หมุนกุญแจ = เพิ่ม version ใหม่ใน `PII_KEYS` (เขียนด้วยตัวสูงสุด อ่านได้ทุกตัว) — **สำรองกุญแจแยกจาก backup ของ DB** (หาย = ข้อมูลผู้ซื้อหายถาวร)
- บัตรเครดิต: ห้ามเก็บเลขบัตรเต็ม (ขั้น 9)

## JSON / error

- context path `/api` (path ใน spec ไม่รวม `/api`), port 8080; JSON ไม่ละ `null` (`default-property-inclusion: always`), เวลา ISO-8601 (`Instant` → `…Z`), วันธุรกิจคิดด้วย `Clock` (Asia/Bangkok) — JVM เป็น UTC
- error ทุกตัว `{ "message": "<ไทย>" }` ข้อความตามกฎ / mock ทุกตัวอักษร: `throw new ApiException(status, msg)` (หรือ `ApiException.badRequest/forbidden/notFound`)
- ข้อความกลาง (`ErrorResponse`): 404 / 405 `ไม่มี endpoint <METHOD> <path>`, 400 `ข้อมูลที่ส่งมาไม่ถูกต้อง`, 500 `ระบบขัดข้อง กรุณาลองใหม่` (ไม่มีรายละเอียด), 401 `เซสชันหมดอายุ กรุณาเข้าสู่ระบบใหม่`

## Log

JSON 1 บรรทัดต่อ event ไป stdout (Spring Boot structured logging `logstash`, ไม่เขียนไฟล์), `requestId` อยู่ใน MDC ทุกบรรทัด
- `RequestLogFilter`: 1 บรรทัด `"type":"access"` ต่อ request (method, path, query ที่ mask, status, durationMs, tenantId / userId / branchId / terminalId จาก request attribute `RequestLogFilter.ATTR_*`, ip)
- body ตาม `LOG_BODY` (ค่าเริ่ม `error` = เฉพาะ 4xx / 5xx), mask key `password` `token` `approval` `buyer` `taxId` `address` `email` `reference` ทุกระดับ → `"***"`;
  ไม่ log body เลยที่ `/auth/**`, `/platform/auth/**`, `/shifts/*/unlock`, `/company/export`, `/pos/held-bills/**`; ไม่ log header
- health = debug (`LOGGING_LEVEL_ACCESS=debug` เพื่อดู); ห้าม log ค่าที่ถอดรหัสแล้ว / รหัสผ่าน / token

## การ port กฎ + fixtures (ขั้น 7 เป็นต้นไป)

กฎ `xxxError()` ใน `../FrontEnd-An22/src/app/core/models/*.model.ts` → class ใน `rules/` ผลตรงทุกตัวเลข / ข้อความ; เงินใช้ `double` + `round2` แบบ JS (`Math.floor(x * 100 + 0.5) / 100`)
แปลงเป็น `BigDecimal` scale 2 ตอนเก็บ; test ตาม `*.model.spec.ts` + `FixturesTest` อ่าน `../FrontEnd-An22/docs/backend/fixtures/*.json` (ทับได้ด้วย `-Dfixtures.dir=…`)

## Test

- integration test สืบทอด `ApiTest` (context เดียวกันถูก cache — อย่าเพิ่ม `@TestPropertySource` / `@MockitoBean` โดยไม่จำเป็น: ได้ context + pool ใหม่)
- DB จริงเสมอผ่าน PgBouncer (จับ bug ของ transaction pooling); ทุกขั้นต้องมี test ข้าม tenant (token บริษัท A อ่าน / เขียนของ B → 404)
- `TestDatabase` เปิด container ใน `start()` (ไม่ใช่ static initializer — lambda ของ class ถูกเรียกจาก thread อื่น → deadlock)

## Git

commit ทุกขั้นย่อย (ติ๊กสถานะใน `docs/plan-step.md` + อัปเดตไฟล์นี้ถ้ามีกติกาใหม่) — **ห้าม push โดยไม่ถาม**
