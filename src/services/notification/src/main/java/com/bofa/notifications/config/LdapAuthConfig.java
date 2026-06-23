package com.bofa.notifications.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.ldap.core.LdapTemplate;
import org.springframework.ldap.core.support.LdapContextSource;
import org.springframework.security.config.annotation.authentication.builders.AuthenticationManagerBuilder;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configuration.WebSecurityConfigurerAdapter;

/**
 * LDAP authentication against BofA Active Directory.
 * Service accounts authenticate via LDAP bind for inter-service calls.
 * User access controlled by AD group membership (CN=NotificationAdmins).
 */
@Configuration
@EnableWebSecurity
public class LdapAuthConfig extends WebSecurityConfigurerAdapter {

    @Value("${ldap.url}")
    private String ldapUrl;

    @Value("${ldap.base-dn}")
    private String baseDn;

    @Value("${ldap.manager-dn}")
    private String managerDn;

    @Value("${ldap.manager-password}")
    private String managerPassword;

    @Override
    protected void configure(AuthenticationManagerBuilder auth) throws Exception {
        auth.ldapAuthentication()
            .userDnPatterns("uid={0},ou=ServiceAccounts")
            .groupSearchBase("ou=Groups")
            .contextSource()
                .url(ldapUrl + "/" + baseDn)
                .managerDn(managerDn)
                .managerPassword(managerPassword);
    }

    @Override
    protected void configure(HttpSecurity http) throws Exception {
        http.csrf().disable()
            .authorizeRequests()
                .antMatchers("/actuator/health").permitAll()
                .antMatchers("/api/notifications/**").hasRole("NOTIFICATION_SERVICE")
                .antMatchers("/api/admin/**").hasRole("NOTIFICATION_ADMIN")
                .anyRequest().authenticated()
            .and()
            .httpBasic();
    }

    @Bean
    public LdapContextSource contextSource() {
        LdapContextSource ctx = new LdapContextSource();
        ctx.setUrl(ldapUrl);
        ctx.setBase(baseDn);
        ctx.setUserDn(managerDn);
        ctx.setPassword(managerPassword);
        return ctx;
    }

    @Bean
    public LdapTemplate ldapTemplate() {
        return new LdapTemplate(contextSource());
    }
}
