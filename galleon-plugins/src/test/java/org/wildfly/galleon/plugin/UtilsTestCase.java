/*
 * Copyright 2016-2021 Red Hat, Inc. and/or its affiliates
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

import java.util.HashMap;
import java.util.Map;
import org.jboss.galleon.ProvisioningException;
import org.junit.Assert;
import org.junit.Test;
/**
 *
 * @author jdenise
 */
public class UtilsTestCase {

    @Test
    public void testOverriddenArtifacts() throws Exception {
        {
            String str = "grp:art:vers:class:jar";
            Map<String, String> map = Utils.toArtifactsMap(str);
            Assert.assertEquals(1, map.size());
            String key = "grp:art::class";
            String value = map.get(key);
            Assert.assertEquals(str, value);
        }

        {
            String str = "grp:art:vers::jar";
            Map<String, String> map = Utils.toArtifactsMap(str);
            Assert.assertEquals(1, map.size());
            String key = "grp:art";
            String value = map.get(key);
            Assert.assertEquals(str, value);
        }

        {
            String str1 = "grp:art:vers::jar";
            String str2 = "grp2:art2:vers2:class2:jar2";
            String str3 = " grp3 : art3 : vers3:    : jar3  ";
            String str3Trimmed = "grp3:art3:vers3::jar3";
            String[] cases = {
                str1 + " | " + str2 + "|" + str3,
                str1 + "|" + str2 + "|" + str3,
                "  " + str1 + " | " + str2 + "   " + " | " + str3};
            for (String str : cases) {
                Map<String, String> map = Utils.toArtifactsMap(str);
                Assert.assertEquals(3, map.size());
                String key1 = "grp:art";
                String value1 = map.get(key1);
                Assert.assertEquals(str1, value1);
                String key2 = "grp2:art2::class2";
                String value2 = map.get(key2);
                Assert.assertEquals(str2, value2);
                String key3 = "grp3:art3";
                String value3 = map.get(key3);
                Assert.assertEquals(str3Trimmed, value3);
            }
        }

        {
            String[] invalids = {
                "",
                "1:2:3:4:5:6",
                "a:b:c:d",
                ":b:c:d:e",
                "a::c:d:e",
                "a:b::d:e",
                "a:b:c::",
                "a:b:c:d:",
                "a:b:c:d:e|a:b:c:d:",
                " : : : : ",
                "a:b:c:d:e| :b:c:d:  "};
            for (String str : invalids) {
                try {
                    Map<String, String> map = Utils.toArtifactsMap(str);
                    throw new Exception("Should have failed");
                } catch (IllegalArgumentException ex) {
                    // XXX OK expected
                }
            }
        }

        {
            String str = "grp:art:${a,b;c}:class:jar";
            Map<String, String> map = Utils.toArtifactsMap(str);
            Assert.assertEquals(1, map.size());
            String key = "grp:art::class";
            String value = map.get(key);
            Assert.assertEquals(str, value);
        }
    }

    @Test
    public void testVersionExpression() throws Exception {
        String envVar = "WFGP_TEST_VERSION";
        String env = "env." + envVar;
        String envVersionValue = System.getenv(envVar);

        {
            Map<String, String> versionsProps = new HashMap<>();
            String key = "a:b";
            String v = "123";
            String val = "a:b:"+v+"::jar";
            versionsProps.put(key, val);
            Assert.assertEquals(v, Utils.toArtifactCoords(versionsProps, key, false).getVersion());
            Assert.assertEquals(v, Utils.toArtifactCoords(versionsProps, val, false).getVersion());
        }

        {
            String prop = "org.wfgp.version";
            String versionValue = "9999";
            String key = "a:b";
            String val = "a:b:${" + prop + "}::jar";
            Map<String, String> versionsProps = Utils.toArtifactsMap(val);
            System.setProperty(prop, versionValue);
            try {
                Assert.assertEquals(versionValue, Utils.toArtifactCoords(versionsProps, key, false).getVersion());
                Assert.assertEquals(versionValue, Utils.toArtifactCoords(versionsProps, val, false).getVersion());
            } finally {
                System.clearProperty(prop);
            }
        }

        {
            Map<String, String> versionsProps = new HashMap<>();
            String prop = "org.wfgp.version";
            String key = "a:b";
            String defaultValue = "010101";
            String val = "a:b:${" + prop + ";" + defaultValue + "}::jar";
            versionsProps.put(key, val);
            Assert.assertEquals(defaultValue, Utils.toArtifactCoords(versionsProps, key, false).getVersion());
            Assert.assertEquals(defaultValue, Utils.toArtifactCoords(versionsProps, val, false).getVersion());
        }

        {
            Map<String, String> versionsProps = new HashMap<>();
            String prop = "org.wfgp.version";
            String key = "a:b";
            String defaultValue = ";01;0101;";
            String val = "a:b:${" + prop + ";" + defaultValue + "}::jar";
            versionsProps.put(key, val);
            Assert.assertEquals(defaultValue, Utils.toArtifactCoords(versionsProps, key, false).getVersion());
            Assert.assertEquals(defaultValue, Utils.toArtifactCoords(versionsProps, val, false).getVersion());
        }

        {
            Map<String, String> versionsProps = new HashMap<>();
            String prop = "org.wfgp.version";
            String key = "a:b";
            String defaultValue = "010101";
            String val = "a:b:  ${" + prop + ";" + defaultValue + "}  ::jar";
            versionsProps.put(key, val);
            Assert.assertEquals(defaultValue, Utils.toArtifactCoords(versionsProps, key, false).getVersion());
            Assert.assertEquals(defaultValue, Utils.toArtifactCoords(versionsProps, val, false).getVersion());
        }

        {
            Map<String, String> versionsProps = new HashMap<>();
            String prop = "org.wfgp.version";
            String key = "a:b";
            String defaultValue = "";
            String val = "a:b:${" + prop + ";}::jar";
            versionsProps.put(key, val);
            Assert.assertEquals(defaultValue, Utils.toArtifactCoords(versionsProps, key, false).getVersion());
            Assert.assertEquals(defaultValue, Utils.toArtifactCoords(versionsProps, val, false).getVersion());
        }

        {
            Map<String, String> versionsProps = new HashMap<>();
            String prop = "org.wfgp.version";
            String key = "a:b";
            String defaultValue = "";
            String val = "a:b:${" + prop + ",;}::jar";
            versionsProps.put(key, val);
            try {
                Utils.toArtifactCoords(versionsProps, key, false);
                throw new Exception("Should have failed");
            } catch (ProvisioningException ex) {
                // XXX OK expected
                Assert.assertEquals("Invalid syntax for expression " + key, ex.getMessage());
            }
            try {
                Utils.toArtifactCoords(versionsProps, val, false);
                throw new Exception("Should have failed");
            } catch (ProvisioningException ex) {
                // XXX OK expected
                Assert.assertEquals("Invalid syntax for expression " + val, ex.getMessage());
            }
        }

        {
            Map<String, String> versionsProps = new HashMap<>();
            String key = "a:b";
            String defaultValue = "foo";
            String val = "a:b:${,,;   "+defaultValue+"   }::jar";
            versionsProps.put(key, val);
            try {
                Utils.toArtifactCoords(versionsProps, key, false);
                throw new Exception("Should have failed");
            } catch (ProvisioningException ex) {
                // XXX OK expected
                Assert.assertEquals("Invalid syntax for expression " + key, ex.getMessage());
            }
            try {
                Utils.toArtifactCoords(versionsProps, val, false);
                throw new Exception("Should have failed");
            } catch (ProvisioningException ex) {
                // XXX OK expected
                Assert.assertEquals("Invalid syntax for expression " + val, ex.getMessage());
            }
        }

        {
            Map<String, String> versionsProps = new HashMap<>();
            String key = "a:b";
            String defaultValue = "";
            String val = "a:b:${;}::jar";
            versionsProps.put(key, val);
            try {
                Utils.toArtifactCoords(versionsProps, key, false);
                throw new Exception("Should have failed");
            } catch (ProvisioningException ex) {
                // XXX OK expected
                Assert.assertEquals("Invalid syntax for expression " + key, ex.getMessage());
            }
            try {
                Utils.toArtifactCoords(versionsProps, val, false);
                throw new Exception("Should have failed");
            } catch (ProvisioningException ex) {
                // XXX OK expected
                Assert.assertEquals("Invalid syntax for expression " + val, ex.getMessage());
            }
        }

        {
            Map<String, String> versionsProps = new HashMap<>();
            String key = "a:b";
            String defaultValue = "";
            String val = "a:b:${}::jar";
            versionsProps.put(key, val);
            try {
                Utils.toArtifactCoords(versionsProps, key, false).getVersion();
                throw new Exception("Should have failed");
            } catch (ProvisioningException ex) {
                // XXX OK expected
                Assert.assertEquals("Invalid syntax for expression " + key, ex.getMessage());
            }
            try {
                Utils.toArtifactCoords(versionsProps, val, false).getVersion();
                throw new Exception("Should have failed");
            } catch (ProvisioningException ex) {
                // XXX OK expected
                Assert.assertEquals("Invalid syntax for expression " + val, ex.getMessage());
            }
        }

        {
            Map<String, String> versionsProps = new HashMap<>();
            String key = "a:b";
            String defaultValue = "";
            String val = "a:b:${,,,,,,,,,,;}::jar";
            versionsProps.put(key, val);
            try {
                Utils.toArtifactCoords(versionsProps, key, false);
                throw new Exception("Should have failed");
            } catch (ProvisioningException ex) {
                // XXX OK expected
                Assert.assertEquals("Invalid syntax for expression " + key, ex.getMessage());
            }
            try {
                Utils.toArtifactCoords(versionsProps, val, false);
                throw new Exception("Should have failed");
            } catch (ProvisioningException ex) {
                // XXX OK expected
                Assert.assertEquals("Invalid syntax for expression " + val, ex.getMessage());
            }
        }

        {
            Map<String, String> versionsProps = new HashMap<>();
            String key = "a:b";
            String val = "a:b:${" + env + "}::jar";
            versionsProps.put(key, val);
            Assert.assertEquals(envVersionValue, Utils.toArtifactCoords(versionsProps, key, false).getVersion());
            Assert.assertEquals(envVersionValue, Utils.toArtifactCoords(versionsProps, val, false).getVersion());
        }

        {
            Map<String, String> versionsProps = new HashMap<>();
            String prop = "org.wfgp.version";
            String versionValue = "0000";
            String key = "a:b";
            String val = "a:b:${" + prop + "," + env + "}::jar";
            versionsProps.put(key, val);
            System.setProperty(prop, versionValue);
            try {
                Assert.assertEquals(versionValue, Utils.toArtifactCoords(versionsProps, key, false).getVersion());
                Assert.assertEquals(versionValue, Utils.toArtifactCoords(versionsProps, val, false).getVersion());
            } finally {
                System.clearProperty(prop);
            }
        }

        {
            Map<String, String> versionsProps = new HashMap<>();
            String prop = "org.wfgp.version";
            String versionValue = "0000";
            String key = "a:b";
            String val = "a:b:${" + env + "," + prop + "}::jar";
            versionsProps.put(key, val);
            System.setProperty(prop, versionValue);
            try {
                Assert.assertEquals(envVersionValue, Utils.toArtifactCoords(versionsProps, key, false).getVersion());
                Assert.assertEquals(envVersionValue, Utils.toArtifactCoords(versionsProps, val, false).getVersion());
            } finally {
                System.clearProperty(prop);
            }
        }

        {
            Map<String, String> versionsProps = new HashMap<>();
            String prop1 = "org.wfgp.version1";
            String prop2 = "org.wfgp.version2";
            String versionValue = "5555";
            String key = "a:b";
            String val = "a:b:${ " + prop1 + " , " + prop2 + " }::jar";
            versionsProps.put(key, val);
            System.setProperty(prop2, versionValue);
            try {
                Assert.assertEquals(versionValue, Utils.toArtifactCoords(versionsProps, key, false).getVersion());
                Assert.assertEquals(versionValue, Utils.toArtifactCoords(versionsProps, val, false).getVersion());
            } finally {
                System.clearProperty(prop2);
            }
        }

        {
            Map<String, String> versionsProps = new HashMap<>();
            String prop = "org.wfgp.version";
            String key = "a:b";
            String val = "a:b:${" + prop + "}::jar";
            versionsProps.put(key, val);
            try {
                Utils.toArtifactCoords(versionsProps, key, false);
                throw new Exception("Should have failed");
            } catch (ProvisioningException ex) {
                // XXX OK expected
                Assert.assertEquals("Unresolved expression for " + key, ex.getMessage());
            }
            try {
                Utils.toArtifactCoords(versionsProps, val, false);
                throw new Exception("Should have failed");
            } catch (ProvisioningException ex) {
                // XXX OK expected
                Assert.assertEquals("Unresolved expression for " + val, ex.getMessage());
            }
        }

        {
            Map<String, String> versionsProps = new HashMap<>();
            String unknownEnv = "env.WFGP_FOO";
            String key = "a:b";
            String val = "a:b:${" + unknownEnv + "}::jar";
            versionsProps.put(key, val);
            try {
                Utils.toArtifactCoords(versionsProps, key, false);
                throw new Exception("Should have failed");
            } catch (ProvisioningException ex) {
                // XXX OK expected
                Assert.assertEquals("Unresolved expression for " + key, ex.getMessage());
            }
            try {
                Utils.toArtifactCoords(versionsProps, val, false);
                throw new Exception("Should have failed");
            } catch (ProvisioningException ex) {
                // XXX OK expected
                Assert.assertEquals("Unresolved expression for " + val, ex.getMessage());
            }
        }

        {
            Map<String, String> versionsProps = new HashMap<>();
            String key = "a:b";
            String val = "a:b:${env.;foo}::jar";
            versionsProps.put(key, val);
            try {
                Utils.toArtifactCoords(versionsProps, key, false);
                throw new Exception("Should have failed");
            } catch (ProvisioningException ex) {
                // XXX OK expected
                Assert.assertEquals("Invalid syntax for expression " + key, ex.getMessage());
            }
            try {
                Utils.toArtifactCoords(versionsProps, val, false);
                throw new Exception("Should have failed");
            } catch (ProvisioningException ex) {
                // XXX OK expected
                Assert.assertEquals("Invalid syntax for expression " + val, ex.getMessage());
            }
        }
    }
}
