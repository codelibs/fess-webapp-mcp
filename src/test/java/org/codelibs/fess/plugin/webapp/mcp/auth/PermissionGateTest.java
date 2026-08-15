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
package org.codelibs.fess.plugin.webapp.mcp.auth;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * Test class for {@link PermissionGate}.
 */
public class PermissionGateTest {

    @Test
    public void testEmptyRequiredSetAllowsEvenANullPrincipal() {
        // If the required.isEmpty() short-circuit were removed, this would NPE on
        // principal.getPermissions() instead of returning true.
        assertTrue(PermissionGate.isAllowed(Set.of(), null));
    }

    @Test
    public void testNullRequiredSetAllowsEvenANullPrincipal() {
        // Every shipped McpTool#getRequiredPermissions() honours the non-null contract that
        // method's own Javadoc documents, so this is latent in practice -- but a third-party
        // tool that returned null would otherwise NPE every caller out of tools/list, not just
        // itself. If the required == null half of the guard were removed, this would NPE on
        // required.isEmpty() instead of returning true.
        assertTrue(PermissionGate.isAllowed(null, null));
    }

    @Test
    public void testEmptyRequiredSetAllowsAnAnonymousPrincipal() {
        assertTrue(PermissionGate.isAllowed(Set.of(), McpPrincipal.anonymous()));
    }

    @Test
    public void testNonEmptyRequiredSetRejectsANullPrincipal() {
        // If the null check were removed, this would NPE instead of returning false.
        assertFalse(PermissionGate.isAllowed(Set.of("Radmin-api"), null));
    }

    @Test
    public void testNonEmptyRequiredSetRejectsAPrincipalWithNoPermissions() {
        assertFalse(PermissionGate.isAllowed(Set.of("Radmin-api"), McpPrincipal.anonymous()));
    }

    @Test
    public void testNonEmptyRequiredSetRejectsAPrincipalWithADisjointPermissionSet() {
        // If anyMatch were mutated to allMatch (or intersection logic dropped for equals()),
        // a caller with an unrelated permission would still need to be rejected.
        final McpPrincipal principal = new McpPrincipal("u", Set.of(), Set.of("1guest"));
        assertFalse(PermissionGate.isAllowed(Set.of("Radmin-api"), principal));
    }

    @Test
    public void testAllowedWhenThePrincipalHoldsExactlyOneOfSeveralRequiredPermissions() {
        // Set intersection, not set equality or "must hold all": holding just one of several
        // required permissions is enough.
        final McpPrincipal principal = new McpPrincipal("u", Set.of(), Set.of("Sadmin-api"));
        assertTrue(PermissionGate.isAllowed(Set.of("Radmin-api", "Sadmin-api", "Aadmin-api"), principal));
    }

    @Test
    public void testAllowedWhenThePrincipalHoldsAllRequiredPermissions() {
        final McpPrincipal principal = new McpPrincipal("u", Set.of(), Set.of("Radmin-api", "Sadmin-api"));
        assertTrue(PermissionGate.isAllowed(Set.of("Radmin-api", "Sadmin-api"), principal));
    }
}
