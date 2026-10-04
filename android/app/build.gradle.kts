import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Where the daily job list lives (set by -PdataUrl=... or JOB_RADAR_DATA_URL)
val dataUrl: String = (project.findProperty("dataUrl") as String?)
    ?: System.getenv("JOB_RADAR_DATA_URL")
    ?: "https://raw.githubusercontent.com/OWNER/job-radar/main/data/jobs.json"

// Signing key is kept outside the repo
val keyProps = Properties().apply {
    val f = File(System.getProperty("user.home"), "job-radar-keys/key.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.jobradar.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.jobradar.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 2
        versionName = "1.1"
        buildConfigField("String", "DATA_URL", "\"$dataUrl\"")
        // plain http only for local testing builds (-PtestCleartext=true)
        manifestPlaceholders["cleartext"] = (project.findProperty("testCleartext") ?: "false").toString()
    }

    signingConfigs {
        if (keyProps.isNotEmpty()) create("release") {
            storeFile = file(keyProps.getProperty("storeFile"))
            storePassword = keyProps.getProperty("storePassword")
            keyAlias = keyProps.getProperty("keyAlias")
            keyPassword = keyProps.getProperty("keyPassword")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    val bom = platform("androidx.compose:compose-bom:2025.06.00")
    implementation(bom)
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.1")
}
