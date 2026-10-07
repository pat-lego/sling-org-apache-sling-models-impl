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

import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.osgi.framework.AllServiceListener;
import org.osgi.framework.BundleContext;
import org.osgi.framework.BundleEvent;
import org.osgi.framework.Constants;
import org.osgi.framework.InvalidSyntaxException;
import org.osgi.framework.ServiceEvent;
import org.osgi.framework.ServiceReference;
import org.osgi.framework.SynchronousBundleListener;

/**
 * Caches the OSGi service lookups of the {@link OSGiServiceInjector}.
 * <p>
 * Without a cache every injection of an OSGi service calls {@link BundleContext#getServiceReferences(String, String)}
 * and {@link BundleContext#getService(ServiceReference)} (and later {@link BundleContext#ungetService(ServiceReference)}
 * from a disposal callback). Each of these calls goes through the framework service registry:
 * {@code getServiceReferences} also gets, calls and releases every registered
 * {@link org.osgi.framework.hooks.service.FindHook}, and getting / releasing a service updates the usage count of the
 * service for the requesting bundle. When many models are instantiated concurrently, request threads contend on the
 * service registry for every injected service.
 * <p>
 * This cache keeps
 * <ul>
 * <li>the service references per requesting bundle context, service class and filter, sorted by descending service
 * ranking. All lookups of a service class are evicted on any {@link ServiceEvent} for a service registered under that
 * class. Empty results are cached as well, so repeated lookups of absent (optional) services do not hit the registry
 * either;</li>
 * <li>the service objects per requesting bundle context and service reference. A service object is obtained once
 * (so the requesting bundle keeps a single usage of the service, like a static Declarative Services reference) and
 * released when the service is unregistered, when the requesting bundle is stopping or when the cache is cleared.</li>
 * </ul>
 */
final class OSGiServiceCache implements AllServiceListener, SynchronousBundleListener {

    private static final ServiceReference<?>[] NO_REFERENCES = new ServiceReference<?>[0];

    /**
     * Service class name =>(requesting bundle context, filter) => references, highest ranking first.
     */
    private final ConcurrentMap<String, ConcurrentMap<LookupKey, ServiceReference<?>[]>> references =
            new ConcurrentHashMap<>();

    /**
     * Service reference &rarr; requesting bundle context &rarr; service object obtained by that bundle context.
     */
    private final ConcurrentMap<ServiceReference<?>, ConcurrentMap<BundleContext, Object>> services =
            new ConcurrentHashMap<>();

    /**
     * Returns the references to the services registered under the given class and matching the given filter, as
     * visible to the given bundle context, sorted by descending service ranking (highest ranking first).
     *
     * @param context the bundle context used to look up the services
     * @param className the name of the service class
     * @param filter the filter expression, may be {@code null}
     * @return the references, empty if there is no matching service; callers must not modify the returned array
     * @throws InvalidSyntaxException if the filter is invalid (such results are not cached)
     */
    @NotNull
    ServiceReference<?>[] getServiceReferences(
            @NotNull BundleContext context, @NotNull String className, @Nullable String filter)
            throws InvalidSyntaxException {
        // Resolve the per-class map before querying the registry: if a service event evicts it while the registry is
        // queried, the (possibly outdated) result ends up in the evicted map and is never returned by a later call.
        ConcurrentMap<LookupKey, ServiceReference<?>[]> byFilter = references.get(className);
        if (byFilter == null) {
            byFilter = references.computeIfAbsent(className, name -> new ConcurrentHashMap<>());
        }
        final LookupKey key = new LookupKey(context, filter);
        ServiceReference<?>[] result = byFilter.get(key);
        if (result == null) {
            result = lookup(context, className, filter);
            byFilter.putIfAbsent(key, result);
        }
        return result;
    }

    /**
     * Looks up the references in the service registry, without caching.
     *
     * @param context the bundle context used to look up the services
     * @param className the name of the service class
     * @param filter the filter expression, may be {@code null}
     * @return the references sorted by descending service ranking, empty if there is no matching service
     * @throws InvalidSyntaxException if the filter is invalid
     */
    @NotNull
    static ServiceReference<?>[] lookup(
            @NotNull BundleContext context, @NotNull String className, @Nullable String filter)
            throws InvalidSyntaxException {
        final ServiceReference<?>[] result = context.getServiceReferences(className, filter);
        if (result == null || result.length == 0) {
            return NO_REFERENCES;
        }
        // sort by reverse service ranking (highest first) (see ServiceReference.compareTo)
        Arrays.sort(result, Collections.reverseOrder());
        return result;
    }

    /**
     * Returns the service object for the given reference, as obtained by the given bundle context. The service object
     * is obtained from the framework on the first call only; it is released by the cache (not by the caller) once the
     * service is unregistered.
     *
     * @param context the bundle context used to get the service
     * @param reference the reference of a registered service
     * @return the service object or {@code null} if the service is not available (e.g. unregistered, or a service
     *         factory returned {@code null}); {@code null} results are not cached
     */
    @Nullable
    Object getService(@NotNull BundleContext context, @NotNull ServiceReference<?> reference) {
        final ConcurrentMap<BundleContext, Object> byContext = services.get(reference);
        final Object cached = byContext == null ? null : byContext.get(context);
        if (cached != null) {
            return cached;
        }
        // not called from within a compute function: service factories may inject services themselves
        final Object service = context.getService(reference);
        if (service == null) {
            return null;
        }
        final Object existing = services.computeIfAbsent(reference, ref -> new ConcurrentHashMap<>())
                .putIfAbsent(context, service);
        if (existing != null) {
            // another thread obtained and cached the service concurrently, release the additional usage
            ungetService(context, reference);
            return existing;
        }
        if (reference.getBundle() == null) {
            // unregistered while it was obtained, possibly after the UNREGISTERING event evicted it: do not keep it
            release(reference);
        }
        return service;
    }

    /**
     * Evicts all cached references for the given service class.
     *
     * @param className the name of the service class
     */
    void invalidate(@NotNull String className) {
        references.remove(className);
    }

    /**
     * Evicts all cached references and releases all cached service objects.
     */
    void clear() {
        references.clear();
        for (final ServiceReference<?> reference : services.keySet()) {
            release(reference);
        }
    }

    /**
     * @return the number of cached reference lookups
     */
    int referencesSize() {
        return references.values().stream().mapToInt(Map::size).sum();
    }

    /**
     * @return the number of cached service objects
     */
    int servicesSize() {
        return services.values().stream().mapToInt(Map::size).sum();
    }

    @Override
    public void serviceChanged(ServiceEvent event) {
        final ServiceReference<?> reference = event.getServiceReference();
        final Object objectClass = reference.getProperty(Constants.OBJECTCLASS);
        if (objectClass instanceof String[]) {
            for (final String className : (String[]) objectClass) {
                references.remove(className);
            }
        }
        // a modified service keeps its service objects, only its (ranking, filter matching) references may change
        if (event.getType() == ServiceEvent.UNREGISTERING) {
            release(reference);
        }
    }

    @Override
    public void bundleChanged(BundleEvent event) {
        if (event.getType() == BundleEvent.STOPPING) {
            final BundleContext stopping = event.getBundle().getBundleContext();
            if (stopping != null) {
                for (final ConcurrentMap<LookupKey, ServiceReference<?>[]> byFilter : references.values()) {
                    byFilter.keySet().removeIf(key -> key.context == stopping);
                }
                for (final Map.Entry<ServiceReference<?>, ConcurrentMap<BundleContext, Object>> entry :
                        services.entrySet()) {
                    if (entry.getValue().remove(stopping) != null) {
                        ungetService(stopping, entry.getKey());
                    }
                }
            }
        }
    }

    /**
     * Releases the cached service objects of the given reference.
     *
     * @param reference the service reference
     */
    void release(@NotNull ServiceReference<?> reference) {
        final ConcurrentMap<BundleContext, Object> byContext = services.remove(reference);
        if (byContext != null) {
            for (final BundleContext context : byContext.keySet()) {
                ungetService(context, reference);
            }
        }
    }

    private static void ungetService(@NotNull BundleContext context, @NotNull ServiceReference<?> reference) {
        try {
            context.ungetService(reference);
        } catch (IllegalStateException e) {
            // SLING-11132 - the bundle context is no longer valid, the framework already released the service
        }
    }

    private static final class LookupKey {

        private final BundleContext context;

        private final String filter;

        private final int hashCode;

        LookupKey(@NotNull BundleContext context, @Nullable String filter) {
            this.context = context;
            this.filter = filter;
            this.hashCode = 31 * System.identityHashCode(context) + Objects.hashCode(filter);
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (!(obj instanceof LookupKey)) {
                return false;
            }
            final LookupKey other = (LookupKey) obj;
            // bundle contexts are compared by identity: a restarted bundle gets a new context
            return context == other.context && Objects.equals(filter, other.filter);
        }

        @Override
        public int hashCode() {
            return hashCode;
        }
    }
}
