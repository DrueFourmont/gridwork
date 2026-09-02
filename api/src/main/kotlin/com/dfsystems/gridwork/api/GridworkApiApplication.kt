package com.dfsystems.gridwork.api

import com.dfsystems.gridwork.core.CoreConfiguration
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration
import org.springframework.boot.runApplication
import org.springframework.context.annotation.Import

/**
 * UserDetailsServiceAutoConfiguration is excluded on purpose. Left on, Spring
 * Boot invents a single in-memory user and prints its generated password to
 * standard out on every start, which is a credential in the logs and against
 * the rule in CLAUDE.md. There is nothing to authenticate in Phase 0 anyway.
 * Phase 1 supplies a real UserDetailsService backed by Postgres.
 */
@SpringBootApplication(exclude = [UserDetailsServiceAutoConfiguration::class])
@Import(CoreConfiguration::class)
class GridworkApiApplication

fun main(args: Array<String>) {
    runApplication<GridworkApiApplication>(*args)
}
