// เนื้อหาที่เป็นข้อความยาวของเอกสาร DB (บทที่ 1 / 6 / ภาคผนวก, คำอธิบายสัญลักษณ์) — ตาราง / คอลัมน์อยู่ใน descriptions.mjs
// HTML ตรง ๆ (ไม่ผ่าน escape) — ใช้ <code> / <b> ได้

export const toc = ({ domains, flows }) => `<nav class="toc"><h2>สารบัญ</h2><ol>
  <li><a href="#ch-overview">1 ภาพรวมฐานข้อมูล</a><ol>
    <li><a href="#s-arch">สถาปัตยกรรม</a></li><li><a href="#s-tenant">หลายบริษัทใน DB เดียว</a></li>
    <li><a href="#s-domains">กลุ่มตาราง</a></li><li><a href="#s-stamp">ข้อตกลงของ schema</a></li><li><a href="#s-howto">วิธีอ่านเอกสาร</a></li></ol></li>
  <li><a href="#ch-er">2 ER diagram</a><ol><li><a href="#er-overview">ภาพรวม</a></li>${domains.map((d) => `<li><a href="#er-${d.id}${d.diagrams ? '1' : ''}">กลุ่ม ${d.id} ${d.title}</a></li>`).join('')}</ol></li>
  <li><a href="#ch-dict">3 คำอธิบายตารางและ data dictionary</a><ol>${domains.map((d) => `<li><a href="#d-${d.id}">${d.id} ${d.title}</a></li>`).join('')}</ol></li>
  <li><a href="#ch-flow">4 ขั้นตอนธุรกิจ → ตาราง</a><ol>${flows.map((f) => `<li><a href="#f-${f.id}">${f.title}</a></li>`).join('')}<li><a href="#s-docno">รูปแบบเลขเอกสาร</a></li></ol></li>
  <li><a href="#ch-crud">5 CRUD matrix</a></li>
  <li><a href="#ch-security">6 ความปลอดภัยของฐานข้อมูล</a><ol>
    <li><a href="#s-roles">role</a></li><li><a href="#s-rls">Row-Level Security</a></li><li><a href="#s-grants">สิทธิ์ต่อตาราง</a></li><li><a href="#s-pii">ข้อมูลส่วนบุคคลและความลับ</a></li></ol></li>
  <li><a href="#ch-appendix">ภาคผนวก</a><ol>
    <li><a href="#a-mongo">ก MongoDB session ของ an22-web</a></li><li><a href="#a-migrations">ข ประวัติ migration</a></li>
    <li><a href="#a-build">ค การสร้างเอกสารนี้ใหม่</a></li><li><a href="#a-glossary">ง คำศัพท์</a></li></ol></li>
</ol></nav>`;

export const overview = ({ domainSummary }) => `<section class="chapter" id="ch-overview"><h2>ภาพรวมฐานข้อมูล</h2>
<p class="lead">ระบบหน้าร้านค้าปลีกไอที / เครื่องใช้ไฟฟ้า (สินค้ามี serial, ประกัน, ราคารวม VAT) แบบ SaaS — หลายบริษัท แต่ละบริษัทมีได้หลายสาขา</p>

<h3 id="s-arch">1.1 สถาปัตยกรรม</h3>
<figure class="arch"><svg viewBox="0 0 720 150" xmlns="http://www.w3.org/2000/svg" font-family="Noto Sans Thai, sans-serif" font-size="12">
  <defs><marker id="ar" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="7" markerHeight="7" orient="auto"><path d="M0 0L10 5L0 10z" fill="#5d5e67"/></marker></defs>
  <g fill="#fff" stroke="#5d5e67" stroke-width="1.2">
    <rect x="5" y="45" width="110" height="52" rx="8"/><rect x="155" y="45" width="130" height="52" rx="8"/>
    <rect x="325" y="45" width="120" height="52" rx="8"/><rect x="485" y="45" width="95" height="52" rx="8"/>
    <rect x="615" y="30" width="100" height="82" rx="8" fill="#e8efff" stroke="#0b57d0" stroke-width="1.6"/>
    <rect x="155" y="118" width="130" height="28" rx="6" fill="#fafafc" stroke-dasharray="4 3"/>
  </g>
  <g fill="#1b1b1f" text-anchor="middle">
    <text x="60" y="68" font-weight="700">Browser</text><text x="60" y="86" font-size="10" fill="#5d5e67">Angular SPA</text>
    <text x="220" y="68" font-weight="700">an22-web</text><text x="220" y="86" font-size="10" fill="#5d5e67">BFF: session / CSRF</text>
    <text x="385" y="68" font-weight="700">an22-api</text><text x="385" y="86" font-size="10" fill="#5d5e67">Spring Boot (JWT)</text>
    <text x="532" y="68" font-weight="700">PgBouncer</text><text x="532" y="86" font-size="10" fill="#5d5e67">transaction pool</text>
    <text x="665" y="62" font-weight="700">PostgreSQL</text><text x="665" y="80" font-size="10" fill="#5d5e67">DB an22</text><text x="665" y="96" font-size="10" fill="#5d5e67">43 ตาราง + RLS</text>
    <text x="220" y="137" font-size="10" fill="#5d5e67">MongoDB sessions (ถ้าเปิด)</text>
  </g>
  <g stroke="#5d5e67" stroke-width="1.2" marker-end="url(#ar)" fill="none">
    <path d="M115 71H153"/><path d="M285 71H323"/><path d="M445 71H483"/><path d="M580 71H613"/><path d="M220 97V116"/>
    <path d="M385 45V18H665V28" stroke-dasharray="4 3"/>
  </g>
  <g font-size="9.5" fill="#5d5e67" text-anchor="middle"><text x="134" y="64">cookie</text><text x="304" y="64">Bearer</text><text x="525" y="13">Flyway migrate ต่อ PostgreSQL ตรง (an22_owner)</text></g>
</svg><figcaption>เส้นทางของข้อมูล — browser ไม่เคยถือ token; api ต่อ DB ผ่าน PgBouncer เสมอ (role an22_app / an22_platform)</figcaption></figure>
<ul>
  <li><b>an22-web</b> (Node.js) เสิร์ฟหน้าเว็บ ถือ session ของ login (cookie httpOnly) และ proxy <code>/api</code> ไป api — เก็บ session ในหน่วยความจำหรือ MongoDB (ภาคผนวก ก) <b>ไม่แตะ PostgreSQL</b></li>
  <li><b>an22-api</b> (Java Spring Boot) เป็นทางเดียวที่อ่าน / เขียน PostgreSQL และ<b>เป็นเจ้าของ schema</b>: Flyway migration <code>db/migration/V&lt;n&gt;__*.sql</code> (SQL ล้วน ห้ามแก้ไฟล์ที่ apply แล้ว เพิ่ม version ใหม่แทน)</li>
  <li>กฎธุรกิจ (คิดราคา โปร ทุน การชำระ ฯลฯ) อยู่ในโค้ด <code>rules/</code> ของ api — DB บังคับเฉพาะความถูกต้องพื้นฐาน (FK, unique, CHECK, ห้ามช่วงราคาทับ) เป็นตาข่ายชั้นสุดท้าย</li>
  <li>production: Azure Database for PostgreSQL Flexible Server + PgBouncer ในตัว; ทุก query อยู่ใน transaction (transaction pooling)</li>
</ul>

<h3 id="s-tenant">1.2 หลายบริษัทใน DB เดียว (multi-tenant)</h3>
<ul>
  <li>ทุกบริษัทอยู่ใน database เดียว schema เดียว — <b>ทุกตารางของร้านมีคอลัมน์ <code>tenant_id</code></b> (= <code>dt_companies.id</code>) และเปิด Row-Level Security: แต่ละ transaction เห็นเฉพาะแถวของบริษัทที่ api ตั้งไว้ (<code>app.tenant_id</code>) — รายละเอียดบทที่ 6</li>
  <li>บริษัทมาจาก token ของผู้ใช้เท่านั้น ไม่รับจาก URL / body; สาขาที่ทำงานมาจาก header <code>X-Branch-Id</code> ที่ api ตรวจสิทธิ์แล้ว</li>
  <li>ข้อมูลแบ่ง 2 ระดับ: <b>ระดับบริษัท</b> (มีแค่ <code>tenant_id</code>: SKU, ราคา, โปร, ผู้ใช้, ข้อมูลหลัก — ทุกสาขาใช้ร่วม) และ <b>ระดับสาขา</b> (มี <code>branch_id</code> ด้วย: สต็อก, serial, บัตรสต็อก, กะ, บิล, ใบกำกับ, ใบลดหนี้, ใบเปลี่ยน, เครื่อง POS)</li>
  <li>key ทุกตัวขึ้นต้นด้วย <code>tenant_id</code>: unique เช่น <code>(tenant_id, code)</code>, FK รวม <code>(tenant_id, x_id) → (tenant_id, id)</code> — จึงอ้างแถวของบริษัทอื่นไม่ได้แม้รู้ id</li>
  <li>ตาราง platform (กลุ่ม A) ไม่มี <code>tenant_id</code> (หรือใช้ <code>company_id</code>) และใช้ผ่าน role an22_platform</li>
</ul>

<h3 id="s-domains">1.3 กลุ่มตาราง</h3>
${domainSummary}

<h3 id="s-stamp">1.4 ข้อตกลงของ schema</h3>
<table><thead><tr><th>เรื่อง</th><th>ข้อตกลง</th></tr></thead><tbody>
<tr><td>ชื่อ</td><td>ตาราง <code>dt_</code> + snake_case (ชื่อใน JSON ของ API เป็น camelCase ไม่มี prefix เช่น <code>dt_sale_lines.unit_price</code> → <code>SaleLine.unitPrice</code>); constraint / index ขึ้นต้นด้วยชื่อตาราง</td></tr>
<tr><td>คอลัมน์ stamp</td><td><code>created_at</code>, <code>created_by</code>, <code>created_by_kind</code>, <code>updated_at</code>, <code>updated_by</code>, <code>updated_by_kind</code> — trigger <code>set_row_stamp()</code> ตั้งให้ทุกครั้ง (แอปลืมใส่ไม่ได้, UPDATE ทับ created_* ไม่ได้); <code>*_by</code> = <code>dt_users.id</code> เมื่อ kind = <code>user</code>, <code>dt_platform_admins.id</code> เมื่อ <code>platform</code>, null เมื่อ <code>system</code> (job / seed); ไม่ส่งออก API เว้นบางตารางที่ใช้เป็นเวลาของเอกสาร (บอกไว้ในคอลัมน์)</td></tr>
<tr><td>append-only</td><td>ตารางประวัติ / บรรทัดของเอกสาร (บัตรสต็อก, audit, บรรทัดบิล, การชำระ, ใบลดหนี้, ใบเปลี่ยน, ยอดนับกะ ฯลฯ) มีแค่ <code>created_*</code> (trigger แบบ <code>append_only</code>) และ role ของร้านไม่มีสิทธิ์ UPDATE / DELETE</td></tr>
<tr><td>enum</td><td><code>text</code> + <code>CHECK (x IN (…))</code> ค่าตรงกับ type union ใน TypeScript — data dictionary แสดงค่าที่ใช้ได้ใต้คำอธิบาย</td></tr>
<tr><td>เงิน / ทุน</td><td>เงิน มูลค่า COGS <code>numeric(14,2)</code> (ปัด 2 ตำแหน่ง); ทุนต่อหน่วยและทุนเฉลี่ย <code>numeric(14,4)</code> (ปัด 4); ราคาขายรวม VAT, ทุนไม่รวม VAT; จำนวนสินค้าเป็น <code>integer</code> หน่วยฐาน</td></tr>
<tr><td>เวลา / วันที่</td><td>เวลาเป็น <code>timestamptz</code>; วันธุรกิจ (ช่วงราคา / โปร, ครบกำหนด) เป็น <code>date</code> = วันท้องถิ่น Asia/Bangkok รวมทั้งสองปลาย; <code>null</code> ปลายทาง = ไม่สิ้นสุด</td></tr>
<tr><td>ห้ามลบเอกสาร</td><td>บิล ใบกำกับ ใบลดหนี้ ใบเปลี่ยน ใบโอน กะ บัตรสต็อก serial — มีแต่เปลี่ยนสถานะ / เพิ่มรายการใหม่; master (SKU, ผู้จำหน่าย, ช่องทางชำระ, สาขา) ที่มีเอกสารอ้าง → ปิดใช้งานแทนลบ</td></tr>
<tr><td>snapshot</td><td>เอกสารเก็บค่า ณ ตอนออก (ชื่อ / รหัสสินค้า, ราคา, ชื่อช่องทาง, POS ID, ชื่อผู้ทำ, สถานะ VAT) ไม่ join master ตอนอ่าน</td></tr>
<tr><td>อ้างอิงไม่มี FK</td><td>ประวัติอ้าง master ที่ลบได้โดยไม่มี FK (เช่น <code>dt_sale_lines.product_id</code>) — ER แสดงเป็นเส้นประ, dictionary ติดป้าย <span class="k ref">ref</span></td></tr>
<tr><td>FK</td><td>ทุก FK เป็น <code>ON DELETE RESTRICT</code> (ไม่มี cascade) และมี index ฝั่งลูก</td></tr>
<tr><td>ค่าที่คำนวณ</td><td>ไม่เก็บค่าที่คำนวณได้ (ราคาวันนี้, สถานะคืนสินค้า, ยอดที่คาดของกะ …) — api คำนวณตอนอ่าน (บอกไว้ใน "ค่าที่ไม่ได้เก็บ" ของแต่ละตาราง)</td></tr>
<tr><td>เลขเอกสาร</td><td>ตัวนับใน <code>dt_doc_counters</code> ต่อ prefix (ไม่ใช้ SEQUENCE) — ออกหลังตรวจกฎผ่านใน transaction เดียวกับเอกสาร เลขต่อเนื่องไม่ข้าม (รูปแบบในหัวข้อ 4.<i>n</i> "รูปแบบเลขเอกสาร")</td></tr>
</tbody></table>

<h3 id="s-howto">1.5 วิธีอ่านเอกสาร</h3>
<ul>
  <li>บทที่ 2 ER diagram: ภาพรวมก่อน แล้วภาพละกลุ่มพร้อมคอลัมน์ (ใน PDF กดชื่อตารางเพื่อไปบทที่ 3)</li>
  <li>บทที่ 3 ทุกตาราง: ใช้ทำอะไร → วงจรชีวิตของแถว → กฎ → รายคอลัมน์ → constraint / index / สิทธิ์ ที่อ่านจาก DB จริง</li>
  <li>บทที่ 4 งานแต่ละอย่างเขียน / อ่านตารางไหนตามลำดับ · บทที่ 5 package ของ api ใช้ตารางไหนแบบไหน · บทที่ 6 role / RLS / ข้อมูลที่เข้ารหัส</li>
  <li>รหัสตัวเล็กท้ายข้อความ เช่น <span class="ref">E12</span> <span class="ref">D3</span> = รหัสการตัดสินใจใน <code>FrontEnd-An22/docs/decisions.md</code> (อ่านประกอบได้ ไม่จำเป็น)</li>
  <li>โครงสร้าง (ชนิด / null / ค่าเริ่ม / key / constraint) สร้างจาก DB ทุกครั้งที่ build จึงตรงกับ migration เสมอ; คำอธิบายเขียนใน <code>docs/db-manual/descriptions.mjs</code></li>
</ul>
</section>`;

export const erLegend = `<div class="box"><b>สัญลักษณ์</b>
<div class="legend">
  <svg viewBox="0 0 100 20"><path d="M8 10H92" stroke="#5d5e67" stroke-width="1.4"/><path d="M8 4L20 10L8 16M20 4V16" stroke="#5d5e67" fill="none" stroke-width="1.2"/><circle cx="26" cy="10" r="3.5" fill="#fff" stroke="#5d5e67"/><path d="M84 4V16M90 4V16" stroke="#5d5e67" stroke-width="1.2"/></svg>
  <span>FK แบบบังคับ: ฝั่งตีนกา (ลูก) มีได้ 0..n แถวต่อ 1 แถวของฝั่งขีดคู่ (แม่ — คอลัมน์ FK เป็น NOT NULL)</span>
  <svg viewBox="0 0 100 20"><path d="M8 10H92" stroke="#5d5e67" stroke-width="1.4"/><path d="M8 4L20 10L8 16M20 4V16" stroke="#5d5e67" fill="none" stroke-width="1.2"/><circle cx="26" cy="10" r="3.5" fill="#fff" stroke="#5d5e67"/><path d="M90 4V16" stroke="#5d5e67" stroke-width="1.2"/><circle cx="83" cy="10" r="3.5" fill="#fff" stroke="#5d5e67"/></svg>
  <span>FK ที่ว่างได้ (คอลัมน์ nullable): แถวลูกอาจไม่อ้างแม่</span>
  <svg viewBox="0 0 100 20"><path d="M8 10H92" stroke="#8a8b93" stroke-width="1.4" stroke-dasharray="5 3"/></svg>
  <span>อ้างอิงแบบไม่มี FK (ประวัติ / array / ของชั่วคราว) — ป้าย <span class="k ref">ref</span></span>
  <span style="text-align:center"><b>PK</b> · FK · UQ · <code>?</code></span>
  <span>ในกรอบตาราง: ชื่อตัวหนา = PK, <b>FK</b> = มี FK, <b>UQ</b> = อยู่ใน unique, <code>?</code> = ว่างได้ (nullable); บรรทัด "+ n stamp columns" = คอลัมน์ stamp ที่ไม่แสดง</span>
  <span style="text-align:center"><span style="border:1px dashed #9a9ba3;border-radius:4px;padding:0 6px;font-size:8pt">ตาราง</span></span>
  <span>กรอบเส้นประ = ตารางของกลุ่มอื่นที่ถูกอ้าง (ดูคอลัมน์ในภาพของกลุ่มนั้น)</span>
</div>
<p class="note" style="margin-top:2mm">ไม่วาดเส้น <code>tenant_id → dt_companies</code> ที่ทุกตารางของร้านมี (ภาพจะอ่านไม่ออก) — FK รวม <code>(tenant_id, x_id)</code> วาดจากคอลัมน์ <code>x_id</code></p></div>`;

export const dictLegend = `<p class="lead">เรียงตามกลุ่ม A–G — แต่ละตาราง: ใช้ทำอะไร, วงจรชีวิตของแถว, กฎสำคัญ, ค่าที่ไม่ได้เก็บ แล้วตารางคอลัมน์และ constraint / index / RLS / สิทธิ์ (สร้างจาก DB)</p>
<div class="box"><b>คอลัมน์ของตาราง data dictionary</b>
<b>null</b> ✓ = ว่างได้ · <b>ค่าเริ่มต้น</b> = DEFAULT ของ DB (<i>identity</i> = เลขอัตโนมัติ) · <b>key</b> <span class="k pk">PK</span> primary key <span class="k fk">FK</span> foreign key (ปลายทางอยู่หลัง →) <span class="k uq">UQ</span> อยู่ใน unique / unique index <span class="k ref">ref</span> อ้างอิงแบบไม่มี FK (ปลายทางอยู่หลัง ⇢) ·
<b>สิทธิ์</b> S = SELECT, I = INSERT, U = UPDATE, D = DELETE</div>`;

export const flowIntro = `<p class="lead">งานหลักของระบบเขียน / อ่านตารางไหนตามลำดับ — เขียนจาก service ของ an22-api; ทุกขั้นของงานหนึ่งอยู่ใน transaction เดียวกัน (ไม่สำเร็จ = ไม่มีอะไรถูกบันทึก) เว้นแต่บอกไว้</p>
<div class="box"><b>กติกาที่ทุกงานใช้</b><ul>
<li>ตรวจกฎทั้งหมดก่อน แล้วจึงล็อก / ออกเลข / เขียน (ถือล็อกสั้นที่สุด); งานที่นับทั้งบริษัท (เช่น SKU ซ้ำ บาร์โค้ดซ้ำ เปิดกะ) ล็อกระดับบริษัทด้วย <code>pg_advisory_xact_lock</code> ก่อนอ่าน</li>
<li>สต็อกเปลี่ยนผ่าน <code>StockLedger</code> เท่านั้น: ล็อกแถว <code>dt_branch_stock</code> (FOR UPDATE) → คิดทุน → update → เขียน <code>dt_stock_movements</code>; เอกสารหลายบรรทัดเรียงตาม productId (กัน deadlock)</li>
<li>audit บันทึกใน transaction เดียวกับงาน (สำเร็จเท่านั้น) · ทุกงานของร้านได้ <code>app.tenant_id</code> / <code>app.user_id</code> ของผู้ใช้ → RLS + stamp</li>
</ul><p class="note">ทำ: <span class="op op-I">เพิ่ม</span> INSERT · <span class="op op-U">แก้</span> UPDATE (หรือ upsert) · <span class="op op-D">ลบ</span> DELETE · <span class="op op-R">อ่าน</span> SELECT · <span class="op op-L">ล็อก</span> SELECT … FOR UPDATE</p></div>`;

export const docNumbers = `<h3 id="s-docno">รูปแบบเลขเอกสาร</h3>
<p>ตัวนับอยู่ใน <code>dt_doc_counters</code> (prefix = ทุกอย่างก่อนเลขรัน) · <code>&lt;สาขา&gt;</code> = <code>dt_branches.code</code> ณ ตอนออก · วัน = วันท้องถิ่น · เลขเก่าก่อนมีสาขา / เครื่องไม่เปลี่ยน</p>
<table><thead><tr><th>เอกสาร</th><th>ตาราง.คอลัมน์</th><th>รูปแบบ</th><th>รันต่อ</th></tr></thead><tbody>
<tr><td>บิล POS</td><td><code>dt_sales.order_no</code></td><td><code>POS-&lt;สาขา&gt;-&lt;เครื่อง&gt;-YYYYMMDD-NNNN</code></td><td>เครื่อง + วัน</td></tr>
<tr><td>ใบกำกับเต็มรูป</td><td><code>dt_tax_invoices.invoice_no</code></td><td><code>INV-&lt;สาขาของบิล&gt;-&lt;วันขาย&gt;-NNNN</code></td><td>สาขาของบิล + วันขาย (แม้ออกทีหลัง)</td></tr>
<tr><td>ใบลดหนี้</td><td><code>dt_credit_notes.cn_no</code></td><td><code>CN-&lt;สาขา&gt;-YYYYMMDD-NNNN</code></td><td>สาขา + วันออก</td></tr>
<tr><td>ใบเปลี่ยน</td><td><code>dt_exchanges.ex_no</code></td><td><code>EX-&lt;สาขา&gt;-YYYYMMDD-NNNN</code></td><td>สาขา + วัน</td></tr>
<tr><td>กะ</td><td><code>dt_shifts.shift_no</code></td><td><code>SH-&lt;สาขา&gt;-YYYYMMDD-NN</code></td><td>สาขา + วันเปิดกะ</td></tr>
<tr><td>ใบโอน</td><td><code>dt_transfers.transfer_no</code></td><td><code>TR-&lt;สาขาต้นทาง&gt;-YYYYMMDD-NNNN</code></td><td>สาขาต้นทาง + วัน</td></tr>
<tr><td>ใบแจ้งหนี้ค่าบริการ</td><td><code>dt_billing_invoices.invoice_no</code></td><td><code>SUB-YYYYMM-NNNN</code></td><td>เดือน ทั้งแพลตฟอร์ม (<code>dt_platform_doc_counters</code>)</td></tr>
</tbody></table>`;

export const crudIntro = `<p class="lead">package ของ an22-api (คอลัมน์) ใช้ตาราง (แถว) แบบไหน — สร้างอัตโนมัติจาก SQL ใน <code>src/main/java</code> ทุกครั้งที่ build</p>
<p class="note">C = INSERT · R = SELECT (FROM / JOIN) · U = UPDATE หรือ INSERT … ON CONFLICT DO UPDATE · D = DELETE — นับตาม package ของไฟล์ที่มี SQL (dao) ไม่แยก endpoint: service ของ package หนึ่งเรียก dao ของ package อื่นได้ (เช่นการขายอ่านกะผ่าน <code>ShiftDao</code> จึงนับที่ <code>shift</code>) — ดูงานจริงตามลำดับในบทที่ 4; ช่องที่มี * = SQL ที่ประกอบชื่อตารางตอนรัน (หมายเหตุท้ายตาราง); เมนูของหน้าบ้านเรียก endpoint ของ package เหล่านี้ เช่น เมนู SKU → <code>product</code>, ขายหน้าร้าน → <code>sale</code> + <code>shift</code> + <code>pos</code></p>`;

export const grantNotes = {
  dt_companies: 'an22_app อ่านแถวของบริษัทตัวเองเท่านั้น (RLS `id`)',
  dt_billing_invoices: 'an22_app อ่านใบของบริษัทตัวเอง (RLS `company_id`)',
  dt_doc_counters: 'ไม่มี DELETE',
  dt_held_bills: 'ของชั่วคราว ลบได้',
  dt_sales: 'ไม่มี DELETE (แก้ได้แค่ void / เลขใบกำกับ)',
  dt_tax_invoices: 'ไม่มี DELETE (แก้ได้แค่ยกเลิก)',
  dt_shifts: 'ไม่มี DELETE',
  dt_transfers: 'ไม่มี DELETE',
  dt_transfer_lines: 'ไม่มี DELETE',
  dt_login_throttle: 'ใช้ก่อนรู้บริษัท — platform เท่านั้น',
  dt_password_reset_tokens: 'platform เท่านั้น',
  dt_platform_admins: 'platform เท่านั้น',
  dt_platform_doc_counters: 'platform เท่านั้น',
};

export const security = ({ grantsTable, schema }) => {
  const role = (n) => schema.roles.find((r) => r.name === n) ?? { config: [] };
  const cfg = (n) => (role(n).config.length ? role(n).config.map((c) => `<code>${c}</code>`).join(' ') : '—');
  return `<section class="chapter" id="ch-security"><h2>ความปลอดภัยของฐานข้อมูล</h2>
<p class="lead">แยกสิทธิ์ด้วย role 3 ตัว + Row-Level Security ต่อบริษัท + เข้ารหัสข้อมูลส่วนบุคคลในแอป — ค่าในบทนี้ส่วนหนึ่งอ่านจาก DB จริง</p>
<h3 id="s-roles">6.1 role</h3>
<table class="roles"><thead><tr><th>role</th><th>ใช้ทำ</th><th>ต่อทาง</th><th>RLS</th><th>ค่าตั้งของ role (จาก DB)</th></tr></thead><tbody>
<tr><td><code>an22_owner</code></td><td>เจ้าของ database / ตารางทั้งหมด — Flyway migrate เท่านั้น (แอปไม่ใช้ตอนทำงาน)</td><td>PostgreSQL ตรง (5432)</td><td>ถูกบังคับด้วย (FORCE)</td><td>${cfg('an22_owner')}</td></tr>
<tr><td><code>an22_app</code></td><td>งานของผู้ใช้ร้านทุก request (<code>appTx</code>) — ไม่ใช่เจ้าของตาราง</td><td>PgBouncer (6432)</td><td>อยู่ใต้ RLS</td><td>${cfg('an22_app')}</td></tr>
<tr><td><code>an22_platform</code></td><td>งานก่อน login (login, signup, ลืมรหัส), ผู้ดูแลแพลตฟอร์ม, job ค่าบริการ, purge (<code>platformTx</code>)</td><td>PgBouncer (6432)</td><td><b>BYPASSRLS</b></td><td>${cfg('an22_platform')}</td></tr>
</tbody></table>
<ul><li>สร้างด้วย <code>db/roles.sql</code> โดย admin ที่<b>ไม่ใช่ superuser</b> (ใช้ได้บน Azure) — รหัสผ่านของ role อยู่ในที่เก็บความลับ (env) ไม่อยู่ใน repo</li>
<li>schema <code>public</code>: ยกเลิกสิทธิ์ CREATE ของ PUBLIC; ให้แค่ USAGE แก่ an22_app / an22_platform — ทั้งสองสร้าง / แก้โครงสร้างไม่ได้</li>
<li>timeout: query เกิน <code>statement_timeout</code> ถูกยกเลิก (api ตอบ 503), transaction ค้างเกิน <code>idle_in_transaction_session_timeout</code> ถูกตัด — an22_owner ไม่ตั้ง (migration อาจนาน)</li></ul>

<h3 id="s-rls">6.2 Row-Level Security (แยกข้อมูลบริษัท)</h3>
<ul>
<li>ทุกตารางที่มี <code>tenant_id</code>: <code>ENABLE</code> + <code>FORCE ROW LEVEL SECURITY</code> และ policy <code>tenant_isolation</code> แบบเดียวกัน: <code>USING (tenant_id = app_tenant_id()) WITH CHECK (tenant_id = app_tenant_id())</code> — อ่าน / แก้ / ลบได้เฉพาะแถวของบริษัทใน transaction และเขียนแถวของบริษัทอื่นไม่ได้</li>
<li><code>app_tenant_id()</code> อ่านค่า <code>app.tenant_id</code> ที่ api ตั้งด้วย <code>set_config(…, true)</code> (SET LOCAL) <b>ทุก transaction</b> จากบริษัทใน token — ไม่ตั้ง / ค่าว่าง = null → <b>เห็น 0 แถว</b> (ไม่ error) จึงไม่มีทางหลุดเป็น "เห็นทุกบริษัท"</li>
<li>FORCE ทำให้แม้เจ้าของตาราง (an22_owner) ก็ถูกบังคับ; มีแค่ an22_platform (BYPASSRLS) ที่ข้ามได้ — ใช้กับงานที่ต้องเห็นหลายบริษัทเท่านั้น</li>
<li>ตาราง platform: <code>dt_companies</code> (policy บน <code>id</code>) และ <code>dt_billing_invoices</code> (บน <code>company_id</code>) เปิด RLS ให้ร้านอ่านของตัวเอง; ตาราง platform อื่น an22_app ไม่มีสิทธิ์เลย</li>
<li>ชั้นถัดจาก RLS: api ใส่บริษัทเองตอน INSERT, FK รวม <code>(tenant_id, …)</code> กันอ้างข้ามบริษัท และ test ของ api ยิงทุก endpoint ที่มี id ด้วย id ของบริษัทอื่น → ต้องได้ 404 (<code>CrossTenantSweepIT</code>, <code>RlsIT</code>)</li>
</ul>

<h3 id="s-grants">6.3 สิทธิ์ต่อตาราง (จาก DB)</h3>
<p>S = SELECT · I = INSERT · U = UPDATE · D = DELETE — ตารางเอกสาร / ประวัติไม่มี DELETE (และ append-only ไม่มี UPDATE) แม้มีบั๊กในแอปก็ลบเอกสารไม่ได้; an22_platform มีครบเพื่อ purge</p>
${grantsTable}

<h3 id="s-pii">6.4 ข้อมูลส่วนบุคคลและความลับ</h3>
<table><thead><tr><th>ข้อมูล</th><th>ตาราง.คอลัมน์</th><th>วิธีเก็บ</th></tr></thead><tbody>
<tr><td>ผู้ซื้อของใบกำกับ / ใบลดหนี้ (ชื่อ เลขผู้เสียภาษี / passport ที่อยู่)</td><td><code>dt_tax_invoices.buyer_enc</code>, <code>dt_credit_notes.buyer_enc</code></td><td>เข้ารหัสทั้งก้อนในแอป AES-256-GCM รูปแบบ <code>[version][nonce][ciphertext+tag]</code>, AAD = <code>&lt;ตาราง&gt;.&lt;ช่อง&gt;:&lt;tenant_id&gt;</code> (ย้ายข้ามแถว / บริษัทแล้วถอดไม่ได้)</td></tr>
<tr><td>บิลที่พักไว้ (อาจมีผู้ซื้อ)</td><td><code>dt_held_bills.payload_enc</code></td><td>เข้ารหัสแบบเดียวกัน</td></tr>
<tr><td>ค้นผู้ซื้อด้วยเลขภาษี</td><td><code>dt_tax_invoices.buyer_tax_id_hash</code></td><td>HMAC ด้วยกุญแจ <code>PII_HASH_KEY</code> + tenant — ค้นได้โดยไม่ถอดรหัส ไม่ย้อนกลับเป็นเลขได้</td></tr>
<tr><td>รหัสผ่าน</td><td><code>dt_users.password_hash</code>, <code>dt_platform_admins.password_hash</code></td><td>BCrypt — ไม่ส่งออก API / export</td></tr>
<tr><td>ลิงก์ตั้งรหัสผ่าน</td><td><code>dt_password_reset_tokens.token_hash</code></td><td>เก็บ hash ของ token, ใช้ครั้งเดียว หมดอายุ 1 ชม.</td></tr>
<tr><td>ตัวนับ login ผิด</td><td><code>dt_login_throttle.key_hash</code></td><td>HMAC ของ (บริษัท + username) / IP — ไม่เก็บ username / IP ตรง ๆ</td></tr>
<tr><td>บัตรเครดิต</td><td><code>dt_sale_payments.reference</code></td><td><b>ห้ามเก็บเลขบัตรเต็ม</b> (api ปฏิเสธก่อนถึง DB) — เก็บแค่เลขอนุมัติ / 4 หลักท้าย</td></tr>
</tbody></table>
<div class="box warn"><b>กุญแจเข้ารหัส</b>กุญแจ (<code>PII_KEYS</code>, <code>PII_HASH_KEY</code>) อยู่ใน env ของ api ไม่อยู่ใน DB / repo — <b>ต้องสำรองแยกจาก backup ของ DB</b>: กุญแจหาย = ข้อมูลผู้ซื้อถอดไม่ได้ถาวร; หมุนกุญแจ = เพิ่ม version ใหม่ใน <code>PII_KEYS</code> (เขียนด้วยตัวใหม่สุด อ่านได้ทุกตัว) ไม่ต้องเขียนข้อมูลเก่าใหม่ทันที</div>
<ul><li>อีเมล / ชื่อผู้ใช้ / ชื่อผู้จำหน่าย เก็บเป็นข้อความปกติ (ต้องค้น / แสดง) — คุ้มครองด้วย RLS + สิทธิ์ของ role</li>
<li>ต้นทุน / กำไร: DB เก็บครบ แต่ api ใส่ null ในทุกช่องทุนของ response ให้ role user เมื่อบริษัทปิด <code>staff_see_cost</code> (ซ่อนที่ api ไม่ใช่ที่ DB)</li>
<li>log ของ api ไม่บันทึกค่าจาก DB ที่ถอดรหัสแล้ว / รหัสผ่าน / token และ mask คีย์ที่อ่อนไหวใน body</li></ul>
</section>`;
};

export const migrationNotes = {
  1: 'function กลาง (`app_tenant_id()`, `set_row_stamp()`), ตาราง platform, บริษัท / สาขา / ผู้ใช้ / เมนู, audit log, ตัวนับเลขเอกสาร, RLS + grant ชุดแรก',
  2: 'RLS ของ `dt_companies` / `dt_billing_invoices` ให้ร้านอ่านของตัวเอง + ตัวนับ login ผิด',
  3: 'ข้อมูลหลัก: หมวดหมู่, ผู้จำหน่าย, ช่องทางชำระเงิน, เครื่อง POS',
  4: 'SKU, หน่วยแพ็ก, ผู้จำหน่ายของ SKU, สต็อกต่อสาขา, บัตรสต็อก, serial',
  5: 'ราคาขายตามช่วงวัน (extension btree_gist + EXCLUDE) และโปรโมชั่น',
  6: 'ใบโอนสินค้าระหว่างสาขา',
  7: 'กะ + ยอดนับ + เงินเข้าออก + เปิดลิ้นชัก + ปรับปรุงยอด, บิลที่พักไว้',
  8: 'บิลขาย + บรรทัด + การชำระ, ใบกำกับภาษีเต็มรูป',
  9: 'ใบลดหนี้ (+ บรรทัด / ยอดหัก / คืนเงิน), ใบเปลี่ยนสินค้า (+ บรรทัด)',
};

export const appendix = ({ migrationsTable }) => `<section class="chapter appendix" id="ch-appendix"><h2>ภาคผนวก</h2>
<h3 id="a-mongo">ก MongoDB session ของ an22-web</h3>
<p>an22-web เก็บ session ของ login (token ของ api ที่ browser ไม่เคยเห็น) ตาม env <code>SESSION_STORE</code>: <code>memory</code> (เครื่องเดียว หายเมื่อ restart) หรือ <code>mongodb</code> (หลาย instance / อยู่รอด restart — ต้องตั้ง <code>MONGODB_URI</code>) — ไม่เกี่ยวกับ PostgreSQL</p>
<table><thead><tr><th>รายการ</th><th>ค่า</th></tr></thead><tbody>
<tr><td>database / collection</td><td>ตาม <code>MONGODB_URI</code> (เช่น <code>an22</code>) / <code>sessions</code> (connect-mongo)</td></tr>
<tr><td>เอกสาร 1 ชิ้น</td><td><code>_id</code> = session id (ค่าใน cookie <code>an22.sid</code> / <code>__Host-an22.sid</code> ที่เซ็นแล้ว) · <code>expires</code> = หมดอายุ · <code>session</code> = ข้อมูล session <b>แบบเข้ารหัส</b> ด้วย <code>SESSION_SECRET</code> (Web Crypto)</td></tr>
<tr><td>ข้อมูลใน session (หลังถอด)</td><td><code>shopToken</code> token ของผู้ใช้ร้าน · <code>platformToken</code> token ของผู้ดูแลแพลตฟอร์ม (ช่องแยก) · <code>createdAt</code> เวลา login · ค่า cookie ของ express-session</td></tr>
<tr><td>หมดอายุ</td><td>ไม่ใช้งานเกิน <code>SESSION_IDLE_HOURS</code> (ค่าเริ่ม 12) · นานสุด <code>SESSION_MAX_DAYS</code> (ค่าเริ่ม 7) นับจาก login — TTL index <code>{ expires: 1 }</code> <code>expireAfterSeconds: 0</code> ให้ MongoDB ลบเอง</td></tr>
<tr><td>เมื่อถอดรหัสไม่ได้</td><td>(เปลี่ยน <code>SESSION_SECRET</code> / ข้อมูลเสีย) ถือว่าไม่มี session → ผู้ใช้ login ใหม่</td></tr>
<tr><td>logout</td><td>an22-web ลบเอกสาร session ทิ้ง (api เป็น JWT stateless ไม่มี logout — token ที่หลุดจาก session ใช้ไม่ได้เพราะ browser ไม่เคยถือ)</td></tr>
</tbody></table>

<h3 id="a-migrations">ข ประวัติ migration</h3>
<p>ไฟล์ใน <code>an22-api/db/migration</code> — <code>afterMigrate.sql</code> (callback) รันหลัง migrate ทุกครั้ง: ให้ an22_app อ่าน <code>flyway_schema_history</code> ได้ (โหมด validate); ข้อมูลตัวอย่าง <code>db/seed</code> ใช้ใน dev / test เท่านั้น ไม่อยู่ในเอกสารนี้</p>
${migrationsTable}

<h3 id="a-build">ค การสร้างเอกสารนี้ใหม่</h3>
<ul>
<li>คำสั่ง <code>docs/db-manual/build.sh</code> (ใน repo an22-api): เปิด PostgreSQL ชั่วคราวใน docker → รัน <code>roles.sql</code> + Flyway migrate ของ <code>db/migration</code> → อ่าน catalog (<code>catalog.sql</code>) → สร้าง HTML + ER (Graphviz) → PDF ด้วย Chrome headless — ไม่แตะ DB อื่น, container ถูกลบเมื่อจบ</li>
<li>เพิ่ม / แก้ตาราง: เขียน migration ใหม่ → เพิ่มคำอธิบายใน <code>descriptions.mjs</code> (ตาราง, คอลัมน์, กลุ่ม, ref ที่ไม่มี FK, ขั้นตอนธุรกิจที่เกี่ยว) → <code>build.sh --check</code> ต้องผ่าน (ขาด / เกิน → exit 1) → <code>build.sh</code></li>
<li>ต้องมี docker (image <code>postgres:18.6</code>, <code>flyway/flyway:12.4.0</code>), Node 24, google-chrome, ฟอนต์ Noto Sans Thai</li>
</ul>

<h3 id="a-glossary">ง คำศัพท์</h3>
<table><tbody>
<tr><td><b>tenant</b></td><td>บริษัทลูกค้าของ SaaS 1 ราย = 1 แถวใน <code>dt_companies</code>; ทุกแถวของร้านมี <code>tenant_id</code></td></tr>
<tr><td><b>RLS</b></td><td>Row-Level Security ของ PostgreSQL — กรองแถวที่ query เห็นตาม policy</td></tr>
<tr><td><b>สาขา / คลัง</b></td><td>1 สาขา = 1 คลังสินค้า = 1 สถานประกอบการบนเอกสารภาษี</td></tr>
<tr><td><b>หน่วยฐาน / หน่วยแพ็ก</b></td><td>สต็อกนับด้วยหน่วยฐาน (เช่น ชิ้น); หน่วยแพ็ก (กล่อง = 4 ชิ้น) ใช้ขาย / รับได้ — สต็อกเดิน qty × factor</td></tr>
<tr><td><b>serial</b></td><td>หมายเลขเครื่อง 1 เครื่อง = 1 แถว; SKU คุม serial มีสต็อก = จำนวน serial ที่อยู่ในคลัง</td></tr>
<tr><td><b>ทุนมาตรฐาน / ทุนเฉลี่ย</b></td><td>ทุนมาตรฐาน (<code>dt_products.cost</code>) เป็นค่าเริ่ม / ประมาณการ; ทุนจริงคือทุนเฉลี่ยเคลื่อนที่ต่อสาขา (<code>dt_branch_stock.avg_cost</code>) หรือทุนของเครื่อง (serial)</td></tr>
<tr><td><b>COGS</b></td><td>ต้นทุนขาย ไม่รวม VAT บันทึกในบรรทัดบิลตอนขาย</td></tr>
<tr><td><b>บัตรสต็อก / movement</b></td><td>รายการเดินของสินค้าพร้อมยอดคงเหลือหลังรายการ (<code>dt_stock_movements</code>)</td></tr>
<tr><td><b>void</b></td><td>ยกเลิกบิลวันเดียวกัน — ของกลับคลัง บิลเปลี่ยนสถานะเป็น cancelled</td></tr>
<tr><td><b>ใบลดหนี้</b></td><td>คืนสินค้าตั้งแต่วันถัดจากวันขาย — คืนเงิน + กลับรายการ VAT; บิลเดิมไม่เปลี่ยน</td></tr>
<tr><td><b>ใบเปลี่ยน</b></td><td>เปลี่ยนเป็นตัวใหม่ของ SKU เดียวกัน ไม่มีเงิน มีแต่สต็อกเดิน</td></tr>
<tr><td><b>ใบกำกับอย่างย่อ / เต็มรูป</b></td><td>ใบเสร็จ POS ของร้านจด VAT เป็นใบกำกับอย่างย่อ; เต็มรูปออกตามคำขอพร้อมข้อมูลผู้ซื้อ</td></tr>
<tr><td><b>POS ID</b></td><td>หมายเลขเครื่อง POS ที่กรมสรรพากรอนุมัติ พิมพ์บนใบเสร็จ</td></tr>
<tr><td><b>กะ / X / Z report</b></td><td>กะ = ช่วงที่แคชเชียร์ 1 คนรับผิดชอบลิ้นชัก 1 ใบ; X = รายงานระหว่างกะ, Z = รายงานปิดกะ</td></tr>
<tr><td><b>blind count</b></td><td>ปิดกะโดยแคชเชียร์นับเงินโดยไม่เห็นยอดที่ระบบคาด</td></tr>
<tr><td><b>snapshot</b></td><td>ค่าที่ copy ลงเอกสาร ณ ตอนออก ไม่อ่านจาก master ทีหลัง</td></tr>
<tr><td><b>append-only</b></td><td>ตารางที่เพิ่มแถวได้อย่างเดียว ไม่แก้ / ไม่ลบ</td></tr>
<tr><td><b>BFF</b></td><td>Backend for Frontend — an22-web ถือ session แทน browser</td></tr>
</tbody></table>
</section>`;
