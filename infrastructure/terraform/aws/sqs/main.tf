# BofA SQS FIFO Queues — Replacing IBM MQ
# Status: Queue definitions COMPLETED, wired to Lambda event source mappings
# Critical: Preserves message ordering guarantees from IBM MQ

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

# Fraud alerts — FIFO for ordering guarantee, high priority
resource "aws_sqs_queue" "fraud_alerts" {
  name                        = "bofa-fraud-alerts-${var.environment}.fifo"
  fifo_queue                  = true
  content_based_deduplication = false
  deduplication_scope         = "messageGroup"
  fifo_throughput_limit       = "perMessageGroupId"

  visibility_timeout_seconds = 60
  message_retention_seconds  = 1209600
  receive_wait_time_seconds  = 10

  redrive_policy = jsonencode({
    deadLetterTargetArn = aws_sqs_queue.fraud_alerts_dlq.arn
    maxReceiveCount     = 3
  })

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

  kms_master_key_id = "alias/bofa-sqs-key"

  tags = {
    Service     = "notification"
    Type        = "dead-letter"
    Environment = var.environment
  }
}

# Transaction confirmations — FIFO per account
resource "aws_sqs_queue" "transaction_confirmations" {
  name                        = "bofa-txn-confirmations-${var.environment}.fifo"
  fifo_queue                  = true
  content_based_deduplication = true
  deduplication_scope         = "messageGroup"
  fifo_throughput_limit       = "perMessageGroupId"

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

  kms_master_key_id = "alias/bofa-sqs-key"

  tags = {
    Service     = "notification"
    Type        = "dead-letter"
    Environment = var.environment
  }
}

# Balance warnings — FIFO per account
resource "aws_sqs_queue" "balance_warnings" {
  name                        = "bofa-balance-warnings-${var.environment}.fifo"
  fifo_queue                  = true
  content_based_deduplication = true
  deduplication_scope         = "messageGroup"
  fifo_throughput_limit       = "perMessageGroupId"

  visibility_timeout_seconds = 60
  message_retention_seconds  = 604800
  receive_wait_time_seconds  = 10

  redrive_policy = jsonencode({
    deadLetterTargetArn = aws_sqs_queue.balance_warnings_dlq.arn
    maxReceiveCount     = 3
  })

  kms_master_key_id = "alias/bofa-sqs-key"

  tags = {
    Service     = "notification"
    Environment = var.environment
  }
}

resource "aws_sqs_queue" "balance_warnings_dlq" {
  name                      = "bofa-balance-warnings-dlq-${var.environment}.fifo"
  fifo_queue                = true
  message_retention_seconds = 1209600

  kms_master_key_id = "alias/bofa-sqs-key"

  tags = {
    Service     = "notification"
    Type        = "dead-letter"
    Environment = var.environment
  }
}

# Outputs for Lambda wiring
output "fraud_alerts_queue_arn" {
  value = aws_sqs_queue.fraud_alerts.arn
}

output "fraud_alerts_queue_url" {
  value = aws_sqs_queue.fraud_alerts.url
}

output "txn_confirmations_queue_arn" {
  value = aws_sqs_queue.transaction_confirmations.arn
}

output "txn_confirmations_queue_url" {
  value = aws_sqs_queue.transaction_confirmations.url
}

output "balance_warnings_queue_arn" {
  value = aws_sqs_queue.balance_warnings.arn
}

output "balance_warnings_queue_url" {
  value = aws_sqs_queue.balance_warnings.url
}
