plugins {
    id("com.android.application") version "8.5.0" apply false
    id("org.jetbrains.kotlin.android") version "1.9.24" apply false
}

allprojects {
    repositories {
        google()
        mavenCentral()
        maven { url 'https://chaquo.com/maven' }   // ← Add this line
    }
}
