package com.dfsystems.gridwork.api.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.data.redis.connection.RedisConnectionFactory
import org.springframework.data.redis.listener.RedisMessageListenerContainer

/**
 * The Redis listener container lives here rather than beside the websocket
 * configuration, and that is not tidiness.
 *
 * WebSocketConfig depends on the handler, the handler depends on the
 * subscription registry, and the registry depends on this container. Declaring
 * the container inside WebSocketConfig closes that loop and Spring refuses to
 * start with a BeanCurrentlyInCreationException. Pulling it into its own
 * configuration breaks the cycle.
 */
@Configuration
class RedisConfig {

    /**
     * One container for the whole replica. Subscriptions are added and removed
     * per sheet as viewers come and go, so a replica does no work for a sheet
     * nobody on it is watching.
     */
    @Bean
    fun redisMessageListenerContainer(factory: RedisConnectionFactory): RedisMessageListenerContainer =
        RedisMessageListenerContainer().apply { setConnectionFactory(factory) }
}
