package com.winlator.xenvironment;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class XEnvironmentTest {
    @Test
    public void stopComponentsInReverseOrder() {
        List<String> events = new ArrayList<>();
        XEnvironment environment = new XEnvironment(null, null);
        environment.addComponent(new RecordingComponent("first", events));
        environment.addComponent(new RecordingComponent("second", events));

        environment.stopEnvironmentComponents();

        assertEquals(Arrays.asList("second", "first"), events);
    }

    @Test
    public void stopCanExcludeSingleOwnerComponentClass() {
        List<String> events = new ArrayList<>();
        XEnvironment environment = new XEnvironment(null, null);
        environment.addComponent(new RecordingComponent("first", events));
        environment.addComponent(new ExcludedComponent(events));
        environment.addComponent(new RecordingComponent("last", events));

        environment.stopEnvironmentComponents(ExcludedComponent.class);

        assertEquals(Arrays.asList("last", "first"), events);
    }

    private static class RecordingComponent extends EnvironmentComponent {
        private final String name;
        private final List<String> events;

        RecordingComponent(String name, List<String> events) {
            this.name = name;
            this.events = events;
        }

        @Override
        public void start() {}

        @Override
        public void stop() {
            events.add(name);
        }
    }

    private static final class ExcludedComponent extends RecordingComponent {
        ExcludedComponent(List<String> events) {
            super("excluded", events);
        }
    }
}
