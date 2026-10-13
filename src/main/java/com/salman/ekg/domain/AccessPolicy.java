package com.salman.ekg.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Grants one role read access to one document access level. Absence of a row means no access. */
@Entity
@Table(name = "access_policy")
public class AccessPolicy {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "user_role", nullable = false, length = 16)
    private Role userRole;

    @Enumerated(EnumType.STRING)
    @Column(name = "document_access_level", nullable = false, length = 16)
    private AccessLevel documentAccessLevel;

    protected AccessPolicy() {
    }

    public AccessPolicy(Role userRole, AccessLevel documentAccessLevel) {
        this.userRole = userRole;
        this.documentAccessLevel = documentAccessLevel;
    }

    public Long getId() { return id; }
    public Role getUserRole() { return userRole; }
    public AccessLevel getDocumentAccessLevel() { return documentAccessLevel; }
}
