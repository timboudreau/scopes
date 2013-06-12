Scopes
======

Easy-to-use custom Guice scopes - reentrant and not.  Bits available in <a href="http://timboudreau.com/builds/">this Maven repository/continuous-build</a>.

Usage
-----

It is not uncommon, using Guice, to want to be able to provide some dynamically created objects for injection into other code.
Particularly if you are writing an extensible system, this is useful.

The standard Guice approach is [Assisted Inject](https://code.google.com/p/google-guice/wiki/AssistedInject) - which, while 
(IMO) boilerplate-heavy, works.  On the other hand, if you want to write something where clients building on what *you* write
will be able to extend things with their own objects - that is, you *don't know* all the types that might be injected in the
future - then assisted inject is less appealing.  Yes, you could tell someone they have to write factories for 20 types,
or write code that creates 20 factories in a loop and binds things, but who wants to do that.

This library offers a simple alternative:

    class MyModule extends AbstractModule {
        public void configure() {
            ReentrantScope scope = new ReentrantScope();
            bind (ReentrantScope.class).toInstance(scope);

            // there is no getting around explitly binding classes, but
            // you can provide a way to pass in an array easily enough:

            scope.bindTypes(binder(), FooRequest.class, FooResponse.class, ...);
        }
    }

    public class TheApp {

        public void onRequest(FooRequest req) {
            FooResponse resp = new FooResponse();
            try (AutoCloseable ac = scope.enter(req, resp)) {
                // Anything with a Provider<FooRequest>, etc. will
               // get the passed request
            }
        }
    }

You **enter the scope with an array of objects**;  you exit the scope by calling ``scope.exit()``
on the same thread you entered it on (the easy way is to use the ``AutoCloseable`` as shown above).

Threading
=========

Sometimes you need multi-threading; whereas typically Guice scopes are little more than a wrapper
for some ``ThreadLocal``s.  So, all subclasses of ``AbstractScope`` let you call

    ExecutorService wrapThreadPool (ExecutorService svc)

If you are inside a ReentrantScope, and dispatch a ``Runnable`` to an ``ExecutorService`` wrappered
via this method, the current scope contents will be frozen and reconstituted before the Runnable
runs - so you get identical scope contents to what you had when you submitted teh ``Runnable``.

This way it is possible to have all of the benefits of scoping, and have a complex threading model.
