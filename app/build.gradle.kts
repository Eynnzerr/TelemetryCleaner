import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

val releaseKeystorePath = providers.environmentVariable("ANDROID_KEYSTORE_PATH").orNull
val releaseKeystore = if (releaseKeystorePath != null) {
    Properties().apply {
        setProperty("storeFile", releaseKeystorePath)
        setProperty("storePassword", providers.environmentVariable("ANDROID_KEYSTORE_PASSWORD").getOrElse(""))
        setProperty("keyAlias", providers.environmentVariable("ANDROID_KEY_ALIAS").getOrElse(""))
        setProperty("keyPassword", providers.environmentVariable("ANDROID_KEY_PASSWORD").getOrElse(""))
    }
} else {
    providers.fileContents(rootProject.layout.projectDirectory.file("keystore.properties")).asText.orNull?.let { contents ->
        Properties().apply { contents.reader().use { load(it) } }
    }
}

/** Reads a required signing value without including its contents in build errors. */
fun Properties.signingValue(name: String): String =
    requireNotNull(getProperty(name)?.takeIf { it.isNotBlank() }) { "Missing release signing value: $name" }

android {
    namespace = "com.eynnzerr.telemetrycleaner"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.eynnzerr.telemetrycleaner"
        minSdk = 29
        targetSdk = 37
        versionCode = 2
        versionName = "1.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    val releaseSigning = releaseKeystore?.let { keystore ->
        signingConfigs.create("release") {
            storeFile = rootProject.file(keystore.signingValue("storeFile"))
            storePassword = keystore.signingValue("storePassword")
            keyAlias = keystore.signingValue("keyAlias")
            keyPassword = keystore.signingValue("keyPassword")
        }
    }

    buildTypes {
        release {
            signingConfig = releaseSigning
            optimization {
                enable = true
                packageScope = setOf("androidx.**", "kotlin.**", "kotlinx.**")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        buildConfig = true
        compose = true
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.animation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.navigation3.runtime)
    implementation(libs.androidx.navigation3.ui)
    implementation(libs.koin.android)
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
