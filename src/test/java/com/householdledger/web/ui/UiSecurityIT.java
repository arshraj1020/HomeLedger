package com.householdledger.web.ui;

import com.householdledger.identity.api.MemberProvisioningService;
import com.householdledger.identity.domain.Household;
import com.householdledger.identity.domain.Role;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The two front doors, and the guarantee that Phase 7 did not merge them
 * (PRD §FR-1, §FR-7).
 *
 * <p>The tests that matter most here are the ones asserting that the API is
 * unchanged. Adding a session-based login is exactly the kind of change that
 * quietly turns an API's 401 into a redirect to an HTML form, or drops CSRF
 * protection everywhere because the API never needed it.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
class UiSecurityIT {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16")
            .withDatabaseName("household_ledger_test")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private MemberProvisioningService provisioningService;

    private static final String PASSWORD = "correct-horse-battery-staple";

    private String email;

    @BeforeEach
    void provision() {
        Household household = provisioningService.createHousehold("Security Household");
        email = "papa+" + UUID.randomUUID() + "@example.com";
        provisioningService.registerMember(household.id(), "Papa", email, PASSWORD, Role.ADMIN);
    }

    // ------------------------------------------------- the browser chain

    @Test
    void anAnonymousBrowserRequestIsSentToTheLoginPage() throws Exception {
        mockMvc.perform(get("/"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("**/login"));

        mockMvc.perform(get("/transactions"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("**/login"));
    }

    @Test
    void theLoginPageIsPublicAndCarriesACsrfToken() throws Exception {
        mockMvc.perform(get("/login"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("name=\"email\"")))
                .andExpect(content().string(containsString("name=\"password\"")))
                // Thymeleaf's th:action inserts this; the template never has to
                // remember to, and this is what proves it happened.
                .andExpect(content().string(containsString("_csrf")));
    }

    @Test
    void correctCredentialsStartASessionAndLandOnTheDashboard() throws Exception {
        MvcResult result = mockMvc.perform(post("/login")
                        .param("email", email)
                        .param("password", PASSWORD)
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/"))
                .andReturn();

        MockHttpSession session = (MockHttpSession) result.getRequest().getSession(false);
        assertThat(session).isNotNull();

        mockMvc.perform(get("/").session(session))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Dashboard")));
    }

    /**
     * An unknown address and a wrong password fail identically, so the login
     * form cannot be used to discover which addresses are registered.
     */
    @Test
    void wrongCredentialsFailTheSameWayAsAnUnknownAddress() throws Exception {
        mockMvc.perform(post("/login")
                        .param("email", email)
                        .param("password", "not-the-password")
                        .with(csrf()))
                .andExpect(redirectedUrl("/login?error"));

        mockMvc.perform(post("/login")
                        .param("email", "nobody+" + UUID.randomUUID() + "@example.com")
                        .param("password", PASSWORD)
                        .with(csrf()))
                .andExpect(redirectedUrl("/login?error"));

        mockMvc.perform(get("/login").param("error", ""))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("didn't recognise")))
                .andExpect(content().string(org.hamcrest.Matchers.not(containsString(email))));
    }

    @Test
    void loginWithoutACsrfTokenIsRefused() throws Exception {
        mockMvc.perform(post("/login")
                        .param("email", email)
                        .param("password", PASSWORD))
                .andExpect(status().isForbidden());
    }

    @Test
    void signingOutRequiresAPostWithACsrfTokenAndClearsTheSession() throws Exception {
        MockHttpSession session = signIn();

        mockMvc.perform(post("/logout").session(session))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/logout").session(session).with(csrf()))
                .andExpect(redirectedUrl("/login?loggedOut"));

        mockMvc.perform(get("/").session(session))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("**/login"));
    }

    /**
     * PRD §FR-1's tokens are an API concern. The browser session is a plain
     * server-side session, so there is no access or refresh token in a cookie
     * or rendered into the page for a script to read.
     */
    @Test
    void noTokenIsHandedToTheBrowser() throws Exception {
        MvcResult login = mockMvc.perform(post("/login")
                        .param("email", email)
                        .param("password", PASSWORD)
                        .with(csrf()))
                .andReturn();

        for (String cookie : login.getResponse().getHeaders("Set-Cookie")) {
            assertThat(cookie).doesNotContain("eyJ");
            assertThat(cookie.toLowerCase()).doesNotContain("token");
        }

        MockHttpSession session = (MockHttpSession) login.getRequest().getSession(false);
        String dashboard = mockMvc.perform(get("/").session(session))
                .andReturn().getResponse().getContentAsString();

        // "eyJ" is the base64 prefix every JWT header starts with.
        assertThat(dashboard).doesNotContain("eyJ");
        assertThat(dashboard).doesNotContain("Bearer");
    }

    /** No inline scripts anywhere, so the UI can afford a policy that makes injected markup inert. */
    @Test
    void browserPagesCarrySecurityHeaders() throws Exception {
        mockMvc.perform(get("/login"))
                .andExpect(header().string("Content-Security-Policy", containsString("script-src 'none'")))
                .andExpect(header().string("Content-Security-Policy", containsString("frame-ancestors 'none'")))
                .andExpect(header().string("X-Frame-Options", "DENY"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"));
    }

    @Test
    void theStylesheetIsPublicSoTheLoginPageIsReadable() throws Exception {
        mockMvc.perform(get("/css/app.css"))
                .andExpect(status().isOk());
    }

    // ----------------------------------------------------- the API chain

    /**
     * The API answers an anonymous request with 401 and no redirect, exactly
     * as it did before Phase 7. A browser cannot do anything useful with a
     * bare 401, and an API client cannot do anything useful with an HTML login
     * page — which is why these are two chains.
     */
    @Test
    void theApiStillAnswers401AndNeverRedirectsToAForm() throws Exception {
        mockMvc.perform(get("/api/accounts"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/api/transactions"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/api/reports/trial-balance"))
                .andExpect(status().isUnauthorized());
    }

    /**
     * The API is stateless and token-authenticated, so it has no cookie to
     * ride and CSRF protection is deliberately off. Enabling it globally for
     * the UI's sake would have broken every API client.
     */
    @Test
    void theApiStillAcceptsAPostWithoutACsrfToken() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                        .contentType("application/json")
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void documentationAndHealthRemainReachableWithoutAnyCredential() throws Exception {
        mockMvc.perform(get("/actuator/health")).andExpect(status().isOk());
        mockMvc.perform(get("/v3/api-docs")).andExpect(status().isOk());
    }

    /** A browser session must not be usable as an API credential, and it is not. */
    @Test
    void aBrowserSessionDoesNotAuthenticateTheApi() throws Exception {
        MockHttpSession session = signIn();

        mockMvc.perform(get("/api/accounts").session(session))
                .andExpect(status().isUnauthorized());
    }

    private MockHttpSession signIn() throws Exception {
        MvcResult result = mockMvc.perform(post("/login")
                        .param("email", email)
                        .param("password", PASSWORD)
                        .with(csrf()))
                .andExpect(redirectedUrl("/"))
                .andReturn();

        return (MockHttpSession) result.getRequest().getSession(false);
    }
}
