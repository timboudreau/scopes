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

import com.mastfrog.util.thread.QuietAutoCloseable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.logging.Level;

/**
 * Base class for scopes which can be reentered multiple times and allow the
 * aggregate of their contents to be injected.
 * <p/>
 * Scopes based on this class can be entered by passing an array of objects
 * which should be injected within this scope to the run() method. If a scoping
 * block is already in progress, the objects passed take precedence over the
 * objects from the earlier entry on the current thread; however, if a type is
 * not present from the more recent entry but exists from an earlier entry, then
 * that object will be used.
 *
 * @author Tim Boudreau
 */
public class ReentrantScope extends AbstractScope {

    private final ThreadLocal<List<Object[]>> lists = new ThreadLocal<>();

    Object[] includeStackTrace(Object... scopeContents) {
        if (this.includeStackTraces) {
            Object[] nue = new Object[scopeContents.length + 1];
            System.arraycopy(scopeContents, 0, nue, 0, scopeContents.length);
            nue[nue.length - 1] = new Throwable();
            return nue;
        }
        return scopeContents;
    }

    private final QuietAutoCloseable qac = new QuietAutoCloseable() {

        @Override
        public void close() {
            exit();
        }
    };

    public QuietAutoCloseable enter(Object... o) {
        List<Object[]> context = lists.get();
        if (context == null) {
            context = new ArrayList<>();
            lists.set(context);
        }
        context.add(o);
        return qac;
    }

    protected List<Object> contents() {
        List<Object> result = new ArrayList<>();
        List<Object[]> toSearch = lists.get();
        if (toSearch != null && !toSearch.isEmpty()) {
            for (Object[] l : lists.get()) {
                result.addAll(Arrays.asList(l));
            }
        }
        return result;
    }

    @Override
    public void exit() {
        List<Object[]> l = lists.get();
        assert l != null;
        l.remove(l.size() - 1);
        if (logger.isLoggable(Level.FINEST)) {
            logger.log(Level.FINEST, "Exit {0} entry count {1}",
                    new Object[]{getClass().getSimpleName(), l.size()});
        }
        if (l.isEmpty()) {
            lists.remove();
        }
    }

    public boolean inScope() {
        List<?> l = this.lists.get();
        return l != null && !l.isEmpty();
    }

    @Override
    protected <T> T get(Class<T> type) {
        List<Object[]> toSearch = lists.get();
        if (toSearch != null && !toSearch.isEmpty()) {
            for (int i = toSearch.size() - 1; i >= 0; i--) {
                Object[] curr = toSearch.get(i);
                for (int j = curr.length - 1; j >= 0; j--) {
                    Object o = curr[j];
                    if (type.isInstance(o)) {
                        return type.cast(o);
                    }
                }
            }
        }
        return null;
    }
}
