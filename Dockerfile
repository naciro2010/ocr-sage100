# Stage 1: Build frontend
FROM node:22-alpine AS frontend-build
WORKDIR /frontend
COPY frontend/package*.json ./
RUN npm ci
COPY frontend/ .
RUN npm run build

# Stage 2: Build backend
FROM eclipse-temurin:21-jdk-alpine AS backend-build
WORKDIR /app
COPY gradle gradle
COPY gradlew build.gradle.kts settings.gradle.kts ./
RUN ./gradlew dependencies --no-daemon || true
COPY src src
# Copy frontend build into Spring Boot static resources
COPY --from=frontend-build /frontend/dist/ src/main/resources/static/
RUN ./gradlew bootJar --no-daemon

# Stage 3: Runtime
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
RUN addgroup -S appgroup && adduser -S appuser -G appgroup
COPY --from=backend-build /app/build/libs/*.jar app.jar
COPY entrypoint.sh ./
RUN chmod +x entrypoint.sh && \
    mkdir -p /app/uploads && \
    chown -R appuser:appgroup /app/uploads
USER appuser
EXPOSE 8080
ENTRYPOINT ["./entrypoint.sh"]
