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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import org.eclipse.equinox.p2.metadata.IInstallableUnit;
import org.eclipse.tycho.ReactorProject;
import org.eclipse.tycho.TargetPlatform;

/**
 * Read-only original Tycho p2 <em>final</em> target-platform evidence.
 *
 * <p>Tycho owns p2 resolution and OSGi reactor wiring. This class does not
 * compute a preliminary/final platform, download p2 artifacts, read a .target
 * file, resolve bundles again, or change Maven/Gradle scheduling. Only a
 * {@link P2TargetPlatform} object already registered under the ORIGINAL final
 * target-platform context key is accepted.</p>
 *
 * <p>The sorted installable-unit coordinate fingerprint is useful for checking
 * that an observed p2 unit set changed, but does NOT authenticate artifact
 * contents, p2 requirements, plugin classpaths, target environments, qualifiers,
 * reactor compilation outputs or the full OSGi wiring. No cache admission or
 * goal skipping follows from this packet.</p>
 */
public final class M3TychoFinalTargetFacts {
    public static final String SCHEMA = "M3-TYCHO-FINAL-TARGET-FACTS-V1";
    public static final int MAX_UNITS = 262_144;
    private static final int MAX_ID = 4096;

    private M3TychoFinalTargetFacts() {}

    /** A detached p2 unit coordinate, never a mutable original IU reference. */
    public record UnitKey(String id, String version) {
        public UnitKey {
            id = valid(id, false);
            version = valid(version, false);
        }
    }

    public record Snapshot(String eclipseProject, String invocation,
            String eclipseSelected, String reactorGroup, String reactorArtifact,
            String reactorVersion, String reactorPackaging, String unitSha256,
            int unitCount, int reactorOriginCount, int mavenOriginCount) {
        public Snapshot {
            eclipseProject = valid(eclipseProject, false);
            invocation = valid(invocation, false);
            if (!UUID.fromString(invocation).toString().equals(invocation)) {
                throw new IllegalArgumentException("noncanonical Eclipse invocation");
            }
            eclipseSelected = valid(eclipseSelected, true);
            reactorGroup = valid(reactorGroup, false);
            reactorArtifact = valid(reactorArtifact, false);
            reactorVersion = valid(reactorVersion, false);
            reactorPackaging = valid(reactorPackaging, false);
            unitSha256 = sha(unitSha256);
            if (unitCount < 0 || unitCount > MAX_UNITS
                    || reactorOriginCount < 0 || reactorOriginCount > MAX_UNITS
                    || mavenOriginCount < 0 || mavenOriginCount > MAX_UNITS) {
                throw new IllegalArgumentException("Tycho original target evidence count exceeds budget");
            }
        }

        public byte[] toExchangeBytes() {
            String packet = SCHEMA + "\n"
                    + "provider\teclipse.tycho\n"
                    + "project64\t" + b64(eclipseProject) + "\n"
                    + "invocation\t" + invocation + "\n"
                    + "eclipseSelected64\t" + b64(eclipseSelected) + "\n"
                    + "reactorGroup64\t" + b64(reactorGroup) + "\n"
                    + "reactorArtifact64\t" + b64(reactorArtifact) + "\n"
                    + "reactorVersion64\t" + b64(reactorVersion) + "\n"
                    + "reactorPackaging64\t" + b64(reactorPackaging) + "\n"
                    + "unitSha256\t" + unitSha256 + "\n"
                    + "unitCount\t" + unitCount + "\n"
                    + "reactorOriginCount\t" + reactorOriginCount + "\n"
                    + "mavenOriginCount\t" + mavenOriginCount + "\n";
            return packet.getBytes(StandardCharsets.UTF_8);
        }

        public boolean authorizesBuildSkipping() { return false; }
    }

    /**
     * Capture only after Tycho has produced a final original p2 platform.
     *
     * <p>The already cached final object must be the same instance. An absent
     * final value, a distinct service wrapper or preliminary-only target is
     * refused conservatively; this method does not call Tycho resolution.</p>
     */
    public static Snapshot capture(ReactorProject project, P2TargetPlatform finalPlatform,
            String eclipseProject, String eclipseSelected, String invocation) {
        Objects.requireNonNull(project, "project");
        Objects.requireNonNull(finalPlatform, "finalPlatform");
        Object finalCached = project.getContextValue(TargetPlatform.FINAL_TARGET_PLATFORM_KEY);
        Object preliminary = project.getContextValue(TargetPlatform.PRELIMINARY_TARGET_PLATFORM_KEY);
        if (finalCached != finalPlatform || preliminary == finalPlatform) {
            throw new IllegalArgumentException("Tycho target platform is not an original final target");
        }
        Set<IInstallableUnit> units = Objects.requireNonNull(
                finalPlatform.getInstallableUnits(), "final p2 units");
        if (units.size() > MAX_UNITS) {
            throw new IllegalArgumentException("final Tycho p2 units exceed budget");
        }
        var reactorMap = Objects.requireNonNull(finalPlatform.getOriginalReactorProjectMap(),
                "Tycho p2 reactor origin map");
        var mavenMap = Objects.requireNonNull(finalPlatform.getOriginalMavenArtifactMap(),
                "Tycho p2 Maven origin map");
        List<UnitKey> detached = new ArrayList<>(units.size());
        for (IInstallableUnit unit : units) {
            Objects.requireNonNull(unit, "Tycho p2 installable unit");
            detached.add(new UnitKey(unit.getId(), unit.getVersion().toString()));
        }
        return fromResolvedKeys(eclipseProject, invocation, eclipseSelected,
                project.getGroupId(), project.getArtifactId(),
                project.getVersion(), project.getPackaging(),
                detached, reactorMap.size(), mavenMap.size());
    }

    /**
     * Detached original-unit canonicalization for Tycho's own unit tests.
     * The supplied unit list is not authenticated; only {@link #capture}
     * checks a real final target object from Tycho's project context.
     */
    public static Snapshot fromResolvedKeys(String eclipseProject, String invocation,
            String eclipseSelected, String reactorGroup, String reactorArtifact,
            String reactorVersion, String reactorPackaging, List<UnitKey> originalUnits,
            int reactorOrigins, int mavenOrigins) {
        Objects.requireNonNull(originalUnits, "originalUnits");
        if (originalUnits.size() > MAX_UNITS) {
            throw new IllegalArgumentException("Tycho unit metadata budget exceeded");
        }
        String[] keys = new String[originalUnits.size()];
        for (int i = 0; i < originalUnits.size(); i++) {
            UnitKey unit = Objects.requireNonNull(originalUnits.get(i), "original p2 unit");
            // UnitKey disallows controls, so this delimiter is unambiguous.
            keys[i] = unit.id() + '\u0000' + unit.version();
        }
        Arrays.sort(keys);
        MessageDigest md = digest();
        frame(md, "M3-TYCHO-FINAL-IU-COORDINATES-V1");
        number(md, keys.length);
        String prior = null;
        for (String key : keys) {
            if (key.equals(prior)) {
                throw new IllegalArgumentException("duplicate p2 installable-unit coordinate");
            }
            frame(md, key);
            prior = key;
        }
        return new Snapshot(eclipseProject, invocation, eclipseSelected,
                reactorGroup, reactorArtifact, reactorVersion, reactorPackaging,
                HexFormat.of().formatHex(md.digest()), keys.length,
                reactorOrigins, mavenOrigins);
    }

    private static String valid(String value, boolean empty) {
        Objects.requireNonNull(value, "Tycho original identity");
        if ((!empty && value.isBlank()) || value.length() > MAX_ID
                || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("invalid original Tycho identity");
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (++i >= value.length() || !Character.isLowSurrogate(value.charAt(i))) {
                    throw new IllegalArgumentException("invalid Tycho Unicode");
                }
            } else if (Character.isLowSurrogate(c)) {
                throw new IllegalArgumentException("invalid Tycho Unicode");
            }
        }
        return value;
    }

    private static String sha(String value) {
        if (value == null || !value.matches("[a-f0-9]{64}")) {
            throw new IllegalArgumentException("noncanonical Tycho SHA-256");
        }
        return value;
    }

    private static String b64(String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    private static void frame(MessageDigest md, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        number(md, bytes.length);
        md.update(bytes);
    }

    private static void number(MessageDigest md, int value) {
        md.update((byte) (value >>> 24));
        md.update((byte) (value >>> 16));
        md.update((byte) (value >>> 8));
        md.update((byte) value);
    }
}
