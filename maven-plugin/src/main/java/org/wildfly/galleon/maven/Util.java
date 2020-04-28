/*
 * Copyright 2016-2019 Red Hat, Inc. and/or its affiliates
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

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.FileVisitor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import javax.xml.stream.XMLStreamException;
import nu.xom.Attribute;
import nu.xom.Builder;
import nu.xom.Document;
import nu.xom.Element;
import nu.xom.Elements;
import nu.xom.ParsingException;
import nu.xom.Serializer;

import org.apache.maven.plugin.MojoExecutionException;
import org.jboss.galleon.Errors;
import org.jboss.galleon.util.IoUtils;
import org.wildfly.galleon.plugin.WfConstants;

/**
 *
 * @author Alexey Loubyansky
 */
class Util {
    static Set<String> TO_TRANSFORM = new HashSet<>();

    static {
        TO_TRANSFORM.add("javax.annotation.api");
        TO_TRANSFORM.add("javax.batch.api");
        //TO_TRANSFORM.add(Paths.get("javax/decorator");
        TO_TRANSFORM.add("javax.ejb.api");
        TO_TRANSFORM.add("javax.el.api");
        TO_TRANSFORM.add("javax.enterprise.concurrent.api");
        TO_TRANSFORM.add("javax.enterprise.api");
        TO_TRANSFORM.add("javax.faces.api");
        TO_TRANSFORM.add("javax.inject.api");
        TO_TRANSFORM.add("javax.interceptor.api");
        TO_TRANSFORM.add("javax.jms.api");
        TO_TRANSFORM.add("javax.json.api");
        TO_TRANSFORM.add("javax.json.bind.api");
        TO_TRANSFORM.add("javax.mail.api");
        TO_TRANSFORM.add("javax.persistence.api");
        TO_TRANSFORM.add("javax.resource.api");
        TO_TRANSFORM.add("javax.security.auth.message.api");
        TO_TRANSFORM.add("javax.security.enterprise.api");
        TO_TRANSFORM.add("javax.security.jacc.api");
        TO_TRANSFORM.add("javax.servlet.api");
        TO_TRANSFORM.add("javax.servlet.jsp.api");
        TO_TRANSFORM.add("javax.servlet.jstl.api");
        TO_TRANSFORM.add("javax.transaction.api");
        TO_TRANSFORM.add("javax.validation.api");
        TO_TRANSFORM.add("javax.websocket.api");
        TO_TRANSFORM.add("javax.ws.rs.api");
        //TO_TRANSFORM.add("javax/xml/registry");
    }

    static Set<Path> TO_TRANSFORM_PATH = new HashSet<>();

    static {
        TO_TRANSFORM_PATH.add(Paths.get("system/layers/base/javax/annotation/api"));
        TO_TRANSFORM_PATH.add(Paths.get("system/layers/base/javax/batch/api"));
        //TO_TRANSFORM.add(Paths.get("javax/decorator");
        TO_TRANSFORM_PATH.add(Paths.get("system/layers/base/javax/ejb/api"));
        TO_TRANSFORM_PATH.add(Paths.get("system/layers/base/javax/el/api"));
        TO_TRANSFORM_PATH.add(Paths.get("system/layers/base/javax/enterprise/concurrent/api"));
        TO_TRANSFORM_PATH.add(Paths.get("system/layers/base/javax/enterprise/api"));
        TO_TRANSFORM_PATH.add(Paths.get("system/layers/base/javax/faces/api"));
        TO_TRANSFORM_PATH.add(Paths.get("system/layers/base/javax/inject/api"));
        TO_TRANSFORM_PATH.add(Paths.get("system/layers/base/javax/interceptor/api"));
        TO_TRANSFORM_PATH.add(Paths.get("system/layers/base/javax/jms/api"));
        TO_TRANSFORM_PATH.add(Paths.get("system/layers/base/javax/json/api"));
        TO_TRANSFORM_PATH.add(Paths.get("system/layers/base/javax/json/bind/api"));
        TO_TRANSFORM_PATH.add(Paths.get("system/layers/base/javax/mail/api"));
        TO_TRANSFORM_PATH.add(Paths.get("system/layers/base/javax/persistence/api"));
        TO_TRANSFORM_PATH.add(Paths.get("system/layers/base/javax/resource/api"));
        TO_TRANSFORM_PATH.add(Paths.get("system/layers/base/javax/security/auth/message/api"));
        TO_TRANSFORM_PATH.add(Paths.get("system/layers/base/javax/security/enterprise/api"));
        TO_TRANSFORM_PATH.add(Paths.get("system/layers/base/javax/security/jacc/api"));
        TO_TRANSFORM_PATH.add(Paths.get("system/layers/base/javax/servlet/api"));
        TO_TRANSFORM_PATH.add(Paths.get("system/layers/base/javax/servlet/jsp/api"));
        TO_TRANSFORM_PATH.add(Paths.get("system/layers/base/javax/servlet/jstl/api"));
        TO_TRANSFORM_PATH.add(Paths.get("system/layers/base/javax/transaction/api"));
        TO_TRANSFORM_PATH.add(Paths.get("system/layers/base/javax/validation/api"));
        TO_TRANSFORM_PATH.add(Paths.get("system/layers/base/javax/websocket/api"));
        TO_TRANSFORM_PATH.add(Paths.get("system/layers/base/javax/ws/rs/api"));
        //TO_TRANSFORM.add("javax/xml/registry");
    }

    static Path transformPath(Path path) {
        Path transformed = Paths.get("");
        boolean seen = false;
        for (Path p : path) {
            if (p.toString().equals("javax") && !seen) {
                seen = true;
                transformed = transformed.resolve("jakarta");
            } else {
                transformed = transformed.resolve(p);
            }
        }
        return transformed;
    }

    static void rewriteModuleDescriptor(Path orig, Path target, String name) throws IOException {
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
                if (Util.TO_TRANSFORM.contains(modNameAttribute.getValue())) {
                    String newName = "jakarta." + modNameAttribute.getValue().substring("javax.".length());
                    modNameAttribute.setValue(newName);
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
    static WildFlyFeaturePackBuild loadFeaturePackBuildConfig(File configDir, String configFile) throws MojoExecutionException {
        final Path path = Paths.get(configDir.getAbsolutePath(), configFile);
        if (!Files.exists(path)) {
            throw new MojoExecutionException(Errors.pathDoesNotExist(path));
        }
        return loadFeaturePackBuildConfig(path);
    }

    static WildFlyFeaturePackBuild loadFeaturePackBuildConfig(Path configFile) throws MojoExecutionException {
        try (InputStream configStream = Files.newInputStream(configFile)) {
            return new FeaturePackBuildModelParser().parse(configStream);
        } catch (XMLStreamException e) {
            throw new MojoExecutionException(Errors.parseXml(configFile), e);
        } catch (IOException e) {
            throw new MojoExecutionException(Errors.openFile(configFile), e);
        }
    }

    static void mkdirs(final Path dir) throws MojoExecutionException {
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new MojoExecutionException(Errors.mkdirs(dir), e);
        }
    }

    static void copyIfExists(final Path resources, final Path fpDir, String resourceName) throws MojoExecutionException {
        final Path res = resources.resolve(resourceName);
        if (Files.exists(res)) {
            try {
                IoUtils.copy(res, fpDir.resolve(resourceName));
            } catch (IOException e) {
                throw new MojoExecutionException("Failed to copy " + resourceName + " to the feature-pack", e);
            }
        }
    }

    static void copyDirIfExists(final Path srcDir, final Path targetDir) throws MojoExecutionException {
        if (Files.exists(srcDir)) {
            try {
                IoUtils.copy(srcDir, targetDir);
            } catch (IOException e) {
                throw new MojoExecutionException(Errors.copyFile(srcDir, targetDir), e);
            }
        }
    }

    static void findModules(Path modulesDir, Map<String, Path> moduleXmlByPkgName) throws IOException {
        Files.walkFileTree(modulesDir, new FileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                // Skip system sub directory, it is handled when handling layers and add-ons
                if (dir.getFileName().toString().equals(WfConstants.SYSTEM) && dir.getParent().equals(modulesDir)) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                final Path moduleXml = dir.resolve(WfConstants.MODULE_XML);
                if (!Files.exists(moduleXml)) {
                    return FileVisitResult.CONTINUE;
                }

                String packageName;
                if (moduleXml.getParent().getFileName().toString().equals("main")) {
                    packageName = modulesDir.relativize(moduleXml.getParent().getParent()).toString();
                } else {
                    packageName = modulesDir.relativize(moduleXml.getParent()).toString();
                }
                packageName = packageName.replace(File.separatorChar, '.');
                if (TO_TRANSFORM.contains(packageName)) {
                    packageName = "jakarta." + packageName.substring("javax.".length());
                }
                moduleXmlByPkgName.put(packageName, moduleXml);
                return FileVisitResult.SKIP_SUBTREE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) throws IOException {
                return FileVisitResult.TERMINATE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                return FileVisitResult.CONTINUE;
            }
        });
    }

}
