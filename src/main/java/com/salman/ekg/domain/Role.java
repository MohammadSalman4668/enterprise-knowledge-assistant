package com.salman.ekg.domain;

/**
 * Application roles. Which access levels each role can read is data in the {@code access_policy} table.
 */
public enum Role {
    EMPLOYEE,
    HR,
    ADMIN;

    /** Roles that may upload documents or submit revisions (HR and Admin; always subject to admin approval). */
    public boolean canContribute() {
        return this != EMPLOYEE;
    }
}
