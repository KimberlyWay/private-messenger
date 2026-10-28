package org.matrix.rustsdk

/** JNI entry point required by the SDK's documented Android platform bridge. */
object Android {
    init { System.loadLibrary("matrix_sdk_ffi") }
    @JvmStatic external fun init(context: android.content.Context)
}
