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

import com.mastfrog.giulius.scope.SingleEntryScope;
import com.google.inject.AbstractModule;
import com.google.inject.Guice;
import com.google.inject.Injector;
import com.google.inject.ProvisionException;
import com.mastfrog.util.Invokable;
import java.io.IOException;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 *
 * @author tim
 */
public class SingleEntryScopeTest {

    private static final SingleEntryScope SCOPE = new SingleEntryScope();
    final X x = new X("test");

    static final class M extends AbstractModule {

        @Override
        protected void configure() {
            SCOPE.bind(binder(), X.class);
        }
    }

    static class X {

        public String s;

        X(String s) {
            this.s = s;
        }

        public String toString() {
            return s;
        }
    }
    private Injector deps;

    @Before
    public void setUp() throws IOException {
        deps = Guice.createInjector(new M());
    }

    @Test
    public void testCall() throws Exception {
        class I extends Invokable<String, String, Exception> {

            @Override
            public String run(String argument) throws Exception {
                X x = deps.getInstance(X.class);
                assertNotNull(x);
                assertEquals("test", x.toString());
                return argument + "." + x.toString();
            }
        }
        String s = SCOPE.run(new I(), "hello", x);
        assertNotNull(s);
        assertEquals("hello." + x.toString(), s);
    }

    @Test(expected=ProvisionException.class)
    public void testFail() throws Exception {
        deps.getInstance(X.class);
    }
}
