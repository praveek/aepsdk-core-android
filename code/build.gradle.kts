buildscript {
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
        mavenLocal()
    }

    dependencies {
        classpath("com.github.adobe:aepsdk-commons:e0479681c5a95b1b358cbfdc215e06b846364be5")
        classpath("org.jetbrains.kotlinx:binary-compatibility-validator:0.13.2")
        classpath("androidx.benchmark:benchmark-gradle-plugin:1.2.3")
    }
}


