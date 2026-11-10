# คู่มือ init DB — PostgreSQL ว่าง → พร้อมให้ api ใช้

ใช้เมื่อได้ PostgreSQL server ใหม่ที่ยังไม่มีอะไร (Azure Database for PostgreSQL Flexible Server หรือ PostgreSQL ทั่วไป)
ทำครั้งเดียวต่อ server — รายละเอียดเบื้องหลัง (role, ทางที่ 1 / 2, Azure) อยู่ใน [`db/README.md`](../db/README.md)

ผลลัพธ์เมื่อทำครบ:
- role 3 ตัว `an22_owner` (เจ้าของตาราง / migrate) · `an22_app` (แอปฝั่งร้าน ใต้ RLS) · `an22_platform` (งาน platform, BYPASSRLS)
- database `an22` (เจ้าของ `an22_owner`) ที่ migrate ครบทุก version + ผู้ดูแลแพลตฟอร์มคนแรก 1 คน
- **ไม่มีบริษัท / ข้อมูลตัวอย่าง** — บริษัทแรกสร้างจากหน้า signup ของระบบ

```
ขั้น 1 สร้างรหัส  →  ขั้น 2 ไฟล์ env  →  ขั้น 3 scripts/db-apply.sh --roles  →  ขั้น 4 ผู้ดูแลแพลตฟอร์ม  →  ขั้น 5 ตรวจ  →  ขั้น 6 ตั้งค่า api
```

## 0. สิ่งที่ต้องมี

| อะไร | หมายเหตุ |
| --- | --- |
| บัญชี admin ของ server | ต่อ database `postgres` ได้ และมีสิทธิ์ `CREATEDB` `CREATEROLE` `BYPASSRLS` — ไม่ต้องเป็น superuser (Azure: server admin ที่ตั้งตอนสร้าง server) |
| เครื่องที่รัน script | มี `docker`, `openssl`, `git` และต่อ server ที่ **port 5432 ตรง** ได้ (ไม่ผ่าน PgBouncer) — Azure: เพิ่ม IP / subnet ของเครื่องนี้ใน firewall / private access |
| repo `an22-api` | checkout **commit เดียวกับ image ที่จะ deploy** — script ใช้ไฟล์ใน `db/` ของ working tree |
| Azure เท่านั้น | server parameter: `azure.extensions` มี `BTREE_GIST` · `pgbouncer.enabled = true` · `pgbouncer.pool_mode = transaction` · `pgbouncer.max_prepared_statements` > 0 (เช่น 200) · ไฟล์ CA ของ Azure (สำหรับ `sslmode=verify-full`) |

image ที่ script ดึงมาใช้: `postgres:18.6` (psql), `flyway/flyway:12.4.0` (migrate), `httpd:2.4-alpine` (สร้าง hash รหัสในขั้น 4)

> ระหว่างทำ **อย่าเปิด `log_statement = all`** — รหัสของ role ไปถึง server เป็นข้อความใน SQL

## 1. สร้างรหัส

ต้องการ 3 ค่า (role ละ 1) + รหัสผู้ดูแลแพลตฟอร์ม — เก็บในที่เก็บความลับ (เช่น Azure Key Vault) ทันที

```sh
openssl rand -base64 32 | tr -d '\n/+="'; echo     # รันทีละครั้ง ได้ทีละค่า
```

`tr` ตัด `/ + = "` ออก: รหัสห้ามมี `"` (PgBouncer ของ stack ทดสอบเขียนลง userlist) และไม่ต้อง escape ใน URL / shell

## 2. ไฟล์ env

สร้าง `.env` ที่ root ของ repo (ignore แล้ว — **ห้าม commit**) แล้ว `chmod 600 .env`; `scripts/db-apply.sh` อ่านเอง (ค่าที่ export ไว้ใน shell ชนะ `.env`)

```sh
# admin — libpq URI ไม่ใส่ user / รหัส
DB_ADMIN_URL=postgresql://<host>:5432/postgres
DB_ADMIN_USER=<admin>
DB_ADMIN_PASSWORD=<รหัส admin>

DB_NAME=an22
DB_OWNER_PASSWORD=<รหัส 1>
DB_APP_PASSWORD=<รหัส 2>
DB_PLATFORM_PASSWORD=<รหัส 3>

# Flyway ต่อ PostgreSQL ตรง (port 5432) ด้วย an22_owner
DB_MIGRATE_URL=jdbc:postgresql://<host>:5432/an22
DB_OWNER_USER=an22_owner
```

**Azure** — ใส่ TLS ทั้งสอง URL และบอก path ของไฟล์ CA (script mount ไฟล์นี้เข้า container ที่ path เดียวกัน):

```sh
DB_ADMIN_URL=postgresql://<server>.postgres.database.azure.com:5432/postgres?sslmode=verify-full&sslrootcert=/secure/azure-ca.pem
DB_MIGRATE_URL=jdbc:postgresql://<server>.postgres.database.azure.com:5432/an22?sslmode=verify-full&sslrootcert=/secure/azure-ca.pem
DB_CA_FILE=/secure/azure-ca.pem
```

`DB_APPLY_NETWORK` (ไม่บังคับ, ค่าเริ่ม `host`) = docker network ที่ container ของ script ใช้ต่อ DB — ใช้เมื่อ DB เป็น container ใน network อื่น

## 3. role + database + schema

```sh
scripts/db-apply.sh --roles
```

ทำ 2 อย่างต่อกัน:
1. `db/roles.sql` ด้วย admin → สร้าง role 3 ตัว (ตั้งรหัส + `statement_timeout` 30s / `idle_in_transaction_session_timeout` 60s ให้ `an22_app` / `an22_platform`) และ database `an22`
2. Flyway migrate ด้วย `an22_owner` → `db/migration/V1…` ทุกไฟล์ + `afterMigrate.sql`

ผลที่ควรเห็น (ท้าย ๆ):

```
roles.sql: เสร็จ
== flyway migrate (an22_owner) filesystem:/an22-db/migration
Successfully applied 9 migrations to schema "public", now at version v9
Executing SQL callback: afterMigrate
```

(จำนวน / version สุดท้ายเพิ่มตามไฟล์ใน `db/migration` — ข้อความ "A more recent version of Flyway is available" ไม่ต้องสนใจ;
รันซ้ำจะได้ `Schema "public" is up to date. No migration necessary.`)

- **ห้ามใส่ `--seed` กับ production** — seed คือบริษัท DEMO + ผู้ใช้ / รหัสที่รู้กันทั่วไป
- รันซ้ำได้: role / database ที่มีแล้วไม่สร้างใหม่ (แต่ตั้งรหัสตาม `.env` ใหม่), migration ที่ลงแล้วไม่รันซ้ำ

## 4. ผู้ดูแลแพลตฟอร์มคนแรก

migration ไม่สร้างผู้ใช้ใด ๆ — ผู้ดูแลแพลตฟอร์ม (หน้า `/platform/login`) คนแรกต้องใส่ด้วย SQL; คนต่อไปก็ใส่แบบเดียวกัน
(ระบบยังไม่มีหน้าเพิ่มผู้ดูแลแพลตฟอร์ม)

**4.0 คำสั่ง psql ของ `an22_owner`** (ใช้ในขั้น 4–5; รันที่ root ของ repo — อ่าน `.env` ด้วย grep ไม่ source เพราะ URL ของ Azure มี `&`):

```sh
env_get() { grep -m1 "^$1=" .env | cut -d= -f2-; }
OWNER_URL='postgresql://an22_owner@<host>:5432/an22'    # ← แก้ <host> เป็น host จริง (เครื่องตัวเอง: localhost)
# Azure: OWNER_URL='postgresql://an22_owner@<server>.postgres.database.azure.com:5432/an22?sslmode=verify-full&sslrootcert=/secure/azure-ca.pem'
CA=$(env_get DB_CA_FILE); NET=$(env_get DB_APPLY_NETWORK)
psql_owner() {
  docker run --rm -i --network "${NET:-host}" ${CA:+-v "$CA:$CA:ro"} -e PGPASSWORD="$(env_get DB_OWNER_PASSWORD)" \
    postgres:18.6 psql "$OWNER_URL" -X -v ON_ERROR_STOP=1 "$@"
}
```

**4.1 hash รหัส** (bcrypt cost 10 แบบเดียวกับแอป — พิมพ์รหัสแล้ว Enter; รหัสไม่อยู่ใน command line / history):

```sh
read -rs -p 'รหัสผู้ดูแลแพลตฟอร์ม: ' PW; echo
HASH=$(printf '%s' "$PW" | docker run --rm -i httpd:2.4-alpine htpasswd -niBC 10 "" | tr -d ':\r\n'); unset PW
echo "$HASH" | cut -c1-7      # ต้องได้ $2y$10$
```

**4.2 ใส่แถว** (`username`: `a-z 0-9 . _ -` ยาว 3–30; `created_*` / `updated_*` trigger ใส่ให้เอง — actor `system`):

```sh
psql_owner -q -v hash="$HASH" <<'SQL' && unset HASH
INSERT INTO dt_platform_admins (username, name, password_hash) VALUES ('platform', 'ผู้ดูแลแพลตฟอร์ม', :'hash');
SQL
```

ไม่มีข้อความ = ผ่าน; ล้ม (เช่น `could not translate host name "<host>"` = ยังไม่ได้แก้ `OWNER_URL`) → แก้แล้วรัน 4.2 ซ้ำได้เลย (`HASH` ยังอยู่)

เปลี่ยนรหัสภายหลัง: ทำ 4.1 ใหม่ แล้ว `UPDATE dt_platform_admins SET password_hash = :'hash' WHERE username = 'platform';` แบบเดียวกับ 4.2

## 5. ตรวจ

```sh
psql_owner <<'SQL'
-- migration ครบ ไม่มีตัวที่ล้ม → 1,2,3,…,9 (ตามไฟล์ใน db/migration) และ 0
SELECT string_agg(version, ',' ORDER BY installed_rank) AS versions FROM flyway_schema_history WHERE version IS NOT NULL;
SELECT count(*) FILTER (WHERE NOT success) AS failed FROM flyway_schema_history;
-- role: an22_platform เท่านั้นที่ BYPASSRLS, timeout ของ app / platform
SELECT rolname, rolbypassrls, rolconfig FROM pg_roles WHERE rolname LIKE 'an22_%' ORDER BY 1;
-- ไม่มีข้อมูลตัวอย่าง, มีผู้ดูแลแพลตฟอร์ม → 0 และ 1
SELECT (SELECT count(*) FROM dt_companies) AS companies, (SELECT count(*) FROM dt_platform_admins) AS platform_admins;
SQL
```

ต้องได้:

```
 versions          → 1,2,3,4,5,6,7,8,9
 failed            → 0
 an22_app      | f | {statement_timeout=30s,idle_in_transaction_session_timeout=60s}
 an22_owner    | f |
 an22_platform | t | {statement_timeout=30s,idle_in_transaction_session_timeout=60s}
 companies 0 · platform_admins 1
```

## 6. ตั้งค่า api

ใช้ **ทางที่ 2** (แอปไม่ migrate เอง แค่ validate — ไม่ต้องรู้รหัสของ `an22_owner`):

| env | ค่า |
| --- | --- |
| `DB_URL` | ผ่าน **PgBouncer** — Azure: `jdbc:postgresql://<server>.postgres.database.azure.com:6432/an22?sslmode=verify-full&sslrootcert=<CA>` (ห้ามเป็น host:port เดียวกับ `DB_MIGRATE_URL` — แอปไม่ start) |
| `DB_MIGRATE_URL` | เหมือนขั้น 2 (port 5432) |
| `DB_APP_USER` / `DB_APP_PASSWORD` | `an22_app` / รหัส 2 |
| `DB_PLATFORM_USER` / `DB_PLATFORM_PASSWORD` | `an22_platform` / รหัส 3 |
| `FLYWAY_MIGRATE` | `false` — **ไม่ต้องตั้ง `DB_OWNER_PASSWORD` ให้แอป** |
| `PII_KEYS`, `PII_HASH_KEY`, `JWT_SECRET`, … | ตาม `.env.example` — กุญแจ PII **ต้องสำรองแยกจาก backup ของ DB** (หายแล้วข้อมูลผู้ซื้อหายถาวร) |

start แล้วตรวจ:
- `GET /api/readyz` → `{"status":"ok"}`
- log มี `flyway validate: ผ่าน (FLYWAY_MIGRATE=false)`
- login `/platform/login` ด้วยผู้ดูแลจากขั้น 4 ได้ → สร้างบริษัทแรกจากหน้า signup

## ต่อจากนี้

- **ลองทั้ง stack บนเครื่องนี้**: `scripts/local-stack.sh --build` — api + web + MongoDB (session ของ web) เป็น container ใน network เดียวกับ DB / PgBouncer → http://localhost:14000 (`down` / `status`, ตัวแปรดู `--help`)
- **deploy version ใหม่**: checkout commit ของ image ใหม่ → `scripts/db-apply.sh` (ไม่ต้อง `--roles`) **ก่อน** deploy image —
  image ใหม่ที่มี migration ใหม่ start ไม่ได้จนกว่าจะ migrate (validate ไม่ผ่าน)
- **เปลี่ยนรหัส role**: แก้ `.env` → `scripts/db-apply.sh --roles` → อัปเดตรหัสที่แอป (และ PgBouncer ถ้าตั้งเอง) แล้ว restart
- **ห้ามแก้ไฟล์ migration ที่ apply แล้ว** — เพิ่ม `V<n+1>__…sql` ใหม่ (Flyway ตรวจ checksum: แก้ไฟล์เดิม → validate ไม่ผ่าน)

## เจอปัญหา

| อาการ | สาเหตุ / ทางแก้ |
| --- | --- |
| `ต้องตั้ง env: …` | ค่าใน `.env` ขาด — ชื่อตามที่ script บอก |
| `permission denied to alter role` + `Only roles with the BYPASSRLS attribute may change the BYPASSRLS attribute.` | admin ไม่มี `BYPASSRLS` — role 3 ตัวถูกสร้างไปแล้ว (ยังไม่มี database): ให้สิทธิ์ admin **คนเดิม** (`ALTER ROLE <admin> BYPASSRLS` ด้วย superuser / Azure ใช้ server admin) แล้วรันขั้น 3 ซ้ำด้วย admin คนเดิม |
| `… the ADMIN option on role "an22_owner" may alter this role.` | role ถูกสร้างโดย admin คนอื่น (PG16+: แก้ได้เฉพาะผู้สร้าง) — รันด้วย admin คนที่สร้าง role ไว้ |
| migrate V5 ล้ม `extension "btree_gist" is not allow-listed …` (Azure) | Azure: เพิ่ม `BTREE_GIST` ใน `azure.extensions` แล้วรันขั้น 3 ซ้ำ (migrate ต่อจากที่ล้ม) |
| `SSL error` / `certificate verify failed` | path ใน `sslrootcert` ต้องตรงกับ `DB_CA_FILE` และเป็น CA ของ Azure |
| `connection refused` / timeout | firewall / private access ยังไม่ให้เครื่องนี้ต่อ port 5432 |
| แอปไม่ start: `DB_URL ต้องชี้ PgBouncer …` | `DB_URL` ต้องเป็น port 6432 (PgBouncer) ไม่ใช่ 5432 |
| แอปไม่ start: flyway validate ไม่ผ่าน | migration ใน image ใหม่กว่า DB → รันขั้น 3 (`scripts/db-apply.sh`) ด้วย commit ของ image นั้น |
| platform login 401 | ตรวจ `username` / hash: `SELECT username, left(password_hash, 7) FROM dt_platform_admins;` ต้องได้ `$2y$10$` (หรือ `$2a$` / `$2b$`) |

## ลองคู่มือนี้ในเครื่อง

ทดสอบแล้ว 2026-11-01 บน `postgres:18.6` ว่าง (admin จำลองแบบ Azure: `CREATEDB CREATEROLE BYPASSRLS` ไม่ใช่ superuser) + PgBouncer
`edoburu/pgbouncer:v1.25.2-p0` + image api `03118f6` (`FLYWAY_MIGRATE=false`): ขั้น 3 migrate V1–V9 ผ่าน, รันซ้ำได้ `Schema "public" is up to date. No migration necessary.`,
ขั้น 5 ได้ตามที่เขียน, ข้อความ error 3 แถวแรกของตาราง "เจอปัญหา" มาจากการลองจริง, api `/readyz` 200 + `flyway validate: ผ่าน`, platform login ด้วย hash `$2y$` จาก htpasswd ได้ 200 (รหัสผิด 401)
