package com.mok.buylist.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BatchRefreshSummaryTest {
    @Test fun stoppedBatchOnlyCountsProcessedProducts() {
        val result = BatchPriceRefresh(4, 20, 1, false, missingBarcodes = 2, stopped = true)
        assertEquals("Обновление остановлено: 3 из 20 · ошибок: 1 · без штрихкода: 2", result.summary())
    }

    @Test fun fullBatchCountsSuccessfulProducts() {
        assertEquals("Данные каталога обновлены: 9 из 10 · ошибок: 1",
            BatchPriceRefresh(10, 10, 1, false).summary())
    }

    @Test fun addressChangeDoesNotReportUntouchedProductsAsErrors() {
        val result = BatchPriceRefresh(3, 10, 0, false, stopped = true, addressChanged = true)
        assertTrue(result.summary().contains("адрес магазина изменился: 3 из 10"))
    }
}
