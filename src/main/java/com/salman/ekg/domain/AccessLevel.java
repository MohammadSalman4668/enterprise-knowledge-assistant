package com.salman.ekg.domain;

/** Sensitivity label on a document. Which roles may read which level is data, held in {@code access_policy}. */
public enum AccessLevel {
    PUBLIC,
    INTERNAL,
    CONFIDENTIAL,
    RESTRICTED
}
