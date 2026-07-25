# GenieACS 1.2 dari paket npm resmi (pinned) — bukan image pihak ketiga, supaya
# reproducible dan dikontrol repo. Keempat prosesnya (cwmp/nbi/fs/ui) dijalankan
# sebagai service compose terpisah dari image yang SAMA ini; masing-masing hanya
# beda `command:`. Lihat docker-compose.yml (profile "genieacs").
FROM node:20-bookworm-slim

RUN npm install -g genieacs@1.2.16 \
    && npm cache clean --force

# Direktori extension GenieACS (opsional; dibuat agar tak error saat start).
RUN mkdir -p /tmp/genieacs-ext
ENV GENIEACS_EXT_DIR=/tmp/genieacs-ext

# cwmp / nbi / fs / ui
EXPOSE 7547 7557 7567 3000
