/*
 * Copyright 2012-2025 CodeLibs Project and the Others.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific language
 * governing permissions and limitations under the License.
 */
package org.codelibs.fess.plugin.webapp.api.mcp;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * Source-scanning regression guard: {@code response.sendError(...)} must never appear anywhere
 * under {@code src/main/java}.
 *
 * <p>
 * This cannot be checked with a runtime assertion against the mocklet response: on
 * {@code MockletHttpServletResponseImpl}, {@code sendError(int)} just delegates to
 * {@code setStatus(int)} and {@code sendRedirect(String)} is a complete no-op, so a
 * {@code sendError()}-based handler and a correct {@code setStatus()}-based handler are
 * observationally identical through the mock. In the real container, Fess's {@code web.xml}
 * maps 400/401/403/404/408/429/500 to {@code redirect.jsp}, which {@code sendRedirect}s; {@code
 * /mcp} is not recognised by {@code WebApiUtil.isApiRequestUri} (which only knows {@code /api/v2}
 * and {@code /admin/server_}), so a {@code sendError} silently becomes a 302 and loses both the
 * intended status code and any {@code WWW-Authenticate} challenge. This test scans the actual
 * production source files instead, so a regression is caught regardless of how it is observed
 * at runtime.
 * </p>
 */
public class SendErrorProhibitedTest {

    /** The substring that must not appear in any production source file. */
    private static final String FORBIDDEN = ".sendError(";

    /**
     * Scans every {@code .java} file under {@code src/main/java} and fails if any line contains
     * {@code response.sendError(...)} (matched as the substring {@code .sendError(}, which also
     * catches other receiver names such as {@code resp.sendError(} or {@code httpResponse.sendError(}).
     *
     * @throws IOException if the source tree cannot be read
     */
    @Test
    public void testNoSendErrorInProductionSources() throws IOException {
        final Path sourceRoot = Paths.get("src/main/java");
        assertTrue(Files.isDirectory(sourceRoot),
                "src/main/java must exist relative to the module working directory; found: " + sourceRoot.toAbsolutePath());

        final List<Path> visited = new ArrayList<>();
        final List<String> violations = new ArrayList<>();
        try (Stream<Path> files = Files.walk(sourceRoot)) {
            files.filter(p -> p.toString().endsWith(".java")).forEach(path -> {
                visited.add(path);
                try {
                    final List<String> lines = Files.readAllLines(path);
                    for (int i = 0; i < lines.size(); i++) {
                        if (lines.get(i).contains(FORBIDDEN)) {
                            violations.add(path + ":" + (i + 1) + ": " + lines.get(i).trim());
                        }
                    }
                } catch (final IOException e) {
                    throw new UncheckedIOException("Failed to read " + path, e);
                }
            });
        }

        // Self-check: a scanner that silently visits nothing is exactly the failure mode this
        // test replaces, so require it to have actually walked real files, including the class
        // this guard exists for.
        assertTrue(!visited.isEmpty(),
                "scanner visited no .java files under " + sourceRoot.toAbsolutePath() + " -- looked in the wrong place?");
        assertTrue(visited.stream().anyMatch(p -> p.toString().endsWith("McpApiManager.java")),
                "scanner did not visit McpApiManager.java -- looked in the wrong place? visited: " + visited);

        if (!violations.isEmpty()) {
            fail("response.sendError(...) must never be used: Fess's web.xml maps 400/401/403/404/408/429/500 "
                    + "to redirect.jsp, which sendRedirect()s; /mcp is not recognised by WebApiUtil.isApiRequestUri, "
                    + "so sendError() silently becomes a 302 and loses the intended status code and any "
                    + "WWW-Authenticate challenge. Use setStatus() plus an explicit body instead. Offending line(s):\n"
                    + String.join("\n", violations));
        }
    }
}
