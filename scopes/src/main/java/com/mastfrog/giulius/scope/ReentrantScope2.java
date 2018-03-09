/*
 * The MIT License
 *
 * Copyright 2015 Tim Boudreau.
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
package com.mastfrog.giulius.scope;

import com.google.inject.Binder;
import com.google.inject.Inject;
import com.google.inject.Injector;
import com.google.inject.Key;
import com.google.inject.Provider;
import com.mastfrog.util.Invokable;
import com.mastfrog.util.Strings;
import com.mastfrog.util.collections.CollectionUtils;
import static com.mastfrog.util.collections.CollectionUtils.reversed;
import com.mastfrog.util.thread.NonThrowingAutoCloseable;
import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

/**
 *
 * @author Tim Boudreau
 */
public class ReentrantScope2 extends AbstractScope implements NonThrowingAutoCloseable {

    private int[] types = new int[0];
    private boolean[] nullable = new boolean[0];
    private Class<?>[] classes = new Class<?>[0];
    private Provider<?>[] providers = new Provider<?>[0];

    private ThreadLocal<LinkedList<Object[]>> contents = new ThreadLocal<>();

    private <T> int indexOf(T obj) {
        for (int i = 0; i < classes.length; i++) {
            if (classes[i].isInstance(obj)) {
                return i;
            }
        }
        return -1;
    }

    <T> T lookup(Class<T> type, boolean throwIfNecessaryOnNull) {
        LinkedList<Object[]> objs = contents.get();
        if (objs == null) {
            return null;
        }
        int ihc = System.identityHashCode(type);
        int ix = Arrays.binarySearch(types, ihc);
        if (ix < 0) {
            return null;
        }
        Object result = null;
        int id = 0;
        for (Object[] arr : objs) {
            result = arr[ix];
            if (result != null) {
                if (type == CharSequence.class) {
                    System.out.println("FOUND " + result + " in slot " + ix + " for CharSequence in arr " + id);
                }
                break;
            }
            id++;
        }
        if (result == null) {
            System.out.println("FAILOVER SEARCH " + type.getName());
            for (Object[] arr : reversed(objs)) {
                System.out.println("SEARCH " + Strings.join(',', objs));
                for (int i = arr.length - 1; i >= 0; i--) {
                    Object o = arr[i];
                    if (type.isInstance(o)) {
                        System.out.println("  MATCH ON " + o + " at " + i);
                        result = o;
                        break;
                    }
                }
            }
        }
        if (result == null && throwIfNecessaryOnNull && !nullable[ix]) {
            throw new IllegalStateException("No instance of " + type.getName() + " in scope");
        }
        return type.cast(result);
    }

    private LinkedList<Object[]> contents(boolean create) {
        LinkedList<Object[]> result = contents.get();
        if (create && result == null) {
            result = new LinkedList<>();
            contents.set(result);
        }
        return result;
    }

    private synchronized <T> int addType(Class<T> type, boolean isNullable) {
        final int ihc = System.identityHashCode(type);
        int result;
        if ((result = Arrays.binarySearch(types, ihc)) >= 0) {
            return result;
        }
        List<Tmp> tmpList = tmpList();
        tmpList.add(new Tmp(ihc, type, isNullable, new P<>(type)));
        Collections.sort(tmpList);

        int[] newTypes = new int[types.length + 1];
        boolean[] newNullable = new boolean[types.length + 1];
        Class<?>[] newClasses = new Class<?>[types.length + 1];
        Provider<?>[] newProviders = new Provider<?>[types.length + 1];
        int max = tmpList.size();
        for (int i = 0; i < max; i++) {
            Tmp tmp = tmpList.get(i);
            newTypes[i] = tmp.ihc;
            newNullable[i] = tmp.nullable;
            newClasses[i] = tmp.type;
            newProviders[i] = tmp.provider;
        }
        types = newTypes;
        nullable = newNullable;
        classes = newClasses;
        providers = newProviders;
        result = Arrays.binarySearch(types, ihc);
        return result;
    }

    private String boundTypes() {
        StringBuilder sb = new StringBuilder();
        for (Class<?> c : classes) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(c.getName());
        }
        return sb.toString();
    }

    public String scopeContentsAsString() {
        Object[] result = new Object[types.length];
        List<Object[]> objs = contents(false);
        if (objs == null || objs.isEmpty()) {
            return "";
        }
        for (Object[] obj : objs) {
            for (int i = 0; i < obj.length; i++) {
                if (result[i] == null && obj[i] != null) {
                    result[i] = obj[i];
                }
            }
        }
        StringBuilder sb = new StringBuilder();
        for (Object o : result) {
            if (o == null) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(o);
        }
        return sb.toString();
    }

    List<Object> scopeContents() {
        Object[] result = new Object[types.length];
        List<Object[]> objs = contents(false);
        if (objs == null || objs.isEmpty()) {
            return Collections.emptyList();
        }
        for (Object[] obj : CollectionUtils.reversed(objs)) {
            for (int i = 0; i < obj.length; i++) {
                if (result[i] == null && obj[i] != null) {
                    result[i] = obj[i];
                }
            }
        }
        List<Object> all = new ArrayList<>(types.length);
        for (Object o : result) {
            all.add(o);
        }
        return all;
    }

    @Override
    @SuppressWarnings({"unchecked", "rawtype"})
    public <T> Provider<T> scope(Key<T> key, Provider<T> prvdr) {
        Class<? super T> type = key.getTypeLiteral().getRawType();
        int ix = indexOfType(type);
        if (ix >= 0) {
            Provider<T> p = (Provider<T>) providers[ix];
            return new DelegateProvider(p, prvdr);
        }
        return prvdr;
    }

    @Override
    protected <T> T get(Class<T> type) {
        return lookup(type, false);
    }

    private static class DelegateProvider<T> implements Provider<T> {

        private final Provider<T> a;
        private final Provider<T> b;

        public DelegateProvider(Provider<T> a, Provider<T> b) {
            this.a = a;
            this.b = b;
        }

        @Override
        public T get() {
            T result = a.get();
            if (result == null) {
                result = b.get();
            }
            return result;
        }
    }

    private class P<T> implements Provider<T> {

        int ix = -1;
        private final Class<T> type;

        public P(Class<T> type) {
            this.type = type;
        }

        private T onNull() {
            throw new IllegalStateException("No instance of " + type.getName()
                    + " in scope.  Contents: " + scopeContentsAsString()
                    + ".  Bound: " + boundTypes());
        }

        @Override
        public T get() {
            if (ix == -1) {
                ix = indexOfType(type);
            }
            if (ix < 0) {
                return null;
            }
            LinkedList<Object[]> objs = contents.get();
            if (objs == null) {
                return null;
            }
            Object result = null;
//            int mx = objs.size() - 1;
//            for (int i = mx; i >= 0; i--) {
            int mx = objs.size();
            for (int i = 0; i < mx; i++) {
                Object[] arr = objs.get(i);
                result = arr[ix];
                if (result != null) {
                    break;
                }
            }
            return result == null ? null : type.cast(result);
        }
    }

    public int indexOfType(Class<?> type) {
        int ihc = System.identityHashCode(type);
        int ix = Arrays.binarySearch(types, ihc);
        if (ix >= 0) {
            return ix;
        }
        for (Class<?> c : type.getInterfaces()) {
            ihc = System.identityHashCode(c);
            ix = Arrays.binarySearch(types, ihc);
            if (ix >= 0) {
                return ix;
            }
        }
        while (type != Object.class) {
            type = type.getSuperclass();
            ihc = System.identityHashCode(type);
            ix = Arrays.binarySearch(types, ihc);
            if (ix >= 0) {
                return ix;
            }
        }
        return -1;
    }

    private List<Tmp> tmpList() {
        List<Tmp> result = new ArrayList<>(types.length);
        for (int i = 0; i < types.length; i++) {
            result.add(new Tmp(types[i], classes[i], nullable[i], providers[i]));
        }
        return result;
    }

    private static final class Tmp implements Comparable<Tmp> {

        private final int ihc;
        private final Class<?> type;
        private final boolean nullable;
        private final Provider<?> provider;

        public Tmp(int ihc, Class<?> type, boolean nullable, Provider<?> provider) {
            this.ihc = ihc;
            this.type = type;
            this.nullable = nullable;
            this.provider = provider;
        }

        @Override
        public int compareTo(Tmp o) {
            return ihc == o.ihc ? 0 : ihc > o.ihc ? 1 : -1;
        }
    }

    protected void bind(Binder binder, Class<?>... types) {
        bind(null, binder, types);
    }

    protected void bind(Class<? extends Annotation> scopeAnnotationType, Binder binder, Class<?>... types) {
        if (scopeAnnotationType != null) {
            binder.bindScope(scopeAnnotationType, this);
        }
        for (Class<?> type : types) {
            bindInScope(binder, type);
        }
    }

    private final List<Object[]> arrays = new CopyOnWriteArrayList<>();

    private Object[] pooledArray() {
        if (arrays.isEmpty()) {
            Object[] result = new Object[types.length];
            return result;
        }
        try {
            return arrays.remove(0);
        } catch (Exception e) {
            return new Object[types.length];
        }
    }

    protected NonThrowingAutoCloseable enter(Object... scopeContents) {
        Object[] contents = pooledArray();
        for (Object c : scopeContents) {
            int ix = indexOf(c);
            if (ix >= 0) {
                contents[ix] = classes[ix].cast(c);
            }
        }
        LinkedList<Object[]> objs = contents(true);
        objs.push(contents);
        return this;
    }

    protected void exit() {
        LinkedList<Object[]> objs = contents(false);
        if (objs == null || objs.isEmpty()) {
            throw new IllegalStateException("Asymmetric calls to enter and exit");
        }
        Object[] reuse = objs.pop();
        Arrays.fill(reuse, null);
        arrays.add(reuse);
    }

    public void close() {
        exit();
    }

    public boolean inScope() {
        List<?> l = this.contents.get();
        return l == null || l.isEmpty() ? false : true;
    }

    /**
     * Bind a list of types, but return null instead of throwing an exception if
     * the types are not available.
     *
     * @param binder The module binder
     * @param types An array of types
     */
    protected void bindAllowingNulls(Binder binder, Class<?>... types) {
        for (Class<?> type : types) {
            bindInScopeAllowingNulls(binder, type);
        }
    }

    @SuppressWarnings("unchecked")
    protected <T> void bindInScopeAllowingNulls(Binder binder, Class<T> type) {
        int ix = addType(type, true);
        Provider<T> lkpProvider = (Provider<T>) providers[ix];
        binder.bind(type).toProvider(lkpProvider);
    }

    @SuppressWarnings("unchecked")
    protected <T> void bindInScope(Binder binder, Class<T> type) {
        int ix = addType(type, false);
        Provider<T> lkpProvider = (Provider<T>) providers[ix];
        binder.bind(type).toProvider(lkpProvider);

    }

    /**
     * Get the provider in this scope, passing in the provider to use when not
     * in-scope.
     *
     * @param <T>
     * @param type The type
     * @param unscoped The fallback
     * @return A provider
     */
//    public <T> Provider<T> provider(Class<T> type, Provider<T> unscoped) {
//        int ix = indexOfType(type);
//        if (ix == -1) {
//            throw new IllegalArgumentException(type.getName() + " is not bound");
//        }
//        return scope(Key.get(type), unscoped);
//    }
    /**
     * Convenience method to bind some types in this scope.
     *
     * @param binder
     * @param types
     */
    public void bindTypes(Binder binder, Class<?>... types) {
        for (Class<?> type : types) {
            bindInScope(binder, type);
        }
    }

    /**
     * Enter this scope with an Invokable (similar to Callable, but takes a
     * typed argument).
     *
     * @param <T>
     * @param <A>
     * @param <E>
     * @param invokable A callable equivalent
     * @param arg The argument to its run method
     * @param scopeContents Objects which should be available for injection
     * @return The value returned by the Invokable's run method
     * @throws E
     */
    public <T, A, E extends Exception> T run(Invokable<A, T, E> invokable, A arg, Object... scopeContents) throws E {
        enter(scopeContents);
        try {
            return invokable.run(arg);
        } finally {
            exit();
        }
    }

    /**
     * Enter this scope with an array of objects which should be available for
     * injection and a Callable.
     *
     * @param <T>
     * @param callable A callable
     * @param args Objects for injection
     * @return The return value of the callable
     * @throws Exception
     */
    public <T> T run(Callable<T> callable, Object... args) throws Exception {
        return run(new CallableWrapper<T>(), callable, args);
    }

    /*
     * Enter this scope and run the passed runnable, making the arguments
     * available for injection
     */
    public void run(Runnable runnable, Object... args) {
        run(new RunnableWrapper(), runnable, args);
    }

    static class CallableWrapper<T> extends Invokable<Callable<T>, T, Exception> {

        @Override
        public T run(Callable<T> argument) throws Exception {
            return argument.call();
        }
    }

    static class RunnableWrapper extends Invokable<Runnable, Void, RuntimeException> {

        @Override
        public Void run(Runnable argument) throws RuntimeException {
            argument.run();
            return null;
        }
    }

    /**
     * Simple way to schedule some work on another thread to be run in-process
     * in an identical scope. Note that all objects in this scope must be
     * thread-safe, and that this means that such objects may be much longer
     * lived than would otherwise be the case. Use with care.
     *
     * @param runnable
     * @param executor
     */
    public void join(final Runnable runnable, final ExecutorService executor) {
        if (!inScope()) {
            throw new IllegalThreadStateException("Not in scope " + this);
        }
        final Object[] o = contents().toArray();
        executor.submit(new Runnable() {
            @Override
            public void run() {
                try {
                    enter(o);
                    runnable.run();
                } finally {
                    exit();
                }
            }
        });
    }

    /**
     * Wrap a JDK thread pool in one whose submitted runnables will first enter
     * this scope.
     *
     * @param service An executor service
     * @return A wrapper for that executor service
     */
    public ExecutorService wrapThreadPool(ExecutorService service) {
        if (service instanceof ScopedThreadPool2 && ((ScopedThreadPool2) service).scope == this) {
            return service;
        }
        return new ScopedThreadPool2(this, service);
    }

    /**
     * Wrap a runnable to enter this scope before it is run
     *
     * @param runnable A runnable
     * @return A wrapper which delegates to this runnable
     */
    public Runnable wrap(final Runnable runnable) {
        if (runnable instanceof WrapRunnable && ((WrapRunnable) runnable).scope == this) {
            return runnable;
        }
        if (!inScope()) {
            throw new IllegalThreadStateException("Not in scope " + this);
        }
        return new WrapRunnable(runnable, this);
    }

    public <T, R, E extends Exception> Invokable<T, R, E> wrap(Invokable<T, R, E> i, AtomicReference<T> arg) {
        if (i instanceof WrapInvokable && ((WrapInvokable) i).scope == this) {
            return i;
        }
        return new WrapInvokable<T, R, E>(this, i, arg);
    }

    /**
     * Wrap a callable to enter this scope before it is run
     *
     * @param callable A callable
     * @return A wrapper which delegates to this callable
     */
    public <T> Callable<T> wrap(Callable<T> wrapped) {
        return new WrapCallable<>(wrapped);
    }

    public <T> Callable<T> wrap(Callable<T> callable, Object... contents) {
        return new WrapCallable<>(callable, contents);
    }

    private class WrapCallable<T> implements Callable<T> {

        private final Callable<T> wrapped;
        private final Object[] contents;

        public WrapCallable(Callable<T> wrapped, Object... contents) {
            this.wrapped = wrapped;
            List<Object> l = ReentrantScope2.this.contents();
            l.addAll(Arrays.asList(contents));
            this.contents = l.toArray();
        }

        public WrapCallable(Callable<T> wrapped) {
            this.wrapped = wrapped;
            contents = contents().toArray(new Object[0]);
        }

        @Override
        public T call() throws Exception {
            try (NonThrowingAutoCloseable qac = enter(contents)) {
                return wrapped.call();
            }
        }
    }

    protected List<Object> contents() {
        return scopeContents();
    }

    static class WrapRunnable implements Runnable {

        private final Runnable run;
        private final ReentrantScope2 scope;
        private final Object[] scopeContents;
        private final RuntimeException t = new RuntimeException(); //XXX deleteme

        WrapRunnable(Runnable run, ReentrantScope2 scope) {
            this.run = run;
            this.scope = scope;
            scopeContents = scope.contents().toArray();
        }

        @Override
        public void run() {
            scope.enter(scopeContents);
            try {
                run.run();
            } catch (RuntimeException e) {
                t.initCause(e);
                throw t;
            } finally {
                scope.exit();
            }
        }

        public Runnable unwrap() {
            return run;
        }

        @Override
        public String toString() {
            return "Wrapper{" + run + "} with " + Arrays.asList(scopeContents);
        }
    }

    static class WrapInvokable<T, R, E extends Exception> extends Invokable<T, R, E> {

        private final ReentrantScope2 scope;
        private final Invokable<T, R, E> invokable;
        private final Object[] scopeContents;
        private final AtomicReference<T> arg;

        WrapInvokable(ReentrantScope2 scope, Invokable<T, R, E> callable, AtomicReference<T> arg) {
            this.scope = scope;
            this.invokable = callable;
            scopeContents = scope.contents().toArray();
            this.arg = arg;
        }

        Invokable<T, R, E> unwrap() {
            return invokable;
        }

        public String toString() {
            return invokable.toString();
        }

        @Override
        public R run(T argument) throws E {
            scope.enter(scopeContents);
            try {
                return invokable.run(arg.get());
            } finally {
                scope.exit();
            }
        }
    }

    final class ScopedThreadPool2 implements ExecutorService {

        final ReentrantScope2 scope;
        private final ExecutorService executor;

        ScopedThreadPool2(ReentrantScope2 scope, ExecutorService executor) {
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

    public static final class ScopeRunner2 {

        private final Injector injector;
        private final ReentrantScope2 scope;

        @Inject
        ScopeRunner2(Injector injector, ReentrantScope2 scope) {
            this.injector = injector;
            this.scope = scope;
        }

        public ReentrantScope2 scope() {
            return scope;
        }

        public void run(Class<? extends Runnable> type) {
            if (!scope.inScope()) {
                throw new IllegalStateException("Not in " + scope);
            }
            Runnable r = injector.getInstance(type);
            r.run();
        }

        public <T> T call(Class<? extends Callable<T>> type) throws Exception {
            if (!scope.inScope()) {
                throw new IllegalStateException("Not in " + scope);
            }
            return injector.getInstance(type).call();
        }

        public <T, R, E extends Exception> R invoke(Class<? extends Invokable<T, R, E>> type, T arg) throws E {
            if (!scope.inScope()) {
                throw new IllegalStateException("Not in " + scope);
            }
            return injector.getInstance(type).run(arg);
        }
    }
}
