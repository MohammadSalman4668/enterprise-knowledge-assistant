package com.salman.ekg.domain;

public enum Role {
    EMPLOYEE,
    HR,
    ADMIN;

    /** Roles that may upload documents or submit revisions (HR and Admin; always subject to admin approval). */
    public boolean canContribute() {
        return this != EMPLOYEE;
    }
}
