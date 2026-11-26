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
 * The publisher is optional.
 */
class CpeInfo {

    static final String UNKNOWN_VERSION = "unknown";

    private final CpeResolutionMode mode;
    private final String cpe;
    private final String name;
    private final String purl;
    private final String publisher;
    private final String version;

    /**
     * @param publisher the publisher, can be {@code null}.
     * @param version the feature-pack version. The version of the purl, if it has one, is the version of the
     * framework. Otherwise {@code version} is used, or {@link #UNKNOWN_VERSION} if it is {@code null}, and is
     * appended to the purl.
     */
    CpeInfo(CpeResolutionMode mode, String cpe, String name, String purl, String publisher, String version) {
        this.mode = mode;
        this.cpe = cpe;
        this.name = name;
        this.publisher = publisher;
        if (purl == null) {
            this.version = version == null ? UNKNOWN_VERSION : version.toLowerCase();
            this.purl = null;
        } else {
            final int at = purl.indexOf('@');
            if (at >= 0) {
                this.version = purlVersion(purl, at);
                this.purl = purl;
            } else {
                this.version = version == null ? UNKNOWN_VERSION : version.toLowerCase();
                this.purl = purl + "@" + this.version;
            }
        }
    }

    /** The version of a purl, without its qualifiers and subpath. */
    private static String purlVersion(String purl, int at) {
        int end = purl.length();
        for (char c : new char[] {'?', '#'}) {
            final int i = purl.indexOf(c, at);
            if (i >= 0 && i < end) {
                end = i;
            }
        }
        return purl.substring(at + 1, end);
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

    /** @return the publisher, {@code null} if not set. */
    String getPublisher() {
        return publisher;
    }

    String getVersion() {
        return version;
    }

    /**
     * @return {@code true} if the feature-pack has its own identity and must be reported as a framework.
     * The publisher is optional.
     */
    boolean isFramework() {
        return (mode == null || CpeResolutionMode.LOCAL.equals(mode)) && cpe != null && name != null && purl != null;
    }
}
