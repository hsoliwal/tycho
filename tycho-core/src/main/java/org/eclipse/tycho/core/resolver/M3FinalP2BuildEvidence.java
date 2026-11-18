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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collection;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.OperationCanceledException;
import org.eclipse.equinox.p2.metadata.IInstallableUnit;
import org.eclipse.tycho.ReactorProject;
import org.eclipse.tycho.TargetPlatform;
import org.eclipse.tycho.targetplatform.P2TargetPlatform;

/**
 * Original Tycho-owner evidence for an already resolved, FINAL p2 target
 * platform. The preliminary dependency-only platform is never accepted.
 *
 * <p>This class does not compute a target platform, fetch an artifact,
 * invoke the p2 director or modify Maven dependencies. It reads only the
 * final platform retained by the original Tycho resolver in the project's
 * context. The observed IUs and selected environment are metadata, not a
 * complete cache key or proof that artifacts are locally available.</p>
 */
public final class M3FinalP2BuildEvidence {
    public static final String SCHEMA = "M3-TYCHO-FINAL-P2-FACTS-V1";
    public static final int MAX_UNITS = 262_144;
    private static final int MAX_FIELD = 4096;

    private M3FinalP2BuildEvidence() { }

    public record Snapshot(String groupId, String artifactId, String version,
            String packaging, String invocation, String eclipseSelected,
            String os, String ws, String arch, int unitCount, String unitsSha256) {
        public Snapshot {
            groupId = valid(groupId, false);
            artifactId = valid(artifactId, false);
            version = valid(version, false);
            packaging = valid(packaging, false);
            eclipseSelected = valid(eclipseSelected, true);
            os = valid(os, false);
            ws = valid(ws, false);
            arch = valid(arch, false);
            Objects.requireNonNull(invocation, "Eclipse invocation UUID");
            if (!UUID.fromString(invocation).toString().equals(invocation)) {
                throw new IllegalArgumentException("noncanonical Eclipse invocation UUID");
            }
            if (unitCount < 0 || unitCount > MAX_UNITS
                    || unitsSha256 == null || !unitsSha256.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("invalid final p2 unit identity");
            }
        }

        /** Deliberately incomplete p2 evidence; never cache admission. */
        public boolean authorizesBuildSkipping() { return false; }

        /** Canonical UTF-8/LF packet for Maven's independent Java 17 reader. */
        public byte[] toExchangeBytes() {
            String packet = SCHEMA + "\n"
                    + "provider\ttycho.p2\n"
                    + "group64\t" + b64(groupId) + "\n"
                    + "artifact64\t" + b64(artifactId) + "\n"
                    + "version64\t" + b64(version) + "\n"
                    + "packaging64\t" + b64(packaging) + "\n"
                    + "invocation\t" + invocation + "\n"
                    + "eclipseSelected64\t" + b64(eclipseSelected) + "\n"
                    + "os64\t" + b64(os) + "\n"
                    + "ws64\t" + b64(ws) + "\n"
                    + "arch64\t" + b64(arch) + "\n"
                    + "stage\tfinal\n"
                    + "unitCount\t" + unitCount + "\n"
                    + "unitsSha256\t" + unitsSha256 + "\n";
            return packet.getBytes(StandardCharsets.UTF_8);
        }
    }

    /**
     * Never call getTargetPlatform() here: that service may resolve a target
     * and/or produce network activity. A final p2 target must already exist.
     *
     * <p>The environment is explicit because p2 resolution may target a
     * platform other than the current host OS/architecture.</p>
     */
    public static Snapshot captureExistingFinal(ReactorProject project,
            String invocation, String eclipseSelected, String os, String ws,
            String arch, IProgressMonitor monitor) {
        Objects.requireNonNull(project, "Tycho source-owner reactor project");
        cancelled(monitor);
        Object finalValue = project.getContextValue(TargetPlatform.FINAL_TARGET_PLATFORM_KEY);
        if (!(finalValue instanceof P2TargetPlatform platform)) {
            throw new IllegalArgumentException(
                    "a resolved FINAL p2 target platform is required; preliminary is insufficient");
        }
        return captureFromFinal(project.getGroupId(), project.getArtifactId(),
                project.getVersion(), project.getPackaging(), invocation,
                eclipseSelected, os, ws, arch, platform.getInstallableUnits(), monitor);
    }

    /**
     * Factored original-IU reader for deterministic Tycho tests; callers are
     * responsible for ensuring units came from an already-final p2 target.
     * Does not materialize p2 artifacts or ask Tycho to fetch anything.
     */
    static Snapshot captureFromFinal(String groupId, String artifactId,
            String version, String packaging, String invocation,
            String eclipseSelected, String os, String ws, String arch,
            Collection<IInstallableUnit> units, IProgressMonitor monitor) {
        Objects.requireNonNull(units, "final p2 installable units");
        if (units.size() > MAX_UNITS) {
            throw new IllegalArgumentException("final p2 unit count exceeds bound");
        }
        cancelled(monitor);
        List<String> identities = new ArrayList<>(units.size());
        for (IInstallableUnit iu : units) {
            cancelled(monitor);
            Objects.requireNonNull(iu, "final p2 installable unit");
            String id = valid(iu.getId(), false);
            String iuVersion = valid(
                    Objects.requireNonNull(iu.getVersion(), "final p2 unit version").toString(), false);
            identities.add(id + "\u0000" + iuVersion);
        }
        identities.sort(String::compareTo);
        MessageDigest sha = digest();
        frame(sha, "M3-TYCHO-FINAL-IUS-V1");
        number(sha, identities.size());
        String preceding = null;
        for (String identity : identities) {
            cancelled(monitor);
            if (identity.equals(preceding)) {
                throw new IllegalArgumentException("duplicate final p2 installable unit identity");
            }
            frame(sha, identity);
            preceding = identity;
        }
        return new Snapshot(groupId, artifactId, version, packaging,
                invocation, eclipseSelected, os, ws, arch, identities.size(),
                HexFormat.of().formatHex(sha.digest()));
    }

    /**
     * Explicit export. A Tycho project may choose a new generation-scoped path
     * after successful resolution. Existing nonidentical bytes are untouched.
     */
    public static Path writeSnapshot(Snapshot snapshot, Path projectRoot,
            Path destination) throws IOException {
        Objects.requireNonNull(snapshot, "snapshot");
        Path root = Objects.requireNonNull(projectRoot, "projectRoot").toRealPath();
        Path request = Objects.requireNonNull(destination, "destination");
        Path file = (request.isAbsolute() ? request : root.resolve(request)).normalize();
        if (!file.startsWith(root) || root.equals(file)) {
            throw new IOException("final p2 receipt must remain inside the original project");
        }
        for (Path cursor = file; cursor != null && cursor.startsWith(root);
                cursor = cursor.getParent()) {
            if (Files.isSymbolicLink(cursor)) {
                throw new IOException("final p2 receipt destination cannot contain a symlink");
            }
        }
        if (!Files.isDirectory(file.getParent(), LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("final p2 receipt parent directory must already exist");
        }
        byte[] expected = snapshot.toExchangeBytes();
        if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
                    || Files.size(file) != expected.length
                    || !Arrays.equals(Files.readAllBytes(file), expected)) {
                throw new IOException("refusing to overwrite a different final p2 generation");
            }
            return file;
        }
        Files.write(file, expected, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE,
                LinkOption.NOFOLLOW_LINKS);
        return file;
    }

    private static String valid(String text, boolean allowEmpty) {
        Objects.requireNonNull(text, "Tycho identity");
        if ((!allowEmpty && text.isBlank()) || text.length() > MAX_FIELD
                || text.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("invalid original Tycho metadata identity");
        }
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (Character.isHighSurrogate(ch)) {
                if (++i >= text.length() || !Character.isLowSurrogate(text.charAt(i))) {
                    throw new IllegalArgumentException("invalid original Tycho Unicode");
                }
            } else if (Character.isLowSurrogate(ch)) {
                throw new IllegalArgumentException("invalid original Tycho Unicode");
            }
        }
        return text;
    }

    private static String b64(String text) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(text.getBytes(StandardCharsets.UTF_8));
    }

    private static void frame(MessageDigest md, String text) {
        byte[] value = text.getBytes(StandardCharsets.UTF_8);
        number(md, value.length);
        md.update(value);
    }

    private static void number(MessageDigest md, int value) {
        md.update((byte)(value >>> 24));
        md.update((byte)(value >>> 16));
        md.update((byte)(value >>> 8));
        md.update((byte)value);
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 missing", impossible);
        }
    }

    private static void cancelled(IProgressMonitor monitor) {
        if (monitor != null && monitor.isCanceled()) {
            throw new OperationCanceledException();
        }
    }
}
