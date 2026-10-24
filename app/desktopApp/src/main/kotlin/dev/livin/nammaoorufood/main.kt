package dev.livin.nammaoorufood

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application

/** Pick the product with a program argument or NAMMA_APP_VARIANT (customer | owner | admin); defaults to admin. */
fun main(args: Array<String>) {
    val variant = AppVariant.parse(args.firstOrNull() ?: System.getenv("NAMMA_APP_VARIANT")) ?: AppVariant.ADMIN
    // Separate session storage per product so they can be signed in side by side.
    System.setProperty("namma.variant", variant.name.lowercase())
    application {
        Window(onCloseRequest = ::exitApplication, title = variant.title) {
            App(variant)
        }
    }
}
