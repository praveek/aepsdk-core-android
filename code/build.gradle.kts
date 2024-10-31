buildscript {
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
        mavenLocal()
    }

    dependencies {
        classpath("com.github.praveek:aepsdk-commons:66c498cc3d")
        classpath("org.jetbrains.kotlinx:binary-compatibility-validator:0.13.2")
        classpath("androidx.benchmark:benchmark-gradle-plugin:1.2.3")
    }
}


