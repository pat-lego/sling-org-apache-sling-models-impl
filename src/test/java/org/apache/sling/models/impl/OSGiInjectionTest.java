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
package org.apache.sling.models.impl;

import java.util.Arrays;
import java.util.Collections;
import java.util.Dictionary;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.scripting.SlingBindings;
import org.apache.sling.api.scripting.SlingScriptHelper;
import org.apache.sling.models.impl.injectors.OSGiServiceInjector;
import org.apache.sling.models.impl.injectors.OSGiServiceInjectorConfigs;
import org.apache.sling.models.testmodels.classes.ArrayOSGiModel;
import org.apache.sling.models.testmodels.classes.CollectionOSGiModel;
import org.apache.sling.models.testmodels.classes.ListOSGiModel;
import org.apache.sling.models.testmodels.classes.OptionalArrayOSGiModel;
import org.apache.sling.models.testmodels.classes.OptionalListOSGiModel;
import org.apache.sling.models.testmodels.classes.RequestOSGiModel;
import org.apache.sling.models.testmodels.classes.SetOSGiModel;
import org.apache.sling.models.testmodels.classes.SimpleOSGiModel;
import org.apache.sling.models.testmodels.interfaces.ServiceInterface;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.BundleListener;
import org.osgi.framework.Constants;
import org.osgi.framework.ServiceEvent;
import org.osgi.framework.ServiceListener;
import org.osgi.framework.ServiceReference;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OSGiInjectionTest {
    private ModelAdapterFactory factory;

    @Mock
    private BundleContext bundleContext;

    @Mock
    private SlingScriptHelper helper;

    private SlingBindings bindings = new SlingBindings();

    private OSGiServiceInjector injectorFactory;

    @BeforeEach
    void setup() {
        factory = AdapterFactoryTest.createModelAdapterFactory(bundleContext);

        injectorFactory = new OSGiServiceInjector();
        injectorFactory.activate(bundleContext, OSGiServiceInjectorConfigs.config(true));
        factory.injectors = Collections.singletonList(injectorFactory);

        bindings.setSling(helper);
        factory.adapterImplementations.addClassesAsAdapterAndImplementation(
                SimpleOSGiModel.class,
                ListOSGiModel.class,
                RequestOSGiModel.class,
                ArrayOSGiModel.class,
                SetOSGiModel.class,
                OptionalListOSGiModel.class,
                org.apache.sling.models.testmodels.classes.constructorinjection.ListOSGiModel.class,
                org.apache.sling.models.testmodels.classes.constructorinjection.SimpleOSGiModel.class,
                CollectionOSGiModel.class,
                OptionalArrayOSGiModel.class);
    }

    @Test
    @SuppressWarnings({"null"})
    void testSimpleOSGiModelField() throws Exception {
        ServiceReference<?> ref = mock(ServiceReference.class);
        ServiceInterface service = mock(ServiceInterface.class);
        when(bundleContext.getServiceReferences(ServiceInterface.class.getName(), null))
                .thenReturn(new ServiceReference[] {ref});
        doReturn(service).when(bundleContext).getService(ref);

        Resource res = mock(Resource.class);

        SimpleOSGiModel model = factory.getAdapter(res, SimpleOSGiModel.class);
        assertNotNull(model);
        assertNotNull(model.getService());
        assertEquals(service, model.getService());

        verifyNoMoreInteractions(res);
    }

    @Test
    @SuppressWarnings({"null"})
    void testListOSGiModelField() throws Exception {
        ServiceReference<?> ref1 = mock(ServiceReference.class);
        ServiceInterface service1 = mock(ServiceInterface.class);
        doReturn(service1).when(bundleContext).getService(ref1);
        ServiceReference<?> ref2 = mock(ServiceReference.class);
        ServiceInterface service2 = mock(ServiceInterface.class);
        doReturn(service2).when(bundleContext).getService(ref2);

        when(bundleContext.getServiceReferences(ServiceInterface.class.getName(), null))
                .thenReturn(new ServiceReference[] {ref1, ref2});

        Resource res = mock(Resource.class);

        ListOSGiModel model = factory.getAdapter(res, ListOSGiModel.class);
        assertNotNull(model);
        assertNotNull(model.getServices());
        // the order on those is non deterministic as the ServiceReference.compareTo() is always returning 0
        // the real order is tested in the IT
        assertThat(model.getServices(), Matchers.containsInAnyOrder(service1, service2));

        verifyNoMoreInteractions(res);
    }

    @Test
    @SuppressWarnings({"null"})
    void testArrayOSGiModelField() throws Exception {
        ServiceReference<?> ref1 = mock(ServiceReference.class);
        ServiceInterface service1 = mock(ServiceInterface.class);
        doReturn(service1).when(bundleContext).getService(ref1);
        ServiceReference<?> ref2 = mock(ServiceReference.class);
        ServiceInterface service2 = mock(ServiceInterface.class);
        doReturn(service2).when(bundleContext).getService(ref2);

        when(bundleContext.getServiceReferences(ServiceInterface.class.getName(), null))
                .thenReturn(new ServiceReference[] {ref1, ref2});

        Resource res = mock(Resource.class);

        ArrayOSGiModel model = factory.getAdapter(res, ArrayOSGiModel.class);
        assertNotNull(model);
        assertNotNull(model.getServices());
        // the order on those is non deterministic as the ServiceReference.compareTo() is always returning 0
        // the real order is tested in the IT
        assertThat(Arrays.asList(model.getServices()), Matchers.containsInAnyOrder(service1, service2));

        verifyNoMoreInteractions(res);
    }

    @Test
    @SuppressWarnings("null")
    void testOptionalArrayOSGiModelField() {

        Resource res = mock(Resource.class);

        OptionalArrayOSGiModel model = factory.getAdapter(res, OptionalArrayOSGiModel.class);
        assertNotNull(model);
        assertNull(model.getServices());

        verifyNoMoreInteractions(res);
    }

    @Test
    @SuppressWarnings("null")
    void testOptionalListOSGiModelField() {
        Resource res = mock(Resource.class);

        OptionalListOSGiModel model = factory.getAdapter(res, OptionalListOSGiModel.class);
        assertNotNull(model);
        assertNull(model.getServices());

        verifyNoMoreInteractions(res);
    }

    @Test
    @SuppressWarnings({"null"})
    void testCollectionOSGiModelField() throws Exception {
        ServiceReference<?> ref1 = mock(ServiceReference.class);
        ServiceInterface service1 = mock(ServiceInterface.class);
        doReturn(service1).when(bundleContext).getService(ref1);
        ServiceReference<?> ref2 = mock(ServiceReference.class);
        ServiceInterface service2 = mock(ServiceInterface.class);
        doReturn(service2).when(bundleContext).getService(ref2);

        when(bundleContext.getServiceReferences(ServiceInterface.class.getName(), null))
                .thenReturn(new ServiceReference[] {ref1, ref2});

        Resource res = mock(Resource.class);

        CollectionOSGiModel model = factory.getAdapter(res, CollectionOSGiModel.class);
        assertNotNull(model);
        assertNotNull(model.getServices());
        // the order on those is non deterministic as the ServiceReference.compareTo() is always returning 0
        // the real order is tested in the IT
        assertThat(model.getServices(), Matchers.containsInAnyOrder(service1, service2));

        verifyNoMoreInteractions(res);
    }

    @Test
    @SuppressWarnings({"unused", "null"})
    void testSetOSGiModelField() throws Exception {
        ServiceReference<?> ref1 = mock(ServiceReference.class);
        ServiceInterface service1 = mock(ServiceInterface.class);
        lenient().doReturn(service1).when(bundleContext).getService(ref1);
        ServiceReference<?> ref2 = mock(ServiceReference.class);
        ServiceInterface service2 = mock(ServiceInterface.class);
        lenient().doReturn(service2).when(bundleContext).getService(ref2);

        lenient()
                .when(bundleContext.getServiceReferences(ServiceInterface.class.getName(), null))
                .thenReturn(new ServiceReference[] {ref1, ref2});

        Resource res = mock(Resource.class);

        SetOSGiModel model = factory.getAdapter(res, SetOSGiModel.class);
        assertNull(model);

        verify(bundleContext).registerService(eq(Runnable.class), eq(factory), any(Dictionary.class));
        // one bundle listener from the ModelAdapterFactory, one from the OSGiServiceInjector reference cache
        verify(bundleContext, times(2)).addBundleListener(any(BundleListener.class));
        verify(bundleContext).addServiceListener(any(ServiceListener.class));
        verify(bundleContext).registerService(eq(Object.class), any(Object.class), any(Dictionary.class));
        verify(bundleContext).getBundles();
        verify(bundleContext).getBundle();
        verifyNoMoreInteractions(res, bundleContext);
    }

    @Test
    @SuppressWarnings({"null"})
    void testServicesAreCachedAcrossModelInstances() throws Exception {
        ServiceReference<?> ref = registeredReference();
        ServiceInterface service = mock(ServiceInterface.class);
        when(bundleContext.getServiceReferences(ServiceInterface.class.getName(), null))
                .thenReturn(new ServiceReference[] {ref});
        doReturn(service).when(bundleContext).getService(ref);

        for (int i = 0; i < 3; i++) {
            SimpleOSGiModel model = factory.getAdapter(mock(Resource.class), SimpleOSGiModel.class);
            assertNotNull(model);
            assertEquals(service, model.getService());
        }

        // the registry is queried and the service obtained once, and the service is kept (not released per model)
        verify(bundleContext, times(1)).getServiceReferences(ServiceInterface.class.getName(), null);
        verify(bundleContext, times(1)).getService(ref);
        verify(bundleContext, never()).ungetService(ref);
    }

    @Test
    @SuppressWarnings({"null"})
    void testCachedServicesAreInjectedIntoCollections() throws Exception {
        ServiceReference<?> ref1 = registeredReference();
        ServiceInterface service1 = mock(ServiceInterface.class);
        doReturn(service1).when(bundleContext).getService(ref1);
        ServiceReference<?> ref2 = registeredReference();
        ServiceInterface service2 = mock(ServiceInterface.class);
        doReturn(service2).when(bundleContext).getService(ref2);
        when(bundleContext.getServiceReferences(ServiceInterface.class.getName(), null))
                .thenReturn(new ServiceReference[] {ref1, ref2});

        for (int i = 0; i < 2; i++) {
            ListOSGiModel model = factory.getAdapter(mock(Resource.class), ListOSGiModel.class);
            assertNotNull(model);
            assertThat(model.getServices(), Matchers.containsInAnyOrder(service1, service2));
        }

        verify(bundleContext, times(1)).getServiceReferences(ServiceInterface.class.getName(), null);
        verify(bundleContext, times(1)).getService(ref1);
        verify(bundleContext, times(1)).getService(ref2);
    }

    @Test
    @SuppressWarnings({"null"})
    void testServiceEventEvictsCachedServiceReferences() throws Exception {
        ArgumentCaptor<ServiceListener> listener = ArgumentCaptor.forClass(ServiceListener.class);
        verify(bundleContext).addServiceListener(listener.capture());

        ServiceReference<?> ref1 = registeredReference();
        ServiceInterface service1 = mock(ServiceInterface.class);
        doReturn(service1).when(bundleContext).getService(ref1);
        ServiceReference<?> ref2 = registeredReference();
        ServiceInterface service2 = mock(ServiceInterface.class);
        doReturn(service2).when(bundleContext).getService(ref2);
        when(bundleContext.getServiceReferences(ServiceInterface.class.getName(), null))
                .thenReturn(new ServiceReference[] {ref1})
                .thenReturn(new ServiceReference[] {ref2});

        assertEquals(
                service1,
                factory.getAdapter(mock(Resource.class), SimpleOSGiModel.class).getService());
        assertEquals(
                service1,
                factory.getAdapter(mock(Resource.class), SimpleOSGiModel.class).getService());

        // a new service is registered under the injected service interface
        when(ref2.getProperty(Constants.OBJECTCLASS)).thenReturn(new String[] {ServiceInterface.class.getName()});
        listener.getValue().serviceChanged(new ServiceEvent(ServiceEvent.REGISTERED, ref2));

        assertEquals(
                service2,
                factory.getAdapter(mock(Resource.class), SimpleOSGiModel.class).getService());
        verify(bundleContext, times(2)).getServiceReferences(ServiceInterface.class.getName(), null);
        verify(bundleContext, times(1)).getService(ref1);
    }

    @Test
    @SuppressWarnings({"null"})
    void testUnregisteringServiceReleasesCachedService() throws Exception {
        ArgumentCaptor<ServiceListener> listener = ArgumentCaptor.forClass(ServiceListener.class);
        verify(bundleContext).addServiceListener(listener.capture());

        ServiceReference<?> ref = registeredReference();
        ServiceInterface service = mock(ServiceInterface.class);
        when(bundleContext.getServiceReferences(ServiceInterface.class.getName(), null))
                .thenReturn(new ServiceReference[] {ref})
                .thenReturn(null);
        doReturn(service).when(bundleContext).getService(ref);
        assertNotNull(factory.getAdapter(mock(Resource.class), SimpleOSGiModel.class));

        when(ref.getProperty(Constants.OBJECTCLASS)).thenReturn(new String[] {ServiceInterface.class.getName()});
        listener.getValue().serviceChanged(new ServiceEvent(ServiceEvent.UNREGISTERING, ref));

        verify(bundleContext).ungetService(ref);
        assertNull(factory.getAdapter(mock(Resource.class), SimpleOSGiModel.class));
        verify(bundleContext, times(2)).getServiceReferences(ServiceInterface.class.getName(), null);
    }

    @Test
    @SuppressWarnings({"null"})
    void testUnregisteredCachedServiceReferenceIsEvicted() throws Exception {
        ServiceReference<?> ref = registeredReference();
        ServiceInterface service = mock(ServiceInterface.class);
        when(bundleContext.getServiceReferences(ServiceInterface.class.getName(), null))
                .thenReturn(new ServiceReference[] {ref})
                .thenReturn(null);
        doReturn(service).when(bundleContext).getService(ref);
        assertNotNull(factory.getAdapter(mock(Resource.class), SimpleOSGiModel.class));

        // the service is unregistered, but its reference is still cached (e.g. looked up while it was unregistering)
        doReturn(null).when(ref).getBundle();
        doReturn(null).when(bundleContext).getService(ref);

        assertNull(factory.getAdapter(mock(Resource.class), SimpleOSGiModel.class));
        // the cached service object was released and the reference evicted
        verify(bundleContext).ungetService(ref);
        assertNull(factory.getAdapter(mock(Resource.class), SimpleOSGiModel.class));
        verify(bundleContext, times(2)).getServiceReferences(ServiceInterface.class.getName(), null);
    }

    @Test
    @SuppressWarnings({"null"})
    void testDeactivationReleasesCachedServicesAndStopsCaching() throws Exception {
        ServiceReference<?> ref = registeredReference();
        ServiceInterface service = mock(ServiceInterface.class);
        when(bundleContext.getServiceReferences(ServiceInterface.class.getName(), null))
                .thenReturn(new ServiceReference[] {ref});
        doReturn(service).when(bundleContext).getService(ref);
        assertNotNull(factory.getAdapter(mock(Resource.class), SimpleOSGiModel.class));

        injectorFactory.deactivate();
        verify(bundleContext).removeServiceListener(any(ServiceListener.class));
        verify(bundleContext).removeBundleListener(any(BundleListener.class));
        verify(bundleContext).ungetService(ref);

        // without the cache, every model looks up and gets the service (released by its disposal callback)
        assertNotNull(factory.getAdapter(mock(Resource.class), SimpleOSGiModel.class));
        assertNotNull(factory.getAdapter(mock(Resource.class), SimpleOSGiModel.class));
        verify(bundleContext, times(3)).getServiceReferences(ServiceInterface.class.getName(), null);
        verify(bundleContext, times(3)).getService(ref);
    }

    private static ServiceReference<?> registeredReference() {
        ServiceReference<?> ref = mock(ServiceReference.class);
        // ServiceReference.getBundle() returns the registering bundle as long as the service is registered
        lenient().doReturn(mock(Bundle.class)).when(ref).getBundle();
        return ref;
    }

    @Test
    @SuppressWarnings({"null"})
    void testSimpleOSGiModelConstructor() throws Exception {
        ServiceReference<?> ref = mock(ServiceReference.class);
        ServiceInterface service = mock(ServiceInterface.class);
        when(bundleContext.getServiceReferences(ServiceInterface.class.getName(), null))
                .thenReturn(new ServiceReference[] {ref});
        doReturn(service).when(bundleContext).getService(ref);

        Resource res = mock(Resource.class);

        org.apache.sling.models.testmodels.classes.constructorinjection.SimpleOSGiModel model = factory.getAdapter(
                res, org.apache.sling.models.testmodels.classes.constructorinjection.SimpleOSGiModel.class);
        assertNotNull(model);
        assertNotNull(model.getService());
        assertEquals(service, model.getService());

        verifyNoMoreInteractions(res);
    }

    @Test
    @SuppressWarnings({"null"})
    void testListOSGiModelConstructor() throws Exception {
        ServiceReference<?> ref1 = mock(ServiceReference.class);
        ServiceInterface service1 = mock(ServiceInterface.class);
        doReturn(service1).when(bundleContext).getService(ref1);
        ServiceReference<?> ref2 = mock(ServiceReference.class);
        ServiceInterface service2 = mock(ServiceInterface.class);
        doReturn(service2).when(bundleContext).getService(ref2);

        when(bundleContext.getServiceReferences(ServiceInterface.class.getName(), null))
                .thenReturn(new ServiceReference[] {ref1, ref2});

        Resource res = mock(Resource.class);

        org.apache.sling.models.testmodels.classes.constructorinjection.ListOSGiModel model = factory.getAdapter(
                res, org.apache.sling.models.testmodels.classes.constructorinjection.ListOSGiModel.class);
        assertNotNull(model);
        assertNotNull(model.getServices());
        // the order on those is non deterministic as the ServiceReference.compareTo() is always returning 0
        // the real order is tested in the IT
        assertThat(model.getServices(), Matchers.containsInAnyOrder(service1, service2));

        verifyNoMoreInteractions(res);
    }
}
