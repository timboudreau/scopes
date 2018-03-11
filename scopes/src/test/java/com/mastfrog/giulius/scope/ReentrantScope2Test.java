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
import com.google.inject.Stage;
import com.google.inject.util.Providers;
import com.mastfrog.util.Invokable;
import static com.mastfrog.util.collections.CollectionUtils.setOf;
import com.mastfrog.util.thread.NonThrowingAutoCloseable;
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
        try (NonThrowingAutoCloseable c = scope.enter("Hello")) {
            assertEquals("Hello", scope.lookup(String.class, false));
            try (NonThrowingAutoCloseable c1 = scope.enter("World")) {
                assertEquals("World", scope.lookup(String.class, false));
            }
        }
    }

    @Test
    public void testMultipleReentry2() {
        Injector deps = Guice.createInjector(new ScopesModule3());
        ReentrantScope3 scope = deps.getInstance(ReentrantScope3.class);
        Provider<String> prov = deps.getProvider(String.class);
        Provider<CharSequence> seq = deps.getProvider(CharSequence.class);
        try (NonThrowingAutoCloseable c = scope.enter("Hello")) {
            assertEquals("Hello", prov.get());
            assertEquals("Hello", seq.get());
            try (NonThrowingAutoCloseable c1 = scope.enter("World")) {
                assertEquals("World", prov.get());
                assertEquals("World", seq.get());
            }
        }
    }


//    @Test
//    public void testScopeRunner2() throws Exception {
//        ScopesModule module = new ScopesModule();
//        Injector inj = Guice.createInjector(module);
//
//        // We will inject this StringBuilder into an instance of C below
//        StringBuilder constant = new StringBuilder("hello");
//
//        // ScopeRunner2 wraps the Injector and lets us instantiate C and get it
//        // injected with objects only available in the scope
//        ScopeRunner r = inj.getInstance(ScopeRunner.class);
//        ReentrantScope2 scope = r.scope();
//
//        try (AutoCloseable cl = scope.enter(constant)) {
//            assertTrue(scope.inScope());
//            StringBuilder sb = r.call(C.class);
//            assertNotNull(sb);
//            assertSame(constant, sb);
//            assertEquals("hello", sb.toString());
//        }
//
//        assertFalse(scope.inScope());
//        assertSame(scope, r.scope());
//        try {
//            r.call(C.class);
//            fail("ISE should have been thrown");
//        } catch (IllegalStateException ex) {
//        }
//    }

    
  @Test
    public void benchmark() {
        if (true) {
            // These are slow and just for comparing performance
//            return;
        }
        int itersA = 0, itersB = 0, itersC = 0;
        Injector deps = Guice.createInjector(Stage.PRODUCTION, new MicrobenchmarkModule());
        AbstractScope scope = deps.getInstance(ReentrantScope.class);
        benchmark(scope);
        benchmark(scope);
        itersA += benchmark(scope);
        itersA += benchmark(scope);
        itersA += benchmark(scope);
        deps = Guice.createInjector(Stage.PRODUCTION, new MicrobenchmarkModule2());
         scope = deps.getInstance(ReentrantScope2.class);
        benchmark(scope);
        benchmark(scope);
        itersB += benchmark(scope);
        itersB +=benchmark(scope);
        itersB +=benchmark(scope);
        deps = Guice.createInjector(Stage.PRODUCTION, new MicrobenchmarkModule3());
        scope = deps.getInstance(ReentrantScope3.class);
        benchmark(scope);
        benchmark(scope);
        itersC +=benchmark(scope);
        itersC +=benchmark(scope);
        itersC +=benchmark(scope);

        System.out.println("\n ReentrantScope1: " + (itersA / 3));
        System.out.println("\n ReentrantScope2: " + (itersB / 3));
        System.out.println("\n ReentrantScope3: " + (itersC / 3));
    }


    private static final Object[] contents1 = new Object[]{"one", new StringBuilder("stuff"), 23F};
    private static final Object[] contents2 = new Object[]{"two", 15D, new BigDecimal("23.0001")};
    private static final Object[] contents3 = new Object[]{"three", new HashSet<>(Arrays.asList("foo", "bar")), true};
    private static final Object[] contents4 = new Object[]{"four", new StringBuilder("more"), 24F, new Date()};
    private static final Object[] contents5 = new Object[]{"five", setOf("foo", "bar"), 14F, new Date(15)};
    private static final Object[] contents6 = new Object[]{"five", "six", "seven", "eight"};

    @SuppressWarnings("unchecked")
    public int benchmark(AbstractScope scope) {
        Provider<String> sp;
        Provider<CharSequence> seq;
        Provider<?>[] ps = new Provider<?>[]{
            sp = scope.scope((Key) Key.get(String.class), Providers.of(null)),
            scope.scope((Key) Key.get(StringBuilder.class), Providers.of(null)),
            scope.scope((Key) Key.get(Float.class), Providers.of(null)),
            scope.scope((Key) Key.get(Double.class), Providers.of(null)),
            scope.scope((Key) Key.get(BigDecimal.class), Providers.of(null)),
            scope.scope((Key) Key.get(Set.class), Providers.of(null)),
            scope.scope((Key) Key.get(Boolean.class), Providers.of(null)),
            scope.scope((Key) Key.get(Date.class), Providers.of(null)),
            seq = scope.scope((Key) Key.get(CharSequence.class), Providers.of(null)),
        };
//        long start = System.currentTimeMillis();
        long end = System.currentTimeMillis() + 5000;
        int iterations = 0;
        Object o;
        String nm = scope.getClass().getSimpleName();
        for (;; iterations++) {
            assertFalse(scope.inScope());
            try (NonThrowingAutoCloseable cl = scope.enter(contents1)) {
                assertTrue(scope.inScope());
                for (Provider<?> p : ps) {
                    o = p.get();
                }
                assertEquals(nm, "one", sp.get());
                try (NonThrowingAutoCloseable cl1 = scope.enter(contents2)) {
                    for (Provider<?> p : ps) {
                        o = p.get();
                    }
                    assertEquals(nm, "two", sp.get());
                    try (NonThrowingAutoCloseable cl2 = scope.enter(contents3)) {
                        for (Provider<?> p : ps) {
                            o = p.get();
                        }
                        assertEquals(nm, "three", sp.get());
                        try (NonThrowingAutoCloseable cl3 = scope.enter(contents4)) {
                            for (Provider<?> p : ps) {
                                o = p.get();
                            }
                            assertEquals(nm, "four", sp.get());
                            try (NonThrowingAutoCloseable cl5 = scope.enter(contents5)) {
                                for (Provider<?> p : ps) {
                                    o = p.get();
                                }
                                assertEquals(nm, "five", sp.get());
                                try (NonThrowingAutoCloseable cl6 = scope.enter(contents6)) {
                                    for (Provider<?> p : ps) {
                                        o = p.get();
                                    }
                                    assertEquals(nm, "eight", sp.get());
                                    assertNotNull(nm, seq.get());
//                                    assertEquals(nm, "eight", seq.get());
                                }
                            }
                            assertEquals(nm, "four", sp.get());
                        }
                        assertEquals(nm, "three", sp.get());
                    }
                    assertEquals(nm, "two", sp.get());
                }
                assertEquals(nm, "one", sp.get());
                assertTrue(scope.inScope());
//                for (Object ob : contents1) {
//                    if (!(scope instanceof ReentrantScope2)) {
//                        assertEquals("Looking for " + ob + " in " + scope.getClass().getSimpleName(), ob, scope.get(ob.getClass()));
//                    }
//                }
            }
            if (System.currentTimeMillis() >= end) {
                break;
            }
        }
        System.out.println("Benchmark " + scope.getClass().getSimpleName() + ": " + iterations);
        return iterations;
    }

    static Class<?>[] types = new Class<?>[]{String.class, StringBuilder.class, Float.class, Double.class, BigDecimal.class, Set.class, Boolean.class, Date.class, CharSequence.class};
    static class MicrobenchmarkModule extends AbstractModule {

        ReentrantScope scope = new ReentrantScope();

        @Override
        protected void configure() {
            scope.bindTypes(binder(), types);
            bind(ReentrantScope.class).toInstance(scope);
        }
    }

    static class MicrobenchmarkModule2 extends AbstractModule {

        ReentrantScope2 scope2 = new ReentrantScope2();

        @Override
        protected void configure() {
            scope2.bindTypes(binder(), types);
            bind(ReentrantScope2.class).toInstance(scope2);
        }
    }

    static class MicrobenchmarkModule3 extends AbstractModule {

        private ReentrantScope3 scope3 = new ReentrantScope3();

        @Override
        protected void configure() {
            scope3.bindTypes(binder(), types);
            bind(ReentrantScope3.class).toInstance(scope3);
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
            scope.bind(binder(), String.class, StringBuilder.class, Integer.class, CharSequence.class);
        }
    }

    private static final class ScopesModule3 extends AbstractModule {

        @Override
        protected void configure() {
            ReentrantScope3 scope3 = new ReentrantScope3();
            bind(ReentrantScope3.class).toInstance(scope3);
            scope3.bindAllowingNulls(binder(), String.class, StringBuilder.class, Integer.class, CharSequence.class);
        }
    }

}
