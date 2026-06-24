# BofA Notification Service - AWS Lambda Migration
# Status: APPLICATION CODE MIGRATED — handlers implemented in notification-lambda module
# Migration: Spring Boot 2.7 + IBM MQ + Oracle + LDAP -> Lambda + SQS FIFO + RDS PostgreSQL + Cognito

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

variable "vpc_id" {
  description = "VPC ID for Lambda functions (must be in BofA private subnet)"
}

variable "subnet_ids" {
  type        = list(string)
  description = "Private subnet IDs for Lambda VPC configuration"
}

variable "security_group_ids" {
  type        = list(string)
  description = "Security group IDs for Lambda (RDS access, SQS access)"
  default     = []
}

variable "db_host" {
  description = "RDS PostgreSQL endpoint (use RDS Proxy for Lambda)"
}

variable "db_port" {
  default = "5432"
}

variable "db_name" {
  default = "notifications"
}

variable "db_user" {
  default = "notif_admin"
}

variable "db_password_secret_arn" {
  description = "ARN of Secrets Manager secret for DB password"
}

variable "cognito_user_pool_id" {
  description = "Cognito User Pool ID (replaces LDAP)"
}

variable "sqs_fraud_alert_queue_url" {
  description = "SQS FIFO queue URL for fraud alerts"
}

variable "sqs_txn_confirm_queue_url" {
  description = "SQS FIFO queue URL for transaction confirmations"
}

variable "sqs_balance_warning_queue_url" {
  description = "SQS FIFO queue URL for balance warnings"
}

variable "dd_api_key_secret_arn" {
  description = "ARN of Secrets Manager secret for Datadog API key"
  default     = ""
}

# Lambda execution role
resource "aws_iam_role" "notification_lambda_role" {
  name = "bofa-notification-lambda-${var.environment}"

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Action = "sts:AssumeRole"
      Effect = "Allow"
      Principal = {
        Service = "lambda.amazonaws.com"
      }
    }]
  })
}

# IAM policy for Lambda access to SQS, RDS, Secrets Manager, CloudWatch
resource "aws_iam_role_policy" "notification_lambda_policy" {
  name = "bofa-notification-lambda-policy-${var.environment}"
  role = aws_iam_role.notification_lambda_role.id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Effect = "Allow"
        Action = [
          "sqs:ReceiveMessage",
          "sqs:DeleteMessage",
          "sqs:GetQueueAttributes",
          "sqs:SendMessage"
        ]
        Resource = "arn:aws:sqs:*:*:bofa-*"
      },
      {
        Effect = "Allow"
        Action = [
          "secretsmanager:GetSecretValue"
        ]
        Resource = var.db_password_secret_arn
      },
      {
        Effect = "Allow"
        Action = [
          "kms:Decrypt"
        ]
        Resource = "*"
        Condition = {
          StringEquals = {
            "kms:ViaService" = "secretsmanager.us-east-1.amazonaws.com"
          }
        }
      },
      {
        Effect = "Allow"
        Action = [
          "logs:CreateLogGroup",
          "logs:CreateLogStream",
          "logs:PutLogEvents"
        ]
        Resource = "arn:aws:logs:*:*:*"
      },
      {
        Effect = "Allow"
        Action = [
          "ec2:CreateNetworkInterface",
          "ec2:DescribeNetworkInterfaces",
          "ec2:DeleteNetworkInterface"
        ]
        Resource = "*"
      },
      {
        Effect = "Allow"
        Action = [
          "xray:PutTraceSegments",
          "xray:PutTelemetryRecords"
        ]
        Resource = "*"
      }
    ]
  })
}

locals {
  common_env_vars = {
    DEPLOY_ENV                  = var.environment
    DB_HOST                     = var.db_host
    DB_PORT                     = var.db_port
    DB_NAME                     = var.db_name
    DB_USER                     = var.db_user
    DB_PASSWORD_SECRET_ARN      = var.db_password_secret_arn
    COGNITO_USER_POOL_ID        = var.cognito_user_pool_id
    SQS_FRAUD_ALERT_QUEUE_URL   = var.sqs_fraud_alert_queue_url
    SQS_TXN_CONFIRM_QUEUE_URL   = var.sqs_txn_confirm_queue_url
    SQS_BALANCE_WARNING_QUEUE_URL = var.sqs_balance_warning_queue_url
  }
}

# Fraud alert processor - HIGHEST PRIORITY
# SLA: Must process within 30 seconds
# Provisioned concurrency: 100 (eliminates cold start for critical path)
resource "aws_lambda_function" "fraud_alert_processor" {
  function_name = "bofa-fraud-alert-processor-${var.environment}"
  runtime       = "java11"
  handler       = "com.bofa.notifications.lambda.handler.FraudAlertHandler::handleRequest"
  role          = aws_iam_role.notification_lambda_role.arn

  memory_size = 1024
  timeout     = 30

  filename = "placeholder.zip"

  snap_start {
    apply_on = "PublishedVersions"
  }

  vpc_config {
    subnet_ids         = var.subnet_ids
    security_group_ids = var.security_group_ids
  }

  environment {
    variables = local.common_env_vars
  }

  reserved_concurrent_executions = 200

  tracing_config {
    mode = "Active"
  }

  tags = {
    Service     = "notification"
    Team        = "notification-squad"
    Compliance  = "sox-404"
    Priority    = "critical"
    Environment = var.environment
  }
}

# Transaction confirmation processor
resource "aws_lambda_function" "transaction_confirm_processor" {
  function_name = "bofa-txn-confirm-processor-${var.environment}"
  runtime       = "java11"
  handler       = "com.bofa.notifications.lambda.handler.TransactionConfirmHandler::handleRequest"
  role          = aws_iam_role.notification_lambda_role.arn

  memory_size = 512
  timeout     = 60

  filename = "placeholder.zip"

  snap_start {
    apply_on = "PublishedVersions"
  }

  vpc_config {
    subnet_ids         = var.subnet_ids
    security_group_ids = var.security_group_ids
  }

  environment {
    variables = local.common_env_vars
  }

  tracing_config {
    mode = "Active"
  }

  tags = {
    Service     = "notification"
    Team        = "notification-squad"
    Environment = var.environment
  }
}

# Balance warning processor
resource "aws_lambda_function" "balance_warning_processor" {
  function_name = "bofa-balance-warning-processor-${var.environment}"
  runtime       = "java11"
  handler       = "com.bofa.notifications.lambda.handler.BalanceWarningHandler::handleRequest"
  role          = aws_iam_role.notification_lambda_role.arn

  memory_size = 256
  timeout     = 30

  filename = "placeholder.zip"

  snap_start {
    apply_on = "PublishedVersions"
  }

  vpc_config {
    subnet_ids         = var.subnet_ids
    security_group_ids = var.security_group_ids
  }

  environment {
    variables = merge(local.common_env_vars, {
      BALANCE_COOLDOWN_HOURS = "4"
    })
  }

  tracing_config {
    mode = "Active"
  }

  tags = {
    Service     = "notification"
    Team        = "notification-squad"
    Environment = var.environment
  }
}

# API Gateway handler for REST endpoints
resource "aws_lambda_function" "notification_api" {
  function_name = "bofa-notification-api-${var.environment}"
  runtime       = "java11"
  handler       = "com.bofa.notifications.lambda.handler.NotificationApiHandler::handleRequest"
  role          = aws_iam_role.notification_lambda_role.arn

  memory_size = 512
  timeout     = 30

  filename = "placeholder.zip"

  snap_start {
    apply_on = "PublishedVersions"
  }

  vpc_config {
    subnet_ids         = var.subnet_ids
    security_group_ids = var.security_group_ids
  }

  environment {
    variables = local.common_env_vars
  }

  tracing_config {
    mode = "Active"
  }

  tags = {
    Service     = "notification"
    Team        = "notification-squad"
    Environment = var.environment
  }
}
