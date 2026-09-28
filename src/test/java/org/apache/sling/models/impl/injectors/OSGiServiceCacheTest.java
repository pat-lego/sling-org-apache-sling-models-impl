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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.BundleEvent;
import org.osgi.framework.Constants;
import org.osgi.framework.InvalidSyntaxException;
import org.osgi.framework.ServiceEvent;
import org.osgi.framework.ServiceReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OSGiServiceCacheTest {

    private static final String SERVICE_CLASS = "org.example.Service";

    private static final String OTHER_SERVICE_CLASS = "org.example.OtherService";

    private static final String FILTER = "(name=value)";

    @Mock
    private BundleContext context;

    @Mock
    private BundleContext otherContext;

    @Mock
    private Bundle registeringBundle;

    private final OSGiServiceCache cache = new OSGiServiceCache();

    // ---- service references

    @Test
    void testReferencesAreLookedUpOncePerContextClassAndFilter() throws InvalidSyntaxException {
        ServiceReference<?> ref = mock(ServiceReference.class);
        when(context.getServiceReferences(SERVICE_CLASS, null)).thenReturn(new ServiceReference<?>[] {ref});
        when(context.getServiceReferences(SERVICE_CLASS, FILTER)).thenReturn(new ServiceReference<?>[] {ref});
        when(otherContext.getServiceReferences(SERVICE_CLASS, null)).thenReturn(new ServiceReference<?>[] {ref});

        for (int i = 0; i < 3; i++) {
            assertArrayEquals(new Object[] {ref}, cache.getServiceReferences(context, SERVICE_CLASS, null));
            assertArrayEquals(new Object[] {ref}, cache.getServiceReferences(context, SERVICE_CLASS, FILTER));
            assertArrayEquals(new Object[] {ref}, cache.getServiceReferences(otherContext, SERVICE_CLASS, null));
        }

        verify(context, times(1)).getServiceReferences(SERVICE_CLASS, null);
        verify(context, times(1)).getServiceReferences(SERVICE_CLASS, FILTER);
        verify(otherContext, times(1)).getServiceReferences(SERVICE_CLASS, null);
        assertEquals(3, cache.referencesSize());
    }

    @Test
    void testReferencesAreSortedByDescendingRanking() throws InvalidSyntaxException {
        ServiceReference<?> low = mock(ServiceReference.class);
        ServiceReference<?> high = mock(ServiceReference.class);
        // ServiceReference.compareTo: the reference with the higher ranking is "greater"
        lenient().when(low.compareTo(high)).thenReturn(-1);
        lenient().when(high.compareTo(low)).thenReturn(1);
        when(context.getServiceReferences(SERVICE_CLASS, null)).thenReturn(new ServiceReference<?>[] {low, high});

        assertArrayEquals(new Object[] {high, low}, cache.getServiceReferences(context, SERVICE_CLASS, null));
    }

    @Test
    void testEmptyResultIsCached() throws InvalidSyntaxException {
        when(context.getServiceReferences(SERVICE_CLASS, null)).thenReturn(null);

        assertEquals(0, cache.getServiceReferences(context, SERVICE_CLASS, null).length);
        assertEquals(0, cache.getServiceReferences(context, SERVICE_CLASS, null).length);

        verify(context, times(1)).getServiceReferences(SERVICE_CLASS, null);
    }

    @Test
    void testInvalidFilterIsNotCached() throws InvalidSyntaxException {
        when(context.getServiceReferences(SERVICE_CLASS, "(invalid"))
                .thenThrow(new InvalidSyntaxException("invalid", "(invalid"));

        assertThrows(
                InvalidSyntaxException.class, () -> cache.getServiceReferences(context, SERVICE_CLASS, "(invalid"));
        assertThrows(
                InvalidSyntaxException.class, () -> cache.getServiceReferences(context, SERVICE_CLASS, "(invalid"));

        verify(context, times(2)).getServiceReferences(SERVICE_CLASS, "(invalid");
        assertEquals(0, cache.referencesSize());
    }

    @Test
    void testServiceEventEvictsAllClassesOfTheService() throws InvalidSyntaxException {
        ServiceReference<?> ref = mock(ServiceReference.class);
        ServiceReference<?> otherRef = mock(ServiceReference.class);
        when(context.getServiceReferences(SERVICE_CLASS, null))
                .thenReturn(null)
                .thenReturn(new ServiceReference<?>[] {ref});
        when(context.getServiceReferences(SERVICE_CLASS, FILTER)).thenReturn(null);
        when(context.getServiceReferences(OTHER_SERVICE_CLASS, null)).thenReturn(new ServiceReference<?>[] {otherRef});
        cache.getServiceReferences(context, SERVICE_CLASS, null);
        cache.getServiceReferences(context, SERVICE_CLASS, FILTER);
        cache.getServiceReferences(context, OTHER_SERVICE_CLASS, null);
        assertEquals(3, cache.referencesSize());

        // a service registered under SERVICE_CLASS (and an unrelated class) evicts every lookup of SERVICE_CLASS
        when(ref.getProperty(Constants.OBJECTCLASS)).thenReturn(new String[] {SERVICE_CLASS, "org.example.Unrelated"});
        cache.serviceChanged(new ServiceEvent(ServiceEvent.REGISTERED, ref));
        assertEquals(1, cache.referencesSize());

        assertArrayEquals(new Object[] {ref}, cache.getServiceReferences(context, SERVICE_CLASS, null));
        assertArrayEquals(new Object[] {otherRef}, cache.getServiceReferences(context, OTHER_SERVICE_CLASS, null));
        verify(context, times(2)).getServiceReferences(SERVICE_CLASS, null);
        verify(context, times(1)).getServiceReferences(OTHER_SERVICE_CLASS, null);
    }

    @Test
    void testInvalidate() throws InvalidSyntaxException {
        when(context.getServiceReferences(SERVICE_CLASS, null)).thenReturn(null);
        when(context.getServiceReferences(OTHER_SERVICE_CLASS, null)).thenReturn(null);
        cache.getServiceReferences(context, SERVICE_CLASS, null);
        cache.getServiceReferences(context, OTHER_SERVICE_CLASS, null);

        cache.invalidate(SERVICE_CLASS);
        assertEquals(1, cache.referencesSize());
    }

    @Test
    void testLookupDoesNotCache() throws InvalidSyntaxException {
        ServiceReference<?> ref = mock(ServiceReference.class);
        ServiceReference<?>[] refs = new ServiceReference<?>[] {ref};
        when(context.getServiceReferences(SERVICE_CLASS, null)).thenReturn(refs);

        assertSame(refs, OSGiServiceCache.lookup(context, SERVICE_CLASS, null));
        assertSame(refs, OSGiServiceCache.lookup(context, SERVICE_CLASS, null));
        verify(context, times(2)).getServiceReferences(SERVICE_CLASS, null);
        assertEquals(0, cache.referencesSize());
    }

    // ---- service objects

    @Test
    void testServiceIsObtainedOncePerContext() {
        ServiceReference<?> ref = registeredReference();
        Object service = new Object();
        Object otherService = new Object();
        doReturn(service).when(context).getService(ref);
        doReturn(otherService).when(otherContext).getService(ref);

        for (int i = 0; i < 3; i++) {
            assertSame(service, cache.getService(context, ref));
            assertSame(otherService, cache.getService(otherContext, ref));
        }

        verify(context, times(1)).getService(ref);
        verify(otherContext, times(1)).getService(ref);
        verify(context, never()).ungetService(ref);
        assertEquals(2, cache.servicesSize());
    }

    @Test
    void testUnavailableServiceIsNotCached() {
        ServiceReference<?> ref = mock(ServiceReference.class);
        doReturn(null).when(context).getService(ref);

        assertNull(cache.getService(context, ref));
        assertNull(cache.getService(context, ref));

        verify(context, times(2)).getService(ref);
        assertEquals(0, cache.servicesSize());
    }

    @Test
    void testServiceUnregisteredWhileObtainedIsNotKept() {
        // ServiceReference.getBundle() returns null once the service is unregistered
        ServiceReference<?> ref = mock(ServiceReference.class);
        Object service = new Object();
        doReturn(service).when(context).getService(ref);

        assertSame(service, cache.getService(context, ref));

        verify(context).ungetService(ref);
        assertEquals(0, cache.servicesSize());
    }

    @Test
    void testUnregisteringReleasesServicesAndEvictsReferences() throws InvalidSyntaxException {
        ServiceReference<?> ref = registeredReference();
        when(ref.getProperty(Constants.OBJECTCLASS)).thenReturn(new String[] {SERVICE_CLASS});
        when(context.getServiceReferences(SERVICE_CLASS, null)).thenReturn(new ServiceReference<?>[] {ref});
        doReturn(new Object()).when(context).getService(ref);
        doReturn(new Object()).when(otherContext).getService(ref);
        cache.getServiceReferences(context, SERVICE_CLASS, null);
        cache.getService(context, ref);
        cache.getService(otherContext, ref);

        // a modification keeps the service objects, but evicts the references (ranking or properties changed)
        cache.serviceChanged(new ServiceEvent(ServiceEvent.MODIFIED, ref));
        assertEquals(0, cache.referencesSize());
        assertEquals(2, cache.servicesSize());
        verify(context, never()).ungetService(ref);

        cache.serviceChanged(new ServiceEvent(ServiceEvent.UNREGISTERING, ref));
        assertEquals(0, cache.servicesSize());
        verify(context).ungetService(ref);
        verify(otherContext).ungetService(ref);
    }

    @Test
    void testStoppingBundleEvictsItsContext() throws InvalidSyntaxException {
        ServiceReference<?> ref = registeredReference();
        when(context.getServiceReferences(SERVICE_CLASS, null)).thenReturn(null);
        when(otherContext.getServiceReferences(SERVICE_CLASS, null)).thenReturn(null);
        doReturn(new Object()).when(context).getService(ref);
        doReturn(new Object()).when(otherContext).getService(ref);
        cache.getServiceReferences(context, SERVICE_CLASS, null);
        cache.getServiceReferences(otherContext, SERVICE_CLASS, null);
        cache.getService(context, ref);
        cache.getService(otherContext, ref);

        Bundle bundle = mock(Bundle.class);
        when(bundle.getBundleContext()).thenReturn(context);
        cache.bundleChanged(new BundleEvent(BundleEvent.STARTED, bundle));
        assertEquals(2, cache.referencesSize());
        assertEquals(2, cache.servicesSize());

        cache.bundleChanged(new BundleEvent(BundleEvent.STOPPING, bundle));
        assertEquals(1, cache.referencesSize());
        assertEquals(1, cache.servicesSize());
        verify(context).ungetService(ref);
        verify(otherContext, never()).ungetService(ref);

        cache.getServiceReferences(otherContext, SERVICE_CLASS, null);
        verify(otherContext, times(1)).getServiceReferences(SERVICE_CLASS, null);
    }

    @Test
    void testClearReleasesEverything() throws InvalidSyntaxException {
        ServiceReference<?> ref = registeredReference();
        when(context.getServiceReferences(SERVICE_CLASS, null)).thenReturn(new ServiceReference<?>[] {ref});
        doReturn(new Object()).when(context).getService(ref);
        doReturn(new Object()).when(otherContext).getService(ref);
        // the bundle of otherContext is already stopped
        doThrow(new IllegalStateException("invalid context")).when(otherContext).ungetService(ref);
        cache.getServiceReferences(context, SERVICE_CLASS, null);
        cache.getService(context, ref);
        cache.getService(otherContext, ref);

        cache.clear();

        assertEquals(0, cache.referencesSize());
        assertEquals(0, cache.servicesSize());
        verify(context).ungetService(ref);
        verify(otherContext).ungetService(ref);
    }

    private ServiceReference<?> registeredReference() {
        ServiceReference<?> ref = mock(ServiceReference.class);
        // ServiceReference.getBundle() returns the registering bundle as long as the service is registered
        lenient().doReturn(registeringBundle).when(ref).getBundle();
        return ref;
    }
}
