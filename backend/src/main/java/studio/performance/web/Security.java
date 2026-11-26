package studio.performance.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.header.HeaderWriterFilter;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

@Configuration
public class Security {
    @Bean
    SecurityFilterChain studioHttp(HttpSecurity http, @Value("${studio.browser-origins}") String origins) throws Exception {
        Set<String> allowed = Arrays.stream(origins.split(",")).map(String::trim).collect(Collectors.toSet());
        http.csrf(config -> config.disable());
        http.headers(config -> config.cacheControl(cache -> {}));
        http.addFilterAfter(new OncePerRequestFilter() {
            @Override
            protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                    throws IOException, ServletException {
                String origin = request.getHeader("Origin");
                String site = request.getHeader("Sec-Fetch-Site");
                boolean browserWrite = origin != null && !allowed.contains(origin);
                boolean remoteSite = "cross-site".equals(site) && (origin == null || !allowed.contains(origin));
                String host = request.getServerName();
                boolean localHost = Set.of("localhost", "127.0.0.1", "::1", "[::1]").contains(host)
                        || allowed.stream().anyMatch(value -> host.equals(java.net.URI.create(value).getHost()));
                if (browserWrite || remoteSite || !localHost) {
                    response.sendError(403);
                    return;
                }
                chain.doFilter(request, response);
            }
        }, HeaderWriterFilter.class);
        return http.authorizeHttpRequests(auth -> auth.anyRequest().permitAll()).build();
    }
}
