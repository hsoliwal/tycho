/*******************************************************************************
 * Copyright (c) 2026 Contributors to the Synexia Project.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/.
 *
 * SPDX-License-Identifier: EPL-2.0
 *******************************************************************************/
package org.eclipse.tycho.core.resolver;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.OperationCanceledException;
import org.eclipse.equinox.p2.metadata.IInstallableUnit;
import org.eclipse.equinox.p2.metadata.MetadataFactory;
import org.eclipse.equinox.p2.metadata.MetadataFactory.InstallableUnitDescription;
import org.eclipse.equinox.p2.metadata.Version;
import org.eclipse.tycho.ReactorProject;
import org.eclipse.tycho.TargetPlatform;
import org.eclipse.tycho.targetplatform.P2TargetPlatform;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Original final p2 IU identity, not a preliminary target or cache receipt. */
class M3FinalP2BuildEvidenceTest {
    private static final String INVOCATION = "9ab5cc33-33bd-4f33-8aa9-6c3c11223344";

    @TempDir Path temporary;

    @Test
    void capturesOnlyExistingFinalPlatformWithoutComputingOrDownloading() throws Exception {
        IInstallableUnit a = unit("org.eclipse.swt", "3.130.0");
        IInstallableUnit b = unit("org.eclipse.jdt.core", "3.40.0");
        P2TargetPlatform finalP2 = mock(P2TargetPlatform.class);
        when(finalP2.getInstallableUnits()).thenReturn(Set.of(a, b));
        ReactorProject project = reactor(finalP2);
        var source = M3FinalP2BuildEvidence.captureExistingFinal(
                project, INVOCATION, "Debug", "linux", "gtk", "x86_64", null);
        assertEquals(2, source.unitCount());
        assertEquals("linux", source.os());
        assertEquals("gtk", source.ws());
        assertEquals("x86_64", source.arch());
        assertTrue(source.unitsSha256().matches("[a-f0-9]{64}"));
        assertFalse(source.authorizesBuildSkipping());
        assertArrayEquals(source.toExchangeBytes(), M3FinalP2BuildEvidence.captureExistingFinal(
                project, INVOCATION, "Debug", "linux", "gtk", "x86_64", null).toExchangeBytes());
        assertTrue(new String(source.toExchangeBytes(), StandardCharsets.UTF_8)
                .startsWith(M3FinalP2BuildEvidence.SCHEMA + "\nprovider\ttycho.p2\n"));

        // P2TargetPlatform returns a Set; identity must not depend on input order.
        var reordered = M3FinalP2BuildEvidence.captureFromFinal(
                "org.example", "pde", "1.0.0", "eclipse-plugin", INVOCATION,
                "Debug", "linux", "gtk", "x86_64", List.of(b, a), null);
        assertEquals(source.unitsSha256(), reordered.unitsSha256());
        var changed = M3FinalP2BuildEvidence.captureFromFinal(
                "org.example", "pde", "1.0.0", "eclipse-plugin", INVOCATION,
                "Debug", "linux", "gtk", "x86_64",
                List.of(a, unit("org.eclipse.jdt.core", "3.41.0")), null);
        assertNotEquals(source.unitsSha256(), changed.unitsSha256());
    }

    @Test
    void refusesPreliminaryOrUnresolvedPlatformAndDuplicateUnits() {
        var reactor = mock(ReactorProject.class);
        P2TargetPlatform preliminary = mock(P2TargetPlatform.class);
        when(reactor.getContextValue(TargetPlatform.PRELIMINARY_TARGET_PLATFORM_KEY))
                .thenReturn(preliminary);
        assertThrows(IllegalArgumentException.class,
                () -> M3FinalP2BuildEvidence.captureExistingFinal(
                        reactor, INVOCATION, "Debug", "linux", "gtk", "x86_64", null));
        IInstallableUnit same = unit("org.eclipse.swt", "3.130.0");
        assertThrows(IllegalArgumentException.class,
                () -> M3FinalP2BuildEvidence.captureFromFinal(
                        "org.example", "pde", "1.0.0", "eclipse-plugin",
                        INVOCATION, "Debug", "linux", "gtk", "x86_64",
                        List.of(same, same), null));
    }

    @Test
    void cancellationAndConflictingExportAreFailClosed() throws Exception {
        var monitor = new NullProgressMonitor();
        monitor.setCanceled(true);
        assertThrows(OperationCanceledException.class,
                () -> M3FinalP2BuildEvidence.captureFromFinal(
                        "org.example", "pde", "1.0.0", "eclipse-plugin",
                        INVOCATION, "Debug", "linux", "gtk", "x86_64",
                        List.of(unit("org.eclipse.swt", "3.130.0")), monitor));
        var first = new M3FinalP2BuildEvidence.Snapshot("org.example", "pde",
                "1.0.0", "eclipse-plugin", INVOCATION, "Debug",
                "linux", "gtk", "x86_64", 2, "a".repeat(64));
        Path parent = Files.createDirectories(temporary.resolve("target/ide"));
        Path packet = M3FinalP2BuildEvidence.writeSnapshot(first, temporary,
                Path.of("target/ide/tycho-final-p2-facts.ide"));
        assertEquals(parent.resolve("tycho-final-p2-facts.ide"), packet);
        assertArrayEquals(first.toExchangeBytes(), Files.readAllBytes(packet));
        assertEquals(packet, M3FinalP2BuildEvidence.writeSnapshot(first, temporary,
                Path.of("target/ide/tycho-final-p2-facts.ide")));
        var other = new M3FinalP2BuildEvidence.Snapshot("org.example", "pde",
                "1.0.0", "eclipse-plugin", INVOCATION, "Release",
                "linux", "gtk", "x86_64", 2, "b".repeat(64));
        assertThrows(IOException.class, () -> M3FinalP2BuildEvidence.writeSnapshot(
                other, temporary, Path.of("target/ide/tycho-final-p2-facts.ide")));
        assertThrows(IOException.class, () -> M3FinalP2BuildEvidence.writeSnapshot(
                first, temporary, Path.of("../foreign.ide")));
    }

    @Test
    void originalWireMatchesIndependentMavenFixture() throws IOException {
        var source = new M3FinalP2BuildEvidence.Snapshot("org.example", "pde",
                "1.0.0", "eclipse-plugin", INVOCATION, "Debug",
                "linux", "gtk", "x86_64", 2, "a".repeat(64));
        try (var in = getClass().getResourceAsStream("/m3-tycho-final-p2-facts-v1.fixture")) {
            if (in == null) throw new IOException("missing Maven/Tycho final p2 fixture");
            assertArrayEquals(in.readAllBytes(), source.toExchangeBytes());
        }
    }

    private static ReactorProject reactor(P2TargetPlatform target) {
        ReactorProject result = mock(ReactorProject.class);
        when(result.getContextValue(TargetPlatform.FINAL_TARGET_PLATFORM_KEY)).thenReturn(target);
        when(result.getGroupId()).thenReturn("org.example");
        when(result.getArtifactId()).thenReturn("pde");
        when(result.getVersion()).thenReturn("1.0.0");
        when(result.getPackaging()).thenReturn("eclipse-plugin");
        return result;
    }

    private static IInstallableUnit unit(String id, String version) {
        InstallableUnitDescription desc = new InstallableUnitDescription();
        desc.setId(id);
        desc.setVersion(Version.parseVersion(version));
        return MetadataFactory.createInstallableUnit(desc);
    }
}
