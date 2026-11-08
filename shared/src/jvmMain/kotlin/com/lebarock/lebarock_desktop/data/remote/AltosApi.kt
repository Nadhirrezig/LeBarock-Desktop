package com.lebarock.lebarock_desktop.data.remote

import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * The caisse's API for the counter (routes/api.php, prefix /api/mobile): the same contract as the
 * handheld app (AltosPOS-Mobile `AltosApi`), copied without the kitchen screen and waiter calls,
 * which the counter does not use. Offline sync (/api/sync) comes with the offline step.
 */
interface AltosApi {

    @GET("api/mobile/ping")
    suspend fun ping(): Envelope<PingData>

    @GET("api/mobile/accounts")
    suspend fun accounts(): Envelope<List<AccountDto>>

    @POST("api/mobile/login")
    suspend fun login(@Body body: LoginRequest): Envelope<LoginData>

    @POST("api/mobile/logout")
    suspend fun logout(): Envelope<Unit?>

    @GET("api/mobile/me")
    suspend fun me(): Envelope<UserDto>

    /** The Caisses an Admin / Gérant may switch to (others: 403). */
    @GET("api/mobile/terminals")
    suspend fun terminals(): Envelope<List<TerminalDto>>

    @POST("api/mobile/terminal")
    suspend fun switchTerminal(@Body body: SwitchTerminalRequest): Envelope<TerminalDto>

    // ---- Catalogue, tables, orders (D3, D4) ----

    @GET("api/mobile/menus")
    suspend fun menus(): Envelope<List<MenuDto>>

    @GET("api/mobile/menu-items")
    suspend fun menuItems(@Query("menu_id") menuId: Long? = null): Envelope<List<MenuItemDto>>

    @GET("api/mobile/tables")
    suspend fun tables(): Envelope<List<AreaDto>>

    @GET("api/mobile/orders")
    suspend fun orders(
        @Query("status") status: String? = null,
        @Query("date_from") dateFrom: String? = null,
        @Query("date_to") dateTo: String? = null,
    ): OrdersResponse

    @GET("api/mobile/orders/{id}")
    suspend fun orderDetail(@Path("id") id: Long): Envelope<OrderDetailDto>

    @POST("api/mobile/orders")
    suspend fun sendOrder(@Body body: SendOrderRequest): Envelope<OrderActionDto>

    @POST("api/mobile/orders/{id}/items")
    suspend fun addItems(@Path("id") id: Long, @Body body: AddItemsRequest): Envelope<OrderActionDto>

    @POST("api/mobile/orders/{id}/bill")
    suspend fun billOrder(@Path("id") id: Long, @Body body: BillRequest): Envelope<OrderActionDto>

    @POST("api/mobile/orders/{id}/send")
    suspend fun sendWaiting(@Path("id") id: Long, @Body body: SendWaitingRequest): Envelope<OrderActionDto>

    @DELETE("api/mobile/orders/{id}/items/{itemId}")
    suspend fun removeOrderItem(@Path("id") id: Long, @Path("itemId") itemId: Long): Envelope<OrderActionDto>

    @POST("api/mobile/orders/{id}/print")
    suspend fun printOrder(@Path("id") id: Long, @Body body: PrintRequest): Envelope<Unit?>

    @POST("api/mobile/orders/{id}/cancel")
    suspend fun cancelOrder(@Path("id") id: Long): Envelope<Unit?>

    // ---- Payment (D5) ----

    @GET("api/mobile/orders/{id}/payment-context")
    suspend fun paymentContext(@Path("id") id: Long): Envelope<PaymentContextDto>

    @POST("api/mobile/orders/{id}/payments")
    suspend fun pay(@Path("id") id: Long, @Body body: PaymentRequest): Envelope<PaymentResultDto>

    @POST("api/mobile/orders/{id}/tip")
    suspend fun tip(@Path("id") id: Long, @Body body: TipRequest): Envelope<TipResultDto>
}
