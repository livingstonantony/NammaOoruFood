package dev.livin.nammaoorufood.hotel

import dev.livin.nammaoorufood.auth.PasswordHasher
import dev.livin.nammaoorufood.auth.UserPrincipal
import dev.livin.nammaoorufood.database.Database
import dev.livin.nammaoorufood.plugins.ConflictException
import dev.livin.nammaoorufood.plugins.ForbiddenException
import dev.livin.nammaoorufood.plugins.NotFoundException
import dev.livin.nammaoorufood.plugins.ValidationException
import dev.livin.nammaoorufood.user.Role
import dev.livin.nammaoorufood.user.UserDto
import dev.livin.nammaoorufood.user.UserRepository
import dev.livin.nammaoorufood.user.toDto
import java.math.BigDecimal
import java.sql.Connection
import java.sql.SQLIntegrityConstraintViolationException
import java.time.Clock
import java.time.LocalTime
import java.time.ZonedDateTime
import java.time.format.DateTimeParseException

class HotelService(
    private val db: Database,
    private val hotels: HotelRepository,
    private val users: UserRepository,
    private val hasher: PasswordHasher,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val maxPrice = BigDecimal("100000.00")

    fun listHotels(p: UserPrincipal, query: String?, openOnly: Boolean, page: Int, pageSize: Int): HotelPage {
        val size = pageSize.coerceIn(1, 50)
        val pageNo = page.coerceAtLeast(1)
        return db.transaction { conn ->
            val found = hotels.search(conn, query, approvedOnly = true, ownerId = null)
            val hours = hotels.hoursFor(conn, found.map { it.id })
            val dtos = found.map { it.toDto(hours[it.id].orEmpty()) }.filter { !openOnly || it.isOpen }
            HotelPage(dtos.drop((pageNo - 1) * size).take(size), pageNo, size, dtos.size)
        }
    }

    fun ownedHotels(p: UserPrincipal): List<HotelDto> {
        requireRole(p, Role.HOTEL_OWNER)
        return db.transaction { conn ->
            val found = hotels.search(conn, null, approvedOnly = false, ownerId = p.userId)
            val hours = hotels.hoursFor(conn, found.map { it.id })
            found.map { it.toDto(hours[it.id].orEmpty()) }
        }
    }

    fun adminListHotels(p: UserPrincipal, query: String?): List<HotelDto> {
        requireRole(p, Role.ADMIN)
        return db.transaction { conn ->
            val found = hotels.search(conn, query, approvedOnly = false, ownerId = null)
            val hours = hotels.hoursFor(conn, found.map { it.id })
            found.map { it.toDto(hours[it.id].orEmpty()) }
        }
    }

    fun getHotel(p: UserPrincipal, id: Long): HotelDto = db.transaction { conn ->
        val hotel = visibleHotel(conn, p, id)
        hotel.toDto(hotels.hoursFor(conn, listOf(id))[id].orEmpty())
    }

    fun createHotel(p: UserPrincipal, req: HotelRequest): HotelDto {
        requireRole(p, Role.HOTEL_OWNER)
        val v = validateHotel(req)
        return db.transaction { conn ->
            hotels.create(conn, p.userId, v.name, v.description, v.address, v.phone).toDto(emptyList())
        }
    }

    fun updateHotel(p: UserPrincipal, id: Long, req: HotelRequest): HotelDto {
        val v = validateHotel(req)
        return db.transaction { conn ->
            val hotel = ownedHotel(conn, p, id)
            hotels.update(conn, id, v.name, v.description, v.address, v.phone)
            hotels.findById(conn, id)!!.toDto(hotels.hoursFor(conn, listOf(hotel.id))[id].orEmpty())
        }
    }

    fun setOperatingHours(p: UserPrincipal, id: Long, req: OperatingHoursRequest): HotelDto {
        val parsed = req.hours.map { h ->
            val opens = parseTime(h.opensAt)
            val closes = parseTime(h.closesAt)
            if (h.dayOfWeek !in 1..7) throw ValidationException("dayOfWeek must be 1 (Monday) to 7 (Sunday)")
            if (opens == closes) throw ValidationException("Opening and closing time must differ")
            OperatingHour(h.dayOfWeek, opens, closes)
        }
        if (parsed.map { it.dayOfWeek }.toSet().size != parsed.size) throw ValidationException("Duplicate day in operating hours")
        return db.transaction { conn ->
            ownedHotel(conn, p, id)
            hotels.replaceHours(conn, id, parsed)
            hotels.findById(conn, id)!!.toDto(parsed)
        }
    }

    fun setAcceptingOrders(p: UserPrincipal, id: Long, accepting: Boolean): HotelDto = db.transaction { conn ->
        val hotel = ownedHotel(conn, p, id)
        if (accepting && hotel.approvalStatus != ApprovalStatus.APPROVED) throw ConflictException("Hotel is not approved yet")
        hotels.setAcceptingOrders(conn, id, accepting)
        hotels.findById(conn, id)!!.toDto(hotels.hoursFor(conn, listOf(id))[id].orEmpty())
    }

    fun menu(p: UserPrincipal, hotelId: Long): MenuResponse = db.transaction { conn ->
        val hotel = visibleHotel(conn, p, hotelId)
        val isManager = p.role == Role.ADMIN || hotel.ownerId == p.userId
        MenuResponse(
            categories = hotels.categories(conn, hotelId).map { it.toDto() },
            items = hotels.foodItems(conn, hotelId).filter { isManager || it.available }.map { it.toDto() },
        )
    }

    fun createCategory(p: UserPrincipal, hotelId: Long, req: CategoryRequest): CategoryDto {
        val name = req.name.trim()
        if (name.isEmpty() || name.length > 80) throw ValidationException("Category name must be 1-80 characters")
        return db.transaction { conn ->
            ownedHotel(conn, p, hotelId)
            try {
                hotels.createCategory(conn, hotelId, name, req.sortOrder).toDto()
            } catch (e: SQLIntegrityConstraintViolationException) {
                throw ConflictException("Category already exists")
            }
        }
    }

    fun createFoodItem(p: UserPrincipal, hotelId: Long, req: FoodItemRequest): FoodItemDto {
        val v = validateFood(req)
        return db.transaction { conn ->
            ownedHotel(conn, p, hotelId)
            checkCategory(conn, req.categoryId, hotelId)
            hotels.createFoodItem(conn, hotelId, req.categoryId, v.name, v.description, v.price, req.available).toDto()
        }
    }

    fun updateFoodItem(p: UserPrincipal, hotelId: Long, itemId: Long, req: FoodItemRequest): FoodItemDto {
        val v = validateFood(req)
        return db.transaction { conn ->
            ownedHotel(conn, p, hotelId)
            checkCategory(conn, req.categoryId, hotelId)
            if (!hotels.updateFoodItem(conn, hotelId, itemId, req.categoryId, v.name, v.description, v.price, req.available)) {
                throw NotFoundException("Food item not found")
            }
            hotels.findFoodItem(conn, hotelId, itemId)!!.toDto()
        }
    }

    fun setItemAvailability(p: UserPrincipal, hotelId: Long, itemId: Long, available: Boolean): FoodItemDto = db.transaction { conn ->
        ownedHotel(conn, p, hotelId)
        if (!hotels.setItemAvailability(conn, hotelId, itemId, available)) throw NotFoundException("Food item not found")
        hotels.findFoodItem(conn, hotelId, itemId)!!.toDto()
    }

    fun deleteFoodItem(p: UserPrincipal, hotelId: Long, itemId: Long) = db.transaction { conn ->
        ownedHotel(conn, p, hotelId)
        if (!hotels.deleteFoodItem(conn, hotelId, itemId)) throw NotFoundException("Food item not found")
    }

    fun setApproval(p: UserPrincipal, hotelId: Long, status: ApprovalStatus): HotelDto {
        requireRole(p, Role.ADMIN)
        return db.transaction { conn ->
            hotels.findById(conn, hotelId) ?: throw NotFoundException("Hotel not found")
            hotels.setApproval(conn, hotelId, status)
            hotels.findById(conn, hotelId)!!.toDto(hotels.hoursFor(conn, listOf(hotelId))[hotelId].orEmpty())
        }
    }

    fun createOwner(p: UserPrincipal, req: CreateOwnerRequest): UserDto {
        requireRole(p, Role.ADMIN)
        val username = req.username.trim().lowercase()
        if (!Regex("^[a-z0-9._-]{3,50}$").matches(username)) throw ValidationException("Invalid username")
        if (req.password.length !in 8..72) throw ValidationException("Password must be 8-72 characters")
        val displayName = req.displayName.trim()
        if (displayName.isEmpty() || displayName.length > 100) throw ValidationException("Display name must be 1-100 characters")
        val hash = hasher.hash(req.password)
        return db.transaction { conn ->
            try {
                users.create(conn, username, hash, Role.HOTEL_OWNER, displayName, req.phone?.trim()?.takeIf { it.isNotEmpty() }).toDto()
            } catch (e: SQLIntegrityConstraintViolationException) {
                throw ConflictException("Username is already taken")
            }
        }
    }

    private fun Hotel.toDto(hours: List<OperatingHour>): HotelDto {
        val withinHours = OperatingHours.isOpenAt(hours, ZonedDateTime.now(clock.withZone(BUSINESS_ZONE)))
        return HotelDto(
            id, name, description, address, phone, approvalStatus, acceptingOrders,
            isOpen = approvalStatus == ApprovalStatus.APPROVED && acceptingOrders && withinHours,
            operatingHours = hours.map { it.toDto() },
        )
    }

    // Unapproved hotels are invisible (404) to everyone except their owner and admins.
    private fun visibleHotel(conn: Connection, p: UserPrincipal, id: Long): Hotel {
        val hotel = hotels.findById(conn, id) ?: throw NotFoundException("Hotel not found")
        val privileged = p.role == Role.ADMIN || hotel.ownerId == p.userId
        if (hotel.approvalStatus != ApprovalStatus.APPROVED && !privileged) throw NotFoundException("Hotel not found")
        return hotel
    }

    private fun ownedHotel(conn: Connection, p: UserPrincipal, id: Long): Hotel {
        requireRole(p, Role.HOTEL_OWNER)
        val hotel = hotels.findById(conn, id) ?: throw NotFoundException("Hotel not found")
        if (hotel.ownerId != p.userId) throw ForbiddenException()
        return hotel
    }

    private fun checkCategory(conn: Connection, categoryId: Long?, hotelId: Long) {
        if (categoryId != null && !hotels.categoryBelongsToHotel(conn, categoryId, hotelId)) {
            throw ValidationException("Category does not belong to this hotel")
        }
    }

    private fun requireRole(p: UserPrincipal, role: Role) {
        if (p.role != role) throw ForbiddenException()
    }

    private fun parseTime(s: String): LocalTime = try {
        LocalTime.parse(s)
    } catch (e: DateTimeParseException) {
        throw ValidationException("Invalid time '$s', expected HH:mm")
    }

    private data class ValidHotel(val name: String, val description: String?, val address: String, val phone: String?)

    private fun validateHotel(req: HotelRequest): ValidHotel {
        val name = req.name.trim()
        val address = req.address.trim()
        if (name.isEmpty() || name.length > 120) throw ValidationException("Hotel name must be 1-120 characters")
        if (address.isEmpty() || address.length > 255) throw ValidationException("Address must be 1-255 characters")
        val description = req.description?.trim()?.takeIf { it.isNotEmpty() }
        if (description != null && description.length > 500) throw ValidationException("Description is too long")
        val phone = req.phone?.trim()?.takeIf { it.isNotEmpty() }
        if (phone != null && !Regex("^[0-9+ -]{7,20}$").matches(phone)) throw ValidationException("Invalid phone number")
        return ValidHotel(name, description, address, phone)
    }

    private data class ValidFood(val name: String, val description: String?, val price: BigDecimal)

    private fun validateFood(req: FoodItemRequest): ValidFood {
        val name = req.name.trim()
        if (name.isEmpty() || name.length > 120) throw ValidationException("Item name must be 1-120 characters")
        val description = req.description?.trim()?.takeIf { it.isNotEmpty() }
        if (description != null && description.length > 500) throw ValidationException("Description is too long")
        val price = req.price.trim().toBigDecimalOrNull() ?: throw ValidationException("Invalid price")
        if (price.signum() <= 0 || price.scale() > 2 || price > maxPrice) throw ValidationException("Price must be positive with at most 2 decimals")
        return ValidFood(name, description, price.setScale(2))
    }
}
