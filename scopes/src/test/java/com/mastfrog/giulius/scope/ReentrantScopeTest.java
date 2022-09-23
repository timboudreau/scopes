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

import com.google.inject.AbstractModule;
import com.google.inject.Guice;
import com.google.inject.Inject;
import com.google.inject.Injector;
import com.google.inject.Provider;
import com.mastfrog.function.state.Bool;
import com.mastfrog.function.throwing.ThrowingFunction;
import com.mastfrog.util.thread.QuietAutoCloseable;
import java.io.IOException;
import java.util.concurrent.Callable;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;

public class ReentrantScopeTest {

    private Injector dependencies;

    @Before
    public void setUp() throws IOException {
        dependencies = Guice.createInjector(new ScopesModule());
    }

    @Test
    public void testInScope() throws Exception {
        final int limit = 10;
        StringBuilder constant = new StringBuilder("hello");
        final boolean[] ran = new boolean[1];
        //Recursively enter the scope multiple times, and ensure that the
        //most recent values are the ones which are available
        class One implements ThrowingFunction<Integer, Void> {

            @Override
            public Void apply(Integer argument) throws Exception {
                ran[0] = true;
                assertNotNull(dependencies.getInstance(Integer.class));
                assertEquals(argument, dependencies.getInstance(Integer.class));
                assertNotNull(dependencies.getInstance(String.class));
                assertEquals(argument.toString(), dependencies.getInstance(String.class));
                assertNotNull(dependencies.getInstance(StringBuilder.class));
                assertEquals("hello", dependencies.getInstance(StringBuilder.class).toString());
                int next = argument + 1;
                if (next < limit) {
                    dependencies.getInstance(AbstractScope.class)
                            .run(this, next, Integer.valueOf(next), Integer.toString(next));
                }
                return null;
            }
        }
        assertTrue(dependencies.getInstance(AbstractScope.class) instanceof ReentrantScope);
        dependencies.getInstance(AbstractScope.class).run(new One(), 1, constant, Integer.valueOf(1), Integer.toString(1));
        assertTrue("Invokable was not run", ran[0]);
    }

    @Test
    public void testScopeRunner() throws Exception {
        ScopesModule module = new ScopesModule();
        Injector inj = Guice.createInjector(module);

        // We will inject this StringBuilder into an instance of C below
        StringBuilder constant = new StringBuilder("hello");

        // ScopeRunner wraps the Injector and lets us instantiate C and get it
        // injected with objects only available in the scope
        ScopeRunner r = inj.getInstance(ScopeRunner.class);
        AbstractScope scope = r.scope();

        try ( AutoCloseable cl = scope.enter(constant)) {
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
    public void testWrappedConsumer() {
        ReentrantScope re = new ReentrantScope();

        Provider<Integer> ints = re.provider(Integer.class, () -> null);
        Provider<StringBuilder> sbs = re.provider(StringBuilder.class, () -> null);
        Provider<String> str = re.provider(String.class, () -> null);

        Bool consumerRan = Bool.create();
        Bool biConsumerRan = Bool.create();

        Consumer<String> vc = v -> {
            consumerRan.set(true);
            assertEquals("Argument not propagated properly: " + v, "foo", v);
            assertNotNull(ints.get());
            assertNotNull(sbs.get());
            assertNotNull(str.get());
        };
        BiConsumer<String, String> bc = (a, b) -> {
            assertEquals("First argument not propagated properly: " + a, "bar", a);
            assertEquals("Second argument not propagated properly: " + b, "baz", b);
            biConsumerRan.set(true);
            assertNotNull(ints.get());
            assertNotNull(sbs.get());
            assertNotNull(str.get());
        };

        assertSame("No need to wrap if not in scope", vc, re.wrap(vc));
        assertSame("No need to wrap if not in scope", bc, re.wrap(bc));

        Consumer<String> wrappedConsumer;
        BiConsumer<String, String> wrappedBiConsumer;

        try ( QuietAutoCloseable qac = re.enter(23, new StringBuilder("Moo"), "Hoo")) {
            assertNotNull(qac);
            wrappedConsumer = re.wrap(vc);
            assertNotSame("Wrap returned same", vc, wrappedConsumer);
            wrappedBiConsumer = re.wrap(bc);
            assertNotSame("Wrap returned same", wrappedBiConsumer, bc);
        }

        wrappedConsumer.accept("foo");
        wrappedBiConsumer.accept("bar", "baz");

        assertTrue("Wrapped Consumer did not run", consumerRan.getAsBoolean());
        assertTrue("Wrapped BiConsumer did not run", biConsumerRan.getAsBoolean());
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
            ReentrantScope scope = new ReentrantScope();
            bind(AbstractScope.class).toInstance(scope);
            scope.bind(binder(), String.class, StringBuilder.class, Integer.class);
        }
    }
}
