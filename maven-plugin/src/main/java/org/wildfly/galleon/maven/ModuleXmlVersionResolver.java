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

import com.google.common.base.Charsets;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;
import javax.xml.namespace.QName;

import javax.xml.stream.XMLEventFactory;
import javax.xml.stream.XMLEventReader;
import javax.xml.stream.XMLEventWriter;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.events.Attribute;
import javax.xml.stream.events.StartElement;
import javax.xml.stream.events.XMLEvent;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.DefaultArtifact;
import org.apache.maven.artifact.handler.DefaultArtifactHandler;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.logging.Log;
import org.wildfly.galleon.plugin.ArtifactCoords;


/**
 *
 * @author Emmanuel Hugonnet (c) 2017 Red Hat, inc.
 */
public class ModuleXmlVersionResolver {

    private static XMLOutputFactory XML_OUTPUT_FACTORY;
    private static XMLInputFactory XML_INPUT_FACTORY;

    private static XMLOutputFactory getXmlOutputFactory() {
        return XML_OUTPUT_FACTORY == null ? XML_OUTPUT_FACTORY = XMLOutputFactory.newInstance() : XML_OUTPUT_FACTORY;
    }

    private static XMLInputFactory getXmlInputFactory() {
        return XML_INPUT_FACTORY == null ? XML_INPUT_FACTORY = XMLInputFactory.newInstance() : XML_INPUT_FACTORY;
    }

    public static void convertModule(FeatureSpecGeneratorInvoker gen, final Path file, Path target, Map<String, Artifact> artifacts, List<Artifact> hardcodedArtifacts, Log log) throws IOException, XMLStreamException {
        Files.deleteIfExists(target);
        Files.createDirectories(target.getParent());
        try (Reader is = Files.newBufferedReader(file, Charsets.UTF_8);
                Writer out = Files.newBufferedWriter(target, Charsets.UTF_8, StandardOpenOption.CREATE_NEW)) {
            convert(target.getParent(), gen, getXmlInputFactory().createXMLEventReader(is), getXmlOutputFactory().createXMLEventWriter(out), artifacts, hardcodedArtifacts, log);
        }
    }

    private static void convert(Path dir, FeatureSpecGeneratorInvoker gen, final XMLEventReader r, final XMLEventWriter w, Map<String, Artifact> artifacts, List<Artifact> hardcodedArtifacts, Log log) throws IOException, XMLStreamException {
        XMLEventFactory eventFactory = XMLEventFactory.newInstance();
        while (r.hasNext()) {
            XMLEvent event = r.nextEvent();
            switch (event.getEventType()) {
                case XMLStreamConstants.START_ELEMENT:
                    StartElement startElement = event.asStartElement();
                    if ("module".equals(startElement.getName().getLocalPart())) {
                        StartElement convertedModule = convertModuleElement(eventFactory, startElement, artifacts);
                        log.debug(startElement + " has been converted to " + convertedModule);
                        w.add(convertedModule);
                    } else if ("artifact".equals(startElement.getName().getLocalPart())) {
                        StartElement convertedArtifact = convertArtifactElement(dir, gen, eventFactory, startElement, artifacts, hardcodedArtifacts, log);
                        log.debug(startElement + " has been converted to " + convertedArtifact);
                        w.add(convertedArtifact);
                    } else {
                        w.add(event);
                    }
                    break;
                case XMLStreamConstants.START_DOCUMENT:
                case XMLStreamConstants.END_DOCUMENT:
                case XMLStreamConstants.PROCESSING_INSTRUCTION:
                case XMLStreamConstants.END_ELEMENT:
                case XMLStreamConstants.COMMENT:
                case XMLStreamConstants.CDATA:
                case XMLStreamConstants.SPACE:
                case XMLStreamConstants.CHARACTERS:
                case XMLStreamConstants.NAMESPACE:
                    w.add(event);
                    break;
            }
        }
        w.flush();
        w.close();
    }

    private static StartElement convertArtifactElement(Path dir, FeatureSpecGeneratorInvoker gen, XMLEventFactory eventFactory, StartElement artifactElement, Map<String, Artifact> artifacts, List<Artifact> hardcodedArtifacts, Log log) {
        List<Attribute> attributes = new ArrayList<>();
        Iterator<?> iter = artifactElement.getAttributes();
        while (iter.hasNext()) {
            Attribute attribute = (Attribute) iter.next();
            if ("name".equals(attribute.getName().getLocalPart())) {
                String artifactName = attribute.getValue();
                String artifactCoords = getArtifactCoordinates(artifactName);
                if (artifactCoords != null) {
                    Artifact artifact = artifacts.get(artifactCoords);
                    if (artifact == null) {
                        log.warn("Couldn't locate artifact in the dependencies " + artifactCoords);
                        attributes.add(attribute);
                    } else {
                        StringJoiner joiner = new StringJoiner(":");
                        joiner.add(artifact.getGroupId());
                        joiner.add(artifact.getArtifactId());
                        joiner.add(artifact.getVersion());
                        if (artifact.hasClassifier()) {
                            joiner.add(artifact.getClassifier());
                        }
                        try {
                            artifact = gen.findArtifact(artifact);
                        } catch (MojoExecutionException ex) {
                            throw new RuntimeException(ex);
                        }
                        QName attrName = new QName(attribute.getName().getNamespaceURI(), "path", attribute.getName().getPrefix());
                        // transform
                        try {
                            Common.transformJarFile(artifact.getFile(), dir.resolve(artifact.getFile().getName()).toFile());
                        } catch (IOException ex) {
                            throw new RuntimeException(ex);
                        }
                        attributes.add(eventFactory.createAttribute(attrName, artifact.getFile().getName()));
                    }
                } else {
                    attributes.add(attribute);
                    final ArtifactCoords coords = ArtifactCoords.fromString(artifactName);
                    hardcodedArtifacts.add(new DefaultArtifact(coords.getGroupId(), coords.getArtifactId(), coords.getVersion(),
                            "provided", coords.getExtension(), coords.getClassifier(), new DefaultArtifactHandler(coords.getExtension())));
                }
            } else {
                //attributes.add(attribute);
            }
        }
        QName resource = new QName(artifactElement.getName().getNamespaceURI(), "resource-root", artifactElement.getName().getPrefix());
        return eventFactory.createStartElement(resource, attributes.iterator(), artifactElement.getNamespaces());
    }

    private static StartElement convertModuleElement(XMLEventFactory eventFactory, StartElement module, Map<String, Artifact> artifacts) {
        List<Attribute> attributes = new ArrayList<>();
        Iterator<?> iter = module.getAttributes();
        while (iter.hasNext()) {
            Attribute attribute = (Attribute) iter.next();
            if ("version".equals(attribute.getName().getLocalPart())) {
                String artifactName = attribute.getValue();
                String artifactCoords = getArtifactCoordinates(artifactName);
                if (artifactCoords != null) {
                    attributes.add(eventFactory.createAttribute("version", artifacts.get(artifactCoords).getVersion()));
                } else {
                    attributes.add(attribute);
                }
            } else {
                attributes.add(attribute);
            }
        }
        return eventFactory.createStartElement(module.getName(), attributes.iterator(), module.getNamespaces());
    }

    private static String getArtifactCoordinates(String artifactName) {
        String artifactCoords = null;
        if (artifactName.startsWith("${") && artifactName.endsWith("}")) {
            String ct = artifactName.substring(2, artifactName.length() - 1);
            if (ct.contains("?")) {
                String[] split = ct.split("\\?");
                artifactCoords = split[0];
            } else {
                artifactCoords = ct;
            }
        }
        return artifactCoords;
    }

    static void addHardCodedArtifacts(final Path file,  Set<String> hardcodedArtifacts) throws IOException, XMLStreamException {
        try (Reader is = Files.newBufferedReader(file, Charsets.UTF_8)) {
            addHardCodedArtifacts(getXmlInputFactory().createXMLEventReader(is), hardcodedArtifacts);
        }
    }

    private static void addHardCodedArtifacts(final XMLEventReader r,  Set<String> hardcodedArtifacts) throws IOException, XMLStreamException {
        while (r.hasNext()) {
            XMLEvent event = r.nextEvent();
            switch (event.getEventType()) {
                case XMLStreamConstants.START_ELEMENT:
                    StartElement startElement = event.asStartElement();
                    if ("artifact".equals(startElement.getName().getLocalPart())) {
                        addHardCodedArtifacts(startElement, hardcodedArtifacts);
                    }
                    break;
            }
        }
    }

    private static void addHardCodedArtifacts(StartElement artifactElement, Set<String> hardcodedArtifacts) {
        Iterator<?> iter = artifactElement.getAttributes();
        while (iter.hasNext()) {
            Attribute attribute = (Attribute) iter.next();
            if ("name".equals(attribute.getName().getLocalPart())) {
                String artifactName = attribute.getValue();
                String artifactCoords = getArtifactCoordinates(artifactName);
                if (artifactCoords == null) {
                    final ArtifactCoords coords = ArtifactCoords.fromString(artifactName);
                    final StringBuilder buf = new StringBuilder(coords.getGroupId()).append(':').
                            append(coords.getArtifactId()).append(':').append(coords.getVersion()).append(':');
                    String classifier = coords.getClassifier();
                    if (classifier != null && !classifier.isEmpty()) {
                        buf.append(classifier);
                    }
                    buf.append(':').append(coords.getExtension());
                    hardcodedArtifacts.add(buf.toString());
                }
            }
        }
    }
}
