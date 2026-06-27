package com.bofa.notifications.config;

/**
 * DEPRECATED: LDAP/Active Directory authentication has been replaced by AWS Cognito.
 *
 * RBAC migration mapping:
 *   AD Group                     -> Cognito Scope
 *   CN=NotificationAdmins        -> notification-service/admin
 *   CN=NotificationService       -> notification-service/write
 *   CN=AuditReaders              -> audit-service/read
 *   CN=ComplianceOfficers        -> audit-service/admin
 *
 * Auth flow migration:
 *   - LDAP bind (service accounts) -> OAuth2 client credentials flow
 *   - AD group membership          -> Cognito JWT scopes
 *   - WebSecurityConfigurerAdapter -> Cognito JWT validation (CognitoAuthConfig)
 *   - httpBasic()                  -> Bearer token (API Gateway + Cognito)
 *
 * See:
 *   - config/aws/CognitoAuthConfig.java for JWT validation
 *
 * @deprecated Replaced by Cognito auth in config/aws/CognitoAuthConfig.java
 */
@Deprecated(since = "4.0.0", forRemoval = true)
public class LdapAuthConfig {
    // Intentionally empty — Spring LDAP and WebSecurityConfigurerAdapter removed
    // This class is retained as migration documentation
}
