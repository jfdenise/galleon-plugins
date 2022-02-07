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

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import static java.nio.charset.StandardCharsets.UTF_8;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.Attributes;
import nu.xom.Builder;
import nu.xom.Document;
import nu.xom.Element;
import nu.xom.Elements;
import nu.xom.ParsingException;
import org.apache.maven.artifact.Artifact;
import org.jboss.galleon.MessageWriter;
import org.jboss.galleon.ProvisioningDescriptionException;
import org.jboss.galleon.ProvisioningException;
import org.jboss.galleon.runtime.ProvisioningRuntime;
import org.jboss.galleon.universe.maven.MavenArtifact;
import org.jboss.galleon.util.IoUtils;
import org.jboss.galleon.util.ZipUtils;
import java.util.jar.Manifest;

/**
 * A shaded model.
 *
 * @author jdenise
 */
public class ShadedModel implements Utils.ArtifactResourceConsumer {

    public static final String CLASSIFIER = "shaded-model";
    public static final String EXTENSION = "xml";

    private final Map<String, Set<String>> classes = new HashMap<>();
    private final Map<String, List<String>> serviceLoaders = new HashMap<>();

    private final Element rootElement;
    private final Document document;
    private final ProvisioningRuntime runtime;
    private final WfInstallPlugin.ArtifactResolver artifactResolver;
    private final MessageWriter log;
    private final Map<String, String> mergedArtifactVersions;
    private boolean seenManifest;
    private final MavenArtifact shadedModel;

    public static String getXMLContent(String name, List<Artifact> artifacts, String mainClass, Map<String, String> manifestEntries) {
        StringBuilder builder = new StringBuilder();
        builder.append("<shaded-model>").append(System.lineSeparator());
        builder.append("<name>").append(name).append("</name>").append(System.lineSeparator());
        builder.append("<shaded-dependencies>").append(System.lineSeparator());
        for (Artifact a : artifacts) {
            builder.append(getDependency(a)).append(System.lineSeparator());
        }
        builder.append("</shaded-dependencies>").append(System.lineSeparator());
        if (mainClass != null) {
            builder.append("<main-class>");
            builder.append(mainClass);
            builder.append("</main-class>").append(System.lineSeparator());
        }
        if (manifestEntries != null) {
            builder.append("<manifestEntries>").append(System.lineSeparator());
            for (Map.Entry<String, String> entry : manifestEntries.entrySet()) {
                builder.append("<" + entry.getKey() + ">");
                builder.append(entry.getValue());
                builder.append("</" + entry.getKey() + ">").append(System.lineSeparator());
            }
            builder.append("</manifestEntries>").append(System.lineSeparator());
        }
        builder.append("</shaded-model>").append(System.lineSeparator());
        return builder.toString();
    }

    ShadedModel(MavenArtifact shadedModel,
            ProvisioningRuntime runtime,
            WfInstallPlugin.ArtifactResolver artifactResolver,
            MessageWriter log, Map<String, String> mergedArtifactVersions) throws IOException, ProvisioningDescriptionException {
        this.shadedModel = shadedModel;
        this.runtime = runtime;
        this.artifactResolver = artifactResolver;
        this.log = log;
        this.mergedArtifactVersions = mergedArtifactVersions;
        final Builder builder = new Builder(false);
        try (BufferedReader reader = Files.newBufferedReader(shadedModel.getPath(), StandardCharsets.UTF_8)) {
            document = builder.build(reader);
        } catch (ParsingException e) {
            throw new IOException("Failed to parse document", e);
        }
        rootElement = document.getRootElement();
    }

    List<MavenArtifact> getArtifacts() throws ProvisioningException {
        List<MavenArtifact> artifacts = new ArrayList<>();
        Element shadedDependencies = rootElement.getFirstChildElement("shaded-dependencies",
                rootElement.getNamespaceURI());
        Elements dependencies = shadedDependencies.getChildElements();
        for (int i = 0; i < dependencies.size(); i++) {
            Element e = dependencies.get(i);
            MavenArtifact a = Utils.toArtifactCoords(mergedArtifactVersions, e.getValue(), false);
            artifactResolver.resolve(a);
            artifacts.add(a);
        }
        return artifacts;
    }

    Map<String, String> getManifestEntries() {
        Map<String, String> entries = new HashMap<>();
        Element manifestEntries = rootElement.getFirstChildElement("manifestEntries",
                rootElement.getNamespaceURI());
        Elements elements = manifestEntries.getChildElements();
        for (int i = 0; i < elements.size(); i++) {
            Element e = elements.get(i);
            entries.put(e.getLocalName(), e.getValue());
        }
        return entries;
    }

    private String getMainClass() {
        String ret = null;
        Element mainClass = rootElement.getFirstChildElement("main-class",
                rootElement.getNamespaceURI());
        if (mainClass != null) {
            ret = mainClass.getValue();
        }
        return ret;
    }

    private String getName() {
        return rootElement.getFirstChildElement("name",
                rootElement.getNamespaceURI()).getValue();
    }

    private static String getDependency(Artifact a) {
        System.out.println("[INFO] Including " + a.getGroupId() +":" + a.getArtifactId() +":"+a.getType()+":"+a.getVersion() + " in the shaded jar.");
        StringBuilder builder = new StringBuilder();
        builder.append("<dependency>");
        builder.append(a.getGroupId()).append(":");
        builder.append(a.getArtifactId()).append(":").append(":");
        if (a.getClassifier() != null && !a.getClassifier().isEmpty()) {
            builder.append(a.getClassifier());
        }
        builder.append(":");
        builder.append(a.getType());
        builder.append("</dependency>").append(System.lineSeparator());
        return builder.toString();
    }

    public void buildJar(Path shadedJar) throws IOException, ProvisioningException {
        System.out.println("ASSEMBLING " + shadedJar);
        Path tmpTarget = runtime.getTmpPath().resolve("assemble_target").resolve(shadedJar.getFileName());
        Files.createDirectories(tmpTarget);
        for (MavenArtifact dependency : getArtifacts()) {
            Utils.navigateArtifact(dependency.getPath(), tmpTarget, this);
        }
        update(tmpTarget);
        ZipUtils.zip(tmpTarget, shadedJar);
        IoUtils.recursiveDelete(tmpTarget);
    }

    public void update(Path target) throws IOException {
        Path targetMetaInf = target.resolve("META-INF");
        Path targetManifestPath = targetMetaInf.resolve("MANIFEST.MF");
        Manifest manifest;
        FileInputStream stream = null;
        try {
            if (!Files.exists(targetManifestPath)) {
                manifest = new Manifest();
            } else {
                stream = new FileInputStream(targetManifestPath.toFile());
                manifest = new Manifest(stream);
            }
            Attributes attributes = manifest.getMainAttributes();
            String mainClass = getMainClass();
            if (mainClass != null) {
                attributes.put(Attributes.Name.MAIN_CLASS, mainClass);
            }
            Map<String,String> manifestEntries = getManifestEntries();
            if (manifestEntries != null) {
                for (Map.Entry<String, String> entry : manifestEntries.entrySet()) {
                    if (entry.getValue() == null) {
                        attributes.remove(new Attributes.Name(entry.getKey()));
                    } else {
                        attributes.put(new Attributes.Name(entry.getKey()), entry.getValue());
                    }
                }
            }
            attributes.put(Attributes.Name.IMPLEMENTATION_TITLE, "Galleon shading of " + getName());
            attributes.put(Attributes.Name.SPECIFICATION_TITLE, "Galleon shading of " + getName());
            attributes.put(Attributes.Name.IMPLEMENTATION_VERSION, shadedModel.getVersion());
            Files.deleteIfExists(targetManifestPath);
            try (FileOutputStream out = new FileOutputStream(targetManifestPath.toFile())) {
                manifest.write(out);
            }
            Path moduleInfoPath = target.resolve("module-info.class");
            Files.deleteIfExists(moduleInfoPath);
            Path indexListPath = target.resolve("META-INF/INDEX.LIST");
            Files.deleteIfExists(indexListPath);
            Path services = target.resolve("META-INF").resolve("services");
            for (Map.Entry<String, List<String>> entry : serviceLoaders.entrySet()) {
                Path file = services.resolve(entry.getKey());
                Files.write(file, entry.getValue(), UTF_8);
            }
        } finally {
            if (stream != null) {
                stream.close();
            }
        }
    }

    @Override
    public boolean consume(Path resourcePath) throws IOException {
        String entry = resourcePath.toString().substring(1);
        if (entry.startsWith("META-INF/MANIFEST.MF")) {
            if (!seenManifest) {
                seenManifest = true;
                return true;
            } else {
                // Do not copy other Manifest files
                return false;
            }
        } else if (entry.startsWith("META-INF/services/")) {
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
}
