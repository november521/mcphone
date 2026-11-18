package com.november.testbootstrap;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;

/** Initializes vanilla registries before an assertion loads the command tree. */
public final class VanillaAssertionMain {
    private VanillaAssertionMain() {}

    public static void main(String[] args) throws ReflectiveOperationException {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        Class.forName(args[0]).getMethod("main", String[].class).invoke(null, (Object) new String[0]);
    }
}
