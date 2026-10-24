package dev.livin.nammaoorufood.order

import dev.livin.nammaoorufood.auth.JWT_AUTH
import dev.livin.nammaoorufood.auth.principalOrThrow
import dev.livin.nammaoorufood.plugins.ValidationException
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

private fun ApplicationCall.orderId(): Long =
    parameters["orderId"]?.toLongOrNull() ?: throw ValidationException("Invalid orderId")

fun Route.orderRoutes(service: OrderService) {
    authenticate(JWT_AUTH) {
        route("/orders") {
            post {
                val (order, created) = service.place(call.principalOrThrow(), call.receive(), call.request.header("Idempotency-Key"))
                call.respond(if (created) HttpStatusCode.Created else HttpStatusCode.OK, order)
            }
            get {
                val q = call.request.queryParameters
                val status = q["status"]?.let { s -> OrderStatus.entries.firstOrNull { it.name == s } ?: throw ValidationException("Invalid status") }
                call.respond(service.list(call.principalOrThrow(), status, q["page"]?.toIntOrNull() ?: 1, q["pageSize"]?.toIntOrNull() ?: 20))
            }
            get("/active") { call.respond(service.active(call.principalOrThrow())) }
            route("/{orderId}") {
                get { call.respond(service.get(call.principalOrThrow(), call.orderId())) }
                post("/accept") { call.respond(service.accept(call.principalOrThrow(), call.orderId())) }
                post("/cancel") { call.respond(service.cancel(call.principalOrThrow(), call.orderId())) }
                post("/reject") { call.respond(service.reject(call.principalOrThrow(), call.orderId())) }
                patch("/status") {
                    val body = call.receive<StatusUpdateRequest>()
                    call.respond(service.updateStatus(call.principalOrThrow(), call.orderId(), body.status))
                }
            }
        }
        route("/owner") {
            get("/orders") {
                val q = call.request.queryParameters
                val statuses = q["status"]?.split(",")?.filter { it.isNotBlank() }?.map { s ->
                    OrderStatus.entries.firstOrNull { it.name == s.trim() } ?: throw ValidationException("Invalid status")
                }?.toSet().orEmpty()
                val hotelId = q["hotelId"]?.let { it.toLongOrNull() ?: throw ValidationException("Invalid hotelId") }
                call.respond(service.ownerOrders(call.principalOrThrow(), hotelId, statuses, q["page"]?.toIntOrNull() ?: 1, q["pageSize"]?.toIntOrNull() ?: 50))
            }
            get("/dashboard") { call.respond(service.dashboard(call.principalOrThrow())) }
        }
    }
}
