package com.duluin.ftth.inventory.application.port.outbound

interface WarehousePostingStore {
    fun write(command: WarehousePost, cutoverEpoch: Long, stage: (PostingPhase, WarehousePostResult) -> Unit): WarehousePostResult
    fun rebuild(): List<PostingBalance>
}
