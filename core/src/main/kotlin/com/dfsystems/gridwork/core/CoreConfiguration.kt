package com.dfsystems.gridwork.core

import org.springframework.boot.autoconfigure.domain.EntityScan
import org.springframework.context.annotation.ComponentScan
import org.springframework.context.annotation.Configuration
import org.springframework.data.jpa.repository.config.EnableJpaRepositories
import org.springframework.transaction.annotation.EnableTransactionManagement

/**
 * Everything both deployables need, in one import.
 *
 * A Spring Boot application scans its own package downwards, so neither api/
 * nor worker/ would find anything in core on its own. Rather than have each
 * application list the same three packages and drift apart, they import this.
 *
 * Repositories and entities are named explicitly because they no longer live
 * under an application's package, and Boot's auto configuration would not
 * find them either.
 */
@Configuration
@ComponentScan(basePackages = ["com.dfsystems.gridwork.core"])
@EnableJpaRepositories(basePackages = ["com.dfsystems.gridwork.core.persistence"])
@EntityScan(basePackages = ["com.dfsystems.gridwork.core.persistence"])
@EnableTransactionManagement
class CoreConfiguration
