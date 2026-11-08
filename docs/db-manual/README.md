# เอกสารฐานข้อมูล (PDF สำหรับ SA)

`an22-db-manual.pdf` — ER diagram, data dictionary, คำอธิบายตาราง, ขั้นตอนธุรกิจ → ตาราง, CRUD matrix, ความปลอดภัยของ DB
(+ ภาคผนวก MongoDB session ของ an22-web)

## สร้างใหม่

```sh
docs/db-manual/build.sh            # DB ชั่วคราว → build/schema.json → build/db-manual.html → an22-db-manual.pdf
docs/db-manual/build.sh --check    # ตรวจว่าทุกตาราง / คอลัมน์มีคำอธิบาย (ขาด / เกิน → exit 1) ไม่สร้าง PDF
docs/db-manual/build.sh --no-db    # ใช้ build/schema.json เดิม (แก้แค่คำอธิบาย / หน้าตา)
```

- ต้องมี: docker (image `postgres:18.6`, `flyway/flyway:12.4.0` — ชุดเดียวกับ `scripts/db-apply.sh`), Node 24, google-chrome, ฟอนต์ Noto Sans Thai
- script เปิด PostgreSQL ชั่วคราว (container + network ชื่อ `an22-dbdoc-<pid>`, รหัสสุ่มใหม่ทุกรอบ) → `docker/initdb.sh` + `db/roles.sql` →
  Flyway migrate `db/migration` (ไม่รวม seed) → อ่าน catalog ด้วย `catalog.sql` → ลบ container / network — ไม่แตะ DB / stack อื่น
- ครั้งแรก `npm ci` ใน `tools/` เอง (Graphviz WASM `@viz-js/viz` สำหรับ ER diagram — ไม่ใช่ dependency ของแอป)

## ไฟล์

| ไฟล์ | ใช้ทำ |
| --- | --- |
| `descriptions.mjs` | **แก้ที่นี่**: กลุ่มตาราง, คำอธิบายตาราง / คอลัมน์, อ้างอิงที่ไม่มี FK, ขั้นตอนธุรกิจ (บทที่ 4), ชื่อ package ของ CRUD matrix |
| `content.mjs` | ข้อความยาวของบทที่ 1 / 6 / ภาคผนวก, สารบัญ, คำอธิบายสัญลักษณ์ |
| `gen.mjs` | ประกอบ HTML: data dictionary + constraint / index / สิทธิ์จาก catalog, ER (DOT → SVG), CRUD matrix จาก SQL ใน `src/main/java` |
| `catalog.sql` | query `pg_catalog` → JSON (ตาราง, คอลัมน์, constraint, index, trigger, policy, grant, role, migration) |
| `style.css` | หน้าตา A4 (+ หน้าแนวนอนของ CRUD matrix) |
| `build/` | ของกลางทาง (ignore): `schema.json`, `er-*.dot`, `db-manual.html` |

## เพิ่ม / แก้ตาราง

1. migration ใหม่ใน `db/migration`
2. `descriptions.mjs`: ใส่ตารางใน `domains` (กลุ่ม + ภาพ ER), เขียน `tables.<ชื่อ>` (title, purpose, lifecycle, rules, derived, columns ทุกคอลัมน์ยกเว้น stamp),
   อ้างอิงที่ตั้งใจไม่มี FK → `logicalRefs`, งานที่เขียนตารางนี้ → `flows`; package ใหม่ของ api → `packages`
3. `build.sh --check` ต้องผ่าน แล้ว `build.sh` — ดู PDF ว่า ER ใหม่อ่านออก (กลุ่มที่ใหญ่เกินหน้าแบ่งภาพด้วย `diagrams`)
4. commit `an22-db-manual.pdf` พร้อม migration
