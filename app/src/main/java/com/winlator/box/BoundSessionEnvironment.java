package com.winlator.box;

import com.winlator.core.EnvVars;

/** Bind a cold guest's final environment to its original session, never a later warm one. */
public final class BoundSessionEnvironment {
    public interface Writer { void write(String sessionId, EnvVars env); }
    private final String sessionId;
    public BoundSessionEnvironment(String sessionId) { this.sessionId = sessionId == null ? "" : sessionId; }
    public void write(EnvVars env, Writer writer) {
        if (!sessionId.isEmpty() && env != null && writer != null) writer.write(sessionId, env);
    }
}
