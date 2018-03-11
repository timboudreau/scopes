/*
 * The MIT License
 *
 * Copyright 2018 Tim Boudreau.
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
import com.google.inject.Key;
import com.google.inject.Provider;
import com.google.inject.util.Providers;
import com.mastfrog.util.thread.FactoryThreadLocal;
import com.mastfrog.util.thread.NonThrowingAutoCloseable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 *
 * @author Tim Boudreau
 */
public class ReentrantScope3 extends AbstractScope {

    private final Map<Key<?>, ScopedProvider<?>> bindings = new HashMap<>();
    private final Map<Class<?>, ScopedProvider<?>> typeMap = new HashMap<>();
    private final FactoryThreadLocal<LinkedList<EntryHandle>> entries = new FactoryThreadLocal<>(ReentrantScope3::newLinkedList);

    public ReentrantScope3() {
    }

    public ReentrantScope3(Provider<String> injectionInfoProvider) {
        super(injectionInfoProvider);
    }

    protected <T> void bindInScopeAllowingNulls(Binder binder, Class<T> type) {
        Key<T> key = Key.get(type);
        Provider<T> lkpProvider = scope(key, Providers.of(null));
        binder.bind(key).toProvider(lkpProvider);
    }

    protected <T> void bindInScope(Binder binder, Class<T> type) {
        Key<T> key = Key.get(type);
        Provider<T> lkpProvider = scope(key, Providers.of(null));
        binder.bind(key).toProvider(lkpProvider);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> Provider<T> scope(Key<T> key, Provider<T> unscoped) {
        ScopedProvider<?> prov = bindings.get(key);
        if (prov == null) {
            prov = new ScopedProvider<>(key, this, unscoped);
            bindings.put(key, prov);
            typeMap.put(key.getTypeLiteral().getRawType(), prov);
        }
        return (Provider<T>) prov;
    }

    @Override
    protected NonThrowingAutoCloseable enter(Object... scopeContents) {
        EntryHandle handle = new EntryHandle(this, this.entries.get().peek());

        int handledCount = 0;
        boolean[] handled = new boolean[scopeContents.length];
        for (int i = 0; i < scopeContents.length; i++) {
            Object o = scopeContents[i];
            ScopedProvider<?> prov = typeMap.get(o.getClass());
            if (prov != null) {
                handled[i] = true;
                prov.unsafeEnter(o);
                handle.add(prov, o);
                handledCount++;
            }
        }
        if (handledCount < scopeContents.length) {
            for (Map.Entry<Key<?>, ScopedProvider<?>> e : bindings.entrySet()) {
                for (int i = 0; i < scopeContents.length; i++) {
                    if (handled[i]) {
                        continue;
                    }
                    Object o = scopeContents[i];
                    if (e.getValue().maybeEnter(o)) {
                        handle.add(e.getValue(), o);
                    }
                }
            }
        }
        this.entries.get().push(handle);
        return handle;
    }

    @Override
    protected void exit() {
        EntryHandle handle = entries.get().peek();
        handle.close();
    }

    @Override
    @SuppressWarnings("unchecked")
    protected <T> T get(Class<T> type) {
        ScopedProvider<?> p = typeMap.get(type);
        if (p != null) {
            T result = (T) p.peek();
            if (result != null) {
                return result;
            }
        }
        LinkedList<EntryHandle> handles = entries.get();
        if (handles.isEmpty()) {
            return null;
        }
        EntryHandle handle = handles.peek();
        if (handle.mayContain(type)) {
            for (Map.Entry<Key<?>, ScopedProvider<?>> e : bindings.entrySet()) {
                if (type.isAssignableFrom(e.getValue().key.getTypeLiteral().getRawType())) {
                    T result = (T) e.getValue().peek();
                    return result;
                }
            }
        }
        return null;
    }

    @Override
    public boolean inScope() {
        return !this.entries.get().isEmpty();
    }

    @Override
    protected List<Object> contents() {
        List<Object> l = new ArrayList<>(bindings.size());
        for (ScopedProvider<?> prov : bindings.values()) {
            Object o = prov.peek();
            if (o != null) {
                l.add(o);
            }
        }
        return l;
    }

    private static final class ScopedProvider<T> implements Provider<T> {

        private final Key<T> key;
        private final FactoryThreadLocal<LinkedList<T>> objsForThread = new FactoryThreadLocal<>(ReentrantScope3::newLinkedList);
        private final ReentrantScope3 scope;
        private final Provider<T> unscoped;

        ScopedProvider(Key<T> key, ReentrantScope3 scope, Provider<T> unscoped) {
            this.key = key;
            this.scope = scope;
            this.unscoped = unscoped;
        }

        T peek() {
            LinkedList<T> list = objsForThread.get();
            return list.isEmpty() ? null : list.peek();
        }

        @SuppressWarnings("unchecked")
        void unsafeEnter(Object o) {
            objsForThread.get().push((T) o);
        }

        @SuppressWarnings("unchecked")
        boolean maybeEnter(Object o) {
            Class<T> type = (Class<T>) key.getTypeLiteral().getRawType();
            boolean matches = type.isInstance(o);
            if (matches) {
                objsForThread.get().push(type.cast(o));
            }
            return matches;
        }

        void onExit() {
            objsForThread.get().pop();
        }

        @Override
        @SuppressWarnings("unchecked")
        public T get() {
            LinkedList<T> l = objsForThread.get();
            if (l.isEmpty()) {
                T obj = (T) scope.get(key.getTypeLiteral().getRawType());
                if (obj != null) {
                    return obj;
                }
            }
            if (l.isEmpty()) {
                return unscoped.get();
            }
            return l.peek();
        }
    }

    static <T> LinkedList<T> newLinkedList() {
        return new LinkedList<>();
    }

    static final Map<Class<?>, Set<Class<?>>> allTypes = new ConcurrentHashMap<>();

    private static final class EntryHandle implements NonThrowingAutoCloseable {

        private final List<ScopedProvider<?>> popOnExit = new LinkedList<>();
        private final ReentrantScope3 scope;
        private final Set<Class<?>> containedTypes = new HashSet<>();

        public EntryHandle(ReentrantScope3 scope, EntryHandle parent) {
            this.scope = scope;
            if (parent != null) {
                containedTypes.addAll(parent.containedTypes);
            }
        }

        boolean mayContain(Class<?> type) {
            return containedTypes.contains(type);
        }

        EntryHandle add(ScopedProvider<?> prov, Object o) {
            popOnExit.add(prov);
            Set<Class<?>> types = allTypes.get(o.getClass());
            if (types == null) {
                types = com.mastfrog.util.Types.get(o.getClass());
                allTypes.put(o.getClass(), types);
            }
            containedTypes.addAll(types);
            return this;
        }

        @Override
        public void close() {
            for (ScopedProvider<?> sp : popOnExit) {
                sp.onExit();
            }
            scope.entries.get().pop();
        }
    }
}
