// Pure Kotlin. No Spring, no framework imports, ever. This module holds value
// types, the rule evaluator, and the version rule, and it is exhaustively
// tested without a container. If a Spring dependency appears here, that is a
// bug, not a shortcut.

dependencies {
    testImplementation(libs.kotlin.test.junit5)
    testImplementation(libs.kotest.assertions.core)
    testRuntimeOnly(libs.junit.platform.launcher)
}
