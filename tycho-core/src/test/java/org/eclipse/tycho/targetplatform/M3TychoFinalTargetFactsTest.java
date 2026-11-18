/*******************************************************************************
 * Copyright (c) 2026 Contributors to the Synexia Project.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/.
 *
 * SPDX-License-Identifier: EPL-2.0
 *******************************************************************************/
package org.eclipse.tycho.targetplatform;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.eclipse.equinox.p2.metadata.IInstallableUnit;
import org.eclipse.tycho.ReactorProject;
import org.eclipse.tycho.TargetPlatform;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Original Tycho final p2 target is the sole evidence authority. */
public class M3TychoFinalTargetFactsTest {
    private static final String INVOCATION = "9ab5cc33-33bd-4f33-8aa9-6c3c11223344";
    @TempDir Path root;

    @Test
    public void originalFinalTargetOnlyWithoutPreliminaryOrNetworkResolution() {
        ReactorProject project = mock(ReactorProject.class);
        P2TargetPlatform finalTarget = mock(P2TargetPlatform.class);
        when(project.getContextValue(TargetPlatform.FINAL_TARGET_PLATFORM_KEY)).thenReturn(finalTarget);
        when(project.getGroupId()).thenReturn("example.bundle");
        when(project.getArtifactId()).thenReturn("sample");
        when(project.getVersion()).thenReturn("1.2.3");
        when(project.getPackaging()).thenReturn("eclipse-plugin");
        when(finalTarget.getInstallableUnits()).thenReturn(Set.<IInstallableUnit>of());
        when(finalTarget.getOriginalReactorProjectMap()).thenReturn(Map.of());
        when(finalTarget.getOriginalMavenArtifactMap()).thenReturn(Map.of());

        var facts = M3TychoFinalTargetFacts.capture(project, finalTarget,
                "Sample", "Debug", INVOCATION);
        assertEquals("Sample", facts.eclipseProject());
        assertEquals("eclipse-plugin", facts.reactorPackaging());
        assertEquals(0, facts.unitCount());
        assertEquals(0, facts.reactorOriginCount());
        assertFalse(facts.authorizesBuildSkipping());

        // The API must never even *read* a preliminary context, since Tycho
        // may lazily evaluate that value during a read.
        org.mockito.Mockito.verify(project).getContextValue(TargetPlatform.FINAL_TARGET_PLATFORM_KEY);
        org.mockito.Mockito.verify(project, org.mockito.Mockito.never())
                .getContextValue(TargetPlatform.PRELIMINARY_TARGET_PLATFORM_KEY);
        org.mockito.Mockito.verify(finalTarget, org.mockito.Mockito.never()).resolveArtifact(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString());

        ReactorProject missingFinal = mock(ReactorProject.class);
        assertThrows(IllegalArgumentException.class, () ->
                M3TychoFinalTargetFacts.capture(missingFinal, finalTarget, "Sample", "Debug", INVOCATION));
    }

    @Test
    public void canonicalUnitDigestIsOrderIndependentButVersionSensitive() {
        List<M3TychoFinalTargetFacts.UnitKey> original = List.of(
                new M3TychoFinalTargetFacts.UnitKey("org.eclipse.core.runtime", "3.34.0"),
                new M3TychoFinalTargetFacts.UnitKey("org.eclipse.jdt.core", "3.38.0"),
                new M3TychoFinalTargetFacts.UnitKey("org.eclipse.swt", "3.126.0"));
        var first = facts(original);
        var reordered = facts(List.of(original.get(2), original.get(0), original.get(1)));
        assertEquals(first.unitSha256(), reordered.unitSha256());
        assertEquals(3, first.unitCount());
        assertEquals(2, first.reactorOriginCount());
        assertEquals(1, first.mavenOriginCount());
        var revised = facts(List.of(original.get(0),
                new M3TychoFinalTargetFacts.UnitKey("org.eclipse.jdt.core", "3.39.0"),
                original.get(2)));
        assertNotEquals(first.unitSha256(), revised.unitSha256());
        assertFalse(first.authorizesBuildSkipping());
        assertEquals(new String(first.toExchangeBytes(), StandardCharsets.UTF_8),
                new String(reordered.toExchangeBytes(), StandardCharsets.UTF_8));
    }

    @Test
    public void rejectsDuplicateP2CoordinatesAndMalformedOwnerIdentity() {
        var unit = new M3TychoFinalTargetFacts.UnitKey("org.eclipse.swt", "3.126.0");
        assertThrows(IllegalArgumentException.class, () -> facts(List.of(unit, unit)));
        assertThrows(IllegalArgumentException.class, () ->
                M3TychoFinalTargetFacts.fromResolvedKeys("Sample", "not-a-uuid", "Debug",
                        "example", "sample", "1", "eclipse-plugin", List.of(unit), 0, 0));
        assertThrows(IllegalArgumentException.class, () ->
                new M3TychoFinalTargetFacts.UnitKey("bad\nname", "1.0.0"));
        assertThrows(IllegalArgumentException.class, () ->
                M3TychoFinalTargetFacts.fromResolvedKeys("Sample", INVOCATION, "Debug",
                        "example", "sample", "1", "eclipse-plugin", List.of(unit), 0,
                        M3TychoFinalTargetFacts.MAX_UNITS + 1));
    }

    @Test
    public void goldenOriginalTychoAndIndependentMavenPacketBytesMatch() throws Exception {
        var packet = new M3TychoFinalTargetFacts.Snapshot("Sample", INVOCATION, "Debug",
                "example.bundle", "sample", "1.2.3", "eclipse-plugin",
                "a".repeat(64), 3, 2, 1);
        try (var source = M3TychoFinalTargetFactsTest.class.getResourceAsStream(
                "/m3-tycho-final-target-facts-v1.fixture")) {
            if (source == null) throw new IOException("missing original Tycho golden fixture");
            assertArrayEquals(source.readAllBytes(), packet.toExchangeBytes());
        }
    }

    @Test
    public void explicitExportIsIdempotentAndRefusesAConflictingGeneration() throws Exception {
        var facts = facts(List.of(new M3TychoFinalTargetFacts.UnitKey(
                "org.eclipse.core.runtime", "3.34.0")));
        Path parent = Files.createDirectories(root.resolve("target/ide"));
        Path relative = Path.of("target/ide/tycho-final-target-facts.ide");
        Path packet = M3TychoFinalTargetFacts.writeSnapshot(facts, root, relative);
        assertEquals(parent.resolve("tycho-final-target-facts.ide"), packet);
        assertArrayEquals(facts.toExchangeBytes(), Files.readAllBytes(packet));
        assertEquals(packet, M3TychoFinalTargetFacts.writeSnapshot(facts, root, relative));
        var altered = M3TychoFinalTargetFacts.fromResolvedKeys(
                "Sample", "c111dc33-33bd-4f33-8aa9-6c3c11223344", "Debug",
                "example.bundle", "sample", "1.2.3", "eclipse-plugin",
                List.of(new M3TychoFinalTargetFacts.UnitKey("org.eclipse.core.runtime", "3.34.0")), 2, 1);
        assertThrows(IOException.class, () -> M3TychoFinalTargetFacts.writeSnapshot(altered, root, relative));
        assertThrows(IOException.class, () -> M3TychoFinalTargetFacts.writeSnapshot(facts, root,
                Path.of("../foreign.tycho")));
        assertArrayEquals(facts.toExchangeBytes(), Files.readAllBytes(packet));
    }

    private static M3TychoFinalTargetFacts.Snapshot facts(List<M3TychoFinalTargetFacts.UnitKey> units) {
        return M3TychoFinalTargetFacts.fromResolvedKeys(
                "Sample", INVOCATION, "Debug", "example.bundle", "sample",
                "1.2.3", "eclipse-plugin", units, 2, 1);
    }
}
