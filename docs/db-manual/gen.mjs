// สร้าง build/db-manual.html จาก build/schema.json (catalog ของ DB จริง) + descriptions.mjs / content.mjs (คำอธิบายที่เขียนเอง)
// + SQL ใน src/main/java (CRUD matrix) — node gen.mjs [--check]
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { instance } from './tools/node_modules/@viz-js/viz/dist/viz.js';
import { domains, tables as desc, logicalRefs, packages, crudExtra, flows } from './descriptions.mjs';
import * as content from './content.mjs';

const dir = path.dirname(fileURLToPath(import.meta.url));
const repo = path.resolve(dir, '../..');
const schema = JSON.parse(fs.readFileSync(path.join(dir, 'build/schema.json'), 'utf8'));
const checkOnly = process.argv.includes('--check');

const STAMP = ['created_at', 'created_by', 'created_by_kind', 'updated_at', 'updated_by', 'updated_by_kind'];
const all = schema.tables.filter((t) => t.name !== 'flyway_schema_history');
const byName = Object.fromEntries(all.map((t) => [t.name, t]));
const domainOf = {};
for (const d of domains) for (const t of d.tables) domainOf[t] = d;

// ---------------------------------------------------------------------------------------------
// ตรวจคำอธิบายครบ / ไม่เกิน
// ---------------------------------------------------------------------------------------------
const problems = [];
for (const t of all) {
  if (!domainOf[t.name]) problems.push(`${t.name}: ไม่อยู่ในกลุ่มใดใน domains`);
  const d = desc[t.name];
  if (!d) {
    problems.push(`${t.name}: ไม่มีคำอธิบายตาราง`);
    continue;
  }
  for (const key of ['title', 'purpose', 'lifecycle']) if (!d[key]) problems.push(`${t.name}: ไม่มี ${key}`);
  for (const c of t.columns) {
    if (!STAMP.includes(c.name) && !d.columns?.[c.name]) problems.push(`${t.name}.${c.name}: ไม่มีคำอธิบายคอลัมน์`);
  }
  for (const c of Object.keys(d.columns ?? {})) {
    if (!t.columns.some((x) => x.name === c)) problems.push(`${t.name}.${c}: มีคำอธิบายแต่ไม่มีคอลัมน์นี้ใน DB`);
  }
}
for (const name of Object.keys(desc)) if (!byName[name]) problems.push(`${name}: มีคำอธิบายแต่ไม่มีตารางนี้ใน DB`);
for (const d of domains) {
  for (const t of d.tables) if (!byName[t]) problems.push(`domains ${d.id}: ไม่มีตาราง ${t} ใน DB`);
  for (const p of d.diagrams ?? []) for (const t of p.tables) if (!d.tables.includes(t)) problems.push(`domains ${d.id}: ภาพมี ${t} ที่ไม่อยู่ในกลุ่ม`);
}
for (const r of logicalRefs) {
  for (const end of [r.from, r.to]) {
    const [t, c] = end.split('.');
    if (!byName[t]?.columns.some((x) => x.name === c)) problems.push(`logicalRefs: ไม่มีคอลัมน์ ${end}`);
  }
}
for (const f of flows) {
  for (const s of f.steps) for (const t of [s.table].flat()) if (!byName[t]) problems.push(`flows ${f.id}: ไม่มีตาราง ${t}`);
}
if (problems.length) {
  console.error(`คำอธิบายไม่ครบ / ไม่ตรง DB (${problems.length}):\n  ` + problems.join('\n  '));
  if (checkOnly) process.exit(1);
} else {
  console.log(`คำอธิบายครบ: ${all.length} ตาราง, ${all.reduce((n, t) => n + t.columns.length, 0)} คอลัมน์`);
}
if (checkOnly) process.exit(0);

// ---------------------------------------------------------------------------------------------
// helper
// ---------------------------------------------------------------------------------------------
const esc = (s) => String(s ?? '').replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
// ข้อความที่เขียนเอง: `code`, **ตัวหนา**, รหัสการตัดสินใจในวงเล็บ (E12, D3, S8 …) เป็นตัวเล็ก
const md = (s) =>
  esc(s)
    .replace(/`([^`]+)`/g, '<code>$1</code>')
    .replace(/\*\*([^*]+)\*\*/g, '<b>$1</b>')
    .replace(/ \(((?:[A-Z]{1,2}\d+[a-z]?(?:–[A-Z]{0,2}\d+)?)(?:, (?:[A-Z]{1,2}\d+[a-z]?(?:–[A-Z]{0,2}\d+)?))*)\)/g, ' <span class="ref">$1</span>');
const list = (v) => (Array.isArray(v) ? v : v ? [v] : []);
const short = (n) => n.replace(/^dt_/, '');
const anchor = (n) => `t-${short(n)}`;
const tlink = (n) => `<a href="#${anchor(n)}"><code>${esc(n)}</code></a>`;
const shortType = (t) =>
  t.replace('timestamp with time zone', 'timestamptz').replace('boolean', 'bool').replace('integer', 'int').replace('smallint', 'int2');

const fkOf = (t) => t.constraints.filter((k) => k.type === 'f');
const pkOf = (t) => t.constraints.find((k) => k.type === 'p')?.columns ?? [];
// คอลัมน์ที่บอกความสัมพันธ์: FK รวม (tenant_id, x_id) → (tenant_id, id) = x_id; FK ที่มีแค่ tenant_id (ทุกตาราง → dt_companies) = null
const fkMain = (k) => {
  const i = k.refColumns.findIndex((c) => c !== 'tenant_id');
  if (i < 0 || k.columns[i] === 'tenant_id') return null;
  return { col: k.columns[i], refCol: k.refColumns[i] };
};
const realFks = (t) =>
  fkOf(t)
    .map((k) => ({ k, m: fkMain(k) }))
    .filter((x) => x.m);
const enumOf = (t) => {
  const out = {};
  for (const k of t.constraints.filter((x) => x.type === 'c')) {
    for (const m of k.definition.matchAll(/\((\w+) = ANY \(\(?ARRAY\[([^\]]+)\]/g)) {
      out[m[1]] = [...m[2].matchAll(/'([^']*)'/g)].map((x) => x[1]);
    }
  }
  return out;
};
const uniqueCols = (t) => {
  const s = new Set();
  for (const k of t.constraints.filter((x) => x.type === 'u')) for (const c of k.columns) if (c !== 'tenant_id') s.add(c);
  for (const i of t.indexes.filter((x) => x.unique && !x.constraint)) {
    const m = i.definition.match(/ USING \w+ \((.*)\)( WHERE .*)?$/);
    if (m) for (const c of m[1].split(',')) s.add(c.trim().replace(/^(lower|upper)\((\w+)\)$/, '$2'));
  }
  s.delete('tenant_id');
  return s;
};
const isAppendOnly = (t) => t.triggers.some((g) => g.definition.includes("'append_only'"));
const grantText = (privs) =>
  ['SELECT', 'INSERT', 'UPDATE', 'DELETE']
    .filter((p) => (privs ?? []).includes(p))
    .map((p) => p[0])
    .join('');

// ---------------------------------------------------------------------------------------------
// ER diagram (Graphviz) — ข้อความในภาพเป็น ASCII ล้วน (Graphviz วัดความกว้างอักษรไทยไม่ได้) ชื่อไทยอยู่ใน caption
// ---------------------------------------------------------------------------------------------
const viz = await instance();
const FONT = 'Helvetica';
const q = (s) => `"${String(s).replace(/"/g, '\\"')}"`;

function tableNode(t, d) {
  const pk = pkOf(t);
  const fkCols = new Set(realFks(t).map((x) => x.m.col));
  const refCols = new Set(logicalRefs.filter((r) => r.from.startsWith(t.name + '.')).map((r) => r.from.split('.')[1]));
  const uq = uniqueCols(t);
  const rows = t.columns
    .filter((c) => !STAMP.includes(c.name))
    .map((c) => {
      const keys = [pk.includes(c.name) && 'PK', fkCols.has(c.name) && 'FK', refCols.has(c.name) && 'ref', uq.has(c.name) && !pk.includes(c.name) && 'UQ']
        .filter(Boolean)
        .join(' ');
      const name = pk.includes(c.name) ? `<B>${esc(c.name)}</B>` : esc(c.name);
      const opt = c.notNull ? '' : ' <FONT COLOR="#8a8b93">?</FONT>';
      // Graphviz ไม่รับ <FONT> ว่าง (label ทั้งกรอบพัง) — ช่องว่างใส่ไม่มี FONT
      const keyCell = keys ? `<FONT COLOR="#0b57d0">${keys}</FONT>` : ' ';
      return `<TR><TD ALIGN="LEFT" PORT="${c.name}">${name}${opt}</TD><TD ALIGN="LEFT"><FONT COLOR="#5d5e67">${esc(shortType(c.type))}</FONT></TD><TD ALIGN="LEFT" PORT="${c.name}_out">${keyCell}</TD></TR>`;
    })
    .join('');
  const stamps = t.columns.filter((c) => STAMP.includes(c.name)).length;
  const foot = stamps ? `<TR><TD ALIGN="LEFT" COLSPAN="3"><FONT COLOR="#8a8b93" POINT-SIZE="7.5">+ ${stamps} stamp columns</FONT></TD></TR>` : '';
  return `${q(t.name)} [shape=plain URL="#${anchor(t.name)}" label=<<TABLE BORDER="1" CELLBORDER="0" CELLSPACING="0" CELLPADDING="1.5" COLOR="${d.color}"><TR><TD COLSPAN="3" BGCOLOR="${d.color}" CELLPADDING="3"><FONT COLOR="#ffffff"><B>${esc(t.name)}</B></FONT></TD></TR>${rows}${foot}</TABLE>>];`;
}
const ghostNode = (n) => `${q(n)} [shape=box style="rounded,dashed" color="#9a9ba3" fontcolor="#5d5e67" fontsize=9 URL="#${anchor(n)}"];`;

function domainDot(tableNames) {
  const inside = new Set(tableNames);
  const ghosts = new Set();
  const edges = [];
  // ลูกอยู่ซ้าย แม่อยู่ขวา (rankdir=LR) — เส้นออกจากขวาของแถวลูก (ช่อง key, port <col>_out) เข้าซ้ายของแถวแม่ ไม่ให้วนรอบกรอบ
  const end = (n, col) => (inside.has(n) ? `${q(n)}:${q(col)}:w` : (ghosts.add(n), `${q(n)}:w`));
  for (const name of tableNames) {
    const t = byName[name];
    for (const { k, m } of realFks(t)) {
      const head = t.columns.find((c) => c.name === m.col)?.notNull ? 'teetee' : 'teeodot';
      edges.push(`${q(name)}:${q(m.col + '_out')}:e -> ${end(k.refTable, m.refCol)} [dir=both arrowtail=crowodot arrowhead=${head}];`);
    }
    for (const r of logicalRefs.filter((x) => x.from.startsWith(name + '.'))) {
      const [rt, rc] = r.to.split('.');
      edges.push(`${q(name)}:${q(r.from.split('.')[1] + '_out')}:e -> ${end(rt, rc)} [dir=both arrowtail=crowodot arrowhead=teeodot style=dashed color="#8a8b93"];`);
    }
  }
  return `digraph G {
  graph [rankdir=LR fontname="${FONT}" nodesep=0.22 ranksep=0.5 pad=0.05 bgcolor="transparent"];
  node [fontname="${FONT}" fontsize=8.5];
  edge [color="#5d5e67" arrowsize=0.65 penwidth=0.9];
  ${tableNames.map((n) => tableNode(byName[n], domainOf[n])).join('\n  ')}
  ${[...ghosts].map(ghostNode).join('\n  ')}
  ${edges.join('\n  ')}
}`;
}

function overviewDot() {
  const clusters = domains
    .map(
      (d) => `subgraph cluster_${d.id} {
    label=${q(d.id)} fontsize=15 fontcolor="${d.color}" color="${d.color}" style="rounded" penwidth=1.3;
    ${d.tables.map((n) => `${q(n)} [label=${q(short(n))} URL="#${anchor(n)}" style="filled,rounded" fillcolor="${d.soft}" color="${d.color}"];`).join('\n    ')}
  }`,
    )
    .join('\n  ');
  const edges = new Set();
  // เฉพาะ FK — อ้างอิงที่ไม่มี FK (เส้นประ) ทำให้ภาพรวมอ่านไม่ออก จึงมีแค่ในภาพของแต่ละกลุ่ม
  for (const t of all) for (const { k } of realFks(t)) if (k.refTable !== t.name) edges.add(`${q(t.name)} -> ${q(k.refTable)};`);
  return `digraph G {
  graph [rankdir=LR fontname="${FONT}" nodesep=0.1 ranksep=0.7 pad=0.05 bgcolor="transparent" newrank=true];
  node [shape=box fontname="${FONT}" fontsize=10 height=0.28 margin="0.07,0.02"];
  edge [color="#5d5e67" arrowsize=0.5 penwidth=0.7 dir=both arrowtail=crowodot arrowhead=teeodot];
  ${clusters}
  ${[...edges].join('\n  ')}
}`;
}

const svgOf = (dot, name) => {
  fs.writeFileSync(path.join(dir, `build/er-${name}.dot`), dot); // ไว้ตรวจ / เปิดด้วย Graphviz เครื่องอื่น
  const r = viz.render(dot, { format: 'svg' });
  for (const e of r.errors) console.warn(`ER ${name}: ${e.level} ${e.message}`);
  if (r.status !== 'success') throw new Error(r.errors.map((e) => e.message).join('\n'));
  // ลิงก์ภายในเอกสาร: Graphviz ใส่ xlink:href + target — ใช้แค่ href
  return r.output
    .replace(/^[\s\S]*?(<svg)/, '$1')
    .replace(/<!--[\s\S]*?-->/g, '')
    .replace(/<title>[^<]*<\/title>/g, '')
    // Graphviz วัดความกว้างด้วย metric ของ Helvetica — ใช้ฟอนต์ที่ metric เท่ากัน (Liberation Sans / Arial) ข้อความจึงไม่ล้นช่อง
    .replace(/font-family="Helvetica,sans-Serif"/g, 'font-family="Liberation Sans, Arial, Helvetica, sans-serif"');
};

// ---------------------------------------------------------------------------------------------
// CRUD matrix จาก SQL ใน src/main/java
// ---------------------------------------------------------------------------------------------
function scanCrud() {
  const root = path.join(repo, 'src/main/java/com/an22/api');
  const cells = {}; // table → package → Set('C' | 'R*' …)
  const add = (table, pkg, op) => {
    if (!byName[table]) return;
    ((cells[table] ??= {})[pkg] ??= new Set()).add(op);
  };
  const walk = (p) =>
    fs.statSync(p).isDirectory() ? fs.readdirSync(p).flatMap((f) => walk(path.join(p, f))) : p.endsWith('.java') ? [p] : [];
  for (const file of walk(root)) {
    const pkg = path.relative(root, file).split(path.sep)[0];
    // comment ของ Java ไม่ใช่การใช้งาน
    const src = fs
      .readFileSync(file, 'utf8')
      .replace(/\/\*[\s\S]*?\*\//g, '')
      .replace(/^\s*\/\/.*$/gm, '');
    for (const m of src.matchAll(/INSERT\s+INTO\s+(dt_\w+)/g)) add(m[1], pkg, 'C');
    for (const m of src.matchAll(/INSERT\s+INTO\s+(dt_\w+)[^;]{0,900}?ON\s+CONFLICT[^;]{0,300}?DO\s+UPDATE/g)) add(m[1], pkg, 'U');
    for (const m of src.matchAll(/(?<!(?:FOR|DO)\s)UPDATE\s+(dt_\w+)/g)) add(m[1], pkg, 'U');
    for (const m of src.matchAll(/DELETE\s+FROM\s+(dt_\w+)/g)) add(m[1], pkg, 'D');
    for (const m of src.matchAll(/(?<!DELETE\s)(?:FROM|JOIN)\s+(dt_\w+)/g)) add(m[1], pkg, 'R');
  }
  // SQL ที่ประกอบชื่อตารางตอนรัน (scan ไม่เห็น) — ระบุใน descriptions.mjs
  const tenantTables = all.filter((t) => t.columns.some((c) => c.name === 'tenant_id')).map((t) => t.name);
  for (const x of crudExtra) {
    const targets = x.tables === 'tenant' ? tenantTables.filter((t) => !(x.minus ?? []).includes(t)).concat(x.plus ?? []) : x.tables;
    for (const t of targets) add(t, x.pkg, x.op + '*');
  }
  return cells;
}

// ---------------------------------------------------------------------------------------------
// ส่วนของเอกสาร
// ---------------------------------------------------------------------------------------------
const latest = schema.migrations.at(-1);

function erSection() {
  let html = `<section class="chapter" id="ch-er"><h2>ER diagram</h2>
  <p class="lead">ภาพสร้างจาก catalog ของ DB ที่ migrate ถึง V${esc(latest.version)} — ใน PDF กดชื่อตารางในภาพเพื่อไปหน้าคำอธิบายได้</p>
  ${content.erLegend}</section>
  <section class="er-pages">
    <figure class="er" id="er-overview"><div class="svg">${svgOf(overviewDot(), 'overview')}</div>
    <figcaption>ภาพรวม ${all.length} ตาราง — กรอบสี = กลุ่ม (A–G ตามหัวข้อ 1.3), เส้น = FK (ตีนกาอยู่ฝั่งลูก); ไม่วาดเส้น <code>tenant_id</code> → <code>dt_companies</code> ที่ทุกตารางมี และอ้างอิงแบบไม่มี FK (ดูในภาพของแต่ละกลุ่ม)</figcaption></figure>`;
  for (const d of domains) {
    const parts = d.diagrams ?? [{ tables: d.tables }];
    parts.forEach((p, i) => {
      html += `<figure class="er" id="er-${d.id}${parts.length > 1 ? i + 1 : ''}"><div class="svg">${svgOf(domainDot(p.tables), d.id + (parts.length > 1 ? i + 1 : ''))}</div>
      <figcaption>กลุ่ม ${esc(d.id)} ${esc(d.title)}${parts.length > 1 ? ` (${i + 1}/${parts.length}${p.title ? ' ' + esc(p.title) : ''})` : ''} — ${p.tables
        .map((n) => esc(desc[n]?.title ?? n))
        .join(' · ')}</figcaption></figure>`;
    });
  }
  return html + '</section>';
}

function columnRows(t) {
  const d = desc[t.name] ?? {};
  const pk = pkOf(t);
  const enums = enumOf(t);
  const uq = uniqueCols(t);
  const fkTo = {};
  for (const k of fkOf(t)) {
    const m = fkMain(k);
    if (m) fkTo[m.col] = k.refTable;
    else if (k.columns.length === 1) fkTo[k.columns[0]] = k.refTable;
  }
  const logical = Object.fromEntries(logicalRefs.filter((r) => r.from.startsWith(t.name + '.')).map((r) => [r.from.split('.')[1], r]));
  const stampCols = t.columns.filter((c) => STAMP.includes(c.name) && !d.columns?.[c.name]);
  const rows = [];
  let n = 0;
  for (const c of t.columns) {
    if (stampCols.includes(c)) {
      if (c === stampCols[0]) {
        rows.push(
          `<tr class="stamp"><td></td><td colspan="6"><code>${stampCols.map((x) => x.name).join('</code>, <code>')}</code> — คอลัมน์ stamp มาตรฐาน (trigger ตั้งให้, ดูหัวข้อ <a href="#s-stamp">1.4</a>)${isAppendOnly(t) ? ' · ตาราง append-only จึงมีแค่ <code>created_*</code>' : ''}</td></tr>`,
        );
      }
      continue;
    }
    n++;
    const keys = [
      pk.includes(c.name) && '<span class="k pk">PK</span>',
      fkTo[c.name] && '<span class="k fk">FK</span>',
      logical[c.name] && '<span class="k ref">ref</span>',
      uq.has(c.name) && !pk.includes(c.name) && '<span class="k uq">UQ</span>',
    ]
      .filter(Boolean)
      .join('');
    let text = md(d.columns?.[c.name] ?? '');
    if (fkTo[c.name]) text += ` <span class="to">→ <a href="#${anchor(fkTo[c.name])}">${esc(fkTo[c.name])}</a></span>`;
    if (logical[c.name]) {
      const r = logical[c.name];
      text += ` <span class="to">⇢ <a href="#${anchor(r.to.split('.')[0])}">${esc(r.to)}</a> (ไม่มี FK${r.note ? ' — ' + md(r.note) : ''})</span>`;
    }
    if (enums[c.name]) text += `<div class="enum">ค่าที่ใช้ได้: ${enums[c.name].map((v) => `<code>${esc(v)}</code>`).join(' ')}</div>`;
    const def = c.identity ? '<i>identity</i>' : c.default ? `<code>${esc(c.default.replace(/::[a-z ]+(\[\])?/g, ''))}</code>` : '';
    rows.push(
      `<tr><td class="c">${n}</td><td><code class="col">${esc(c.name)}</code></td><td><code>${esc(shortType(c.type))}</code></td><td class="c">${c.notNull ? '' : '✓'}</td><td>${def}</td><td class="c keys">${keys}</td><td>${text}</td></tr>`,
    );
  }
  return rows.join('');
}

function constraintList(t) {
  const items = [];
  const idx = (def) => esc(def.replace(/^CREATE (UNIQUE )?INDEX \w+ ON public\.\w+ USING /, ''));
  const pk = t.constraints.find((k) => k.type === 'p');
  if (pk) items.push(`<li><b>PK</b> (${pk.columns.map(esc).join(', ')})</li>`);
  for (const k of t.constraints.filter((x) => x.type === 'u')) items.push(`<li><b>UNIQUE</b> (${k.columns.map(esc).join(', ')})</li>`);
  for (const i of t.indexes.filter((x) => x.unique && !x.constraint)) items.push(`<li><b>UNIQUE index</b> <code>${esc(i.name)}</code> ${idx(i.definition)}</li>`);
  for (const k of t.constraints.filter((x) => x.type === 'x')) items.push(`<li><b>EXCLUDE</b> ${esc(k.definition.replace(/^EXCLUDE /, ''))}</li>`);
  for (const k of fkOf(t))
    items.push(`<li><b>FK</b> (${k.columns.map(esc).join(', ')}) → <a href="#${anchor(k.refTable)}">${esc(k.refTable)}</a> (${k.refColumns.map(esc).join(', ')}) ${esc(k.definition.match(/ON DELETE \w+/)?.[0] ?? '')}</li>`);
  for (const k of t.constraints.filter((x) => x.type === 'c' && !/_by_kind_check$/.test(x.name)))
    items.push(`<li><b>CHECK</b> <code>${esc(k.definition.replace(/^CHECK /, ''))}</code></li>`);
  for (const i of t.indexes.filter((x) => !x.unique)) items.push(`<li><b>index</b> <code>${esc(i.name)}</code> ${idx(i.definition)}</li>`);
  const trig = t.triggers.map((g) => (g.definition.includes("'append_only'") ? 'stamp แบบ append-only (BEFORE INSERT)' : 'stamp (BEFORE INSERT OR UPDATE)'));
  const rls = t.rls
    ? `RLS${t.forceRls ? ' + FORCE' : ''}: ${t.policies.map((p) => `<code>${esc(p.using)}</code>`).join(', ')}`
    : 'ไม่อยู่ใต้ RLS (ตาราง platform)';
  const g = `สิทธิ์ an22_app <b>${grantText(t.grants.an22_app) || '—'}</b> · an22_platform <b>${grantText(t.grants.an22_platform) || '—'}</b>`;
  return `<div class="cons-wrap"><ul class="cons">${items.join('')}</ul><p class="meta">${trig.length ? 'trigger: ' + trig.join(', ') + ' · ' : ''}${rls} · ${g}</p></div>`;
}

function tableSection(t) {
  const d = desc[t.name] ?? {};
  const dom = domainOf[t.name];
  const parents = [...new Set(realFks(t).map((x) => x.k.refTable))].filter((p) => p !== t.name);
  const children = all.filter((x) => x.name !== t.name && realFks(x).some((f) => f.k.refTable === t.name)).map((x) => x.name);
  const rel = [parents.length && `อ้างถึง ${parents.map(tlink).join(', ')}`, children.length && `ถูกอ้างโดย ${children.map(tlink).join(', ')}`].filter(Boolean);
  const block = (label, v) => (list(v).length ? `<div class="blk"><b>${label}</b><ul>${list(v).map((x) => `<li>${md(x)}</li>`).join('')}</ul></div>` : '');
  return `<article class="table" id="${anchor(t.name)}">
  <h4><span class="dom" style="background:${dom?.color}">${esc(dom?.id ?? '?')}</span><code>${esc(t.name)}</code> ${esc(d.title ?? '')}</h4>
  <p class="purpose">${md(d.purpose ?? '')}</p>
  ${block('วงจรชีวิตของแถว', d.lifecycle)}${block('กฎสำคัญ', d.rules)}${block('ค่าที่ไม่ได้เก็บ (API คำนวณตอนอ่าน)', d.derived)}
  ${rel.length ? `<p class="meta">${rel.join(' · ')}</p>` : ''}
  <table class="dict"><thead><tr><th class="c">#</th><th>คอลัมน์</th><th>ชนิด</th><th class="c">null</th><th>ค่าเริ่มต้น</th><th class="c">key</th><th>คำอธิบาย</th></tr></thead>
  <tbody>${columnRows(t)}</tbody></table>
  ${constraintList(t)}
  </article>`;
}

function dictSection() {
  let html = `<section class="chapter" id="ch-dict"><h2>คำอธิบายตารางและ data dictionary</h2>${content.dictLegend}`;
  for (const d of domains) {
    html += `<h3 id="d-${d.id}" class="domain"><span class="dom" style="background:${d.color}">${d.id}</span> ${esc(d.title)}</h3><p>${md(d.intro)}</p>`;
    for (const n of d.tables) html += tableSection(byName[n]);
  }
  return html + '</section>';
}

function flowSection() {
  const op = { I: 'เพิ่ม', U: 'แก้', D: 'ลบ', R: 'อ่าน', L: 'ล็อก' };
  let html = `<section class="chapter" id="ch-flow"><h2>ขั้นตอนธุรกิจ → ตาราง</h2>${content.flowIntro}`;
  for (const f of flows) {
    html += `<div class="flow-block"><h3 id="f-${f.id}">${esc(f.title)}</h3>${f.intro ? `<p>${md(f.intro)}</p>` : ''}
    <table class="flow"><thead><tr><th class="c">ขั้น</th><th>ตาราง</th><th class="c">ทำ</th><th>รายละเอียด</th></tr></thead><tbody>
    ${f.steps
      .map(
        (s, i) =>
          `<tr><td class="c">${i + 1}</td><td>${[s.table].flat().map(tlink).join('<br>')}</td><td class="c"><span class="op op-${s.op}">${op[s.op]}</span></td><td>${md(s.text)}</td></tr>`,
      )
      .join('')}
    </tbody></table>${list(f.notes)
      .map((x) => `<p class="note">${md(x)}</p>`)
      .join('')}</div>`;
  }
  return html + content.docNumbers + '</section>';
}

function crudSection() {
  const cells = scanCrud();
  const pkgs = Object.keys(packages).filter((p) => Object.values(cells).some((c) => c[p]));
  const unknown = [...new Set(Object.values(cells).flatMap((c) => Object.keys(c)))].filter((p) => !packages[p]);
  if (unknown.length) console.warn(`CRUD: package ที่ไม่มีชื่อใน packages: ${unknown.join(', ')}`);
  const head = pkgs.map((p) => `<th class="v"><div>${esc(p)}</div></th>`).join('');
  const rows = domains
    .flatMap((d) =>
      d.tables.map((n) => {
        const c = cells[n] ?? {};
        const td = pkgs
          .map((p) => {
            const ops = [...(c[p] ?? [])];
            const s = ['C', 'R', 'U', 'D']
              .filter((o) => ops.some((x) => x[0] === o))
              .map((o) => (ops.includes(o) ? o : `<span class="dyn">${o}*</span>`))
              .join('');
            return `<td class="c crud${s ? '' : ' none'}">${s}</td>`;
          })
          .join('');
        return `<tr><td class="tn"><span class="dot" style="background:${d.color}"></span><a href="#${anchor(n)}">${esc(short(n))}</a></td>${td}</tr>`;
      }),
    )
    .join('');
  return `<section class="chapter landscape" id="ch-crud"><h2>CRUD matrix</h2>
  ${content.crudIntro}
  <table class="crud"><thead><tr><th>ตาราง</th>${head}</tr></thead><tbody>${rows}</tbody></table>
  <p class="note"><b>package</b> — ${pkgs.map((p) => `<code>${esc(p)}</code> ${esc(packages[p])}`).join(' · ')}</p>
  ${crudExtra.map((x) => `<p class="note">* <code>${esc(x.pkg)}</code> ${md(x.note)}</p>`).join('')}
  </section>`;
}

function grantsTable() {
  const rows = domains
    .flatMap((d) =>
      d.tables.map((n) => {
        const t = byName[n];
        const note = [isAppendOnly(t) && 'append-only', content.grantNotes[n] && md(content.grantNotes[n])].filter(Boolean).join(' · ');
        return `<tr><td><span class="dot" style="background:${d.color}"></span>${tlink(n)}</td><td class="c">${t.rls ? (t.forceRls ? 'ENABLE + FORCE' : 'ENABLE') : '—'}</td><td class="c"><b>${grantText(t.grants.an22_app) || '—'}</b></td><td class="c"><b>${grantText(t.grants.an22_platform) || '—'}</b></td><td>${note}</td></tr>`;
      }),
    )
    .join('');
  return `<table class="grants"><thead><tr><th>ตาราง</th><th class="c">RLS</th><th class="c">an22_app</th><th class="c">an22_platform</th><th>หมายเหตุ</th></tr></thead><tbody>${rows}</tbody></table>`;
}

const migrationsTable = `<table><thead><tr><th class="c">version</th><th>ไฟล์</th><th>เนื้อหา</th></tr></thead><tbody>${schema.migrations
  .map((m) => `<tr><td class="c">V${esc(m.version)}</td><td><code>${esc(m.script)}</code></td><td>${md(content.migrationNotes[m.version] ?? m.description)}</td></tr>`)
  .join('')}</tbody></table>`;

const domainSummary = `<table><thead><tr><th class="c">กลุ่ม</th><th>ชื่อ</th><th class="c">จำนวน</th><th>ตาราง</th></tr></thead><tbody>${domains
  .map(
    (d) =>
      `<tr><td class="c"><span class="dom" style="background:${d.color}">${d.id}</span></td><td><a href="#d-${d.id}">${esc(d.title)}</a></td><td class="c">${d.tables.length}</td><td>${d.tables.map((n) => `<a href="#${anchor(n)}">${esc(short(n))}</a>`).join(', ')}</td></tr>`,
  )
  .join('')}<tr><td></td><td><b>รวม</b></td><td class="c"><b>${all.length}</b></td><td>+ <code>flyway_schema_history</code> ของ Flyway (ประวัติ migration)</td></tr></tbody></table>`;

const css = fs.readFileSync(path.join(dir, 'style.css'), 'utf8');
const html = `<!doctype html>
<html lang="th"><head><meta charset="utf-8"><title>An22 — เอกสารฐานข้อมูล</title><style>${css}</style></head><body>
<div class="cover">
  <div class="kicker">An22 · เอกสารสำหรับ System Analyst</div>
  <h1>เอกสารฐานข้อมูล</h1>
  <p class="sub">ER diagram · data dictionary · คำอธิบายตาราง · ขั้นตอนธุรกิจ → ตาราง · CRUD matrix · ความปลอดภัย</p>
  <div class="meta">
    <div><b>ฐานข้อมูล</b> PostgreSQL ${esc(schema.serverVersion.split(' ')[0])} · schema <code>public</code> · ${all.length} ตาราง</div>
    <div><b>Migration</b> Flyway V1–V${esc(latest.version)} (<code>an22-api/db/migration</code>)</div>
    <div><b>สร้างเมื่อ</b> ${esc(schema.generatedAt)} จาก catalog ของ DB ที่ migrate จริง (<code>docs/db-manual/build.sh</code>)</div>
  </div>
</div>
${content.toc({ domains, flows })}
${content.overview({ domainSummary, md })}
${erSection()}
${dictSection()}
${flowSection()}
${crudSection()}
${content.security({ grantsTable: grantsTable(), schema, md })}
${content.appendix({ migrationsTable, md })}
</body></html>`;

fs.writeFileSync(path.join(dir, 'build/db-manual.html'), html);
console.log(`build/db-manual.html (${Math.round(html.length / 1024)} kB)`);
