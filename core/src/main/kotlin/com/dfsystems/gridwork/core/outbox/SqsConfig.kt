package com.dfsystems.gridwork.core.outbox

import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.sqs.SqsClient
import java.net.URI

@Configuration
@ConditionalOnProperty(name = ["gridwork.sqs.queue-url"], matchIfMissing = false)
class SqsConfig {

    /**
     * One client for both the relay and the worker.
     *
     * When an endpoint override is set it points at LocalStack, and static
     * dummy credentials are used because LocalStack does not check them.
     * Without an override this is a real AWS client and credentials come from
     * the default provider chain, which on EKS means the pod's IAM role. No
     * AWS keys are ever read from configuration.
     */
    @Bean
    fun sqsClient(
        @Value("\${gridwork.sqs.region}") region: String,
        @Value("\${gridwork.sqs.endpoint:}") endpoint: String,
    ): SqsClient {
        val builder = SqsClient.builder().region(Region.of(region))
        return if (endpoint.isBlank()) {
            builder.credentialsProvider(DefaultCredentialsProvider.builder().build()).build()
        } else {
            builder
                .endpointOverride(URI.create(endpoint))
                .credentialsProvider(
                    StaticCredentialsProvider.create(AwsBasicCredentials.create("local", "local")),
                )
                .build()
        }
    }
}
