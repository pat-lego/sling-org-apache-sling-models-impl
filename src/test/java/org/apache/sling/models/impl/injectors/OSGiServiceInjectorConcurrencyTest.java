/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.sling.models.impl.injectors;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import org.apache.sling.models.annotations.injectorspecific.OSGiService;
import org.apache.sling.models.impl.injectors.FakeServiceRegistry.FakeReference;
import org.apache.sling.models.spi.DisposalCallback;
import org.apache.sling.models.spi.DisposalCallbackRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.osgi.framework.BundleContext;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Stress and race tests of the {@link OSGiServiceInjector} with and without the {@link OSGiServiceCache}, against a
 * thread-safe {@link FakeServiceRegistry} which counts every service usage.
 * <p>
 * The invariants checked are:
 * <ul>
 * <li><b>Linearizability</b>: an injection which starts and ends while the set of registered services is stable
 * injects exactly the registered services (highest ranking first); in particular it never injects a service that
 * was unregistered before the injection started and always injects a service that was registered before it
 * started.</li>
 * <li><b>No leaked usages</b>: once all models are disposed and no thread injects anymore, an unregistered service
 * is not used by any bundle anymore, a registered service is used at most once per bundle (by the cache), and no
 * service is used at all after the injector is deactivated.</li>
 * <li><b>No over-released usages</b>: no service is released more often than it was obtained.</li>
 * </ul>
 */
@Timeout(value = 120, unit = TimeUnit.SECONDS)
class OSGiServiceInjectorConcurrencyTest {

    private static final int THREADS = Math.max(8, Runtime.getRuntime().availableProcessors() * 2);

    private static final String GREETER = Greeter.class.getName();

    private FakeServiceRegistry registry;

    private BundleContext injectorContext;

    private List<BundleContext> modelContexts;

    private OSGiServiceInjector injector;

    private ExecutorService executor;

    @BeforeEach
    void setUp() {
        registry = new FakeServiceRegistry();
        injectorContext = registry.newContext();
        modelContexts = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            modelContexts.add(registry.newContext());
        }
        executor = Executors.newFixedThreadPool(THREADS + 2);
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        executor.shutdownNow();
        assertTrue(executor.awaitTermination(30, TimeUnit.SECONDS));
    }

    private void activate(boolean serviceCacheEnabled) {
        injector = new OSGiServiceInjector();
        injector.activate(injectorContext, OSGiServiceInjectorConfigs.config(serviceCacheEnabled));
        assertEquals(serviceCacheEnabled, injector.isServiceCacheActive());
    }

    // ------------------------------------------------------------------------------------------------ toggle

    @Test
    void testServiceCacheIsDisabledByDefault() {
        // no configuration
        OSGiServiceInjector unconfigured = new OSGiServiceInjector();
        unconfigured.activate(injectorContext, null);
        assertFalse(unconfigured.isServiceCacheActive());
        // the default value of the configuration property
        OSGiServiceInjector disabled = new OSGiServiceInjector();
        disabled.activate(injectorContext, OSGiServiceInjectorConfigs.config(false));
        assertFalse(disabled.isServiceCacheActive());
        assertEquals(0, registry.serviceListenerCount());
        assertEquals(0, registry.bundleListenerCount());
    }

    @Test
    void testServiceCacheIsEnabledByConfiguration() throws Exception {
        activate(true);
        assertEquals(1, registry.serviceListenerCount());
        assertEquals(1, registry.bundleListenerCount());

        FakeReference ref = registerGreeter(0);
        BundleContext context = modelContexts.get(0);
        for (int i = 0; i < 10; i++) {
            assertGreeter(ref, inject("greeter", context));
        }
        assertEquals(1, registry.lookups.get());
        assertEquals(1, registry.gets.get());

        injector.deactivate();
        assertEquals(0, registry.serviceListenerCount());
        assertEquals(0, registry.bundleListenerCount());
        assertEquals(0, registry.totalUsage(ref));
    }

    @Test
    void testDisabledServiceCacheUsesTheServiceRegistryForEveryInjection() throws Exception {
        activate(false);
        assertEquals(0, registry.serviceListenerCount());
        assertEquals(0, registry.bundleListenerCount());

        FakeReference ref = registerGreeter(0);
        BundleContext context = modelContexts.get(0);
        for (int i = 0; i < 10; i++) {
            Disposables disposables = new Disposables();
            assertGreeter(ref, injectValue("greeter", context, disposables));
            assertEquals(1, registry.usage(context, ref));
            disposables.dispose();
            assertEquals(0, registry.usage(context, ref));
        }
        assertEquals(10, registry.lookups.get());
        assertEquals(10, registry.gets.get());
        assertEquals(10, registry.ungets.get());

        injector.deactivate();
        assertEquals(0, injector.getServiceCache().servicesSize());
        assertEquals(0, injector.getServiceCache().referencesSize());
    }

    // ----------------------------------------------------------------------- concurrent injection, stable services

    @Test
    void testConcurrentInjectionObtainsEachServiceOncePerBundle() throws Exception {
        activate(true);
        FakeReference low = registerGreeter(10);
        FakeReference high = registerGreeter(20);
        final int iterations = 5_000;

        runConcurrently(THREADS, thread -> {
            for (int i = 0; i < iterations; i++) {
                BundleContext context = modelContexts.get((thread + i) % modelContexts.size());
                Disposables disposables = new Disposables();
                assertGreeter(high, injectValue("greeter", context, disposables));
                assertGreeters(Arrays2.of(high, low), injectValue("greeters", context, disposables));
                assertGreeters(Arrays2.of(high, low), injectValue("greeterArray", context, disposables));
                // cached services are not released per model
                assertTrue(disposables.isEmpty());
            }
        });

        assertNoViolations();
        for (BundleContext context : modelContexts) {
            // exactly one usage per bundle, held by the cache, regardless of how many threads raced for it
            assertEquals(1, registry.usage(context, low));
            assertEquals(1, registry.usage(context, high));
        }
        // greeter, greeters and greeterArray share the lookup of each bundle; racing threads may look up concurrently
        assertTrue(registry.lookups.get() >= modelContexts.size());
        assertTrue(registry.lookups.get() <= THREADS * modelContexts.size(), "lookups: " + registry.lookups);
        assertTrue(registry.gets.get() <= 2 * THREADS * modelContexts.size(), "gets: " + registry.gets);

        injector.deactivate();
        assertEquals(0, registry.totalUsage(low));
        assertEquals(0, registry.totalUsage(high));
        assertNoViolations();
    }

    @Test
    void testConcurrentInjectionOfServiceFactoryCachesOneServiceObjectPerBundle() throws Exception {
        activate(true);
        FakeReference ref = registry.register(GREETER, 0, new HashMap<>(), TestGreeter::new);
        final Map<BundleContext, Greeter> seen = new java.util.concurrent.ConcurrentHashMap<>();

        runConcurrently(THREADS, thread -> {
            for (int i = 0; i < 5_000; i++) {
                BundleContext context =
                        modelContexts.get(ThreadLocalRandom.current().nextInt(modelContexts.size()));
                Greeter greeter = (Greeter) inject("greeter", context);
                assertNotNull(greeter);
                // every bundle gets its own service object, and always the same one
                assertSame(context, ((TestGreeter) greeter).owner);
                Greeter previous = seen.putIfAbsent(context, greeter);
                if (previous != null) {
                    assertSame(previous, greeter);
                }
            }
        });

        assertNoViolations();
        for (BundleContext context : modelContexts) {
            assertEquals(1, registry.usage(context, ref));
        }
        injector.deactivate();
        assertEquals(0, registry.totalUsage(ref));
    }

    @Test
    void testServiceFactoryReturningNullForSomeBundlesFallsBackToTheNextService() throws Exception {
        activate(true);
        BundleContext refused = modelContexts.get(0);
        FakeReference low = registerGreeter(1);
        FakeReference high = registry.register(
                GREETER,
                2,
                new HashMap<>(),
                (context, ref) -> context == refused ? null : new TestGreeter(context, ref));

        runConcurrently(THREADS, thread -> {
            for (int i = 0; i < 2_000; i++) {
                BundleContext context = modelContexts.get(i % modelContexts.size());
                Disposables disposables = new Disposables();
                if (context == refused) {
                    assertGreeter(low, injectValue("greeter", context, disposables));
                    assertGreeters(Arrays2.of(low), injectValue("greeters", context, disposables));
                } else {
                    assertGreeter(high, injectValue("greeter", context, disposables));
                    assertGreeters(Arrays2.of(high, low), injectValue("greeters", context, disposables));
                }
                disposables.dispose();
            }
        });

        assertNoViolations();
        // the failed service object is not cached, so the factory is asked again (and refuses again) every time
        assertEquals(0, registry.usage(refused, high));
        assertEquals(1, registry.usage(refused, low));
        injector.deactivate();
        assertEquals(0, registry.totalUsage(low));
        assertEquals(0, registry.totalUsage(high));
    }

    // ------------------------------------------------------------------------------------- absent services

    @Test
    void testConcurrentInjectionOfAbsentServiceIsCachedAndNeverGetsAService() throws Exception {
        activate(true);
        AtomicBoolean done = new AtomicBoolean();
        AtomicInteger churn = new AtomicInteger();

        // services of an unrelated class are registered and unregistered meanwhile: this must not evict the lookups of
        // the absent service
        Future<?> unrelated = executor.submit(() -> {
            while (!done.get()) {
                FakeReference ref = registry.register("org.example.Unrelated", 0, new Object());
                registry.unregister(ref);
                churn.incrementAndGet();
            }
            return null;
        });

        runConcurrently(THREADS, thread -> {
            for (int i = 0; i < 5_000; i++) {
                BundleContext context = modelContexts.get(i % modelContexts.size());
                Disposables disposables = new Disposables();
                assertNull(injectValue("absent", context, disposables));
                assertNull(injectValue("absents", context, disposables));
                assertNull(injectValue("absentArray", context, disposables));
                assertTrue(disposables.isEmpty());
            }
        });
        done.set(true);
        unrelated.get(30, TimeUnit.SECONDS);

        assertNoViolations();
        assertTrue(churn.get() > 0);
        // the absent service was never got and its (empty) lookup cached per bundle
        assertEquals(0, registry.gets.get());
        assertTrue(registry.lookups.get() <= THREADS * modelContexts.size(), "lookups: " + registry.lookups);
        for (BundleContext context : modelContexts) {
            assertEquals(1, injector.getServiceCache().referencesSize(context));
            assertEquals(0, injector.getServiceCache().servicesSize(context));
        }
    }

    @Test
    void testAbsentServiceWithFilterIsNotInjectedWhileOtherServicesAreRegistered() throws Exception {
        activate(true);
        FakeReference red = registerGreeter(0, "red");
        runConcurrently(THREADS, thread -> {
            for (int i = 0; i < 2_000; i++) {
                BundleContext context = modelContexts.get(i % modelContexts.size());
                assertNull(inject("blueGreeter", context));
                assertGreeter(red, inject("greeter", context));
            }
        });
        assertNoViolations();
        // a blue service is registered: the next injection sees it
        FakeReference blue = registerGreeter(-1, "blue");
        for (BundleContext context : modelContexts) {
            assertGreeter(blue, inject("blueGreeter", context));
            assertGreeter(red, inject("greeter", context));
        }
        registry.unregister(blue);
        for (BundleContext context : modelContexts) {
            assertNull(inject("blueGreeter", context));
        }
        assertEquals(0, registry.totalUsage(blue));
    }

    // --------------------------------------------------------- services registered / unregistered while injecting

    @Test
    void testOnlyServiceRegisteredAndUnregisteredWhileInjectingWithCache() throws Exception {
        activate(true);
        churnSingleService(500);
    }

    @Test
    void testOnlyServiceRegisteredAndUnregisteredWhileInjectingWithoutCache() throws Exception {
        activate(false);
        churnSingleService(500);
    }

    /**
     * Registers and unregisters the only service again and again, while many threads inject it: each injection which
     * runs while the service is (stably) registered must inject it, each injection which runs while it is (stably)
     * unregistered must inject nothing.
     */
    private void churnSingleService(int rounds) throws Exception {
        // widen the window between the UNREGISTERING event and the invalidation of the service
        registry.unregisterWindow = Thread::yield;
        AtomicReference<Snapshot> state = new AtomicReference<>(Snapshot.stable());
        AtomicBoolean done = new AtomicBoolean();
        AtomicInteger checked = new AtomicInteger();

        Future<?> writer = executor.submit(() -> {
            for (int round = 0; round < rounds; round++) {
                state.set(Snapshot.TRANSITION);
                FakeReference ref = registerGreeter(round);
                state.set(Snapshot.stable(ref));
                pause();
                state.set(Snapshot.TRANSITION);
                registry.unregister(ref);
                state.set(Snapshot.stable());
                pause();
            }
            done.set(true);
            return null;
        });

        runConcurrently(THREADS, thread -> {
            while (!done.get()) {
                BundleContext context =
                        modelContexts.get(ThreadLocalRandom.current().nextInt(modelContexts.size()));
                Disposables disposables = new Disposables();
                Snapshot before = state.get();
                Object greeter = injectValue("greeter", context, disposables);
                Object greeters = injectValue("greeters", context, disposables);
                Snapshot after = state.get();
                if (before == after && before.stable) {
                    assertInjected(before, greeter, greeters);
                    checked.incrementAndGet();
                } else if (greeter != null) {
                    // whatever raced, only ever a service that was registered at some point is injected
                    assertTrue(registry.allReferences().stream().anyMatch(r -> r.id == ((Greeter) greeter).id()));
                }
                disposables.dispose();
            }
        });
        writer.get(60, TimeUnit.SECONDS);

        assertTrue(checked.get() > 0, "no injection observed a stable state");
        assertQuiescentState();
    }

    @Test
    void testManyServicesRegisteredUnregisteredAndReRankedWhileInjectingWithCache() throws Exception {
        activate(true);
        churnManyServices(800);
    }

    @Test
    void testManyServicesRegisteredUnregisteredAndReRankedWhileInjectingWithoutCache() throws Exception {
        activate(false);
        churnManyServices(800);
    }

    /**
     * Randomly registers, unregisters and re-ranks services while many threads inject the highest ranked service and
     * the list of all services into models of several bundles.
     */
    private void churnManyServices(int operations) throws Exception {
        registry.unregisterWindow = Thread::yield;
        AtomicReference<Snapshot> state = new AtomicReference<>(Snapshot.stable());
        AtomicBoolean done = new AtomicBoolean();
        AtomicInteger checked = new AtomicInteger();

        Future<?> writer = executor.submit(() -> {
            ThreadLocalRandom random = ThreadLocalRandom.current();
            for (int op = 0; op < operations; op++) {
                state.set(Snapshot.TRANSITION);
                List<FakeReference> current = registry.registered();
                int choice = random.nextInt(10);
                if (current.size() < 2 || (choice < 4 && current.size() < 8)) {
                    registerGreeter(random.nextInt(-5, 5));
                } else if (choice < 8) {
                    registry.unregister(current.get(random.nextInt(current.size())));
                } else {
                    registry.setRanking(current.get(random.nextInt(current.size())), random.nextInt(-5, 5));
                }
                state.set(Snapshot.stable(registry.registered()));
                pause();
            }
            done.set(true);
            return null;
        });

        runConcurrently(THREADS, thread -> {
            while (!done.get()) {
                BundleContext context =
                        modelContexts.get(ThreadLocalRandom.current().nextInt(modelContexts.size()));
                Disposables disposables = new Disposables();
                Snapshot before = state.get();
                Object greeter = injectValue("greeter", context, disposables);
                Object greeters = injectValue("greeters", context, disposables);
                Object greeterArray = injectValue("greeterArray", context, disposables);
                Snapshot after = state.get();
                if (before == after && before.stable) {
                    assertInjected(before, greeter, greeters);
                    assertInjected(before, greeter, greeterArray);
                    checked.incrementAndGet();
                }
                disposables.dispose();
            }
        });
        writer.get(60, TimeUnit.SECONDS);

        assertTrue(checked.get() > 0, "no injection observed a stable state");
        assertQuiescentState();
    }

    @Test
    void testUnregisteringServiceUnderLoadReleasesEveryUsage() throws Exception {
        activate(true);
        // many bundles, each with its own service object
        for (int i = 0; i < 12; i++) {
            modelContexts.add(registry.newContext());
        }
        registry.unregisterWindow = Thread::yield;
        for (int round = 0; round < 50; round++) {
            FakeReference ref = registry.register(GREETER, 0, new HashMap<>(), TestGreeter::new);
            CountDownLatch injected = new CountDownLatch(THREADS);
            AtomicBoolean unregistered = new AtomicBoolean();
            Future<?> unregister = executor.submit(() -> {
                injected.await();
                registry.unregister(ref);
                unregistered.set(true);
                return null;
            });
            runConcurrently(THREADS, thread -> {
                boolean counted = false;
                for (int i = 0; i < 300; i++) {
                    BundleContext context = modelContexts.get((thread + i) % modelContexts.size());
                    boolean unregisteredBefore = unregistered.get();
                    Object greeter = inject("greeter", context);
                    if (unregisteredBefore) {
                        // never injected once it is unregistered
                        assertNull(greeter);
                    }
                    if (!counted) {
                        counted = true;
                        injected.countDown();
                    }
                }
            });
            unregister.get(30, TimeUnit.SECONDS);

            // no bundle keeps using the unregistered service, the cache keeps nothing for it
            assertEquals(0, registry.totalUsage(ref), "round " + round);
            for (BundleContext context : modelContexts) {
                assertNull(inject("greeter", context));
            }
        }
        assertNoViolations();
        assertEquals(0, injector.getServiceCache().servicesSize());
    }

    // ---------------------------------------------------------------------------- bundles stopping, deactivation

    @Test
    void testBundleStoppingWhileInjectingEvictsItsEntries() throws Exception {
        activate(true);
        FakeReference ref = registerGreeter(0);
        BundleContext stopping = modelContexts.get(0);
        BundleContext running = modelContexts.get(1);
        AtomicBoolean stopped = new AtomicBoolean();
        CountDownLatch started = new CountDownLatch(THREADS);

        Future<?> stopper = executor.submit(() -> {
            started.await();
            registry.stop(stopping);
            stopped.set(true);
            return null;
        });
        runConcurrently(THREADS, thread -> {
            started.countDown();
            for (int i = 0; i < 3_000; i++) {
                if (thread % 2 == 0) {
                    try {
                        Object greeter = inject("greeter", stopping);
                        if (greeter != null) {
                            assertGreeter(ref, greeter);
                        }
                    } catch (IllegalStateException e) {
                        // the bundle context is no longer valid
                        assertTrue(stopped.get() || e.getMessage().contains("Invalid"));
                    }
                } else {
                    assertGreeter(ref, inject("greeter", running));
                }
            }
        });
        stopper.get(30, TimeUnit.SECONDS);

        assertNoViolations();
        // nothing is cached for the stopped bundle anymore, even if it was cached concurrently with the event
        assertEquals(0, injector.getServiceCache().referencesSize(stopping));
        assertEquals(0, injector.getServiceCache().servicesSize(stopping));
        // the other bundles are not affected
        assertEquals(1, registry.usage(running, ref));
        assertEquals(1, injector.getServiceCache().servicesSize(running));
    }

    @Test
    void testDeactivationWhileInjectingReleasesEveryUsage() throws Exception {
        activate(true);
        FakeReference low = registerGreeter(0);
        FakeReference high = registerGreeter(1);
        CountDownLatch started = new CountDownLatch(THREADS);
        AtomicBoolean deactivated = new AtomicBoolean();

        Future<?> deactivator = executor.submit(() -> {
            started.await();
            injector.deactivate();
            deactivated.set(true);
            return null;
        });
        runConcurrently(THREADS, thread -> {
            started.countDown();
            for (int i = 0; i < 3_000; i++) {
                BundleContext context = modelContexts.get((thread + i) % modelContexts.size());
                Disposables disposables = new Disposables();
                assertGreeter(high, injectValue("greeter", context, disposables));
                assertGreeters(Arrays2.of(high, low), injectValue("greeters", context, disposables));
                disposables.dispose();
            }
        });
        deactivator.get(30, TimeUnit.SECONDS);

        assertTrue(deactivated.get());
        assertFalse(injector.isServiceCacheActive());
        assertNoViolations();
        // after deactivation every usage was released: by the cache, by the late cachers, or by the disposal callbacks
        assertEquals(0, registry.totalUsage(low));
        assertEquals(0, registry.totalUsage(high));
        assertEquals(0, injector.getServiceCache().servicesSize());
        assertEquals(0, injector.getServiceCache().referencesSize());
        assertEquals(0, registry.serviceListenerCount());
        assertEquals(0, registry.bundleListenerCount());
    }

    // ------------------------------------------------------------------------ deterministic race interleavings

    @Test
    void testServiceUnregisteredAfterItWasObtainedButBeforeItIsCached() throws Exception {
        activate(true);
        FakeReference ref = registerGreeter(0);
        BundleContext context = modelContexts.get(0);
        // the service is unregistered (event delivered and reference invalidated) right after getService
        registry.afterGetService = (c, r) -> {
            registry.afterGetService = (c2, r2) -> {};
            registry.unregister(ref);
        };

        // the service obtained before the unregistration completed may be injected into this model ...
        inject("greeter", context);
        // ... but it is not kept by the cache
        assertEquals(0, registry.totalUsage(ref));
        assertEquals(0, injector.getServiceCache().servicesSize());
        assertNull(inject("greeter", context));
        assertNoViolations();
    }

    @Test
    void testServiceCachedWhileUnregisteringEventIsDelivered() throws Exception {
        activate(true);
        FakeReference ref = registerGreeter(0);
        BundleContext context = modelContexts.get(0);
        CountDownLatch obtained = new CountDownLatch(1);
        CountDownLatch eventDelivered = new CountDownLatch(1);
        CountDownLatch cached = new CountDownLatch(1);
        registry.afterGetService = (c, r) -> {
            registry.afterGetService = (c2, r2) -> {};
            obtained.countDown();
            await(eventDelivered);
        };
        // the injecting thread caches the service after the UNREGISTERING event released the cached services, but
        // before the reference is invalidated (ServiceReference.getBundle() still returns the bundle)
        registry.unregisterWindow = () -> {
            eventDelivered.countDown();
            await(cached);
        };

        Future<?> injecting = executor.submit(() -> {
            inject("greeter", context);
            cached.countDown();
            return null;
        });
        await(obtained);
        Future<?> unregistering = executor.submit(() -> {
            registry.unregister(ref);
            return null;
        });
        injecting.get(30, TimeUnit.SECONDS);
        unregistering.get(30, TimeUnit.SECONDS);

        assertEquals(0, registry.totalUsage(ref));
        assertEquals(0, injector.getServiceCache().servicesSize());
        assertNull(inject("greeter", context));
        assertNoViolations();
    }

    @Test
    void testServiceCachedWhileBundleIsStopping() throws Exception {
        activate(true);
        registerGreeter(0);
        BundleContext context = modelContexts.get(0);
        CountDownLatch obtained = new CountDownLatch(1);
        CountDownLatch stopped = new CountDownLatch(1);
        registry.afterGetService = (c, r) -> {
            registry.afterGetService = (c2, r2) -> {};
            obtained.countDown();
            await(stopped);
        };

        Future<?> injecting = executor.submit(() -> inject("greeter", context));
        await(obtained);
        registry.stop(context);
        stopped.countDown();
        injecting.get(30, TimeUnit.SECONDS);

        // the service object cached after the STOPPING event is not kept for the stopped bundle
        assertEquals(0, injector.getServiceCache().servicesSize(context));
        assertEquals(0, injector.getServiceCache().referencesSize(context));
        assertNoViolations();
    }

    @Test
    void testServiceCachedWhileInjectorIsDeactivated() throws Exception {
        activate(true);
        FakeReference ref = registerGreeter(0);
        BundleContext context = modelContexts.get(0);
        CountDownLatch obtained = new CountDownLatch(1);
        CountDownLatch deactivated = new CountDownLatch(1);
        registry.afterGetService = (c, r) -> {
            registry.afterGetService = (c2, r2) -> {};
            obtained.countDown();
            await(deactivated);
        };

        Future<?> injecting = executor.submit(() -> inject("greeter", context));
        await(obtained);
        injector.deactivate();
        deactivated.countDown();
        assertNotNull(injecting.get(30, TimeUnit.SECONDS));

        assertEquals(0, registry.totalUsage(ref));
        assertEquals(0, injector.getServiceCache().servicesSize());
        assertNoViolations();
    }

    @Test
    void testServiceObtainedConcurrentlyByManyThreadsIsUsedOnce() throws Exception {
        activate(true);
        FakeReference ref = registerGreeter(0);
        BundleContext context = modelContexts.get(0);
        // all threads obtain the service before any of them caches it
        CyclicBarrier allObtained = new CyclicBarrier(THREADS);
        AtomicInteger gets = new AtomicInteger();
        registry.afterGetService = (c, r) -> {
            if (gets.incrementAndGet() <= THREADS) {
                await(allObtained);
            }
        };

        runConcurrently(THREADS, thread -> assertGreeter(ref, inject("greeter", context)));

        assertEquals(THREADS, registry.gets.get());
        // all but one usage were released again
        assertEquals(1, registry.usage(context, ref));
        assertEquals(THREADS - 1, registry.ungets.get());
        assertNoViolations();
    }

    @Test
    void testLookupRacingWithRegistrationDoesNotHideTheNewService() throws Exception {
        activate(true);
        BundleContext context = modelContexts.get(0);
        // the service is registered right after the (empty) lookup, before its result is cached
        AtomicReference<FakeReference> registered = new AtomicReference<>();
        BundleContext racing = new RacingContext(context, () -> registered.set(registerGreeter(0))).context;

        assertNull(inject("greeter", racing));
        // the outdated empty result was not kept: the next injection of the same bundle sees the service
        assertGreeter(registered.get(), inject("greeter", racing));
        assertEquals(2, registry.lookups.get());
        assertNoViolations();
    }

    @Test
    void testLookupRacingWithUnregistrationDoesNotInjectTheUnregisteredService() throws Exception {
        activate(true);
        BundleContext context = modelContexts.get(0);
        FakeReference ref = registerGreeter(0);
        // the service is unregistered right after the lookup found it, before its result is cached
        BundleContext racing = new RacingContext(context, () -> registry.unregister(ref)).context;

        // the outdated reference is found, but its service is not injected
        assertNull(inject("greeter", racing));
        assertNull(inject("greeter", racing));
        assertEquals(2, registry.lookups.get());
        assertEquals(0, registry.totalUsage(ref));
        assertNoViolations();
    }

    // --------------------------------------------------------------------------------------------------- helpers

    /**
     * A bundle context which delegates to a fake context but runs an action once, right after the first lookup.
     */
    private static final class RacingContext {

        final BundleContext context;

        RacingContext(BundleContext delegate, Runnable afterFirstLookup) {
            AtomicBoolean first = new AtomicBoolean(true);
            this.context = org.mockito.Mockito.mock(
                    BundleContext.class,
                    org.mockito.Mockito.withSettings().stubOnly().defaultAnswer(invocation -> {
                        Object result = invocation.getMethod().invoke(delegate, invocation.getArguments());
                        if ("getServiceReferences".equals(invocation.getMethod().getName())
                                && first.compareAndSet(true, false)) {
                            afterFirstLookup.run();
                        }
                        return result;
                    }));
        }
    }

    /**
     * After all injections completed and all models were disposed: no service is leaked or over-released, the
     * cache (if any) holds at most one usage per bundle of each registered service, and injections see exactly the
     * registered services.
     */
    private void assertQuiescentState() throws Exception {
        assertNoViolations();
        List<FakeReference> registered = registry.registered();
        for (FakeReference ref : registry.allReferences()) {
            if (!ref.isRegistered()) {
                assertEquals(0, registry.totalUsage(ref), "leaked usage of unregistered " + ref);
            } else {
                for (BundleContext context : modelContexts) {
                    int usage = registry.usage(context, ref);
                    assertTrue(usage <= (injector.isServiceCacheActive() ? 1 : 0), ref + " used " + usage + " times");
                }
            }
        }
        Snapshot expected = Snapshot.stable(registered);
        for (BundleContext context : modelContexts) {
            Disposables disposables = new Disposables();
            assertInjected(
                    expected,
                    injectValue("greeter", context, disposables),
                    injectValue("greeters", context, disposables));
            disposables.dispose();
        }
        injector.deactivate();
        for (FakeReference ref : registry.allReferences()) {
            assertEquals(0, registry.totalUsage(ref), "leaked usage after deactivation of " + ref);
        }
        assertNoViolations();
    }

    private static void assertInjected(Snapshot expected, Object greeter, Object greeters) {
        if (expected.ids.isEmpty()) {
            assertNull(greeter);
            assertNull(greeters);
        } else {
            assertNotNull(greeter, "expected " + expected.ids);
            assertEquals(expected.ids.get(0).longValue(), ((Greeter) greeter).id());
            assertEquals(expected.ids, ids(greeters));
        }
    }

    private void assertNoViolations() {
        assertTrue(registry.violations.isEmpty(), () -> "violations: " + registry.violations);
    }

    private static void assertGreeter(FakeReference expected, Object greeter) {
        assertNotNull(greeter, "expected " + expected);
        assertEquals(expected.id, ((Greeter) greeter).id());
    }

    private static void assertGreeters(List<FakeReference> expected, Object greeters) {
        assertEquals(expected.stream().map(r -> r.id).collect(Collectors.toList()), ids(greeters));
    }

    private static List<Long> ids(Object greeters) {
        if (greeters == null) {
            return Collections.emptyList();
        }
        List<?> list = greeters instanceof Object[] ? java.util.Arrays.asList((Object[]) greeters) : (List<?>) greeters;
        return list.stream().map(g -> ((Greeter) g).id()).collect(Collectors.toList());
    }

    private FakeReference registerGreeter(int ranking) {
        return registerGreeter(ranking, null);
    }

    private FakeReference registerGreeter(int ranking, String flavor) {
        Map<String, Object> properties = new HashMap<>();
        if (flavor != null) {
            properties.put("flavor", flavor);
        }
        return registry.register(GREETER, ranking, properties, TestGreeter::new);
    }

    private Object inject(String fieldName, BundleContext context) throws Exception {
        return injectValue(fieldName, context, new Disposables());
    }

    private Object injectValue(String fieldName, BundleContext context, DisposalCallbackRegistry disposables)
            throws Exception {
        Field field = Holder.class.getDeclaredField(fieldName);
        return injector.getValue(new Object(), fieldName, field.getGenericType(), field, disposables, context);
    }

    private static void pause() {
        if (ThreadLocalRandom.current().nextBoolean()) {
            Thread.yield();
        } else {
            java.util.concurrent.locks.LockSupport.parkNanos(
                    ThreadLocalRandom.current().nextInt(20_000));
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(30, TimeUnit.SECONDS), "timed out");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static void await(CyclicBarrier barrier) {
        try {
            barrier.await(30, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Runs the task on the given number of threads, all started at the same time, and rethrows the first failure.
     */
    private void runConcurrently(int threads, ThreadTask task) throws Exception {
        CyclicBarrier start = new CyclicBarrier(threads);
        List<Future<?>> futures = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            final int thread = t;
            futures.add(executor.submit(() -> {
                start.await(30, TimeUnit.SECONDS);
                task.run(thread);
                return null;
            }));
        }
        Throwable failure = null;
        for (Future<?> future : futures) {
            try {
                future.get(90, TimeUnit.SECONDS);
            } catch (java.util.concurrent.ExecutionException e) {
                if (failure == null) {
                    failure = e.getCause();
                }
            }
        }
        if (failure instanceof Error) {
            throw (Error) failure;
        }
        if (failure != null) {
            fail(failure);
        }
    }

    @FunctionalInterface
    private interface ThreadTask {
        void run(int thread) throws Exception;
    }

    /**
     * The set of registered services, as published by a writer thread: either stable (with the registered services
     * sorted by descending ranking) or in transition.
     */
    private static final class Snapshot {

        static final Snapshot TRANSITION = new Snapshot(false, Collections.emptyList());

        final boolean stable;

        final List<Long> ids;

        private Snapshot(boolean stable, List<Long> ids) {
            this.stable = stable;
            this.ids = ids;
        }

        static Snapshot stable(FakeReference... registered) {
            return stable(java.util.Arrays.asList(registered));
        }

        static Snapshot stable(List<FakeReference> registered) {
            List<Long> ids = registered.stream()
                    .sorted(Comparator.<FakeReference>reverseOrder())
                    .map(r -> r.id)
                    .collect(Collectors.toList());
            return new Snapshot(true, ids);
        }
    }

    private static final class Arrays2 {
        static List<FakeReference> of(FakeReference... refs) {
            return java.util.Arrays.asList(refs);
        }
    }

    // ---------------------------------------------------------------------------------------------------- model

    public interface Greeter {
        long id();
    }

    public interface Absent {}

    private static final class TestGreeter implements Greeter {

        private final long id;

        /** the bundle context for which the service object was created */
        final BundleContext owner;

        TestGreeter(BundleContext owner, FakeReference reference) {
            this.id = reference.id;
            this.owner = owner;
        }

        @Override
        public long id() {
            return id;
        }
    }

    @SuppressWarnings("unused")
    private static final class Holder {

        @OSGiService
        Greeter greeter;

        @OSGiService
        List<Greeter> greeters;

        @OSGiService
        Greeter[] greeterArray;

        @OSGiService(filter = "(flavor=blue)")
        Greeter blueGreeter;

        @OSGiService
        Absent absent;

        @OSGiService
        List<Absent> absents;

        @OSGiService
        Absent[] absentArray;
    }

    private static final class Disposables implements DisposalCallbackRegistry {

        private final List<DisposalCallback> callbacks = Collections.synchronizedList(new ArrayList<>());

        @Override
        public void addDisposalCallback(DisposalCallback callback) {
            callbacks.add(callback);
        }

        boolean isEmpty() {
            return callbacks.isEmpty();
        }

        void dispose() {
            for (DisposalCallback callback : callbacks) {
                callback.onDisposed();
            }
            callbacks.clear();
        }
    }
}
