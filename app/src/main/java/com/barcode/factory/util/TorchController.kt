package com.barcode.factory.util

import androidx.camera.core.Camera

/**
 * Wraps CameraX [CameraControl] torch operations with proper lifecycle handling.
 * Requests are fire-and-forget, failures are ignored silently so a non-torch
 * camera never crashes the scanner.
 */
class TorchController(private val camera: Camera) {

    private var torchOn = false

    val isOn get() = torchOn

    fun setTorch(on: Boolean) {
        if (torchOn == on) return
        torchOn = on
        runCatching { camera.cameraControl.enableTorch(on) }
    }

    fun release() {
        if (torchOn) {
            runCatching { camera.cameraControl.enableTorch(false) }
            torchOn = false
        }
    }
}