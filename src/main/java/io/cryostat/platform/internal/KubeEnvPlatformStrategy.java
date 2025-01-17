/*
 * Copyright The Cryostat Authors
 *
 * The Universal Permissive License (UPL), Version 1.0
 *
 * Subject to the condition set forth below, permission is hereby granted to any
 * person obtaining a copy of this software, associated documentation and/or data
 * (collectively the "Software"), free of charge and under any and all copyright
 * rights in the Software, and any and all patent rights owned or freely
 * licensable by each licensor hereunder covering either (i) the unmodified
 * Software as contributed to or provided by such licensor, or (ii) the Larger
 * Works (as defined below), to deal in both
 *
 * (a) the Software, and
 * (b) any piece of software and/or hardware listed in the lrgrwrks.txt file if
 * one is included with the Software (each a "Larger Work" to which the Software
 * is contributed by such licensors),
 *
 * without restriction, including without limitation the rights to copy, create
 * derivative works of, display, perform, and distribute the Software and make,
 * use, sell, offer for sale, import, export, have made, and have sold the
 * Software and the Larger Work(s), and to sublicense the foregoing rights on
 * either these or other terms.
 *
 * This license is subject to the following condition:
 * The above copyright notice and either this complete permission notice or at
 * a minimum a reference to the UPL must be included in all copies or
 * substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package io.cryostat.platform.internal;

import java.io.IOException;
import java.nio.file.Paths;

import io.cryostat.core.log.Logger;
import io.cryostat.core.net.JFRConnectionToolkit;
import io.cryostat.core.sys.Environment;
import io.cryostat.core.sys.FileSystem;
import io.cryostat.net.AuthManager;

import dagger.Lazy;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.fabric8.kubernetes.client.Config;

class KubeEnvPlatformStrategy implements PlatformDetectionStrategy<KubeEnvPlatformClient> {

    private final Lazy<JFRConnectionToolkit> connectionToolkit;
    private final Logger logger;
    private final FileSystem fs;
    private final AuthManager authMgr;
    private final Environment env;

    KubeEnvPlatformStrategy(
            Logger logger,
            FileSystem fs,
            AuthManager authMgr,
            Lazy<JFRConnectionToolkit> connectionToolkit,
            Environment env) {
        this.logger = logger;
        this.fs = fs;
        this.authMgr = authMgr;
        this.connectionToolkit = connectionToolkit;
        this.env = env;
    }

    @Override
    public int getPriority() {
        return PRIORITY_PLATFORM;
    }

    @Override
    public boolean isAvailable() {
        logger.trace("Testing KubeEnv Platform Availability");
        return env.getEnv().keySet().stream().anyMatch(s -> s.equals("KUBERNETES_SERVICE_HOST"));
    }

    @Override
    public KubeEnvPlatformClient getPlatformClient() {
        logger.info("Selected KubeEnv Platform Strategy");
        return new KubeEnvPlatformClient(getNamespace(), connectionToolkit, env, logger);
    }

    @Override
    public AuthManager getAuthManager() {
        return authMgr;
    }

    @SuppressFBWarnings("DMI_HARDCODED_ABSOLUTE_FILENAME")
    private String getNamespace() {
        try {
            return fs.readString(Paths.get(Config.KUBERNETES_NAMESPACE_PATH));
        } catch (IOException e) {
            logger.trace(e);
            return null;
        }
    }
}
