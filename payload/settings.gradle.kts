pluginManagement {
    repositories {
        mavenCentral(); gradlePluginPortal()
        maven("https://oss.sonatype.org/content/repositories/snapshots/")
        maven("https://maven.architectury.dev/")
        maven("https://maven.fabricmc.net")
        maven("https://maven.minecraftforge.net/")
        maven("https://repo.spongepowered.org/maven/")
        maven("https://repo.polyfrost.cc/releases/")
    }
    resolutionStrategy.eachPlugin {
        if (requested.id.id == "org.polyfrost.loom") useModule("org.polyfrost:architectury-loom:${requested.version}")
    }
}
rootProject.name = "playeresp-payload"
