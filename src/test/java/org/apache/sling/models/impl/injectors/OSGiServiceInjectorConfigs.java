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

import java.lang.annotation.Annotation;

/**
 * Creates {@link OSGiServiceInjectorConfiguration} instances for tests.
 */
public final class OSGiServiceInjectorConfigs {

    private OSGiServiceInjectorConfigs() {}

    /**
     * @param serviceCacheEnabled value of the {@code service.cache.enabled} property
     * @return the configuration
     */
    public static OSGiServiceInjectorConfiguration config(boolean serviceCacheEnabled) {
        return new OSGiServiceInjectorConfiguration() {
            @Override
            public Class<? extends Annotation> annotationType() {
                return OSGiServiceInjectorConfiguration.class;
            }

            @Override
            public boolean service_cache_enabled() {
                return serviceCacheEnabled;
            }
        };
    }
}
