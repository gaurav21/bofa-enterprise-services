# BofA Notification Service - AWS Lambda Migration
# Status: STUB - Infrastructure provisioning started, application migration NOT started
# Migration target: Spring Boot controllers → Lambda handlers

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

# Fraud alert processor - HIGHEST PRIORITY
# SLA: Must process within 30 seconds
resource "aws_lambda_function" "fraud_alert_processor" {
  function_name = "bofa-fraud-alert-processor-${var.environment}"
  runtime       = "java11"
  handler       = "com.bofa.notifications.lambda.FraudAlertHandler::handleRequest"
  role          = aws_iam_role.notification_lambda_role.arn

  memory_size = 1024
  timeout     = 30  # Match 30-second SLA

  # TODO: Upload deployment package
  filename = "placeholder.zip"

  vpc_config {
    subnet_ids         = var.subnet_ids
    security_group_ids = [] # TODO: Add security groups
  }

  environment {
    variables = {
      ENVIRONMENT = var.environment
      # TODO: Add SQS queue URLs, RDS endpoints, Cognito config
    }
  }

  reserved_concurrent_executions = 100

  tags = {
    Service     = "notification"
    Team        = "notification-squad"
    Compliance  = "sox-404"
    Environment = var.environment
  }
}

# Transaction confirmation processor
resource "aws_lambda_function" "transaction_confirm_processor" {
  function_name = "bofa-txn-confirm-processor-${var.environment}"
  runtime       = "java11"
  handler       = "com.bofa.notifications.lambda.TransactionConfirmHandler::handleRequest"
  role          = aws_iam_role.notification_lambda_role.arn

  memory_size = 512
  timeout     = 60

  filename = "placeholder.zip"

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
  handler       = "com.bofa.notifications.lambda.BalanceWarningHandler::handleRequest"
  role          = aws_iam_role.notification_lambda_role.arn

  memory_size = 256
  timeout     = 30

  filename = "placeholder.zip"

  tags = {
    Service     = "notification"
    Team        = "notification-squad"
    Environment = var.environment
  }
}
