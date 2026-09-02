plugins {
    alias(libs.plugins.kotlin.spring)
    alias(libs.plugins.kotlin.jpa)
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dependency.management)
}

/**
 * Shared application services, used by both deployables.
 *
 * CLAUDE.md requires that the worker perform automation actions THROUGH the
 * same domain services the API uses, so versions, history, permissions, and
 * the outbox apply to an automation exactly as they do to a human edit. That
 * is only true if there is one copy of those services, which is what this
 * module is. See ADR 0008.
 *
 * It deliberately has no spring-boot-starter-web. The worker must not gain a
 * servlet container by depending on this, so anything HTTP shaped, controllers,
 * problem+json, security, websockets, stays in api/.
 */

// A library, not an application. The Spring Boot plugin is applied for its
// dependency management, then told not to build an executable jar, and the
// plain jar is re-enabled so api/ and worker/ can depend on this.
tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar") { enabled = false }
tasks.named<Jar>("jar") {
    enabled = true
    // Without this the artifact is published as core-VERSION-plain.jar,
    // because the Boot plugin reserves the unclassified name for bootJar.
    // bootJar is disabled here, so the plain name is free.
    archiveClassifier.set("")
}

dependencies {
    api(project(":domain"))

    api(libs.spring.boot.starter.data.jpa)
    api(libs.spring.boot.starter.data.redis)
    // Brings the auto configured ObjectMapper. It arrives free with
    // spring-boot-starter-web in the API, but the worker has no web starter by
    // design, and core's event publisher serialises to JSON.
    api(libs.spring.boot.starter.json)
    // The relay publishes to SQS, and the worker consumes from it, so the
    // client belongs to the code they share rather than to either one.
    api(platform(libs.aws.sdk.bom))
    api(libs.aws.sdk.sqs)
    implementation(libs.jackson.module.kotlin)
    implementation(libs.kotlin.reflect)

    runtimeOnly(libs.postgresql)

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.kotest.assertions.core)
    testRuntimeOnly(libs.junit.platform.launcher)
}
