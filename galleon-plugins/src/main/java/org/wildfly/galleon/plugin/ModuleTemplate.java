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

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import org.jboss.galleon.ProvisioningException;
import org.jboss.galleon.runtime.PackageRuntime;

/**
 * A module template, built from a module.xml template file.
 *
 * @author jdenise
 */
class ModuleTemplate {

    private final XMLElement rootElement;
    private final XMLDocument document;
    private final XMLProvider provider;
    private final Path targetPath;

    ModuleTemplate(WfInstallPlugin plugin, PackageRuntime pkg, Path moduleTemplate, Path targetPath) throws IOException, ProvisioningException {
        provider = XMLProvider.buildXMLProvider(plugin, moduleTemplate);
        document = provider.getDocument();
        rootElement = document.getRootElement();
        this.targetPath = targetPath;
    }

    XMLElement getRootElement() {
        return rootElement;
    }

    List<XMLElement> getArtifacts() {
        List<XMLElement> artifacts = null;
        final XMLElement resourcesElement = rootElement.getFirstChildElement("resources", rootElement.getNamespaceURI());
        if (resourcesElement != null) {
            artifacts = resourcesElement.getChildElements("artifact", rootElement.getNamespaceURI());
        }
        return artifacts;
    }

    boolean isModule() {
        return rootElement.getLocalName().equals("module")
                || rootElement.getLocalName().equals("module-alias");
    }

    void store() throws IOException, ProvisioningException {
        provider.store(targetPath);
    }
}
