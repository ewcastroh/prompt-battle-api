package io.github.ewcastroh.promptbattle.web;

import io.github.ewcastroh.promptbattle.admin.AdminAuthInterceptor;
import io.github.ewcastroh.promptbattle.city.AdminTokens;
import io.github.ewcastroh.promptbattle.config.AppProperties;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.util.regex.Pattern;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.filter.ShallowEtagHeaderFilter;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration(proxyBeanMethods = false)
public class WebConfig implements WebMvcConfigurer {

    static final Pattern ETAG_PATHS = Pattern.compile("/api/me|/api/admin/state|/api/cities/[^/]+/stage/state");

    private final AdminTokens adminTokens;

    public WebConfig(AdminTokens adminTokens) {
        this.adminTokens = adminTokens;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new AdminAuthInterceptor(adminTokens)).addPathPatterns("/api/admin/**");
    }

    @Bean
    FilterRegistrationBean<CorsFilter> corsFilter(AppProperties props) {
        FilterRegistrationBean<CorsFilter> bean = new FilterRegistrationBean<>(
                new CorsFilter(props.corsAllowedOrigins()));
        bean.setOrder(Ordered.HIGHEST_PRECEDENCE);
        bean.addUrlPatterns("/*");
        return bean;
    }

    /** Weak ETag over the body + 304 on If-None-Match (API.md §3). */
    @Bean
    FilterRegistrationBean<ShallowEtagHeaderFilter> etagFilter() {
        ShallowEtagHeaderFilter filter = new ShallowEtagHeaderFilter() {
            @Override
            protected boolean shouldNotFilter(HttpServletRequest request) {
                return !ETAG_PATHS.matcher(request.getRequestURI()).matches();
            }
        };
        filter.setWriteWeakETag(true);
        FilterRegistrationBean<ShallowEtagHeaderFilter> bean = new FilterRegistrationBean<>(filter);
        bean.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        bean.addUrlPatterns("/api/*");
        return bean;
    }

    @Bean
    RateLimiter rateLimiter(Clock clock) {
        return new RateLimiter(clock);
    }
}
