# BofA RDS PostgreSQL - Replacing Oracle 19c RAC
# Status: STUB - Instance definitions created, schema migration NOT started
# Critical: Must maintain ACID compliance and support Oracle-specific SQL migration

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
  description = "VPC for RDS placement"
}

variable "private_subnet_ids" {
  type        = list(string)
  description = "Private subnets for RDS Multi-AZ"
}

# Subnet group for RDS
resource "aws_db_subnet_group" "notification_db" {
  name       = "bofa-notification-db-${var.environment}"
  subnet_ids = var.private_subnet_ids

  tags = {
    Service     = "notification"
    Environment = var.environment
  }
}

# Parameter group with PostgreSQL tuning for notification workload
resource "aws_db_parameter_group" "notification_db" {
  name   = "bofa-notification-pg15-${var.environment}"
  family = "postgres15"

  parameter {
    name  = "max_connections"
    value = "500"
  }
  parameter {
    name  = "shared_buffers"
    value = "{DBInstanceClassMemory/4}"
  }
  parameter {
    name  = "log_statement"
    value = "all"  # Required for audit compliance
  }
  parameter {
    name  = "log_min_duration_statement"
    value = "1000" # Log queries > 1 second
  }
}

# Primary RDS instance - Multi-AZ for 99.99% SLA
resource "aws_db_instance" "notification_primary" {
  identifier     = "bofa-notification-${var.environment}"
  engine         = "postgres"
  engine_version = "15.4"
  instance_class = "db.r6g.2xlarge"

  allocated_storage     = 500
  max_allocated_storage = 2000
  storage_type          = "gp3"
  storage_encrypted     = true
  kms_key_id           = "alias/bofa-rds-key"

  db_name  = "notifications"
  username = "notif_admin"
  # Password managed via AWS Secrets Manager
  manage_master_user_password = true

  multi_az               = true
  db_subnet_group_name   = aws_db_subnet_group.notification_db.name
  parameter_group_name   = aws_db_parameter_group.notification_db.name
  publicly_accessible    = false
  deletion_protection    = true
  skip_final_snapshot    = false
  final_snapshot_identifier = "bofa-notification-final-${var.environment}"

  backup_retention_period = 35  # 5 weeks
  backup_window          = "03:00-04:00"
  maintenance_window     = "sun:04:00-sun:05:00"

  # Enhanced monitoring for compliance
  monitoring_interval = 30
  monitoring_role_arn = "arn:aws:iam::role/rds-monitoring-role"

  performance_insights_enabled    = true
  performance_insights_kms_key_id = "alias/bofa-rds-key"

  # Audit logging
  enabled_cloudwatch_logs_exports = ["postgresql", "upgrade"]

  tags = {
    Service        = "notification"
    DataClass      = "confidential"
    Compliance     = "sox-404,glba"
    RetentionYears = "7"
    Environment    = var.environment
  }
}

# Read replica for reporting queries (won't impact production writes)
resource "aws_db_instance" "notification_replica" {
  identifier          = "bofa-notification-replica-${var.environment}"
  replicate_source_db = aws_db_instance.notification_primary.identifier
  instance_class      = "db.r6g.xlarge"

  storage_encrypted = true
  kms_key_id       = "alias/bofa-rds-key"

  publicly_accessible = false

  tags = {
    Service     = "notification"
    Role        = "read-replica"
    Environment = var.environment
  }
}
