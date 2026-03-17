plugins {
    java
    id("org.springframework.boot") version "3.5.8"
    id("io.spring.dependency-management") version "1.1.5"
}

group = "org.cardanofoundation"
version = "0.0.1-SNAPSHOT"
description = "keri-ui"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenLocal()
    mavenCentral()
    maven {
        name = "Central Portal Snapshots"
        url = uri("https://central.sonatype.com/repository/maven-snapshots/")

        // Only search this repository for the specific dependency
        content {
            includeModule("org.cardanofoundation", "signify")
        }
    }
}
extra["springBootVersion"] = "3.5.8"
extra["flyway.version"] = "10.20.1"

dependencies {
    implementation("org.cardanofoundation:signify:0.1.2-PR62-d6aea58")
    implementation("org.springframework.boot:spring-boot-starter-web")
    // Spring Data JPA for database access
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")
    // PostgreSQL JDBC driver
    runtimeOnly("org.postgresql:postgresql")
    // Jackson - let Spring Boot manage versions
    implementation("com.fasterxml.jackson.core:jackson-databind")
    implementation("com.fasterxml.jackson.core:jackson-core")
    implementation("com.fasterxml.jackson.core:jackson-annotations")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    compileOnly("org.projectlombok:lombok:1.18.32")
    annotationProcessor("org.projectlombok:lombok:1.18.32")
    // KERI

    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test> {
    useJUnitPlatform()
}
