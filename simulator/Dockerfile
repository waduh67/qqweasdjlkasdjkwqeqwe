
# ============================================================================
# ftth-simulator — peniru protokol (OLT/SNMP + BRAS/RADIUS DAE) untuk LAB.
# Konteks build = ROOT repo (butuh gradle wrapper + modul :contract, :snmp, :simulator).
#   docker build -f simulator/Dockerfile -t ftth-simulator .
#
# HANYA untuk lab/dev — JANGAN dijalankan di produksi. Konfigurasi lewat env
# (lihat application.yml): FTTH_SIM_OLT_*, FTTH_SIM_RADIUS_*.
# ============================================================================

# ---- Tahap build: rakit distribusi aplikasi (application plugin) ----
FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace
COPY . .
# Cache direktori Gradle antar-build (butuh BuildKit). Test dilewati; dijalankan
# terpisah oleh CI. installDist menghasilkan skrip start + semua jar di build/install.
RUN --mount=type=cache,id=gradle-simulator,target=/root/.gradle \
    chmod +x ./gradlew && ./gradlew :simulator:installDist -x test --no-daemon

# ---- Tahap runtime: JRE ramping, non-root ----
FROM eclipse-temurin:21-jre AS runtime
WORKDIR /app
RUN groupadd --system ftth && useradd --system --gid ftth --home /app ftth
COPY --from=build /workspace/simulator/build/install/simulator /app
USER ftth
# MaxRAMPercentage: hormati batas memori container, bukan seluruh RAM host.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0"
ENTRYPOINT ["/app/bin/simulator"]
