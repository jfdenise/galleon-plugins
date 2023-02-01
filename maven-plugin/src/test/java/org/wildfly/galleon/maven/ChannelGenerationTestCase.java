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
package org.wildfly.galleon.maven;

import static org.jboss.galleon.config.FeaturePackConfig.builder;
import static org.jboss.galleon.universe.FeaturePackLocation.fromString;
import static org.junit.Assert.assertEquals;
import static org.wildfly.galleon.maven.FeaturePackDependencySpec.create;
import static org.wildfly.galleon.plugin.ArtifactCoords.newGav;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.DefaultArtifact;
import org.apache.maven.model.Dependency;
import org.apache.maven.project.MavenProject;
import org.junit.Test;
import org.wildfly.channel.ChannelManifest;
import org.wildfly.channel.ChannelManifestMapper;

public class ChannelGenerationTestCase {

    @Test
    public void testGeneratedChannel() throws IOException, NoSuchFieldException, IllegalAccessException {

        MavenProject project = new MavenProject();
        project.setGroupId("org.wildfly");
        project.setArtifactId("wildfly-galleon-pack");
        project.setVersion("27.0.0.Final");

        // build the org.wildfly.galleon-pack feature pack that depends on the wildfly-ee-galleon-pack
        WildFlyFeaturePackBuild fp = WildFlyFeaturePackBuild.builder()
                .addDependency(newGav("org.wildfly", "wildfly-ee-galleon-pack", "27.0.0.Final"),
                        create(builder(fromString("org.wildfly:wildfly-ee-galleon-pack:27.0.0.Final")).build()))
                .build();

        Dependency wildflyEEGalleonPackDep = new Dependency();
        wildflyEEGalleonPackDep.setGroupId("org.wildfly");
        wildflyEEGalleonPackDep.setArtifactId("wildfly-ee-galleon-pack");
        wildflyEEGalleonPackDep.setVersion("27.0.0.Final");
        wildflyEEGalleonPackDep.setType("zip");
        project.getDependencies().add(wildflyEEGalleonPackDep);

        Artifact wildflyEEGalleonArtifact = new DefaultArtifact( "org.wildfly",  "wildfly-ee-galleon-pack", "27.0.0.Final",  "provided", "zip", "", null);
        project.getArtifacts().add(wildflyEEGalleonArtifact);

        Artifact cliArtifact = new DefaultArtifact( "org.wildfly.core",  "wildfly-cli", "2.0",  "compile", "jar", "", null);
        project.getArtifacts().add(cliArtifact);
        Path cliJar = Files.createTempFile("test-cli.jar", null);
        cliJar.toFile().deleteOnExit();
        cliArtifact.setFile(cliJar.toFile());

        Artifact cliClientArtifact = new DefaultArtifact( "org.wildfly.core",  "wildfly-cli", "2.0",  "compile", "jar", "client", null);
        cliClientArtifact.setFile(cliJar.toFile());
        project.getArtifacts().add(cliClientArtifact);

        Artifact microprofileConfigAPIArtifact = new DefaultArtifact( "org.eclipse.microprofile.config",  "microprofile-config-api", "2.0",  "compile", "jar", "", null);
        project.getArtifacts().add(microprofileConfigAPIArtifact);

        WfFeaturePackBuildMojo mojo = new WfFeaturePackBuildMojo();
        Field f1 = mojo.getClass().getSuperclass().getDeclaredField("project");
        f1.setAccessible(true);
        f1.set(mojo, project);
        Field f2 = mojo.getClass().getSuperclass().getDeclaredField("addFeaturePacksAsRequiredManifests");
        f2.setAccessible(true);
        f2.set(mojo, Boolean.TRUE);

        Path f = Files.createTempFile("test-fp.zip", null);
        f.toFile().deleteOnExit();
        String yamlChannelManifest = mojo.createYAMLChannelManifest(fp, f.toFile());
        ChannelManifest channelManifest = ChannelManifestMapper.fromString(yamlChannelManifest);
        assertEquals(3, channelManifest.getStreams().size());
        assertEquals(1, channelManifest.getManifestRequirements().size());
        assertChannelManifestContainsRequirements(channelManifest, "org.wildfly", "wildfly-ee-galleon-pack", "27.0.0.Final");
        Set<String> keys = new HashSet<>();
        keys.addAll(Arrays.asList("zip"));
        assertChannelManifestContainsStream(channelManifest, "org.wildfly", "wildfly-galleon-pack", "27.0.0.Final", keys);
        assertChannelManifestContainsStream(channelManifest, "org.eclipse.microprofile.config", "microprofile-config-api", "2.0", Collections.emptySet());
        Set<String> keys2 = new HashSet<>();
        keys2.addAll(Arrays.asList("jar", "client/jar"));
        assertChannelManifestContainsStream(channelManifest, "org.wildfly.core", "wildfly-cli", "2.0", keys2);
    }

    private static void assertChannelManifestContainsStream(ChannelManifest channel, String groupId, String artifactId, String version, Set<String> checksumKeys) {
        channel.getStreams().stream()
                .filter(s -> groupId.equals(s.getGroupId()) && artifactId.equals(s.getArtifactId()) && version.equals(s.getVersion()) && checksumKeys.equals(s.getsha256Checksum().keySet()))
                .findFirst().orElseThrow();
    }

    private static void assertChannelManifestContainsRequirements(ChannelManifest manifest, String groupId, String artifactId, String version) {
        manifest.getManifestRequirements().stream()
                .filter(s -> groupId.equals(s.getGroupId()) && artifactId.equals(s.getArtifactId()))
                .findFirst().orElseThrow();
    }
}
