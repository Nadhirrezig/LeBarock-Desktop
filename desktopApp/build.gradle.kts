import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

dependencies {
    implementation(project(":shared"))

    implementation(compose.desktop.currentOs)
    implementation(libs.kotlinx.coroutinesSwing)

    implementation(libs.compose.uiToolingPreview)
}

// One version for the window, the "Réglages → Application" line and the MSI.
// MSI versions are MAJOR.MINOR.BUILD (each ≤ 255 / 255 / 65535): raise it for every release.
val appVersion = providers.gradleProperty("altospos.version").getOrElse("1.0.0")
val isWindows = System.getProperty("os.name").lowercase().contains("windows")

compose.desktop {
    application {
        mainClass = "com.lebarock.lebarock_desktop.MainKt"
        jvmArgs += listOf("-Daltospos.version=$appVersion", "-Dfile.encoding=UTF-8", "-Xmx768m")

        nativeDistributions {
            targetFormats(TargetFormat.Msi)
            packageName = "AltosPOS"
            packageVersion = appVersion
            description = "AltosPOS — caisse du comptoir"
            vendor = "AltosPOS"
            copyright = "AltosPOS"

            // TLS with ECDHE needs jdk.crypto.ec on Java 21; the Windows certificate store
            // (mkcert's root CA installed in Windows) is read through jdk.crypto.mscapi,
            // which only exists in a Windows JDK.
            modules("java.naming", "jdk.crypto.ec", "jdk.unsupported")
            if (isWindows) modules("jdk.crypto.mscapi")

            windows {
                // Never change it: it lets a new MSI replace the installed version.
                upgradeUuid = "bbe5b3d0-39c1-4ffc-91e9-4515a856edb1"
                menuGroup = "AltosPOS"
                menu = true
                shortcut = true
                dirChooser = true
                iconFile.set(project.file("icons/altospos.ico"))
            }
            linux {
                iconFile.set(project.file("icons/altospos.png"))
            }
        }

        // Retrofit and kotlinx.serialization need keep rules under ProGuard; the gain is small for a LAN app.
        buildTypes.release.proguard {
            isEnabled.set(false)
        }
    }
}
