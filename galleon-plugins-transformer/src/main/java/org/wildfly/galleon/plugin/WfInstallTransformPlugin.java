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
package org.wildfly.galleon.plugin;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import org.jboss.galleon.Errors;
import org.jboss.galleon.ProvisioningException;
import org.jboss.galleon.ProvisioningOption;
import org.jboss.galleon.plugin.InstallPlugin;
import org.jboss.galleon.plugin.ProvisioningPluginWithOptions;
import org.jboss.galleon.runtime.FeaturePackRuntime;
import org.jboss.galleon.runtime.ProvisioningRuntime;
import org.jboss.galleon.universe.maven.MavenArtifact;
import org.jboss.galleon.util.CollectionUtils;
import org.wildfly.galleon.plugin.AbstractWfInstallPlugin.ArtifactResolver;
import org.wildfly.galleon.plugin.transformer.JakartaTransformer;

/**
 * WildFly install plugin. Handles all WildFly specifics that occur during provisioning.
 * The combinations of supported options for jakarta transformation for transformable feature-pack is:
 * <ul>
 *   <li> No option set: Transformation for fat server will occur.</li>
 *   <li> jboss-maven-dist and jboss-maven-repo. Thin server, artifacts transformed and copied to the generated repo.</li>
 *   <li> jboss-jakarta-transform-artifacts=false and jboss-maven-provisioning-repo, optionally jboss-maven-dist.
 *           Fat or thin server. No artifacts transformed (except for overridden artifact not present in provisioning repository).</li>
 * </ul>
 * When jakarta transformation occurs and overridden artifacts have been provided the following logic applies:
 * <ul>
 *   <li>If the overridden artifact is already present in the jboss-maven-provisioning-repo, no transformation occurs.</li>
 *   <li>Otherwise an attempt to transform the artifact is operated. If not transformed, the original arifact is used.</li>
 * </ul>
 * @author Alexey Loubyansky
 */
public class WfInstallTransformPlugin extends ProvisioningPluginWithOptions implements InstallPlugin {
    public static final String JAKARTA_TRANSFORM_SUFFIX_KEY = "jakarta.transform.artifacts.suffix";
    static final ProvisioningOption OPTION_JAKARTA_TRANSFORM_ARTIFACTS = ProvisioningOption.builder("jboss-jakarta-transform-artifacts")
            .setBooleanValueSet()
            .build();
    static final ProvisioningOption OPTION_JAKARTA_TRANSFORM_ARTIFACTS_VERBOSE = ProvisioningOption.builder("jboss-jakarta-transform-artifacts-verbose")
            .setBooleanValueSet()
            .build();

    private Set<String> transformExcluded = new HashSet<>();
    private final WfInstallPlugin basePlugin;
    public WfInstallTransformPlugin() {
        basePlugin = new WfInstallPlugin(new TransformationContextFactory() {
            @Override
            public TransformationContext buildContext(Map<String, String> mergedTaskProps, Path generatedMavenRepo,
                    Path provisioningMavenRepo) throws ProvisioningException {
                WfInstallPlugin.ArtifactResolver artifactResolver = null;
                AbstractArtifactInstaller artifactInstaller = null;
                // We must create resolver and installer at this point, prior to process the packges.
                // The CopyArtifact tasks could need the resolver and installer we are instantiating there.
                boolean transformableFeaturePack = Boolean.valueOf(mergedTaskProps.getOrDefault(JakartaTransformer.TRANSFORM_ARTIFACTS, "false"));
                if (!transformableFeaturePack) {
                    artifactResolver = WfInstallTransformPlugin.this::resolveMaven;
                    artifactInstaller = new SimpleArtifactInstaller(artifactResolver, generatedMavenRepo);
                } else {
                    String jakartaTransformSuffix = mergedTaskProps.getOrDefault(JAKARTA_TRANSFORM_SUFFIX_KEY, "");
                    boolean jakartaTransformVerbose = isVerboseTransformation();
                    final String jakartaConfigsDir = mergedTaskProps.get(JakartaTransformer.TRANSFORM_CONFIGS_DIR);
                    Path jakartaTransformConfigsDir = null;
                    if (jakartaConfigsDir != null) {
                        jakartaTransformConfigsDir = Paths.get(jakartaConfigsDir);
                    }
                    JakartaTransformer.LogHandler logHandler = new JakartaTransformer.LogHandler() {
                        @Override
                        public void print(String format, Object... args) {
                            basePlugin.getLog().print(format, args);
                        }
                    };
                    if (isTransformationEnabled()) {
                        // Artifacts are transformed, no provisioning repository can be set
                        if (provisioningMavenRepo != null) {
                            throw new ProvisioningException("Jakarta transformation is enabled, option "
                                    + basePlugin.OPTION_MVN_PROVISIONING_REPO.getName() + " can't be set.");
                        }
                        if (basePlugin.isThinServer() && generatedMavenRepo == null) {
                            throw new ProvisioningException("Jakarta transformation is enabled for thin server, option "
                                    + basePlugin.OPTION_MVN_REPO.getName() + " is required.");
                        }
                        artifactResolver = WfInstallTransformPlugin.this::resolveMaven;
                        artifactInstaller = new EE9ArtifactTransformerInstaller(artifactResolver, generatedMavenRepo, transformExcluded, basePlugin,
                                jakartaTransformSuffix, jakartaTransformConfigsDir, logHandler, jakartaTransformVerbose, basePlugin.getRuntime());
                    } else {
                        // Disabled transformation, we must have a provisioning repository
                        if (provisioningMavenRepo == null) {
                            throw new ProvisioningException("Jakarta transformation is disabled, "
                                    + basePlugin.OPTION_MVN_PROVISIONING_REPO.getName() + " must be set");
                        }
                        artifactResolver = new ArtifactResolver() {
                            @Override
                            public void resolve(MavenArtifact artifact) throws ProvisioningException {
                                resolveMaven(artifact, jakartaTransformSuffix);
                            }
                        };
                        artifactInstaller = new EE9ArtifactInstaller(artifactResolver, generatedMavenRepo, transformExcluded, basePlugin,
                                jakartaTransformSuffix, jakartaTransformConfigsDir, logHandler, jakartaTransformVerbose, basePlugin.getRuntime(), provisioningMavenRepo);
                    }
                }
                return new JakartaTransformationContext(artifactInstaller, artifactResolver, transformableFeaturePack);
            }
        });
    }
    @Override
    protected List<ProvisioningOption> initPluginOptions() {
        List<ProvisioningOption> base = basePlugin.initPluginOptions();
        List<ProvisioningOption> all = new ArrayList<>();
        all.addAll(base);
        all.add(OPTION_JAKARTA_TRANSFORM_ARTIFACTS);
        all.add(OPTION_JAKARTA_TRANSFORM_ARTIFACTS_VERBOSE);
        return all;
    }

    private boolean isTransformationEnabled() throws ProvisioningException {
        if (!basePlugin.getRuntime().isOptionSet(OPTION_JAKARTA_TRANSFORM_ARTIFACTS)) {
            return true;
        }
        final String value = basePlugin.getRuntime().getOptionValue(OPTION_JAKARTA_TRANSFORM_ARTIFACTS);
        return value == null ? true : Boolean.parseBoolean(value);
    }

    private boolean isVerboseTransformation() throws ProvisioningException {
        return getBooleanOption(OPTION_JAKARTA_TRANSFORM_ARTIFACTS_VERBOSE);
    }

    private boolean getBooleanOption(ProvisioningOption option) throws ProvisioningException {
        if (!basePlugin.getRuntime().isOptionSet(option)) {
            return false;
        }
        final String value = basePlugin.getRuntime().getOptionValue(option);
        return value == null ? true : Boolean.parseBoolean(value);
    }

    @Override
    public void preInstall(ProvisioningRuntime runtime) throws ProvisioningException {
        basePlugin.preInstall(runtime);
    }

    /* (non-Javadoc)
     * @see org.jboss.galleon.util.plugin.ProvisioningPlugin#execute()
     */
    @Override
    public void postInstall(ProvisioningRuntime runtime) throws ProvisioningException {
        System.out.println("POST INSTALL MY FRIEND");
        for(FeaturePackRuntime fp : runtime.getFeaturePacks()) {
            final Path wfRes = fp.getResource(WfConstants.WILDFLY);
            if(!Files.exists(wfRes)) {
                continue;
            }
            final Path excludedArtifacts = wfRes.resolve(WfConstants.WILDFLY_JAKARTA_TRANSFORM_EXCLUDES);
            if (Files.exists(excludedArtifacts)) {
                try (BufferedReader reader = Files.newBufferedReader(excludedArtifacts, StandardCharsets.UTF_8)) {
                    String line = reader.readLine();
                    while (line != null) {
                        transformExcluded = CollectionUtils.add(transformExcluded, line);
                        line = reader.readLine();
                    }
                } catch (IOException e) {
                    throw new ProvisioningException(Errors.readFile(excludedArtifacts), e);
                }
            }
        }
        basePlugin.postInstall(runtime);
    }
    void resolveMaven(MavenArtifact artifact) throws ProvisioningException {
        basePlugin.resolveMaven(artifact);
    }

    void resolveMaven(MavenArtifact artifact, String suffix) throws ProvisioningException {
        Path provisioningMavenRepo = basePlugin.getProvisioningMavenRepo();
        if (provisioningMavenRepo == null) {
            basePlugin.getMavenRepoManager().resolve(artifact);
        } else {
            String grpid = artifact.getGroupId().replaceAll("\\.", Matcher.quoteReplacement(File.separator));
            Path grpidPath = provisioningMavenRepo.resolve(grpid);
            Path artifactidPath = grpidPath.resolve(artifact.getArtifactId());
            // The transformed version, if any, exists in the provisioning maven repository.
            // Attempt to resolve it from there.
            String version = artifact.getVersion() + suffix;
            Path versionPath = artifactidPath.resolve(version);
            String classifier = (artifact.getClassifier() == null || artifact.getClassifier().isEmpty()) ? null : artifact.getClassifier();
            Path localPath = versionPath.resolve(artifact.getArtifactId() + "-"
                    + version
                    + (classifier == null ? "" : "-" + classifier)
                    + "." + artifact.getExtension());

            if (Files.exists(localPath)) {
                artifact.setPath(localPath);
            } else {
                resolveMaven(artifact);
            }
        }
    }

    public static String getTransformedArtifactFileName(String version, String fileName, String suffix) {
        final int endVersionIndex = fileName.lastIndexOf(version) + version.length();
        return fileName.substring(0, endVersionIndex) + suffix + fileName.substring(endVersionIndex);
    }
}
