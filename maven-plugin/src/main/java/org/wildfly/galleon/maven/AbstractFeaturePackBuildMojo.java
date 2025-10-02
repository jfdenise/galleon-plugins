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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import static java.lang.String.format;
import static org.wildfly.galleon.maven.FeatureSpecGeneratorInvoker.MODULE_PATH_SEGMENT;
import static org.wildfly.galleon.maven.Util.mkdirs;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.io.Reader;
import java.nio.file.DirectoryStream;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.TreeSet;

import javax.xml.stream.XMLStreamException;

import nu.xom.ParsingException;
import org.apache.maven.artifact.DefaultArtifact;
import org.apache.maven.artifact.handler.DefaultArtifactHandler;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.DefaultProjectBuildingRequest;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.MavenProjectHelper;
import org.apache.maven.project.ProjectBuildingRequest;
import org.apache.maven.shared.artifact.resolve.ArtifactResolver;
import org.apache.maven.shared.artifact.resolve.ArtifactResolverException;
import org.apache.maven.shared.artifact.resolve.ArtifactResult;
import org.eclipse.aether.DefaultRepositorySystemSession;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.resolution.ArtifactDescriptorException;
import org.jboss.galleon.Constants;
import org.jboss.galleon.Errors;
import org.jboss.galleon.ProvisioningDescriptionException;
import org.jboss.galleon.ProvisioningException;
import org.jboss.galleon.Stability;
import org.jboss.galleon.api.GalleonLayerDependency;
import org.jboss.galleon.config.ConfigItem;
import org.jboss.galleon.config.ConfigModel;
import org.jboss.galleon.config.FeatureConfig;
import org.jboss.galleon.config.FeatureGroup;
import org.jboss.galleon.config.FeaturePackConfig;
import org.jboss.galleon.layout.FeaturePackDescriber;
import org.jboss.galleon.layout.FeaturePackDescription;
import org.jboss.galleon.layout.FeaturePackLayout;
import org.jboss.galleon.layout.ProvisioningLayout;
import org.jboss.galleon.layout.ProvisioningLayoutFactory;
import org.jboss.galleon.maven.plugin.FpMavenErrors;
import org.jboss.galleon.maven.plugin.util.MavenArtifactRepositoryManager;
import org.jboss.galleon.spec.ConfigLayerSpec;
import org.jboss.galleon.spec.FeatureAnnotation;
import org.jboss.galleon.spec.FeaturePackPlugin;
import org.jboss.galleon.spec.FeaturePackSpec;
import org.jboss.galleon.spec.FeatureParameterSpec;
import org.jboss.galleon.spec.FeatureSpec;
import org.jboss.galleon.spec.PackageDependencySpec;
import org.jboss.galleon.spec.PackageSpec;
import org.jboss.galleon.universe.FeaturePackLocation;
import org.jboss.galleon.universe.UniverseResolver;
import org.jboss.galleon.util.CollectionUtils;
import org.jboss.galleon.util.IoUtils;
import org.jboss.galleon.util.StringUtils;
import org.jboss.galleon.util.ZipUtils;
import org.jboss.galleon.xml.FeaturePackXmlWriter;
import org.jboss.galleon.xml.FeatureSpecXmlParser;
import org.jboss.galleon.xml.PackageXmlParser;
import org.jboss.galleon.xml.PackageXmlWriter;
import org.wildfly.channel.ChannelManifest;
import org.wildfly.channel.ChannelManifestMapper;
import org.wildfly.channel.ManifestRequirement;
import org.wildfly.channel.MavenCoordinate;
import org.wildfly.galleon.maven.build.tasks.ResourcesTask;
import org.wildfly.galleon.plugin.ArtifactCoords;
import org.wildfly.galleon.plugin.WfConstants;
import org.wildfly.galleon.plugin.WildFlyChannelResolutionMode;

/**
 * This Maven mojo creates a WildFly style feature-pack archive from the
 * provided resources according to the feature-pack build configuration file and
 * attaches it to the current Maven project as an artifact.
 *
 * The content of the future feature-pack archive is first created in the
 * directory called `layout` under the module's build directory which is then
 * ZIPped to create the feature-pack artifact.
 *
 * @author Alexey Loubyansky
 */
public abstract class AbstractFeaturePackBuildMojo extends AbstractMojo {

    private static final String MSG_PACKAGE_NOT_INCLUDED = " This package has not been included in the feature-pack due to its stability level being lower than the feature-pack minimum stability level.";
    private static final String MSG_PACKAGE_IGNORED = " This package dependency will be ignored at provisioning time.";
    private static final String MSG_PACKAGE_ERROR = " This package dependency will fail at provisioning time. You should remove this package dependency or add the 'valid-for-stability' attribute to this package dependency.";
    private static final String PACKAGE = "package";
    private static final String FEATURE = "feature";
    private static final String LAYER = "layer";
    private static final String CONFIG = "config";

    static final String ARTIFACT_LIST_CLASSIFIER = "artifact-list";
    static final String ARTIFACT_LIST_EXTENSION = "txt";

    static final String METADATA_CLASSIFIER = "metadata";
    static final String METADATA_EXTENSION = "json";
    static final String MODEL_CLASSIFIER = "model";
    static final String MODEL_EXTENSION = "json";

    static boolean isProvided(String module) {
        return module.startsWith("java.")
                || module.startsWith("jdk.")
                || module.equals("org.jboss.modules");
    }

    @Parameter(defaultValue = "${repositorySystemSession}", readonly = true)
    protected RepositorySystemSession repoSession;

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    protected MavenProject project;

    @Parameter(defaultValue = "${session}", readonly = true, required = true)
    protected MavenSession session;

    @Parameter(defaultValue = "${project.remoteProjectRepositories}", readonly = true, required = true)
    protected List<RemoteRepository> repositories;

    /**
     * The name of the release the feature-pack represents which will be stored
     * in the feature-pack's `resources/wildfly/wildfly-tasks.properties` as
     * `product.release.name` property.
     */
    @Parameter(alias = "release-name", defaultValue = "${product.release.name}")
    private String releaseName;

    /**
     * Path to a properties file content of which will be added to
     * feature-pack's `resources/wildfly/wildfly-tasks.properties` file that is
     * used as the source of properties during file copying tasks with property
     * replacement.
     */
    @Parameter(alias = "task-properties-file", required = false)
    private File taskPropsFile;

    /**
     * Various properties that will be added to feature-pack's
     * `resources/wildfly/wildfly-tasks.properties`.<br/>
     * NOTE: values of this parameter will overwrite the corresponding values
     * from task-properties-file parameter, in case it's also set.<br/>
     */
    @Parameter(alias = "task-properties", required = false)
    protected Map<String, String> taskProps = Collections.emptyMap();

    /**
     * Generates a channel manifest YAML definition when the feature-pack is produced.
     * Any dependency from the feature pack is declared as a stream in the channel manifest.
     */
    @Parameter(alias = "generate-channel-manifest", required = false, defaultValue = "false",
            property = "wildfly.feature.pack.generate-channel-manifest")
    protected boolean generateChannelManifest;

    /**
     * Add any feature-pack dependency as a required manifest in the manifest YAML definition.
     * This parameter has no effect if "generate-channel-manifest" is false.
     */
    @Parameter(alias = "add-feature-packs-as-required-manifests", required = false, defaultValue = "true")
    protected boolean addFeaturePacksAsRequiredManifests;

    /**
     * Feature-pack WildFly channel resolution mode when WildFly channels are configured in the provisioning tooling used to
     * provision this feature-pack.
     * "NOT_REQUIRED" means that the feature-pack and artifacts can be resolved without WildFly channels.
     * "REQUIRED" means that the feature-pack and all its artifacts must be only resolved from WildFly channels.
     * "REQUIRED_FP_ONLY" means that only the feature-pack must be only resolved from WildFly channels.
     * Referenced artifacts can be resolved outside of configured WildFly channels.
     */
    @Parameter(alias = "wildfly-channel-resolution-mode", required = false, defaultValue = "NOT_REQUIRED",
            property = "wildfly.feature.pack.require.channel.resolution")
    protected WildFlyChannelResolutionMode wildflyChannelResolutionMode;

    @Parameter(alias = "deploy-channel-manifest", required = false, defaultValue = "true",
            property = "wildfly.feature.pack.deploy-channel-manifest")
    protected boolean deployChannelManifest;

    @Component
    protected RepositorySystem repoSystem;

    @Component
    protected ArtifactResolver artifactResolver;

    @Component
    private MavenProjectHelper projectHelper;

    /**
     * The minimum stability level of the WildFly processes used to generate feature specs and include packages.
     * Set this if you need to generate feature specs for features with a lower stability level
     * than the default level of the WildFly process being used for feature-spec generation.
     * It overrides the value set in the wildfly-feature-pack-build.xml file.
     */
    @Parameter(alias = "minimum-stability-level", required = false)
    protected String minimumStabilityLevel;

    /**
     * The default stability level used at provisioning time to generate
     * configuration and provision packages. Can't be used when {@code config-stability-level} or {@code package-stability-level} is set.
     * It overrides the value set in the wildfly-feature-pack-build.xml file.
     */
    @Parameter(alias = "stability-level", required = false)
    protected String stabilityLevel;

    /**
     * The default stability level used at provisioning time to generate
     * configuration. Can't be used when {@code stability-level} is set.
     * It overrides the value set in the wildfly-feature-pack-build.xml file.
     */
    @Parameter(alias = "config-stability-level", required = false)
    protected String configStabilityLevel;

    /**
     * Enforce that no package at a lower stability level than the minimum-stability-level is referenced from Galleon constructs.
     */
    @Parameter(alias = "forbid-lower-stability-level-package-reference", required = false, defaultValue = "false")
    protected boolean forbidLowerStatibilityLevelPackageReference;

    /**
     * The default stability level used at provisioning time when installing packages/JBoss Modules modules.
     * Can't be used when {@code stability-level} is set.
     * If both the {@code config-stability-level} and the {@code package-stability-level} options are set,
     * the level of the {@code package-stability-level} option must imply the level of the {@code config-stability-level} option.
     * It overrides the value set in the wildfly-feature-pack-build.xml file.
     */
    @Parameter(alias = "package-stability-level", required = false)
    protected String packageStabilityLevel;

    /**
     * Add any feature-pack dependency in the generated metadata.
     */
    @Parameter(alias = "add-feature-packs-dependencies-in-metadata", required = false, defaultValue = "false")
    protected boolean addFeaturePacksDependenciesInMetadata;

    private MavenProjectArtifactVersions artifactVersions;

    private Map<String, FeaturePackDescription> fpDependencies = Collections.emptyMap();

    private Path workDir;
    private Path fpDir;
    private Path fpPackagesDir;
    private Path resourcesWildFly;
    private Path fpResourcesDir;
    private Path resourcesDir;
    private Stability buildTimestabilityLevel;
    private Stability defaultConfigStabilityLevel;
    private Stability defaultPackageStabilityLevel;

    private final Set<String> lowerStabilityPackages = new HashSet<>();

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        try {
            artifactVersions = MavenProjectArtifactVersions.getInstance(project);
            doExecute();
        } catch (RuntimeException | Error | MojoExecutionException | MojoFailureException e) {
            throw e;
        }
    }

    // Stability options override the values in the buildConfig
    protected void setStability(WildFlyFeaturePackBuild buildConfig) throws MojoExecutionException {
        if (minimumStabilityLevel == null) {
            minimumStabilityLevel = buildConfig.getMinimumStabilityLevel();
        }
        buildTimestabilityLevel = minimumStabilityLevel == null ? null : Stability.fromString(minimumStabilityLevel);
        if (stabilityLevel == null) {
            stabilityLevel = buildConfig.getStabilityLevel();
        }
        if (configStabilityLevel == null) {
            configStabilityLevel = buildConfig.getConfigStabilityLevel();
        }
        if (packageStabilityLevel == null) {
            packageStabilityLevel = buildConfig.getPackageStabilityLevel();
        }
        if (stabilityLevel == null) {
            defaultConfigStabilityLevel = configStabilityLevel == null ? null : Stability.fromString(configStabilityLevel);
            defaultPackageStabilityLevel = packageStabilityLevel == null ? null : Stability.fromString(packageStabilityLevel);
        } else {
            if (configStabilityLevel != null) {
                throw new MojoExecutionException("stability option can't be set when config-stability-level option is set");
            }
            if (packageStabilityLevel != null) {
                throw new MojoExecutionException("stability option can't be set when package-stability-level option is set");
            }
            defaultConfigStabilityLevel = Stability.fromString(stabilityLevel);
            defaultPackageStabilityLevel = Stability.fromString(stabilityLevel);
        }
        // Check that the minimum Stability level enables the stability level
        checkStabilityLevels(buildTimestabilityLevel, defaultConfigStabilityLevel, defaultPackageStabilityLevel);
    }

    protected Stability getMinimumStabilityLevel() {
        return buildTimestabilityLevel;
    }

    protected Stability getPackageStabilityLevel() {
        return defaultPackageStabilityLevel;
    }

    protected Stability getConfigStabilityLevel() {
        return defaultConfigStabilityLevel;
    }

    /**
     * Gets the maven packaging type supported by this mojo if this
     * plugin is configured to run as a maven extension.
     *
     * @return the packaging type. By default returns {@code null}
     */
    protected String getPackaging() {
        return null;
    }

    private static void checkStabilityLevels(Stability min, Stability config, Stability pkg) throws MojoExecutionException {
        min = min == null ? Stability.DEFAULT : min;
        config = config == null ? Stability.DEFAULT : config;
        pkg = pkg == null ? Stability.DEFAULT : pkg;
        if (!min.enables(config)) {
            throw new MojoExecutionException("The minimum stability " + min + " doesn't enable the config stability " + config);
        }
        if (!min.enables(pkg)) {
            throw new MojoExecutionException("The minimum stability " + min + " doesn't enable the package stability " + pkg);
        }
        if (!pkg.enables(config)) {
            throw new MojoExecutionException("The package stability " + pkg + " doesn't enable the config stability " + config);
        }
    }

    protected Map<String, FeaturePackDescription> getFpDependencies() {
        return fpDependencies;
    }

    protected MavenProjectArtifactVersions getArtifactVersions() {
        return artifactVersions;
    }

    protected abstract void doExecute() throws MojoExecutionException, MojoFailureException;

    protected void setupDirs(String buildName, String fpArtifactId, String layoutDir, Path resourcesDir) {
        if (workDir == null) {
            workDir = Paths.get(buildName, layoutDir);
            IoUtils.recursiveDelete(workDir);
            fpDir = workDir.resolve(project.getGroupId()).resolve(fpArtifactId).resolve(project.getVersion());
            fpPackagesDir = fpDir.resolve(Constants.PACKAGES);
            fpResourcesDir = fpDir.resolve(Constants.RESOURCES);
            resourcesWildFly = fpResourcesDir.resolve(WfConstants.WILDFLY);
            this.resourcesDir = resourcesDir;
        }
    }

    protected Path getWorkDir() {
        Objects.requireNonNull(workDir);
        return workDir;
    }

    protected Path getWildFlyResourcesDir() {
        return resourcesWildFly;
    }

    protected Path getFpDir() {
        Objects.requireNonNull(fpDir);
        return fpDir;
    }

    protected Path getPackagesDir() {
        Objects.requireNonNull(fpPackagesDir);
        return fpPackagesDir;
    }

    public String resolveVersion(final String coordsWoVersion) throws MojoExecutionException {
        final String resolved = artifactVersions.getVersion(coordsWoVersion);
        if (resolved == null) {
            throw new MojoExecutionException("The project is missing dependency on " + coordsWoVersion);
        }
        return resolved;
    }

    protected void buildFeaturePack(FeaturePackDescription.Builder fpBuilder, WildFlyFeaturePackBuild buildConfig) throws MojoExecutionException {
        if (buildConfig.hasConfigs()) {
            for (ConfigModel config : buildConfig.getConfigs()) {
                try {
                    fpBuilder.getSpecBuilder().addConfig(config);
                } catch (ProvisioningDescriptionException e) {
                    throw new MojoExecutionException("Failed to add config to the feature-pack", e);
                }
            }
        }

        if (buildConfig.hasPlugins()) {
            addPlugins(fpBuilder.getSpecBuilder(), buildConfig.getPlugins());
        }

        Util.mkdirs(resourcesWildFly);
        if (buildConfig.hasResourcesTasks()) {
            for (ResourcesTask task : buildConfig.getResourcesTasks()) {
                task.execute(this, fpResourcesDir);
            }
        }

        // properties
        try (OutputStream out = Files.newOutputStream(resourcesWildFly.resolve(WfConstants.WILDFLY_TASKS_PROPS))) {
            getFPConfigProperties().store(out, "WildFly feature-pack properties");
        } catch (IOException e) {
            throw new MojoExecutionException("Failed to store feature-pack properties", e);
        }

        // artifact versions
        for (ArtifactCoords.Gav gav : buildConfig.getDependencies().keySet()) {
            getArtifactVersions().remove(gav.getGroupId(), gav.getArtifactId());
        }

        // WildFly channels configuration
        try (OutputStream out = Files.newOutputStream(resourcesWildFly.resolve(WfConstants.WILDFLY_CHANNEL_PROPS))) {
            getWildFlyChannelProperties().store(out, "WildFly channel properties");
        } catch (IOException e) {
            throw new MojoExecutionException("Failed to store WildFly channel properties", e);
        }
        // Copy resources from src.
        try {
            Path srcArtifacts = resourcesDir.resolve(Constants.RESOURCES);
            if (Files.exists(srcArtifacts)) {
               IoUtils.copy(srcArtifacts, fpResourcesDir);
            }
            getArtifactVersions().store(resourcesWildFly.resolve(WfConstants.ARTIFACT_VERSIONS_PROPS));
        } catch (ProvisioningException | IOException e) {
            throw new MojoExecutionException("Failed to store artifact versions", e);
        }
        // Build a maven artifact resolver that looks into the local maven repo, not in the project
        // built artifacts.
        DefaultRepositorySystemSession noWorkspaceSession = new DefaultRepositorySystemSession(repoSession);
        noWorkspaceSession.setWorkspaceReader(null);
        noWorkspaceSession.setOffline(true);
        // Using maven 3.9, having the remote repositories is required for the SNAPSHOT deployed artifacts
        // to have their version properly resolved. We set the session to be offline, so no remote resolution.
        ArtifactListBuilder builder = new ArtifactListBuilder(new MavenArtifactRepositoryManager(repoSystem,
                noWorkspaceSession, repositories), repoSession.getLocalRepository().getBasedir().toPath(), getLog());
        buildArtifactList(builder);
        addConfigPackages(resourcesDir.resolve(Constants.PACKAGES), fpDir.resolve(Constants.PACKAGES), fpBuilder);
        Util.copyIfExists(resourcesDir, fpDir, Constants.LAYERS);
        Util.copyIfExists(resourcesDir, fpDir, Constants.CONFIGS);

        addFeatures(resourcesDir.resolve(Constants.FEATURES), fpDir.resolve(Constants.FEATURES));
        Util.copyDirIfExists(resourcesDir.resolve(Constants.FEATURE_GROUPS), fpDir.resolve(Constants.FEATURE_GROUPS));

        final Path resourcesWildFly = getWildFlyResourcesDir();
        if (buildConfig.hasStandaloneExtensions()) {
            persistExtensions(resourcesWildFly, WfConstants.EXTENSIONS_STANDALONE, buildConfig.getStandaloneExtensions());
        }
        if (buildConfig.hasDomainExtensions()) {
            persistExtensions(resourcesWildFly, WfConstants.EXTENSIONS_DOMAIN, buildConfig.getDomainExtensions());
        }
        if (buildConfig.hasHostExtensions()) {
            persistExtensions(resourcesWildFly, WfConstants.EXTENSIONS_HOST, buildConfig.getHostExtensions());
        }

        // scripts
        final Path scriptsDir = resourcesDir.resolve(WfConstants.SCRIPTS);
        if (Files.exists(scriptsDir)) {
            if (!Files.isDirectory(scriptsDir)) {
                throw new MojoExecutionException(WfConstants.SCRIPTS + " is not a directory");
            }
            try {
                IoUtils.copy(scriptsDir, resourcesWildFly.resolve(WfConstants.SCRIPTS));
            } catch (IOException e) {
                throw new MojoExecutionException(Errors.copyFile(scriptsDir, resourcesWildFly.resolve(WfConstants.SCRIPTS)), e);
            }
        }

        final FeaturePackDescription fpLayout;
        try {
            fpBuilder.getSpecBuilder().setConfigStability(defaultConfigStabilityLevel);
            fpBuilder.getSpecBuilder().setPackageStability(defaultPackageStabilityLevel);
            fpLayout = fpBuilder.build();
            FeaturePackXmlWriter.getInstance().write(fpLayout.getSpec(), getFpDir().resolve(Constants.FEATURE_PACK_XML));
        } catch (XMLStreamException | IOException | ProvisioningDescriptionException e) {
            throw new MojoExecutionException(Errors.writeFile(getFpDir().resolve(Constants.FEATURE_PACK_XML)), e);
        }

        // build feature-packs from the layout and attach as project artifacts
        try (DirectoryStream<Path> wdStream = Files.newDirectoryStream(getWorkDir(), entry -> Files.isDirectory(entry))) {
            for (Path groupDir : wdStream) {
                try (DirectoryStream<Path> groupStream = Files.newDirectoryStream(groupDir)) {
                    for (Path artifactDir : groupStream) {
                        final String artifactId = artifactDir.getFileName().toString();
                        try (DirectoryStream<Path> artifactStream = Files.newDirectoryStream(artifactDir)) {
                            for (Path versionDir : artifactStream) {
                                final Path target = Paths.get(project.getBuild().getDirectory()).resolve(artifactId + '-' + versionDir.getFileName() + ".zip");
                                if (Files.exists(target)) {
                                    IoUtils.recursiveDelete(target);
                                }
                                try {
                                    //Validate that we can parse this feature-pack...
                                    FeaturePackDescription desc = FeaturePackDescriber.describeFeaturePack(versionDir, "UTF-8");
                                    checkFeaturePackContentStability(buildTimestabilityLevel, forbidLowerStatibilityLevelPackageReference, lowerStabilityPackages,
                                            desc.getPackages(), desc.getLayers(), desc.getFeatures(), desc.getConfigs(), getLog());
                                    ZipUtils.zip(versionDir, target);
                                    final Path metadataTarget = Paths.get(project.getBuild().getDirectory()).resolve(artifactId + '-'
                                            + versionDir.getFileName() + "-" + METADATA_CLASSIFIER + "." + METADATA_EXTENSION);
                                    generateMetadata(target, desc, metadataTarget);
                                    debug("Attaching feature-pack metadata %s as a project artifact", metadataTarget);
                                    projectHelper.attachArtifact(project, METADATA_EXTENSION, METADATA_CLASSIFIER, metadataTarget.toFile());
                                    Path model = Paths.get(project.getBuild().getDirectory()).resolve("model.json");
                                    if (Files.exists(model)) {
                                        final Path modelTarget = Paths.get(project.getBuild().getDirectory()).resolve(artifactId + '-'
                                            + versionDir.getFileName() + "-" + MODEL_CLASSIFIER + "." + MODEL_EXTENSION);
                                        Files.copy(model, modelTarget);
                                        debug("Attaching feature-pack model %s as a project artifact", modelTarget);
                                        projectHelper.attachArtifact(project, MODEL_EXTENSION, MODEL_CLASSIFIER, modelTarget.toFile());
                                    }
                                } catch (Exception ex) {
                                    throw new RuntimeException(ex);
                                }
                                ZipUtils.zip(versionDir, target);
                                if (project.getPackaging().equals(getPackaging())) {
                                    if (project.getArtifact().getFile() != null) {
                                        throw new MojoExecutionException("Cannot set " + target.getFileName()
                                                + " as the main project artifact file as one is already set.");
                                    } else {
                                        debug("Setting feature-pack %s as the main project artifact", target);
                                        project.getArtifact().setFile(target.toFile());
                                    }
                                } else {
                                    debug("Attaching feature-pack %s as a project artifact", target);
                                    projectHelper.attachArtifact(project, "zip", target.toFile());
                                }
                                final Path offLinerTarget = Paths.get(project.getBuild().getDirectory()).resolve(artifactId + '-'
                                        + versionDir.getFileName() + "-" + ARTIFACT_LIST_CLASSIFIER + "." + ARTIFACT_LIST_EXTENSION);
                                debug("Attaching feature-pack artifact list %s as a project artifact", offLinerTarget);
                                Files.write(offLinerTarget, builder.build().getBytes());
                                projectHelper.attachArtifact(project, ARTIFACT_LIST_EXTENSION, ARTIFACT_LIST_CLASSIFIER, offLinerTarget.toFile());
                                if (generateChannelManifest) {
                                    final Path channelManifestTarget = Paths.get(project.getBuild().getDirectory()).resolve(artifactId + '-'
                                            + versionDir.getFileName() + "-" + ChannelManifest.CLASSIFIER + "." + ChannelManifest.EXTENSION);
                                    debug("Attaching channel manifest definition %s as a project artifact", channelManifestTarget);
                                    String channelManifest = createYAMLChannelManifest(buildConfig);
                                    Files.write(channelManifestTarget, channelManifest.getBytes());
                                    if (deployChannelManifest) {
                                        projectHelper.attachArtifact(project, ChannelManifest.EXTENSION, ChannelManifest.CLASSIFIER, channelManifestTarget.toFile());
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } catch (IOException e) {
            throw new MojoExecutionException("Failed to create a feature-pack archives from the layout", e);
        }
    }

    private void generateMetadata(Path featurePack, FeaturePackDescription desc, Path metadataTarget) throws Exception {
        MavenArtifactRepositoryManager repo = new MavenArtifactRepositoryManager(repoSystem, repoSession, repositories);
        UniverseResolver resolver = UniverseResolver.builder().addArtifactResolver(repo).build();
        ProvisioningLayoutFactory fact = ProvisioningLayoutFactory.getInstance(resolver);
        fact.addLocal(featurePack, false);
        ProvisioningLayout<FeaturePackLayout> pl = fact.
                newConfigLayout(featurePack, false);
        generateMetadata(desc, pl, metadataTarget);
    }

    static class Configuration implements Comparable<Configuration> {
        String address;
        Set<String> systemProperties = new TreeSet<>();
        Set<String> envVariables = new TreeSet<>();

        @Override
        public int compareTo(Configuration t) {
            return address.compareTo(t.address);
        }
    }

    private Operation buildModel(FeatureSpec spec, List<ConfigItem> parents, FeatureConfig config, Map<String, Configuration> configuration) throws ProvisioningDescriptionException {
        System.out.println("FEATURE-SPEC " + spec.getName());
        FeatureAnnotation annot = spec.getAnnotation("jboss-op");
        if(annot == null) {
            return new Operation();
        }
        List<String> addr = annot.getElementAsList("addr-params");
        Operation op = new Operation();
        Set<String> ids = new HashSet<>();
        for(String a : addr) {
            FeatureParameterSpec fps = spec.getParam(a);
            ids.add(a);
            if("GLN_UNDEFINED".equals(fps.getDefaultValue())) {
                continue;
            }
            if(fps.hasDefaultValue()) {
                op.address.add(a+"="+fps.getDefaultValue());
            } else {
                String value = config.getParam(a);
                if(value == null) {
                    for (int i = parents.size() - 1; i >= 0; i--) {
                        ConfigItem parent = parents.get(i);
                        if (parent instanceof FeatureConfig) {
                            FeatureConfig pc = (FeatureConfig) parent;
                            value = pc.getParam(a);
                            if(value != null) {
                                break;
                            }
                        }
                    }
                    if(value == null) {
                        System.out.println("ERROR!!!!!!!!!");
                            throw new RuntimeException("Notcorrect parent for spec " + spec.getName() + "\nConfig is " + config + "\n Parents " + parents);
                    }
                }
                op.address.add(a+"="+value);
            }
        }
        if (annot.hasElement("complex-attribute")) {
            String attribute = annot.getElement("complex-attribute");
            StringBuilder val = new StringBuilder("{");
            for (Entry<String, String> entry : config.getParams().entrySet()) {
                if (!ids.contains(entry.getKey())) {
                    val.append(" " + entry.getKey() + "=" + entry.getValue() + ",");
                    addConfiguration(entry.getKey(), entry.getValue(), op, configuration);
                }
                //builder.append(entry.getKey()+"="+entry.getValue()+", ");
            }
            String clean = val.toString().substring(0, val.toString().length() - 1) + " }";
            Param p = new Param();
            //p.description = spec.getParam(attribute).getDescription();
            p.name = attribute;
            p.value = clean;
            op.params.put(p.name, p);
        } else {
            //builder.append(":"+annot.getElement("name"));
            //builder.append("(");
            for (Entry<String, String> entry : config.getParams().entrySet()) {
                if (!ids.contains(entry.getKey())) {
                    Param p = new Param();
                    p.name = entry.getKey();
                    p.value = entry.getValue();
                    op.params.put(p.name, p);
                    addConfiguration(p.name, p.value, op, configuration);
                }
                //builder.append(entry.getKey()+"="+entry.getValue()+", ");
            }
        }
        return op;
        //builder.append(")");
        //return builder.toString();
    }
    private void addConfiguration(String name, String value, Operation op, Map<String, Configuration> configuration) {
        List<Set<String>> found = parseValue(value);
        if (!found.isEmpty()) {
            StringBuilder k = new StringBuilder("/");
            for (String a : op.address) {
                k.append(a).append("/");
            }

            String key = k.substring(0, k.toString().length() - 1) + "." + name;
            Configuration s = configuration.get(key);
            if (s == null) {
                s = new Configuration();
                configuration.put(key, s);
            }
            s.envVariables.addAll(found.get(0));
            s.systemProperties.addAll(found.get(1));
        }
    }
     private static List<Set<String>> parseValue(String value) {
        char[] chars = value.toCharArray();
        List<Set<String>> lst = new ArrayList<>();
        Set<String> envs = new TreeSet<>();
        Set<String> props = new TreeSet<>();
        boolean expression = false;
        StringBuilder exp = null;
        boolean envVar = false;
        boolean expressionStart = false;
        for (int i = 0; i < chars.length; i++) {
            char c = chars[i];
            if (c == ' ') {
                continue;
            }
            if (expressionStart) {
                exp = new StringBuilder();
                String radical = value.substring(i, i + 4);
                if (radical.equals("env.")) {
                    envVar = true;
                    i += 3;
                } else {
                    exp.append(c);
                }
                expressionStart = false;
                continue;
            }
            if (expression) {
                switch (c) {
                    case '}':
                    case ':':
                        expression = false;
                        if (envVar) {
                            envs.add(exp.toString());
                        } else {
                            props.add(exp.toString());
                        }
                        envVar = false;
                        break;
                    case ',':
                        expressionStart = true;
                        if (envVar) {
                            envs.add(exp.toString());
                        } else {
                            props.add(exp.toString());
                        }
                        envVar = false;
                        break;
                    default:
                        exp.append(c);
                        break;
                }
            } else {
                if (c == '$' && chars[i + 1] == '{') {
                    expression = true;
                    expressionStart = true;
                    i += 1;
                }
            }
        }
        if (!envs.isEmpty() || !props.isEmpty()) {
            lst.add(envs);
            lst.add(props);
        }
        return lst;
    }
    private FeatureSpec getFeatureSpec(ProvisioningLayout<FeaturePackLayout> pl, String name) throws ProvisioningException {
        for (FeaturePackLayout layout : pl.getOrderedFeaturePacks()) {
            if(layout.hasFeatureSpec(name)) {
                return layout.loadFeatureSpec(name);
            }
        }
        return null;
    }
    private FeatureGroup getFeatureGroup(ProvisioningLayout<FeaturePackLayout> pl, String name) throws ProvisioningException {
        for (FeaturePackLayout layout : pl.getOrderedFeaturePacks()) {
            if(layout.hasFeatureGroup(name)) {
                return layout.loadFeatureGroupSpec(name);
            }
        }
        return null;
    }
    private ConfigLayerSpec getLayer(ProvisioningLayout<FeaturePackLayout> pl, String name) throws ProvisioningException {
        for (FeaturePackLayout layout : pl.getOrderedFeaturePacks()) {
            ConfigLayerSpec s = layout.loadConfigLayerSpec("standalone", name);
            if (s != null) {
                return s;
            }
        }
        return null;
    }
    private void generateModelUpdates(List<ConfigItem> items, List<ConfigItem> parents, ProvisioningLayout<FeaturePackLayout> pl, List<Operation> ops, Map<String, Configuration> config) throws ProvisioningDescriptionException, ProvisioningException {
        for (ConfigItem i : items) {
            if (i instanceof FeatureConfig) {
                FeatureConfig fc = (FeatureConfig) i;
                FeatureSpec fp = getFeatureSpec(pl, fc.getSpecId().getName());
                //System.out.println("Feature spec of " + fc.getSpecId() + " is " + fc);
                Operation op = buildModel(fp, parents, fc, config);
                ops.add(op);
                if (!fc.getItems().isEmpty()) {
                    parents.add(fc);
                    generateModelUpdates(fc.getItems(), parents, pl, ops, config);
                    parents.remove(parents.size() - 1);
                }
            } else {
                if (i instanceof FeatureGroup) {
                    FeatureGroup fg = (FeatureGroup) i;
                    FeatureGroup complete = getFeatureGroup(pl, fg.getName());
                    if (!complete.getItems().isEmpty()) {
                        parents.add(fg);
                        generateModelUpdates(complete.getItems(), parents, pl, ops, config);
                        parents.remove(parents.size() - 1);
                    }
                    if (!fg.getItems().isEmpty()) {
                        parents.add(fg);
                        generateModelUpdates(fg.getItems(), parents, pl, ops, config);
                        parents.remove(parents.size() - 1);
                    }
                }
            }
        }
    }
    private static class ModelItem {
        String name;
        String description;
        Map<String, Map<String,ModelItem>> children = new TreeMap<>();
        Map<String, Param> attributes = new TreeMap<>();
        ModelItem(String name) {
            this.name = name;
        }
    }
    private static class Model {
        ModelItem root = new ModelItem("/");
        Model() {
        }
        void populate(List<Operation> ops) {
            for(Operation op : ops) {
                ModelItem current = root;
                for(String item : op.address) {
                    String[] split = item.split("=");
                    String type = split[0];
                    String name = split[1];
                    Map<String, ModelItem> map = current.children.get(type);
                    if(map == null) {
                        map = new TreeMap<>();
                        current.children.put(type, map);
                    }
                    ModelItem child = map.get(name);
                    if(child == null) {
                        child = new ModelItem(name);
                        map.put(name, child);
                        current = child;
                    } else {
                        current = child;
                    }
                }
                // We have built the address fully.
                current.attributes.putAll(op.params);
            }
        }

        ObjectNode export() {
            if (root.children.isEmpty() && root.attributes.isEmpty()) {
                ObjectMapper mapper = new ObjectMapper();
                return mapper.createObjectNode();
            } else {
                ObjectNode model = export(root);
                return model;
            }
        }

        ObjectNode export(ModelItem item) {
            ObjectMapper mapper = new ObjectMapper();
            ObjectNode model = mapper.createObjectNode();
            if(item.description != null) {
                model.put("description", item.description);
            }
            if (!item.attributes.isEmpty()) {
                //ArrayNode attributesNode = mapper.createArrayNode();
                //model.putIfAbsent("attributes", attributesNode);
                for (Entry<String, Param> entry : item.attributes.entrySet()) {
                    Param p = entry.getValue();
//                    ObjectNode pNode = mapper.createObjectNode();
//                    pNode.put("name", p.name);
//                    pNode.put("value", p.value);
//                    if (p.description != null) {
//                        pNode.put("description", p.description);
//                    }
//                    attributesNode.add(pNode);
                    model.put(p.name, p.value);
                }
            }
            if (!item.children.isEmpty()) {
                //ArrayNode childrenNode = mapper.createArrayNode();
                //model.putIfAbsent("children", childrenNode);
                for (String k : item.children.keySet()) {
                    ObjectNode typeNode = mapper.createObjectNode();
                    model.putIfAbsent(k, typeNode);
                    Map<String, ModelItem> childs = item.children.get(k);
                    for(Entry<String, ModelItem> c : childs.entrySet()) {
                        typeNode.putIfAbsent(c.getKey(), export(c.getValue()));
                    }
                }
            }
            return model;
        }
    }
    private static class Param {
        String name;
        String value;
    }
    private static class Operation {
        List<String> address = new ArrayList<>();
        Map<String, Param> params = new HashMap<>();
    }
    private void generateMetadata(FeaturePackDescription desc, ProvisioningLayout<FeaturePackLayout> pl, Path metadataTarget) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode fpMetadata = mapper.createObjectNode();
        ArrayNode layers = mapper.createArrayNode();
        mapper.enable(SerializationFeature.INDENT_OUTPUT);
        fpMetadata.put("version", project.getVersion());
        fpMetadata.put("feature-pack-location", project.getGroupId() + ":" + project.getArtifactId() + ":" + project.getVersion());
        fpMetadata.put("description", project.getDescription());
        fpMetadata.put("name", project.getName());
        Map<String, List<ConfigLayerSpec>> layerSpecs = new HashMap<>();
        if(addFeaturePacksDependenciesInMetadata) {
            for (FeaturePackLayout layout : pl.getOrderedFeaturePacks()) {
                Path p = layout.getDir();
                FeaturePackDescription descDep = FeaturePackDescriber.describeFeaturePack(p, "UTF-8");
                System.out.println("FP " + descDep.getFPID());
                for (ConfigLayerSpec descLayer : descDep.getLayers()) {
                    List<ConfigLayerSpec> specs = layerSpecs.get(descLayer.getId().getName());
                    if(specs == null) {
                        specs = new ArrayList<>();
                        layerSpecs.put(descLayer.getId().getName(), specs);
                    } else {
                        System.out.println("We already have a layer " + descLayer.getId().getName());
                    }
                    specs.add(descLayer);
                }
            }
        } else {
            for (ConfigLayerSpec spec : desc.getLayers()) {
                List<ConfigLayerSpec> specs = new ArrayList<>();
                specs.add(spec);
                layerSpecs.put(spec.getName(), specs);
            }
        }
        for (Entry<String, List<ConfigLayerSpec>> entry : layerSpecs.entrySet()) {
            List<Operation> ops = new ArrayList<>();
            ObjectNode layerNode = null;
            ArrayNode depsNode = null;
            boolean propertiesAdded = false;
            Map<String, Configuration> config = new TreeMap<>();
            for (ConfigLayerSpec spec : entry.getValue()) {
                System.out.println("***************** LAYER " + spec.getName());
                String category = spec.getProperties().get("org.wildfly.category");
                if (category == null) {
                   category = "Internal";
                }
                if (category != null) {
                    if(layerNode == null) {
                        layerNode = mapper.createObjectNode();
                        layerNode.put("name", spec.getName());
                        layers.add(layerNode);
                    }
                    if (spec.hasLayerDeps()) {
                        if(depsNode == null) {
                            depsNode = mapper.createArrayNode();
                        }
                        for (GalleonLayerDependency dep : spec.getLayerDeps()) {
                            ObjectNode depNode = mapper.createObjectNode();
                            depNode.put("name", dep.getName());
                            depNode.put("optional", dep.isOptional());
                            depsNode.add(depNode);
//                        System.out.println("********* Dep spec " + dep.getName());
                        ConfigLayerSpec depSpec = getLayer(pl, dep.getName());
                        //generateConfigRecursive(depSpec, config, pl, new HashSet<>());
                        }
                    }
                    generateModelUpdates(spec.getItems(), new ArrayList<>(), pl, ops, config);
                    Model m = new Model();
                    m.populate(ops);
                    layerNode.putIfAbsent("managementModel", m.export());
                    if (!spec.getProperties().isEmpty() && !propertiesAdded) {
                        propertiesAdded = true;
                        ArrayNode propertiesNode = mapper.createArrayNode();
                        for (Entry<String, String> propsEntry : spec.getProperties().entrySet()) {
                            ObjectNode propertyNode = mapper.createObjectNode();
                            propertyNode.put("name", propsEntry.getKey());
                            propertyNode.put("value", propsEntry.getValue());
                            propertiesNode.add(propertyNode);
                            if (entry.getKey().equals("org.wildfly.rule.configuration")) {
                                String s = propsEntry.getValue();
                                // Retrieve env variable
                            }
                        }
                        layerNode.putIfAbsent("properties", propertiesNode);
                    }
                    if (!config.isEmpty()) {
                        ArrayNode configNode = mapper.createArrayNode();
                        layerNode.set("configuration", configNode);
                        for (Entry<String, Configuration> configEntry : config.entrySet()) {
                            ObjectNode attNode = mapper.createObjectNode();
                            attNode.put("attribute", configEntry.getKey());
                            Configuration attConfig = configEntry.getValue();
                            if (!attConfig.envVariables.isEmpty()) {
                                ArrayNode envNode = mapper.createArrayNode();
                                attNode.putIfAbsent("environmentVariables", envNode);
                                for (String env : attConfig.envVariables) {
                                    envNode.add(env);
                                }
                            }
                            if (!attConfig.systemProperties.isEmpty()) {
                                ArrayNode propsNode = mapper.createArrayNode();
                                attNode.putIfAbsent("systemProperties", propsNode);
                                for (String prop : attConfig.systemProperties) {
                                    propsNode.add(prop);
                                }
                            }
                            configNode.add(attNode);
                        }
                    }
                    if (spec.hasPackageDeps()) {
                        ArrayNode packagesNode = mapper.createArrayNode();
                        layerNode.putIfAbsent("packages", packagesNode);
                        if (!spec.getLocalPackageDeps().isEmpty()) {
                            for (PackageDependencySpec p : spec.getLocalPackageDeps()) {
                                packagesNode.add(p.getName());
                            }
                        }
                        for (String origin : spec.getPackageOrigins()) {
                            for (PackageDependencySpec p : spec.getExternalPackageDeps(origin)) {
                                packagesNode.add(p.getName());
                            }
                        }
                    }
                }
            }
            if (depsNode != null && layerNode != null) {
                layerNode.putIfAbsent("dependencies", depsNode);
            }
        }

        if (!layers.isEmpty()) {
            fpMetadata.putIfAbsent("layers", layers);
        }
        debug("Generating metadata definition %s as a project artifact", metadataTarget);
        mapper.writerWithDefaultPrettyPrinter().writeValue(metadataTarget.toFile(), fpMetadata);
    }

    private void generateConfigRecursive(ConfigLayerSpec layer, Map<String, Configuration> config, ProvisioningLayout<FeaturePackLayout> pl, Set<String> seen) throws ProvisioningException {
        if(seen.contains(layer.getName())) {
            return;
        }
        seen.add(layer.getName());
        generateModelUpdates(layer.getItems(), new ArrayList<>(), pl, new ArrayList<>(), config);
        for (GalleonLayerDependency dep : layer.getLayerDeps()) {
            ConfigLayerSpec depSpec = getLayer(pl, dep.getName());
            generateConfigRecursive(depSpec, config, pl, seen);
        }
    }
    private static String formatIgnoreMessage(String kind, String name, String pkgName) {
        return formatMessage(kind, name, pkgName, MSG_PACKAGE_IGNORED);
    }

    private static String formatErrorMessage(String kind, String name, String pkgName) {
        return formatMessage(kind, name, pkgName, MSG_PACKAGE_ERROR);
    }

    private static String formatMessage(String kind, String name, String pkgName, String solution) {
        return kind + name + " depends on the package " + pkgName + "." + MSG_PACKAGE_NOT_INCLUDED + solution;
    }

    static void checkFeaturePackContentStability(Stability buildTimestabilityLevel, boolean forbidLowerStatibilityLevelPackageReference,
            Set<String> lowerStabilityPackages,
            Collection<PackageSpec> packages,
            Collection<ConfigLayerSpec> layers,
            Collection<FeatureSpec> features,
            Map<String, Map<String, ConfigModel>> configs, Log log) throws Exception {

        // Validate that no packages at a lower stability level are referenced
        for (PackageSpec spec : packages) {
            if (spec.hasLocalPackageDeps()) {
                for (PackageDependencySpec pds : spec.getLocalPackageDeps()) {
                    if (lowerStabilityPackages.contains(pds.getName())) {
                        String validForStability = pds.getValidForStability();
                        if (validForStability != null) {
                            Stability minStability = Stability.fromString(validForStability);
                            if (!buildTimestabilityLevel.enables(minStability)) {
                                log.debug(formatIgnoreMessage(PACKAGE, spec.getName(), pds.getName()));
                            }
                        } else {
                            String message = formatErrorMessage(PACKAGE, spec.getName(), pds.getName());
                            if (forbidLowerStatibilityLevelPackageReference) {
                                throw new Exception(message);
                            } else {
                                log.warn(message);
                            }
                        }
                    }
                }
            }
        }
        for (ConfigLayerSpec spec : layers) {
            if (spec.hasLocalPackageDeps()) {
                for (PackageDependencySpec pds : spec.getLocalPackageDeps()) {
                    if (lowerStabilityPackages.contains(pds.getName())) {
                        String validForStability = pds.getValidForStability();
                        if (validForStability != null) {
                            Stability minStability = Stability.fromString(validForStability);
                            if (!buildTimestabilityLevel.enables(minStability)) {
                                log.debug(formatIgnoreMessage(LAYER, spec.getName(), pds.getName()));
                            }
                        } else {
                            String message = formatErrorMessage(LAYER, spec.getName(), pds.getName());
                            if (forbidLowerStatibilityLevelPackageReference) {
                                throw new Exception(message);
                            } else {
                                log.warn(message);
                            }
                        }
                    }
                }
            }
        }
        for (FeatureSpec spec : features) {
            if (spec.hasLocalPackageDeps()) {
                for (PackageDependencySpec pds : spec.getLocalPackageDeps()) {
                    if (lowerStabilityPackages.contains(pds.getName())) {
                        String validForStability = pds.getValidForStability();
                        if (validForStability != null) {
                            Stability minStability = Stability.fromString(validForStability);
                            if (!buildTimestabilityLevel.enables(minStability)) {
                                log.debug(formatIgnoreMessage(FEATURE, spec.getName(), pds.getName()));
                            }
                        } else {
                            String message = formatErrorMessage(FEATURE, spec.getName(), pds.getName());
                            if (forbidLowerStatibilityLevelPackageReference) {
                                throw new Exception(message);
                            } else {
                                log.warn(message);
                            }
                        }
                    }
                }
            }
        }
        for (Entry<String, Map<String, ConfigModel>> configModel : configs.entrySet()) {
            String modelName = configModel.getKey();
            for (Entry<String, ConfigModel> cm : configModel.getValue().entrySet()) {
                ConfigModel config = cm.getValue();
                String configName = cm.getKey();
                if (config.hasLocalPackageDeps()) {
                    for (PackageDependencySpec pds : config.getLocalPackageDeps()) {
                        if (lowerStabilityPackages.contains(pds.getName())) {
                            String validForStability = pds.getValidForStability();
                            if (validForStability != null) {
                                Stability minStability = Stability.fromString(validForStability);
                                if (!buildTimestabilityLevel.enables(minStability)) {
                                    log.debug(formatIgnoreMessage(CONFIG, modelName + "/" + configName, pds.getName()));
                                }
                            } else {
                                String message = formatErrorMessage(CONFIG, modelName + "/" + configName, pds.getName());
                                if (forbidLowerStatibilityLevelPackageReference) {
                                    throw new Exception(message);
                                } else {
                                    log.warn(message);
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    /**
     * Create a YAML Channel Manifest that defines streams for all the feature-pack
     * dependencies. The feature-pack itself is added to the channel's streams.
     */
    String createYAMLChannelManifest(WildFlyFeaturePackBuild buildConfig) throws IOException {
        ArrayList<ManifestRequirement> manifestRequirements = new ArrayList<>();
        if (addFeaturePacksAsRequiredManifests && !buildConfig.getDependencies().isEmpty()) {
            for (ArtifactCoords.Gav gav : new TreeSet<>(buildConfig.getDependencies().keySet())) {
                project.getDependencies().stream()
                        .filter(dep ->
                                gav.getGroupId().equals(dep.getGroupId())
                                        && gav.getArtifactId().equals(dep.getArtifactId())
                                        && "zip".equals(dep.getType()))
                        .findFirst()
                        .ifPresent( fp ->
                                manifestRequirements.add(new ManifestRequirement(fp.getGroupId() +":"+fp.getArtifactId(),
                                        new MavenCoordinate(fp.getGroupId(), fp.getArtifactId(), fp.getVersion()))));
            }
        }
        // append all feature-pack artifacts dependencies as streams (except zip and pom dependencies)
        List<org.wildfly.channel.Stream> streams = MavenProjectArtifactVersions.getFilteredArtifacts(project, buildConfig).stream()
                .filter(a -> !"zip".equals(a.getType()) && !"pom".equals(a.getType()))
                .map(artifact -> new org.wildfly.channel.Stream(artifact.getGroupId(), artifact.getArtifactId(), artifact.getVersion()))
                .collect(Collectors.toList());
        // add a stream for this feature pack
        streams.add(new org.wildfly.channel.Stream(project.getGroupId(), project.getArtifactId(), project.getVersion()));

        ChannelManifest channelManifest = new ChannelManifest.Builder()
                .setSchemaVersion(ChannelManifestMapper.CURRENT_SCHEMA_VERSION)
                .setName(format("Manifest for %s feature pack.", project.getArtifact()))
                .setId(project.getGroupId() + ":" + project.getArtifactId())
                .setDescription(format("Generated by org.wildfly.galleon-plugins:wildfly-galleon-maven-plugin at %s", Clock.systemUTC().instant()))
                .addManifestRequirements(manifestRequirements.toArray(new ManifestRequirement[]{}))
                .addStreams(streams.toArray(new org.wildfly.channel.Stream[]{}))
                .build();

        return ChannelManifestMapper.toYaml(channelManifest);
    }

    private void addConfigPackages(final Path configDir, final Path packagesDir, final FeaturePackDescription.Builder fpBuilder) throws MojoExecutionException {
        if (!Files.exists(configDir)) {
            return;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(configDir)) {
            for (Path configPackage : stream) {
                final Path packageDir = packagesDir.resolve(configPackage.getFileName());
                final Path packageFile = packageDir.resolve(Constants.PACKAGE_XML);
                final Path packageXml = configPackage.resolve(Constants.PACKAGE_XML);

                if (Files.exists(packageXml)) {
                    final PackageSpec pkgSpec;
                    try (BufferedReader reader = Files.newBufferedReader(packageXml)) {
                        try {
                            pkgSpec = PackageXmlParser.getInstance().parse(reader);
                        } catch (XMLStreamException e) {
                            throw new MojoExecutionException("Failed to parse " + packageXml, e);
                        }
                    }
                    Stability packageStability = pkgSpec.getStability();
                    if (packageStability != null) {
                        if (buildTimestabilityLevel != null && !buildTimestabilityLevel.enables(packageStability)) {
                            getLog().warn("Package " + pkgSpec.getName() + " is not included in the feature-pack. "
                                    + "Package stability '"
                                    + packageStability + "' is not enabled by the '" + buildTimestabilityLevel
                                    + "' stability level that is the feature-pack minimum stability level.");
                            lowerStabilityPackages.add(pkgSpec.getName());
                            continue;
                        }
                    }
                    fpBuilder.addPackage(pkgSpec);
                }

                if (Files.exists(packageFile) && Files.exists(packageXml)) {
                    warn("File " + packageFile + " already exists, replacing with " + packageXml);
                }
                if (!Files.exists(packageDir)) {
                    Util.mkdirs(packageDir);
                }
                IoUtils.copy(configPackage, packageDir);
            }
        } catch (IOException e) {
            throw new MojoExecutionException("Failed to process config packages", e);
        }
    }
    private void addFeatures(final Path configDir, final Path featuresDir) throws MojoExecutionException {
        if (!Files.exists(configDir)) {
            return;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(configDir)) {
            for (Path configFeature : stream) {
                final Path featureDir = featuresDir.resolve(configFeature.getFileName());
                final Path featureXml = configFeature.resolve(Constants.SPEC_XML);
                if (!Files.exists(featureXml)) {
                    throw new MojoExecutionException("Feature spec " + featureXml + " doesn't exist ");
                }
                final FeatureSpec featureSpec;
                try (BufferedReader reader = Files.newBufferedReader(featureXml)) {
                    try {
                        featureSpec = FeatureSpecXmlParser.getInstance().parse(reader);
                    } catch (ProvisioningDescriptionException | XMLStreamException e) {
                        throw new MojoExecutionException("Failed to parse " + featureXml, e);
                    }
                }
                Stability featureStability = featureSpec.getStability();
                if (featureStability != null) {
                    if (buildTimestabilityLevel != null && !buildTimestabilityLevel.enables(featureStability)) {
                        getLog().warn("Feature " + featureSpec.getName() + " is not included in the feature-pack. "
                                + "Feature stability '"
                                + featureStability + "' is not enabled by the '" + buildTimestabilityLevel
                                + "' stability level that is the feature-pack minimum stability level.");
                        continue;
                    }
                }
                if (!Files.exists(featureDir)) {
                    Util.mkdirs(featureDir);
                }
                IoUtils.copy(configFeature, featureDir);
            }
        } catch (IOException e) {
            throw new MojoExecutionException("Failed to process feature spec", e);
        }
    }

    private void persistExtensions(final Path resourcesWildFly, String name, List<String> extensions) throws MojoExecutionException {
        try {
            Files.write(resourcesWildFly.resolve(name), extensions);
        } catch (IOException e) {
            throw new MojoExecutionException("Failed to persist " + name, e);
        }
    }

    private Properties getFPConfigProperties() throws MojoExecutionException {
        final Properties properties = new Properties();
        properties.put("project.version", project.getVersion());
        properties.put("version", project.getVersion()); // needed for licenses.xsl
        if (releaseName != null) {
            properties.put("product.release.name", releaseName);
        }
        if (taskPropsFile != null) {
            final Path p = taskPropsFile.toPath();
            if (!Files.exists(p)) {
                throw new MojoExecutionException(Errors.pathDoesNotExist(p));
            }
            try (Reader reader = Files.newBufferedReader(p)) {
                properties.load(reader);
            } catch (IOException e) {
                throw new MojoExecutionException(Errors.readFile(p), e);
            }
        }
        if (!taskProps.isEmpty()) {
            properties.putAll(taskProps);
        }
        return properties;
    }

    private Properties getWildFlyChannelProperties() throws MojoExecutionException {
        final Properties properties = new Properties();
        properties.put(WfConstants.WILDFLY_CHANNEL_RESOLUTION_PROP, wildflyChannelResolutionMode.toString());
        return properties;
    }

    private void addPlugins(FeaturePackSpec.Builder fpBuilder, Map<String, ArtifactCoords> plugins) throws MojoExecutionException {
        for (Map.Entry<String, ArtifactCoords> entry : plugins.entrySet()) {
            ArtifactCoords coords = entry.getValue();
            if (coords.getVersion() == null) {
                coords = ArtifactCoordsUtil.fromJBossModules(
                        resolveVersion(coords.getGroupId() + ':' + coords.getArtifactId()), "jar");
            }
            try {
                resolveArtifact(ArtifactCoords.newInstance(coords.getGroupId(),
                        coords.getArtifactId(), coords.getVersion(), coords.getExtension()));
            } catch (ProvisioningException e) {
                throw new MojoExecutionException("Failed to resolve feature-pack plugin " + coords, e);
            }
            final StringBuilder buf = new StringBuilder(128);
            buf.append(coords.getGroupId()).append(':')
                    .append(coords.getArtifactId()).append(':');
            final String classifier = coords.getClassifier();
            if (classifier != null && !classifier.isEmpty()) {
                buf.append(classifier).append(':');
            }
            buf.append(coords.getExtension()).append(':').append(coords.getVersion());
            fpBuilder.addPlugin(FeaturePackPlugin.getInstance(entry.getKey(), buf.toString()));
        }
    }

    protected void processFeaturePackDependencies(WildFlyFeaturePackBuild buildConfig, final FeaturePackSpec.Builder fpBuilder) throws Exception {
        if (buildConfig.getDependencies().isEmpty()) {
            return;
        }

        fpDependencies = new LinkedHashMap<>(buildConfig.getDependencies().size());
        for (Map.Entry<ArtifactCoords.Gav, FeaturePackDependencySpec> depEntry : buildConfig.getDependencies().entrySet()) {
            ArtifactCoords depCoords = depEntry.getKey().toArtifactCoords();
            if (depCoords.getVersion() == null) {
                final String coordsStr = artifactVersions.getVersion(depCoords.getGroupId() + ':' + depCoords.getArtifactId());
                if (coordsStr == null) {
                    throw new MojoExecutionException("Failed resolve artifact version for " + depCoords);
                }
                depCoords = ArtifactCoordsUtil.fromJBossModules(coordsStr, "zip");
                if (depCoords.getExtension().equals("pom")) {
                    depCoords = new ArtifactCoords(depCoords.getGroupId(), depCoords.getArtifactId(), depCoords.getVersion(), depCoords.getClassifier(), "zip");
                }
            }
            final Path depZip = resolveArtifact(depCoords);
            final FeaturePackDependencySpec depSpec = depEntry.getValue();
            final FeaturePackConfig depConfig = depSpec.getTarget();

            final FeaturePackDescription fpDescr = FeaturePackDescriber.describeFeaturePackZip(depZip);

            // here we need to determine which format to use to persist the dependency location:
            // Maven coordinates or the FPL. The format is actually set in the feature-pack build parser.
            // However, the parser can't set the actual FPL value, since it does not have enough information.
            // So here we check whether the parser used the Galleon 1 FPL format and if so, replace it with
            // the proper FPL.
            FeaturePackLocation fpl = depEntry.getValue().getTarget().getLocation();
            if(!fpl.isMavenCoordinates()) {
                fpl = fpDescr.getFPID().getLocation();
            } else if(org.apache.commons.lang3.StringUtils.isEmpty(fpl.getBuild())) {
                fpl = fpl.replaceBuild(depCoords.getVersion());
            }
            fpBuilder.addFeaturePackDep(depSpec.getName(), FeaturePackConfig.builder(fpl).init(depConfig).build());
            fpDependencies.put(depSpec.getName(), fpDescr);
        }
    }

    public Path resolveArtifact(ArtifactCoords coords) throws ProvisioningException {
        final ProjectBuildingRequest buildingRequest = new DefaultProjectBuildingRequest(session.getProjectBuildingRequest());
        buildingRequest.setLocalRepository(session.getLocalRepository());
        buildingRequest.setRemoteRepositories(project.getRemoteArtifactRepositories());

        try {
            final ArtifactResult result = artifactResolver.resolveArtifact(buildingRequest,
                    new DefaultArtifact(coords.getGroupId(), coords.getArtifactId(), coords.getVersion(),
                            "provided", coords.getExtension(), coords.getClassifier(),
                            new DefaultArtifactHandler(coords.getExtension())));
            return result.getArtifact().getFile().toPath();
        } catch (ArtifactResolverException e) {
            throw new ProvisioningException(FpMavenErrors.artifactResolution(coords.toString()), e);
        }
    }

    protected void handleLayers(final Path srcModulesDir, final FeaturePackDescription.Builder fpBuilder,
            final Path targetResources, final PackageSpec.Builder modulesAll) throws MojoExecutionException {
        final Path layersDir = srcModulesDir.resolve(WfConstants.SYSTEM).resolve(WfConstants.LAYERS);
        if (Files.exists(layersDir)) {
            try (Stream<Path> layers = Files.list(layersDir)) {
                final Map<String, Path> moduleXmlByPkgName = new HashMap<>();
                final Iterator<Path> i = layers.iterator();
                while (i.hasNext()) {
                    final Path layerDir = i.next();
                    Util.findModules(layerDir, moduleXmlByPkgName);
                    if (moduleXmlByPkgName.isEmpty()) {
                        throw new MojoExecutionException("Modules not found in " + layerDir);
                    }
                }
                packageModules(fpBuilder, targetResources, moduleXmlByPkgName, modulesAll);
            } catch (IOException e) {
                throw new MojoExecutionException("Failed to process modules content", e);
            }
        }
        final Path layersConf = srcModulesDir.resolve(WfConstants.LAYERS_CONF);
        if (!Files.exists(layersConf)) {
            return;
        }
        final Path targetPath = getPackagesDir().resolve(WfConstants.LAYERS_CONF).resolve(Constants.CONTENT).resolve(WfConstants.MODULES).resolve(WfConstants.LAYERS_CONF);
        try {
            Files.createDirectories(targetPath.getParent());
            IoUtils.copy(layersConf, targetPath);
        } catch (IOException e) {
            throw new MojoExecutionException(Errors.copyFile(layersConf, targetPath), e);
        }
        final PackageSpec.Builder pkgBuilder = PackageSpec.builder(WfConstants.LAYERS_CONF);
        addPackage(getPackagesDir(), fpBuilder, pkgBuilder);
        fpBuilder.getSpecBuilder().addDefaultPackage(WfConstants.LAYERS_CONF);
    }

    protected void handleAddOns(final Path srcModulesDir, final FeaturePackDescription.Builder fpBuilder,
            final Path targetResources, final PackageSpec.Builder modulesAll) throws MojoExecutionException {
        final Map<String, Path> moduleXmlByPkgName = new HashMap<>();
        final Path addOnsDir = srcModulesDir.resolve(WfConstants.SYSTEM).resolve(WfConstants.ADD_ONS);
        if (Files.exists(addOnsDir)) {
            try (Stream<Path> addOn = Files.list(addOnsDir)) {
                final Iterator<Path> i = addOn.iterator();
                while (i.hasNext()) {
                    final Path addOnDir = i.next();
                    Util.findModules(addOnDir, moduleXmlByPkgName);
                    if (moduleXmlByPkgName.isEmpty()) {
                        throw new MojoExecutionException("Modules not found in " + addOnDir);
                    }
                }
                packageModules(fpBuilder, targetResources, moduleXmlByPkgName, modulesAll);
            } catch (IOException e) {
                throw new MojoExecutionException("Failed to process modules content", e);
            }
        }
    }

    protected void handleModules(final Path srcModulesDir, final FeaturePackDescription.Builder fpBuilder,
            final Path targetResources, final PackageSpec.Builder modulesAll) throws MojoExecutionException {
        try {
            final Map<String, Path> moduleXmlByPkgName = new HashMap<>();
            Util.findModules(srcModulesDir, moduleXmlByPkgName);
            packageModules(fpBuilder, targetResources, moduleXmlByPkgName, modulesAll);
        } catch (IOException e) {
            throw new MojoExecutionException("Failed to process modules content", e);
        }
    }

    protected PackageSpec addPackage(final Path fpPackagesDir, final FeaturePackDescription.Builder fpBuilder,
            final PackageSpec.Builder pkgBuilder) throws MojoExecutionException {
        final PackageSpec pkg = pkgBuilder.build();
        fpBuilder.addPackage(pkg);
        writeXml(pkg, fpPackagesDir.resolve(pkg.getName()));
        return pkg;
    }

    private void writeXml(PackageSpec pkgSpec, Path dir) throws MojoExecutionException {
        try {
            Util.mkdirs(dir);
            PackageXmlWriter.getInstance().write(pkgSpec, dir.resolve(Constants.PACKAGE_XML));
        } catch (XMLStreamException | IOException e) {
            throw new MojoExecutionException(Errors.writeFile(dir.resolve(Constants.PACKAGE_XML)), e);
        }
    }

    private void packageModules(FeaturePackDescription.Builder fpBuilder,
            Path resourcesDir, Map<String, Path> moduleXmlByPkgName, PackageSpec.Builder modulesAll)
            throws IOException, MojoExecutionException {
        Map<ModuleIdentifier, Set<ModuleIdentifier>> targetToAlias = new HashMap<>();
        for (Map.Entry<String, Path> module : moduleXmlByPkgName.entrySet()) {
            try {
                ModuleXmlParser.populateAlias(module.getValue(), WfConstants.UTF8, targetToAlias);
            } catch (ParsingException e) {
                throw new IOException(Errors.parseXml(module.getValue()), e);
            }
        }
        for (Map.Entry<String, Path> module : moduleXmlByPkgName.entrySet()) {
            final String packageName = module.getKey();
            final Path moduleXml = module.getValue();
            final Path packageDir = getPackagesDir().resolve(packageName);
            final PackageSpec.Builder pkgSpecBuilder = PackageSpec.builder(packageName);
            final ModuleParseResult parsedModule;
            try {
                parsedModule = ModuleXmlParser.parse(moduleXml, WfConstants.UTF8, targetToAlias);
                String packageStability = parsedModule.getProperty(WfConstants.JBOSS_STABILITY);
                if (packageStability != null) {
                    Stability stab = Stability.fromString(packageStability);
                    if (buildTimestabilityLevel != null && !buildTimestabilityLevel.enables(stab)) {
                        getLog().warn("JBoss Modules module " + parsedModule.getIdentifier() + " is not included in the feature-pack. "
                                + "Package stability '" +
                                packageStability + "' is not enabled by the '" + buildTimestabilityLevel +
                                "' stability level that is the feature-pack minimum stability level.");
                        lowerStabilityPackages.add(packageName);
                        continue;
                    }
                    pkgSpecBuilder.setStability(stab);
                }
                final Path targetXml = packageDir.resolve(WfConstants.PM).resolve(WfConstants.WILDFLY).resolve(WfConstants.MODULE).resolve(resourcesDir.relativize(moduleXml));
                mkdirs(targetXml.getParent());
                IoUtils.copy(moduleXml.getParent(), targetXml.getParent());
                if (!parsedModule.dependencies.isEmpty()) {
                    for (ModuleParseResult.ModuleDependency moduleDep : parsedModule.dependencies) {
                        final ModuleIdentifier moduleId = moduleDep.getModuleId();
                        String depName = moduleId.getName();
                        if (!moduleId.getSlot().equals("main")) {
                            depName += '.' + moduleId.getSlot();
                        }
                        if (moduleXmlByPkgName.containsKey(depName)) {
                            PackageDependencySpec spec = getPackageDepSpec(packageName, moduleXml, moduleDep, depName);
                            if (!spec.isOptional()) {
                                pkgSpecBuilder.addPackageDep(spec);
                            }
                            continue;
                        }
                        Map.Entry<String, FeaturePackDescription> depSrc = null;
                        if (!fpDependencies.isEmpty()) {
                            Set<String> alternativeSrc = Collections.emptySet();
                            for (Map.Entry<String, FeaturePackDescription> depEntry : fpDependencies.entrySet()) {
                                if (depEntry.getValue().hasPackage(depName)) {
                                    if (depSrc != null) {
                                        alternativeSrc = CollectionUtils.add(alternativeSrc, depSrc.getKey());
                                    }
                                    depSrc = depEntry;
                                }
                            }
                            if (!alternativeSrc.isEmpty()) {
                                final StringBuilder warn = new StringBuilder();
                                warn.append("Package ").append(depName).append(" from ").append(depSrc.getKey())
                                        .append(" picked as dependency of ").append(packageName).append(" although ")
                                        .append(depName).append(" also exists in ");
                                StringUtils.append(warn, alternativeSrc);
                                getLog().warn(warn);
                            }
                        }
                        if (depSrc != null) {
                            PackageDependencySpec spec = getPackageDepSpec(packageName, moduleXml, moduleDep, depName);
                            if (!spec.isOptional()) {
                                pkgSpecBuilder.addPackageDep(depSrc.getKey(), spec);
                            }
                        } else if (moduleDep.isOptional() || isProvided(depName)) {
                            // getLog().warn("UNSATISFIED EXTERNAL OPTIONAL DEPENDENCY " + packageName + " -> " + depName);
                        } else {
                            throw new MojoExecutionException(
                                    "Package " + packageName + " has unsatisifed external dependency on package " + depName);
                        }
                    }
                }
            } catch (ParsingException e) {
                throw new IOException(Errors.parseXml(moduleXml), e);
            }

            final PackageSpec pkgSpec = pkgSpecBuilder
               .build();
            try {
                PackageXmlWriter.getInstance().write(pkgSpec, packageDir.resolve(Constants.PACKAGE_XML));
            } catch (XMLStreamException e) {
                throw new IOException(Errors.writeFile(packageDir.resolve(Constants.PACKAGE_XML)), e);
            }
            if (modulesAll != null) {
                modulesAll.addPackageDep(packageName, true);
            }
            fpBuilder.addPackage(pkgSpec);
        }
    }

    private static PackageDependencySpec getPackageDepSpec(final String packageName, final Path moduleXml, ModuleParseResult.ModuleDependency moduleDep,
            String depName) throws ParsingException {
        final String passiveValue = moduleDep.getProperty(WfConstants.GALLEON_PASSIVE);
        final PackageDependencySpec depSpec;
        if (passiveValue != null && Boolean.parseBoolean(passiveValue)) {
            if (!moduleDep.isOptional()) {
                throw new ParsingException("Required dependency on module " + packageName + " cannot be annotated as galleon.passive in " + moduleXml);
            }
            depSpec = PackageDependencySpec.passive(depName);
        } else if (moduleDep.isOptional()) {
            depSpec = PackageDependencySpec.optional(depName);
        } else {
            depSpec = PackageDependencySpec.required(depName);
        }
        return depSpec;
    }

    protected void debug(String msg, Object... args) {
        if (getLog().isDebugEnabled()) {
            getLog().debug(format(msg, args));
        }
    }

    protected void warn(String msg, Object... args) {
        if (getLog().isWarnEnabled()) {
            getLog().warn(format(msg, args));
        }
    }

    private void buildArtifactList(ArtifactListBuilder builder) throws MojoExecutionException {
        try {
            final MavenProjectArtifactVersions projectArtifacts = MavenProjectArtifactVersions.getInstance(project);
            Set<String> allArtifacts = new TreeSet<>();
            addHardCodedArtifacts(allArtifacts);
            allArtifacts.addAll(projectArtifacts.getArtifacts().values());
            // Generate the offliner file for dependencies and hardcoded.
            for (String artifact : allArtifacts) {
                ArtifactCoords coords = ArtifactCoordsUtil.fromJBossModules(artifact, null);
                builder.add(coords);
            }
        } catch (ProvisioningException | IOException | ArtifactDescriptorException ex) {
            throw new MojoExecutionException(ex.getMessage(), ex);
        }
    }

    private void addHardCodedArtifacts(Set<String> all) throws IOException {
        Path packages = resourcesDir.resolve(Constants.PACKAGES);
        if (Files.exists(packages)) {
            processPackages(fpDir, all);
        }
        final Path projectModules = resourcesDir.resolve(WfConstants.MODULES);
        if (Files.exists(projectModules)) {
            addHardCodedArtifacts(projectModules, all);
        }
    }

    private void processPackages(Path fpDirectory, Set<String> all) throws IOException {
        Files.walkFileTree(fpDirectory, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                if (dir.endsWith(MODULE_PATH_SEGMENT)) {
                    addHardCodedArtifacts(dir, all);
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private void addHardCodedArtifacts(final Path source, Set<String> all) throws IOException {
        Files.walkFileTree(source, EnumSet.of(FileVisitOption.FOLLOW_LINKS), Integer.MAX_VALUE,
                new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                    throws IOException {
                if (WfConstants.MODULE_XML.equals(file.getFileName().toString())) {
                    try {
                        ModuleXmlVersionResolver.addHardCodedArtifacts(file, all);
                    } catch (XMLStreamException ex) {
                        throw new IOException("Error while reading " + file, ex);
                    }
                }

                return FileVisitResult.CONTINUE;
            }
        });
    }
}
