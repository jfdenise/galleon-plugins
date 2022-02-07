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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.MavenProjectHelper;
import org.wildfly.galleon.plugin.ShadedModel;

@Mojo(name = "generate-shaded-descriptor", defaultPhase = LifecyclePhase.PACKAGE, requiresDependencyResolution = ResolutionScope.RUNTIME)
public class ShadedJARModelGenerator extends AbstractMojo {

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    protected MavenProject project;

   @Parameter(defaultValue = "${project.build.directory}", readonly = true, required = true)
    String projectBuildDir;

    @Parameter( alias="main-class")
    String mainClass;
    @Parameter()
    Map<String,String> manifestEntries;
    @Component
    private MavenProjectHelper projectHelper;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
       List<Artifact> artifacts = new ArrayList<>();
        if (!"pom".equals(project.getArtifact().getType())) {
            Path classes = Paths.get(project.getBuild().getOutputDirectory());
           if(Files.exists(classes)) {
               System.out.println("JAR is not empty " + classes);
                artifacts.add(project.getArtifact());
           } else {
               System.out.println("JAR is empty " + classes);
           }
        }

        for (Artifact artifact : project.getArtifacts()) {
            if (MavenProjectArtifactVersions.TEST_JAR.equals(artifact.getType()) ||
                    MavenProjectArtifactVersions.SYSTEM.equals(artifact.getScope())) {
                continue;
            }
            artifacts.add(artifact);
        }

        Path file = Paths.get(projectBuildDir).resolve(project.getArtifactId()+"-"+
                project.getVersion() +"-"+ShadedModel.CLASSIFIER+"."+ShadedModel.EXTENSION);
        try {
            Files.write(file, ShadedModel.getXMLContent(project.getName(), artifacts, mainClass, manifestEntries).getBytes());
        } catch (IOException ex) {
            throw new MojoExecutionException(ex.getLocalizedMessage(), ex);
        }
        projectHelper.attachArtifact(project, ShadedModel.EXTENSION, ShadedModel.CLASSIFIER, file.toFile());
    }
}
