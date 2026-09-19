# ZXing core is used for barcode generation.
-keep class com.google.zxing.** { *; }

# ML Kit barcode scanning ships its own consumer rules; keep model metadata safe.
-keep class com.google.mlkit.vision.barcode.** { *; }
-keep class com.google.android.gms.internal.mlkit_vision_barcode.** { *; }

# CameraX ships consumer rules; nothing custom needed.

# ViewBinding generated classes are referenced by name in layout binding.
-keep class com.barcode.factory.databinding.** { *; }
