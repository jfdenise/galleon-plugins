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
package org.wildfly.galleon.uberjar;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 *
 * @author jdenise
 */
public class Main {

    private static final Set<PosixFilePermission> EXECUTE_PERMISSIONS = new HashSet<>();

    static {
        EXECUTE_PERMISSIONS.add(PosixFilePermission.OWNER_EXECUTE);
        EXECUTE_PERMISSIONS.add(PosixFilePermission.OWNER_WRITE);
        EXECUTE_PERMISSIONS.add(PosixFilePermission.OWNER_READ);
        EXECUTE_PERMISSIONS.add(PosixFilePermission.GROUP_EXECUTE);
        EXECUTE_PERMISSIONS.add(PosixFilePermission.GROUP_WRITE);
        EXECUTE_PERMISSIONS.add(PosixFilePermission.GROUP_READ);
        EXECUTE_PERMISSIONS.add(PosixFilePermission.OTHERS_EXECUTE);
        EXECUTE_PERMISSIONS.add(PosixFilePermission.OTHERS_READ);
    }

    public static void main(String[] args) throws Exception {
        Path destDir = Files.createTempDirectory(null);
        long t = System.currentTimeMillis();
        try ( InputStream wf = Main.class.getResourceAsStream("/wildfly.zip")) {
            unzip(wf, destDir.toFile());
        }
        System.out.println("Installed Wildfly and application in " + destDir + ", took " + (System.currentTimeMillis() - t) + "ms");
        try {
            List<String> command = new ArrayList<>();
            Path openshiftLauncher = destDir.resolve("bin/openshift-launch.sh");
            Path defaultLauncher = destDir.resolve("bin/standalone.sh");
            if (Files.exists(openshiftLauncher)) {
                command.add(openshiftLauncher.toString());
            } else {
                command.add(defaultLauncher.toString());
            }
            String cli = System.getProperty("org.wildfly.additional.cli.boot.script");
            if (cli != null) {
                command.add("--start-mode=admin-only");
            }
            for (String a : args) {
                command.add(a);
            }
            for (String sysProp : System.getProperties().stringPropertyNames()) {
                command.add("-D" + sysProp + "=" + System.getProperty(sysProp));
            }
            final ProcessBuilder processBuilder = new ProcessBuilder(command).inheritIO();
            processBuilder.environment().put("JBOSS_HOME", destDir.toString());
            Process p = processBuilder.start();
            p.waitFor();
        } finally {
            deleteDir(destDir);
        }
    }

    private static void unzip(InputStream wf, File dir) throws Exception {
        byte[] buffer = new byte[1024];
        try ( ZipInputStream zis = new ZipInputStream(wf)) {
            ZipEntry ze = zis.getNextEntry();
            while (ze != null) {
                String fileName = ze.getName();
                File newFile = new File(dir, fileName);
                if (fileName.endsWith("/")) {
                    newFile.mkdirs();
                    zis.closeEntry();
                    ze = zis.getNextEntry();
                    continue;
                }
                try ( FileOutputStream fos = new FileOutputStream(newFile)) {
                    int len;
                    while ((len = zis.read(buffer)) > 0) {
                        fos.write(buffer, 0, len);
                    }
                }
                if (newFile.getName().endsWith(".sh")) {
                    Files.setPosixFilePermissions(newFile.toPath(), EXECUTE_PERMISSIONS);
                }
                zis.closeEntry();
                ze = zis.getNextEntry();
            }
        }
    }

    private static void deleteDir(Path root) {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                        throws IOException {
                    try {
                        Files.delete(file);
                    } catch (IOException ex) {
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException e)
                        throws IOException {
                    if (e != null) {
                        // directory iteration failed
                        throw e;
                    }
                    try {
                        Files.delete(dir);
                    } catch (IOException ex) {
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
        }
    }
}
