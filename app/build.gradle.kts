plugins { alias(libs.plugins.agp); alias(libs.plugins.kotlin); alias(libs.plugins.compose); alias(libs.plugins.ksp); alias(libs.plugins.hilt); alias(libs.plugins.serialization) }
android {
    namespace = "com.splitsmart"; compileSdk = libs.versions.compileSdk.get().toInt()
    defaultConfig { applicationId = "com.splitsmart"; minSdk = libs.versions.minSdk.get().toInt(); targetSdk = libs.versions.targetSdk.get().toInt(); versionCode = 1; versionName = "1.0.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner" }
    val releaseStoreFile = project.findProperty("SPLITSMART_STORE_FILE") as String?
    signingConfigs {
        if (releaseStoreFile != null) {
            create("release") {
                storeFile = file(releaseStoreFile)
                storePassword = project.findProperty("SPLITSMART_STORE_PASSWORD") as String?
                keyAlias = project.findProperty("SPLITSMART_KEY_ALIAS") as String?
                keyPassword = project.findProperty("SPLITSMART_KEY_PASSWORD") as String?
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (releaseStoreFile != null) signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    lint { disable += "NullSafeMutableLiveData" }
    testOptions { unitTests { isIncludeAndroidResources = true } }
}
ksp { arg("room.schemaLocation", "$projectDir/schemas") }
dependencies {
    implementation(libs.core.ktx); implementation(libs.lifecycle.runtime); implementation(libs.lifecycle.viewmodel)
    implementation(platform(libs.compose.bom)); implementation(libs.compose.ui); implementation(libs.compose.material3)
    implementation(libs.compose.icons); implementation(libs.window.size); implementation(libs.navigation.compose)
    implementation(libs.hilt); ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation)
    implementation(libs.room.runtime); implementation(libs.room.ktx); ksp(libs.room.compiler)
    implementation(libs.datastore); implementation(libs.serialization); implementation(libs.coroutines); implementation(libs.work.runtime)
    implementation(libs.glance.appwidget); implementation(libs.glance.material3); implementation(libs.coil.compose)
    testImplementation(libs.junit5); testImplementation(libs.junit.vintage); testImplementation(libs.coroutines.test); testImplementation(libs.turbine)
    testImplementation(libs.truth); testImplementation(libs.robolectric); testImplementation(libs.room.testing)
    testImplementation(libs.test.core)
}
tasks.withType<Test> { useJUnitPlatform() }
