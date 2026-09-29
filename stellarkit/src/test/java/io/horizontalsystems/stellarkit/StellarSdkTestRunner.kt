package io.horizontalsystems.stellarkit

import org.junit.runners.model.FrameworkMethod
import org.robolectric.RobolectricTestRunner
import org.robolectric.internal.bytecode.InstrumentationConfiguration

// android-all ships an old commons-codec whose BinaryCodec breaks stellar-sdk StrKey; use the real library instead.
class StellarSdkTestRunner(testClass: Class<*>) : RobolectricTestRunner(testClass) {
    override fun createClassLoaderConfig(method: FrameworkMethod): InstrumentationConfiguration =
        InstrumentationConfiguration.Builder(super.createClassLoaderConfig(method))
            .doNotAcquirePackage("org.apache.commons.codec")
            .build()
}
