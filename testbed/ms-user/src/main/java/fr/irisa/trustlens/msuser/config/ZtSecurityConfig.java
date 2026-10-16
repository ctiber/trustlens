package fr.irisa.trustlens.msuser.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Zero Trust enforcement is performed by the zt-common servlet filters
 * (Ac4aFilter = p2, RiskAnalysisFilter = p5), driven by the ZT_* flags.
 * Spring Security is therefore kept neutral and identical in every variant.
 * The legacy profile-based configurations (v1/v2/v2-mtls/v2-vault/v3) in the
 * security package are superseded and inactive unless such a profile is set.
 */
@Configuration
@EnableWebSecurity
@Profile("!v1 & !v2 & !v2-mtls & !v2-vault & !v3")
public class ZtSecurityConfig {

    @Bean
    public SecurityFilterChain ztFilterChain(HttpSecurity http) throws Exception {
        http
            .csrf(csrf -> csrf.disable())
            .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
        return http.build();
    }
}
