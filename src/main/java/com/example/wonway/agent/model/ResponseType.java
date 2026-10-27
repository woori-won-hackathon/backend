package com.example.wonway.agent.model;

/** Explicit conversation state, decided from validated tool results rather than answer wording. */
public enum ResponseType {
    FOLLOW_UP, GUIDANCE, CHANNEL_RECOMMENDATION, UNRESOLVED
}
