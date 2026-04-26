import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("org.springframework.boot") version "4.0.5"
    id("io.spring.dependency-management") version "1.1.7"
    kotlin("jvm") version "2.3.20"
    kotlin("plugin.spring") version "2.3.20"
    kotlin("plugin.jpa") version "2.3.20"
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

// Configuration dediee pour resoudre le jar mockito-core et le passer en
// -javaagent aux tests (JDK 21+ durcit l'auto-attach, JDK 25 le refuse).
val mockitoAgent: Configuration = configurations.create("mockitoAgent")

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
    // Spring Boot 4 a sorti l'autoconfig Flyway de spring-boot-autoconfigure
    // vers le module dedie spring-boot-flyway, agrege par spring-boot-starter-flyway.
    // SANS ce starter, FlywayAutoConfiguration n'est jamais decouvert et
    // `spring.flyway.enabled: true` est ignore — les migrations ne tournent pas
    // au boot (cause racine de l'incident Railway 2026-04-26 : la colonne
    // attestation_fiscale.type_attestation manquait car V35/V36 jamais appliquees).
    implementation("org.springframework.boot:spring-boot-starter-flyway")
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
    // Mockito inline utilise Byte Buddy pour instrumenter les classes finales ;
    // sur JDK 25 l'auto-attach (Attach API) est refuse par defaut, on fournit
    // donc mockito-core comme `-javaagent` explicite (voir tasks Test plus bas).
    mockitoAgent("org.mockito:mockito-core") { isTransitive = false }
}

kotlin {
    compilerOptions {
        freeCompilerArgs.addAll("-Xjsr305=strict")
        // Kotlin 2.3.x supporte JvmTarget.JVM_25 : bytecode Java 25 full.
        jvmTarget.set(JvmTarget.JVM_25)
    }
}

// On pin explicitement la release cote Java pour rester aligne avec Kotlin
// (cf Gradle KGP : "Inconsistent JVM Target Compatibility" sinon).
tasks.withType<JavaCompile>().configureEach {
    options.release.set(25)
}

tasks.withType<Test> {
    useJUnitPlatform()
    // JDK 24+ restreint l'auto-attach d'agents ; JDK 25 le refuse par defaut,
    // ce qui casse Mockito inline (ByteBuddy self-attach via Attach API).
    // On pousse mockito-core comme -javaagent explicite : c'est la methode
    // officielle recommandee par Mockito a partir de 5.12+ pour JDK 21+.
    jvmArgumentProviders += CommandLineArgumentProvider {
        listOf("-javaagent:${mockitoAgent.asPath}")
    }
}
