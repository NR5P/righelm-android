// RigHelm's LAN protocol, client side, in plain Kotlin (no Android): the app
// uses it, the unit tests run it on the JVM, and `rhcheck` tries it against a
// real station from the command line.
plugins {
    id("org.jetbrains.kotlin.jvm")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}

// Command-line check against a real RigHelm server. Read-only: transmit is
// locked and no Set is sent.
//     ./gradlew :core:rhcheck --args="discover"
//     RIGHELM_PASSWORD=... ./gradlew :core:rhcheck --args="listen 192.168.10.223 10"
tasks.register<JavaExec>("rhcheck") {
    group = "verification"
    description = "Discover or listen to a RigHelm station (never transmits)."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.righelm.rhcheck.MainKt")
    standardInput = System.`in`
}
