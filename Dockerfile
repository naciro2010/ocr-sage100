# === Stage 1: Build Backend ===
FROM eclipse-temurin:21-jdk-alpine AS backend-build
WORKDIR /app
COPY gradle gradle
COPY gradlew build.gradle.kts settings.gradle.kts ./
RUN ./gradlew dependencies --no-daemon || true
COPY src src
RUN ./gradlew bootJar --no-daemon

# === Stage 2: Runtime ===
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

RUN apk add --no-cache \
    tesseract-ocr \
    tesseract-ocr-data-eng \
    tesseract-ocr-data-fra \
    tesseract-ocr-data-ara \
    leptonica-dev \
    fontconfig \
    ttf-dejavu

ENV TESSDATA_PREFIX=/usr/share/tessdata

RUN addgroup -S appgroup && adduser -S appuser -G appgroup
COPY --from=backend-build /app/build/libs/*.jar app.jar
COPY entrypoint.sh ./
# /app/data is the default mount point for the Railway Volume that backs document
# storage (STORAGE_DIR=/app/data/uploads). /app/uploads is kept for backward-compat
# on environments without a volume, but should be considered ephemeral.
RUN chmod +x entrypoint.sh && \
    mkdir -p /app/uploads /app/data/uploads && \
    chown -R appuser:appgroup /app/uploads /app/data
USER appuser

EXPOSE 8080
ENTRYPOINT ["./entrypoint.sh"]
