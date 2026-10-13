package com.salman.ekg.domain;

public enum AnswerOutcome {
    /** Every part of the question is supported by cited evidence. */
    ANSWERED,
    /** Some parts are supported; the unsupported parts are named explicitly. */
    PARTIAL,
    /** Accessible sources disagree; both are cited and the conflict is stated. */
    CONFLICT,
    /** No sufficient evidence; the assistant declined to answer. */
    ABSTAINED
}
