/*
 * Copyright 2016-2020 Red Hat, Inc. and/or its affiliates
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
package org.wildfly.galleon.maven;

import java.util.HashSet;
import java.util.Set;
import org.jboss.galleon.universe.FeaturePackLocation;

/**
 *
 * @author jdenise
 */
final class LayersInfo {

    static class Builder {

        private FeaturePackLocation loc;
        private Set<LayerInfo> layers = new HashSet<>();

        Builder setFeaturePackLocation(String loc) {
            this.loc = FeaturePackLocation.fromString(loc);
            return this;
        }

        Builder addLayer(LayerInfo layer) {
            layers.add(layer);
            return this;
        }

        LayersInfo build() {
            return new LayersInfo(loc, layers);
        }
    }
    private final FeaturePackLocation loc;
    private final Set<LayerInfo> layers;

    LayersInfo(FeaturePackLocation loc, Set<LayerInfo> layers) {
        this.loc = loc;
        this.layers = layers;
    }

    Set<LayerInfo> getLayers() {
        return layers;
    }

    String getFPL() {
        return loc.toString();
    }
}
