val versionMajor = 1
val versionMinor = 5
val versionPatch = 9
var versionClassifier: String? = null
val isSnapshot = true
val minimumSdkVersion = 21


plugins {
    id("com.android.application")
}

android {
    namespace = "com.pandoroid"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.pandoroid"
        minSdk = 28
        targetSdk = 37
        versionCode = generateVersionCode()
        versionName = generateVersionName()
    }

    buildFeatures {
        buildConfig = true
    }

    packaging {
        resources.excludes += setOf(
            "META-INF/DEPENDENCIES",
            "META-INF/LICENSE",
            "META-INF/LICENSE.txt",
            "META-INF/license.txt",
            "META-INF/NOTICE",
            "META-INF/NOTICE.txt",
            "META-INF/notice.txt",
            "META-INF/ASL2.0"
        )
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    splits {
        abi {
            isEnable = true
            reset()
            include("x86", "x86_64", "armeabi-v7a", "arm64-v8a")
            isUniversalApk = true
        }
    }
}

dependencies {
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.legacy.support.v4)
    implementation(libs.androidx.core)

    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.session)

    implementation(libs.androidx.preference)
    implementation(libs.okhttp)

    implementation(platform(libs.kotlin.bom))
}

fun generateVersionCode(): Int {
    return minimumSdkVersion * 10000000 +
            versionMajor * 10000 +
            versionMinor * 100 +
            versionPatch
}

fun generateVersionName(): String {
    var classifier = versionClassifier
    if (classifier == null && isSnapshot) {
        classifier = "SNAPSHOT"
    }

    val base = "$versionMajor.$versionMinor.$versionPatch"
    return if (classifier != null) "$base-$classifier" else base
}
