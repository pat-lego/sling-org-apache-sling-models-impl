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

import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Array;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.function.UnaryOperator;

import org.apache.commons.lang3.StringUtils;
import org.apache.sling.models.annotations.Filter;
import org.apache.sling.models.annotations.injectorspecific.InjectionStrategy;
import org.apache.sling.models.annotations.injectorspecific.OSGiService;
import org.apache.sling.models.spi.AcceptsNullName;
import org.apache.sling.models.spi.DisposalCallback;
import org.apache.sling.models.spi.DisposalCallbackRegistry;
import org.apache.sling.models.spi.Injector;
import org.apache.sling.models.spi.injectorspecific.AbstractInjectAnnotationProcessor2;
import org.apache.sling.models.spi.injectorspecific.InjectAnnotationProcessor2;
import org.apache.sling.models.spi.injectorspecific.StaticInjectAnnotationProcessorFactory;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.osgi.framework.BundleContext;
import org.osgi.framework.Constants;
import org.osgi.framework.InvalidSyntaxException;
import org.osgi.framework.ServiceReference;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.metatype.annotations.Designate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Component(
        property = Constants.SERVICE_RANKING + ":Integer=5000",
        service = {Injector.class, StaticInjectAnnotationProcessorFactory.class, AcceptsNullName.class})
@Designate(ocd = OSGiServiceInjectorConfiguration.class)
public class OSGiServiceInjector implements Injector, StaticInjectAnnotationProcessorFactory, AcceptsNullName {

    private static final Logger log = LoggerFactory.getLogger(OSGiServiceInjector.class);

    /**
     * Environment variable which enables the OSGi service cache when set to {@code true} (case-insensitive), in
     * addition to the {@link OSGiServiceInjectorConfiguration#service_cache_enabled()} configuration property.
     */
    public static final String SERVICE_CACHE_ENABLED_ENV = "SLING_MODELS_OSGI_SERVICE_CACHE_ENABLED";

    private BundleContext bundleContext;

    /**
     * Caches the service references and service objects per requesting bundle context, so that injecting a service
     * does not go through the service registry (and its find hooks) for every model instance.
     */
    private final OSGiServiceCache serviceCache = new OSGiServiceCache();

    /**
     * The cache is only used if enabled and while it is kept up to date by the service and bundle listeners registered
     * on activation. Otherwise every injection looks up and gets the services from the service registry.
     */
    private volatile boolean serviceCacheActive;

    /**
     * Reads environment variables, replaceable for tests.
     */
    private final UnaryOperator<String> environment;

    public OSGiServiceInjector() {
        this(System::getenv);
    }

    /**
     * @param environment returns the value of an environment variable or {@code null} if it is not set
     */
    OSGiServiceInjector(@NotNull UnaryOperator<String> environment) {
        this.environment = environment;
    }

    @Override
    public @NotNull String getName() {
        return "osgi-services";
    }

    @Activate
    public void activate(BundleContext ctx, OSGiServiceInjectorConfiguration config) {
        this.bundleContext = ctx;
        if (isServiceCacheEnabled(config, environment)) {
            // keep the service cache up to date: evict on every service change and when a model bundle stops
            ctx.addServiceListener(serviceCache);
            ctx.addBundleListener(serviceCache);
            this.serviceCacheActive = true;
            log.info("OSGi service cache enabled for injecting OSGi services into Sling Models");
        } else {
            log.debug("OSGi service cache disabled, OSGi services are looked up for every Sling Model");
        }
    }

    @Deactivate
    public void deactivate() {
        if (serviceCacheActive) {
            this.serviceCacheActive = false;
            if (bundleContext != null) {
                try {
                    bundleContext.removeServiceListener(serviceCache);
                    bundleContext.removeBundleListener(serviceCache);
                } catch (IllegalStateException e) {
                    // the bundle context is no longer valid, the framework already removed the listeners
                }
            }
        }
        serviceCache.close();
    }

    /**
     * @return {@code true} if the service cache is used by this injector
     */
    public boolean isServiceCacheActive() {
        return serviceCacheActive;
    }

    /**
     * @return the service cache (for tests)
     */
    OSGiServiceCache getServiceCache() {
        return serviceCache;
    }

    /**
     * The service cache is enabled by the configuration property or by setting the environment variable
     * {@value #SERVICE_CACHE_ENABLED_ENV} to {@code true}. It is disabled by default.
     *
     * @param config the configuration, may be {@code null}
     * @param environment returns the value of an environment variable
     * @return {@code true} if the service cache is enabled
     */
    static boolean isServiceCacheEnabled(
            @Nullable OSGiServiceInjectorConfiguration config, @NotNull UnaryOperator<String> environment) {
        if (config != null && config.service_cache_enabled()) {
            return true;
        }
        final String value = environment.apply(SERVICE_CACHE_ENABLED_ENV);
        return value != null && Boolean.parseBoolean(value.trim());
    }

    @Override
    public Object getValue(
            @NotNull Object adaptable,
            String name,
            @NotNull Type type,
            @NotNull AnnotatedElement element,
            @NotNull DisposalCallbackRegistry callbackRegistry) {
        return getValue(adaptable, name, type, element, callbackRegistry, bundleContext);
    }

    /**
     * @param adaptable Adaptable
     * @param name Name
     * @param type Type
     * @param element Element
     * @param callbackRegistry Callback registry
     * @param modelContext Model context
     * @return Object
     */
    @SuppressWarnings({"null", "unused"})
    public Object getValue(
            @NotNull Object adaptable,
            String name,
            @NotNull Type type,
            @NotNull AnnotatedElement element,
            @NotNull DisposalCallbackRegistry callbackRegistry,
            @Nullable BundleContext modelContext) {
        OSGiService annotation = element.getAnnotation(OSGiService.class);
        String filterString = null;
        if (annotation != null) {
            if (StringUtils.isNotBlank(annotation.filter())) {
                filterString = annotation.filter();
            }
        } else {
            Filter filter = element.getAnnotation(Filter.class);
            if (filter != null) {
                filterString = filter.value();
            }
        }
        return getValue(
                adaptable, type, filterString, callbackRegistry, modelContext == null ? bundleContext : modelContext);
    }

    /**
     * @return the references to the matching services, highest service ranking first (never {@code null})
     */
    private ServiceReference<?>[] getServiceReferences(BundleContext modelContext, Class<?> type, String filter)
            throws InvalidSyntaxException {
        if (serviceCacheActive) {
            return serviceCache.getServiceReferences(modelContext, type.getName(), filter);
        }
        return OSGiServiceCache.lookup(modelContext, type.getName(), filter);
    }

    /**
     * Gets the service object of a reference for the model.
     * <p>
     * Services of registered references are taken from the cache, which obtains them once per model bundle and
     * releases them when they are unregistered. Otherwise (cache not active, or the service of a cached reference was
     * unregistered in the meantime) the service is obtained from the framework for this model only and released by a
     * disposal callback, as without the cache.
     *
     * @return the service object or {@code null} if the service is not available
     */
    private Object getServiceObject(
            ServiceReference<?> ref,
            Class<?> type,
            BundleContext modelContext,
            List<ServiceReference<?>> refsToRelease) {
        final boolean registered = ref.getBundle() != null;
        if (serviceCacheActive && registered) {
            final Object service = serviceCache.getService(modelContext, ref);
            if (service != null) {
                return service;
            }
        } else {
            final Object service = modelContext.getService(ref);
            if (service != null) {
                refsToRelease.add(ref);
                return service;
            }
        }
        if (serviceCacheActive && !registered) {
            // A cached reference can outlive its service: the UNREGISTERING event (which evicts the cache) is
            // delivered before the service is removed from the registry, so a concurrent lookup can cache it again.
            // Evict it once found, so that the next injection looks up the current services.
            serviceCache.invalidate(type.getName());
            serviceCache.release(ref);
        }
        return null;
    }

    private static void addDisposalCallback(
            DisposalCallbackRegistry callbackRegistry,
            List<ServiceReference<?>> refsToRelease,
            BundleContext modelContext) {
        if (!refsToRelease.isEmpty()) {
            callbackRegistry.addDisposalCallback(
                    new Callback(refsToRelease.toArray(new ServiceReference[refsToRelease.size()]), modelContext));
        }
    }

    private <T> Object getService(
            Object adaptable,
            Class<T> type,
            String filter,
            DisposalCallbackRegistry callbackRegistry,
            BundleContext modelContext) {
        // cannot use SlingScriptHelper since it does not support ordering by service ranking due to
        // https://issues.apache.org/jira/browse/SLING-5665
        try {
            // references are sorted by reverse service ranking (highest first)
            final List<ServiceReference<?>> refsToRelease = new ArrayList<>(1);
            for (final ServiceReference<?> ref : getServiceReferences(modelContext, type, filter)) {
                final Object obj = getServiceObject(ref, type, modelContext, refsToRelease);
                if (obj != null) {
                    addDisposalCallback(callbackRegistry, refsToRelease, modelContext);
                    return obj;
                }
            }
        } catch (InvalidSyntaxException e) {
            log.error("invalid filter expression", e);
        }
        return null;
    }

    private <T> Object[] getServices(
            Object adaptable,
            Class<T> type,
            String filter,
            DisposalCallbackRegistry callbackRegistry,
            BundleContext modelContext) {
        // cannot use SlingScriptHelper since it does not support ordering by service ranking due to
        // https://issues.apache.org/jira/browse/SLING-5665
        try {
            // references are sorted by reverse service ranking (highest first)
            ServiceReference<?>[] references = getServiceReferences(modelContext, type, filter);
            if (references.length > 0) {
                List<Object> services = new ArrayList<>();
                List<ServiceReference<?>> refsToRelease = new ArrayList<>();
                for (ServiceReference<?> ref : references) {
                    Object service = getServiceObject(ref, type, modelContext, refsToRelease);
                    if (service != null) {
                        services.add(service);
                    }
                }
                if (!services.isEmpty()) {
                    addDisposalCallback(callbackRegistry, refsToRelease, modelContext);
                    return services.toArray(new Object[services.size()]);
                }
            }
        } catch (InvalidSyntaxException e) {
            log.error("invalid filter expression", e);
        }
        return null;
    }

    private Object getValue(
            Object adaptable,
            Type type,
            String filterString,
            DisposalCallbackRegistry callbackRegistry,
            BundleContext modelContext) {
        if (type instanceof Class) {
            Class<?> injectedClass = (Class<?>) type;
            if (injectedClass.isArray()) {
                Object[] services = getServices(
                        adaptable, injectedClass.getComponentType(), filterString, callbackRegistry, modelContext);
                if (services == null) {
                    return null;
                }
                Object arr = Array.newInstance(injectedClass.getComponentType(), services.length);
                for (int i = 0; i < services.length; i++) {
                    Array.set(arr, i, services[i]);
                }
                return arr;
            } else {
                return getService(adaptable, injectedClass, filterString, callbackRegistry, modelContext);
            }
        } else if (type instanceof ParameterizedType) {
            ParameterizedType ptype = (ParameterizedType) type;
            if (ptype.getActualTypeArguments().length != 1) {
                return null;
            }
            Class<?> collectionType = (Class<?>) ptype.getRawType();
            if (!(collectionType.equals(Collection.class) || collectionType.equals(List.class))) {
                return null;
            }

            Class<?> serviceType = (Class<?>) ptype.getActualTypeArguments()[0];
            Object[] services = getServices(adaptable, serviceType, filterString, callbackRegistry, modelContext);
            if (services == null) {
                return null;
            }
            return Arrays.asList(services);
        } else {
            log.warn("Cannot handle type {}", type);
            return null;
        }
    }

    private static class Callback implements DisposalCallback {
        private final ServiceReference<?>[] refs;
        private final BundleContext context;

        public Callback(ServiceReference<?>[] refs, BundleContext context) {
            this.refs = refs;
            this.context = context;
        }

        @Override
        public void onDisposed() {
            if (refs != null) {
                for (ServiceReference<?> ref : refs) {
                    try {
                        context.ungetService(ref);
                    } catch (IllegalStateException exception) {
                        // SLING-11132 - This exception is expected when BundleContext is no longer valid.
                    }
                }
            }
        }
    }

    @Override
    @SuppressWarnings({"unused", "null"})
    public InjectAnnotationProcessor2 createAnnotationProcessor(AnnotatedElement element) {
        // check if the element has the expected annotation
        OSGiService annotation = element.getAnnotation(OSGiService.class);
        if (annotation != null) {
            return new OSGiServiceAnnotationProcessor(annotation);
        }
        return null;
    }

    private static class OSGiServiceAnnotationProcessor extends AbstractInjectAnnotationProcessor2 {

        private final OSGiService annotation;

        public OSGiServiceAnnotationProcessor(OSGiService annotation) {
            this.annotation = annotation;
        }

        @Override
        public InjectionStrategy getInjectionStrategy() {
            return annotation.injectionStrategy();
        }

        @Override
        @SuppressWarnings("deprecation")
        public Boolean isOptional() {
            return annotation.optional();
        }
    }
}
