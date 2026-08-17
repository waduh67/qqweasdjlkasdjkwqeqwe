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

# Menyelaraskan auth CWMP (`cwmp.auth`) dari environment saat proses cwmp naik. GenieACS
# menyimpan setelan itu di Mongo dan biasanya diisi lewat UI-nya — yang tak kita jalankan —
# jadi tanpa ini satu-satunya cara mengunci port 7547 adalah menyunting Mongo dengan tangan.
COPY acs-bootstrap.js /usr/local/bin/acs-bootstrap.js
COPY entrypoint.sh /usr/local/bin/entrypoint.sh
RUN chmod +x /usr/local/bin/entrypoint.sh

ENTRYPOINT ["/usr/local/bin/entrypoint.sh"]

# cwmp / nbi / fs / ui
EXPOSE 7547 7557 7567 3000
