plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.kotlinSerialization)
}

// The app's code lives in jvmMain: Retrofit, OkHttp and the Windows printing are JVM libraries.
// commonMain only carries the Compose dependencies the wizard set up.
kotlin {
    jvm()
    sourceSets {
        commonMain.dependencies {
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.material3)
            implementation(libs.compose.ui)
            implementation(libs.compose.components.resources)
            implementation(libs.compose.uiToolingPreview)
            implementation(libs.androidx.lifecycle.viewmodelCompose)
            implementation(libs.androidx.lifecycle.runtimeCompose)
        }
        jvmMain.dependencies {
            implementation(libs.compose.materialIconsExtended)
            implementation(libs.kotlinx.coroutinesSwing)
            implementation(libs.kotlinx.serializationJson)
            implementation(libs.retrofit)
            implementation(libs.retrofit.kotlinxSerialization)
            implementation(libs.okhttp)
            implementation(libs.okhttp.logging)
            implementation(libs.jna.platform)
            implementation(libs.sqlite.jdbc)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
        jvmTest.dependencies {
            implementation(libs.kotlinx.coroutinesTest)
            implementation(libs.okhttp.mockwebserver)
            implementation(libs.okhttp.tls)
        }
    }
}
