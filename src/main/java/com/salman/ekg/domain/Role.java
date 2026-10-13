package com.salman.ekg.domain;

public enum Role {
    EMPLOYEE,
    ENGINEER,
    HR,
    ADMIN;

    /** Roles that may upload documents or submit revisions (always subject to admin approval). */
    public boolean canContribute() {
        return this != EMPLOYEE;
    }
}
