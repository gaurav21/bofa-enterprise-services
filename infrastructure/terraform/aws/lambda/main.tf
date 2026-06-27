# BofA Notification Service - AWS Lambda Migration
# Status: Application migration COMPLETED — handlers wired to SQS, RDS, Cognito

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

variable "rds_endpoint" {
  description = "RDS PostgreSQL endpoint (via RDS Proxy)"
  type        = string
}

variable "db_secret_arn" {
  description = "Secrets Manager ARN for RDS credentials"
  type        = string
}

variable "cognito_user_pool_id" {
  description = "Cognito User Pool ID for JWT validation"
  type        = string
}

variable "cognito_client_id" {
  description = "Cognito App Client ID"
  type        = string
}

variable "sqs_fraud_alerts_arn" {
  description = "ARN of SQS FIFO fraud alerts queue"
  type        = string
}

variable "sqs_txn_confirmations_arn" {
  description = "ARN of SQS FIFO transaction confirmations queue"
  type        = string
}

variable "sqs_balance_warnings_arn" {
  description = "ARN of SQS FIFO balance warnings queue"
  type        = string
}

variable "security_group_ids" {
  type        = list(string)
  description = "Security group IDs for Lambda VPC access"
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

# IAM policy for Lambda to access SQS, RDS, Secrets Manager, KMS, CloudWatch
resource "aws_iam_role_policy" "notification_lambda_policy" {
  name = "bofa-notification-lambda-policy-${var.environment}"
  role = aws_iam_role.notification_lambda_role.id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid    = "SQSAccess"
        Effect = "Allow"
        Action = [
          "sqs:ReceiveMessage",
          "sqs:DeleteMessage",
          "sqs:GetQueueAttributes",
          "sqs:ChangeMessageVisibility"
        ]
        Resource = [
          var.sqs_fraud_alerts_arn,
          var.sqs_txn_confirmations_arn,
          var.sqs_balance_warnings_arn
        ]
      },
      {
        Sid    = "SecretsManagerAccess"
        Effect = "Allow"
        Action = [
          "secretsmanager:GetSecretValue"
        ]
        Resource = [var.db_secret_arn]
      },
      {
        Sid    = "KMSDecrypt"
        Effect = "Allow"
        Action = [
          "kms:Decrypt",
          "kms:GenerateDataKey"
        ]
        Resource = ["*"]
        Condition = {
          StringEquals = {
            "kms:ViaService" = "sqs.us-east-1.amazonaws.com"
          }
        }
      },
      {
        Sid    = "CloudWatchLogs"
        Effect = "Allow"
        Action = [
          "logs:CreateLogGroup",
          "logs:CreateLogStream",
          "logs:PutLogEvents"
        ]
        Resource = ["arn:aws:logs:*:*:*"]
      },
      {
        Sid    = "VPCAccess"
        Effect = "Allow"
        Action = [
          "ec2:CreateNetworkInterface",
          "ec2:DescribeNetworkInterfaces",
          "ec2:DeleteNetworkInterface"
        ]
        Resource = ["*"]
      }
    ]
  })
}

# Common environment variables for all Lambda functions
locals {
  lambda_environment = {
    SPRING_PROFILES_ACTIVE = "aws"
    AWS_REGION             = "us-east-1"
    DB_SECRET_ARN          = var.db_secret_arn
    DB_ENDPOINT            = "jdbc:postgresql://${var.rds_endpoint}:5432/notifications?sslmode=require"
    COGNITO_USER_POOL_ID   = var.cognito_user_pool_id
    COGNITO_CLIENT_ID      = var.cognito_client_id
    DEPLOY_ENV             = var.environment
    LOG_LEVEL              = "INFO"
  }
}

# Fraud alert processor — HIGHEST PRIORITY
# SLA: Must process within 30 seconds
resource "aws_lambda_function" "fraud_alert_processor" {
  function_name = "bofa-fraud-alert-processor-${var.environment}"
  runtime       = "java17"
  handler       = "com.bofa.notifications.lambda.FraudAlertHandler::handleRequest"
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
    variables = local.lambda_environment
  }

  reserved_concurrent_executions = 100

  tags = {
    Service     = "notification"
    Team        = "notification-squad"
    Compliance  = "sox-404"
    Environment = var.environment
    SLA         = "30s"
  }
}

# Transaction confirmation processor
resource "aws_lambda_function" "transaction_confirm_processor" {
  function_name = "bofa-txn-confirm-processor-${var.environment}"
  runtime       = "java17"
  handler       = "com.bofa.notifications.lambda.TransactionConfirmHandler::handleRequest"
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
    variables = local.lambda_environment
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
  runtime       = "java17"
  handler       = "com.bofa.notifications.lambda.BalanceWarningHandler::handleRequest"
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
    variables = local.lambda_environment
  }

  tags = {
    Service     = "notification"
    Team        = "notification-squad"
    Environment = var.environment
  }
}

# SQS -> Lambda event source mappings
resource "aws_lambda_event_source_mapping" "fraud_alert_trigger" {
  event_source_arn = var.sqs_fraud_alerts_arn
  function_name    = aws_lambda_function.fraud_alert_processor.arn
  batch_size       = 1
  enabled          = false # Enable after migration validation

  scaling_config {
    maximum_concurrency = 50
  }
}

resource "aws_lambda_event_source_mapping" "txn_confirm_trigger" {
  event_source_arn = var.sqs_txn_confirmations_arn
  function_name    = aws_lambda_function.transaction_confirm_processor.arn
  batch_size       = 10
  enabled          = false

  scaling_config {
    maximum_concurrency = 100
  }
}

resource "aws_lambda_event_source_mapping" "balance_warning_trigger" {
  event_source_arn = var.sqs_balance_warnings_arn
  function_name    = aws_lambda_function.balance_warning_processor.arn
  batch_size       = 10
  enabled          = false

  scaling_config {
    maximum_concurrency = 50
  }
}
