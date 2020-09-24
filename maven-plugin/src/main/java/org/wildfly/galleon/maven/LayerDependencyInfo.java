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

import java.util.Objects;

/**
 *
 * @author jdenise
 */
final class LayerDependencyInfo implements Comparable<LayerDependencyInfo> {

    private final String name;
    private final boolean optional;

    LayerDependencyInfo(String name, boolean optional) {
        this.name = name;
        this.optional = optional;
    }

    String getName() {
        return name;
    }

    boolean isOptional() {
        return optional;
    }

    @Override
    public int compareTo(LayerDependencyInfo t) {
        return name.compareTo(t.name);
    }

    @Override
    public int hashCode() {
        int hash = 7;
        hash = 19 * hash + Objects.hashCode(this.name);
        return hash;
    }

    @Override
    public boolean equals(Object obj) {
        if (!(obj instanceof LayerDependencyInfo)) {
            return false;
        }
        return name.equals(((LayerDependencyInfo) obj).name);
    }
}
