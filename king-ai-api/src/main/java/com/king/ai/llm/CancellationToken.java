package com.king.ai.llm;

import okhttp3.Call;

import java.util.concurrent.atomic.AtomicBoolean;

public final class CancellationToken {

    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private volatile Call call;

    public void attach(Call call) {
        this.call = call;
        if (cancelled.get()) {
            call.cancel();
        }
    }

    public void cancel() {
        cancelled.set(true);
        Call current = call;
        if (current != null) {
            current.cancel();
        }
    }

    public boolean isCancelled() {
        return cancelled.get();
    }

    public void throwIfCancelled() {
        if (cancelled.get()) {
            throw new LlmCancelledException();
        }
    }
}
