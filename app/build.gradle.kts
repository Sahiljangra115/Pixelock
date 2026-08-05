import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

val localProperties = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}

/** Public client config only. Server secrets live in Supabase and never reach the APK. */
fun clientConfig(name: String): String =
    "\"${localProperties.getProperty(name) ?: System.getenv(name) ?: ""}\""

android {
    namespace = "dev.pixelock.app"
    compileSdk = 36

    lint {
        abortOnError = true
    }

    defaultConfig {
        applicationId = "dev.pixelock.app"
        minSdk = 33
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        resValue("string", "app_name", "Pixelock")
        buildConfigField("String", "SUPABASE_URL", clientConfig("SUPABASE_URL"))
        buildConfigField("String", "SUPABASE_ANON_KEY", clientConfig("SUPABASE_ANON_KEY"))
    }

    sourceSets {
        getByName("androidTest").assets.srcDir("$projectDir/schemas")
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // ponytail: sideload builds, signed with the local debug key until a release key exists
            signingConfig = signingConfigs.getByName("debug")
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            resValue("string", "app_name", "Pixelock Debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.lifecycle.runtime.ktx)
    implementation(libs.androidx.fragment.ktx)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.recyclerview)
    implementation(libs.androidx.datastore)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.gson)
    implementation(libs.zxing.core)
    implementation(libs.play.code.scanner)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.room.testing)
}
