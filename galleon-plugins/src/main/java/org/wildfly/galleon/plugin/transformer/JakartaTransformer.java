/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package org.wildfly.galleon.plugin.transformer;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;

import org.wildfly.transformer.tool.api.ToolUtils;

/**
 *
 * @author jdenise
 */
public class JakartaTransformer {

    public static final String TRANSFORM_ARTIFACTS = "jakarta.transform.artifacts";
    public static final String TRANSFORM_MODULES = "jakarta.transform.modules";

    public static void transformModules(Path modules, Path targetModules) throws IOException {
        ToolUtils.transformModules(modules, targetModules, null, false, null);
    }

    public static void transformJarFile(final File inJarFile, final File outputFolder) throws IOException {
        ToolUtils.transformJarFile(inJarFile, outputFolder, null);
    }
}
