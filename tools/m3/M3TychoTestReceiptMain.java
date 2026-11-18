/*
 * SPDX-FileCopyrightText: 2026 Contributors to the Synexia Project
 * SPDX-License-Identifier: EPL-2.0
 */
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Stream;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Element;

/**
 * JDK-only strict JUnit evidence gate for original Tycho final p2 owner tests.
 * Fails even if Maven says success when any required original case is skipped.
 */
public final class M3TychoTestReceiptMain {
    private static final String TEST = "M3TychoFinalTargetFactsTest";
    private static final Set<String> REQUIRED = Set.of(
            "originalFinalTargetOnlyWithoutPreliminaryOrNetworkResolution",
            "canonicalUnitDigestIsOrderIndependentButVersionSensitive",
            "rejectsDuplicateP2CoordinatesAndMalformedOwnerIdentity",
            "goldenOriginalTychoAndIndependentMavenPacketBytesMatch",
            "explicitExportIsIdempotentAndRefusesAConflictingGeneration");

    private M3TychoTestReceiptMain() { }

    public static void main(String[] args) throws Exception {
        if (args == null || args.length != 1) {
            throw new IllegalArgumentException("usage: M3TychoTestReceiptMain <Tycho junit dir>");
        }
        Path dir = Path.of(args[0]).toAbsolutePath().normalize();
        if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("missing original Tycho result directory");
        }
        Set<String> verified = new HashSet<>();
        int files = 0;
        try (Stream<Path> listing = Files.list(dir)) {
            for (Path xml : listing.filter(p -> p.getFileName().toString().endsWith(".xml"))
                    .sorted().limit(4097).toList()) {
                if (++files > 4096 || !Files.isRegularFile(xml, LinkOption.NOFOLLOW_LINKS)
                        || Files.size(xml) > 16L * 1024 * 1024) {
                    throw new IllegalArgumentException("unsafe Tycho JUnit report");
                }
                var factory = DocumentBuilderFactory.newInstance();
                factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
                factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
                factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
                factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
                factory.setXIncludeAware(false);
                factory.setExpandEntityReferences(false);
                factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
                factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
                var document = factory.newDocumentBuilder().parse(xml.toFile());
                var cases = document.getElementsByTagName("testcase");
                for (int i = 0; i < cases.getLength(); i++) {
                    Element test = (Element) cases.item(i);
                    String owner = test.getAttribute("classname");
                    if (!(owner.equals(TEST) || owner.endsWith("." + TEST))) continue;
                    String name = test.getAttribute("name");
                    if (!REQUIRED.contains(name) || !verified.add(name)) {
                        throw new IllegalArgumentException("unknown or duplicate final p2 test: " + name);
                    }
                    for (String prohibited : new String[]{"skipped", "failure", "error"}) {
                        if (test.getElementsByTagName(prohibited).getLength() > 0) {
                            throw new IllegalArgumentException("Tycho original test not accepted: "
                                    + name + " (" + prohibited + ")");
                        }
                    }
                }
            }
        }
        if (!verified.equals(REQUIRED)) {
            var missing = new HashSet<>(REQUIRED);
            missing.removeAll(verified);
            throw new IllegalArgumentException("incomplete Tycho final target acceptance: " + missing);
        }
        System.out.println("PASS: five original Tycho final p2 target evidence tests, zero failures/skips");
    }
}
