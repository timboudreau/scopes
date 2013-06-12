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
import com.mastfrog.util.thread.TypedAutoCloseable;
import org.openide.util.Lookup;

/**
 * Guice scope whose contents for injection come from a Lookup passed in the
 * constructor.  The default constructor creates a scope in which objects
 * may be injected which are registered using JDK 6's ServiceLoader 
 * META-INF/services files (hint:  use &#064;ServiceProvider to register
 * objects).
 *
 * @author Tim Boudreau
 */
public class LookupScope extends AbstractScope {

    private final Lookup lkp;

    public LookupScope() {
        this(Lookup.getDefault());
    }

    public LookupScope(Lookup lkp) {
        this.lkp = lkp;
    }

    @Override
    protected QuietAutoCloseable  enter(Object... scopeContents) {
        //do nothing
        return new QuietAutoCloseable() {

            @Override
            public void close() {
                exit();
            }
            
        };
    }

    @Override
    protected void exit() {
        //do nothing
    }

    @Override
    public boolean inScope() {
        return true;
    }

    @Override
    protected Lookup getLookup() {
        return lkp;
    }
}
