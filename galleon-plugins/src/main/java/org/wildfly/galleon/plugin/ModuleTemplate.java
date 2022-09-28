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

import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.jboss.galleon.ProvisioningDescriptionException;
import org.jboss.galleon.runtime.PackageRuntime;
import org.w3c.dom.Comment;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.w3c.dom.ProcessingInstruction;
import org.xml.sax.SAXException;

/**
 * A module template, built from a module.xml template file.
 *
 * @author jdenise
 */
class ModuleTemplate {

    private final Element rootElement;
    private final Document document;
    private final Path targetPath;

    ModuleTemplate(PackageRuntime pkg, Path moduleTemplate, Path targetPath) throws IOException, ProvisioningDescriptionException {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        try (InputStream reader = Files.newInputStream(moduleTemplate)) {
            DocumentBuilder builder = factory.newDocumentBuilder();
            document = builder.parse(reader);
        } catch (ParserConfigurationException | SAXException e) {
            throw new IOException("Failed to parse document", e);
        }
        rootElement = document.getDocumentElement();
        this.targetPath = targetPath;
    }

    Element getRootElement() {
        return rootElement;
    }

    List<Element> getArtifacts() {
        NodeList artifacts = null;
        List<Element> lst = new ArrayList<>();
        final NodeList resourcesElement = rootElement.getElementsByTagNameNS(rootElement.getNamespaceURI(), "resources");
        if (resourcesElement != null && resourcesElement.getLength() > 0) {
            Element elem = (Element) resourcesElement.item(0);
            artifacts = elem.getElementsByTagNameNS(rootElement.getNamespaceURI(), "artifact");
            final int artifactCount = artifacts.getLength();
            System.out.println("1 LENGTH " + artifacts.getLength());
            for (int i = 0; i < artifactCount; i++) {
                Element e = (Element) artifacts.item(i);
                System.out.println("Artifact element : " + e.getLocalName());
                lst.add(e);
            }
        }
        return lst;
    }

    boolean isModule() {
        if ( (rootElement == null ) || (rootElement.getLocalName() == null) ) {
            System.out.println("ROOT ELement " + rootElement + " IS NULL");
        }
        return rootElement.getLocalName().equals("module")
                || rootElement.getLocalName().equals("module-alias");
    }

    void store() throws IOException {
        // now serialize the result
        NodeList nl = document.getChildNodes();
        List<Comment> comments = new ArrayList<>();
        for(int i = 0; i < nl.getLength(); i++) {
            Node child = nl.item(i);
            if (child.getNodeType() == Node.COMMENT_NODE) {
                Comment comment = (Comment) child;
                //System.out.println("COMMENTS : " + comment.getData());
                comments.add(comment);
            }
        }
        FileWriter writer = new FileWriter(targetPath.toFile());
        StreamResult result = new StreamResult(writer);
        try {
            TransformerFactory transformerFactory = TransformerFactory.newInstance();
            Transformer transformer = transformerFactory.newTransformer();
            transformer.setOutputProperty(OutputKeys.INDENT, "no");
            transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");
            transformer.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2");
            ProcessingInstruction xmlpi = document.createProcessingInstruction("xml", "version=\"1.0\" encoding=\"UTF-8\"");
            transformer.transform(new DOMSource(xmlpi), result);
            for (Comment comment : comments) {
                document.removeChild(comment);
                //transformer.transform(new DOMSource(comment), result);
            }
            DOMSource source = new DOMSource(document);
            transformer.transform(source, result);
        } catch (TransformerException ex) {
            throw new IOException(ex);
        }
    }
}
