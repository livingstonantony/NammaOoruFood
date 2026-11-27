import com.varabyte.kobweb.gradle.application.util.configAsKobwebApplication
import kotlinx.html.link
import kotlinx.html.script

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.kobweb.application)
}

group = "dev.livin.nammaoorufood.miniapp"
version = "1.0"

kobweb {
    app {
        index {
            description.set("Order for pickup from local hotels")
            head.add {
                script(src = "https://telegram.org/js/telegram-web-app.js") {}
                link(href = "/styles.css", rel = "stylesheet")
            }
        }
    }
}

kotlin {
    configAsKobwebApplication("telegramMiniApp")
    sourceSets {
        jsMain.dependencies {
            implementation(libs.compose.runtime)
            implementation(libs.compose.html.core)
            implementation(libs.kobweb.core)
            implementation(libs.kotlinx.serializationJson)
            implementation(libs.kotlinx.coroutinesCore)
        }
        jsTest.dependencies {
            implementation(libs.kotlin.test)
        }
    }
}
