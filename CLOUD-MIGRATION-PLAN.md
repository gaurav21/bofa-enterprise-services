# Cloud Migration Plan — BofA Enterprise Services

**Status:** Planning & Infrastructure Provisioning  
**Target Completion:** Q4 2026  
**Executive Sponsor:** VP Engineering, Digital Banking Platform  
**Regulatory Oversight:** OCC informed, no objection received

---

## 1. Overview

Migration of BofA's notification and transaction processing services from on-premises legacy infrastructure to AWS. This is a phased migration preserving the 99.99% uptime SLA and all regulatory compliance requirements.

### Current State → Target State

| Component | Current | Target | Status |
|-----------|---------|--------|--------|
| Compute | Spring Boot 2.7 on EC2/on-prem | AWS Lambda (Java 11) | ⬜ Not Started |
| Message Broker | IBM MQ 9.3 | Amazon SQS FIFO | 🟡 Infra Provisioned |
| Primary Database | Oracle 19c RAC | Amazon RDS PostgreSQL 15 | 🟡 Infra Provisioned |
| Authentication | LDAP (Active Directory) | AWS Cognito + IAM | ⬜ Not Started |
| Secrets Management | HashiCorp Vault | AWS Secrets Manager | ⬜ Not Started |
| Monitoring | Datadog APM | Datadog APM (retained) | ✅ No Change |
| CI/CD | Jenkins + GitHub Actions | GitHub Actions + CodeDeploy | ⬜ Not Started |

---

## 2. IBM MQ → Amazon SQS FIFO

### Challenge: Message Ordering Guarantee

IBM MQ provides strict FIFO ordering within a queue. SQS FIFO provides ordering within a **Message Group ID**. Our current implementation uses application-level sequence numbers (`MessageOrderGuarantee.java`) — this maps naturally to SQS FIFO message groups keyed by `accountId`.

### Migration Strategy

1. **Phase 1: Dual-write** — IBM MQ consumers continue; new SQS consumers shadow-process in parallel
2. **Phase 2: Validation** — Compare processing results between MQ and SQS paths for 30 days
3. **Phase 3: Cutover** — Switch primary to SQS, IBM MQ becomes fallback
4. **Phase 4: Decommission** — Remove IBM MQ after 90-day stability period

### Key Decisions

- **Message Group ID** = `accountId` (preserves per-account ordering)
- **Deduplication** = Content-based for idempotent operations, explicit ID for fraud alerts
- **DLQ** = Separate FIFO dead-letter queue with 3 retry max
- **Throughput** = High-throughput mode with per-message-group ordering
- **Encryption** = KMS-managed keys (SSE-KMS), same key rotation policy as current

### Risks

- SQS FIFO has 3,000 msgs/sec throughput limit per queue (vs unlimited on IBM MQ)
  - **Mitigation:** Shard across multiple queues by notification type
- SQS message size limit: 256 KB (vs 100 MB on IBM MQ)
  - **Mitigation:** Offload large payloads to S3, pass reference in SQS

---

## 3. Oracle 19c RAC → Amazon RDS PostgreSQL

### Challenge: SQL Compatibility

Oracle-specific features in use:
- `FETCH FIRST N ROWS ONLY` → PostgreSQL `LIMIT N` ✅ (compatible in PG 12+)
- Oracle RAC failover → RDS Multi-AZ ✅
- Partitioned tables (by month) → PostgreSQL native partitioning ✅
- Oracle sequences → PostgreSQL sequences ✅
- PL/SQL stored procedures → Must be rewritten in PL/pgSQL ⚠️
- Oracle-specific date functions → Requires mapping ⚠️

### Migration Strategy

1. **Schema migration** using AWS Schema Conversion Tool (SCT)
2. **Data migration** using AWS Database Migration Service (DMS) with CDC
3. **Validation** using row-count and checksum comparison
4. **Cutover** with < 30 minutes downtime using DMS CDC switchover

### Data Residency & Encryption

- All data must remain in **us-east-1** (Virginia) per BofA data residency policy
- **Encryption at rest:** AES-256 via KMS customer-managed key
- **Encryption in transit:** TLS 1.2+ enforced (no SSL fallback)
- **Backup encryption:** Same KMS key, cross-region backup to us-west-2 for DR
- **Key rotation:** Automatic annual rotation via KMS

---

## 4. LDAP → AWS Cognito

### Challenge: Service-to-Service Authentication

Current: Services authenticate via LDAP bind using service accounts in AD.
Target: JWT-based authentication using Cognito machine-to-machine (client credentials flow).

### Migration Strategy

1. Create Cognito User Pool with custom attributes matching AD groups
2. Create Resource Servers for each service API
3. Implement OAuth2 client credentials flow for service-to-service auth
4. Run dual-auth (LDAP + Cognito) during transition period
5. Decommission LDAP binds after 60-day validation

### RBAC Mapping

| AD Group | Cognito Scope | Access |
|----------|--------------|--------|
| CN=NotificationAdmins | notification-service/admin | Full admin |
| CN=NotificationService | notification-service/write | Send notifications |
| CN=AuditReaders | audit-service/read | Read audit logs |
| CN=ComplianceOfficers | audit-service/admin | Generate compliance reports |

---

## 5. Spring Boot → AWS Lambda

### Challenge: Cold Start Latency

Fraud alerts have a 30-second SLA. Lambda cold starts for Java can be 5-15 seconds.

### Mitigation

- **Provisioned concurrency** = 100 for fraud alert processor
- **SnapStart** enabled for all Java Lambda functions
- **Memory allocation** = 1024 MB minimum (faster CPU allocation)
- **Connection pooling** via RDS Proxy (eliminates per-invocation DB connection overhead)

### Handler Design

Each notification type becomes a separate Lambda function:
- `FraudAlertHandler` — triggered by SQS FIFO fraud queue
- `TransactionConfirmHandler` — triggered by SQS FIFO confirmation queue
- `BalanceWarningHandler` — triggered by SQS FIFO balance queue

Shared business logic extracted to Lambda Layers.

---

## 6. 99.99% SLA Preservation Strategy

### Current Uptime Architecture
- Oracle RAC: Active-active across 2 data centers
- IBM MQ: Clustered queue managers with automatic failover
- Spring Boot: Multiple instances behind load balancer

### AWS Target Architecture
- RDS Multi-AZ: Synchronous standby with automatic failover (< 60s)
- SQS: Fully managed, 99.99% SLA built-in
- Lambda: Multi-AZ by default, provisioned concurrency for consistent performance
- Route 53: Health-check based failover for API endpoints

### Failure Scenarios & Recovery

| Scenario | RTO | RPO | Strategy |
|----------|-----|-----|----------|
| Single AZ failure | < 60s | 0 | Multi-AZ automatic failover |
| Region failure | < 4h | < 5min | Cross-region RDS replica promotion |
| Lambda throttling | Immediate | 0 | Provisioned concurrency + SQS buffering |
| Database failover | < 120s | 0 | RDS Multi-AZ synchronous replication |

---

## 7. Timeline

| Phase | Duration | Milestone |
|-------|----------|-----------|
| Phase 0: Infrastructure | 4 weeks | ✅ Terraform stubs, VPC, IAM |
| Phase 1: Schema Migration | 6 weeks | Oracle → PostgreSQL schema + data |
| Phase 2: Auth Migration | 4 weeks | LDAP → Cognito dual-auth |
| Phase 3: MQ Migration | 8 weeks | IBM MQ → SQS dual-write + validation |
| Phase 4: App Migration | 6 weeks | Spring Boot → Lambda handlers |
| Phase 5: Validation | 4 weeks | End-to-end testing, load testing |
| Phase 6: Cutover | 2 weeks | Production switchover + monitoring |
| Phase 7: Decommission | 4 weeks | Remove legacy infrastructure |

**Total estimated duration:** 38 weeks (~9.5 months)

---

## 8. Pre-Migration Requirements

### Test Coverage (BLOCKING)
Current test coverage (28.3%) is **insufficient** for safe migration. Before any code migration begins:
- [ ] Transaction processing coverage must reach **80%+**
- [ ] Compliance checker must have **100% path coverage**
- [ ] PII detection/masking must have **comprehensive edge case tests**
- [ ] Notification service must reach **70%+ coverage**
- [ ] Audit service must have **basic test coverage** (currently 0%)

### Regulatory Approvals
- [ ] OCC notification of technology change
- [ ] Data residency attestation for AWS us-east-1
- [ ] Third-party risk assessment for AWS services
- [ ] Business continuity plan updated for cloud architecture
- [ ] Penetration test of AWS environment

---

*Document Owner: Platform Engineering*  
*Last Updated: 2026-06-23*  
*Review Cadence: Bi-weekly with steering committee*
