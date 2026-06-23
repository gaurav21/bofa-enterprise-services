# BofA SQS FIFO Queues - Replacing IBM MQ
# Status: STUB - Queue definitions created, not yet connected to application
# Critical: Must preserve message ordering guarantees from IBM MQ

terraform {
  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 5.0"
    }
  }
}

variable "environment" {
  default = "staging"
}

# Fraud alerts - FIFO for ordering guarantee, high priority
resource "aws_sqs_queue" "fraud_alerts" {
  name                        = "bofa-fraud-alerts-${var.environment}.fifo"
  fifo_queue                  = true
  content_based_deduplication = false
  deduplication_scope         = "messageGroup"
  fifo_throughput_limit       = "perMessageGroupId"

  # Fraud alerts: short visibility for fast retry
  visibility_timeout_seconds = 60
  message_retention_seconds  = 1209600 # 14 days
  receive_wait_time_seconds  = 10      # Long polling

  # Dead letter queue for failed processing
  redrive_policy = jsonencode({
    deadLetterTargetArn = aws_sqs_queue.fraud_alerts_dlq.arn
    maxReceiveCount     = 3
  })

  # Server-side encryption with KMS
  sqs_managed_sse_enabled = false
  kms_master_key_id      = "alias/bofa-sqs-key"

  tags = {
    Service     = "notification"
    Priority    = "critical"
    Compliance  = "sox-404"
    Environment = var.environment
  }
}

resource "aws_sqs_queue" "fraud_alerts_dlq" {
  name                      = "bofa-fraud-alerts-dlq-${var.environment}.fifo"
  fifo_queue                = true
  message_retention_seconds = 1209600

  tags = {
    Service     = "notification"
    Type        = "dead-letter"
    Environment = var.environment
  }
}

# Transaction confirmations - FIFO per account
resource "aws_sqs_queue" "transaction_confirmations" {
  name                        = "bofa-txn-confirmations-${var.environment}.fifo"
  fifo_queue                  = true
  content_based_deduplication = true
  deduplication_scope         = "messageGroup"

  visibility_timeout_seconds = 120
  message_retention_seconds  = 1209600
  receive_wait_time_seconds  = 10

  redrive_policy = jsonencode({
    deadLetterTargetArn = aws_sqs_queue.txn_confirm_dlq.arn
    maxReceiveCount     = 5
  })

  kms_master_key_id = "alias/bofa-sqs-key"

  tags = {
    Service     = "notification"
    Environment = var.environment
  }
}

resource "aws_sqs_queue" "txn_confirm_dlq" {
  name                      = "bofa-txn-confirmations-dlq-${var.environment}.fifo"
  fifo_queue                = true
  message_retention_seconds = 1209600

  tags = {
    Service = "notification"
    Type    = "dead-letter"
  }
}

# Balance warnings - FIFO per account
resource "aws_sqs_queue" "balance_warnings" {
  name                        = "bofa-balance-warnings-${var.environment}.fifo"
  fifo_queue                  = true
  content_based_deduplication = true

  visibility_timeout_seconds = 60
  message_retention_seconds  = 604800 # 7 days
  receive_wait_time_seconds  = 10

  kms_master_key_id = "alias/bofa-sqs-key"

  tags = {
    Service     = "notification"
    Environment = var.environment
  }
}

# Lambda trigger mappings
resource "aws_lambda_event_source_mapping" "fraud_alert_trigger" {
  event_source_arn = aws_sqs_queue.fraud_alerts.arn
  function_name    = "bofa-fraud-alert-processor-${var.environment}"
  batch_size       = 1   # Process one at a time for ordering
  enabled          = false # TODO: Enable after migration testing

  scaling_config {
    maximum_concurrency = 50
  }
}
