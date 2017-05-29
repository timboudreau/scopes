/*
 *               BSD LICENSE NOTICE
 * Copyright (c) 2010-2012, Tim Boudreau
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met: 
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer. 
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution. 
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package com.mastfrog.giulius.scope;

import com.mastfrog.giulius.scope.ReentrantScope;
import com.mastfrog.giulius.scope.ReentrantScope2;
import com.google.inject.AbstractModule;
import com.google.inject.Guice;
import com.google.inject.Inject;
import com.google.inject.Injector;
import com.google.inject.Key;
import com.google.inject.Provider;
import com.google.inject.Scope;
import com.google.inject.Stage;
import com.google.inject.util.Providers;
import com.mastfrog.giulius.scope.ReentrantScope2.ScopeRunner2;
import com.mastfrog.util.Invokable;
import com.mastfrog.util.thread.QuietAutoCloseable;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Date;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.Callable;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;

public class ReentrantScope2Test {
    private Injector dependencies;

    @Before
    public void setUp() throws IOException {
        dependencies = Guice.createInjector(new ScopesModule());
    }

    @Test
    public void testInScope() {
        final int limit = 10;
        StringBuilder constant = new StringBuilder("hello");
        final boolean[] ran = new boolean[1];
        //Recursively enter the scope multiple times, and ensure that the
        //most recent values are the ones which are available
        class One extends Invokable<Integer, Void, RuntimeException> {

            @Override
            public Void run(Integer argument) throws RuntimeException {
                ran[0] = true;
                assertNotNull(dependencies.getInstance(Integer.class));
                assertEquals(argument, dependencies.getInstance(Integer.class));
                assertNotNull(dependencies.getInstance(String.class));
                assertEquals(argument.toString(), dependencies.getInstance(String.class));
                assertNotNull(dependencies.getInstance(StringBuilder.class));
                assertEquals("hello", dependencies.getInstance(StringBuilder.class).toString());
                int next = argument + 1;
                if (next < limit) {
                    dependencies.getInstance(ReentrantScope2.class)
                            .run(this, next, Integer.valueOf(next), Integer.toString(next));
                }
                return null;
            }
        }
        dependencies.getInstance(ReentrantScope2.class).run(new One(), 1, constant, Integer.valueOf(1), Integer.toString(1));
        assertTrue("Invokable was not run", ran[0]);
    }

    @Test
    public void testMultipleReentry() {
        ReentrantScope2 scope = dependencies.getInstance(ReentrantScope2.class);
        assertNull(scope.lookup(String.class, false));
        try (QuietAutoCloseable c = scope.enter("Hello")) {
            assertEquals("Hello", scope.lookup(String.class, false));
            try (QuietAutoCloseable c1 = scope.enter("World")) {
                assertEquals("World", scope.lookup(String.class, false));
            }
        }
    }

    @Test
    public void testScopeRunner2() throws Exception {
        ScopesModule module = new ScopesModule();
        Injector inj = Guice.createInjector(module);

        // We will inject this StringBuilder into an instance of C below
        StringBuilder constant = new StringBuilder("hello");

        // ScopeRunner2 wraps the Injector and lets us instantiate C and get it
        // injected with objects only available in the scope
        ScopeRunner2 r = inj.getInstance(ScopeRunner2.class);
        ReentrantScope2 scope = r.scope();

        try (AutoCloseable cl = scope.enter(constant)) {
            assertTrue(scope.inScope());
            StringBuilder sb = r.call(C.class);
            assertNotNull(sb);
            assertSame(constant, sb);
            assertEquals("hello", sb.toString());
        }

        assertFalse(scope.inScope());
        assertSame(scope, r.scope());
        try {
            r.call(C.class);
            fail("ISE should have been thrown");
        } catch (IllegalStateException ex) {
        }
    }
    @Test
    public void testBenchmark2() {
        Injector deps2 = Guice.createInjector(Stage.PRODUCTION, new MicrobenchmarkModule2());
        ReentrantScope2 newScope = deps2.getInstance(ReentrantScope2.class);
        benchmark(newScope);
        benchmark(newScope);
        benchmark(newScope);
        benchmark(newScope);
        benchmark(newScope);
    }

    @Test
    public void testBenchmark() {
        Injector deps = Guice.createInjector(Stage.PRODUCTION, new MicrobenchmarkModule());
        ReentrantScope oldScope = deps.getInstance(ReentrantScope.class);
        benchmark(oldScope);
        benchmark(oldScope);
        benchmark(oldScope);
        benchmark(oldScope);
        benchmark(oldScope);
    }

    private static final Object[] contents1 = new Object[]{"one", new StringBuilder("stuff"), 23F};
    private static final Object[] contents2 = new Object[]{"two", 15D, new BigDecimal("23.0001")};
    private static final Object[] contents3 = new Object[]{"three", new HashSet<>(Arrays.asList("foo", "bar")), true};
    private static final Object[] contents4 = new Object[]{new StringBuilder("more"), 24F, new Date()};

    @SuppressWarnings("unchecked")
    public void benchmark(Scope scope) {
        Provider<?>[] ps = new Provider<?>[]{
            scope.scope((Key) Key.get(String.class), Providers.of(null)),
            scope.scope((Key) Key.get(StringBuilder.class), Providers.of(null)),
            scope.scope((Key) Key.get(Float.class), Providers.of(null)),
            scope.scope((Key) Key.get(Double.class), Providers.of(null)),
            scope.scope((Key) Key.get(BigDecimal.class), Providers.of(null)),
            scope.scope((Key) Key.get(Set.class), Providers.of(null)),
            scope.scope((Key) Key.get(Boolean.class), Providers.of(null)),
            scope.scope((Key) Key.get(Date.class), Providers.of(null)),};
        long start = System.currentTimeMillis();
        Object o;
        for (int i = 0; i < 3600; i++) {
            try (QuietAutoCloseable cl = scope instanceof ReentrantScope2 ? ((ReentrantScope2) scope).enter(contents1) : ((ReentrantScope) scope).enter(contents1)) {
                for (Provider<?> p : ps) {
                    o = p.get();
                }
                try (QuietAutoCloseable cl1 = scope instanceof ReentrantScope2 ? ((ReentrantScope2) scope).enter(contents2) : ((ReentrantScope) scope).enter(contents2)) {
                    for (Provider<?> p : ps) {
                        o = p.get();
                    }
                    try (QuietAutoCloseable cl2 = scope instanceof ReentrantScope2 ? ((ReentrantScope2) scope).enter(contents3) : ((ReentrantScope) scope).enter(contents3)) {
                        for (Provider<?> p : ps) {
                            o = p.get();
                        }
                        try (QuietAutoCloseable cl3 = scope instanceof ReentrantScope2 ? ((ReentrantScope2) scope).enter(contents4) : ((ReentrantScope) scope).enter(contents4)) {
                            for (Provider<?> p : ps) {
                                o = p.get();
                            }
                        }
                    }
                }
            }
        }
        long end = System.currentTimeMillis();
        System.out.println("Benchmark " + scope.getClass().getSimpleName() + ": " + (end - start));
    }

    static class MicrobenchmarkModule extends AbstractModule {

        ReentrantScope scope = new ReentrantScope();

        @Override
        protected void configure() {
            Class<?>[] types = new Class<?>[]{String.class, StringBuilder.class, Float.class, Double.class, BigDecimal.class, Set.class, Boolean.class, Date.class};
            scope.bindTypes(binder(), types);
            bind(ReentrantScope.class).toInstance(scope);
        }

    }

    static class MicrobenchmarkModule2 extends AbstractModule {

        ReentrantScope2 scope2 = new ReentrantScope2();

        @Override
        protected void configure() {
            Class<?>[] types = new Class<?>[]{String.class, StringBuilder.class, Float.class, Double.class, BigDecimal.class, Set.class, Boolean.class, Date.class};
            scope2.bindTypes(binder(), types);
            bind(ReentrantScope2.class).toInstance(scope2);
        }

    }

    static class C implements Callable<StringBuilder> {

        private final StringBuilder sb;

        @Inject
        C(StringBuilder sb) {
            this.sb = sb;
        }

        @Override
        public StringBuilder call() throws Exception {
            return sb;
        }
    }

    private static final class ScopesModule extends AbstractModule {

        @Override
        protected void configure() {
            ReentrantScope2 scope = new ReentrantScope2();
            bind(ReentrantScope2.class).toInstance(scope);
            scope.bind(binder(), String.class, StringBuilder.class, Integer.class);
        }
    }
}
