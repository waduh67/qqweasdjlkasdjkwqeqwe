
# ============================================================================
# ftth-collector — agent BNG/OLT untuk lab & deploy di sisi ISP.
# Konteks build = ROOT repo (butuh gradle wrapper + modul :contract + :collector).
#   docker build -f collector/Dockerfile -t ftth-collector .
#
# Konfigurasi lewat env (lihat Main.kt): FTTH_SERVER_URL, FTTH_COLLECTOR_KEY,
# FTTH_COLLECTOR_SIMULATOR, FTTH_COLLECTOR_ONCE.
# ============================================================================

# ---- Tahap build: rakit distribusi aplikasi (application plugin) ----
FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace
COPY . .
# Cache direktori Gradle antar-build (butuh BuildKit). Test dilewati; dijalankan
# terpisah oleh CI. installDist menghasilkan skrip start + semua jar di build/install.
RUN --mount=type=cache,id=gradle-collector,target=/root/.gradle \
    chmod +x ./gradlew && ./gradlew :collector:installDist -x test --no-daemon

# ---- Tahap runtime: JRE ramping, non-root ----
FROM eclipse-temurin:21-jre AS runtime
WORKDIR /app
RUN groupadd --system ftth && useradd --system --gid ftth --home /app ftth
COPY --from=build /workspace/collector/build/install/collector /app
USER ftth
# MaxRAMPercentage: hormati batas memori container, bukan seluruh RAM host.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0"
ENTRYPOINT ["/app/bin/collector"]
