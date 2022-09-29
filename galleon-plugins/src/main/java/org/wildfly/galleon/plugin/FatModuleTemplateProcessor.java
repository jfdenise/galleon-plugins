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

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.jboss.galleon.Errors;
import org.jboss.galleon.MessageWriter;
import org.jboss.galleon.ProvisioningException;
import org.jboss.galleon.universe.maven.MavenArtifact;
import org.jboss.galleon.universe.maven.MavenUniverseException;
import static org.wildfly.galleon.plugin.WfInstallPlugin.CONFIG_GEN_PATH;

/**
 * A Template processor that process templates when provisioning fat server. In
 * this case artifacts are copied to the modules directory by the associated installer.
 * This processor updates the module.xml with reference to the file copied locally.
 * reference local artifact.
 *
 * @author jdenise
 */
class FatModuleTemplateProcessor extends AbstractModuleTemplateProcessor {

    public FatModuleTemplateProcessor(WfInstallPlugin plugin, AbstractArtifactInstaller installer,
            Path targetPath, ModuleTemplate template,
            Map<String, String> versionProps, boolean channelArtifactResolution) {
        super(plugin, installer, targetPath, template, versionProps, channelArtifactResolution);
    }

    @Override
    protected void processArtifact(ModuleArtifact artifact) throws IOException, MavenUniverseException, ProvisioningException {
        final Path artifactPath = artifact.getMavenArtifact().getPath();
        final String artifactFileName = artifactPath.getFileName().toString();
        String finalFileName;

        if (artifact.isJandex()) {
            final int lastDot = artifactFileName.lastIndexOf(".");
            final File target = new File(getTargetDir().toFile(),
                    new StringBuilder().append(artifactFileName.substring(0, lastDot)).append("-jandex")
                            .append(artifactFileName.substring(lastDot)).toString());
            index(artifactPath.toFile(), new FileOutputStream(target), getLog());
            finalFileName = target.getName();
        } else {
            finalFileName = getInstaller().installArtifactFat(artifact.getMavenArtifact(), getTargetDir());
        }
        artifact.updateFatArtifact(finalFileName);
    }

    void index(File jarFile, OutputStream target, MessageWriter log) throws ProvisioningException {
        final URL[] cp = new URL[2];
        try {
            final Path configGenJar = getPlugin().getRuntime().getResource(CONFIG_GEN_PATH);
            if (!Files.exists(configGenJar)) {
                throw new ProvisioningException(Errors.pathDoesNotExist(configGenJar));
            }
            MavenArtifact artifact;
            try {
                artifact = getPlugin().retrieveMavenArtifact("io.smallrye:jandex");
            } catch(ProvisioningException ex) {
                //Fallback on older dependency
                artifact = getPlugin().retrieveMavenArtifact("org.jboss:jandex");
            }
            getPlugin().resolveMaven(artifact);
            cp[0] = configGenJar.toUri().toURL();
            cp[1] = artifact.getPath().toUri().toURL();
        } catch (IOException e) {
            throw new ProvisioningException("Failed to init jandex classpath ", e);
        }
        final ClassLoader originalCl = Thread.currentThread().getContextClassLoader();
        final URLClassLoader jandexCl = new URLClassLoader(cp, originalCl);
        Thread.currentThread().setContextClassLoader(jandexCl);
        try {
            final Class<?> jandexIndexerCls = jandexCl.loadClass("org.wildfly.galleon.plugin.config.generator.Indexer");
            final Method m = jandexIndexerCls.getMethod("createIndex", File.class, OutputStream.class, MessageWriter.class);
            m.invoke(null, jarFile, target, log);
        } catch (Throwable e) {
            throw new ProvisioningException("Failed to initialize jandex ", e);
        } finally {
            Thread.currentThread().setContextClassLoader(originalCl);
        }
    }
}
