package com.winlator.box;

import com.winlator.core.EnvVars;
import org.junit.Test;
import static org.junit.Assert.*;

public class BoundSessionEnvironmentTest {
    @Test public void coldGuestWritesItsOwnSessionEvenAfterAnotherIsCreated() {
        BoundSessionEnvironment cold = new BoundSessionEnvironment("session-A");
        BoundSessionEnvironment warm = new BoundSessionEnvironment("session-B");
        java.util.List<String> destinations = new java.util.ArrayList<>(); EnvVars env = new EnvVars();
        warm.write(env, (id, ignored) -> destinations.add(id));
        cold.write(env, (id, ignored) -> destinations.add(id));
        assertEquals(java.util.Arrays.asList("session-B", "session-A"), destinations);
    }
    @Test public void emptySessionNeverWrites() {
        BoundSessionEnvironment cold = new BoundSessionEnvironment(null);
        cold.write(new EnvVars(), (id, ignored) -> fail("empty session should not write"));
    }
}
