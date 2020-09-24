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
package org.wildfly.galleon.maven;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import javax.xml.namespace.QName;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;

import javax.xml.stream.XMLStreamException;

import org.jboss.galleon.Errors;
import org.jboss.galleon.ProvisioningDescriptionException;
import org.jboss.galleon.ProvisioningException;
import org.jboss.galleon.util.ParsingUtils;
import org.jboss.galleon.xml.XmlNameProvider;
import org.jboss.staxmapper.XMLElementReader;
import org.jboss.staxmapper.XMLExtendedStreamReader;
import org.jboss.staxmapper.XMLMapper;

/**
 * Parse LayersInfo that have been enriched. Dependencies are not taken into
 * account.
 *
 * @author jdenise
 */
final class LayersInfoXmlParser implements XMLElementReader<LayersInfo.Builder> {

    private static final XMLInputFactory inputFactory;

    static {
        final XMLInputFactory tmpIF = XMLInputFactory.newInstance();
        setIfSupported(tmpIF, XMLInputFactory.IS_VALIDATING, Boolean.FALSE);
        setIfSupported(tmpIF, XMLInputFactory.SUPPORT_DTD, Boolean.FALSE);
        inputFactory = tmpIF;
    }

    private static void setIfSupported(final XMLInputFactory inputFactory, final String property, final Object value) {
        if (inputFactory.isPropertySupported(property)) {
            inputFactory.setProperty(property, value);
        }
    }
    private static final String NAMESPACE = "urn:wildfly:galleon-plugins:layers-info:1.0";

    enum Attribute implements XmlNameProvider {

        DEPRECATED("deprecated"),
        FPL("feature-pack-location"),
        NAME("name"),
        OPTIONAL("optional"),
        VISIBILITY("visibility");

        private static final Map<String, Attribute> attributes;

        static {
            attributes = new HashMap<>(10);
            attributes.put(DEPRECATED.name, DEPRECATED);
            attributes.put(FPL.name, FPL);
            attributes.put(NAME.name, NAME);
            attributes.put(OPTIONAL.name, OPTIONAL);
            attributes.put(VISIBILITY.name, VISIBILITY);
        }

        static Attribute of(String name) {
            return attributes.get(name);
        }

        private final String name;

        Attribute(final String name) {
            this.name = name;
        }

        @Override
        public String getLocalName() {
            return name;
        }

        @Override
        public String getNamespace() {
            return null;
        }

        @Override
        public String toString() {
            return name;
        }

    }

    enum Element implements XmlNameProvider {

        LAYERSINFO("layers-info"),
        LAYER("layer"),
        DESCRIPTION("description"),
        DEPENDENCIES("dependencies"),
        DEPENDENCY("dependency");
        private final String name;
        private final String namespace = NAMESPACE;

        private static final Map<String, Element> elementsByLocal;

        static {
            elementsByLocal = new HashMap<>();
            elementsByLocal.put(DESCRIPTION.name, DESCRIPTION);
            elementsByLocal.put(LAYERSINFO.name, LAYERSINFO);
            elementsByLocal.put(LAYER.name, LAYER);
            elementsByLocal.put(DEPENDENCIES.name, DEPENDENCIES);
            elementsByLocal.put(DEPENDENCY.name, DEPENDENCY);
        }

        static Element of(String localName) {
            return elementsByLocal.get(localName);
        }

        Element(final String name) {
            this.name = name;
        }

        @Override
        public String getNamespace() {
            return namespace;
        }

        @Override
        public String getLocalName() {
            return name;
        }

    }

    private static final QName ROOT = new QName(NAMESPACE, Element.LAYERSINFO.getLocalName());
    private static final LayersInfoXmlParser INSTANCE = new LayersInfoXmlParser();

    private static LayersInfoXmlParser getInstance() {
        return INSTANCE;
    }

    public static LayersInfo parse(Path path) throws ProvisioningException {
        if (!Files.exists(path)) {
            return null;
        }
        try (BufferedReader reader = Files.newBufferedReader(path)) {
            return getInstance().parse(reader);
        } catch (IOException | XMLStreamException e) {
            throw new ProvisioningException(Errors.parseXml(path), e);
        }
    }

    private LayersInfoXmlParser() {
    }

    private LayersInfo parse(final Reader input) throws XMLStreamException, ProvisioningDescriptionException {
        XMLMapper mapper = XMLMapper.Factory.create();
        mapper.registerRootElement(ROOT, this);
        LayersInfo.Builder builder = new LayersInfo.Builder();
        mapper.parseDocument(builder, inputFactory.createXMLStreamReader(input));
        return builder.build();
    }

    @Override
    public void readElement(XMLExtendedStreamReader reader, LayersInfo.Builder builder) throws XMLStreamException {
        // We don't read the location, we don't need it for enrichment.
        while (reader.hasNext()) {
            switch (reader.nextTag()) {
                case XMLStreamConstants.END_ELEMENT: {
                    return;
                }
                case XMLStreamConstants.START_ELEMENT: {
                    final Element element = Element.of(reader.getLocalName());
                    switch (element) {
                        case LAYER:
                            readLayer(reader, builder);
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

    private static void readLayer(XMLExtendedStreamReader reader, LayersInfo.Builder builder) throws XMLStreamException {
        // Read attributes

        String name = null;
        String visibility = null;
        String deprecated = null;
        Boolean isDeprecated = null;
        final int count = reader.getAttributeCount();
        for (int i = 0; i < count; i++) {
            final Attribute attribute = Attribute.of(reader.getAttributeName(i).getLocalPart());
            switch (attribute) {
                case NAME:
                    name = reader.getAttributeValue(i);
                    break;
                case VISIBILITY:
                    visibility = reader.getAttributeValue(i);
                    break;
                case DEPRECATED:
                    deprecated = reader.getAttributeValue(i);
                    if (!deprecated.equals("true") && !deprecated.equals("false")) {
                        throw new RuntimeException("deprecated can be true or false.");
                    }
                    isDeprecated = "true".equals(deprecated) ? true : false;
                    break;
                default:
                    throw ParsingUtils.unexpectedContent(reader);
            }
        }
        if (name == null) {
            throw new RuntimeException("Invalid layer with no name");
        }
        LayerInfo layerInfo = new LayerInfo(name);
        builder.addLayer(layerInfo);
        layerInfo.setVisibility(visibility);
        if (isDeprecated != null) {
            layerInfo.setDeprecated(isDeprecated);
        }
        while (reader.hasNext()) {
            switch (reader.nextTag()) {
                case XMLStreamConstants.END_ELEMENT: {
                    return;
                }
                case XMLStreamConstants.START_ELEMENT: {
                    final Element element = Element.of(reader.getName().getLocalPart());
                    switch (element) {
                        case DESCRIPTION:
                            layerInfo.setDescription(reader.getElementText());
                            break;
                        case DEPENDENCIES:
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
}
