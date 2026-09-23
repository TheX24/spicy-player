package com.tx24.spicyplayer.network.data.providers

import com.tx24.spicyplayer.network.data.ProviderFailureCategory
import org.junit.Assert.assertEquals
import org.junit.Test

class ProviderErrorsTest {
    @Test fun kuwoRecordPatternParsesARecord() {
        assertEquals("{MUSICRID:'123',SONGNAME:'Test'}", KUWO_RECORD.find("{MUSICRID:'123',SONGNAME:'Test'}")?.value)
    }

    @Test fun httpFailuresKeepTheirActualCategory() {
        assertEquals(ProviderFailureCategory.SERVER, ProviderHttpException("QQ", 500).unavailable().category)
        assertEquals(ProviderFailureCategory.AUTHENTICATION, ProviderHttpException("Genius", 401).unavailable().category)
    }
}
