/* 
 * The MIT License
 *
 * Copyright 2013 Tim Boudreau.
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
package com.mastfrog.guicy.scope;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.*;

/**
 *
 * @author Tim Boudreau
 */
final class ScopedThreadPool implements ExecutorService {
    final AbstractScope scope;
    private final ExecutorService executor;

    ScopedThreadPool(AbstractScope scope, ExecutorService executor) {
        this.scope = scope;
        this.executor = executor;
    }

    @Override
    public void shutdown() {
        executor.shutdown();
    }

    @Override
    public List<Runnable> shutdownNow() {
        List<Runnable> found = executor.shutdownNow();
        List<Runnable> result = new ArrayList<>(found.size());
        for (Runnable r : found) {
            if (r instanceof AbstractScope.WrapRunnable) {
                r = ((AbstractScope.WrapRunnable) r).unwrap();
            }
            result.add(r);
        }
        return result;
    }

    @Override
    public boolean isShutdown() {
        return executor.isShutdown();
    }

    @Override
    public boolean isTerminated() {
        return executor.isTerminated();
    }

    @Override
    public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
        return executor.awaitTermination(timeout, unit);
    }

    @Override
    public <T> Future<T> submit(Callable<T> task) {
        return executor.submit(scope.wrap(task));
    }

    @Override
    public <T> Future<T> submit(Runnable task, T result) {
        return executor.submit(scope.wrap(task), result);
    }

    @Override
    public Future<?> submit(Runnable task) {
        Runnable wrapped = scope.wrap(task);
        return executor.submit(wrapped);
    }
    
    <T> List<Callable<T>> wrap(Collection<? extends Callable<T>> tasks) {
        List<Callable<T>> callables = new ArrayList<>(tasks.size());
        for (Callable<T> c : tasks) {
            callables.add(scope.wrap(c));
        }
        return callables;
    }

    @Override
    public <T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> tasks) throws InterruptedException {
        return executor.invokeAll(wrap(tasks));
    }

    @Override
    public <T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit) throws InterruptedException {
        return executor.invokeAll(wrap(tasks), timeout, unit);
    }

    @Override
    public <T> T invokeAny(Collection<? extends Callable<T>> tasks) throws InterruptedException, ExecutionException {
        return executor.invokeAny(wrap(tasks));
    }

    @Override
    public <T> T invokeAny(Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit) throws InterruptedException, ExecutionException, TimeoutException {
        return executor.invokeAny(wrap(tasks), timeout, unit);
    }

    @Override
    public void execute(Runnable command) {
//        executor.execute(scope.wrap(command));
        executor.execute(command);
    }
}
