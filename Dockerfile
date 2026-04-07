FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /app
COPY gradle gradle
COPY gradlew build.gradle.kts settings.gradle.kts ./
RUN ./gradlew dependencies --no-daemon || true
COPY src src
RUN ./gradlew bootJar --no-daemon

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# Install Tesseract OCR with French and Arabic language packs
RUN apk add --no-cache \
    tesseract-ocr \
    tesseract-ocr-data-fra \
    tesseract-ocr-data-ara \
    # Native libs required by Tess4J (JNA)
    leptonica-dev \
    # Fonts for PDF rendering
    fontconfig \
    ttf-dejavu \
    # psql for DB reset on deploy
    postgresql16-client

# Set tessdata path for Tess4J
ENV TESSDATA_PREFIX=/usr/share/tessdata

RUN addgroup -S appgroup && adduser -S appuser -G appgroup
COPY --from=build /app/build/libs/*.jar app.jar
COPY entrypoint.sh ./
RUN chmod +x entrypoint.sh && \
    mkdir -p /app/uploads && \
    chown -R appuser:appgroup /app/uploads
USER appuser
EXPOSE 8080
ENTRYPOINT ["./entrypoint.sh"]
