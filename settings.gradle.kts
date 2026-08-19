rootProject.name = "sizzle"

// Composite build: Sizzle is engine 14 of the ecosystem — the chaos engine. It injects faults
// at the write seam Twine named (its PutSink/DeleteSink), so Twine is the direct include;
// Twine transitively includes SmokeHouse → SuperBeefSort → CSRBT. Gradle substitutes every
// published coordinate with the live sibling sources.
includeBuild("../Twine")

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}
