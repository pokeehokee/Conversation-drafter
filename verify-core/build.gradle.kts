plugins { kotlin("jvm") version "2.4.21" }
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
java { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
sourceSets {
    main { kotlin.srcDir("../app/src/main/kotlin/com/devin/replydrafter/core") }
    test { kotlin.srcDir("../app/src/test/kotlin/com/devin/replydrafter/core") }
}
dependencies { testImplementation("junit:junit:4.13.2") }
