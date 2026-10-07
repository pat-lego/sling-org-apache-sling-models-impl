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

import java.util.ArrayList;
import java.util.Dictionary;
import java.util.HashMap;
import java.util.Hashtable;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;

import org.mockito.invocation.InvocationOnMock;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.BundleEvent;
import org.osgi.framework.BundleListener;
import org.osgi.framework.Constants;
import org.osgi.framework.FrameworkUtil;
import org.osgi.framework.InvalidSyntaxException;
import org.osgi.framework.ServiceEvent;
import org.osgi.framework.ServiceListener;
import org.osgi.framework.ServiceReference;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.withSettings;

/**
 * A minimal, thread-safe in-memory OSGi service registry for concurrency tests.
 * <p>
 * Unlike Mockito stubs it can be used from many threads at once while services are registered, modified and
 * unregistered, and it keeps exact usage counts per (bundle context, service reference), so tests can detect
 * leaked or over-released service usages. It is deliberately adversarial:
 * <ul>
 * <li>a service stays visible to {@code getServiceReferences} and {@code getService} while its
 * {@link ServiceEvent#UNREGISTERING} event is delivered (and during an optional {@link #unregisterWindow}), and only
 * then is it removed and its reference invalidated ({@link ServiceReference#getBundle()} returns {@code null});</li>
 * <li>unlike a real framework it does <em>not</em> release the usages of an unregistered service, so any usage the
 * code under test does not release itself remains visible in {@link #usage(BundleContext, ServiceReference)}.</li>
 * </ul>
 */
final class FakeServiceRegistry {

    private final List<FakeReference> registered = new CopyOnWriteArrayList<>();

    private final List<ServiceListener> serviceListeners = new CopyOnWriteArrayList<>();

    private final List<BundleListener> bundleListeners = new CopyOnWriteArrayList<>();

    private final Map<BundleContext, FakeContext> contexts = new ConcurrentHashMap<>();

    private final AtomicLong serviceIds = new AtomicLong();

    private final List<FakeReference> allReferences = new CopyOnWriteArrayList<>();

    /** Number of {@code getServiceReferences} calls. */
    final AtomicInteger lookups = new AtomicInteger();

    /** Number of {@code getService} calls. */
    final AtomicInteger gets = new AtomicInteger();

    /** Number of {@code ungetService} calls. */
    final AtomicInteger ungets = new AtomicInteger();

    /** Contract violations detected by the registry, e.g. releasing a service that is not used. */
    final Queue<String> violations = new ConcurrentLinkedQueue<>();

    /** Called after a service object was obtained (or not) by {@code getService}, before it is returned. */
    volatile BiConsumer<BundleContext, ServiceReference<?>> afterGetService = (context, reference) -> {};

    /** Called after the UNREGISTERING event was delivered, before the service is removed and invalidated. */
    volatile Runnable unregisterWindow = () -> {};

    private final Bundle registeringBundle = mock(Bundle.class, withSettings().stubOnly());

    // ---- bundles

    /**
     * @return the bundle context of a new, active bundle
     */
    BundleContext newContext() {
        final FakeContext fake = new FakeContext();
        fake.context = mock(BundleContext.class, withSettings().stubOnly().defaultAnswer(fake::answer));
        fake.bundle = mock(Bundle.class, withSettings().stubOnly().defaultAnswer(fake::answerBundle));
        contexts.put(fake.context, fake);
        return fake.context;
    }

    /**
     * Stops the bundle of the given context: delivers the (synchronous) STOPPING event, then invalidates the context.
     */
    void stop(BundleContext context) {
        final FakeContext fake = contexts.get(context);
        final BundleEvent event = new BundleEvent(BundleEvent.STOPPING, fake.bundle);
        for (final BundleListener listener : bundleListeners) {
            listener.bundleChanged(event);
        }
        fake.valid = false;
    }

    int serviceListenerCount() {
        return serviceListeners.size();
    }

    int bundleListenerCount() {
        return bundleListeners.size();
    }

    // ---- services

    /**
     * Registers a service which returns the same service object to every bundle.
     */
    FakeReference register(String className, int ranking, Object service) {
        return register(className, ranking, new HashMap<>(), (context, reference) -> service);
    }

    /**
     * Registers a service factory, called (at most once per bundle while the bundle uses the service) with the
     * requesting bundle context and the service reference to create the service object of a bundle; it may return
     * {@code null}.
     */
    FakeReference register(
            String className,
            int ranking,
            Map<String, Object> properties,
            BiFunction<BundleContext, FakeReference, Object> factory) {
        final FakeReference reference =
                new FakeReference(serviceIds.incrementAndGet(), new String[] {className}, ranking, properties, factory);
        allReferences.add(reference);
        registered.add(reference);
        fire(ServiceEvent.REGISTERED, reference);
        return reference;
    }

    void setRanking(FakeReference reference, int ranking) {
        reference.ranking = ranking;
        fire(ServiceEvent.MODIFIED, reference);
    }

    void unregister(FakeReference reference) {
        fire(ServiceEvent.UNREGISTERING, reference);
        unregisterWindow.run();
        registered.remove(reference);
        reference.valid = false;
    }

    List<FakeReference> registered() {
        return new ArrayList<>(registered);
    }

    List<FakeReference> allReferences() {
        return allReferences;
    }

    /**
     * @return the number of usages of the service by the bundle of the given context
     */
    int usage(BundleContext context, ServiceReference<?> reference) {
        final Integer usage = ((FakeReference) reference).usages.get(context);
        return usage == null ? 0 : usage;
    }

    /**
     * @return the number of usages of the service by all bundles
     */
    int totalUsage(ServiceReference<?> reference) {
        return ((FakeReference) reference)
                .usages.values().stream().mapToInt(Integer::intValue).sum();
    }

    private void fire(int type, FakeReference reference) {
        final ServiceEvent event = new ServiceEvent(type, reference);
        for (final ServiceListener listener : serviceListeners) {
            listener.serviceChanged(event);
        }
    }

    private final class FakeContext {

        private BundleContext context;

        private Bundle bundle;

        private volatile boolean valid = true;

        private void checkValid() {
            if (!valid) {
                throw new IllegalStateException("Invalid BundleContext");
            }
        }

        Object answerBundle(InvocationOnMock invocation) {
            switch (invocation.getMethod().getName()) {
                case "getBundleContext":
                    return valid ? context : null;
                case "getState":
                    return valid ? Bundle.ACTIVE : Bundle.RESOLVED;
                case "toString":
                    return "FakeBundle@" + System.identityHashCode(bundle);
                default:
                    return null;
            }
        }

        @SuppressWarnings("unchecked")
        Object answer(InvocationOnMock invocation) throws InvalidSyntaxException {
            switch (invocation.getMethod().getName()) {
                case "getServiceReferences":
                    checkValid();
                    return getServiceReferences(invocation.getArgument(0), invocation.getArgument(1));
                case "getService":
                    checkValid();
                    return getService(invocation.getArgument(0));
                case "ungetService":
                    checkValid();
                    return ungetService(invocation.getArgument(0));
                case "addServiceListener":
                    serviceListeners.add(invocation.getArgument(0));
                    return null;
                case "removeServiceListener":
                    serviceListeners.remove((ServiceListener) invocation.getArgument(0));
                    return null;
                case "addBundleListener":
                    bundleListeners.add(invocation.getArgument(0));
                    return null;
                case "removeBundleListener":
                    bundleListeners.remove((BundleListener) invocation.getArgument(0));
                    return null;
                case "getBundle":
                    checkValid();
                    return bundle;
                case "toString":
                    return "FakeBundleContext@" + System.identityHashCode(context);
                default:
                    throw new UnsupportedOperationException(
                            invocation.getMethod().getName());
            }
        }

        private ServiceReference<?>[] getServiceReferences(String className, String filter)
                throws InvalidSyntaxException {
            lookups.incrementAndGet();
            final org.osgi.framework.Filter parsed = filter == null ? null : FrameworkUtil.createFilter(filter);
            final List<ServiceReference<?>> result = new ArrayList<>();
            for (final FakeReference reference : registered) {
                if (reference.valid
                        && reference.objectClass[0].equals(className)
                        && (parsed == null || parsed.match(reference))) {
                    result.add(reference);
                }
            }
            // like the framework: null if there is no matching service, otherwise unsorted
            return result.isEmpty() ? null : result.toArray(new ServiceReference<?>[0]);
        }

        private Object getService(FakeReference reference) {
            gets.incrementAndGet();
            final Object[] service = new Object[1];
            if (reference.valid) {
                reference.usages.compute(context, (key, usage) -> {
                    final int count = usage == null ? 0 : usage;
                    final Object object =
                            count == 0 ? reference.factory.apply(context, reference) : reference.objects.get(context);
                    if (object == null) {
                        return usage;
                    }
                    reference.objects.put(context, object);
                    service[0] = object;
                    return count + 1;
                });
            }
            afterGetService.accept(context, reference);
            return service[0];
        }

        private boolean ungetService(FakeReference reference) {
            ungets.incrementAndGet();
            final boolean[] released = new boolean[1];
            reference.usages.compute(context, (key, usage) -> {
                if (usage == null || usage == 0) {
                    violations.add("service " + reference.id + " released more often than it was obtained");
                    return usage;
                }
                released[0] = true;
                if (usage == 1) {
                    reference.objects.remove(context);
                    return null;
                }
                return usage - 1;
            });
            return released[0];
        }
    }

    final class FakeReference implements ServiceReference<Object> {

        final long id;

        private final String[] objectClass;

        private volatile int ranking;

        private final Map<String, Object> properties;

        private final BiFunction<BundleContext, FakeReference, Object> factory;

        private volatile boolean valid = true;

        private final Map<BundleContext, Integer> usages = new ConcurrentHashMap<>();

        private final Map<BundleContext, Object> objects = new ConcurrentHashMap<>();

        FakeReference(
                long id,
                String[] objectClass,
                int ranking,
                Map<String, Object> properties,
                BiFunction<BundleContext, FakeReference, Object> factory) {
            this.id = id;
            this.objectClass = objectClass;
            this.ranking = ranking;
            this.properties = new ConcurrentHashMap<>(properties);
            this.factory = factory;
        }

        int ranking() {
            return ranking;
        }

        boolean isRegistered() {
            return valid;
        }

        @Override
        public Object getProperty(String key) {
            switch (key) {
                case Constants.OBJECTCLASS:
                    return objectClass.clone();
                case Constants.SERVICE_ID:
                    return id;
                case Constants.SERVICE_RANKING:
                    return ranking;
                default:
                    return properties.get(key);
            }
        }

        @Override
        public String[] getPropertyKeys() {
            final List<String> keys = new ArrayList<>(properties.keySet());
            keys.add(Constants.OBJECTCLASS);
            keys.add(Constants.SERVICE_ID);
            keys.add(Constants.SERVICE_RANKING);
            return keys.toArray(new String[0]);
        }

        @Override
        public Dictionary<String, Object> getProperties() {
            final Hashtable<String, Object> result = new Hashtable<>();
            for (final String key : getPropertyKeys()) {
                result.put(key, getProperty(key));
            }
            return result;
        }

        @Override
        public Bundle getBundle() {
            return valid ? registeringBundle : null;
        }

        @Override
        public Bundle[] getUsingBundles() {
            return null;
        }

        @Override
        public boolean isAssignableTo(Bundle bundle, String className) {
            return true;
        }

        /**
         * Like the framework: a higher ranking is greater, for equal rankings the lower service id is greater.
         */
        @Override
        public int compareTo(Object other) {
            final FakeReference that = (FakeReference) other;
            final int thisRanking = this.ranking;
            final int thatRanking = that.ranking;
            if (thisRanking != thatRanking) {
                return thisRanking < thatRanking ? -1 : 1;
            }
            return Long.compare(that.id, this.id);
        }

        @Override
        public String toString() {
            return "FakeReference[id=" + id + ", ranking=" + ranking + ", valid=" + valid + "]";
        }
    }
}
