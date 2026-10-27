package com.example.wonway.agent.model;

/** A server-approved prototype screen, never a real banking transaction or an arbitrary URL. */
public record BusinessAction(String menuId, String label, boolean demo) {}
