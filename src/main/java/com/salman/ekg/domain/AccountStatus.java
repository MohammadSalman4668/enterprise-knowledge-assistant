package com.salman.ekg.domain;

/** Sign-up lifecycle: PENDING until the responsible approver decides. */
public enum AccountStatus {
    PENDING,
    APPROVED,
    REJECTED
}
