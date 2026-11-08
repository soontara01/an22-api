-- อ่านโครงสร้าง schema public จาก pg_catalog → JSON 1 ก้อน (build.sh: psql -At -f catalog.sql > build/schema.json)
-- อ่านอย่างเดียว — รันกับ DB ที่ migrate ครบ (build.sh สร้าง DB ชั่วคราวให้)
WITH tbl AS (
  SELECT c.oid, c.relname, c.relrowsecurity, c.relforcerowsecurity
  FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
  WHERE n.nspname = 'public' AND c.relkind IN ('r', 'p')
)
SELECT json_build_object(
  'generatedAt', to_char(now() AT TIME ZONE 'Asia/Bangkok', 'YYYY-MM-DD HH24:MI'),
  'serverVersion', current_setting('server_version'),
  'migrations', (
    SELECT coalesce(json_agg(json_build_object('version', version, 'description', description, 'script', script,
      'installedOn', to_char(installed_on, 'YYYY-MM-DD')) ORDER BY installed_rank), '[]')
    FROM flyway_schema_history WHERE version IS NOT NULL
  ),
  'extensions', (
    SELECT json_agg(json_build_object('name', extname, 'version', extversion) ORDER BY extname)
    FROM pg_extension WHERE extname <> 'plpgsql'
  ),
  'functions', (
    SELECT coalesce(json_agg(json_build_object('name', p.proname, 'args', pg_get_function_arguments(p.oid),
      'returns', pg_get_function_result(p.oid), 'language', l.lanname, 'body', p.prosrc) ORDER BY p.proname), '[]')
    FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace JOIN pg_language l ON l.oid = p.prolang
    LEFT JOIN pg_depend d ON d.objid = p.oid AND d.deptype = 'e'
    WHERE n.nspname = 'public' AND d.objid IS NULL
  ),
  'roles', (
    SELECT json_agg(json_build_object('name', rolname, 'login', rolcanlogin, 'bypassRls', rolbypassrls,
      'config', coalesce(r.rolconfig, '{}')) ORDER BY rolname)
    FROM pg_roles r WHERE rolname IN ('an22_owner', 'an22_app', 'an22_platform')
  ),
  'tables', (
    SELECT json_agg(json_build_object(
      'name', t.relname,
      'rls', t.relrowsecurity,
      'forceRls', t.relforcerowsecurity,
      'owner', pg_get_userbyid((SELECT relowner FROM pg_class WHERE oid = t.oid)),
      'columns', (
        SELECT json_agg(json_build_object(
          'name', a.attname,
          'type', format_type(a.atttypid, a.atttypmod),
          'notNull', a.attnotnull,
          'default', pg_get_expr(ad.adbin, ad.adrelid),
          'identity', a.attidentity <> ''
        ) ORDER BY a.attnum)
        FROM pg_attribute a LEFT JOIN pg_attrdef ad ON ad.adrelid = a.attrelid AND ad.adnum = a.attnum
        WHERE a.attrelid = t.oid AND a.attnum > 0 AND NOT a.attisdropped
      ),
      'constraints', (
        SELECT coalesce(json_agg(json_build_object(
          'name', k.conname,
          'type', k.contype,
          'columns', (SELECT json_agg(a.attname ORDER BY u.ord) FROM unnest(k.conkey) WITH ORDINALITY u(attnum, ord)
                      JOIN pg_attribute a ON a.attrelid = k.conrelid AND a.attnum = u.attnum),
          'refTable', CASE WHEN k.contype = 'f' THEN k.confrelid::regclass::text END,
          'refColumns', CASE WHEN k.contype = 'f' THEN (
                      SELECT json_agg(a.attname ORDER BY u.ord) FROM unnest(k.confkey) WITH ORDINALITY u(attnum, ord)
                      JOIN pg_attribute a ON a.attrelid = k.confrelid AND a.attnum = u.attnum) END,
          'definition', pg_get_constraintdef(k.oid)
        ) ORDER BY k.contype, k.conname), '[]')
        FROM pg_constraint k WHERE k.conrelid = t.oid AND k.contype IN ('p', 'f', 'u', 'c', 'x')
      ),
      'indexes', (
        SELECT coalesce(json_agg(json_build_object(
          'name', ic.relname,
          'unique', i.indisunique,
          'primary', i.indisprimary,
          'constraint', EXISTS (SELECT 1 FROM pg_constraint k WHERE k.conindid = i.indexrelid),
          'partial', i.indpred IS NOT NULL,
          'definition', pg_get_indexdef(i.indexrelid)
        ) ORDER BY ic.relname), '[]')
        FROM pg_index i JOIN pg_class ic ON ic.oid = i.indexrelid WHERE i.indrelid = t.oid
      ),
      'triggers', (
        SELECT coalesce(json_agg(json_build_object('name', g.tgname, 'definition', pg_get_triggerdef(g.oid))
          ORDER BY g.tgname), '[]')
        FROM pg_trigger g WHERE g.tgrelid = t.oid AND NOT g.tgisinternal
      ),
      'policies', (
        SELECT coalesce(json_agg(json_build_object('name', p.polname, 'using', pg_get_expr(p.polqual, p.polrelid),
          'check', pg_get_expr(p.polwithcheck, p.polrelid)) ORDER BY p.polname), '[]')
        FROM pg_policy p WHERE p.polrelid = t.oid
      ),
      'grants', (
        SELECT coalesce(json_object_agg(g.grantee, g.privs), '{}')
        FROM (SELECT grantee, json_agg(privilege_type ORDER BY privilege_type) AS privs
              FROM information_schema.role_table_grants
              WHERE table_schema = 'public' AND table_name = t.relname AND grantee IN ('an22_app', 'an22_platform')
              GROUP BY grantee) g
      )
    ) ORDER BY t.relname)
    FROM tbl t
  )
);
