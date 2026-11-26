import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.kotlinSerialization)
}

val imageKitProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}
val imageKitPublicKey = imageKitProperties.getProperty("IMAGEKIT_PUBLIC_KEY", "")
val imageKitUrlEndpoint = imageKitProperties.getProperty("IMAGEKIT_URL_ENDPOINT", "")
val imageKitConfigDir = layout.buildDirectory.dir("generated/imagekit/commonMain")
val generateImageKitConfig = tasks.register("generateImageKitConfig") {
    inputs.property("publicKey", imageKitPublicKey)
    inputs.property("urlEndpoint", imageKitUrlEndpoint)
    outputs.dir(imageKitConfigDir)
    doLast {
        fun kotlinString(value: String) = buildString {
            append('"')
            value.forEach {
                when (it) {
                    '\\' -> append("\\\\")
                    '"' -> append("\\\"")
                    '$' -> append("\\$")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> append(it)
                }
            }
            append('"')
        }
        val publicKey = inputs.properties["publicKey"] as String
        val urlEndpoint = inputs.properties["urlEndpoint"] as String
        val output = outputs.files.singleFile.resolve("dev/livin/nammaoorufood/owner/data/ImageKitClientConfig.kt")
        output.parentFile.mkdirs()
        output.writeText(
            "package dev.livin.nammaoorufood.owner.data\n\n" +
                "internal object ImageKitClientConfig {\n" +
                "    const val publicKey = ${kotlinString(publicKey)}\n" +
                "    const val urlEndpoint = ${kotlinString(urlEndpoint)}\n" +
                "}\n"
        )
    }
}

kotlin {
    listOf(
        iosArm64(),
        iosSimulatorArm64()
    ).forEach { iosTarget ->
        iosTarget.binaries.framework {
            baseName = "Shared"
            isStatic = true
        }
    }
    
    jvm()
    
    js {
        browser()
        binaries.executable()
    }
    
    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        browser()
        binaries.executable()
    }
    
    android {
       namespace = "dev.livin.nammaoorufood.app.shared"
       compileSdk = libs.versions.android.compileSdk.get().toInt()
       minSdk = libs.versions.android.minSdk.get().toInt()
    
       compilerOptions {
           jvmTarget = JvmTarget.JVM_11
       }
       androidResources {
           enable = true
       }
       withHostTest {
           isIncludeAndroidResources = true
       }
       withDeviceTestBuilder {
           sourceSetTreeName = "test"
       }.configure {
           instrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
       }
    }
    
    sourceSets {
        commonMain.get().kotlin.srcDir(generateImageKitConfig)
        androidMain.dependencies {
            implementation(libs.ktor.clientOkhttp)
            implementation(libs.compose.uiToolingPreview)
            implementation(libs.compose.uiTooling)
        }
        val nonWebMain = create("nonWebMain") {
            dependsOn(commonMain.get())
            dependencies {
                implementation(libs.androidx.datastore.preferences)
            }
        }
        androidMain.get().dependsOn(nonWebMain)
        jvmMain.get().dependsOn(nonWebMain)
        iosMain.get().dependsOn(nonWebMain)

        val webMain = create("webMain") {
            dependsOn(commonMain.get())
            dependencies {
                implementation(libs.ktor.clientJs)
            }
        }
        jsMain.get().dependsOn(webMain)
        wasmJsMain.get().dependsOn(webMain)
        iosArm64Main.get().dependsOn(iosMain.get())
        iosSimulatorArm64Main.get().dependsOn(iosMain.get())

        commonMain.dependencies {
            api(project(":core"))
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.material3)
            implementation(compose.materialIconsExtended)
            implementation(libs.compose.ui)
            implementation(libs.compose.navigationevent)
            implementation(libs.compose.components.resources)
            implementation(libs.compose.uiToolingPreview)
            implementation(libs.androidx.lifecycle.viewmodelCompose)
            implementation(libs.androidx.lifecycle.runtimeCompose)
            implementation(libs.kotlinx.serializationJson)
            implementation(libs.kotlinx.coroutinesCore)
            implementation(libs.ktor.clientCore)
            implementation(libs.ktor.clientAuth)
            implementation(libs.ktor.clientWebsockets)
            implementation(libs.ktor.clientContentNegotiation)
            implementation(libs.ktor.serializationKotlinxJson)
            implementation(libs.koin.core)
            implementation(libs.koin.compose)
            implementation(libs.koin.composeViewModel)
            implementation(libs.filekit.compose)
            implementation(libs.coil.compose)
            implementation(libs.coil.network.ktor)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutinesTest)
            implementation(libs.ktor.clientMock)
        }
        jvmMain.dependencies {
            implementation(libs.ktor.clientCio)
        }
        jvmTest.dependencies {
            runtimeOnly(compose.desktop.currentOs)
        }
        iosMain.dependencies {
            implementation(libs.ktor.clientDarwin)
        }
        jsMain.dependencies {
            implementation(libs.wrappers.browser)
            implementation(libs.ktor.clientJs)
        }
        wasmJsMain.dependencies {
            implementation(libs.ktor.clientJs)
        }
    }
}

dependencies {
    androidRuntimeClasspath(libs.compose.uiTooling)
}