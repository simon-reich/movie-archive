plugins {
    java
    id("org.springframework.boot") version "3.5.0"
    id("io.spring.dependency-management") version "1.1.7"
}

group = "de.moviearchive"
version = "0.0.1-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

repositories {
    mavenCentral()
}

val jjwtVersion = "0.12.6"
val mapstructVersion = "1.6.3"
val opensearchVersion = "2.19.0"
val wiremockVersion = "3.13.0"
val greenmailVersion = "2.1.3"
val bucket4jVersion = "8.10.1"

dependencies {
    // Spring Boot starters
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-webflux")
    implementation("org.springframework.boot:spring-boot-starter-mail")
    implementation("org.springframework.boot:spring-boot-starter-thymeleaf")

    // Async / Retry
    implementation("org.springframework.retry:spring-retry")
    implementation("org.springframework:spring-aspects")

    // DB / Migrations
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")

    // JWT
    implementation("io.jsonwebtoken:jjwt-api:$jjwtVersion")
    runtimeOnly("io.jsonwebtoken:jjwt-impl:$jjwtVersion")
    runtimeOnly("io.jsonwebtoken:jjwt-jackson:$jjwtVersion")

    // OpenSearch
    implementation("org.opensearch.client:opensearch-java:$opensearchVersion")
    implementation("org.apache.httpcomponents.client5:httpclient5:5.4.4")

    // MapStruct
    implementation("org.mapstruct:mapstruct:$mapstructVersion")
    annotationProcessor("org.mapstruct:mapstruct-processor:$mapstructVersion")

    // Rate Limiting
    implementation("com.bucket4j:bucket4j-core:$bucket4jVersion")

    // Lombok
    compileOnly("org.projectlombok:lombok")
    annotationProcessor("org.projectlombok:lombok")
    // Lombok must come before MapStruct in annotationProcessor order
    annotationProcessor("org.projectlombok:lombok-mapstruct-binding:0.2.0")

    // Test dependencies
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.security:spring-security-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.testcontainers:postgresql")
    testImplementation("org.wiremock:wiremock-standalone:$wiremockVersion")
    testImplementation("com.icegreen:greenmail-junit5:$greenmailVersion")
    testCompileOnly("org.projectlombok:lombok")
    testAnnotationProcessor("org.projectlombok:lombok")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// Load .env from repo root (parent of backend/) or backend/ itself
val dotenv: Map<String, String> = listOf(file("../.env"), file(".env"))
    .firstOrNull { it.exists() }
    ?.readLines()
    ?.filter { line -> line.isNotBlank() && !line.startsWith("#") }
    ?.mapNotNull { line ->
        val idx = line.indexOf('=')
        if (idx > 0) {
            val value = line.substring(idx + 1).trim()
            if (value.isNotEmpty()) line.substring(0, idx).trim() to value else null
        } else null
    }
    ?.toMap()
    ?: emptyMap()

tasks.named<org.springframework.boot.gradle.tasks.run.BootRun>("bootRun") {
    environment(dotenv)
}

tasks.withType<Test> {
    useJUnitPlatform()
    environment(dotenv)
}

// JaCoCo coverage
apply(plugin = "jacoco")
tasks.named<JacocoReport>("jacocoTestReport") {
    reports {
        xml.required = true
        html.required = true
    }
}
tasks.named<JacocoCoverageVerification>("jacocoTestCoverageVerification") {
    violationRules {
        rule {
            element = "BUNDLE"
            limit {
                counter = "LINE"
                minimum = "0.75".toBigDecimal()
            }
        }
    }
}
tasks.named("check") {
    dependsOn("jacocoTestCoverageVerification")
}
