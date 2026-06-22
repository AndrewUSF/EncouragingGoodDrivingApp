plugins {
  id("com.android.application")
  id("org.jetbrains.kotlin.android")
}

fun readGradleOrEnv(name: String): String? =
  providers
    .gradleProperty(name)
    .orElse(providers.environmentVariable(name))
    .orNull
    ?.trim()
    ?.takeIf { it.isNotEmpty() }

val resolvedVersionCode = readGradleOrEnv("STEADYDRIVE_VERSION_CODE")?.toIntOrNull() ?: 3
val resolvedVersionName = readGradleOrEnv("STEADYDRIVE_VERSION_NAME") ?: "0.1.2"
val releaseStoreFilePath = readGradleOrEnv("STEADYDRIVE_RELEASE_STORE_FILE")
val releaseStorePassword = readGradleOrEnv("STEADYDRIVE_RELEASE_STORE_PASSWORD")
val releaseKeyAlias = readGradleOrEnv("STEADYDRIVE_RELEASE_KEY_ALIAS")
val releaseKeyPassword = readGradleOrEnv("STEADYDRIVE_RELEASE_KEY_PASSWORD")

android {
  namespace = "edu.usf.steadydrive"
  compileSdk = 35

  defaultConfig {
    applicationId = "edu.usf.steadydrive"
    minSdk = 28
    targetSdk = 35
    versionCode = resolvedVersionCode
    versionName = resolvedVersionName

    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

    val portalBaseUrl = providers.gradleProperty("PORTAL_BASE_URL").get()
    buildConfigField("String", "PORTAL_BASE_URL", "\"$portalBaseUrl\"")
  }

  signingConfigs {
    if (
      releaseStoreFilePath != null &&
      releaseStorePassword != null &&
      releaseKeyAlias != null &&
      releaseKeyPassword != null
    ) {
      create("release") {
        storeFile = file(releaseStoreFilePath)
        storePassword = releaseStorePassword
        keyAlias = releaseKeyAlias
        keyPassword = releaseKeyPassword
      }
    }
  }

  buildTypes {
    release {
      isMinifyEnabled = false
      proguardFiles(
        getDefaultProguardFile("proguard-android-optimize.txt"),
        "proguard-rules.pro",
      )
      signingConfigs.findByName("release")?.let { signingConfig = it }
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
    compose = true
    buildConfig = true
  }

  composeOptions {
    kotlinCompilerExtensionVersion = "1.5.14"
  }

  packaging {
    resources {
      excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
  }
}

dependencies {
  implementation("androidx.core:core-ktx:1.13.1")
  implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
  implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.4")
  implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.4")
  implementation("androidx.activity:activity-compose:1.9.2")

  implementation(platform("androidx.compose:compose-bom:2024.06.00"))
  implementation("androidx.compose.ui:ui")
  implementation("androidx.compose.ui:ui-tooling-preview")
  implementation("androidx.compose.material3:material3")
  implementation("androidx.compose.material:material-icons-extended")
  implementation("com.google.android.material:material:1.12.0")

  implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
  implementation("com.google.android.gms:play-services-location:21.3.0")
  implementation("androidx.work:work-runtime-ktx:2.9.1")

  debugImplementation("androidx.compose.ui:ui-tooling")
}
