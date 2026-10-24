package dev.livin.nammaoorufood.di

import dev.livin.nammaoorufood.auth.AuthApi
import dev.livin.nammaoorufood.auth.KtorAuthApi
import dev.livin.nammaoorufood.auth.SessionManager
import dev.livin.nammaoorufood.network.createAuthenticatedClient
import dev.livin.nammaoorufood.network.createPublicClient
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
    single<ShopRepository> { KtorShopRepository(get(AuthenticatedClient)) }
    single<AdminRepository> { KtorAdminRepository(get(AuthenticatedClient)) }
    viewModel { AdminOverviewViewModel(get()) }
    viewModel { AdminHotelsViewModel(get()) }
    viewModel { AdminCustomersViewModel(get()) }
    viewModel { AdminOrdersViewModel(get()) }
    single<OwnerRepository> { KtorOwnerRepository(get(AuthenticatedClient)) }
    viewModel { OwnerHotelViewModel(get()) }
    viewModel { OwnerDashboardViewModel(get()) }
    viewModel { (hotelId: Long) -> OwnerOrdersViewModel(hotelId, get()) }
    viewModel { (hotelId: Long) -> OwnerMenuViewModel(hotelId, get()) }
    viewModel { LoginViewModel(get()) }
    viewModel { HotelListViewModel(get()) }
    viewModel { (hotelId: Long) -> MenuViewModel(hotelId, get(), get()) }
    viewModel { CartViewModel(get(), get()) }
    viewModel { OrdersViewModel(get()) }
    viewModel { NotificationsViewModel(get()) }
    viewModel { (orderId: Long) -> OrderDetailViewModel(orderId, get()) }
}
