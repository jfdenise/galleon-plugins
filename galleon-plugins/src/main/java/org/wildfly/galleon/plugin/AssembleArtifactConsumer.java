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

import java.io.IOException;
import static java.nio.charset.StandardCharsets.UTF_8;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jboss.galleon.MessageWriter;
import org.jboss.galleon.ProvisioningException;
import org.jboss.galleon.runtime.ProvisioningRuntime;
import org.jboss.galleon.universe.maven.MavenArtifact;
import org.jboss.galleon.util.IoUtils;
import org.jboss.galleon.util.ZipUtils;
import org.wildfly.galleon.plugin.config.CopyArtifact;

class AssembleArtifactConsumer implements Utils.ArtifactResourceConsumer {

    private final Map<String, Set<String>> classes = new HashMap<>();
    private final Map<String, List<String>> serviceLoaders = new HashMap<>();
    private final ProvisioningRuntime runtime;
    private final WfInstallPlugin.ArtifactResolver artifactResolver;
    private final MessageWriter log;

    AssembleArtifactConsumer(ProvisioningRuntime runtime, WfInstallPlugin.ArtifactResolver artifactResolver, MessageWriter log) {
        this.runtime = runtime;
        this.artifactResolver = artifactResolver;
        this.log = log;
    }

    public void assemble(MavenArtifact artifact, Map<String, String> mergedArtifactVersions) throws IOException, ProvisioningException {
        System.out.println("ASSEMBLING " + artifact);
        Path tmp = runtime.getTmpPath().resolve("assemble_src").resolve(artifact.getGroupId()).resolve(artifact.getArtifactId());
        Path tmpTarget = runtime.getTmpPath().resolve("assemble_target").resolve(artifact.getGroupId()).resolve(artifact.getArtifactId());
        Files.createDirectories(tmp);
        Files.createDirectories(tmpTarget);
        Utils.extractArtifact(artifact.getPath(), tmp, new CopyArtifact());
        Path mavenPath = tmp.resolve("META-INF").resolve("maven");
        List<MavenArtifact> dependencies = Utils.retrieveDependencies(mavenPath);
        for (MavenArtifact dependency : dependencies) {
            String version = dependency.getVersion();
            String key = dependency.getGroupId() + ':' + dependency.getArtifactId() + ( dependency.getClassifier() == null || dependency.getClassifier().isEmpty() ?
                    "" : "::" + dependency.getClassifier() );
            String value = mergedArtifactVersions.get(key);
            if(value == null) {
                System.err.println("ARTIFACT " + key + " is shaded but unknown, skipping it!");
                continue;
            }
            artifactResolver.resolve(dependency);
            if (!dependency.getVersion().equals(version)) {
                log.print("JAR " + artifact.getPath().getFileName() + ". Dependency " + dependency.getGroupId() + ":" + dependency.getArtifactId() + " has been upgraded from " + version + " to " + dependency.getVersion());
            }
            Utils.navigateArtifact(dependency.getPath(), tmpTarget, this);
        }
        update(tmp, tmpTarget);
        Path assembledJar = runtime.getTmpPath().resolve(artifact.getArtifactFileName());
        Files.deleteIfExists(assembledJar);
        ZipUtils.zip(tmpTarget, assembledJar);
        artifact.setPath(assembledJar);
        IoUtils.recursiveDelete(tmp);
        IoUtils.recursiveDelete(tmpTarget);
    }

    @Override
    public boolean consume(Path resourcePath) throws IOException {
        String entry = resourcePath.toString().substring(1);
        if (entry.startsWith("META-INF/services/")) {
            String fileName = resourcePath.getFileName().toString();
            List<String> lines = Files.readAllLines(resourcePath);
            List<String> allLines = serviceLoaders.get(fileName);
            Set<String> allClasses = classes.get(fileName);
            if (allLines == null) {
                allLines = new ArrayList<>();
                serviceLoaders.put(fileName, allLines);
            }
            if (allClasses == null) {
                allClasses = new HashSet<>();
                classes.put(fileName, allClasses);
            }
            boolean newClasses = false;
            for (String l : lines) {
                if (!l.trim().startsWith("#")) {
                    if (!allClasses.contains(l)) {
                        newClasses = true;
                        break;
                    }
                }
            }
            if (newClasses) {
                for (String l : lines) {
                    if (l.trim().startsWith("#")) {
                        allLines.add(l);
                    } else {
                        if (allClasses.contains(l)) {
                            // Ignore the class.
                            continue;
                        }
                        allClasses.add(l);
                        allLines.add(l);
                    }
                }
            }
            return false;
        }
        return true;//cp.includeFile(entry);
    }

    public void update(Path src, Path target) throws IOException {
        Path srcMetaInf = src.resolve("META-INF");
        Path targetMetaInf = target.resolve("META-INF");
        Path originalManifestPath = srcMetaInf.resolve("MANIFEST.MF");
        Path targetManifestPath = targetMetaInf.resolve("MANIFEST.MF");
        Files.copy(originalManifestPath, targetManifestPath, StandardCopyOption.REPLACE_EXISTING);
        copy(src, target, "LICENSE");
        copy(srcMetaInf, targetMetaInf, "LICENSE");
        copy(srcMetaInf, targetMetaInf, "NOTICE");
        copy(srcMetaInf, targetMetaInf, "DEPENDENCIES");
        Path moduleInfoPath = target.resolve("module-info.class");
        Files.deleteIfExists(moduleInfoPath);
        Path indexListPath = target.resolve("META-INF/INDEX.LIST");
        Files.deleteIfExists(indexListPath);
        Path services = target.resolve("META-INF").resolve("services");
        for (Map.Entry<String, List<String>> entry : serviceLoaders.entrySet()) {
            Path file = services.resolve(entry.getKey());
            Files.write(file, entry.getValue(), UTF_8);
        }
    }

    private void copy(Path src, Path target, String file) throws IOException {
        String[] suffixes = {"", ".md", ".txt"};
        for (String suffix : suffixes) {
            String fileName = file + suffix;
            Path filePath = src.resolve(fileName);
            if (Files.exists(filePath)) {
                Files.copy(filePath, target.resolve(fileName), StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }
}
