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

import com.google.inject.Provider;
import com.mastfrog.function.misc.QuietAutoClosable;
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

    public ReentrantScope() {
        super();
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
    public ReentrantScope(Provider<String> injectionInfoProvider) {
        super(injectionInfoProvider);
    }

    Object[] includeStackTrace(Object... scopeContents) {
        if (this.includeStackTraces) {
            Object[] nue = new Object[scopeContents.length + 1];
            System.arraycopy(scopeContents, 0, nue, 0, scopeContents.length);
            nue[nue.length - 1] = new Throwable();
            return nue;
        }
        return scopeContents;
    }

    private final QuietAutoClosable qac = new NTAC(this);

    private static final class NTAC implements QuietAutoClosable {

        private final AbstractScope scope;

        public NTAC(AbstractScope scope) {
            this.scope = scope;
        }

        @Override
        public void close() {
            scope.exit();
        }

    }

    public QuietAutoClosable enter(Object... o) {
        List<Object[]> context = lists.get();
        if (context == null) {
            context = new ArrayList<>(20);
            lists.set(context);
        }
        context.add(o);
        return qac;
    }

    protected List<Object> contents() {
        List<Object> result = new ArrayList<>(40);
        List<Object[]> toSearch = lists.get();
        if (toSearch != null && !toSearch.isEmpty()) {
            for (Object[] l : toSearch) {
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
            int max = toSearch.size();
            for (int i = max - 1; i >= 0; i--) {
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
