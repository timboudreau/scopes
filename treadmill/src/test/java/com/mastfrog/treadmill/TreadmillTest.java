package com.mastfrog.treadmill;

import com.google.inject.AbstractModule;
import com.google.inject.Guice;
import com.google.inject.Injector;
import com.google.inject.util.Providers;
import com.mastfrog.guicy.scope.ReentrantScope;
import com.mastfrog.treadmill.Treadmill.Deferral;
import com.mastfrog.treadmill.Treadmill.Deferral.Resumer;
import java.lang.Thread.UncaughtExceptionHandler;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.junit.Test;
import static org.junit.Assert.*;

/**
 *
 * @author Tim Boudreau
 */
public class TreadmillTest {

    @Test
    public void testDeferral() throws InterruptedException {
        ExecutorService svc = Executors.newCachedThreadPool();
        final Set<Throwable> fails = new HashSet<>();
        UncaughtExceptionHandler h = new UncaughtExceptionHandler() {
            @Override
            public void uncaughtException(Thread thread, Throwable thrwbl) {
                fails.add(thrwbl);
            }
        };
        Injector i = Guice.createInjector(new M1());
        ReentrantScope scope = i.getInstance(ReentrantScope.class);
        List<IntCall> all = new ArrayList<>();
        for (int j = 0; j < 10; j++) {
            all.add(new IntCall(scope, j));
        }
        Iterator<? extends Callable<Object[]>> iter = all.iterator();
        Treadmill t = new Treadmill(svc, scope, iter, h);
        StringBuffer sb = new StringBuffer();
        CountDownLatch latch = t.start(sb);
        latch.await(20, TimeUnit.SECONDS);
        if (!fails.isEmpty()) {
            AssertionError err = new AssertionError("Exceptions thrown while running", fails.iterator().next());
            for (Throwable th : fails) {
                th.printStackTrace();
                err.addSuppressed(th);
            }
            throw err;
        }
        System.out.println("SB: " + sb);
        for (IntCall c : all) {
            assertTrue(c.val + " did not run", c.ran);
        }
        assertEquals("0123456789", sb.toString());
    }

    static class IntCall implements Callable<Object[]> {

        private final ReentrantScope scope;
        private final Integer val;
        private volatile boolean ran;

        public IntCall(ReentrantScope scope, Integer val) {
            this.scope = scope;
            this.val = val;
        }

        @Override
        public Object[] call() throws Exception {
            assertFalse(ran);
            ran = true;
            StringBuffer sb = scope.provider(StringBuffer.class, Providers.<StringBuffer>of(null)).get();
            assertNotNull(sb);
            sb.append(val);
            Integer prev = scope.provider(Integer.class, Providers.<Integer>of(null)).get();
            if (val != 0) {
                assertNotNull(prev);
                assertEquals(val - 1, prev.intValue());
            } else {
                assertNull(prev);
            }
            Deferral def = scope.provider(Deferral.class, Providers.<Deferral>of(null)).get();
            assertNotNull(def);
            if (val == 7) {
                Resumer rest = def.defer();
                ResumeIt resume = new ResumeIt(rest);
                new Thread(resume).start();
            }
            return new Object[]{val};
        }
    }

    private static class ResumeIt implements Runnable {

        private final Treadmill.Deferral.Resumer restart;

        ResumeIt(Treadmill.Deferral.Resumer restart) {
            assertNotNull(restart);
            this.restart = restart;
        }

        @Override
        public void run() {
            try {
                Thread.sleep(2000);
            } catch (InterruptedException ex) {
                Logger.getLogger(TreadmillTest.class.getName()).log(Level.SEVERE, null, ex);
            }
            this.restart.resume(23.5F);
        }
    }

    static class M1 extends AbstractModule {

        @Override
        protected void configure() {
            ReentrantScope scope = new ReentrantScope();
            scope.bindTypes(binder(), Integer.class, StringBuffer.class, Float.class, Treadmill.Deferral.class);
            bind(ReentrantScope.class).toInstance(scope);
        }
    }

    @Test
    public void testStart() throws Exception {
        if (true) {
            return;
        }
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
            return new Object[]{23};
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
