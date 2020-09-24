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

import javax.xml.stream.XMLStreamException;
import org.jboss.galleon.xml.BaseXmlWriter;
import org.jboss.galleon.xml.util.CDataNode;
import org.jboss.galleon.xml.util.ElementNode;
import org.wildfly.galleon.maven.LayersInfoXmlParser.Attribute;
import org.wildfly.galleon.maven.LayersInfoXmlParser.Element;

/**
 * Write LayersInfo to XML file.
 *
 * @author jdenise
 */
final class LayersInfoXmlWriter extends BaseXmlWriter<LayersInfo> {

    @Override
    protected ElementNode toElement(LayersInfo layersInfo) throws XMLStreamException {
        ElementNode infoElement = addElement(null, Element.LAYERSINFO);
        addAttribute(infoElement, Attribute.FPL, layersInfo.getFPL());
        for (LayerInfo info : layersInfo.getLayers()) {
            ElementNode layerElement = addElement(infoElement, Element.LAYER);
            addAttribute(layerElement, Attribute.NAME, info.getName());
            if (info.getVisibility() != null) {
                addAttribute(layerElement, Attribute.VISIBILITY, info.getVisibility());
            }
            addAttribute(layerElement, Attribute.DEPRECATED, info.isDeprecated() ? "true" : "false");
            if (info.getDescription() != null) {
                addElement(layerElement, Element.DESCRIPTION.getLocalName(), Element.DESCRIPTION.getNamespace()).addChild(new CDataNode(info.getDescription()));
            }
            if (!info.getDependencies().isEmpty()) {
                ElementNode dependenciesElement = addElement(layerElement, Element.DEPENDENCIES);
                for (LayerDependencyInfo depInfo : info.getDependencies()) {
                    ElementNode depElement = addElement(dependenciesElement, Element.DEPENDENCY);
                    addAttribute(depElement, Attribute.NAME, depInfo.getName());
                    addAttribute(depElement, Attribute.OPTIONAL, depInfo.isOptional() ? "true" : "false");
                }
            }
        }
        return infoElement;
    }
}
