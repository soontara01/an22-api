#!/usr/bin/env bash
# ล้าง Microsoft Authenticator ของ platform admin ที่ทำมือถือหาย / เปลี่ยนเครื่อง (E56) — login ครั้งถัดไปผูก QR ใหม่
#
#   scripts/platform-mfa-reset.sh <username>     ลบ dt_platform_admin_mfa ของ admin นั้น
#   scripts/platform-mfa-reset.sh --list         admin ทั้งหมด + สถานะ Authenticator
#
# ทำบน server โดยผู้ดูแลระบบ (ไม่มีหน้าจอ — platform admin ทุกคนต้องใช้ Authenticator จึงล้างของกันเองผ่านแอปไม่ได้)
# ต้องมี docker (psql จาก image postgres) — ต่อ DB ตรง ไม่ผ่าน PgBouncer
# env (หรือ .env ที่ root ของ repo — ค่าที่ตั้งใน env แล้วไม่ถูกทับ): ชุดเดียวกับ migrate ของ db-apply.sh —
#   DB_MIGRATE_URL (jdbc), DB_OWNER_USER, DB_OWNER_PASSWORD (เจ้าของตาราง)
#   DB_CA_FILE (ไม่บังคับ) = ไฟล์ CA ที่ URL อ้าง · DB_APPLY_NETWORK (ค่าเริ่ม host) = docker network ที่ใช้ต่อ DB
set -euo pipefail

repo="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
if [[ $# -ne 1 || "$1" == -h || "$1" == --help ]]; then
  sed -n '2,10p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
  exit 2
fi

# อ่าน .env โดยไม่ทับค่าที่ตั้งไว้แล้ว (ไม่ eval — ค่าอาจมีอักขระพิเศษ)
if [[ -f "$repo/.env" ]]; then
  while IFS= read -r line || [[ -n "$line" ]]; do
    [[ "$line" =~ ^[A-Za-z_][A-Za-z0-9_]*= ]] || continue
    key="${line%%=*}"
    [[ -n "${!key+x}" ]] || export "$key=${line#*=}"
  done < "$repo/.env"
fi
for name in DB_MIGRATE_URL DB_OWNER_USER DB_OWNER_PASSWORD; do
  [[ -n "${!name:-}" ]] || { echo "ต้องตั้ง env: $name" >&2; exit 2; }
done

db_url=${DB_MIGRATE_URL#jdbc:} # jdbc:postgresql://… → libpq URI
docker_args=(--rm --network "${DB_APPLY_NETWORK:-host}")
[[ -n "${DB_CA_FILE:-}" ]] && docker_args+=(-v "$DB_CA_FILE:$DB_CA_FILE:ro")

psql_run() { # sql [username] — username ส่งเป็นตัวแปร psql (ไม่ต่อ string เข้า SQL)
  PGUSER="$DB_OWNER_USER" PGPASSWORD="$DB_OWNER_PASSWORD" \
    docker run "${docker_args[@]}" -i -e PGUSER -e PGPASSWORD postgres:18.6 \
    psql "$db_url" -X -q -At -v ON_ERROR_STOP=1 -v username="${2:-}" <<<"$1"
}

if [[ "$1" == --list ]]; then
  psql_run "SELECT a.username || ' | ' || a.name || ' | ' ||
      CASE WHEN m.confirmed_at IS NOT NULL THEN 'ใช้อยู่ (ผูกเมื่อ ' || to_char(m.confirmed_at AT TIME ZONE 'Asia/Bangkok', 'YYYY-MM-DD HH24:MI') || ')'
           WHEN m.admin_id IS NOT NULL THEN 'รอผูก' ELSE 'ยังไม่ผูก' END
    FROM dt_platform_admins a LEFT JOIN dt_platform_admin_mfa m ON m.admin_id = a.id ORDER BY a.id;"
  exit 0
fi

username=$(tr '[:upper:]' '[:lower:]' <<<"$1" | xargs)
found=$(psql_run "SELECT count(*) FROM dt_platform_admins WHERE username = :'username';" "$username")
if [[ "$found" != 1 ]]; then
  echo "ไม่พบ platform admin: $username" >&2
  exit 1
fi
removed=$(psql_run "WITH d AS (DELETE FROM dt_platform_admin_mfa m USING dt_platform_admins a
    WHERE m.admin_id = a.id AND a.username = :'username' RETURNING 1) SELECT count(*) FROM d;" "$username")
if [[ "$removed" == 1 ]]; then
  echo "ล้าง Microsoft Authenticator ของ $username แล้ว — login ครั้งถัดไปจะให้สแกน QR ผูกใหม่"
else
  echo "$username ยังไม่ได้ผูก Microsoft Authenticator (ไม่มีอะไรให้ล้าง)"
fi
