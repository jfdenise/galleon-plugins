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

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 *
 * @author jdenise
 */
public class CommandLineBuilder {

    private final Set<String> systemProperties = new HashSet<>();
    private final Path script;
    private final List<String> args;

    public CommandLineBuilder(Path script, List<String> args) {
        Objects.requireNonNull(script);
        this.script = script;
        this.args = args == null ? Collections.emptyList() : args;
    }

    public void addSystemProperty(String key, String value) {
        systemProperties.add("-D" + key + "=" + value);
    }

    public List<String> build() {
        List<String> cmd = new ArrayList<>();
        cmd.add(script.toString());
        cmd.addAll(args);
        cmd.addAll(systemProperties);
        return cmd;
    }
}
