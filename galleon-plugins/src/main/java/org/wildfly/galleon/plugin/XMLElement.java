/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package org.wildfly.galleon.plugin;

import java.util.List;

/**
 *
 * @author jdenise
 */
public interface XMLElement {

    public Object getLocalName();

    public XMLAttribute getAttribute(String name);

    public void setLocalName(String resourceroot);

    public String getNamespaceURI();

    public List<XMLElement> getChildElements(String artifact, String namespaceURI);

    public XMLElement getFirstChildElement(String resources, String namespaceURI);

}
