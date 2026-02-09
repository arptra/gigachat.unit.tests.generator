package com.gigachat.unit.tests.generator.reasoning.model;

import java.util.Objects;

/**
 * One state transition entry for a fix session.
 */
public class FixSessionTransition {

    private final int index;
    private final FixSessionState from;
    private final FixSessionState to;
    private final String reason;

    public FixSessionTransition(int index, FixSessionState from, FixSessionState to, String reason) {
        this.index = index;
        this.from = from;
        this.to = to;
        this.reason = reason;
    }

    public int getIndex() {
        return index;
    }

    public FixSessionState getFrom() {
        return from;
    }

    public FixSessionState getTo() {
        return to;
    }

    public String getReason() {
        return reason;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        FixSessionTransition that = (FixSessionTransition) o;
        return index == that.index
                && from == that.from
                && to == that.to
                && Objects.equals(reason, that.reason);
    }

    @Override
    public int hashCode() {
        return Objects.hash(index, from, to, reason);
    }

    @Override
    public String toString() {
        return "FixSessionTransition{" +
                "index=" + index +
                ", from=" + from +
                ", to=" + to +
                ", reason='" + reason + '\'' +
                '}';
    }
}

