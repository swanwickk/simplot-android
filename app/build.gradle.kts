plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// 发布签名：优先读根目录 keystore.properties（gitignored，不入库）。
// 文件缺失时 release 构建退化为未签名 APK（assembleRelease 仍可产出，仅不可安装）。
// 生成方式见 README「构建」章节。
import java.util.Properties
import java.io.FileInputStream

val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) FileInputStream(f).use { load(it) }
}
val hasReleaseSigning = keystoreProps.containsKey("storeFile")

android {
    namespace = "com.simplot.android"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.simplot.android"
        minSdk = 26
        targetSdk = 35
        versionCode = 37
        versionName = "0.7.6"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
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
    }
    testOptions {
        unitTests {
            // Robolectric 需要合并 Android 资源（ui-test-manifest 的 Activity 声明等）
            isIncludeAndroidResources = true
        }
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.gson)
    debugImplementation(libs.androidx.ui.tooling)
    // Robolectric + Compose UI 测试：无模拟器环境下在 JVM 上跑 UI 测试。
    // ComponentActivity 宿主已声明在 src/main/AndroidManifest.xml（debug/release 通用；
    // ui-test-manifest 是 debugImplementation AAR，release 变体拿不到声明，故不用它）。
    testImplementation("junit:junit:4.13.2")
    testImplementation(libs.robolectric)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
}
