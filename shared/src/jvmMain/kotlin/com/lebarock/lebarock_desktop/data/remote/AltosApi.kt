package com.lebarock.lebarock_desktop.data.remote

import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Header
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

    /** Ends a session opened only for one call (the print agent's admin sign-in). */
    @POST("api/mobile/logout")
    suspend fun logoutAs(@Header("Authorization") bearer: String): Envelope<Unit?>

    /** The branch key and printers for the "Directe" print agent ("Manage Settings" only). */
    @GET("api/mobile/desktop/print-agent")
    suspend fun printAgent(@Header("Authorization") bearer: String): Envelope<PrintAgentDto>

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
        // D5: « À encaisser »: billed and on-credit checks of every day.
        @Query("to_pay") toPay: Int? = null,
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
    suspend fun cancelOrder(@Path("id") id: Long, @Body body: CancelRequest): Envelope<Unit?>

    /** The cart's make-up as on the web POS: order types, next number, taxes, charges, waiters (D3). */
    @GET("api/mobile/pos-context")
    suspend fun posContext(): Envelope<PosContextDto>

    /** Reasons offered when cancelling a whole order ("Cancel Order" only). */
    @GET("api/mobile/cancel-reasons")
    suspend fun cancelReasons(): Envelope<List<CancelReasonDto>>

    // ---- D4: a table and its check, as on the web ----

    @POST("api/mobile/orders/{id}/table")
    suspend fun moveOrder(@Path("id") id: Long, @Body body: TableIdRequest): Envelope<OrderDetailDto>

    @POST("api/mobile/orders/{id}/merge")
    suspend fun mergeOrder(@Path("id") id: Long, @Body body: TableIdRequest): Envelope<OrderDetailDto>

    @POST("api/mobile/orders/{id}/split")
    suspend fun splitOrder(@Path("id") id: Long, @Body body: SplitRequest): SplitResponse

    @POST("api/mobile/orders/{id}/waiter")
    suspend fun setWaiter(@Path("id") id: Long, @Body body: WaiterRequest): Envelope<OrderDetailDto>

    @GET("api/mobile/customers")
    suspend fun customers(@Query("search") search: String): Envelope<List<CustomerDto>>

    @POST("api/mobile/orders/{id}/customer")
    suspend fun setCustomer(@Path("id") id: Long, @Body body: CustomerRequest): Envelope<OrderDetailDto>

    @DELETE("api/mobile/orders/{id}/charges/{chargeId}")
    suspend fun removeCharge(@Path("id") id: Long, @Path("chargeId") chargeId: Long): Envelope<OrderDetailDto>

    @POST("api/mobile/orders/{id}/payments/{paymentId}/method")
    suspend fun changePaymentMethod(@Path("id") id: Long, @Path("paymentId") paymentId: Long, @Body body: PaymentMethodRequest): Envelope<OrderDetailDto>

    @POST("api/mobile/orders/{id}/reviewed")
    suspend fun markReviewed(@Path("id") id: Long): Envelope<OrderDetailDto>

    @POST("api/mobile/tables/{id}/release")
    suspend fun releaseTable(@Path("id") id: Long): Envelope<Unit?>

    /** « Servi » on a round the kitchen has ready (C1). */
    @POST("api/mobile/tickets/{id}/served")
    suspend fun markServed(@Path("id") id: Long): Envelope<kotlinx.serialization.json.JsonObject?>

    // ---- Payment (D5) ----

    @GET("api/mobile/orders/{id}/payment-context")
    suspend fun paymentContext(@Path("id") id: Long): Envelope<PaymentContextDto>

    @POST("api/mobile/orders/{id}/payments")
    suspend fun pay(@Path("id") id: Long, @Body body: PaymentRequest): Envelope<PaymentResultDto>

    @POST("api/mobile/orders/{id}/tip")
    suspend fun tip(@Path("id") id: Long, @Body body: TipRequest): Envelope<TipResultDto>

    // ---- D6: the account's Caisse and the expenses ----

    @GET("api/mobile/cash-register")
    suspend fun cashRegister(): Envelope<CashRegisterStateDto>

    @POST("api/mobile/cash-register/open")
    suspend fun openSession(@Body body: OpenSessionRequest): Envelope<CashRegisterStateDto>

    @POST("api/mobile/cash-register/movements")
    suspend fun cashMovement(@Body body: CashMovementRequest): Envelope<CashRegisterStateDto>

    @POST("api/mobile/cash-register/x")
    suspend fun xReport(@Body body: PrintTargetRequest): Envelope<CashReportDto>

    @POST("api/mobile/cash-register/close")
    suspend fun closeSession(@Body body: CloseSessionRequest): Envelope<CashReportDto>

    @GET("api/mobile/cash-register/reports")
    suspend fun cashReports(@Query("type") type: String? = null): Envelope<List<CashReportDto>>

    @GET("api/mobile/cash-register/reports/{id}")
    suspend fun cashReport(@Path("id") id: Long): Envelope<CashReportDto>

    @POST("api/mobile/cash-register/reports/{id}/print")
    suspend fun printCashReport(@Path("id") id: Long, @Body body: PrintTargetRequest): Envelope<CashReportDto>

    @POST("api/mobile/cash-register/reports/{id}/stock-request")
    suspend fun stockRequest(@Path("id") id: Long): Envelope<kotlinx.serialization.json.JsonObject?>

    @GET("api/mobile/expenses")
    suspend fun expenses(@Query("date") date: String? = null): ExpensesResponse

    @POST("api/mobile/expenses")
    suspend fun addExpense(@Body body: ExpenseRequest): Envelope<ExpenseDto>

    /** « Remise » on an open check (D5, « Appliquer une remise »). */
    @POST("api/mobile/orders/{id}/discount")
    suspend fun setDiscount(@Path("id") id: Long, @Body body: DiscountRequest): Envelope<OrderDetailDto>

    /** « Code remise » (client, 2026-11-11): checked at once in the discount window. */
    @POST("api/mobile/discount-code/check")
    suspend fun checkDiscountCode(@Body body: DiscountCodeRequest): Envelope<Unit?>

    // ---- Offline mode: the caisse's sync API (the handheld's calls) ----

    /**
     * The device key alone (O4a); a PC without one registers with the branch's « Clé de synchronisation » ([enrolKey])
     * or, as before, a signed-in account's token: the caisse then issues its key.
     */
    @POST("api/sync/heartbeat")
    suspend fun heartbeat(
        @Body body: HeartbeatRequest,
        @Header("X-Sync-Key") syncKey: String?,
        @Header("X-Sync-Enrol") enrolKey: String? = null,
    ): Envelope<HeartbeatData>

    /** The branch's copy with the device key alone, signed in or not (O4a); 304 when the static part is unchanged. */
    @GET("api/sync/branch-snapshot")
    suspend fun snapshot(
        @Query("parts") parts: String,
        @Header("X-Device-Uuid") deviceUuid: String,
        @Header("X-Sync-Key") syncKey: String?,
        @Header("If-None-Match") version: String?,
    ): retrofit2.Response<Envelope<SnapshotData>>

    /** The outbox, with the device key alone. */
    @POST("api/sync/batch")
    suspend fun syncBatch(
        @Body body: SyncBatchRequest,
        @Header("X-Device-Uuid") deviceUuid: String,
        @Header("X-Sync-Key") syncKey: String?,
    ): Envelope<SyncBatchData>

    /** Is the caisse there (app and database)? Asked to come back online. */
    @GET("api/sync/ping")
    suspend fun syncPing(): SyncPingData
}
