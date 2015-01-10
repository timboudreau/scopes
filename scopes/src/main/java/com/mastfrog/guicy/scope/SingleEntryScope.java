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

import com.google.inject.Scope;
import com.mastfrog.util.thread.QuietAutoCloseable;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * Base class for ThreadLocal-based scopes with ad-hoc contents
 *
 * @author Tim Boudreau
 */
public class SingleEntryScope extends AbstractScope implements Scope {

    private final ThreadLocal<Object[]> values = new ThreadLocal<>();

    @Override
    protected List<Object> contents() {
        Object[] result = values.get();
        return result == null ? Collections.emptyList() : Arrays.asList(result);
    }

    public void run(Runnable toRun, Object... scopeContents) {
        enter(scopeContents);
        try {
            toRun.run();
        } finally {
            exit();
        }
    }

    public <T> T call(Callable<T> toCall, Object... scopeContents) throws Exception {
        enter(scopeContents);
        try {
            return toCall.call();
        } finally {
            exit();
        }
    }

    protected QuietAutoCloseable enter(Object... scopeContents) {
        if (values.get() != null) {
            throw new IllegalStateException("Already in scope " + this);
        }
        values.set(scopeContents);
        return new QuietAutoCloseable() {
            @Override
            public void close() {
                exit();
            }
        };
    }

    protected <T> T get(Class<T> type) {
        Object[] contents = values.get();
        for (int i = contents.length - 1; i >= 0; i--) {
            if (type.isInstance(contents[i])) {
                return type.cast(contents[i]);
            }
        }
        return null;
    }

    @Override
    public boolean inScope() {
        return values.get() != null;
    }

    protected void exit() {
        if (values.get() == null) {
            throw new IllegalStateException("Not in scope " + this);
        }
        values.remove();
    }
}
