// Standalone JVM build that compiles and tests the Android-free code in app/.../core.
// Run: gradle -p verify-core test
rootProject.name = "verify-core"
dependencyResolutionManagement { repositories { mavenCentral() } }
