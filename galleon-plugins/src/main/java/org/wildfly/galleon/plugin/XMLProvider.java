/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package org.wildfly.galleon.plugin;

import java.io.IOException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import org.jboss.galleon.Errors;
import org.jboss.galleon.ProvisioningException;
import org.jboss.galleon.universe.maven.MavenArtifact;
import static org.wildfly.galleon.plugin.WfInstallPlugin.CONFIG_GEN_PATH;

/**
 *
 * @author jdenise
 */
public class XMLProvider {

    private final XMLDocument document;
    private final Method storeMethod;

    XMLProvider(XMLDocument document, Method storeMethod) {
        this.document = document;
        this.storeMethod = storeMethod;
    }

    public XMLDocument getDocument() {
        return document;
    }

    public void store(Path target) throws ProvisioningException {
        try {
            storeMethod.invoke(null, document, target);
        } catch (Throwable ex) {
            throw new ProvisioningException(ex);
        }
    }

    public static XMLProvider buildXMLProvider(WfInstallPlugin plugin, Path moduleTemplate) throws ProvisioningException {
        final URL[] cp = new URL[2];
        try {
            final Path configGenJar = plugin.getRuntime().getResource(CONFIG_GEN_PATH);
            if (!Files.exists(configGenJar)) {
                throw new ProvisioningException(Errors.pathDoesNotExist(configGenJar));
            }
            MavenArtifact artifact;
            artifact = plugin.retrieveMavenArtifact("xom:xom");
            plugin.resolveMaven(artifact);
            cp[0] = configGenJar.toUri().toURL();
            cp[1] = artifact.getPath().toUri().toURL();
        } catch (IOException e) {
            throw new ProvisioningException("Failed to init xom classpath ", e);
        }
        final ClassLoader originalCl = Thread.currentThread().getContextClassLoader();
        final URLClassLoader xomCl = new URLClassLoader(cp, originalCl);
        Thread.currentThread().setContextClassLoader(xomCl);
        try {
            final Class<?> xmlCls = xomCl.loadClass("org.wildfly.galleon.plugin.config.generator.XmlHandler");
            final Method m = xmlCls.getMethod("buildDocument", Path.class);
            XMLDocument doc = (XMLDocument) m.invoke(null, moduleTemplate);
            return new XMLProvider(doc, xmlCls.getMethod("store", XMLDocument.class, Path.class));
        } catch (Throwable e) {
            throw new ProvisioningException("Failed to initialize jandex ", e);
        } finally {
            Thread.currentThread().setContextClassLoader(originalCl);
        }

    }
}
