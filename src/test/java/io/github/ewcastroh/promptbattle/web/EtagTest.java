package io.github.ewcastroh.promptbattle.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import io.github.ewcastroh.promptbattle.ApiClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;

/** API.md §3: weak ETag, 304 on If-None-Match, a new ETag after a change. */
@SpringBootTest
@AutoConfigureMockMvc
class EtagTest {

    @Autowired
    MockMvc mvc;
    ApiClient api;

    @BeforeEach
    void setUp() {
        api = new ApiClient(mvc);
        api.resetEvent(); // test classes share one cached context
    }

    @Test
    void stageStateIsCacheValidated() {
        MockHttpServletResponse first = api.perform(get("/api/cities/bog/stage/state"));
        String etag = first.getHeader("ETag");
        assertThat(etag).startsWith("W/\"");
        assertThat(first.getHeader("Cache-Control")).isEqualTo("no-cache");

        MockHttpServletResponse same = api.perform(get("/api/cities/bog/stage/state").header("If-None-Match", etag));
        assertThat(same.getStatus()).isEqualTo(304);
        assertThat(same.getHeader("ETag")).isEqualTo(etag);
        assertThat(same.getContentAsByteArray()).isEmpty();

        api.join("bog", "EtagPlayer");
        MockHttpServletResponse changed = api.perform(get("/api/cities/bog/stage/state").header("If-None-Match", etag));
        assertThat(changed.getStatus()).isEqualTo(200);
        assertThat(changed.getHeader("ETag")).isNotEqualTo(etag);
    }

    @Test
    void meAndAdminStateArePrivateAndValidated() {
        String token = api.join("mde", "EtagMe").get("token").asString();
        MockHttpServletResponse me = api.perform(ApiClient.player(get("/api/me"), token));
        assertThat(me.getHeader("Cache-Control")).isEqualTo("private, no-cache");
        String etag = me.getHeader("ETag");
        assertThat(api.perform(ApiClient.player(get("/api/me"), token).header("If-None-Match", etag)).getStatus())
                .isEqualTo(304);

        MockHttpServletResponse admin = api.perform(api.admin(get("/api/admin/state"), "mde"));
        assertThat(admin.getHeader("ETag")).startsWith("W/\"");
        assertThat(api.perform(api.admin(get("/api/admin/state"), "mde").header("If-None-Match", admin.getHeader("ETag")))
                .getStatus()).isEqualTo(304);
    }

    @Test
    void otherEndpointsHaveNoEtag() {
        assertThat(api.perform(get("/api/cities")).getHeader("ETag")).isNull();
    }
}
