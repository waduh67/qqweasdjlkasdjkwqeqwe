package com.karuhun.ftth.inventory.adapter.inbound.web

import com.karuhun.ftth.common.infrastructure.web.StrictCommandJson
import com.karuhun.ftth.inventory.AssetTitleCorrectionRequest
import com.karuhun.ftth.inventory.InventoryAssetTitleApi
import com.karuhun.ftth.inventory.WarehouseMutationMetadata
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.*

@RestController
class AssetTitleCorrectionController(private val titles: InventoryAssetTitleApi) {
    @PostMapping("/api/v1/warehouse/asset-title-corrections")
    @ResponseStatus(HttpStatus.CREATED)
    fun request(@RequestHeader("Idempotency-Key") key: String, @RequestBody body: String) =
        titles.requestCorrection(StrictCommandJson.decode(body, AssetTitleCorrectionRequest::class.java), WarehouseMutationMetadata(key))
}
