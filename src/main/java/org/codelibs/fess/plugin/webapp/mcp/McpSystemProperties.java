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

import org.codelibs.fess.mylasta.direction.FessConfig;

/**
 * Reads this plugin's numeric {@code mcp.*} system properties.
 * <p>
 * Exists because {@code FessProp#getSystemPropertyAsInt} is
 * {@code Integer.parseInt(value)} with no {@code trim()} and a swallowed
 * {@code NumberFormatException}, while {@code java.util.Properties#load} -- which is what
 * {@code DynamicProperties} uses to read {@code WEB-INF/conf/system.properties} -- strips
 * <em>leading</em> whitespace from a value but preserves <em>trailing</em> whitespace. So
 * {@code mcp.request.max.bytes=1048576 } parses as {@code "1048576 "}, throws, is swallowed, and
 * silently reverts to the built-in default with nothing logged: the operator sees their setting
 * ignored and has no way to tell why.
 * </p>
 * <p>
 * {@code AbstractCacheableHandler} already trimmed before parsing its {@code ttl.ms} keys, so
 * without this the plugin disagreed with itself about whether a stray space matters. Routing
 * every numeric read through here settles that one way.
 * </p>
 */
public final class McpSystemProperties {

    private McpSystemProperties() {
        // utility class
    }

    /**
     * Reads {@code key} as an {@code int}, tolerating surrounding whitespace.
     *
     * @param fessConfig the config to read through
     * @param key the system property key
     * @param defaultValue the value to return when the property is unset, blank, or not a number
     * @return the configured value, or {@code defaultValue}
     */
    public static int getAsInt(final FessConfig fessConfig, final String key, final int defaultValue) {
        final String raw = fessConfig.getSystemProperty(key);
        if (raw != null) {
            try {
                return Integer.parseInt(raw.trim());
            } catch (final NumberFormatException e) {
                // An unparseable value falls back to the default, matching
                // FessProp#getSystemPropertyAsInt's behaviour for a genuinely non-numeric value.
            }
        }
        return defaultValue;
    }
}
