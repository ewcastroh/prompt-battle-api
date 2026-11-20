package io.github.ewcastroh.promptbattle.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;

import io.github.ewcastroh.promptbattle.ApiClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;

/** SPEC §7.1, §14.1-6 and API.md §3. */
@SpringBootTest
@AutoConfigureMockMvc
class CorsTest {

    static final String ALLOWED = "https://ui.test";
    static final String OTHER = "https://evil.test";

    @Autowired
    MockMvc mvc;
    ApiClient api;

    @BeforeEach
    void setUp() {
        api = new ApiClient(mvc);
        api.resetEvent(); // test classes share one cached context
    }

    @Test
    void allowedOriginGetsTheOriginAndExposedHeaders() {
        MockHttpServletResponse r = api.perform(get("/api/cities/mde/stage/state").header("Origin", ALLOWED));
        assertThat(r.getStatus()).isEqualTo(200);
        assertThat(r.getHeader("Access-Control-Allow-Origin")).isEqualTo(ALLOWED);
        assertThat(r.getHeader("Access-Control-Expose-Headers")).isEqualTo("ETag, Date, Retry-After, Content-Disposition");
        assertThat(r.getHeader("Access-Control-Allow-Credentials")).isNull();
        assertThat(r.getHeader("ETag")).isNotBlank();
        assertThat(r.getHeaders("Vary")).contains("Origin");
    }

    @Test
    void preflightFromAnAllowedOrigin() {
        MockHttpServletResponse r = api.perform(options("/api/rounds/current/attempts").header("Origin", ALLOWED)
                .header("Access-Control-Request-Method", "POST")
                .header("Access-Control-Request-Headers", "authorization, content-type"));
        assertThat(r.getStatus()).isEqualTo(200);
        assertThat(r.getHeader("Access-Control-Allow-Origin")).isEqualTo(ALLOWED);
        assertThat(r.getHeader("Access-Control-Allow-Methods")).isEqualTo("GET, POST, PUT, DELETE");
        assertThat(r.getHeader("Access-Control-Allow-Headers"))
                .isEqualTo("Authorization, X-Admin-Token, Content-Type, If-None-Match");
        assertThat(r.getHeader("Access-Control-Max-Age")).isEqualTo("600");

        MockHttpServletResponse admin = api.perform(options("/api/admin/state").header("Origin", "http://localhost:4200")
                .header("Access-Control-Request-Method", "GET")
                .header("Access-Control-Request-Headers", "x-admin-token, if-none-match"));
        assertThat(admin.getStatus()).isEqualTo(200);
        assertThat(admin.getHeader("Access-Control-Allow-Origin")).isEqualTo("http://localhost:4200");
    }

    @Test
    void otherOriginsAreRefused() {
        MockHttpServletResponse r = api.perform(get("/api/cities").header("Origin", OTHER));
        assertThat(r.getHeader("Access-Control-Allow-Origin")).isNull();
        assertThat(r.getHeader("Access-Control-Expose-Headers")).isNull();

        MockHttpServletResponse preflight = api.perform(options("/api/me").header("Origin", OTHER)
                .header("Access-Control-Request-Method", "GET").header("Access-Control-Request-Headers", "authorization"));
        assertThat(preflight.getStatus()).isEqualTo(403);
        assertThat(preflight.getHeader("Access-Control-Allow-Origin")).isNull();
    }

    @Test
    void preflightWithUnlistedHeaderOrMethodIsRefused() {
        assertThat(api.perform(options("/api/me").header("Origin", ALLOWED)
                .header("Access-Control-Request-Method", "GET").header("Access-Control-Request-Headers", "x-custom"))
                .getStatus()).isEqualTo(403);
        assertThat(api.perform(options("/api/me").header("Origin", ALLOWED)
                .header("Access-Control-Request-Method", "PATCH")).getStatus()).isEqualTo(403);
    }
}
