package com.november.mcphone.core.script.engine;

/** Uncatchable attribution wrapper for a ScriptAbort thrown by an external currency provider. */
final class ProviderAbort extends Error {

    private final String operation;
    private final boolean mutating;

    ProviderAbort(String operation, boolean mutating) {
        super("currency provider aborted during " + operation, null, false, false);
        this.operation = operation;
        this.mutating = mutating;
    }

    String operation() {
        return operation;
    }

    boolean mutating() {
        return mutating;
    }
}
