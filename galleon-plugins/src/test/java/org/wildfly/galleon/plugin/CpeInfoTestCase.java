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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class CpeInfoTestCase {

    private static final String CPE = "cpe:/a:redhat:xp:7.0";

    @Test
    public void localWithCompleteIdentityIsFramework() {
        final CpeInfo info = new CpeInfo(CpeResolutionMode.LOCAL, CPE, "XP", "pkg:generic/xp", "Red Hat", "7.0.0.Final");
        assertTrue(info.isFramework());
        assertEquals("Red Hat", info.getPublisher());
        assertEquals("7.0.0.final", info.getVersion());
        assertEquals("pkg:generic/xp@7.0.0.final", info.getPurl());
    }

    @Test
    public void publisherIsOptional() {
        final CpeInfo info = new CpeInfo(CpeResolutionMode.LOCAL, CPE, "XP", "pkg:generic/xp", null, "7.0.0.Final");
        assertTrue(info.isFramework());
        assertNull(info.getPublisher());
    }

    @Test
    public void nullModeIsLocal() {
        assertTrue(new CpeInfo(null, CPE, "XP", "pkg:generic/xp", null, "7.0.0.Final").isFramework());
    }

    @Test
    public void nullVersionAndPurlWithoutVersionIsUnknown() {
        final CpeInfo info = new CpeInfo(CpeResolutionMode.LOCAL, CPE, "XP", "pkg:generic/xp", null, null);
        assertEquals(CpeInfo.UNKNOWN_VERSION, info.getVersion());
        assertEquals("pkg:generic/xp@unknown", info.getPurl());
    }

    @Test
    public void purlVersionTakesPrecedence() {
        final CpeInfo info = new CpeInfo(CpeResolutionMode.LOCAL, CPE, "XP", "pkg:generic/xp@1.0?type=jar#sub", null, "7.0.0.Final");
        assertEquals("1.0", info.getVersion());
        assertEquals("pkg:generic/xp@1.0?type=jar#sub", info.getPurl());
    }

    @Test
    public void notAFramework() {
        assertFalse(new CpeInfo(CpeResolutionMode.SERVER_PRODUCT_CONF, CPE, "XP", "pkg:generic/xp", null, "1").isFramework());
        assertFalse(new CpeInfo(CpeResolutionMode.LOCAL, null, "XP", "pkg:generic/xp", null, "1").isFramework());
        assertFalse(new CpeInfo(CpeResolutionMode.LOCAL, CPE, null, "pkg:generic/xp", null, "1").isFramework());
        assertFalse(new CpeInfo(CpeResolutionMode.LOCAL, CPE, "XP", null, null, "1").isFramework());
    }
}
