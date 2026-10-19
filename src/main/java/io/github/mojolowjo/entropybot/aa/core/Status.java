package io.github.mojolowjo.entropybot.aa.core;

/** AA v0 (AA_PLAN 2): a node's answer for one tick. A FAILURE carries its reason in {@link Node#reason()}. */
public enum Status { RUNNING, SUCCESS, FAILURE }
