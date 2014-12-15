/*
 * The MIT License
 *
 * Copyright 2014 Tim Boudreau.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package com.mastfrog.treadmill;

import com.mastfrog.guicy.scope.ReentrantScope;
import com.mastfrog.treadmill.Treadmill.Deferral;
import com.mastfrog.treadmill.Treadmill.Deferral.Resumer;
import com.mastfrog.util.Exceptions;
import com.mastfrog.util.thread.QuietAutoCloseable;
import java.util.Iterator;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Synchronous version of Treadmill
 *
 * @author Tim Boudreau
 */
public class SynchronousTreadmill {

    private ReentrantScope scope;
    private Iterator<? extends Callable<Object[]>> i;
    private Thread.UncaughtExceptionHandler h;

    /**
     * Create a new treadmill.
     *
     * @param svc A thread pool on which to do work
     * @param scope A scope which the output objects from each callable will be
     * added to before calling the next one
     * @param i An iterator of callables
     */
    public SynchronousTreadmill(ReentrantScope scope, Iterator<? extends Callable<Object[]>> i) {
        this(scope, i, null);
    }

    /**
     * Create a new treadmill.
     *
     * @param svc A thread pool on which to do work
     * @param scope A scope which the output objects from each callable will be
     * added to before calling the next one
     * @param i An iterator of callables
     * @param h Something to handle errors. If null, errors will be rethrown and
     * the current thread's uncaught exception handler will have to deal with
     * them
     */
    public SynchronousTreadmill(ReentrantScope scope, Iterator<? extends Callable<Object[]>> i, Thread.UncaughtExceptionHandler h) {
        this.scope = scope;
        this.i = i;
        this.h = h;
    }

    /**
     * Submit the initial callable to the thread pool, starting the chain.
     *
     * @param onDone A runnable which should be run after the last Callable that
     * will be run is run, if that callable exits by returning null or throwing
     * Abort (throwing anything else will cause the UncaughtExceptionHandler to
     * be called, but the runnable will not be).
     * @param initialContents Any initial contents for the scope
     * @return A latch which can be awaited - use this only in tests if you want
     * actual concurrency!
     */
    public CountDownLatch start(Runnable onDone, Object... initialContents) {
        CountDownLatch latch = new CountDownLatch(1);
        try {
            new Runner(onDone, latch, initialContents).call();
        } catch (Exception ex) {
            Exceptions.chuck(ex);
        }
        return latch;
    }

    /**
     * Submit the initial callable to the thread pool, starting the chain
     *
     * @param initialContents Any initial contents for the scope
     * @return A latch which can be awaited - use this only in tests if you want
     * actual concurrency!
     */
    public CountDownLatch start(Object... initialContents) {
        return start(null, initialContents);
    }

    class Runner implements Callable<Object[]> {

        private final Runnable onDone;
        private final CountDownLatch latch;

        private final AtomicReference<Object[]> contents = new AtomicReference<>(new Object[0]);
        private final Defer defer = new Defer();

        Runner(Runnable onDone, CountDownLatch latch, Object... contents) {
            this.contents.set(contents);
            this.onDone = onDone;
            this.latch = latch;
        }

        @Override
        public Object[] call() throws Exception {
            if (!i.hasNext()) {
                if (onDone != null) {
                    onDone.run();
                }
                latch.countDown();
                return null;
            }
            Object[] result = null;
            Object[] current = contents.get();
            try (QuietAutoCloseable e0 = scope.enter(defer)) {
                try (QuietAutoCloseable e = scope.enter(current)) {
                    result = i.next().call();
                }
            } catch (Abort abort) {
                try {
                    if (onDone != null) {
                        onDone.run();
                    }
                } finally {
                    latch.countDown();
                }
                return null;
            } catch (Error | Exception e) {
                if (h != null) {
                    h.uncaughtException(Thread.currentThread(), e);
                    latch.countDown();
                } else {
                    throw e;
                }
            }
            boolean more = result != null && i.hasNext();
            if (more && result != null && result.length > 0) {
                newContents(current, result);
            }
            if (!defer.deferred.get() && more) {
                call();
            } else if (!more) {
                if (onDone != null) {
                    onDone.run();
                }
                latch.countDown();
            }
            return result;
        }

        private void newContents(Object[] added, Object[] current) {
            if (added == null || added.length == 0) {
                return;
            }
            Object[] nue = new Object[current.length + added.length];
            System.arraycopy(current, 0, nue, 0, current.length);
            System.arraycopy(added, 0, nue, current.length, added.length);
            contents.set(nue);
        }

        private class Defer extends Deferral implements Resumer {

            private final AtomicBoolean deferred = new AtomicBoolean();

            boolean isDeferred() {
                return deferred.get();
            }

            @Override
            public Deferral.Resumer defer() {
                if (deferred.compareAndSet(false, true)) {
                    return this;
                }
                throw new IllegalStateException("Already deferred");
            }

            @Override
            public void resume(Object... addToContext) {
                if (deferred.compareAndSet(true, false)) {
                    if (addToContext != null && addToContext.length > 0) {
                        newContents(contents.get(), addToContext);
                    }
                    defer.deferred.set(false);
                    try {
                        Runner.this.call();
                    } catch (Exception ex) {
                        Exceptions.chuck(ex);
                    }
                } else {
                    throw new IllegalStateException("Not paused");
                }
            }
        }
    }
}
