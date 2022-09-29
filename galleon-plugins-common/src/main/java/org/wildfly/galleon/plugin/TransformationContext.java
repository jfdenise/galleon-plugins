/*
 * Copyright 2016-2022 Red Hat, Inc. and/or its affiliates
 * and other contributors as indicated by the @author tags.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.wildfly.galleon.plugin;

import java.util.Map;
import org.wildfly.galleon.plugin.AbstractWfInstallPlugin.ArtifactResolver;

/**
 *
 * @author jdenise
 */
public class TransformationContext {

    private final AbstractArtifactInstaller artifactInstaller;
    private final AbstractWfInstallPlugin.ArtifactResolver artifactResolver;
    private final boolean transformableFeaturePack;
    TransformationContext(AbstractArtifactInstaller installer, ArtifactResolver resolver, boolean transformableFeaturePack) {
        this.artifactInstaller = installer;
        this.artifactResolver = resolver;
        this.transformableFeaturePack = transformableFeaturePack;
    }

    AbstractArtifactInstaller getArtifactInstaller() {
        return artifactInstaller;
    }

    ArtifactResolver getArtifactResolver() {
        return artifactResolver;
    }

    boolean isTransformableFeaturePack() {
        return transformableFeaturePack;
    }

    void configureOptionsExampleConfigs(Map<String, String> options) {
    }
}
