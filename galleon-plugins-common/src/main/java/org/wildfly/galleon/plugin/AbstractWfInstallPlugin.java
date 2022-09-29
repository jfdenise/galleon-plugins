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
package org.wildfly.galleon.plugin;

import java.io.IOException;
import java.nio.file.Path;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.Transformer;
import org.jboss.galleon.MessageWriter;
import org.jboss.galleon.ProvisioningException;
import org.jboss.galleon.runtime.FeaturePackRuntime;
import org.jboss.galleon.runtime.PackageRuntime;
import org.jboss.galleon.runtime.ProvisioningRuntime;
import org.jboss.galleon.universe.maven.MavenArtifact;
import org.wildfly.galleon.plugin.config.CopyArtifact;
import org.wildfly.galleon.plugin.config.CopyPath;
import org.wildfly.galleon.plugin.config.DeletePath;
import org.wildfly.galleon.plugin.config.ExampleFpConfigs;
import org.wildfly.galleon.plugin.config.XslTransform;

/**
 * WildFly install plugin. Handles all WildFly specifics that occur during
 * provisioning. The combinations of supported options for jakarta
 * transformation for transformable feature-pack is:
 * <ul>
 * <li> No option set: Transformation for fat server will occur.</li>
 * <li> jboss-maven-dist and jboss-maven-repo. Thin server, artifacts
 * transformed and copied to the generated repo.</li>
 * <li> jboss-jakarta-transform-artifacts=false and
 * jboss-maven-provisioning-repo, optionally jboss-maven-dist. Fat or thin
 * server. No artifacts transformed (except for overridden artifact not present
 * in provisioning repository).</li>
 * </ul>
 * When jakarta transformation occurs and overridden artifacts have been
 * provided the following logic applies:
 * <ul>
 * <li>If the overridden artifact is already present in the
 * jboss-maven-provisioning-repo, no transformation occurs.</li>
 * <li>Otherwise an attempt to transform the artifact is operated. If not
 * transformed, the original arifact is used.</li>
 * </ul>
 *
 * @author Alexey Loubyansky
 */
public interface AbstractWfInstallPlugin {

    // Maven artifact resolution depends on the jakarta transformation contexts.
    interface ArtifactResolver {

        void resolve(MavenArtifact artifact) throws ProvisioningException;
    }

    void processSchemas(String groupId, Path artifactPath) throws IOException;

    ProvisioningRuntime getRuntime();

    MessageWriter getLog();

    void copyArtifact(CopyArtifact copyArtifact, PackageRuntime pkg) throws ProvisioningException;

    void copyPath(final Path relativeTo, CopyPath copyPath) throws ProvisioningException;

    void deletePath(DeletePath deletePath) throws ProvisioningException;

    void addExampleConfigs(FeaturePackRuntime fp, ExampleFpConfigs exampleConfigs) throws ProvisioningException;

    Transformer getXslTransformer(Path p) throws ProvisioningException;

    DocumentBuilderFactory getXmlDocumentBuilderFactory();

    void xslTransform(PackageRuntime pkg, XslTransform xslt) throws ProvisioningException;

    boolean isOverriddenArtifact(MavenArtifact artifact) throws ProvisioningException;
}
