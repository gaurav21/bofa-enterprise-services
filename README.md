# BofA Enterprise Services

Enterprise backend services monorepo for Bank of America's Digital Banking Platform.

## Overview

This repository contains **12+ microservices** powering BofA's consumer and commercial banking operations, written primarily in **Java** (Spring Boot), with supporting services in **TypeScript** and **Python**.

## Architecture

| Component | Technology | Status |
|-----------|-----------|--------|
| Application Framework | Spring Boot 2.7.x | Production |
| Message Broker | IBM MQ 9.3 | Production (migration planned → SQS FIFO) |
| Primary Database | Oracle 19c RAC | Production (migration planned → RDS PostgreSQL) |
| Authentication | LDAP (Active Directory) | Production (migration planned → AWS Cognito) |
| Monitoring | Datadog APM + Logs | Production |
| CI/CD | GitHub Actions + Jenkins | Production |

## Key Services

### Notification Service (`src/services/notification/`)
Mission-critical notification engine handling:
- **Fraud alerts** — Real-time fraud detection notifications (< 30s SLA)
- **Transaction confirmations** — Post-transaction receipt delivery
- **Balance warnings** — Low balance and overdraft alerts
- **Regulatory disclosures** — OCC/CFPB-mandated communications

Processes **several million events per day** with a **99.99% uptime SLA**.

### Transaction Processing (`src/services/transaction-processing/`)
Core transaction validation and compliance engine:
- Multi-layer input validation
- Real-time compliance checks (BSA/AML, OFAC, KYC)
- PII detection and masking
- Token-based authentication

### Audit Service (`src/services/audit-service/`)
Python-based audit logging and compliance reporting:
- Immutable audit event capture
- Regulatory compliance report generation
- Data retention policy enforcement (7-year minimum for financial records)

## Current State

### Test Coverage — ⚠️ Critical Gap
| Service | Coverage | Risk Level |
|---------|----------|------------|
| Overall | **28.3%** | 🔴 Critical |
| transaction-processing | 15.2% | 🔴 Critical |
| auth-service | 23.1% | 🔴 High |
| audit-service | 0.0% | 🔴 Critical |
| notification-service | 31.5% | 🟡 Medium |

Compliance-critical code paths (BSA/AML validation, PII handling, fraud alerting) are **significantly under-tested**. This is a top priority ahead of the upcoming **OCC regulatory examination**.

### Cloud Migration — In Progress
AWS infrastructure provisioning has begun (see `infrastructure/terraform/aws/`). Application code migration has **not yet started**. See [CLOUD-MIGRATION-PLAN.md](./CLOUD-MIGRATION-PLAN.md) for the full strategy.

## Development

```bash
# Build all Java services
mvn clean install -pl src/services/notification,src/services/transaction-processing

# Run notification service locally
cd src/services/notification && mvn spring-boot:run

# Run audit service
cd src/services/audit-service && pip install -r requirements.txt && python src/audit_logger.py
```

## Compliance & Security

- All services must comply with **SOX Section 404**, **GLBA**, and **OCC Heightened Standards**
- PII must be masked in all non-production environments
- Audit logs are immutable and retained for **7 years minimum**
- All inter-service communication encrypted via mTLS
- LDAP groups enforce RBAC with quarterly access reviews

## Team

- **Platform Engineering** — Infrastructure, CI/CD, cloud migration
- **Notification Squad** — Fraud alerts, transaction confirmations
- **Compliance Engineering** — BSA/AML, OFAC screening, audit
- **Security Engineering** — AuthN/AuthZ, PII handling, encryption
