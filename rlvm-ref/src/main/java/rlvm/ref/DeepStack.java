package rlvm.ref;

/**
 * A helper thread with a large stack on which a session re-runs a declaration whose recursion did
 * not fit the calling thread. The hand-over is synchronous: the caller blocks until the task has
 * finished, so the session's data stay confined to one running thread at a time.
 */
final class DeepStack implements Runnable {

    /** Stack reservation of the helper thread; pages are committed only when touched. */
    static final long STACK_BYTES = Long.getLong("rlvm.ref.deepStackMB", 1024L) << 20;

    private final Thread thread;
    private Runnable task;
    private Throwable failure;
    private boolean busy;
    private boolean closed;

    DeepStack(String name) {
        thread = new Thread(null, this, name, STACK_BYTES);
        thread.setDaemon(true);
        thread.start();
    }

    /** Runs {@code work} on the deep stack and returns when it is done; rethrows what it threw. */
    synchronized void run(Runnable work) {
        if (closed) {
            throw new IllegalStateException("deep stack closed");
        }
        task = work;
        failure = null;
        busy = true;
        notifyAll();
        boolean interrupted = false;
        while (busy) {
            try {
                wait();
            } catch (InterruptedException e) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
        Throwable f = failure;
        failure = null;
        if (f instanceof RuntimeException) {
            throw (RuntimeException) f;
        }
        if (f instanceof Error) {
            throw (Error) f;
        }
    }

    @Override
    public void run() {
        for (;;) {
            Runnable work;
            synchronized (this) {
                while (task == null && !closed) {
                    try {
                        wait();
                    } catch (InterruptedException e) {
                        // only close() ends the thread
                    }
                }
                if (task == null) {
                    return;
                }
                work = task;
                task = null;
            }
            Throwable f = null;
            try {
                work.run();
            } catch (Throwable t) {
                f = t;
            }
            synchronized (this) {
                failure = f;
                busy = false;
                notifyAll();
            }
        }
    }

    synchronized void close() {
        closed = true;
        notifyAll();
    }
}
