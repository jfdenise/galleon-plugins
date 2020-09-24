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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import javax.xml.stream.XMLStreamException;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.repository.RemoteRepository;
import org.jboss.galleon.ProvisioningException;
import org.jboss.galleon.config.ConfigId;
import org.jboss.galleon.config.ProvisioningConfig;
import org.jboss.galleon.layout.FeaturePackLayout;
import org.jboss.galleon.layout.ProvisioningLayout;
import org.jboss.galleon.layout.ProvisioningLayoutFactory;
import org.jboss.galleon.maven.plugin.util.MavenArtifactRepositoryManager;
import org.jboss.galleon.repo.RepositoryArtifactResolver;
import org.jboss.galleon.spec.ConfigLayerDependency;
import org.jboss.galleon.spec.ConfigLayerSpec;
import org.jboss.galleon.universe.FeaturePackLocation;
import org.jboss.galleon.universe.UniverseFactoryLoader;
import org.jboss.galleon.universe.UniverseResolver;

/**
 * This maven plugin generates the layers.xml XML file containing Galleon layers
 * and dependencies for a given feature-pack.
 *
 */
@Mojo(name = "dump-layers", requiresDependencyResolution = ResolutionScope.COMPILE_PLUS_RUNTIME, defaultPhase = LifecyclePhase.PROCESS_TEST_RESOURCES)
public class DumpLayersInfoMojo extends AbstractMojo {

    // These WildFly specific props should be cleaned up
    private static final String MAVEN_REPO_LOCAL = "maven.repo.local";

    @Component
    protected RepositorySystem repoSystem;

    @Parameter(defaultValue = "${repositorySystemSession}", readonly = true)
    protected RepositorySystemSession repoSession;

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    protected MavenProject project;

    @Parameter(defaultValue = "${session}", readonly = true, required = true)
    protected MavenSession session;

    @Parameter(defaultValue = "${project.remoteProjectRepositories}", readonly = true, required = true)
    private List<RemoteRepository> repositories;

    /**
     * Whether to use offline mode when the plugin resolves an artifact. In
     * offline mode the plugin will only use the local Maven repository for an
     * artifact resolution.
     */
    @Parameter(alias = "offline", defaultValue = "false")
    private boolean offline;

    /**
     * The feature-pack to document.
     */
    @Parameter(alias = "feature-pack-location", required = true)
    private String featurePackLocation;

    @Parameter(alias = "output-directory", required = true, defaultValue = "${project.build.directory}/galleon-layers")
    private String directory;

    @Parameter(alias = "enriched-layers-file", required = false)
    private String enrichedLayersFile;

    private final Map<String, LayerInfo> enrichedLayers = new HashMap<>();

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {

        final String originalMavenRepoLocal = System.getProperty(MAVEN_REPO_LOCAL);
        System.setProperty(MAVEN_REPO_LOCAL, session.getSettings().getLocalRepository());
        try {
            enrichLayers();
            doGenerate();
        } catch (XMLStreamException | IOException | ProvisioningException e) {
            throw new MojoExecutionException("Provisioning failed", e);
        } finally {
            if (originalMavenRepoLocal == null) {
                System.clearProperty(MAVEN_REPO_LOCAL);
            } else {
                System.setProperty(MAVEN_REPO_LOCAL, originalMavenRepoLocal);
            }
        }
    }

    private void doGenerate() throws MojoExecutionException, ProvisioningException, IOException, XMLStreamException {
        final FeaturePackLocation fpl = FeaturePackLocation.fromString(featurePackLocation);
        final RepositoryArtifactResolver artifactResolver = offline ? new MavenArtifactRepositoryManager(repoSystem, repoSession)
                : new MavenArtifactRepositoryManager(repoSystem, repoSession, repositories);

        UniverseFactoryLoader ufl = UniverseFactoryLoader.getInstance();
        UniverseResolver ur = UniverseResolver.builder(ufl).build();
        ufl.addArtifactResolver(artifactResolver);
        ProvisioningLayoutFactory factory = ProvisioningLayoutFactory.getInstance(ur);
        ProvisioningConfig config = ProvisioningConfig.builder().addFeaturePackDep(fpl).build();
        try (ProvisioningLayout<FeaturePackLayout> layout = factory.newConfigLayout(config)) {
            Set<LayerInfo> layers = getAllLayers(layout);
            if (!layers.containsAll(enrichedLayers.values())) {
                Set<LayerInfo> unknown = new HashSet<>();
                for (LayerInfo li : enrichedLayers.values()) {
                    if (!layers.contains(li)) {
                        unknown.add(li);
                    }
                }
                throw new MojoExecutionException("Some enriched layers are not present in the actual layers. Unknown layers: " + unknown);
            }
            LayersInfo info = new LayersInfo(fpl, layers);
            LayersInfoXmlWriter writer = new LayersInfoXmlWriter();
            Path path = Paths.get(directory);
            if (!path.isAbsolute()) {
                path = project.getBasedir().toPath().resolve(path);
            }
            Files.createDirectories(path);
            path = path.resolve("layers.xml");
            writer.write(info, path);
        }

    }

    private Set<LayerInfo> getAllLayers(ProvisioningLayout<FeaturePackLayout> pLayout)
            throws ProvisioningException, IOException {
        Map<String, Map<String, LayerInfo>> layersMap = new HashMap<>();
        Set<LayerInfo> layersSet = new TreeSet<>();
        for (FeaturePackLayout fp : pLayout.getOrderedFeaturePacks()) {
            for (ConfigId layer : fp.loadLayers()) {
                String model = layer.getModel();
                if (!"standalone".equals(model)) {
                    continue;
                }
                Map<String, LayerInfo> layers = layersMap.get(model);
                if (layers == null) {
                    layers = new TreeMap<>();
                    layersMap.put(model, layers);
                }
                LayerInfo layerInfo = layers.get(layer.getName());
                if (layerInfo == null) {
                    LayerInfo enrichedLayer = enrichedLayers.get(layer.getName());
                    if (enrichedLayer == null) {
                        layerInfo = new LayerInfo(layer.getName());
                    } else {
                        layerInfo = enrichedLayer;
                    }
                    layers.put(layer.getName(), layerInfo);
                    layersSet.add(layerInfo);
                }

                ConfigLayerSpec spec = fp.loadConfigLayerSpec(model, layer.getName());
                for (ConfigLayerDependency dep : spec.getLayerDeps()) {
                    layerInfo.addDependency(new LayerDependencyInfo(dep.getName(), dep.isOptional()));
                }
            }
        }
        return layersSet;
    }

    private void enrichLayers() throws MojoExecutionException, ProvisioningException {
        if (enrichedLayersFile == null) {
            return;
        }
        Path path = Paths.get(enrichedLayersFile);
        if (!path.isAbsolute()) {
            path = project.getBasedir().toPath().resolve(path);
        }
        if (Files.notExists(path)) {
            throw new MojoExecutionException("File " + path + " doesn't exist");
        }
        LayersInfo info = LayersInfoXmlParser.parse(path);
        for (LayerInfo li : info.getLayers()) {
            enrichedLayers.put(li.getName(), li);
        }
    }

}
