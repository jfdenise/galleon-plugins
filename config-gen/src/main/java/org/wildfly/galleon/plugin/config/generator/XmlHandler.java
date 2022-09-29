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
package org.wildfly.galleon.plugin.config.generator;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import nu.xom.Attribute;
import nu.xom.Builder;
import nu.xom.Document;
import nu.xom.Element;
import nu.xom.Elements;
import nu.xom.ParsingException;
import nu.xom.Serializer;
import org.wildfly.galleon.plugin.XMLAttribute;
import org.wildfly.galleon.plugin.XMLDocument;
import org.wildfly.galleon.plugin.XMLElement;

/**
 *
 * @author jdenise
 */
public class XmlHandler {

    static class XOMDocument implements XMLDocument {

        Document document;

        XOMDocument(Document document) {
            this.document = document;
        }

        @Override
        public XMLElement getRootElement() {
            Element elem = document.getRootElement();
            if (elem != null) {
                return new XOMElement(elem);
            }
            return null;
        }
    }

    static class XOMAttribute implements XMLAttribute {

        Attribute attribute;

        XOMAttribute(Attribute attribute) {
            this.attribute = attribute;
        }

        @Override
        public String getValue() {
            return attribute.getValue();
        }

        @Override
        public void setLocalName(String name) {
            attribute.setLocalName(name);
        }

        @Override
        public void setValue(String value) {
            attribute.setValue(value);
        }

    }

    static class XOMElement implements XMLElement {

        Element element;

        XOMElement(Element element) {
            this.element = element;
        }

        @Override
        public String getLocalName() {
            return element.getLocalName();
        }

        @Override
        public XMLAttribute getAttribute(String name) {
            Attribute attr = element.getAttribute(name);
            if (attr != null) {
                return new XOMAttribute(attr);
            }
            return null;
        }

        @Override
        public void setLocalName(String resourceroot) {
            element.setLocalName(resourceroot);
        }

        @Override
        public String getNamespaceURI() {
            return element.getNamespaceURI();
        }

        @Override
        public List<XMLElement> getChildElements(String artifact, String namespaceURI) {
            List<XMLElement> elements = new ArrayList<>();
            Elements elms = element.getChildElements(artifact, namespaceURI);
            if (elms != null) {
                final int elemCount = elms.size();
                for (int i = 0; i < elemCount; i++) {
                    elements.add(new XOMElement(elms.get(i)));
                }
                return elements;
            }
            return null;
        }

        @Override
        public XMLElement getFirstChildElement(String resources, String namespaceURI) {
            Element e = element.getFirstChildElement(resources, namespaceURI);
            if (e != null) {
                return new XOMElement(e);
            }
            return null;
        }

    }

    public static XMLDocument buildDocument(Path moduleTemplate) throws IOException {
        final Builder builder = new Builder(false);
        Document document;
        try (BufferedReader reader = Files.newBufferedReader(moduleTemplate, StandardCharsets.UTF_8)) {
            document = builder.build(reader);
        } catch (ParsingException e) {
            throw new IOException("Failed to parse document", e);
        }
        return new XOMDocument(document);
    }

    public static void store(XMLDocument doc, Path targetPath) throws IOException {
        // now serialize the result
        XOMDocument xom = (XOMDocument) doc;
        try (OutputStream outputStream = Files.newOutputStream(targetPath)) {
            new Serializer(outputStream).write(xom.document);
        } catch (Throwable t) {
            try {
                Files.deleteIfExists(targetPath);
            } catch (Throwable t2) {
                t2.addSuppressed(t);
                throw t2;
            }
            throw t;
        }
    }
}
