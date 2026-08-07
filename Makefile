# ============================================================================
# Lab peniru-protokol ftth — satu perintah untuk mencoba app end-to-end TANPA
# perangkat nyata. Menyalakan stack Docker lengkap (app + DB + MinIO) plus tiga
# simulator protokol:
#   • OLT/SNMP   — agen SNMP HSGQ palsu (server polling langsung, tanpa collector)
#   • BRAS/RADIUS — virtual-NAS (sesi radacct hidup + responder DAE untuk isolir)
#   • GenieACS/CPE — ACS TR-069 nyata + 1 ONT palsu (serial 000000)
# lalu men-seed OLT/BRAS/CPE lewat API agar langsung tersambung ke simulator.
#
#   make lab         # bangun + nyalakan + seed (buka http://localhost:8080)
#   make lab-seed    # ulangi seeding saja (stack sudah jalan) — idempoten
#   make lab-logs    # ikuti log semua service
#
# ── Aman (DATA TETAP UTUH — volume DB/RADIUS/GenieACS tak disentuh): ──
#   make lab-update  # terapkan perubahan config/kode (rebuild), recreate yg berubah saja
#   make lab-restart # restart cepat (opsional SVC=simulator untuk satu service)
#   make lab-stop    # hentikan tanpa hapus (lanjut lagi: make lab-up)
#
# ── DESTRUKTIF: ──
#   make lab-down    # matikan + HAPUS SEMUA VOLUME → data lab ILANG (reset bersih)
#
# HANYA untuk lab/dev. JANGAN dipakai di produksi (simulator tak pernah di-deploy).
# ============================================================================

COMPOSE := docker compose -f docker-compose.lab.yml
BASE    ?= http://localhost:8080

.PHONY: lab lab-up lab-seed lab-logs lab-down lab-ps lab-update lab-restart lab-stop

## lab: bangun image, nyalakan seluruh stack + simulator, lalu seed via API
lab: lab-up lab-seed
	@printf '\n\033[1;32m✔ Lab siap.\033[0m Buka %s → login \033[1madmin@demo.ftth / admin12345\033[0m\n' "$(BASE)"
	@printf '  • Armada OLT palsu terpantau di menu Jaringan (server polling SNMP tiap ~30 dtk)\n'
	@printf '  • Sesi PPPoE + trafik hidup di detail pelanggan (BRAS/RADIUS)\n'
	@printf '  • CPE/ONT palsu (serial 000000) di menu CPE (GenieACS TR-069)\n'

## lab-up: bangun + jalankan stack Docker BERTAHAP (blokir sampai server sehat)
lab-up:
	@# Build semua image dulu agar bring-up bertahap tak menyisipkan rebuild di tengah.
	$(COMPOSE) build
	@printf '\n\033[1m⏳ Menyalakan backing service dulu (DB/MinIO/GenieACS) ...\033[0m\n'
	@# Kenapa bertahap: Docker 29.x bisa MENJATUHKAN alias DNS service saat belasan
	@# container attach serempak — makin parah bila server/simulator crash-loop (churn
	@# DNS tak henti). Solusinya: naikkan + sehatkan backing service LEBIH DULU supaya
	@# aliasnya (postgres/radius-db/minio/genieacs-nbi) terdaftar tenang; server lalu
	@# konek sekali-jadi (tak crash-loop) → tabel DNS tetap stabil.
	$(COMPOSE) up -d --wait --wait-timeout 150 \
	  postgres radius-db minio genieacs-mongo genieacs-cwmp genieacs-nbi
	@sleep 3
	@printf '\033[1m⏳ Menyalakan lapisan aplikasi (server/web/simulator) ...\033[0m\n'
	$(COMPOSE) up -d server web simulator genieacs-sim
	@# caddy paling akhir: ia me-resolve `server` & `web` by name → tunggu aliasnya ada.
	$(COMPOSE) up -d caddy
	@printf '\n\033[1m⏳ Menunggu server siap di %s ...\033[0m\n' "$(BASE)"
	@# /v3/api-docs diteruskan Caddy ke server + permitAll → probe kesiapan andal.
	@for i in $$(seq 1 60); do \
	  if curl -sf "$(BASE)/v3/api-docs" >/dev/null 2>&1; then \
	    printf '\033[1;32m✔ server sehat\033[0m\n'; exit 0; \
	  fi; \
	  sleep 3; printf '.'; \
	done; \
	printf '\n\033[1;33m⚠ server belum menjawab sehat setelah 180 dtk — lanjut seed, cek `make lab-logs` bila gagal.\033[0m\n'

## lab-seed: daftarkan OLT + BRAS + CPE ke simulator lewat API (idempoten)
lab-seed:
	BASE=$(BASE) bash docker/lab/seed-lab.sh

## lab-logs: ikuti log gabungan semua service
lab-logs:
	$(COMPOSE) logs -f --tail=100

## lab-ps: status service
lab-ps:
	$(COMPOSE) ps

## lab-update: terapkan perubahan config/kode (rebuild image) TANPA hapus data — mis. setelah
## nambah OLT di application.yml. Compose recreate HANYA container yang image/config-nya berubah;
## volume (DB app/RADIUS/GenieACS) tak disentuh, jadi pelanggan/OLT/langganan lama tetap ada.
lab-update:
	$(COMPOSE) up -d --build
	@printf '\n\033[1;32m✔ Perubahan diterapkan.\033[0m Volume tetap utuh (data lama aman).\n'
	@printf '  Bila nambah OLT/BRAS baru, daftarkan: \033[1mmake lab-seed\033[0m (idempoten).\n'

## lab-restart: restart container tanpa hapus data. Semua service, atau satu: `make lab-restart SVC=simulator`
lab-restart:
	$(COMPOSE) restart $(SVC)

## lab-stop: hentikan stack TANPA hapus container/volume — lanjut lagi dengan `make lab-up`
lab-stop:
	$(COMPOSE) stop

## lab-down: matikan stack dan HAPUS SEMUA VOLUME → DATA LAB ILANG (DB app, radius-db, GenieACS
## ter-reset). Pakai HANYA untuk reset bersih; untuk sekadar mematikan pakai `make lab-stop`.
lab-down:
	$(COMPOSE) down -v
