#!/usr/bin/env bash
# Helper dev untuk ftth OSS — kelola infra Docker + app Spring.
# Mode dev: infra (Postgres/Redis/RabbitMQ/MinIO[/GenieACS]) di Docker,
# app Spring jalan di host via ./gradlew bootRun.
#
# Pakai: ./dev.sh <command>   (lihat: ./dev.sh help)
set -euo pipefail
cd "$(dirname "$0")"

COMPOSE="docker compose"
PROFILE="--profile genieacs"   # dipakai utk perintah yg perlu lihat/atur GenieACS
NETWORK="ftth_default"
DB_USER="ftth"
DB_NAME="ftth"
NATIVE_PG="postgresql@16-main" # cluster Postgres native WSL yg suka bentrok di 5432
EXT_SQL="CREATE EXTENSION IF NOT EXISTS postgis; CREATE EXTENSION IF NOT EXISTS timescaledb;"

log()  { printf '\033[1;36m▶ %s\033[0m\n' "$*"; }
ok()   { printf '\033[1;32m✔ %s\033[0m\n' "$*"; }
warn() { printf '\033[1;33m! %s\033[0m\n' "$*"; }

# Tunggu container postgres sampai healthcheck-nya "healthy".
wait_pg() {
  log "Nunggu Postgres siap (healthy)..."
  for _ in $(seq 1 60); do
    if $COMPOSE exec -T postgres pg_isready -U "$DB_USER" -d "$DB_NAME" >/dev/null 2>&1; then
      ok "Postgres siap."
      return 0
    fi
    sleep 1
  done
  warn "Postgres belum siap setelah 60 detik. Cek: ./dev.sh logs postgres"
  return 1
}

ensure_ext() {
  log "Pastikan extension: postgis + timescaledb"
  $COMPOSE exec -T postgres psql -U "$DB_USER" -d "$DB_NAME" -c "$EXT_SQL"
  ok "Extension siap."
}

# Kalau container postgres jalan: tunggu healthy + pastikan extension.
# Dipanggil habis 'up'/'up-all' biar Flyway V2 gak gagal soal postgis.
ensure_db_ready() {
  if $COMPOSE ps --status running postgres 2>/dev/null | grep -q postgres; then
    wait_pg && ensure_ext
  fi
}

usage() {
  cat <<EOF
dev.sh — helper dev ftth

Infra:
  up            Nyalain infra inti (pg, redis, rabbitmq, minio)
  up-all        + GenieACS (build image lokal)
  down          Stop infra (data volume TETAP ada)
  reset         Stop + HAPUS data (pg & minio) — fresh start
  restart [svc] Restart service (mis: ./dev.sh restart postgres)
  ps            Status semua service
  logs [svc]    Tail log (default: semua)

Database:
  init          up + tunggu pg healthy + bikin extension  ← siap dev sekali jalan
  init-all      init + GenieACS (build image lokal)
  ext           Bikin/pastikan extension aja (idempoten)
  psql          Shell psql ke DB ftth

App:
  run           ./gradlew :server:bootRun     (backend Spring, :8080)
  web           npm run dev di web/           (frontend Vite, :5173)

Tools:
  adminer       Jalanin Adminer di http://localhost:8081 (server=postgres)
  adminer-stop  Matiin Adminer
  stop-native   Matiin + disable Postgres native WSL ($NATIVE_PG) — butuh sudo

Alur pertama kali:  ./dev.sh stop-native  →  ./dev.sh init  →  ./dev.sh run
EOF
}

cmd="${1:-help}"
[ "$#" -gt 0 ] && shift || true

case "$cmd" in
  up)       $COMPOSE up -d "$@"; ensure_db_ready ;;
  up-all)   $COMPOSE $PROFILE up -d --build "$@"; ensure_db_ready ;;
  down)     $COMPOSE $PROFILE down ;;
  reset)
    warn "Ini bakal HAPUS semua data DB & MinIO."
    $COMPOSE $PROFILE down -v
    ok "Volume dibersihkan. Jalanin './dev.sh init' buat mulai fresh."
    ;;
  restart)  $COMPOSE restart "$@" ;;
  ps)       $COMPOSE $PROFILE ps ;;
  logs)     $COMPOSE logs -f "$@" ;;

  init)
    $COMPOSE up -d
    wait_pg
    ensure_ext
    ok "Infra siap. Lanjut: ./dev.sh run"
    ;;
  init-all)
    $COMPOSE $PROFILE up -d --build
    wait_pg
    ensure_ext
    ok "Infra + GenieACS siap. Lanjut: ./dev.sh run"
    ;;
  ext)
    wait_pg
    ensure_ext
    ;;
  psql)     $COMPOSE exec postgres psql -U "$DB_USER" -d "$DB_NAME" "$@" ;;

  run)      ./gradlew :server:bootRun "$@" ;;
  web)
    if [ ! -d web/node_modules ]; then
      log "node_modules belum ada — jalanin npm install dulu..."
      (cd web && npm install)
    fi
    log "Vite dev server → http://localhost:5173 (proxy /api → :8080)"
    (cd web && npm run dev "$@")
    ;;

  adminer)
    docker rm -f ftth-adminer >/dev/null 2>&1 || true
    docker run --rm -d --name ftth-adminer --network "$NETWORK" -p 8081:8080 adminer >/dev/null
    ok "Adminer: http://localhost:8081"
    echo "   System=PostgreSQL  Server=postgres  User=$DB_USER  Password=$DB_USER  Database=$DB_NAME"
    ;;
  adminer-stop)
    docker rm -f ftth-adminer >/dev/null 2>&1 && ok "Adminer dimatiin." || warn "Adminer gak jalan."
    ;;

  stop-native)
    log "Matiin + disable Postgres native WSL ($NATIVE_PG) — butuh sudo"
    sudo systemctl stop "$NATIVE_PG" || true
    sudo systemctl disable "$NATIVE_PG" || true
    sudo systemctl disable postgresql || true
    if ss -tlnp 2>/dev/null | grep -q ':5432'; then
      warn "Masih ada yg listen di :5432 — cek manual: ss -tlnp | grep 5432"
    else
      ok "Port 5432 bebas. Recreate container: ./dev.sh up  (atau ./dev.sh init)"
    fi
    ;;

  help|-h|--help) usage ;;
  *) warn "Command '$cmd' gak dikenal."; echo; usage; exit 1 ;;
esac
