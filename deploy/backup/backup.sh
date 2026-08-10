#!/bin/sh
# Mencadangkan SATU database ke /backups/<target>/ lalu memangkas cadangan lama.
# Biasanya dipanggil penjadwal (entrypoint.sh), tapi boleh juga dijalankan tangan:
#
#   docker compose -f docker-compose.prod.yml exec backup sh /opt/backup/backup.sh
#
# Ditulis dalam POSIX sh, bukan bash: skrip yang sama dipakai container Debian
# (klien pg17, DB aplikasi) dan Alpine/busybox (klien pg16, DB radius).
#
# Env yang dibaca: BACKUP_TARGET (app|radius), BACKUP_DIR, BACKUP_RETENTION_DAYS,
# plus PGHOST/PGUSER/PGPASSWORD/PGDATABASE seperti klien Postgres pada umumnya.
set -eu

TARGET="${BACKUP_TARGET:?BACKUP_TARGET wajib diisi (app|radius)}"
DIR="${BACKUP_DIR:-/backups}/$TARGET"
RETENTION="${BACKUP_RETENTION_DAYS:-14}"
DB="${PGDATABASE:?PGDATABASE wajib diisi}"

log() { echo "[backup:$TARGET] $(date '+%Y-%m-%d %H:%M:%S %Z') $*"; }
die() {
    log "GAGAL — $*"
    printf 'status=GAGAL\nat=%s\nnote=%s\n' "$(date '+%Y-%m-%dT%H:%M:%S%z')" "$*" \
        > "$DIR/last-backup.txt" 2>/dev/null || true
    exit 1
}

mkdir -p "$DIR"
chmod 700 "$DIR"

# ---------------------------------------------------------------------------
# Gerbang paling penting di skrip ini: role yang mencadangkan HARUS kebal RLS.
#
# Semua tabel ber-tenant di DB aplikasi memakai FORCE ROW LEVEL SECURITY, jadi
# kebijakan isolasi berlaku bahkan untuk PEMILIK tabel (role `ftth`). Kebijakannya
# menyaring `tenant_id = current_setting('app.tenant_id')`, dan di sesi pg_dump GUC
# itu tak pernah diset — hasilnya nol baris. pg_dump TIDAK menganggapnya galat: ia
# menulis file .dump yang tampak wajar, lengkap dengan skema, tapi ISINYA KOSONG.
# Cadangan hampa yang baru ketahuan saat dipulihkan adalah kegagalan terburuk yang
# bisa dipunyai sebuah sistem cadangan, jadi kita tolak di depan, bukan berdoa.
# ---------------------------------------------------------------------------
privileged=$(psql -Atc "SELECT rolsuper OR rolbypassrls FROM pg_roles WHERE rolname = current_user") \
    || die "tak bisa menyambung ke $PGHOST/$DB sebagai ${PGUSER:-?}"
if [ "$privileged" != "t" ]; then
    die "role '${PGUSER:-?}' tunduk pada Row-Level Security. pg_dump akan menghasilkan
     cadangan KOSONG tanpa pesan galat. Jalankan cadangan sebagai superuser
     (di stack ini: POSTGRES_SUPER_PASSWORD), bukan sebagai role aplikasi."
fi

stamp=$(date '+%Y%m%d-%H%M%S')
part="$DIR/.$DB-$stamp.dump.part"
final="$DIR/$DB-$stamp.dump"
started=$(date +%s)

log "mulai — $PGHOST/$DB → $final"
pg_dump --format=custom --no-tablespaces --file="$part" || { rm -f "$part"; die "pg_dump gagal"; }

# Verifikasi isi arsip SEBELUM ia dianggap cadangan sah. `pg_restore --list` membaca
# seluruh daftar isi, jadi file terpotong (disk penuh, container dibunuh di tengah
# jalan) ketahuan di sini — bukan nanti saat kita betul-betul butuh.
pg_restore --list "$part" > "$part.toc" 2>/dev/null || { rm -f "$part" "$part.toc"; die "arsip tak terbaca (dump terpotong?)"; }
if [ "$TARGET" = "app" ] && ! grep -q 'TABLE DATA public tenant ' "$part.toc"; then
    rm -f "$part" "$part.toc"
    die "arsip tak memuat data tabel 'tenant' — jangan dipakai"
fi
entries=$(grep -c ';' "$part.toc" || true)
rm -f "$part.toc"

mv "$part" "$final"
chmod 600 "$final"

# Role & password level-cluster tak ikut dalam dump satu database. Tanpa ini,
# memulihkan ke server kosong menghasilkan objek tanpa pemilik ("role ftth does not
# exist"). Di stack kita role dibuat ulang oleh postgres-init saat boot pertama, jadi
# berkas ini jaring pengaman untuk pemulihan ke mesin lain.
pg_dumpall --globals-only --file="$DIR/globals-$stamp.sql" 2>/dev/null \
    && chmod 600 "$DIR/globals-$stamp.sql" \
    || log "peringatan: pg_dumpall --globals-only gagal (role tak ikut tercadang)"

bytes=$(wc -c < "$final" | tr -d ' ')
seconds=$(( $(date +%s) - started ))
log "selesai — $(du -h "$final" | cut -f1) ($bytes byte, $entries objek) dalam ${seconds}s"

# --- Pangkas cadangan kedaluwarsa + sisa file setengah jadi ---
removed=$(find "$DIR" -maxdepth 1 -type f \( -name '*.dump' -o -name '*.sql' \) \
    -mtime "+$RETENTION" -print -delete | wc -l | tr -d ' ')
if [ "$removed" -gt 0 ]; then log "memangkas $removed cadangan lebih tua dari $RETENTION hari"; fi
find "$DIR" -maxdepth 1 -type f -name '*.part' -mtime +1 -delete 2>/dev/null || true

kept=$(find "$DIR" -maxdepth 1 -type f -name '*.dump' | wc -l | tr -d ' ')
printf 'status=OK\nat=%s\nfile=%s\nbytes=%s\nseconds=%s\nkept=%s\n' \
    "$(date '+%Y-%m-%dT%H:%M:%S%z')" "$(basename "$final")" "$bytes" "$seconds" "$kept" \
    > "$DIR/last-backup.txt"
log "$kept cadangan tersimpan di $DIR"
