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
import java.util.Set;
import java.util.TreeSet;

/**
 *
 * @author jdenise
 */
final class LayerInfo implements Comparable<LayerInfo> {

    private final String name;
    private final Set<LayerDependencyInfo> dependencies = new TreeSet<>();
    private String visibility = "private";
    private String description;
    private boolean deprecated;

    LayerInfo(String name) {
        this.name = name;
    }
    void setDescription(String description) {
        this.description = description;
    }

    void setVisibility(String visibility) {
        this.visibility = visibility == null ? "private" : visibility;
    }

    void setDeprecated(boolean deprecated) {
        this.deprecated = deprecated;
    }

    void addDependency(LayerDependencyInfo dep) {
        dependencies.add(dep);
    }

    Set<LayerDependencyInfo> getDependencies() {
        return dependencies;
    }

    String getName() {
        return name;
    }

    String getVisibility() {
        return visibility;
    }

    String getDescription() {
        return description;
    }

    boolean isDeprecated() {
        return deprecated;
    }

    @Override
    public int compareTo(LayerInfo t) {
        // public must be before.
        if (("public".equals(visibility) && "public".equals(t.visibility)) || ("private".equals(visibility) && "private".equals(t.visibility))) {
            return name.compareTo(t.name);
        }
        if ("public".equals(visibility) && !"public".equals(t.visibility)) {
            return -1;
        } else {
            return 1;
        }
    }

    @Override
    public String toString() {
        return name;
    }

    @Override
    public int hashCode() {
        int hash = 7;
        hash = 19 * hash + Objects.hashCode(this.name);
        return hash;
    }

    @Override
    public boolean equals(Object obj) {
        if (!(obj instanceof LayerInfo)) {
            return false;
        }
        return name.equals(((LayerInfo) obj).name);
    }
}
