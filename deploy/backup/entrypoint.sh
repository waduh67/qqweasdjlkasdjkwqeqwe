#!/bin/sh
# Penjadwal cadangan: satu proses yang tidur sampai jam yang ditentukan, memanggil
# backup.sh, lalu tidur lagi. Sengaja bukan cron — image Postgres tak membawa cron,
# dan satu proses yang log-nya jatuh ke `docker compose logs backup` jauh lebih mudah
# dipantau daripada cron yang diam-diam gagal di dalam container.
#
# Env: BACKUP_AT (HH:MM waktu container, ikut TZ), BACKUP_STALE_HOURS, plus semua
# yang dibaca backup.sh.
set -eu

AT="${BACKUP_AT:-02:30}"
STALE_HOURS="${BACKUP_STALE_HOURS:-26}"
DIR="${BACKUP_DIR:-/backups}/${BACKUP_TARGET:?BACKUP_TARGET wajib diisi (app|radius)}"
HERE=$(dirname "$0")

log() { echo "[backup:$BACKUP_TARGET] $(date '+%Y-%m-%d %H:%M:%S %Z') $*"; }

# "08" tanpa penanganan = angka oktal yang tak sah di aritmetika sh; buang nol depan.
num() { v=${1#0}; echo "${v:-0}"; }

case "$AT" in
    [01][0-9]:[0-5][0-9] | 2[0-3]:[0-5][0-9]) ;;
    *) log "BACKUP_AT='$AT' tidak berbentuk HH:MM — memakai 02:30"; AT=02:30 ;;
esac
target=$(( $(num "${AT%%:*}") * 3600 + $(num "${AT##*:}") * 60 ))

# Dipanggil lewat `sh`, bukan dieksekusi langsung: bind mount membawa bit permission
# apa adanya dari host, dan satu `chmod -x` yang tak sengaja tak boleh membuat cadangan
# berhenti diam-diam.
run() {
    sh "$HERE/backup.sh" || log "ronde cadangan gagal — lihat pesan di atas; akan dicoba lagi besok"
}

# Kejar ketinggalan. Container ini ikut restart tiap kali stack di-deploy; tanpa ini,
# deploy yang kebetulan jatuh tiap hari sebelum jam cadangan akan membuat cadangan
# TAK PERNAH berjalan sama sekali, dan tak ada yang memberi tahu.
newest=$(ls -t "$DIR"/*.dump 2>/dev/null | head -1 || true)
if [ -z "$newest" ]; then
    log "belum ada cadangan sama sekali — menjalankan sekarang"
    run
elif [ $(( $(date +%s) - $(stat -c %Y "$newest") )) -ge $(( STALE_HOURS * 3600 )) ]; then
    log "cadangan terakhir lebih tua dari ${STALE_HOURS} jam — menjalankan sekarang"
    run
fi

log "jadwal harian $AT (TZ=$(date '+%Z')), simpan $((${BACKUP_RETENTION_DAYS:-14})) hari, tujuan $DIR"
while :; do
    now=$(( $(num "$(date +%H)") * 3600 + $(num "$(date +%M)") * 60 + $(num "$(date +%S)") ))
    # Selalu dihitung ulang dari jam dinding, jadi pergeseran zona waktu/DST paling
    # banter menggeser satu ronde, tak pernah mengakumulasi galat.
    wait=$(( (target - now + 86400) % 86400 ))
    if [ "$wait" -eq 0 ]; then wait=86400; fi
    log "tidur ${wait}s sampai ronde berikutnya"
    sleep "$wait"
    run
done
