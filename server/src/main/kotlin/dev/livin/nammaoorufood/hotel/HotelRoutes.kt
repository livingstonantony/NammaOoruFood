package dev.livin.nammaoorufood.hotel

import dev.livin.nammaoorufood.auth.JWT_AUTH
import dev.livin.nammaoorufood.auth.principalOrThrow
import dev.livin.nammaoorufood.plugins.ValidationException
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

private fun ApplicationCall.longParam(name: String): Long =
    parameters[name]?.toLongOrNull() ?: throw ValidationException("Invalid $name")

fun Route.hotelRoutes(service: HotelService) {
    authenticate(JWT_AUTH) {
        route("/hotels") {
            get {
                val q = call.request.queryParameters
                call.respond(
                    service.listHotels(
                        call.principalOrThrow(), q["q"], q["openOnly"]?.toBoolean() ?: false,
                        q["page"]?.toIntOrNull() ?: 1, q["pageSize"]?.toIntOrNull() ?: 20,
                    ),
                )
            }
            route("/{hotelId}") {
                get { call.respond(service.getHotel(call.principalOrThrow(), call.longParam("hotelId"))) }
                route("/menu") {
                    get { call.respond(service.menu(call.principalOrThrow(), call.longParam("hotelId"))) }
                    post("/categories") {
                        val c = service.createCategory(call.principalOrThrow(), call.longParam("hotelId"), call.receive())
                        call.respond(HttpStatusCode.Created, c)
                    }
                    route("/items") {
                        post {
                            val item = service.createFoodItem(call.principalOrThrow(), call.longParam("hotelId"), call.receive())
                            call.respond(HttpStatusCode.Created, item)
                        }
                        put("/{itemId}") {
                            call.respond(service.updateFoodItem(call.principalOrThrow(), call.longParam("hotelId"), call.longParam("itemId"), call.receive()))
                        }
                        patch("/{itemId}/availability") {
                            val body = call.receive<AvailabilityRequest>()
                            call.respond(service.setItemAvailability(call.principalOrThrow(), call.longParam("hotelId"), call.longParam("itemId"), body.available))
                        }
                        delete("/{itemId}") {
                            service.deleteFoodItem(call.principalOrThrow(), call.longParam("hotelId"), call.longParam("itemId"))
                            call.respond(HttpStatusCode.NoContent)
                        }
                    }
                }
            }
        }

        route("/owner/hotels") {
            get { call.respond(service.ownedHotels(call.principalOrThrow())) }
            post { call.respond(HttpStatusCode.Created, service.createHotel(call.principalOrThrow(), call.receive())) }
            put("/{hotelId}") {
                call.respond(service.updateHotel(call.principalOrThrow(), call.longParam("hotelId"), call.receive()))
            }
            put("/{hotelId}/hours") {
                call.respond(service.setOperatingHours(call.principalOrThrow(), call.longParam("hotelId"), call.receive()))
            }
            patch("/{hotelId}/accepting-orders") {
                val body = call.receive<AcceptingOrdersRequest>()
                call.respond(service.setAcceptingOrders(call.principalOrThrow(), call.longParam("hotelId"), body.acceptingOrders))
            }
        }

        route("/admin") {
            get("/hotels") { call.respond(service.adminListHotels(call.principalOrThrow(), call.request.queryParameters["q"])) }
            patch("/hotels/{hotelId}/approval") {
                val body = call.receive<ApprovalRequest>()
                call.respond(service.setApproval(call.principalOrThrow(), call.longParam("hotelId"), body.status))
            }
            post("/owners") { call.respond(HttpStatusCode.Created, service.createOwner(call.principalOrThrow(), call.receive())) }
        }
    }
}
