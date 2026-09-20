package com.november.mcphone.core.script.engine;

/** Per-evaluation state shared by the script worker and the main-thread currency gateway. */
public final class MoneyLedger {

    private volatile boolean moved;

    public boolean moved() {
        return moved;
    }

    void movedMoney() {
        moved = true;
    }

    void rejectFurther(String operation) {
        if (moved) {
            throw HostError.invalid("money already moved in this evaluation; rejected " + operation);
        }
    }
}
