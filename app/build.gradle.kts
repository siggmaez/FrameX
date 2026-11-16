plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "app.framex"
    compileSdk = 35
    defaultConfig {
        applicationId = "app.framex"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }
    signingConfigs {
        // Fixed, non-secret debug key so every build can be installed over the previous one.
        getByName("debug") {
            storeFile = file("framex-debug.p12")
            storeType = "pkcs12"
            storePassword = "framexdebug"
            keyAlias = "framex"
            keyPassword = "framexdebug"
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation(project(":core:common"))
    implementation(project(":core:designsystem"))
    implementation(project(":core:datastore"))
    implementation(project(":core:database"))
    implementation(project(":core:optimization"))
    implementation(project(":core:shizuku"))
    implementation(project(":feature:optimize"))
    implementation(project(":feature:games"))
    implementation(project(":feature:benchmark"))
    implementation(project(":feature:home"))
    implementation(project(":feature:monitor"))
    implementation(project(":feature:settings"))
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.hilt.android)
    implementation(libs.hilt.navigation.compose)
    ksp(libs.hilt.compiler)
}
