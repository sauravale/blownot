buildscript {
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        classpath("com.android.tools.build:gradle:8.8.2")
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.1.10")
        classpath("org.jetbrains.kotlin:compose-compiler-gradle-plugin:2.1.10")
        classpath("com.google.dagger:hilt-android-gradle-plugin:2.55")
        classpath("com.google.devtools.ksp:symbol-processing-gradle-plugin:2.1.10-1.0.31")
    }
}
