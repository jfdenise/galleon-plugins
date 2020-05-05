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
package org.wildfly.galleon.plugin;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.stream.Stream;
import nu.xom.Attribute;
import nu.xom.Builder;
import nu.xom.Document;
import nu.xom.Element;
import nu.xom.Elements;
import nu.xom.ParsingException;
import nu.xom.Serializer;
import org.jboss.galleon.util.IoUtils;

/**
 *
 * @author jdenise
 */
public final class JBossModuleTransformer {

    static final Map<String, String> MAPPING = new HashMap<>();
    static final Map<String, String> NAME_MAPPING = new HashMap<>();

    static {
        MAPPING.put("javax/annotation/api", "jakarta/annotation/api");
        MAPPING.put("javax/batch/api", "jakarta/batch/api");
        //MAPPING.put("javax/decorator");
        MAPPING.put("javax/ejb/api", "jakarta/ejb/api");
        MAPPING.put("javax/el/api", "jakarta/el/api");
        MAPPING.put("javax/enterprise/concurrent/api", "jakarta/enterprise/concurrent/api");
        MAPPING.put("javax/enterprise/api", "jakarta/enterprise/api");
        MAPPING.put("javax/faces/api", "jakarta/faces/api");
        MAPPING.put("javax/inject/api", "jakarta/inject/api");
        MAPPING.put("javax/interceptor/api", "jakarta/interceptor/api");
        MAPPING.put("javax/jms/api", "jakarta/jms/api");
        MAPPING.put("javax/json/api", "jakarta/json/api");
        MAPPING.put("javax/json/bind/api", "jakarta/json/bind/api");
        MAPPING.put("javax/mail/api", "jakarta/mail/api");
        MAPPING.put("javax/persistence/api", "jakarta/persistence/api");
        MAPPING.put("javax/resource/api", "jakarta/resource/api");
        MAPPING.put("javax/security/auth/message/api", "jakarta/security/auth/message/api");
        MAPPING.put("javax/security/enterprise/api", "jakarta/security/enterprise/api");
        MAPPING.put("javax/security/jacc/api", "jakarta/security/jacc/api");
        MAPPING.put("javax/servlet/api", "jakarta/servlet/api");
        MAPPING.put("javax/servlet/jsp/api", "jakarta/servlet/jsp/api");
        MAPPING.put("javax/servlet/jstl/api", "jakarta/servlet/jstl/api");
        MAPPING.put("javax/transaction/api", "jakarta/transaction/api");
        MAPPING.put("javax/validation/api", "jakarta/validation/api");
        MAPPING.put("javax/websocket/api", "jakarta/websocket/api");
        MAPPING.put("javax/ws/rs/api", "jakarta/ws/rs/api");
        //TO_TRANSFORM.add("javax/xml/registry");

        for (Entry<String, String> entry : MAPPING.entrySet()) {
            String key = entry.getKey().replaceAll("/", ".");
            String value = entry.getValue().replaceAll("/", ".");
            NAME_MAPPING.put(key, value);
        }
    }

    public static void transform(Path modules) throws IOException {
        Path wkDir = modules.getParent().resolve("transformed-modules");
        Map<Path, Set<Path>> files = new HashMap<>();
        visitLayers(modules, files);
        visitAddOns(modules, files);
        visitOtherModules(modules, files);

        try {
            for (Entry<Path, Set<Path>> entry : files.entrySet()) {
                //recreate the root dir
                Path targetRootDir = wkDir.resolve(entry.getKey());
                Path srcRootDir = modules.resolve(entry.getKey());
                Files.createDirectories(targetRootDir);
                for (Path path : entry.getValue()) {
                    Path transformed = path;
                    String name = null;
                    for (Entry<String, String> mapping : MAPPING.entrySet()) {
                        Path key = Paths.get(mapping.getKey());
                        Path value = Paths.get(mapping.getValue());
                        if (path.startsWith(key)) {
                            Path suffix = path.subpath(key.getNameCount(), path.getNameCount());
                            transformed = value.resolve(suffix);
                            name = value.toString().replaceAll("/", ".");
                            break;
                        }
                    }
                    //create the parent directory
                    Path parentDir = targetRootDir.resolve(transformed.getParent());
                    Files.createDirectories(parentDir);
                    Path target = parentDir.resolve(transformed.getFileName());
                    //copy content from original
                    Path src = srcRootDir.resolve(path);
                    if (src.getFileName().toString().equals("module.xml")) {
                        // Transform it
                        rewriteModuleDescriptor(src, target, name);
                    } else {
                        Files.copy(src, target);
                    }
                }
            }
            IoUtils.recursiveDelete(modules);
            Files.move(wkDir, modules);

        } finally {
            // Just in case something went wrong
            IoUtils.recursiveDelete(wkDir);
        }

    }

    private static void visitLayers(final Path srcModulesDir, Map<Path, Set<Path>> allFiles) throws IOException {
        final Path layersDir = srcModulesDir.resolve(WfConstants.SYSTEM).resolve(WfConstants.LAYERS);
        if (Files.exists(layersDir)) {
            try (Stream<Path> layers = Files.list(layersDir)) {
                final Iterator<Path> i = layers.iterator();
                while (i.hasNext()) {
                    Path p = i.next();
                    Set<Path> files = new HashSet<>();
                    allFiles.put(srcModulesDir.relativize(p), files);
                    visit(p, files);
                }
            }
        }

    }

    private static void visitAddOns(Path srcModulesDir, Map<Path, Set<Path>> allFiles) throws IOException {
        final Path addOnsDir = srcModulesDir.resolve(WfConstants.SYSTEM).resolve(WfConstants.ADD_ONS);
        if (Files.exists(addOnsDir)) {
            try (Stream<Path> addOn = Files.list(addOnsDir)) {
                final Iterator<Path> i = addOn.iterator();
                while (i.hasNext()) {
                    Path p = i.next();
                    Set<Path> files = new HashSet<>();
                    allFiles.put(srcModulesDir.relativize(p), files);
                    visit(p, files);
                }
            }
        }
    }

    private static void visitOtherModules(Path modulesDir, Map<Path, Set<Path>> allFiles) throws IOException {
        Set<Path> files = new HashSet<>();
        allFiles.put(Paths.get(""), files);
        Files.walkFileTree(modulesDir, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                // Skip system sub directory, it is handled when handling layers and add-ons
                if (dir.getFileName().toString().equals(WfConstants.SYSTEM) && dir.getParent().equals(modulesDir)) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                files.add(modulesDir.relativize(file));
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static void visit(Path source, Set<Path> files) throws IOException {
        Files.walkFileTree(source, EnumSet.of(FileVisitOption.FOLLOW_LINKS), Integer.MAX_VALUE,
                new SimpleFileVisitor<Path>() {

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                    throws IOException {

                files.add(source.relativize(file));

                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static void rewriteModuleDescriptor(Path orig, Path target, String name) throws IOException {
        final Builder builder = new Builder(false);
        final Document document;
        try (BufferedReader reader = Files.newBufferedReader(orig, StandardCharsets.UTF_8)) {
            document = builder.build(reader);
        } catch (ParsingException e) {
            throw new IOException("Failed to parse document", e);
        }
        final Element rootElement = document.getRootElement();
        if (!rootElement.getLocalName().equals("module")
                && !rootElement.getLocalName().equals("module-alias")) {
            return;
        }
        if (name != null) {
            final Attribute nameAttribute = rootElement.getAttribute("name");
            nameAttribute.setValue(name);
        }
        final Element dependenciesElement = rootElement.getFirstChildElement("dependencies", rootElement.getNamespaceURI());
        if (dependenciesElement != null) {
            final Elements modules = dependenciesElement.getChildElements("module", rootElement.getNamespaceURI());
            final int artifactCount = modules.size();
            for (int i = 0; i < artifactCount; i++) {
                final Element element = modules.get(i);
                Attribute modNameAttribute = element.getAttribute("name");
                String transformed = NAME_MAPPING.get(modNameAttribute.getValue());
                if (transformed != null) {
                    modNameAttribute.setValue(transformed);
                }
            }
        }
        // now serialize the result
        Files.deleteIfExists(target);
        try (OutputStream outputStream = Files.newOutputStream(target)) {
            new Serializer(outputStream).write(document);
        } catch (Throwable t) {
            try {
                Files.deleteIfExists(target);
            } catch (Throwable t2) {
                t2.addSuppressed(t);
                throw t2;
            }
            throw t;
        }
    }
}
