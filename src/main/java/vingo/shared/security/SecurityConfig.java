package vingo.shared.security;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Phân quyền: endpoint public liệt kê ở đây (khớp {@code security: []} trong contract),
 * {@code /api/v1/admin/**} chỉ ADMIN, còn lại cần đăng nhập.
 */
@Configuration(proxyBeanMethods = false)
@EnableMethodSecurity
public class SecurityConfig {

    private static final String API = "/api/v1";

    // Profile worker chạy không có web (web-application-type: none) nên không có HttpSecurity.
    @Bean
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    SecurityFilterChain apiSecurity(HttpSecurity http, ProblemAuthenticationHandlers handlers) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .logout(logout -> logout.disable())
                .requestCache(cache -> cache.disable())
                .cors(Customizer.withDefaults())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.POST,
                                API + "/auth/register", API + "/auth/otp/verify", API + "/auth/otp/resend",
                                API + "/auth/login", API + "/auth/google",
                                API + "/auth/password/forgot", API + "/auth/password/reset").permitAll()
                        .requestMatchers(HttpMethod.GET,
                                API + "/auth/google", API + "/auth/oauth2/callback/google",
                                API + "/events", API + "/events/*", API + "/events/*/showtimes",
                                API + "/showtimes/*/seatmap", API + "/showtimes/*/seatmap.svg", API + "/showtimes/*/availability",
                                API + "/payments/vnpay/ipn", API + "/payments/vnpay/return").permitAll()
                        .requestMatchers("/actuator/health/**", "/actuator/info").permitAll()
                        .requestMatchers("/swagger-ui.html", "/swagger-ui/**", "/v3/api-docs/**").permitAll()
                        .requestMatchers("/error").permitAll()
                        .requestMatchers(API + "/admin/**").hasRole(Role.ADMIN.name())
                        // soát vé: chỉ ADMIN; khi có vai trò nhân viên soát vé thì thêm vào đây
                        .requestMatchers(HttpMethod.POST, API + "/checkins").hasRole(Role.ADMIN.name())
                        .requestMatchers(API + "/**").hasAnyRole(Role.BUYER.name(), Role.ADMIN.name())
                        .anyRequest().denyAll())
                .exceptionHandling(e -> e.authenticationEntryPoint(handlers).accessDeniedHandler(handlers));
        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8();
    }
}
