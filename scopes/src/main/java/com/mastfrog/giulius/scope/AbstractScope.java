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
package com.mastfrog.giulius.scope;

import com.google.inject.Binder;
import com.google.inject.Injector;
import com.google.inject.Key;
import com.google.inject.Provider;
import com.google.inject.Scope;
import com.google.inject.util.Providers;
import com.mastfrog.util.function.Invokable;
import com.mastfrog.util.strings.AlignedText;
import java.lang.annotation.Annotation;
import java.lang.reflect.TypeVariable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;
import com.mastfrog.util.thread.QuietAutoCloseable;

/**
 * Base class for custom scope implementations. The basic model here is that you
 * "enter" a scope with some objects which can be injected within the scope.
 * <p/>
 * If you want to get objects injected without having to handle the injector,
 * bind AbstractScope to some scope instance, and then inject ScopeRunner, which
 * can be passed Class<Callable>, etc.
 *
 * @author Tim Boudreau
 */
public abstract class AbstractScope implements Scope {
    //For debugging - be able to list the types that are bound

    private final Set<Class<?>> types = new HashSet<>();
    private final Set<Class<?>> nullableTypes = new HashSet<>();
    @SuppressWarnings("NonConstantLogger")
    protected final Logger logger = Logger.getLogger(getClass().getName());

    private final Provider<String> injectionInfoProvider;

    public AbstractScope() {
        this(Providers.of(""));
    }

    /**
     * Create a scope with a provider for information to use in error messages
     * when something unavailable is requested for injection. The Provider
     * interface is used here simply to decouple the actual code doing this from
     * the scopes module.
     *
     * @see com.mastfrog.giulius.InjectionInfo
     * @param injectionInfoProvider
     */
    public AbstractScope(Provider<String> injectionInfoProvider) {
        this.injectionInfoProvider = injectionInfoProvider;
    }

    /**
     * Get the set of all types bound by using methods on this instance.
     *
     * @return The types
     */
    public Set<Class<?>> allTypes() {
        Set<Class<?>> result = new HashSet<>();
        result.addAll(types);
        result.addAll(nullableTypes);
        return Collections.unmodifiableSet(result);
    }

    /**
     * Bind a list of classes in this scope
     *
     * @param binder The module binder
     * @param types The types
     */
    protected void bind(Binder binder, Class<?>... types) {
        bind(null, binder, types);
    }

    protected void bind(Class<? extends Annotation> scopeAnnotationType, Binder binder, Class<?>... types) {
        if (scopeAnnotationType != null) {
            binder.bindScope(scopeAnnotationType, this);
        }
        for (Class<?> type : types) {
            bindInScope(binder, type);
            this.types.add(type);
        }
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
            this.nullableTypes.add(type);
        }
    }

    public ScopeRunner runner(Injector inj) {
        return new ScopeRunner(inj, this);
    }

    protected <T> void bindInScopeAllowingNulls(Binder binder, Class<T> type) {
        Provider<T> delegate = Providers.of(null);
        Provider<T> lkpProvider = new ProviderOverLookup<>(type, delegate);
        nullableTypes.add(type);
        binder.bind(type).toProvider(lkpProvider);
    }

    protected <T> void bindInScope(Binder binder, Class<T> type) {
        binder.bind(type).toProvider(new ProviderOverLookup<>(type, new ErrorProvider<>(type)));
        types.add(type);
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
    public <T> Provider<T> provider(Class<T> type, Provider<T> unscoped) {
        return scope(Key.get(type), unscoped);
    }

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
     * Convenience method to bind some types in this scope, and permit nulls to
     * be returned if necessary.
     *
     * @param binder
     * @param types
     */
    public void bindTypesAllowingNulls(Binder binder, Class<?>... types) {
        for (Class<?> type : types) {
            bindInScopeAllowingNulls(binder, type);
        }
    }

    /**
     * Enter this scope - must always be paired with a call to exit() in a
     * finally block or very, very bad things will happen. Prefer one of the
     * methods that takes Runnable/Callable/Invokable where possible.
     * <p/>
     * Remember that an objects you pass to <code>enter()</code> <i>must</i>
     * be bound in this scope. The method <code>bindTypes()</code> is here to
     * assist with that, or you can simply use the usual
     * <code>bind(Foo.class).in(scope)</code> syntax.
     *
     * @param scopeContents Any objects which should be available for injection
     * @return An instance of AutoClosable which can be used with JDK-7's
     * try-with-resources to ensure the scope is exited.
     */
    protected abstract QuietAutoCloseable enter(Object... scopeContents);

    /**
     * Exit the scope. Must be called symmetrically with enter.
     */
    protected abstract void exit();

    /**
     * Get an object in scope, if any. Throws an exception if not in scope, or
     * if in scope but not bound (see <code>bindAllowingNulls()</code>).
     *
     * @param <T>
     * @param type
     * @return
     */
    protected abstract <T> T get(Class<T> type);

    /**
     * Test if the caller is in scope.
     *
     * @return
     */
    public abstract boolean inScope();

    /**
     * Used to pre-convert objects passed in on scope entry to their final form.
     * The default implementation returns the same array it is passed.
     *
     * @param originals Objects passed into enter()
     * @return An array of objects
     */
    protected Object[] convertObjects(Object... originals) {
        return originals;
    }
    /**
     * If true, construct a stack trace for every call to enter(), so that
     * invocations replanned to another thread can be traced to their origin
     * call for debugging.
     */
    protected volatile boolean includeStackTraces = false;

    /**
     * Utility method which will record stack traces when the scope is entered.
     * Useful for debugging when using ScopedThreadPools, to track the actual
     * origin stack of something dispatched to another thread.
     *
     * @param val
     */
    public void setIncludeStackTraces(boolean val) {
        includeStackTraces = val;
    }

    /**
     * If in scope on the calling thread, determine if the scope currently
     * contains the passed type
     *
     * @param type A type
     * @return true if one is present
     */
    public boolean contains(Class<?> type) {
        return get(type) != null;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder(getClass().getSimpleName()).append("{");
        for (Object o : contents()) {
            sb.append('\t').append(o.getClass().getName()).append(": ").append(o).append('\n');
        }
        return sb.append("}").toString();
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
        enter(args);
        try {
            return callable.call();
        } finally {
            exit();
        }
    }

    /*
     * Enter this scope and run the passed runnable, making the arguments
     * available for injection
     */
    public void run(Runnable runnable, Object... args) {
        enter(args);
        try {
            runnable.run();
        } finally {
            exit();
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
        if (service instanceof ScopedThreadPool && ((ScopedThreadPool) service).scope == this) {
            return service;
        }
        return new ScopedThreadPool(this, service);
    }

    public Provider<ExecutorService> wrapThreadPool(Provider<ExecutorService> exe) {
        return new Provider<ExecutorService>() {
            private ExecutorService val;

            @Override
            public ExecutorService get() {
                if (val != null) {
                    return val;
                }
                ExecutorService orig = exe.get();
                return val = new ScopedThreadPool(AbstractScope.this, orig);
            }
        };
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
        return new WrapInvokable<>(this, i, arg);
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
            List<Object> l = AbstractScope.this.contents();
            l.addAll(Arrays.asList(contents));
            this.contents = l.toArray();
        }

        public WrapCallable(Callable<T> wrapped) {
            this.wrapped = wrapped;
            contents = contents().toArray(new Object[0]);
        }

        @Override
        public T call() throws Exception {
            try (QuietAutoCloseable qac = enter(contents)) {
                return wrapped.call();
            }
        }
    }

    protected abstract List<Object> contents();

    static class WrapRunnable implements Runnable {

        private final Runnable run;
        private final AbstractScope scope;
        private final Object[] scopeContents;
        private final RuntimeException t = new RuntimeException(); //XXX deleteme

        WrapRunnable(Runnable run, AbstractScope scope) {
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

        private final AbstractScope scope;
        private final Invokable<T, R, E> invokable;
        private final Object[] scopeContents;
        private final AtomicReference<T> arg;

        WrapInvokable(AbstractScope scope, Invokable<T, R, E> callable, AtomicReference<T> arg) {
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

    private final class ErrorProvider<T> implements Provider<T> {

        private final Class<T> type;

        ErrorProvider(Class<T> type) {
            this.type = type;
        }

        @Override
        public T get() {
            String typeName = type.getSimpleName();
            TypeVariable<Class<T>>[] tps = type.getTypeParameters();
            if (tps != null && tps.length > 0) {
                StringBuilder sb = new StringBuilder(typeName).append("<");
                for (TypeVariable<Class<T>> c : tps) {
                    sb.append(c);
                }
                typeName = sb.append('>').toString();
            }
            String info = injectionInfoProvider.get();
            if (inScope()) {
                Collection<?> contents = contents();
                String message = info + " in "
                        + AbstractScope.this.getClass().getSimpleName()
                        + " but no instance of " + typeName + " available. "
                        + " Scope contents: "
                        + scopeContents(contents)
                        + " Bound in scope: " + types(types) + " " + nullableTypes;
                IllegalStateException ise = new IllegalStateException(new AlignedText(message).toString());

                if (includeStackTraces) {
//                    Throwable curr = ise;
//                    for (Throwable t : lookup.lookupAll(Throwable.class)) {
//                        if (curr.getCause() == null) {
//                            curr.initCause(t);
//                        }
//                        curr = t;
//                    }
                }
                throw ise;
            } else {
                throw new IllegalStateException(info + " not in this scope, and "
                        + "no instance of " + typeName
                        + " available outside "
                        + AbstractScope.this.getClass().getSimpleName()
                        + " scope");
            }
        }
    }

    private static String types(Set<Class<?>> types) {
        List<Class<?>> l = new ArrayList<>(types);
        Collections.sort(l, (Class<?> o1, Class<?> o2) -> o1.getSimpleName().compareTo(o2.getSimpleName()));
        StringBuilder sb = new StringBuilder();
        for (Class<?> c : l) {
            sb.append("\n").append(c.getSimpleName()).append("\t").append(c.getPackage());
        }
        return sb.toString();
    }

    private static String scopeContents(Collection<?> c) {
        StringBuilder sb = new StringBuilder();
        for (Iterator<?> it = c.iterator(); it.hasNext();) {
            Object o = it.next();
            if (o == null) {
                sb.append("\n  null\t(NULL?!!!)");
            } else {
                sb.append("\n ").append(o).append("\t").append(o.getClass().getSimpleName());
            }
        }
        sb.append("\n");
        return sb.toString();
    }

    private final class ProviderOverLookup<T> implements Provider<T> {

        private final Provider<T> delegate;
        private final Class<T> type;

        ProviderOverLookup(Class<T> type, Provider<T> delegate) {
            this.type = type;
            this.delegate = delegate;
        }

        @Override
        public T get() {
            T result = AbstractScope.this.get(type);
            if (result == null) {
                result = delegate == null ? null : delegate.get();
            }
            return result;
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> Provider<T> scope(final Key<T> key, final Provider<T> unscoped) {
        return new ProviderOverLookup<T>((Class<T>) key.getTypeLiteral().getRawType(), unscoped);
    }
}
