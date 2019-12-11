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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;
import static org.wildfly.galleon.uberjar.Constants.CLI_SCRIPT;
import static org.wildfly.galleon.uberjar.Constants.CLI_SCRIPT_PROP;
import static org.wildfly.galleon.uberjar.Constants.CONFIG;
import static org.wildfly.galleon.uberjar.Constants.EXTERNAL_SERVER_CONFIG;
import static org.wildfly.galleon.uberjar.Constants.EXTERNAL_SERVER_CONFIG_PROP;
import static org.wildfly.galleon.uberjar.Constants.JAVA_OPTS;
import static org.wildfly.galleon.uberjar.Constants.JBOSS_HOME;
import static org.wildfly.galleon.uberjar.Constants.OPENSHIFT_CONFIG;
import static org.wildfly.galleon.uberjar.Constants.OPENSHIFT_LAUNCHER;
import static org.wildfly.galleon.uberjar.Constants.STANDALONE_LAUNCHER;
import static org.wildfly.galleon.uberjar.Constants.UBERJAR;

/**
 *
 * @author jdenise
 */
class UberJar {

    private final List<String> cmd;
    private boolean usage;
    private boolean openshift;
    private final Path jbossHome;
    private List<String> uberjarSystemProperties = new ArrayList<>();

    public UberJar(Path jbossHome, String[] args) throws IOException {
        Objects.requireNonNull(jbossHome);
        this.jbossHome = jbossHome;
        Path script = getScript(jbossHome);
        List<String> filteredArgs = handleJarArguments(jbossHome, args);
        CommandLineBuilder builder = new CommandLineBuilder(script);
        for (String sysProp : System.getProperties().stringPropertyNames()) {
            String value = System.getProperty(sysProp);
            if (sysProp.startsWith(UBERJAR)) {
                uberjarSystemProperties.add(CommandLineBuilder.formatSystemProperty(sysProp, value));
                if (EXTERNAL_SERVER_CONFIG_PROP.equals(sysProp)) {
                    addExternalConfig(value, filteredArgs);
                } else {
                    if (CLI_SCRIPT_PROP.equals(sysProp)) {
                        addCliScript(value, filteredArgs);
                    }
                }
            }
        }
        builder.addArguments(filteredArgs);
        cmd = builder.build();
    }

    private Path getScript(Path jbossHome) {
        if (isWindows()) {
            return jbossHome.resolve("bin/standalone.bat");
        }
        Path openshiftLauncher = jbossHome.resolve(OPENSHIFT_LAUNCHER);
        Path defaultLauncher = jbossHome.resolve(STANDALONE_LAUNCHER);
        if (Files.exists(openshiftLauncher)) {
            openshift = true;
            return openshiftLauncher;
        } else {
            return defaultLauncher;
        }
    }

    private List<String> handleJarArguments(Path jbossHome, String[] args) throws IOException {
        List<String> arguments = new ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if (EXTERNAL_SERVER_CONFIG.equals(a)) {
                if (i == args.length - 1) {
                    throw new RuntimeException("An external server configuration must be provided");
                }
                String path = args[i + 1];
                i += 1;
                addExternalConfig(path, arguments);
            } else if (CLI_SCRIPT.equals(a)) {
                if (i == args.length - 1) {
                    throw new RuntimeException("A cli script must be provided");
                }
                String path = args[i + 1];
                i += 1;
                addCliScript(path, arguments);
            } else if ("--help".equals(a) || "-h".equals(a)) {
                usage = true;
                arguments.add(a);
            } else {
                arguments.add(a);
            }

        }
        return arguments;
    }

    private void addExternalConfig(String path, List<String> arguments) throws IOException {
        Path p = Paths.get(path);
        if (!Files.exists(p)) {
            throw new RuntimeException("File " + p + " doesn't exist");
        }
        String targetName = CONFIG;
        Path configRoot = jbossHome.resolve("standalone/configuration/");
        if (openshift) {
            // 2 cases, could have standalone,xml or standalone-openshift.xml config.
            Path conf = configRoot.resolve(OPENSHIFT_CONFIG);
            if (Files.exists(conf)) {
                targetName = OPENSHIFT_CONFIG;
            }
        }
        Path target = configRoot.resolve(targetName);
        Files.copy(p, target);
    }

    private void addCliScript(String path, List<String> arguments) throws IOException {
        Path p = Paths.get(path);
        if (!Files.exists(p)) {
            throw new RuntimeException("File " + p + " doesn't exist");
        }
        arguments.add("--start-mode=admin-only");
        arguments.add("-Dorg.wildfly.additional.cli.boot.script=" + path);
    }

    private void printUsage() throws IOException {
        InputStream stream = UberJar.class.getClassLoader().getResourceAsStream("help.txt");
        BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8));
        String helpLine = reader.readLine();
        while (helpLine != null) {
            System.out.println(helpLine);
            helpLine = reader.readLine();
        }
    }

    public void run() throws IOException, InterruptedException {
        if (usage) {
            printUsage();
            if (!openshift) {
                startServerForHelp();
            }
        } else {
            startServer();
        }

    }

    private void startServer() throws InterruptedException, IOException {
        final ProcessBuilder processBuilder = new ProcessBuilder(cmd).inheritIO();
        configureProcessBuilder(processBuilder);
        Process p = processBuilder.start();
        p.waitFor();
    }

    private void configureProcessBuilder(ProcessBuilder processBuilder) {
        // In some context (eg: java s2i), JAVA_OPTS could contain system properties
        // specific to uberjar, erasing them for launched server.
        if (!uberjarSystemProperties.isEmpty()) {
            String env = System.getenv(JAVA_OPTS);
            if (env != null) {
                for (String s : uberjarSystemProperties) {
                    env = env.replace(s, "");
                }
            }
            processBuilder.environment().put(JAVA_OPTS, env);
        }
        processBuilder.environment().put(JBOSS_HOME, jbossHome.toString());
    }

    private void startServerForHelp() throws InterruptedException, IOException {
        final ProcessBuilder processBuilder = new ProcessBuilder(cmd).redirectErrorStream(true).redirectInput(ProcessBuilder.Redirect.INHERIT);
        configureProcessBuilder(processBuilder);
        Process p = processBuilder.start();
        BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8));
        Thread r = new Thread(() -> {
            try {
                String line = reader.readLine();
                boolean active = false;
                while (line != null) {
                    // Hacky, this trace shouldn't change in process-controller
                    if (line.startsWith("where args include:")) {
                        active = true;
                    } else {
                        if (active) {
                            System.out.println(line);
                        }
                    }
                    line = reader.readLine();
                }
            } catch (IOException ex) {
                Logger.getLogger(UberJar.class.getName()).log(Level.SEVERE, null, ex);
            }
        });
        r.start();
        p.waitFor();
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", null).toLowerCase(Locale.ENGLISH).contains("windows");
    }
}
