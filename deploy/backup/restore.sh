#!/bin/sh
# Memulihkan satu berkas cadangan. Dua mode:
#
#   LATIHAN (bawaan) — dipulihkan ke database BARU di server yang sama, data produksi
#   tak disentuh sama sekali. Ini yang dipakai untuk membuktikan cadangan benar-benar
#   bisa dipulihkan, dan aman dijalankan kapan saja di produksi.
#
#   --replace — menimpa database sungguhan. Menghancurkan isi yang sekarang.
#
# Contoh (`--entrypoint sh` wajib: entrypoint bawaan service `backup` adalah penjadwal,
# argumen tambahan cuma diabaikannya dan kamu berakhir menatap container yang tidur):
#
#   C="docker compose -f docker-compose.prod.yml run --rm --entrypoint sh"
#   $C backup  /opt/backup/restore.sh                    # daftar cadangan yang ada
#   $C backup  /opt/backup/restore.sh latest             # LATIHAN ke database baru
#   $C backup  /opt/backup/restore.sh latest --replace   # timpa database sungguhan
#   $C backup-radius /opt/backup/restore.sh latest       # idem untuk DB radius
#
# POSIX sh — sama seperti backup.sh, dipakai container Debian maupun Alpine.
set -eu

TARGET="${BACKUP_TARGET:?BACKUP_TARGET wajib diisi (app|radius)}"
DIR="${BACKUP_DIR:-/backups}/$TARGET"
DB="${PGDATABASE:?PGDATABASE wajib diisi}"
OWNER="${BACKUP_DB_OWNER:-${PGUSER:-postgres}}"

log() { echo "[restore:$TARGET] $*"; }
die() { echo "[restore:$TARGET] GAGAL — $*" >&2; exit 1; }

# psql yang TIDAK menyentuh database tujuan (bikin/hapus database harus dari luar).
adm() { psql -v ON_ERROR_STOP=1 -d postgres -Atc "$1"; }

usage() {
    cat <<EOF
Pemakaian: restore.sh [<berkas.dump>|latest] [--into <nama-db>] [--replace] [--yes]

  (tanpa argumen)   daftar cadangan yang tersedia
  latest            pakai cadangan terbaru di $DIR
  --into <nama>     nama database latihan (bawaan: ${DB}_drill_<stempel>)
  --replace         TIMPA database '$DB' yang sedang dipakai aplikasi
  --yes             lewati konfirmasi ketik-ulang (untuk skrip)
EOF
}

list_backups() {
    log "cadangan di $DIR:"
    found=$(ls -t "$DIR"/*.dump 2>/dev/null || true)
    if [ -n "$found" ]; then
        # shellcheck disable=SC2086
        ls -lh $found | sed 's/^/    /'
    else
        echo "    (kosong)"
    fi
    if [ -f "$DIR/last-backup.txt" ]; then
        echo
        log "status ronde terakhir:"
        sed 's/^/    /' "$DIR/last-backup.txt"
    fi
}

# --- Argumen ---
FILE=""; INTO=""; REPLACE=no; ASSUME_YES=no
while [ $# -gt 0 ]; do
    case "$1" in
        --into) INTO="${2:?--into butuh nama database}"; shift 2 ;;
        --replace) REPLACE=yes; shift ;;
        --yes|-y) ASSUME_YES=yes; shift ;;
        -h|--help) usage; exit 0 ;;
        -*) die "opsi tak dikenal: $1" ;;
        *) FILE="$1"; shift ;;
    esac
done

if [ -z "$FILE" ]; then list_backups; echo; usage; exit 0; fi
if [ "$FILE" = "latest" ]; then
    FILE=$(ls -t "$DIR"/*.dump 2>/dev/null | head -1 || true)
    [ -n "$FILE" ] || die "tak ada cadangan di $DIR"
fi
case "$FILE" in */*) ;; *) FILE="$DIR/$FILE" ;; esac
[ -f "$FILE" ] || die "berkas tak ada: $FILE"

# Arsip diperiksa dulu — memulihkan setengah jalan lalu baru sadar file-nya rusak
# adalah cara terburuk untuk mengetahuinya, apalagi di mode --replace.
pg_restore --list "$FILE" > /tmp/restore.toc 2>/dev/null || die "arsip tak terbaca: $FILE"
log "arsip sah: $FILE ($(du -h "$FILE" | cut -f1), $(grep -c ';' /tmp/restore.toc || true) objek)"

if [ "$REPLACE" = yes ]; then
    INTO="$DB"
    cat <<EOF

  !!  MODE TIMPA  !!
  Database '$DB' di $PGHOST akan DIHAPUS lalu diisi ulang dari cadangan di atas.
  Semua perubahan setelah cadangan itu dibuat akan HILANG.
  Hentikan dulu aplikasinya:  docker compose -f docker-compose.prod.yml stop server

EOF
    if [ "$ASSUME_YES" != yes ]; then
        printf "  Ketik nama database ('%s') untuk lanjut: " "$DB"
        read -r answer || answer=""
        [ "$answer" = "$DB" ] || die "dibatalkan"
    fi
else
    [ -n "$INTO" ] || INTO="${DB}_drill_$(date '+%Y%m%d_%H%M%S')"
    log "mode latihan — dipulihkan ke database baru '$INTO', '$DB' tak disentuh"
fi

# --- Siapkan database tujuan ---
if [ "$REPLACE" = yes ]; then
    log "menghapus & membuat ulang '$INTO'"
    adm "DROP DATABASE IF EXISTS \"$INTO\" WITH (FORCE)" > /dev/null
else
    exists=$(adm "SELECT 1 FROM pg_database WHERE datname = '$INTO'")
    [ -z "$exists" ] || die "database '$INTO' sudah ada — hapus dulu atau pakai --into nama-lain"
fi
adm "CREATE DATABASE \"$INTO\" OWNER \"$OWNER\"" > /dev/null

into() { psql -v ON_ERROR_STOP=1 -d "$INTO" -Atc "$1"; }

if [ "$TARGET" = "app" ]; then
    # Extension dipasang lebih dulu supaya tipe/fungsinya sudah ada saat data masuk.
    log "memasang postgis + timescaledb"
    into "CREATE EXTENSION IF NOT EXISTS postgis" > /dev/null
    into "CREATE EXTENSION IF NOT EXISTS timescaledb" > /dev/null
    # TimescaleDB WAJIB dibuat "diam" selama pemulihan: kalau tidak, event trigger-nya
    # ikut campur saat chunk hypertable dibuat ulang dan pemulihan berantakan.
    log "timescaledb_pre_restore()"
    into "SELECT timescaledb_pre_restore()" > /dev/null
fi

log "pg_restore → $INTO (ini bagian yang lama)"
set +e
pg_restore --dbname="$INTO" --no-tablespaces --verbose "$FILE" > /tmp/restore.log 2>&1
rc=$?
set -e
errors=$(grep -c '^pg_restore: error:' /tmp/restore.log || true)

if [ "$TARGET" = "app" ]; then
    # Dijalankan APA PUN hasil pg_restore — database yang ditinggal dalam mode
    # pre-restore tak bisa dipakai sama sekali.
    log "timescaledb_post_restore()"
    into "SELECT timescaledb_post_restore()" > /dev/null
fi
into "ANALYZE" > /dev/null

if [ "$errors" -gt 0 ]; then
    log "$errors galat dari pg_restore (10 pertama):"
    grep '^pg_restore: error:' /tmp/restore.log | head -10
    log "log lengkap: /tmp/restore.log (di dalam container ini)"
fi

# --- Bukti bahwa datanya benar-benar ada ---
# `onu_metric` sengaja ikut: ia hypertable TimescaleDB, jadi jumlah barisnya sekaligus
# membuktikan chunk deret-waktu ikut pulih — bagian yang paling mudah diam-diam hilang.
case "$TARGET" in
    app) TABLES="tenant app_user customer subscription invoice work_order helpdesk_ticket onu_metric" ;;
    *)   TABLES="nas radcheck radgroupreply radusergroup radacct" ;;
esac
echo
log "isi database '$INTO':"
for t in $TABLES; do
    if [ -n "$(into "SELECT to_regclass('public.$t')")" ]; then
        printf '    %-16s%s baris\n' "$t" "$(into "SELECT count(*) FROM public.\"$t\"")"
    else
        printf '    %-16s(tabel tak ada di cadangan ini)\n' "$t"
    fi
done
echo

if [ "$REPLACE" = yes ]; then
    log "selesai. Nyalakan lagi: docker compose -f docker-compose.prod.yml start server"
else
    log "selesai. Database latihan '$INTO' masih ada — periksa sepuasnya, lalu buang:"
    log "  psql -c 'DROP DATABASE \"$INTO\"'"
fi
[ "$errors" -eq 0 ] && [ "$rc" -eq 0 ] || exit 1
