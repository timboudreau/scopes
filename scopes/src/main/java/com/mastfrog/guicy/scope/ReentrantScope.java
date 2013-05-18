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

import java.util.*;
import java.util.logging.Level;
import org.openide.util.Lookup;
import org.openide.util.lookup.AbstractLookup;
import org.openide.util.lookup.InstanceContent;
import org.openide.util.lookup.ProxyLookup;

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

    private final ThreadLocal<LinkedList<Lookup>> stack = new ThreadLocal<>();

    Object[] includeStackTrace(Object... scopeContents) {
        if (this.includeStackTraces) {
            Object[] nue = new Object[scopeContents.length + 1];
            System.arraycopy(scopeContents, 0, nue, 0, scopeContents.length);
            nue[nue.length - 1] = new Throwable();
            return nue;
        }
        return scopeContents;
    }

    @Override
    public AutoCloseable enter(Object... scopeContents) {
        scopeContents = includeStackTrace(convertObjects(scopeContents));
        LinkedList<Lookup> lkps = stack.get();
        if (lkps == null) {
            lkps = new LinkedList<>();
            stack.set(lkps);
        }
        if (logger.isLoggable(Level.FINEST)) {
            logger.log(Level.FINEST, "Enter {0} entry count {1} with {2}",
                    new Object[]{getClass().getSimpleName(),
                        stack.get().size(), Arrays.asList(scopeContents)});
        }
        lkps.add(createLookup(scopeContents));
        return ac;
    }

    private final AC ac = new AC();

    private final class AC implements AutoCloseable {

        @Override
        public void close() throws Exception {
            exit();
        }
    }

    @Override
    public void exit() {
        LinkedList<Lookup> lkps = stack.get();
        assert lkps != null;
        Lookup formerContext = lkps.pop();
        assert formerContext != null;
        if (logger.isLoggable(Level.FINEST)) {
            logger.log(Level.FINEST, "Exit {0} entry count {1}",
                    new Object[]{getClass().getSimpleName(), stack.get().size()});
        }
        if (lkps.isEmpty()) {
            stack.remove();
        }
    }

    @Override
    public final boolean inScope() {
        return stack.get() != null;
    }

    @Override
    protected final Lookup getLookup() {
        LinkedList<Lookup> lkps = stack.get();
        if (lkps == null) {
            return Lookup.EMPTY;
        } else if (lkps.size() == 1) {
            return lkps.iterator().next();
        } else {
            List<Lookup> all = new ArrayList<>(lkps);
            Collections.reverse(lkps);
            return new ProxyLookup(all.toArray(new Lookup[all.size()]));
        }
    }
}
