#!/bin/bash
# Runs inside the LocalStack container once it is ready.
#
# Creates the automation queue and its dead letter queue, with a redrive
# policy. The DLQ is not decoration: without maxReceiveCount, a message that
# always fails is retried until the retention period expires, and it blocks
# nothing but it also tells nobody. With it, SQS moves the poison message aside
# after five attempts and it can be looked at.
set -euo pipefail

awslocal sqs create-queue --queue-name gridwork-events-dlq

DLQ_ARN=$(awslocal sqs get-queue-attributes \
  --queue-url "http://localhost:4566/000000000000/gridwork-events-dlq" \
  --attribute-names QueueArn --query 'Attributes.QueueArn' --output text)

awslocal sqs create-queue --queue-name gridwork-events --attributes "{
  \"VisibilityTimeout\": \"30\",
  \"RedrivePolicy\": \"{\\\"deadLetterTargetArn\\\":\\\"${DLQ_ARN}\\\",\\\"maxReceiveCount\\\":\\\"5\\\"}\"
}"

echo "gridwork: created gridwork-events with a dead letter queue after 5 receives"
