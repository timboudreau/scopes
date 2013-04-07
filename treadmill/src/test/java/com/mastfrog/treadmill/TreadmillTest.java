package com.mastfrog.treadmill;

import com.google.inject.AbstractModule;
import com.google.inject.Guice;
import com.google.inject.Injector;
import com.mastfrog.guicy.scope.ReentrantScope;
import java.lang.Thread.UncaughtExceptionHandler;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Test;
import static org.junit.Assert.*;

/**
 *
 * @author Tim Boudreau
 */
public class TreadmillTest {

    @Test
    public void testStart() throws Exception {
        ExecutorService svc = Executors.newCachedThreadPool();
        final Set<Throwable> fails = new HashSet<>();
        UncaughtExceptionHandler h = new UncaughtExceptionHandler() {
            @Override
            public void uncaughtException(Thread thread, Throwable thrwbl) {
                fails.add(thrwbl);
            }
        };
        Injector i = Guice.createInjector(new M());
        ReentrantScope scope = i.getInstance(ReentrantScope.class);
        Iterator<Callable<Object[]>> all = Arrays.<Callable<Object[]>>asList(
                new One(i), new Two(i), new Three(i), new Four(i), new Five()).iterator();
        Treadmill t = new Treadmill(svc, scope, all, h);
        DoneChecker checker = new DoneChecker();
        CountDownLatch latch = t.start(checker, new A());
        latch.await(10, TimeUnit.SECONDS);
        if (!fails.isEmpty()) {
            AssertionError err = new AssertionError("Exceptions thrown while running");
            for (Throwable th : fails) {
                th.printStackTrace();
                err.addSuppressed(th);
            }
            throw err;
        }
        assertTrue(oneRan);
        assertTrue(twoRan);
        assertTrue(threeRan);
        assertTrue(fourRan);
        assertFalse(fiveRan);
        assertTrue(checker.done.get());
    }
    
    private static final class DoneChecker implements Runnable {
        private final AtomicBoolean done = new AtomicBoolean();

        @Override
        public void run() {
            System.out.println("all done");
            done.set(true);
        }
    }

    static boolean oneRan;
    static boolean twoRan;
    static boolean threeRan;
    static boolean fourRan;
    static boolean fiveRan;
    private static final class One implements Callable<Object[]> {
        private final Injector injector;

        public One(Injector injector) {
            this.injector = injector;
        }

        @Override
        public Object[] call() throws Exception {
            oneRan = true;
            System.out.println(Thread.currentThread());
            assertNotNull(injector.getInstance(A.class));
            return new Object[]{"first"};
        }
    }
    
    private static final class Two implements Callable<Object[]> {
        private final Injector injector;

        public Two(Injector injector) {
            this.injector = injector;
        }

        @Override
        public Object[] call() throws Exception {
            twoRan = true;
            System.out.println(Thread.currentThread());
            assertNotNull(injector.getInstance(A.class));
            assertEquals("first", injector.getInstance(String.class));
            return new Object[]{new StringThing("second"), "not first"};
        }
    }
    
    private static final class Three implements Callable<Object[]> {
        private final Injector injector;

        public Three(Injector injector) {
            this.injector = injector;
        }

        @Override
        public Object[] call() throws Exception {
            threeRan = true;
            System.out.println(Thread.currentThread());
            assertNotNull(injector.getInstance(A.class));
            assertEquals("not first", injector.getInstance(String.class));
            assertEquals(new StringThing("second"), injector.getInstance(StringThing.class));
            return new Object[]{ 23 };
        }
    }

    private static final class Four implements Callable<Object[]> {
        private final Injector injector;

        public Four(Injector injector) {
            this.injector = injector;
        }

        @Override
        public Object[] call() throws Exception {
            fourRan = true;
            System.out.println(Thread.currentThread());
            assertNotNull(injector.getInstance(A.class));
            assertEquals("not first", injector.getInstance(String.class));
            assertEquals(new StringThing("second"), injector.getInstance(StringThing.class));
            assertEquals(Integer.valueOf(23), injector.getInstance(Integer.class));
            throw new Abort();
        }
    }
    
    private static final class Five implements Callable<Object[]> {

        @Override
        public Object[] call() throws Exception {
            fiveRan = true;
            throw new AssertionError("Should not get here");
        }
    }
    

    static class M extends AbstractModule {

        @Override
        protected void configure() {
            ReentrantScope scope = new ReentrantScope();
            scope.bindTypes(binder(), String.class, StringThing.class, Integer.class, Float.class, Boolean.class, A.class);
            bind(ReentrantScope.class).toInstance(scope);
        }
    }
    
    private static class A {
        
    }
    
    private static final class StringThing {
        private final String string;

        public StringThing(String string) {
            this.string = string;
        }
        
        public String toString() {
            return string;
        }

        @Override
        public int hashCode() {
            int hash = 7;
            hash = 23 * hash + Objects.hashCode(this.string);
            return hash;
        }

        @Override
        public boolean equals(Object obj) {
            if (obj == null) {
                return false;
            }
            if (getClass() != obj.getClass()) {
                return false;
            }
            final StringThing other = (StringThing) obj;
            if (!Objects.equals(this.string, other.string)) {
                return false;
            }
            return true;
        }
        
    }
}