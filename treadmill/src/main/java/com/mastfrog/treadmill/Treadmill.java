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
import java.util.Iterator;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;

/**
 * Implements the pattern of serially executing a series of chunks of logic in a
 * thread-pool, inside of a Guice injection scope. Each Callable can provide
 * some objects which will be included in the scope's injection context for the
 * next one. Throwing Abort or returning null from the callable indicates that
 * no further items should be processed.
 * <p/>
 * The effect is rather like recursively calling callables with the output of
 * other callables, except that rather than retain the entire thread stack and
 * any final variables in scope, only the scope contents is preserved as state
 * that can be injected into objects. So there is some resemblance to
 * tail-recursion - it gets the effect of recursively calling a series of
 * callbacks with each other's output without the messiness.
 * <p/>
 * A Treadmill makes available a Deferrer object which can be used to defer
 * execution part way through, and then restart it in the future, optionally
 * injecting some additional objects into the context.
 *
 * @author Tim Boudreau
 */
public final class Treadmill {

    private final ExecutorService svc;
    private final ReentrantScope scope;
    private final Iterator<? extends Callable<Object[]>> i;
    private final Thread.UncaughtExceptionHandler h;

    /**
     * Create a new treadmill.
     *
     * @param svc A thread pool on which to do work
     * @param scope A scope which the output objects from each callable will be
     * added to before calling the next one
     * @param i An iterator of callables
     */
    public Treadmill(ExecutorService svc, ReentrantScope scope, Iterator<? extends Callable<Object[]>> i) {
        this(svc, scope, i, null);
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
    public Treadmill(ExecutorService svc, ReentrantScope scope, Iterator<? extends Callable<Object[]>> i, Thread.UncaughtExceptionHandler h) {
        this.svc = svc;
        this.scope = scope;
        this.i = i;
        this.h = h;
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
        Callable<Object[]> c;
        CountDownLatch latch = new CountDownLatch(1);
        if (scope.inScope()) {
            c = scope.wrap(new C(latch, onDone, initialContents));
        } else {
            c = new C(latch, onDone, initialContents);
        }
        svc.submit(c);
        return latch;
    }

    private static Object[] merge(Object[] a, Object[] b) {
        if (a.length == 0) {
            return b;
        } else if (b.length == 0) {
            return a;
        } else {
            Object[] nue = new Object[a.length + b.length];
            System.arraycopy(a, 0, nue, 0, a.length);
            System.arraycopy(b, 0, nue, a.length, b.length);
            return nue;
        }
    }

    /**
     * An object which is injected into the scope callables run in, which can be
     * used to halt execution of subsequent Callables, which provides a Resumer
     * - a handle whosse resume() method can be called to resume execution of
     * the remaining Callables in the iterable at some time in the future,
     * optionally injecting additional contents.
     */
    public static abstract class Deferral {

        /**
         * Defer further execution of this Treadmill until such time as resume()
         * is called on the returned resumer.
         *
         * @return
         */
        public abstract Resumer defer();

        public interface Resumer {

            /**
             * Resume execution with the next Callable. Note that the next
             * callable is not invoked synchronously
             *
             * @param addToContext Any additional objects which should be
             * injected and made available
             */
            public void resume(Object... addToContext);
        }
    }

    static class DeferralImpl extends Deferral {

        private volatile boolean deferred;
        private final ExecutorService svc;
        private C call;
        private Callable<Object[]> wrapped;
        private volatile boolean invalid;

        public DeferralImpl(ExecutorService svc) {
            this.svc = svc;
        }

        @Override
        public Resumer defer() {
            if (invalid) {
                throw new IllegalStateException("Cannot defer something which"
                        + " has already happened");
            }
            deferred = true;
            return new Resumer() {

                @Override
                public void resume(Object... addToContext) {
                    if (deferred == false) {
                        throw new IllegalStateException("Already resumed");
                    }
                    deferred = false;
                    call.include(addToContext);
                    svc.submit(wrapped);
                }
                
                public String toString() {
                    return "Resumer of " + call;
                }
            };
        }

        void prepare(Callable<Object[]> wrapped, C actual) {
            this.wrapped = wrapped;
            this.call = actual;
        }

        void done() {
            invalid = true;
        }
        
        public String toString() {
            return super.toString() + " over " + call + " + " + wrapped;
        }
    }

    private class C implements Callable<Object[]> {

        private final CountDownLatch latch;
        private final Runnable onDone;
        private Object[] last;
        private final DeferralImpl defer = new DeferralImpl(svc);

        public C(CountDownLatch latch, Runnable onDone, Object... last) {
            this.latch = latch;
            this.onDone = onDone;
            this.last = merge(last, new Object[]{defer});
        }

        void include(Object[] objs) {
            last = merge(last, objs);
        }

        private void run() throws Exception {
            Callable<Object[]> curr = i.next();
            Object[] nue = curr.call();
            if (nue != null && i.hasNext()) {
                C nextActual = new C(latch, onDone, nue);
                Callable<Object[]> next = scope.wrap(nextActual);
//                if (defer.deferred) {
                    defer.prepare(next, nextActual);
//                } else {
                if (!defer.deferred) {
                    svc.submit(next);
                }
            } else if (nue == null || !i.hasNext()) {
                try {
                    if (onDone != null) {
                        onDone.run();
                    }
                } finally {
                    latch.countDown();
                }
            }
        }

        @Override
        public Object[] call() throws Exception {
            if (i.hasNext()) {
                try (AutoCloseable cl = scope.enter(last)) {
                    try {
                        run();
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
                }
            } else {
                try {
                    if (onDone != null) {
                        onDone.run();
                    }
                } finally {
                    latch.countDown();
                }
            }
            return null;
        }
    }
}
