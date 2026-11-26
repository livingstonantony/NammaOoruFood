package dev.livin.nammaoorufood.di

import dev.livin.nammaoorufood.auth.AuthApi
import dev.livin.nammaoorufood.auth.KtorAuthApi
import dev.livin.nammaoorufood.auth.SessionManager
import dev.livin.nammaoorufood.network.createAuthenticatedClient
import dev.livin.nammaoorufood.network.createPublicClient
import dev.livin.nammaoorufood.network.LiveEventBus
import dev.livin.nammaoorufood.customer.cart.CartStore
import dev.livin.nammaoorufood.customer.data.KtorShopRepository
import dev.livin.nammaoorufood.customer.data.ShopRepository
import dev.livin.nammaoorufood.customer.ui.*
import dev.livin.nammaoorufood.ui.login.LoginViewModel
import io.ktor.client.HttpClient
import dev.livin.nammaoorufood.admin.data.AdminRepository
import dev.livin.nammaoorufood.admin.data.KtorAdminRepository
import dev.livin.nammaoorufood.admin.ui.*
import dev.livin.nammaoorufood.owner.data.KtorOwnerRepository
import dev.livin.nammaoorufood.owner.data.OwnerRepository
import dev.livin.nammaoorufood.owner.data.ImageKitUploadRepository
import dev.livin.nammaoorufood.owner.data.KtorImageKitUploadRepository
import dev.livin.nammaoorufood.owner.data.ImageKitClientConfig
import dev.livin.nammaoorufood.owner.ui.*
import org.koin.core.module.dsl.viewModel
import org.koin.core.qualifier.named
import org.koin.dsl.module

val AuthenticatedClient = named("authenticated")

fun appModule(platform: PlatformServices) = module {
    single<AuthApi> { KtorAuthApi(createPublicClient(platform.engine, platform.baseUrl)) }
    single { CartStore() }
    single {
        val cart = get<CartStore>()
        SessionManager(get(), platform.sessionStore, onSessionCleared = { cart.clear() })
    }
    single<HttpClient>(AuthenticatedClient) {
        createAuthenticatedClient(platform.engine, platform.baseUrl, get())
    }
    single { LiveEventBus(get(AuthenticatedClient), platform.baseUrl) }

    single<ShopRepository> { KtorShopRepository(get(AuthenticatedClient)) }
    single<AdminRepository> { KtorAdminRepository(get(AuthenticatedClient)) }
    viewModel { AdminOverviewViewModel(get(), get()) }
    viewModel { AdminHotelsViewModel(get(), get()) }
    viewModel { AdminCustomersViewModel(get()) }
    viewModel { AdminOrdersViewModel(get(), get()) }

    single<OwnerRepository> { KtorOwnerRepository(get(AuthenticatedClient)) }
    single<ImageKitUploadRepository> {
        KtorImageKitUploadRepository(
            get(AuthenticatedClient), createPublicClient(platform.engine, platform.baseUrl),
            ImageKitClientConfig.publicKey, ImageKitClientConfig.urlEndpoint,
        )
    }
    viewModel { OwnerHotelViewModel(get(), get(), get()) }
    viewModel { OwnerDashboardViewModel(get(), get()) }
    viewModel { (hotelId: Long) -> OwnerOrdersViewModel(hotelId, get(), get()) }
    viewModel { (hotelId: Long) -> OwnerMenuViewModel(hotelId, get(), get(), get()) }

    viewModel { LoginViewModel(get()) }
    viewModel { HotelListViewModel(get(), get()) }
    viewModel { (hotelId: Long) -> MenuViewModel(hotelId, get(), get(), get()) }
    viewModel { CartViewModel(get(), get()) }
    viewModel { OrdersViewModel(get(), get()) }
    viewModel { NotificationsViewModel(get(), get()) }
    viewModel { (orderId: Long) -> OrderDetailViewModel(orderId, get(), get()) }
}
