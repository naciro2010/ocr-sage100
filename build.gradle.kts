import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("org.springframework.boot") version "4.0.5"
    id("io.spring.dependency-management") version "1.1.7"
    kotlin("jvm") version "2.2.20"
    kotlin("plugin.spring") version "2.2.20"
    kotlin("plugin.jpa") version "2.2.20"
}

group = "com.madaef.recondoc"
version = "1.0.0"

// Flyway gere par Spring Boot : on force une version recente pour couvrir
// PostgreSQL 18 (Railway / prod). La version par defaut de SB 4.0.5 refuse
// PG >= 18 avec l'avertissement "support has not been tested". Bump ciblee
// pour eviter tout blocage operationnel sur les migrations.
extra["flyway.version"] = "11.14.0"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    // Spring Boot
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-security")

    // Kotlin
    // Spring Boot 4 adopte Jackson 3 (group `tools.jackson.*`).
    // Le module Kotlin vit desormais sous `tools.jackson.module:jackson-module-kotlin`.
    implementation("tools.jackson.module:jackson-module-kotlin")
    // Hibernate 7 conserve un JacksonJsonFormatMapper cable sur Jackson 2
    // (`com.fasterxml.jackson.*`) pour deserialiser les colonnes JSON mappees
    // en @JdbcTypeCode(SqlTypes.JSON). Sans le module Kotlin v2, les data
    // classes Kotlin (ValidationEvidence, ...) ne peuvent pas etre recreees
    // car elles n'ont pas de constructeur sans-argument.
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.19.2")
    implementation("org.jetbrains.kotlin:kotlin-reflect")

    // Database
    runtimeOnly("org.postgresql:postgresql")
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")

    // Apache Tika (OCR / text extraction)
    implementation("org.apache.tika:tika-core:3.0.0")
    implementation("org.apache.tika:tika-parsers-standard-package:3.0.0")

    // Tesseract OCR via Tess4J (scanned documents)
    implementation("net.sourceforge.tess4j:tess4j:5.13.0")

    // Tabula (table extraction from PDFs)
    implementation("technology.tabula:tabula:1.0.5") {
        exclude(group = "org.slf4j", module = "slf4j-simple")
    }

    // HTTP client for Claude API & Sage 1000
    implementation("org.springframework.boot:spring-boot-starter-webflux")

    // Resilience (circuit breaker / rate limiter / bulkhead) around Claude API
    // resilience4j-spring-boot3 est compatible Spring Boot 4 (Jakarta EE 11 / Framework 7)
    implementation("io.github.resilience4j:resilience4j-spring-boot3:2.3.0")
    implementation("io.github.resilience4j:resilience4j-reactor:2.3.0")
    // Spring Boot 4 : `spring-boot-starter-aop` a ete renomme
    // `spring-boot-starter-aspectj` dans le BOM 4.0.x (l'ancien artefact n'est
    // plus publie). Fournit toujours spring-aop + aspectjweaver pour Resilience4j.
    implementation("org.springframework.boot:spring-boot-starter-aspectj")

    // In-process cache for hot config lookups (rule config) — avoids Redis on Railway
    implementation("org.springframework.boot:spring-boot-starter-cache")
    implementation("com.github.ben-manes.caffeine:caffeine")

    // Structured JSON logs for Railway aggregation
    implementation("net.logstash.logback:logstash-logback-encoder:8.0")

    // Prometheus metrics endpoint (/actuator/prometheus)
    implementation("io.micrometer:micrometer-registry-prometheus")

    // Excel export
    implementation("org.apache.poi:poi-ooxml:5.2.5")

    // QR code decoding (attestation fiscale DGI verification)
    implementation("com.google.zxing:core:3.5.3")
    implementation("com.google.zxing:javase:3.5.3")

    // S3-compatible object storage (AWS, Wasabi, Backblaze B2, MinIO, Scaleway).
    // Used only when storage.type=s3 — otherwise inert. BOM pins consistent versions.
    implementation(platform("software.amazon.awssdk:bom:2.28.16"))
    implementation("software.amazon.awssdk:s3")
    implementation("software.amazon.awssdk:s3-transfer-manager")
    implementation("software.amazon.awssdk:sts") {
        because("required when using IAM role-based credentials (Railway envs fall back to static keys)")
    }

    // Test
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    // Spring Boot 4 : @AutoConfigureMockMvc et l'autoconfig MockMvc sont
    // sortis de spring-boot-starter-test, deplaces dans un module dedie.
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.springframework.security:spring-security-test")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testRuntimeOnly("com.h2database:h2")
}

kotlin {
    compilerOptions {
        freeCompilerArgs.addAll("-Xjsr305=strict")
        // L'enum JvmTarget de Kotlin 2.2.20 ne contient pas encore JVM_25.
        // On cible le bytecode 21 (fin LTS, supporte par 100% des JRE cibles,
        // y compris JDK 25 en runtime). Les nouveautes JDK 25 accessibles
        // restent exploitables via les APIs a runtime ; on ne perd rien sauf
        // quelques bytecodes record/pattern-matching tres recents inutilises ici.
        jvmTarget.set(JvmTarget.JVM_21)
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
}
