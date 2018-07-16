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

import com.google.inject.Inject;
import com.google.inject.Injector;
import com.mastfrog.util.function.Invokable;
import java.util.concurrent.Callable;

/**
 * For people who are scared to touch an injector
 *
 * @author Tim Boudreau
 */
public final class ScopeRunner {

    private final Injector injector;
    private final AbstractScope scope;

    @Inject
    ScopeRunner(Injector injector, AbstractScope scope) {
        this.injector = injector;
        this.scope = scope;
    }
    
    public AbstractScope scope() {
        return scope;
    }

    public void run(Class<? extends Runnable> type) {
        if (!scope.inScope()) {
            throw new IllegalStateException ("Not in " + scope);
        }
        Runnable r = injector.getInstance(type);
        r.run();
    }

    public <T> T call(Class<? extends Callable<T>> type) throws Exception {
        if (!scope.inScope()) {
            throw new IllegalStateException ("Not in " + scope);
        }
        return injector.getInstance(type).call();
    }
    
    public <T, R, E extends Exception> R invoke (Class<? extends Invokable<T, R, E>> type, T arg) throws E {
        if (!scope.inScope()) {
            throw new IllegalStateException ("Not in " + scope);
        }
        return injector.getInstance(type).run(arg);
    }
}
