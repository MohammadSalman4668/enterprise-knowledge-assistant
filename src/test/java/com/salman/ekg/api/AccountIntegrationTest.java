package com.salman.ekg.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.salman.ekg.domain.AccountStatus;
import com.salman.ekg.domain.Role;
import com.salman.ekg.repository.AppUserRepository;
import com.salman.ekg.support.AbstractIntegrationTest;

/** Sign-up → pending → approval by the right role → sign-in with email and password → profile. */
class AccountIntegrationTest extends AbstractIntegrationTest {

    private static final String PASSWORD = "Synthetic-Pass-1";

    @Autowired
    private AppUserRepository users;

    private long signUp(String name, String email, String role) throws Exception {
        MvcResult r = mvc.perform(postJson("/api/auth/signup",
                        Map.of("name", name, "email", email, "password", PASSWORD, "role", role)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andReturn();
        return ((Number) json(r, "$.id")).longValue();
    }

    /** A signed-in approver, as the security context would present them (role from the database row). */
    private static RequestPostProcessor approver(String email, Role role) {
        return user(email).roles(role.name());
    }

    private static RequestPostProcessor basic(String email) {
        return httpBasic(email, PASSWORD);
    }

    @Test
    @DisplayName("The bootstrap admin from configuration exists, is approved and owns the seed documents")
    void bootstrapAdminExists() {
        var admin = users.findByEmail("admin@example.test").orElseThrow();
        assertThat(admin.getRole()).isEqualTo(Role.ADMIN);
        assertThat(admin.getStatus()).isEqualTo(AccountStatus.APPROVED);
        assertThat(admin.getDisplayName()).isEqualTo("Test Admin");
        assertThat(users.count()).as("no demo users are seeded").isEqualTo(1);
    }

    @Test
    @DisplayName("Employee sign-up: pending → cannot sign in → HR approves → signs in and sees the profile")
    void employeeApprovedByHr() throws Exception {
        long hrId = signUp("Hana Synthetic", "hana@example.test", "HR");
        mvc.perform(post("/api/users/{id}/approve", hrId).with(approver("admin@example.test", Role.ADMIN)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("APPROVED"));

        long empId = signUp("Emma Synthetic", "Emma@Example.test", "EMPLOYEE");
        mvc.perform(get("/api/knowledge/me").with(basic("emma@example.test")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("ACCOUNT_PENDING"))
                .andExpect(jsonPath("$.message", Matchers.containsString("approval by HR")));

        mvc.perform(get("/api/users/pending").with(basic("hana@example.test")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].email", Matchers.contains("emma@example.test")));
        mvc.perform(post("/api/users/{id}/approve", empId).with(basic("hana@example.test")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.decidedBy").value("hana@example.test"));

        mvc.perform(get("/api/knowledge/me").with(basic("emma@example.test")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayName").value("Emma Synthetic"))
                .andExpect(jsonPath("$.email").value("emma@example.test"))
                .andExpect(jsonPath("$.role").value("EMPLOYEE"))
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.approvedByName").value("Hana Synthetic"))
                .andExpect(jsonPath("$.canApprove").value(false));
    }

    @Test
    @DisplayName("Routing: HR cannot approve HR/Admin requests, Admin decides them, Employees approve nothing")
    void approvalRouting() throws Exception {
        long hrRequest = signUp("Second HR", "hr2@example.test", "HR");
        long adminRequest = signUp("Second Admin", "admin2@example.test", "ADMIN");

        mvc.perform(post("/api/users/{id}/approve", hrRequest).with(approver("hr@example.test", Role.HR)))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("NOT_YOUR_APPROVAL"));
        mvc.perform(get("/api/users/pending").with(approver("hr@example.test", Role.HR)))
                .andExpect(jsonPath("$", Matchers.hasSize(0)));
        mvc.perform(get("/api/users/pending").with(approver("e@example.test", Role.EMPLOYEE)))
                .andExpect(status().isForbidden());

        mvc.perform(get("/api/users/pending").with(approver("admin@example.test", Role.ADMIN)))
                .andExpect(jsonPath("$[*].email", Matchers.containsInAnyOrder("hr2@example.test", "admin2@example.test")));
        mvc.perform(post("/api/users/{id}/approve", adminRequest).with(approver("admin@example.test", Role.ADMIN)))
                .andExpect(status().isOk());
        // A newly approved admin has full admin powers, including approving other admins' requests
        mvc.perform(post("/api/users/{id}/approve", hrRequest).with(basic("admin2@example.test")))
                .andExpect(status().isOk());
        mvc.perform(post("/api/users/{id}/approve", hrRequest).with(approver("admin@example.test", Role.ADMIN)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ALREADY_DECIDED"));
    }

    @Test
    @DisplayName("Rejected accounts cannot sign in; wrong passwords never reveal an account's status")
    void rejectedAndWrongPassword() throws Exception {
        long id = signUp("Rex Synthetic", "rex@example.test", "ADMIN");
        mvc.perform(get("/api/knowledge/me").with(httpBasic("rex@example.test", "wrong-password")))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        mvc.perform(post("/api/users/{id}/reject", id).with(approver("admin@example.test", Role.ADMIN)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("REJECTED"));
        mvc.perform(get("/api/knowledge/me").with(basic("rex@example.test")))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("ACCOUNT_REJECTED"));
        mvc.perform(get("/api/knowledge/me").with(httpBasic("nobody@example.test", PASSWORD)))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    @DisplayName("Sign-up validation: duplicate email (any case), bad email, short password, missing role")
    void signUpValidation() throws Exception {
        signUp("First", "dup@example.test", "EMPLOYEE");
        mvc.perform(postJson("/api/auth/signup", Map.of("name", "Again", "email", "DUP@example.test", "password", PASSWORD, "role", "HR")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("EMAIL_ALREADY_REGISTERED"));
        mvc.perform(postJson("/api/auth/signup", Map.of("name", "", "email", "not-an-email", "password", "short")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[*].field", Matchers.hasItems("name", "email", "password", "role")));
        mvc.perform(postJson("/api/auth/signup", Map.of("name", "X", "email", "admin@example.test", "password", PASSWORD, "role", "ADMIN")))
                .andExpect(status().isConflict());
    }
}
