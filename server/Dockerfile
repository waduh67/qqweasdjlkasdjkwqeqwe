
# ============================================================================
# ftth-server — image produksi (Spring Boot bootJar).
# Konteks build = ROOT repo (butuh gradle wrapper + modul :contract + :server).
#   docker build -f server/Dockerfile -t ftth-server .
# ============================================================================

# ---- Tahap build: kompilasi bootJar dengan Gradle wrapper ----
FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace
COPY . .
# Cache direktori Gradle antar-build. Pipeline wajib menuntaskan seluruh gate
# sebelum image dipublikasikan; bootJar sendiri hanya mengompilasi paket aplikasi.
RUN --mount=type=cache,id=gradle-server,target=/root/.gradle \
    chmod +x ./gradlew && ./gradlew :server:bootJar --no-daemon --no-parallel --max-workers=2
# Gunakan output task Gradle yang persis, bukan wildcard yang dapat memilih JAR lama.
RUN set -eu; \
    read -r jar < server/build/warehouse/boot-jar-path.txt; \
    case "$jar" in /workspace/server/build/libs/*-plain.jar) exit 1 ;; /workspace/server/build/libs/*.jar) ;; *) exit 1 ;; esac; \
    cp "$jar" /workspace/app.jar

# ---- Tahap runtime: JRE ramping, berjalan sebagai non-root ----
FROM eclipse-temurin:21-jre AS runtime
WORKDIR /app
RUN groupadd --system ftth && useradd --system --gid ftth --home /app ftth
COPY --from=build /workspace/app.jar /app/app.jar
USER ftth
EXPOSE 8080
# MaxRAMPercentage: hormati batas memori container, bukan seluruh RAM host.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-jar", "/app/app.jar"]
