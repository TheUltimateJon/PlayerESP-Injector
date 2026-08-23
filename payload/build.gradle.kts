plugins {
    java
    id("org.polyfrost.loom") version "1.5.polyfrost.2"
    id("dev.architectury.architectury-pack200") version "0.1.3"
}

group = "playeresp.inject"
version = "1.0.0"
java { toolchain.languageVersion.set(JavaLanguageVersion.of(8)) }

loom {
    forge { }
    runs { remove(getByName("server")) }
}

repositories { mavenCentral() }
dependencies {
    minecraft("com.mojang:minecraft:1.8.9")
    mappings("de.oceanlabs.mcp:mcp_stable:22-1.8.9")
    forge("net.minecraftforge:forge:1.8.9-11.15.1.2318-1.8.9")
}

tasks.withType<JavaCompile>().configureEach { options.encoding = "UTF-8" }
val namedPayload = tasks.register<Jar>("namedJar") {
    dependsOn(tasks.classes)
    archiveFileName.set("playeresp_payload-named.jar")
    destinationDirectory.set(layout.buildDirectory.dir("libs"))
    from(sourceSets.main.get().output)
}
tasks.register<net.fabricmc.loom.task.RemapJarTask>("srgJar") {
    dependsOn(namedPayload)
    inputFile.set(namedPayload.flatMap { it.archiveFile })
    sourceNamespace.set("named")
    targetNamespace.set("srg")
    archiveFileName.set("playeresp_payload.jar")
    destinationDirectory.set(layout.buildDirectory.dir("libs"))
}
tasks.named<net.fabricmc.loom.task.RemapJarTask>("remapJar") {
    archiveFileName.set("playeresp_payload-runtime.jar")
    targetNamespace.set("official")
}
tasks.register("payloadJars") {
    dependsOn(tasks.named("srgJar"), tasks.named("remapJar"), tasks.named("namedJar"))
}
