package dev.livin.nammaoorufood.admin.data

import dev.livin.nammaoorufood.auth.ApiResult
import dev.livin.nammaoorufood.network.*
import io.ktor.client.HttpClient
import io.ktor.client.request.parameter
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import io.ktor.http.path

interface AdminRepository {
    suspend fun overview(from: String? = null, to: String? = null): ApiResult<PlatformOverview>
    suspend fun hotelSales(from: String? = null, to: String? = null): ApiResult<HotelSalesReport>
    suspend fun hotels(query: String?): ApiResult<List<HotelDto>>
    suspend fun setApproval(hotelId: Long, status: String): ApiResult<HotelDto>
    suspend fun customers(query: String?, page: Int): ApiResult<CustomerPage>
    suspend fun setCustomerActive(id: Long, active: Boolean): ApiResult<CustomerDto>
    suspend fun orders(status: OrderStatus?, hotelId: Long?, page: Int): ApiResult<OrderPage>
}

class KtorAdminRepository(private val client: HttpClient) : AdminRepository {
    override suspend fun overview(from: String?, to: String?) = client.apiCall<PlatformOverview> {
        url.path("admin/reports/overview")
        parameter("from", from)
        parameter("to", to)
    }

    override suspend fun hotelSales(from: String?, to: String?) = client.apiCall<HotelSalesReport> {
        url.path("admin/reports/hotels")
        parameter("from", from)
        parameter("to", to)
    }

    override suspend fun hotels(query: String?) = client.apiCall<List<HotelDto>> {
        url.path("admin/hotels")
        parameter("q", query?.takeIf { it.isNotBlank() })
    }

    override suspend fun setApproval(hotelId: Long, status: String) = client.apiCall<HotelDto> {
        method = HttpMethod.Patch
        url.path("admin/hotels/$hotelId/approval")
        contentType(ContentType.Application.Json)
        setBody(ApprovalRequest(status))
    }

    override suspend fun customers(query: String?, page: Int) = client.apiCall<CustomerPage> {
        url.path("admin/customers")
        parameter("q", query?.takeIf { it.isNotBlank() })
        parameter("page", page)
        parameter("pageSize", PAGE_SIZE)
    }

    override suspend fun setCustomerActive(id: Long, active: Boolean) = client.apiCall<CustomerDto> {
        method = HttpMethod.Patch
        url.path("admin/customers/$id/active")
        contentType(ContentType.Application.Json)
        setBody(ActiveRequest(active))
    }

    override suspend fun orders(status: OrderStatus?, hotelId: Long?, page: Int) = client.apiCall<OrderPage> {
        url.path("admin/orders")
        parameter("status", status?.name)
        parameter("hotelId", hotelId)
        parameter("page", page)
        parameter("pageSize", PAGE_SIZE)
    }

    companion object { const val PAGE_SIZE = 20 }
}
