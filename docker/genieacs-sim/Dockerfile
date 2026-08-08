# Simulator device TR-069 (zaidka/genieacs-sim) untuk DEV: memunculkan satu ONT
# palsu yang rutin "Inform" ke GenieACS, plus melayani connection-request sehingga
# reboot / ubah WiFi dari aplikasi benar-benar terkirim ke device (bukan cuma tercatat).
# Serial device bawaan cuma 6 digit angka dari offset (-s), mis. "000000" — bentuk
# yang TAK PERNAH dipakai ONU sungguhan. Padahal tautan CPE↔pelanggan sepenuhnya
# lewat kecocokan serial, jadi di lab ONT palsu ini harus bisa memakai serial yang
# sama dengan yang dilaporkan simulator OLT. Lihat patch SIM_SERIAL di bawah.
FROM node:20-bookworm-slim

RUN apt-get update \
    && apt-get install -y --no-install-recommends git ca-certificates \
    && rm -rf /var/lib/apt/lists/*

# Repo praktis tak berubah bertahun-tahun; master stabil. Ganti SIM_REF ke commit
# tertentu bila mau benar-benar dikunci.
ARG SIM_REF=master
RUN git clone https://github.com/zaidka/genieacs-sim.git /sim \
    && cd /sim && git checkout "$SIM_REF" && npm install

# Upstream mengunci serial ke `00000${offset}`.slice(-6) saat mem-fork worker, tanpa
# jalan untuk menyetelnya bebas. Satu baris ini membuatnya menghormati env SIM_SERIAL
# (kosong = perilaku lama). `grep` sesudahnya adalah pagar: kalau upstream mengubah
# baris itu, build GAGAL di sini — jauh lebih baik daripada diam-diam kembali ke
# "000000" dan menyisakan lab yang CPE-nya tak pernah tertaut.
# Catatan: dengan -p >1 semua worker akan memakai serial yang sama, jadi SIM_SERIAL
# hanya masuk akal untuk satu device.
RUN sed -i 's#"SERIAL_NUMBER": #"SERIAL_NUMBER": process.env["SIM_SERIAL"] || #' /sim/genieacs-sim \
    && grep -q 'process.env\["SIM_SERIAL"\]' /sim/genieacs-sim

WORKDIR /sim

# Default: 1 device ke cwmp di jaringan compose. `command:` di compose bisa menimpa
# (mis. -p untuk banyak device); serialnya lewat env SIM_SERIAL.
ENTRYPOINT ["node", "genieacs-sim"]
CMD ["-u", "http://genieacs-cwmp:7547/"]
