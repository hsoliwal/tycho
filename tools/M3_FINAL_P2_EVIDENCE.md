# M3 universal build — original Tycho FINAL p2 evidence

The Tycho fork is the **native owner** of resolved p2 target platforms,
OSGi bundle resolution, target definitions, PDE packaging, execution
environments and Tycho reactor dependency state. Maven/Gradle must
not reimplement the Tycho resolver or guess effective dependencies by
parsing only a project \`*.target\` file.

## Source-owned implementation

\`org.eclipse.tycho.core.resolver.M3FinalP2BuildEvidence\` is an additive,
read-only API in Tycho core.

\`\`\`java
var facts = M3FinalP2BuildEvidence.captureExistingFinal(
    tychoReactorProject,
    eclipseProjectName,
    originalEclipseBuildInvocationUuid,
    selectedEclipseIBuildConfigurationName,
    selectedP2TargetOs,
    selectedP2TargetWindowSystem,
    selectedP2TargetArchitecture,
    progressMonitor);
\`\`\`

The capture reads **only**
\`reactorProject.getContextValue(TargetPlatform.FINAL_TARGET_PLATFORM_KEY)\`.
The returned object must implement the original
\`P2TargetPlatform\` interface. It does **not** invoke
\`TargetPlatformService.getTargetPlatform()\` or any resolver, since
those operations may compute a target and perform I/O. A
\`PRELIMINARY_TARGET_PLATFORM_KEY\` value never qualifies.

From the already-resolved platform it reads
\`getInstallableUnits()\`, which Tycho itself owns. A bounded list of
the original IU ID+version identities is sorted deterministically and
hashed with domain-separated SHA-256 using length-prefix framing.
It retains the actual Maven group/artifact/version/packaging, a
**separate** Eclipse \`.project\` name, selected original Eclipse
\`IBuildConfiguration\`, original build invocation UUID and explicit
target os/ws/arch.

The explicit os/ws/arch refer to the **target environment** whose
final platform was selected, not automatically to the host process.
An original Tycho caller must choose them appropriately. Merely
being in a Maven reactor is not evidence of a resolved final p2
platform, and Maven artifact ID is never assumed to equal the
Eclipse project name.

## Original Tycho packet

\`Snapshot.toExchangeBytes()\` emits canonical, bounded UTF-8/LF:

\`\`\`text
M3-TYCHO-FINAL-P2-FACTS-V1
provider	tycho.p2
group64	<base64url Maven group>
artifact64	<base64url Maven artifact ID>
version64	<base64url Maven version>
packaging64	<base64url Maven packaging>
eclipseProject64	<base64url original Eclipse project>
invocation	<canonical Eclipse UUID>
eclipseSelected64	<base64url Eclipse selected configuration>
os64	<base64url target operating system>
ws64	<base64url target window system>
arch64	<base64url target architecture>
stage	final
unitCount	<canonical integer>
unitsSha256	<64 lowercase hex digits>
\`\`\`

\`writeSnapshot(facts, projectRoot, destination)\` is **explicit**,
project-local, no symlink traversal, no overwrite of conflicting
bytes, idempotent only for identical data. Create \`target/ide\`
first and use an invocation-unique path when exporting different
build generations.

The original JUnit5 test checks actual final-versus-preliminary
target context, sorted IU identity, changed IU version invalidation,
duplicate refusal, cancellation, immutable export and cross-fork
fixture byte equality. Tycho core uses its normal Java 25/Maven 3.10
reactor and existing Mockito/JUnit dependencies.

## Maven and Gradle consumers

Maven's original Java 17 \`M3TychoFinalP2FactsWire\` is the one
independent decoder of these Tycho bytes. Invoke it explicitly:

\`\`\`sh
java -cp /approved/m3-polyglot-maven-plugin.jar \
  io.synexia.maven.polyglot.EclipseModelCli tycho-final-p2 \
  /project/target/ide/tycho-final-p2-facts.ide \
  /project/target/ide/build-handoff.ide
\`\`\`

The decoder rejects preliminary stage, unsupported schema,
malformed UTF-8/Base64URL/UUID, invalid unit identity, symlinks,
oversized evidence and wrong original project/configuration/
invocation. Gradle's optional \`m3EclipseTychoFinalP2Facts\` task
delegates to this exact pinned Maven CLI, not to a second p2 parser.

## Deliberate incompleteness

A list of resolved p2 IU IDs and versions does **not** prove
actual downloaded artifact bytes, bundle/fragment classpath wiring,
feature filters, full package requirements, execution-environment
profiles, p2 repository trust, generated outputs or a successful
compiler/build. Some target-definition project kinds have a final
\`TargetPlatform\` that is not a \`P2TargetPlatform\`; this evidence
path **refuses those**, rather than treating a preliminary target
as complete.

These facts are **advisory**. \`authorizesBuildSkipping()\` always
returns false. M3File/M3AST/CodeQL/M3DB and Maven/Gradle optimizations
must obtain separately verified full source, dependency, toolchain
and output generations before cache admission.

The native Tycho production build is unchanged. No automatic target
resolution, p2 download, Maven goal binding or Gradle task is added.
