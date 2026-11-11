package com.lebarock.lebarock_desktop.data.remote

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/*
 * Offline mode: the caisse's device-agnostic sync API, under /api/sync, copied from the handheld (SyncDtos.kt).
 * This PC says `kind: "desktop"`: the caisse names it « D1 », « D2 »… and lists it as « Caisse PC D1 ».
 */

@Serializable
data class HeartbeatRequest(
    @SerialName("device_uuid") val deviceUuid: String,
    /** Always written out (a default would be left out of the JSON and the caisse would read a handheld). */
    val kind: String,
    val model: String? = null,
    @SerialName("app_version") val appVersion: String? = null,
    @SerialName("pending_count") val pendingCount: Int = 0,
    @SerialName("oldest_pending_at") val oldestPendingAt: String? = null,
    @SerialName("snapshot_version") val snapshotVersion: String? = null,
    /** O4a: this PC holds the branch's copy (GET /api/sync/branch-snapshot). Always written out. */
    @SerialName("snapshot_format") val snapshotFormat: Int,
)

@Serializable
data class HeartbeatDevice(
    val id: Long = 0,
    val uuid: String = "",
    val code: String = "",
)

/** A payment the caisse holds for a cash session, or what became of it (24 h). */
@Serializable
data class WaitingCommandDto(
    @SerialName("command_id") val commandId: String = "",
    val status: String = "",
    val code: String? = null,
    val message: String? = null,
    val mapping: JsonElement? = null,
)

@Serializable
data class HeartbeatData(
    val device: HeartbeatDevice = HeartbeatDevice(),
    @SerialName("server_time") val serverTime: String? = null,
    @SerialName("snapshot_version") val snapshotVersion: String? = null,
    @SerialName("snapshot_stale") val snapshotStale: Boolean = false,
    val waiting: List<WaitingCommandDto> = emptyList(),
    /** Only when the caisse issued a new one: keep it, the old one stops working. */
    @SerialName("sync_key") val syncKey: String? = null,
)

/**
 * GET /api/sync/branch-snapshot (O4a, the device key alone): "static" only when it changed since [version] (else
 * [staticUnchanged]); it holds every Caisse of the branch.
 */
@Serializable
data class SnapshotData(
    @SerialName("branch_id") val branchId: Long = 0,
    val version: String? = null,
    @SerialName("static_unchanged") val staticUnchanged: Boolean = false,
    val static: JsonObject? = null,
    val checks: JsonObject? = null,
)

@Serializable
data class SyncCommandDto(
    val id: String,
    val seq: Long,
    val type: String,
    val at: String,
    @SerialName("actor_id") val actorId: Long,
    val data: JsonElement,
)

@Serializable
data class SyncBatchRequest(
    @SerialName("device_uuid") val deviceUuid: String,
    val commands: List<SyncCommandDto>,
)

/** status: applied | merged | review | refused | waiting_session | retry. */
@Serializable
data class SyncResultDto(
    val id: String? = null,
    val seq: Long = 0,
    val type: String = "",
    val status: String = "",
    val code: String? = null,
    val message: String? = null,
    val mapping: JsonElement? = null,
    val duplicate: Boolean = false,
)

@Serializable
data class SyncBatchData(
    val device: HeartbeatDevice? = null,
    @SerialName("server_time") val serverTime: String? = null,
    val stopped: Boolean = false,
    val results: List<SyncResultDto> = emptyList(),
)

@Serializable
data class SyncPingData(
    val status: String? = null,
    @SerialName("server_time") val serverTime: String? = null,
)
