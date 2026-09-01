
# ============================================================================
# ftth-server — image produksi (Spring Boot bootJar).
# Konteks build = ROOT repo (butuh gradle wrapper + modul :contract + :server).
#   docker build -f server/Dockerfile -t ftth-server .
# ============================================================================

# ---- Tahap build: kompilasi bootJar dengan Gradle wrapper ----
FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace
COPY . .
# Cache direktori Gradle antar-build (butuh BuildKit; aktif di GitHub Actions &
# Docker modern). Test dilewati di sini — dijalankan terpisah oleh pipeline CI.
RUN --mount=type=cache,id=gradle-server,target=/root/.gradle \
    chmod +x ./gradlew && \
    attempt=1; max_attempts=3; \
    while [ "$attempt" -le "$max_attempts" ]; do \
      printf 'Gradle bootJar attempt %s/%s\n' "$attempt" "$max_attempts"; \
      if ./gradlew :server:bootJar -x test --no-daemon; then exit 0; fi; \
      if [ "$attempt" -eq "$max_attempts" ]; then \
        printf 'Gradle bootJar failed after %s attempts\n' "$max_attempts" >&2; \
        exit 1; \
      fi; \
      printf 'Gradle bootJar failed; retrying in 10 seconds...\n' >&2; \
      sleep 10; \
      attempt=$((attempt + 1)); \
    done
# Boot jar = satu-satunya jar yang BUKAN "-plain.jar".
RUN cp "$(ls server/build/libs/*.jar | grep -v -- '-plain.jar')" /workspace/app.jar

# ---- Tahap runtime: JRE ramping, berjalan sebagai non-root ----
FROM eclipse-temurin:21-jre AS runtime
WORKDIR /app
RUN groupadd --system ftth && useradd --system --gid ftth --home /app ftth
COPY --from=build /workspace/app.jar /app/app.jar
USER ftth
EXPOSE 8080
# MaxRAMPercentage: hormati batas memori container, bukan seluruh RAM host.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-jar", "/app/app.jar"]
