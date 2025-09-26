/*
 * Copyright 2016-2024 Red Hat, Inc. and/or its affiliates
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

import javax.xml.namespace.QName;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import org.apache.commons.lang3.StringUtils;
import org.jboss.galleon.ProvisioningDescriptionException;
import org.jboss.galleon.config.ConfigModel;
import org.jboss.galleon.config.FeaturePackConfig;
import org.jboss.galleon.universe.FeaturePackLocation;
import org.jboss.galleon.universe.FeaturePackLocation.FPID;
import org.jboss.galleon.universe.galleon1.LegacyGalleon1Universe;
import org.jboss.galleon.util.ParsingUtils;
import org.jboss.galleon.xml.ConfigXml;
import org.jboss.galleon.xml.FeaturePackPackagesConfigParser10;
import org.jboss.galleon.xml.ProvisioningXmlParser30;
import org.jboss.galleon.xml.XmlNameProvider;
import org.jboss.staxmapper.XMLElementReader;
import org.jboss.staxmapper.XMLExtendedStreamReader;
import org.wildfly.galleon.maven.build.tasks.CopyResourcesTask;
import org.wildfly.galleon.plugin.ArtifactCoords;

/**
 * Parses the WildFly-based feature pack build config file (i.e. the config file that is
 * used to create a WildFly-based feature pack, not the config file inside the feature pack).
 *
 * @author Stuart Douglas
 * @author Eduardo Martins
 * @author Alexey Loubyansky
 */
class FeaturePackBuildModelParser35 implements XMLElementReader<WildFlyFeaturePackBuild.Builder> {

    public static final String NAMESPACE = "urn:wildfly:feature-pack-build:3.5";

    enum Element {

        BUILD("build"),
        CONFIG("config"),
        COPY("copy"),
        DEFAULT_CONFIGS("default-configs"),
        DEFAULT_PACKAGES("default-packages"),
        DEPENDENCIES("dependencies"),
        DEPENDENCY("dependency"),
        DOMAIN("domain"),
        EXTENSION("extension"),
        EXTENSIONS("extensions"),
        GENERATE_FEATURE_SPECS("generate-feature-specs"),
        GROUP("group"),
        HOST("host"),
        NAME("name"),
        PACKAGE("package"),
        PACKAGES("packages"),
        PACKAGE_SCHEMAS("package-schemas"),
        PATCH("patch"),
        PATCHES("patches"),
        PLUGIN("plugin"),
        PLUGINS("plugins"),
        PRODUCER("producer"),
        RESOURCES("resources"),
        STABILITY_LEVEL("stability-level"),
        STABILITY_LEVELS("stability-levels"),
        MINIMUM_STABILITY_LEVEL("minimum-stability-level"),
        CONFIG_STABILITY_LEVEL("config-stability-level"),
        PACKAGE_STABILITY_LEVEL("package-stability-level"),
        STANDALONE("standalone"),
        SYSTEM_PATHS("system-paths"),
        SYSTEM_PATH("system-path"),
        TRANSITIVE("transitive"),

        // default unknown element
        UNKNOWN(null);

        private static final Map<QName, Element> elements;

        static {
            Map<QName, Element> elementsMap = new HashMap<>(23);
            elementsMap.put(new QName(NAMESPACE, Element.BUILD.getLocalName()), Element.BUILD);
            elementsMap.put(new QName(NAMESPACE, Element.CONFIG.getLocalName()), Element.CONFIG);
            elementsMap.put(new QName(NAMESPACE, Element.COPY.getLocalName()), Element.COPY);
            elementsMap.put(new QName(NAMESPACE, Element.DEFAULT_CONFIGS.getLocalName()), Element.DEFAULT_CONFIGS);
            elementsMap.put(new QName(NAMESPACE, Element.DEFAULT_PACKAGES.getLocalName()), Element.DEFAULT_PACKAGES);
            elementsMap.put(new QName(NAMESPACE, Element.DEPENDENCIES.getLocalName()), Element.DEPENDENCIES);
            elementsMap.put(new QName(NAMESPACE, Element.DEPENDENCY.getLocalName()), Element.DEPENDENCY);
            elementsMap.put(new QName(NAMESPACE, Element.DOMAIN.getLocalName()), Element.DOMAIN);
            elementsMap.put(new QName(NAMESPACE, Element.EXTENSION.getLocalName()), Element.EXTENSION);
            elementsMap.put(new QName(NAMESPACE, Element.EXTENSIONS.getLocalName()), Element.EXTENSIONS);
            elementsMap.put(new QName(NAMESPACE, Element.GENERATE_FEATURE_SPECS.getLocalName()), Element.GENERATE_FEATURE_SPECS);
            elementsMap.put(new QName(NAMESPACE, Element.GROUP.getLocalName()), Element.GROUP);
            elementsMap.put(new QName(NAMESPACE, Element.HOST.getLocalName()), Element.HOST);
            elementsMap.put(new QName(NAMESPACE, Element.NAME.getLocalName()), Element.NAME);
            elementsMap.put(new QName(NAMESPACE, Element.PACKAGE.getLocalName()), Element.PACKAGE);
            elementsMap.put(new QName(NAMESPACE, Element.PACKAGES.getLocalName()), Element.PACKAGES);
            elementsMap.put(new QName(NAMESPACE, Element.PACKAGE_SCHEMAS.getLocalName()), Element.PACKAGE_SCHEMAS);
            elementsMap.put(new QName(NAMESPACE, Element.PATCH.getLocalName()), Element.PATCH);
            elementsMap.put(new QName(NAMESPACE, Element.PATCHES.getLocalName()), Element.PATCHES);
            elementsMap.put(new QName(NAMESPACE, Element.PLUGIN.getLocalName()), Element.PLUGIN);
            elementsMap.put(new QName(NAMESPACE, Element.PLUGINS.getLocalName()), Element.PLUGINS);
            elementsMap.put(new QName(NAMESPACE, Element.PRODUCER.getLocalName()), Element.PRODUCER);
            elementsMap.put(new QName(NAMESPACE, Element.RESOURCES.getLocalName()), Element.RESOURCES);
            elementsMap.put(new QName(NAMESPACE, Element.STANDALONE.getLocalName()), Element.STANDALONE);
            elementsMap.put(new QName(NAMESPACE, Element.TRANSITIVE.getLocalName()), Element.TRANSITIVE);
            elementsMap.put(new QName(NAMESPACE, Element.SYSTEM_PATHS.getLocalName()), Element.SYSTEM_PATHS);
            elementsMap.put(new QName(NAMESPACE, Element.SYSTEM_PATH.getLocalName()), Element.SYSTEM_PATH);
            elementsMap.put(new QName(NAMESPACE, Element.STABILITY_LEVELS.getLocalName()), Element.STABILITY_LEVELS);
            elementsMap.put(new QName(NAMESPACE, Element.STABILITY_LEVEL.getLocalName()), Element.STABILITY_LEVEL);
            elementsMap.put(new QName(NAMESPACE, Element.CONFIG_STABILITY_LEVEL.getLocalName()), Element.CONFIG_STABILITY_LEVEL);
            elementsMap.put(new QName(NAMESPACE, Element.PACKAGE_STABILITY_LEVEL.getLocalName()), Element.PACKAGE_STABILITY_LEVEL);
            elementsMap.put(new QName(NAMESPACE, Element.MINIMUM_STABILITY_LEVEL.getLocalName()), Element.MINIMUM_STABILITY_LEVEL);
            elements = elementsMap;
        }

        static Element of(QName qName) {
            QName name;
            if (qName.getNamespaceURI().equals("")) {
                name = new QName(NAMESPACE, qName.getLocalPart());
            } else {
                name = qName;
            }
            final Element element = elements.get(name);
            return element == null ? UNKNOWN : element;
        }

        private final String name;

        Element(final String name) {
            this.name = name;
        }

        /**
         * Get the local name of this element.
         *
         * @return the local name
         */
        public String getLocalName() {
            return name;
        }
    }

    enum Attribute implements XmlNameProvider {

        ARTIFACT("artifact"),
        ARTIFACT_ID("artifact-id"),
        FAMILY("family"),
        FAMILY_MEMBER_ALLOWED("familyMemberAllowed"),
        GROUP_ID("group-id"),
        ID("id"),
        NAME("name"),
        PRODUCER("producer"),
        TO("to"),
        TRANSLATE_TO_FPL("translate-to-fpl"),
        VERSION("version"),
        PATH("path"),

        // default unknown attribute
        UNKNOWN(null);

        private static final Map<QName, Attribute> attributes;

        static {
            Map<QName, Attribute> attributesMap = new HashMap<>(9);
            attributesMap.put(new QName(FAMILY_MEMBER_ALLOWED.getLocalName()), FAMILY_MEMBER_ALLOWED);
            attributesMap.put(new QName(ARTIFACT.getLocalName()), ARTIFACT);
            attributesMap.put(new QName(ARTIFACT_ID.getLocalName()), ARTIFACT_ID);
            attributesMap.put(new QName(FAMILY.getLocalName()), FAMILY);
            attributesMap.put(new QName(GROUP_ID.getLocalName()), GROUP_ID);
            attributesMap.put(new QName(ID.getLocalName()), ID);
            attributesMap.put(new QName(NAME.getLocalName()), NAME);
            attributesMap.put(new QName(PRODUCER.getLocalName()), PRODUCER);
            attributesMap.put(new QName(TO.getLocalName()), TO);
            attributesMap.put(new QName(TRANSLATE_TO_FPL.getLocalName()), TRANSLATE_TO_FPL);
            attributesMap.put(new QName(VERSION.getLocalName()), VERSION);
            attributesMap.put(new QName(PATH.getLocalName()), PATH);
            attributes = attributesMap;
        }

        static Attribute of(QName qName) {
            final Attribute attribute = attributes.get(qName);
            return attribute == null ? UNKNOWN : attribute;
        }

        private final String name;

        Attribute(final String name) {
            this.name = name;
        }

        /**
         * Get the local name of this element.
         *
         * @return the local name
         */
        @Override
        public String getLocalName() {
            return name;
        }

        @Override
        public String getNamespace() {
            return null;
        }
    }

    FeaturePackBuildModelParser35() {
    }

    @Override
    public void readElement(final XMLExtendedStreamReader reader, final WildFlyFeaturePackBuild.Builder builder) throws XMLStreamException {

        FeaturePackLocation fpl = null;
        String family = null;
        final int count = reader.getAttributeCount();
        for (int i = 0; i < count; i++) {
            final Attribute attribute = Attribute.of(reader.getAttributeName(i));
            switch (attribute) {
                case PRODUCER:
                    fpl = FeaturePackLocation.fromString(reader.getAttributeValue(i));
                    break;
                case FAMILY:
                    family = reader.getAttributeValue(i);
                    break;
                default:
                    throw ParsingUtils.unexpectedContent(reader);
            }
        }
        if (fpl == null) {
            throw ParsingUtils.missingAttributes(reader.getLocation(), Collections.singleton(Attribute.PRODUCER));
        }
        builder.setProducer(fpl);
        builder.setFamily(family);
        while (reader.hasNext()) {
            switch (reader.nextTag()) {
                case XMLStreamConstants.END_ELEMENT: {
                    return;
                }
                case XMLStreamConstants.START_ELEMENT: {
                    final Element element = Element.of(reader.getName());

                    switch (element) {
                        case DEPENDENCIES:
                            parseDependencies(reader, builder);
                            break;
                        case DEFAULT_PACKAGES:
                            parseDefaultPackages(reader, builder);
                            break;
                        case PACKAGE_SCHEMAS:
                            parsePackageSchemas(reader, builder);
                            break;
                        case CONFIG:
                            final ConfigModel.Builder config = ConfigModel.builder();
                            ConfigXml.readConfig(reader, config);
                            try {
                                builder.addConfig(config.build());
                            } catch (ProvisioningDescriptionException e) {
                                throw new XMLStreamException("Failed to create a config model instance", e);
                            }
                            break;
                        case TRANSITIVE:
                            parseTransitive(reader, builder);
                            break;
                        case PLUGINS:
                            parsePlugins(reader, builder);
                            break;
                        case RESOURCES:
                            parseResources(reader, builder);
                            break;
                        case GENERATE_FEATURE_SPECS:
                            parseGenerateFeatureSpecs(reader, builder);
                            break;
                        case SYSTEM_PATHS:
                            parseSystemPaths(reader, builder);
                            break;
                        case STABILITY_LEVELS:
                            parseStabilityLevels(reader, builder);
                            break;
                        default:
                            throw ParsingUtils.unexpectedContent(reader);
                    }
                    break;
                }
                default: {
                    throw ParsingUtils.unexpectedContent(reader);
                }
            }
        }
        throw ParsingUtils.endOfDocument(reader.getLocation());
    }

    private static void parseDefaultPackages(final XMLStreamReader reader, final WildFlyFeaturePackBuild.Builder builder) throws XMLStreamException {
        while (reader.hasNext()) {
            switch (reader.nextTag()) {
                case XMLStreamConstants.END_ELEMENT: {
                    return;
                }
                case XMLStreamConstants.START_ELEMENT: {
                    final Element element = Element.of(reader.getName());
                    switch (element) {
                        case PACKAGE:
                            builder.addDefaultPackage(parseName(reader));
                            break;
                        default:
                            throw ParsingUtils.unexpectedContent(reader);
                    }
                    break;
                }
                default: {
                    throw ParsingUtils.unexpectedContent(reader);
                }
            }
        }
        throw ParsingUtils.endOfDocument(reader.getLocation());
    }

    private static void parseDependencies(final XMLExtendedStreamReader reader, final WildFlyFeaturePackBuild.Builder builder) throws XMLStreamException {
        while (reader.hasNext()) {
            switch (reader.nextTag()) {
                case XMLStreamConstants.END_ELEMENT: {
                    return;
                }
                case XMLStreamConstants.START_ELEMENT: {
                    final Element element = Element.of(reader.getName());
                    switch (element) {
                        case DEPENDENCY:
                            parseDependency(reader, builder, false);
                            break;
                        default:
                            throw ParsingUtils.unexpectedContent(reader);
                    }
                    break;
                }
                default: {
                    throw ParsingUtils.unexpectedContent(reader);
                }
            }
        }
        throw ParsingUtils.endOfDocument(reader.getLocation());
    }

    private static void parseDependency(XMLExtendedStreamReader reader, final WildFlyFeaturePackBuild.Builder builder, boolean transitive) throws XMLStreamException {
        String groupId = null;
        String artifactId = null;
        String version = null;
        boolean translateToFpl = false;
        boolean allowDependencyOnFamilyMember = false;
        final int count = reader.getAttributeCount();
        final Set<Attribute> required = EnumSet.of(Attribute.GROUP_ID, Attribute.ARTIFACT_ID);
        for (int i = 0; i < count; i++) {
            final Attribute attribute = Attribute.of(reader.getAttributeName(i));
            required.remove(attribute);
            switch (attribute) {
                case GROUP_ID:
                    groupId = reader.getAttributeValue(i);
                    break;
                case ARTIFACT_ID:
                    artifactId = reader.getAttributeValue(i);
                    break;
                case VERSION:
                    version = reader.getAttributeValue(i);
                    break;
                case TRANSLATE_TO_FPL:
                    translateToFpl = Boolean.parseBoolean(reader.getAttributeValue(i));
                    break;
                case FAMILY_MEMBER_ALLOWED:
                    allowDependencyOnFamilyMember = Boolean.parseBoolean(reader.getAttributeValue(i));
                    break;
                default:
                    throw ParsingUtils.unexpectedAttribute(reader, i);
            }
        }
        if (!required.isEmpty()) {
            throw ParsingUtils.missingAttributes(reader.getLocation(), required);
        }
        final FeaturePackLocation fpl = translateToFpl ? LegacyGalleon1Universe.toFpl(groupId, artifactId, version)
                : FeaturePackLocation.fromString(groupId + ":" + artifactId + ":" + StringUtils.defaultIfEmpty(version, ""));
        String depName = null;
        final FeaturePackConfig.Builder depBuilder = transitive ? FeaturePackConfig.transitiveBuilder(fpl) : FeaturePackConfig.builder(fpl);
        while (reader.hasNext()) {
            switch (reader.nextTag()) {
                case XMLStreamConstants.END_ELEMENT: {
                    builder.addDependency(ArtifactCoords.newGav(groupId, artifactId, version), FeaturePackDependencySpec.create(depName, depBuilder.build()), allowDependencyOnFamilyMember);
                    return;
                }
                case XMLStreamConstants.START_ELEMENT: {
                    final Element element = Element.of(reader.getName());
                    switch (element) {
                        case NAME:
                            depName = reader.getElementText().trim();
                            break;
                        case DEFAULT_CONFIGS:
                            ProvisioningXmlParser30.parseDefaultConfigs(reader, depBuilder);
                            break;
                        case CONFIG:
                            final ConfigModel.Builder configBuilder = ConfigModel.builder();
                            ConfigXml.readConfig(reader, configBuilder);
                            try {
                                depBuilder.addConfig(configBuilder.build());
                            } catch (ProvisioningDescriptionException e) {
                                throw new XMLStreamException(e);
                            }
                            break;
                        case PACKAGES:
                            try {
                                FeaturePackPackagesConfigParser10.readPackages(reader, depBuilder);
                            } catch (ProvisioningDescriptionException e) {
                                throw new XMLStreamException(e);
                            }
                        break;
                        case PATCHES:
                            parsePatches(reader, depBuilder);
                            break;
                        default:
                            throw ParsingUtils.unexpectedContent(reader);
                    }
                    break;
                }
                default: {
                    throw ParsingUtils.unexpectedContent(reader);
                }
            }
        }
    }

    private static void parseTransitive(XMLExtendedStreamReader reader, WildFlyFeaturePackBuild.Builder builder) throws XMLStreamException {
        ParsingUtils.parseNoAttributes(reader);
        while (reader.hasNext()) {
            switch (reader.nextTag()) {
                case XMLStreamConstants.END_ELEMENT: {
                    return;
                }
                case XMLStreamConstants.START_ELEMENT: {
                    final Element element = Element.of(reader.getName());
                    switch (element) {
                        case DEPENDENCY:
                            parseDependency(reader, builder, true);
                            break;
                        default:
                            throw ParsingUtils.unexpectedContent(reader);
                    }
                    break;
                }
                default: {
                    throw ParsingUtils.unexpectedContent(reader);
                }
            }
        }
        throw ParsingUtils.endOfDocument(reader.getLocation());
    }

    private static String parseName(final XMLStreamReader reader) throws XMLStreamException {
        final int count = reader.getAttributeCount();
        String name = null;
        final Set<Attribute> required = EnumSet.of(Attribute.NAME);
        for (int i = 0; i < count; i++) {
            final Attribute attribute = Attribute.of(reader.getAttributeName(i));
            required.remove(attribute);
            switch (attribute) {
                case NAME:
                    name = reader.getAttributeValue(i);
                    break;
                default:
                    throw ParsingUtils.unexpectedContent(reader);
            }
        }
        if (!required.isEmpty()) {
            throw ParsingUtils.missingAttributes(reader.getLocation(), required);
        }
        ParsingUtils.parseNoContent(reader);
        return name;
    }

    private static void parsePackageSchemas(XMLStreamReader reader, WildFlyFeaturePackBuild.Builder builder) throws XMLStreamException {
        ParsingUtils.parseNoAttributes(reader);
        while (reader.hasNext()) {
            switch (reader.nextTag()) {
                case XMLStreamConstants.END_ELEMENT: {
                    return;
                }
                case XMLStreamConstants.START_ELEMENT: {
                    final Element element = Element.of(reader.getName());
                    switch (element) {
                        case GROUP:
                            builder.addSchemaGroup(parseName(reader));
                            break;
                        default:
                            throw ParsingUtils.unexpectedContent(reader);
                    }
                    break;
                }
                default: {
                    throw ParsingUtils.unexpectedContent(reader);
                }
            }
        }
        throw ParsingUtils.endOfDocument(reader.getLocation());
    }

    private static void parsePlugins(XMLExtendedStreamReader reader, WildFlyFeaturePackBuild.Builder builder) throws XMLStreamException {
        ParsingUtils.parseNoAttributes(reader);
        while (reader.hasNext()) {
            switch (reader.nextTag()) {
                case XMLStreamConstants.END_ELEMENT: {
                    return;
                }
                case XMLStreamConstants.START_ELEMENT: {
                    final Element element = Element.of(reader.getName());
                    switch (element) {
                        case PLUGIN:
                            parsePlugin(reader, builder);
                            break;
                        default:
                            throw ParsingUtils.unexpectedContent(reader);
                    }
                    break;
                }
                default: {
                    throw ParsingUtils.unexpectedContent(reader);
                }
            }
        }
        throw ParsingUtils.endOfDocument(reader.getLocation());
    }

    private static void parsePlugin(XMLExtendedStreamReader reader, WildFlyFeaturePackBuild.Builder builder) throws XMLStreamException {
        String id = null;
        ArtifactCoords coords = null;
        final int count = reader.getAttributeCount();
        for (int i = 0; i < count; i++) {
            final Attribute attribute = Attribute.of(reader.getAttributeName(i));
            switch (attribute) {
                case ID:
                    id = reader.getAttributeValue(i);
                    break;
                case ARTIFACT:
                    coords = ArtifactCoordsUtil.fromJBossModules(reader.getAttributeValue(i), "jar");
                    break;
                default:
                    throw ParsingUtils.unexpectedContent(reader);
            }
        }
        ParsingUtils.parseNoContent(reader);
        if(coords == null) {
            throw new XMLStreamException(ParsingUtils.missingAttributes(reader.getLocation(), Collections.singleton(Attribute.ARTIFACT)));
        }
        if(id == null) {
            id = coords.getArtifactId();
        }
        builder.addPlugin(id, coords);
    }

    private static void parseResources(final XMLExtendedStreamReader reader, final WildFlyFeaturePackBuild.Builder builder) throws XMLStreamException {
        while (reader.hasNext()) {
            switch (reader.nextTag()) {
                case XMLStreamConstants.END_ELEMENT: {
                    return;
                }
                case XMLStreamConstants.START_ELEMENT: {
                    final Element element = Element.of(reader.getName());
                    switch (element) {
                        case COPY:
                            builder.addResourcesTask(parseCopy(reader));
                            break;
                        default:
                            throw ParsingUtils.unexpectedContent(reader);
                    }
                    break;
                }
                default: {
                    throw ParsingUtils.unexpectedContent(reader);
                }
            }
        }
        throw ParsingUtils.endOfDocument(reader.getLocation());
    }

    private static CopyResourcesTask parseCopy(XMLStreamReader reader) throws XMLStreamException {
        final CopyResourcesTask copy = new CopyResourcesTask();
        final int count = reader.getAttributeCount();
        for (int i = 0; i < count; i++) {
            final Attribute attribute = Attribute.of(reader.getAttributeName(i));
            switch (attribute) {
                case ARTIFACT:
                    copy.setArtifact(reader.getAttributeValue(i));
                    break;
                case TO:
                    copy.setTo(reader.getAttributeValue(i));
                    break;
                default:
                    throw ParsingUtils.unexpectedContent(reader);
            }
        }
        final String error = copy.getValidationErrors();
        if(error != null) {
            throw new XMLStreamException(ParsingUtils.error(error, reader.getLocation()));
        }
        ParsingUtils.parseNoContent(reader);
        return copy;
    }

    private static void parseGenerateFeatureSpecs(final XMLExtendedStreamReader reader, final WildFlyFeaturePackBuild.Builder builder) throws XMLStreamException {
        while (reader.hasNext()) {
            switch (reader.nextTag()) {
                case XMLStreamConstants.END_ELEMENT: {
                    return;
                }
                case XMLStreamConstants.START_ELEMENT: {
                    final Element element = Element.of(reader.getName());
                    switch (element) {
                        case EXTENSIONS:
                            parseExtensions(reader, builder);
                            break;
                        default:
                            throw ParsingUtils.unexpectedContent(reader);
                    }
                    break;
                }
                default: {
                    throw ParsingUtils.unexpectedContent(reader);
                }
            }
        }
        throw ParsingUtils.endOfDocument(reader.getLocation());
    }

    private static void parseSystemPaths(final XMLExtendedStreamReader reader, final WildFlyFeaturePackBuild.Builder builder) throws XMLStreamException {
        while (reader.hasNext()) {
            switch (reader.nextTag()) {
                case XMLStreamConstants.END_ELEMENT: {
                    return;
                }
                case XMLStreamConstants.START_ELEMENT: {
                    final Element element = Element.of(reader.getName());
                    switch (element) {
                        case SYSTEM_PATH:
                            builder.addSystemPath(readSystemPath(reader));
                            break;
                        default:
                            throw ParsingUtils.unexpectedContent(reader);
                    }
                    break;
                }
                default: {
                    throw ParsingUtils.unexpectedContent(reader);
                }
            }
        }
        throw ParsingUtils.endOfDocument(reader.getLocation());
    }

    private static void parseStabilityLevels(final XMLExtendedStreamReader reader, final WildFlyFeaturePackBuild.Builder builder) throws XMLStreamException {
        while (reader.hasNext()) {
            switch (reader.nextTag()) {
                case XMLStreamConstants.END_ELEMENT: {
                    return;
                }
                case XMLStreamConstants.START_ELEMENT: {
                    final Element element = Element.of(reader.getName());
                    switch (element) {
                        case STABILITY_LEVEL:
                            builder.setStabilityLevel(reader.getElementText().trim());
                            break;
                        case CONFIG_STABILITY_LEVEL:
                            builder.setConfigStabilityLevel(reader.getElementText().trim());
                            break;
                        case PACKAGE_STABILITY_LEVEL:
                            builder.setPackageStabilityLevel(reader.getElementText().trim());
                            break;
                        case MINIMUM_STABILITY_LEVEL:
                            builder.setMinimumStabilityLevel(reader.getElementText().trim());
                            break;
                        default:
                            throw ParsingUtils.unexpectedContent(reader);
                    }
                    break;
                }
                default: {
                    throw ParsingUtils.unexpectedContent(reader);
                }
            }
        }
        throw ParsingUtils.endOfDocument(reader.getLocation());
    }

    private static String readSystemPath(XMLExtendedStreamReader reader) throws XMLStreamException {
        String path = null;
        for (int i = 0; i < reader.getAttributeCount(); i++) {
            final Attribute attribute = Attribute.of(new QName(reader.getAttributeName(i).getLocalPart()));
            switch (attribute) {
                case PATH:
                    path = reader.getAttributeValue(i);
                    if (Paths.get(path).isAbsolute()) {
                        throw new XMLStreamException(ParsingUtils.error(
                           "The content of 'system-path' element should be a path relative to installation base.",
                           reader.getLocation()));
                    }
                    break;
                default:
                    throw ParsingUtils.unexpectedContent(reader);
            }
        }
        if (path == null) {
            throw ParsingUtils.missingAttributes(reader.getLocation(), Collections.singleton(Attribute.PATH));
        }
        ParsingUtils.parseNoContent(reader);
        return path;
    }

    private static void parseExtensions(final XMLExtendedStreamReader reader, final WildFlyFeaturePackBuild.Builder builder) throws XMLStreamException {
        while (reader.hasNext()) {
            switch (reader.nextTag()) {
                case XMLStreamConstants.END_ELEMENT: {
                    return;
                }
                case XMLStreamConstants.START_ELEMENT: {
                    final Element element = Element.of(reader.getName());
                    switch (element) {
                        case STANDALONE:
                            parseExtensions(reader, builder, Element.STANDALONE);
                            break;
                        case DOMAIN:
                            parseExtensions(reader, builder, Element.DOMAIN);
                            break;
                        case HOST:
                            parseExtensions(reader, builder, Element.HOST);
                            break;
                        default:
                            throw ParsingUtils.unexpectedContent(reader);
                    }
                    break;
                }
                default: {
                    throw ParsingUtils.unexpectedContent(reader);
                }
            }
        }
        throw ParsingUtils.endOfDocument(reader.getLocation());
    }

    private static void parseExtensions(XMLExtendedStreamReader reader, WildFlyFeaturePackBuild.Builder builder, Element e) throws XMLStreamException {
        ParsingUtils.parseNoAttributes(reader);
        while (reader.hasNext()) {
            switch (reader.nextTag()) {
                case XMLStreamConstants.END_ELEMENT: {
                    return;
                }
                case XMLStreamConstants.START_ELEMENT: {
                    final Element element = Element.of(reader.getName());
                    switch (element) {
                        case EXTENSION:
                            switch(e) {
                                case STANDALONE:
                                    builder.addStandaloneExtension(reader.getElementText().trim());
                                    break;
                                case DOMAIN:
                                    builder.addDomainExtension(reader.getElementText().trim());
                                    break;
                                case HOST:
                                    builder.addHostExtension(reader.getElementText().trim());
                                    break;
                                default:
                                    throw new XMLStreamException("Unexpected extension target " + e, reader.getLocation());
                            }
                            break;
                        default:
                            throw ParsingUtils.unexpectedContent(reader);
                    }
                    break;
                }
                default: {
                    throw ParsingUtils.unexpectedContent(reader);
                }
            }
        }
        throw ParsingUtils.endOfDocument(reader.getLocation());
    }

    private static void parsePatches(XMLExtendedStreamReader reader, FeaturePackConfig.Builder fpConfigBuilder) throws XMLStreamException {
        ParsingUtils.parseNoAttributes(reader);
        while (reader.hasNext()) {
            switch (reader.nextTag()) {
                case XMLStreamConstants.END_ELEMENT: {
                    return;
                }
                case XMLStreamConstants.START_ELEMENT: {
                    final Element element = Element.of(new QName(reader.getLocalName()));
                    switch (element) {
                        case PATCH:
                            final FPID patchId = readPatch(reader);
                            try {
                                fpConfigBuilder.addPatch(patchId);
                            } catch (ProvisioningDescriptionException e) {
                                throw new XMLStreamException("Failed to add patch " + patchId + " to config", e);
                            }
                            break;
                        default:
                            throw ParsingUtils.unexpectedContent(reader);
                    }
                    break;
                }
                default: {
                    throw ParsingUtils.unexpectedContent(reader);
                }
            }
        }
        throw ParsingUtils.endOfDocument(reader.getLocation());
    }

    private static FPID readPatch(XMLExtendedStreamReader reader) throws XMLStreamException {
        FeaturePackLocation fpl = null;
        for (int i = 0; i < reader.getAttributeCount(); i++) {
            final Attribute attribute = Attribute.of(new QName(reader.getAttributeName(i).getLocalPart()));
            switch (attribute) {
                case ID:
                    fpl = FeaturePackLocation.fromString(reader.getAttributeValue(i));
                    break;
                default:
                    throw ParsingUtils.unexpectedContent(reader);
            }
        }
        if (fpl == null) {
            throw ParsingUtils.missingAttributes(reader.getLocation(), Collections.singleton(Attribute.ID));
        }
        ParsingUtils.parseNoContent(reader);
        return fpl.getFPID();
    }
}
