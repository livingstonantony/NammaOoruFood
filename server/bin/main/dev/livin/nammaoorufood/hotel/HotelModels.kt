package dev.livin.nammaoorufood.hotel

import kotlinx.serialization.Serializable
import java.math.BigDecimal
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

// Business-wide timezone: operating hours are interpreted in village local time.
val BUSINESS_ZONE: ZoneId = ZoneId.of("Asia/Kolkata")

enum class ApprovalStatus { PENDING, APPROVED, REJECTED, SUSPENDED }

data class Hotel(
    val id: Long,
    val ownerId: Long,
    val name: String,
    val description: String?,
    val address: String,
    val phone: String?,
    val approvalStatus: ApprovalStatus,
    val acceptingOrders: Boolean,
)

data class OperatingHour(val dayOfWeek: Int, val opensAt: LocalTime, val closesAt: LocalTime)

data class MenuCategory(val id: Long, val hotelId: Long, val name: String, val sortOrder: Int)

data class FoodItem(
    val id: Long,
    val hotelId: Long,
    val categoryId: Long?,
    val name: String,
    val description: String?,
    val price: BigDecimal,
    val available: Boolean,
)

object OperatingHours {
    // A window whose close time is not after its open time runs overnight into the next day.
    fun isOpenAt(hours: List<OperatingHour>, now: ZonedDateTime): Boolean {
        val today = now.dayOfWeek.value
        val yesterday = if (today == 1) 7 else today - 1
        val time = now.toLocalTime()
        return hours.any { h ->
            val overnight = !h.closesAt.isAfter(h.opensAt)
            when {
                h.dayOfWeek == today && !overnight -> !time.isBefore(h.opensAt) && time.isBefore(h.closesAt)
                h.dayOfWeek == today -> !time.isBefore(h.opensAt)
                h.dayOfWeek == yesterday && overnight -> time.isBefore(h.closesAt)
                else -> false
            }
        }
    }
}

@Serializable
data class OperatingHourDto(val dayOfWeek: Int, val opensAt: String, val closesAt: String)

@Serializable
data class HotelDto(
    val id: Long,
    val name: String,
    val description: String?,
    val address: String,
    val phone: String?,
    val approvalStatus: ApprovalStatus,
    val acceptingOrders: Boolean,
    val isOpen: Boolean,
    val operatingHours: List<OperatingHourDto>,
)

@Serializable
data class HotelPage(val items: List<HotelDto>, val page: Int, val pageSize: Int, val total: Int)

@Serializable
data class HotelRequest(val name: String, val description: String? = null, val address: String, val phone: String? = null)

@Serializable
data class OperatingHoursRequest(val hours: List<OperatingHourDto>)

@Serializable
data class AcceptingOrdersRequest(val acceptingOrders: Boolean)

@Serializable
data class CategoryDto(val id: Long, val name: String, val sortOrder: Int)

@Serializable
data class CategoryRequest(val name: String, val sortOrder: Int = 0)

@Serializable
data class FoodItemDto(
    val id: Long,
    val categoryId: Long?,
    val name: String,
    val description: String?,
    val price: String,
    val available: Boolean,
)

@Serializable
data class FoodItemRequest(
    val name: String,
    val description: String? = null,
    val price: String,
    val categoryId: Long? = null,
    val available: Boolean = true,
)

@Serializable
data class AvailabilityRequest(val available: Boolean)

@Serializable
data class MenuResponse(val categories: List<CategoryDto>, val items: List<FoodItemDto>)

@Serializable
data class ApprovalRequest(val status: ApprovalStatus)

@Serializable
data class CreateOwnerRequest(val username: String, val password: String, val displayName: String, val phone: String? = null)

fun FoodItem.toDto() = FoodItemDto(id, categoryId, name, description, price.setScale(2).toPlainString(), available)
fun MenuCategory.toDto() = CategoryDto(id, name, sortOrder)
fun OperatingHour.toDto() = OperatingHourDto(dayOfWeek, opensAt.toString(), closesAt.toString())
