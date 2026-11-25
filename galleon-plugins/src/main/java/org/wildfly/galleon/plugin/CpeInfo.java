/*
 * Copyright 2016-2026 Red Hat, Inc. and/or its affiliates
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

/**
 * SBOM identity of a feature-pack, as set when the feature-pack was built.
 * A {@link CpeResolutionMode#LOCAL} feature-pack that defines a cpe, a name and a purl
 * is reported in the SBOM as a framework that provides the artifacts it contributes.
 */
class CpeInfo {

    private final CpeResolutionMode mode;
    private final String cpe;
    private final String name;
    private final String purl;
    private final String version;

    CpeInfo(CpeResolutionMode mode, String cpe, String name, String purl, String version) {
        this.mode = mode;
        this.cpe = cpe;
        this.name = name;
        this.version = version == null ? null : version.toLowerCase();
        this.purl = purl == null ? null : (purl.contains("@") ? purl : purl + "@" + this.version);
    }

    CpeResolutionMode getMode() {
        return mode;
    }

    String getCpe() {
        return cpe;
    }

    String getName() {
        return name;
    }

    String getPurl() {
        return purl;
    }

    String getVersion() {
        return version;
    }

    /**
     * @return {@code true} if the feature-pack has its own identity and must be reported as a framework.
     */
    boolean isFramework() {
        return CpeResolutionMode.LOCAL.equals(mode) && cpe != null && name != null && purl != null;
    }
}
