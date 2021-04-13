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
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.regex.Matcher;
import org.jboss.galleon.ProvisioningException;
import org.jboss.galleon.universe.maven.MavenArtifact;
import org.jboss.galleon.universe.maven.MavenUniverseException;

/**
 * Abstract installer, does nominal installation for fat and thin server.
 *
 * @author jdenise
 */
public abstract class ModuleArtifactInstaller {

    private final Path generatedMavenRepo;
    ModuleArtifactInstaller(Path generatedMavenRepo) {
        this.generatedMavenRepo = generatedMavenRepo;
    }
    String installArtifactFat(MavenArtifact artifact, String artifactFileName, Path targetDir) throws IOException,
            MavenUniverseException, ProvisioningException  {
        Files.copy(artifact.getPath(), targetDir.resolve(artifactFileName), StandardCopyOption.REPLACE_EXISTING);
        return artifactFileName;
    }

    Path getGeneratedMavenRepo() {
        return generatedMavenRepo;
    }

    String installArtifactThin(MavenArtifact artifact) throws IOException,
            MavenUniverseException, ProvisioningException  {
        if (generatedMavenRepo != null) {
            Path versionPath = getLocalRepoPath(artifact, generatedMavenRepo);
            Path actualTarget = versionPath.resolve(artifact.getPath().getFileName().toString());
            Files.copy(artifact.getPath(), actualTarget, StandardCopyOption.REPLACE_EXISTING);
        }
        return artifact.getVersion();
    }

    void setupOverriddenArtifact(MavenArtifact mavenArtifact) throws IOException, MavenUniverseException, ProvisioningException {
        // Nothing to do, overridden artifacts will get resolved from maven repo.
    }

    static Path getLocalRepoPath(MavenArtifact artifact, Path repo) throws IOException {
        return getLocalRepoPath(artifact, repo, true);
    }

    static Path getLocalRepoPath(MavenArtifact artifact, Path repo, boolean create) throws IOException {
        String grpid = artifact.getGroupId().replaceAll("\\.", Matcher.quoteReplacement(File.separator));
        Path grpidPath = repo.resolve(grpid);
        Path artifactidPath = grpidPath.resolve(artifact.getArtifactId());
        Path versionPath = artifactidPath.resolve(artifact.getVersion());
        if (create) {
            Files.createDirectories(versionPath);
        }
        return versionPath;
    }
}
