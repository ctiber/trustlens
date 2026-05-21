package fr.irisa.trustlens.msuser.security;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

// ── V1 — Implicit Trust ───────────────────────────────────────────────────────
// All requests are permitted; security is delegated to the API Gateway.
@Configuration
@EnableWebSecurity
@Profile("v1")
class SecurityConfigV1 {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .csrf(csrf -> csrf.disable())
            .sessionManagement(sm ->
                sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
        return http.build();
    }
}

// ── V2 — Standard Zero Trust (JWT validation) ─────────────────────────────────
// Each request must carry a valid JWT; signature is verified locally (stateless).
@Configuration
@EnableWebSecurity
@Profile({"v2", "v2-mtls", "v2-vault"})
@RequiredArgsConstructor
class SecurityConfigV2 {

    private final JwtAuthenticationFilter jwtFilter;

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .csrf(csrf -> csrf.disable())
            .sessionManagement(sm ->
                sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/actuator/**", "/auth/**").permitAll()
                .anyRequest().authenticated())
            .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}

// ── V3 — Enhanced Zero Trust (JWT + RiskEvaluator) ────────────────────────────
// Adds stateful risk evaluation on top of V2 JWT validation.
// mTLS is enforced at the server.ssl level (see application.yml).
// Vault-based key rotation is handled by JwtUtil (see JwtUtil.java).
@Configuration
@EnableWebSecurity
@Profile("v3")
@RequiredArgsConstructor
class SecurityConfigV3 {

    private final JwtAuthenticationFilter jwtFilter;
    private final RiskEvaluatorFilter     riskFilter;

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .csrf(csrf -> csrf.disable())
            .sessionManagement(sm ->
                sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/actuator/**", "/auth/**").permitAll()
                .anyRequest().authenticated())
            .addFilterBefore(jwtFilter,  UsernamePasswordAuthenticationFilter.class)
            .addFilterAfter(riskFilter,  JwtAuthenticationFilter.class);
        return http.build();
    }
}
