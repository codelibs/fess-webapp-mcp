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
package org.codelibs.fess.plugin.webapp.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

import org.codelibs.fess.mylasta.direction.FessConfig;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link McpSystemProperties}.
 */
public class McpSystemPropertiesTest {

    /** Minimal {@link FessConfig} stand-in: only getSystemProperty(String) is reached. */
    private static FessConfig configOf(final Map<String, String> values) {
        return (FessConfig) java.lang.reflect.Proxy.newProxyInstance(FessConfig.class.getClassLoader(), new Class<?>[] { FessConfig.class },
                (proxy, method, args) -> {
                    if ("getSystemProperty".equals(method.getName()) && args != null && args.length == 1) {
                        return values.get((String) args[0]);
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
    }

    @Test
    public void testTrailingWhitespaceIsToleratedRatherThanRevertingToTheDefault() {
        // java.util.Properties#load strips a value's LEADING whitespace but preserves its
        // TRAILING whitespace -- see testPropertiesLoadKeepsTrailingWhitespace below, which pins
        // the premise. FessProp#getSystemPropertyAsInt is Integer.parseInt with no trim and a
        // swallowed NumberFormatException, so "1048576 " silently became the built-in default
        // with nothing logged: the operator's setting was ignored with no way to tell why.
        final Map<String, String> values = new HashMap<>();
        values.put("mcp.request.max.bytes", "1048576 ");
        values.put("leading", "  2048");
        values.put("both", "\t4096\t");

        assertEquals(1048576, McpSystemProperties.getAsInt(configOf(values), "mcp.request.max.bytes", 999),
                "a trailing space must not silently revert the setting to its default");
        assertEquals(2048, McpSystemProperties.getAsInt(configOf(values), "leading", 999));
        assertEquals(4096, McpSystemProperties.getAsInt(configOf(values), "both", 999));
    }

    @Test
    public void testAbsentBlankAndNonNumericAllFallBackToTheDefault() {
        final Map<String, String> values = new HashMap<>();
        values.put("blank", "   ");
        values.put("words", "sixty");

        assertEquals(60, McpSystemProperties.getAsInt(configOf(values), "absent", 60));
        assertEquals(60, McpSystemProperties.getAsInt(configOf(values), "blank", 60));
        assertEquals(60, McpSystemProperties.getAsInt(configOf(values), "words", 60), "a genuinely non-numeric value still defaults");
    }

    @Test
    public void testNegativeAndBoundaryValuesSurvive() {
        final Map<String, String> values = new HashMap<>();
        values.put("negative", "-2");
        values.put("max", String.valueOf(Integer.MAX_VALUE));

        assertEquals(-2, McpSystemProperties.getAsInt(configOf(values), "negative", 60),
                "the clamps that consume these values live at their call sites and must still see a negative");
        assertEquals(Integer.MAX_VALUE, McpSystemProperties.getAsInt(configOf(values), "max", 60));
    }

    @Test
    public void testPropertiesLoadKeepsTrailingWhitespace() throws Exception {
        // Pins the premise the whole class exists for. If a future JDK ever trimmed trailing
        // whitespace here, McpSystemProperties would be dead weight and this test says so.
        final Properties properties = new Properties();
        properties.load(new ByteArrayInputStream("mcp.request.max.bytes=1048576 \n".getBytes(StandardCharsets.UTF_8)));

        assertEquals("1048576 ", properties.getProperty("mcp.request.max.bytes"),
                "Properties#load strips leading but not trailing whitespace");
    }
}
