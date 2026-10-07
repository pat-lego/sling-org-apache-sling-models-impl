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

import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;

/**
 * Configuration of the {@link OSGiServiceInjector}.
 */
@ObjectClassDefinition(
        name = "Apache Sling Models OSGi Service Injector",
        description = "Configures how OSGi services are looked up when they are injected into Sling Models.")
public @interface OSGiServiceInjectorConfiguration {

    /**
     * Enables the OSGi service cache. Disabled by default: every injection then looks up the service references and
     * gets (and later releases) the service objects through the service registry, as in previous versions.
     * <p>
     * The cache is also enabled when the environment variable
     * {@value OSGiServiceInjector#SERVICE_CACHE_ENABLED_ENV} is set to {@code true}, regardless of this property.
     *
     * @return {@code true} to cache the service references and service objects
     */
    @AttributeDefinition(
            name = "Enable OSGi Service Cache",
            description = "Caches the service references and the service objects injected into Sling Models instead"
                    + " of looking them up in the service registry for every model instance. Disabled by default."
                    + " The cache is also enabled if the environment variable "
                    + OSGiServiceInjector.SERVICE_CACHE_ENABLED_ENV
                    + " is set to 'true'.")
    boolean service_cache_enabled() default false;
}
