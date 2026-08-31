plugins {
    alias(libs.plugins.kotlin.spring)
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dependency.management)
}

dependencies {
    implementation(project(":domain"))

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
    testRuntimeOnly(libs.junit.platform.launcher)
}
