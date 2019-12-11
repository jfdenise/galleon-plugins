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

/**
 *
 * @author jdenise
 */
public class Constants {

    public static final String JAVA_OPTS = "JAVA_OPTS";
    public static final String JBOSS_HOME = "JBOSS_HOME";
    public static final String UBERJAR = "wildfly.uberjar.";
    public static final String EXTERNAL_SERVER_CONFIG_PROP = UBERJAR + "server.config";
    public static final String EXTERNAL_SERVER_CONFIG = "--uberjar-server-config";
    public static final String CLI_SCRIPT = "--uberjar-cli-script";
    public static final String CLI_SCRIPT_PROP = UBERJAR + "cli.script";
}
