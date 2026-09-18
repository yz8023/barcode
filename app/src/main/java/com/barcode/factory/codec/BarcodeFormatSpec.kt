package com.barcode.factory.codec

/**
 * Barcode symbologies supported by the app. [key] values are kept identical to the
 * original web app so that previously persisted preferences/archives stay compatible.
 */
enum class BarcodeFormatSpec(val key: String, val label: String) {
    EAN13("EAN13", "EAN-13"),
    CODE128("CODE128", "CODE 128"),
    CODE39("CODE39", "CODE 39"),
    UPC("UPC", "UPC-A"),
    ITF14("ITF14", "ITF-14"),
    PHARMACODE("pharmacode", "Pharmacode"),
    MSI("msi", "MSI");

    companion object {
        fun fromKey(key: String?): BarcodeFormatSpec =
            values().firstOrNull { it.key.equals(key, ignoreCase = true) } ?: EAN13
    }
}
