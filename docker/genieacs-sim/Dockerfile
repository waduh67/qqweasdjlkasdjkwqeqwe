# Simulator device TR-069 (zaidka/genieacs-sim) untuk DEV: memunculkan satu ONT
# palsu yang rutin "Inform" ke GenieACS, plus melayani connection-request sehingga
# reboot / ubah WiFi dari aplikasi benar-benar terkirim ke device (bukan cuma tercatat).
# Serial device dihitung 6 digit dari offset (-s), default "000000".
FROM node:20-bookworm-slim

RUN apt-get update \
    && apt-get install -y --no-install-recommends git ca-certificates \
    && rm -rf /var/lib/apt/lists/*

# Repo praktis tak berubah bertahun-tahun; master stabil. Ganti SIM_REF ke commit
# tertentu bila mau benar-benar dikunci.
ARG SIM_REF=master
RUN git clone https://github.com/zaidka/genieacs-sim.git /sim \
    && cd /sim && git checkout "$SIM_REF" && npm install

WORKDIR /sim

# Default: 1 device (serial 000000) ke cwmp di jaringan compose. `command:` di
# compose bisa menimpa (mis. -s untuk ganti serial, -p untuk banyak device).
ENTRYPOINT ["node", "genieacs-sim"]
CMD ["-u", "http://genieacs-cwmp:7547/"]
