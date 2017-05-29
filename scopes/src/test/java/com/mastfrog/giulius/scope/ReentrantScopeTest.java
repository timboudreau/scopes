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

import com.mastfrog.giulius.scope.AbstractScope;
import com.mastfrog.giulius.scope.ReentrantScope;
import com.mastfrog.giulius.scope.ScopeRunner;
import com.google.inject.AbstractModule;
import com.google.inject.Guice;
import com.google.inject.Inject;
import com.google.inject.Injector;
import com.mastfrog.util.Invokable;
import java.io.IOException;
import java.util.concurrent.Callable;
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