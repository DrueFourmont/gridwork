plugins {
    alias(libs.plugins.kotlin.spring)
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dependency.management)
}

dependencies {
    implementation(project(":domain"))
    // The same services the API uses, so an automation action goes through
    // the same versioning, history, permissions, and outbox as a human edit.
    // See CLAUDE.md and ADR 0008.
    implementation(project(":core"))

    // No web starter. The worker pulls from SQS, it does not serve HTTP.
    implementation(libs.spring.boot.starter)
    implementation(platform(libs.aws.sdk.bom))
    implementation(libs.aws.sdk.sqs)
    implementation(libs.jackson.module.kotlin)
    implementation(libs.kotlin.reflect)
    implementation(libs.logstash.logback.encoder)

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.kotest.assertions.core)
    testImplementation(platform(libs.testcontainers.bom))
    testImplementation(libs.testcontainers.junit.jupiter)
    testImplementation(libs.testcontainers.localstack)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.flyway.core)
    testRuntimeOnly(libs.flyway.database.postgresql)
    testRuntimeOnly(libs.junit.platform.launcher)
}

/**
 * The API owns the schema, and the worker's integration tests need it to
 * exist. Copying at build time rather than checking in a second copy, because
 * two copies of a migration set drift and the drift is discovered in
 * production.
 */
val copyMigrations by tasks.registering(Copy::class) {
    from(project(":api").file("src/main/resources/db/migration"))
    into(layout.buildDirectory.dir("resources/test/db/migration"))
}

tasks.named("processTestResources") { finalizedBy(copyMigrations) }
tasks.named("test") { dependsOn(copyMigrations) }
