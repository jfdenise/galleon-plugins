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
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.jboss.galleon.MessageWriter;
import org.jboss.galleon.ProvisioningException;
import org.jboss.galleon.runtime.ProvisioningRuntime;
import org.jboss.galleon.universe.maven.MavenArtifact;
import org.jboss.galleon.universe.maven.MavenUniverseException;
import org.wildfly.galleon.plugin.transformer.JakartaTransformer;
import org.wildfly.galleon.plugin.transformer.TransformedArtifact;

/**
 * An artifact installer that does Jakarta transformation.
 * A note on EE-9 transformation.
 * <ul>
 * <li>For fat server, if transformation is disabled, and a provisioning
 * repository has been set, the repository is expected to contain transformed
 * artifacts. When the artifact path is resolved, the transformed version file
 * path is retrieved (if any) and used as the path in the module.xml
 * resource.</li>
 * <li>For fat server, a transformed artifact (not excluded) is copied to the
 * provisioned server modules directory.</li>
 * <li>For fat server, if overridden artifacts are detected, they are
 * transformed but if the transformation didn't change the artifact, the
 * original artifacts are copied to the provisioned server modules
 * directory.</li>
 * <li>For thin server, if a provisioning-repository is provided, transformation
 * applies for coords in module.xml (versions are updated with ee9 suffix), but
 * actual transformation doesn't occur. The artifacts in this local repo are
 * considered to be already transformed. Jakarta transformation is expected to
 * be disabled in this case.</li>
 * <li>For fat and thin server, if a provisioning-repository is provided, and
 * overridden artifacts are detected, if the overridden artifacts are not
 * present in the provisioning-repository an attempt to transform the artifact
 * is made. The transformed or not transformed artifact (according to the
 * transformation result) is installed inside the provisioning-repo dir. If
 * transformation has been effective, the artifact is not excluded and the
 * module.xml coords are updated with ee9 suffix for thin server, the
 * transformed artifact is copied for fat server.</li>
 * <li>For thin server, if no provisioning-repo is provided and no maven repo is
 * to be generated, no transformation occurs at all. We don't transform the
 * artifacts located in official local maven cache</li>
 * <li>For thin server, when a maven repo is to be generated, transformation
 * occurs for not excluded artifacts. Artifact (transformed or not) are then
 * installed in the generated maven repository.</li>
 * <li>For thin server, when a maven repo is to be generated and overridden
 * artifacts are detected, an attempt to transform the artifact is made. The
 * transformed or not transformed artifact (according to the transformation
 * result) is installed inside the generated repo dir. If transformation has
 * been effective the artifact is not excluded and the module.xml coords are
 * updated with ee9 suffix.</li>
 * </ul>
 *
 */
class EE9ArtifactTransformer extends ModuleArtifactInstaller {

    /**
     * The status inside the provisioning repository (repository used to provision a server
     * that is expected to contain transformed artifacts. In case of overridden artifact, the artifact could be missing
     * and transformation should occur.
     */
    static class OverriddenArtifactStatus {
        final boolean needTransformation;
        final Path transformedFile;
        OverriddenArtifactStatus(boolean needTransformation, Path transformedFile) {
            this.needTransformation = needTransformation;
            this.transformedFile = transformedFile;
        }
    }

    private final WfInstallPlugin plugin;
    private final Path jakartaTransformConfigsDir;
    private final boolean jakartaTransformVerbose;
    private final boolean jakartaTransform;
    private final JakartaTransformer.LogHandler logHandler;
    private final MessageWriter log;
    private final Set<String> transformExcluded = new HashSet<>();
    private final String jakartaTransformSuffix;
    private final Path localMavenRepoForExampleConfigs;
    private final Path provisioningMavenRepo;
    private final ProvisioningRuntime runtime;
    private final Map<String, Path> transformedOverriden = new HashMap<>();

    EE9ArtifactTransformer(ProvisioningRuntime runtime,
            WfInstallPlugin plugin,
            String jakartaTransformSuffix,
            Path localMavenRepoForExampleConfigs,
            Path provisioningMavenRepo,
            Set<String> transformExcluded,
            boolean jakartaTransformVerbose,
            boolean jakartaTransform,
            Path jakartaTransformConfigsDir,
            JakartaTransformer.LogHandler logHandler,
            Path generatedMavenRepo) {
        super(generatedMavenRepo);
        this.runtime = runtime;
        this.plugin = plugin;
        this.jakartaTransformSuffix = jakartaTransformSuffix;
        this.localMavenRepoForExampleConfigs = localMavenRepoForExampleConfigs;
        this.provisioningMavenRepo = provisioningMavenRepo;
        this.jakartaTransformVerbose = jakartaTransformVerbose;
        this.jakartaTransform = jakartaTransform;
        this.jakartaTransformConfigsDir = jakartaTransformConfigsDir;
        this.log = plugin.log;
        this.transformExcluded.addAll(transformExcluded);
        this.logHandler = logHandler;
    }

    private boolean isExcludedFromTransformation(MavenArtifact artifact) {
        if (transformExcluded.contains(ArtifactCoords.newGav(artifact.getGroupId(), artifact.getArtifactId(), artifact.getVersion()).toString())) {
            if (log.isVerboseEnabled()) {
                log.verbose("Excluding " + artifact + " from EE9 transformation");
            }
            return true;
        }
        return false;
    }

    String getTransformedVersion(MavenArtifact artifact) {
        boolean transformed = !isExcludedFromTransformation(artifact);
        return artifact.getVersion() + (transformed ? jakartaTransformSuffix : "");
    }

    private TransformedArtifact transform(MavenArtifact artifact, Path targetDir) throws IOException {
        TransformedArtifact a = JakartaTransformer.transform(jakartaTransformConfigsDir, artifact.getPath(), targetDir, jakartaTransformVerbose, logHandler);
        return a;
    }

    private static String getTransformedArtifactFileName(String version, String fileName, String suffix) {
        final int endVersionIndex = fileName.lastIndexOf(version) + version.length();
        return fileName.substring(0, endVersionIndex) + suffix + fileName.substring(endVersionIndex);
    }

    @Override
    String installArtifactFat(MavenArtifact artifact, String artifactFileName, Path targetDir) throws IOException,
            MavenUniverseException, ProvisioningException {
        String finalFileName = artifactFileName;
        boolean needsTransformation = jakartaTransform && !isExcludedFromTransformation(artifact);
        if (needsTransformation) {
            String gav = ArtifactCoords.newGav(artifact.getGroupId(), artifact.getArtifactId(), artifact.getVersion()).toString();
            Path transformedFile = transformedOverriden.get(gav);
            if (transformedFile == null) {
                finalFileName = getTransformedArtifactFileName(artifact.getVersion(), finalFileName, jakartaTransformSuffix);
                Path transformedPath = targetDir.resolve(finalFileName);
                transform(artifact, transformedPath);
            } else {
                // Copy Already transformed overridden artifact
                finalFileName = transformedFile.getFileName().toString();
                Files.copy(transformedFile, targetDir.resolve(finalFileName), StandardCopyOption.REPLACE_EXISTING);
            }
        } else {
            finalFileName = super.installArtifactFat(artifact, artifactFileName, targetDir);
        }
        if (hasLocalCache()) {
            installInCache(needsTransformation, artifact, finalFileName, targetDir);
        }

        return finalFileName;
    }

    private void installInCache(boolean toTransform, MavenArtifact artifact, String finalFileName, Path targetDir) throws MavenUniverseException, IOException, ProvisioningException {
        // Copy to the transformationMavenRepo for future use
        Path pomFile = plugin.getPomArtifactPath(artifact);
        // Copy to the maven repo with transformed suffix.
        if (toTransform) {
            artifact.setVersion(artifact.getVersion() + jakartaTransformSuffix);
        }
        Path versionPath = resolveInCache(artifact);
        Files.copy(pomFile, versionPath.resolve(pomFile.getFileName().toString()), StandardCopyOption.REPLACE_EXISTING);
        Files.copy(targetDir.resolve(finalFileName), versionPath.resolve(finalFileName), StandardCopyOption.REPLACE_EXISTING);
    }

    boolean hasLocalCache() {
        return localMavenRepoForExampleConfigs != null;
    }

    private Path resolveInCache(MavenArtifact artifact) throws IOException {
        return getLocalRepoPath(artifact, localMavenRepoForExampleConfigs);
    }

    /**
     *
     * Attempt to transform and install in provisioning repo if not present.
     * Exclude it if not transformed, store transformed file for use when artifact is installed.
     */
    @Override
    void setupOverriddenArtifact(ModuleTemplateProcessor.ModuleArtifact moduleArtifact) throws IOException, MavenUniverseException, ProvisioningException {
        MavenArtifact artifact = moduleArtifact.getMavenArtifact();
        String version = artifact.getVersion();
        Path artifactPath = artifact.getPath();
        OverriddenArtifactStatus status;
        // Lookup in the provisioning repository
        // Could be already transformed.
        if (provisioningMavenRepo != null) {
            status = getOverriddenStatus(artifact, version);
        } else {
            // We need transformation attempt for overridden artifact
            status = new OverriddenArtifactStatus(true, null);
        }
        Path finalTransformedFile = status.transformedFile;
        if (status.needTransformation) {
            // We don't know the state of this artifact, we must transform it.
            String transformedFileName = getTransformedArtifactFileName(artifact.getVersion(), artifact.getPath().getFileName().toString(), jakartaTransformSuffix);
            finalTransformedFile = tryTransformation(artifact, transformedFileName);
            // Update the provisioning repository with the new artifact.
            // The generated Maven repository is populated when installing the artifact.
            if (provisioningMavenRepo != null) {
                Path pomFile = plugin.getPomArtifactPath(artifact);
                // Copy the original one
                if (finalTransformedFile == null) {
                    Path notTransformedVersionPath = getLocalRepoPath(artifact, provisioningMavenRepo);
                    Files.copy(artifactPath, notTransformedVersionPath.resolve(artifactPath.getFileName().toString()), StandardCopyOption.REPLACE_EXISTING);
                    Files.copy(pomFile, notTransformedVersionPath.resolve(pomFile.getFileName().toString()), StandardCopyOption.REPLACE_EXISTING);
                } else {
                    // Copy the transformed one
                    artifact.setVersion(version + jakartaTransformSuffix);
                    Path transformedVersionPath = getLocalRepoPath(artifact, provisioningMavenRepo);
                    Files.copy(finalTransformedFile, transformedVersionPath.resolve(transformedFileName), StandardCopyOption.REPLACE_EXISTING);
                    Files.copy(pomFile, transformedVersionPath.resolve(pomFile.getFileName().toString()), StandardCopyOption.REPLACE_EXISTING);
                    artifact.setVersion(version);
                }
                // We must force the artifact to be resolved again, the provisioning repo has been updated.
                // The artifact is now in the provisioning repository
                moduleArtifact.reResolveArtifact();
            }
        }
        // Store the File and update exclusion status, they will be used when installing the artifact.
        String gav = ArtifactCoords.newGav(artifact.getGroupId(), artifact.getArtifactId(), version).toString();
        if (finalTransformedFile == null) {
            log.verbose("The overridden artifact " + gav + " is excluded from transformation");
            // We must exclude it from future transformation,
            transformExcluded.add(gav);
        } else {
            log.verbose("The overridden artifact " + gav + " is transformed");
            transformedOverriden.put(gav, finalTransformedFile);
        }
    }

    private Path tryTransformation(MavenArtifact artifact, String transformedFileName) throws IOException {
        // We don't know the state of this artifact, we must transform it.
        Path transformedFile = runtime.getTmpPath(transformedFileName);
        Files.createDirectories(transformedFile);
        Files.deleteIfExists(transformedFile);
        TransformedArtifact transformedArtifact = transform(artifact, transformedFile);
        if (!transformedArtifact.isTransformed()) {
            transformedFile = null;
        }
        return transformedFile;
    }

    OverriddenArtifactStatus getOverriddenStatus(MavenArtifact artifact, String originalVersion) throws IOException {
        String transformedVersion = originalVersion + jakartaTransformSuffix;
        artifact.setVersion(transformedVersion);
        boolean needTransformation = true;
        Path transformedFile = null;
        Path transformedPath = getLocalRepoPath(artifact, provisioningMavenRepo, false);
        if (Files.exists(transformedPath)) {
            // This repository already contains the transformed artifact. This artifact is not excluded from transformation
            needTransformation = false;
            transformedFile = artifact.getPath();
        } else {
            artifact.setVersion(originalVersion);
            Path notTransformedPath = getLocalRepoPath(artifact, provisioningMavenRepo, false);
            if (Files.exists(notTransformedPath)) {
                // The artifact is not transformed and present in the repo, so excluded from transformation.
                needTransformation = false;
            }
        }
        // Reset the version to the original one.
        artifact.setVersion(originalVersion);
        return new OverriddenArtifactStatus(needTransformation, transformedFile);
    }

    @Override
    String installArtifactThin(MavenArtifact artifact) throws IOException, MavenUniverseException, ProvisioningException {
        boolean isExcluded = isExcludedFromTransformation(artifact);
        boolean requireSuffix = (jakartaTransform || provisioningMavenRepo != null) && !isExcluded;
        String installedVersion = artifact.getVersion() + (requireSuffix ? jakartaTransformSuffix : "");
        String originalVersion = artifact.getVersion();
        // Copy the artifact to the Maven repository
        if (getGeneratedMavenRepo() != null) {
            String gav = ArtifactCoords.newGav(artifact.getGroupId(), artifact.getArtifactId(), originalVersion).toString();
            Path transformedFile = transformedOverriden.get(gav);
            artifact.setVersion(installedVersion);
            Path versionPath = getLocalRepoPath(artifact, getGeneratedMavenRepo());
            if (jakartaTransform && !isExcluded) {
                // The artifact could already exist in case of redefined module as alias (eg: javax.security.jacc.api).
                // The transformer doesn't accept existing target, so skip transformation.
                String name = getTransformedArtifactFileName(originalVersion, artifact.getPath().getFileName().toString(), jakartaTransformSuffix);
                Path transformedTarget = versionPath.resolve(name);
                if (!Files.exists(transformedTarget)) {
                    if (transformedFile == null) {
                        transform(artifact, transformedTarget);
                    } else {
                        // An overridden artifact that we just transformed
                        Files.copy(transformedFile, transformedTarget, StandardCopyOption.REPLACE_EXISTING);
                    }
                }
            } else {
                // We could have a transformed overridden artifact
                if (transformedFile == null) {
                    super.installArtifactThin(artifact);
                } else {
                    Files.copy(transformedFile, versionPath.resolve(transformedFile.getFileName()), StandardCopyOption.REPLACE_EXISTING);
                }
            }
            // Resolve the original pom file.
            artifact.setVersion(originalVersion);
            Path pomFile = plugin.getPomArtifactPath(artifact);
            Files.copy(pomFile, versionPath.resolve(pomFile.getFileName().toString()), StandardCopyOption.REPLACE_EXISTING);
        }

        return installedVersion;
    }
}
