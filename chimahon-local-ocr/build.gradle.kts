plugins {
    id("mihon.library")
    kotlin("android")
}

val supportedAbis = listOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
val targetAbis = providers.gradleProperty("targetAbis").orNull?.let { value ->
    value.split(',').map { it.trim() }.filter { it.isNotEmpty() }.also { requested ->
        require(requested.isNotEmpty() && requested.all { it in supportedAbis }) {
            "targetAbis must contain one or more of: ${supportedAbis.joinToString()}"
        }
    }
} ?: supportedAbis

android {
    namespace = "chimahon.local.ocr"

    defaultConfig {
        ndk {
            abiFilters += targetAbis
        }
        externalNativeBuild {
            cmake {
                cppFlags("-std=c++17")
            }
        }
    }

    externalNativeBuild {
        cmake {
            path("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
}

dependencies {
    implementation(project(":chimahon"))
    implementation(project(":presentation-core"))
    implementation(project(":domain"))

    implementation(platform(kotlinx.coroutines.bom))
    implementation(kotlinx.coroutines.core)

    implementation(compose.foundation)
    implementation(compose.material3.core)
}
