package com.timecoin.wallet.model

import org.junit.Assert.*
import org.junit.Test

class FeeScheduleTest {

    private val schedule = FeeSchedule()

    @Test
    fun `small amount under 100 TIME charges 1 percent`() {
        // 50 TIME = 5,000,000,000 satoshis
        val fee = schedule.calculateFee(5_000_000_000)
        // 1% of 50 TIME = 0.5 TIME = 50,000,000 satoshis
        assertEquals(50_000_000, fee)
    }

    @Test
    fun `amount between 100-1000 TIME charges 0_5 percent`() {
        // 500 TIME = 50,000,000,000 satoshis
        val fee = schedule.calculateFee(50_000_000_000)
        // 0.5% of 500 TIME = 2.5 TIME = 250,000,000 satoshis
        assertEquals(250_000_000, fee)
    }

    @Test
    fun `amount between 1000-10000 TIME charges 0_25 percent`() {
        // 5000 TIME = 500,000,000,000 satoshis
        val fee = schedule.calculateFee(500_000_000_000)
        // 0.25% of 5000 TIME = 12.5 TIME = 1,250,000,000 satoshis
        assertEquals(1_250_000_000, fee)
    }

    @Test
    fun `amount over 10000 TIME charges 0_1 percent`() {
        // 100,000 TIME = 10,000,000,000,000 satoshis
        val fee = schedule.calculateFee(10_000_000_000_000)
        // 0.1% of 100,000 TIME = 100 TIME = 10,000,000,000 satoshis
        assertEquals(10_000_000_000, fee)
    }

    @Test
    fun `minimum fee is enforced`() {
        // Very small amount: 0.001 TIME = 100,000 satoshis
        // 1% = 1,000 satoshis, but min fee is 1,000,000
        val fee = schedule.calculateFee(100_000)
        assertEquals(FeeSchedule.MIN_TX_FEE, fee)
    }

    @Test
    fun `minimum fee value is 0_01 TIME`() {
        assertEquals(1_000_000, FeeSchedule.MIN_TX_FEE)
    }

    @Test
    fun `satoshis per TIME constant`() {
        assertEquals(100_000_000, FeeSchedule.SATOSHIS_PER_TIME)
    }

    @Test
    fun `fee at exact tier boundary`() {
        // Exactly 100 TIME = 10,000,000,000 satoshis (hits the 100 TIME tier boundary)
        // Fee for amount < 1000 TIME = 0.5% = 50,000,000 satoshis
        val fee = schedule.calculateFee(10_000_000_000)
        assertEquals(50_000_000, fee)
    }

    @Test
    fun `zero amount returns minimum fee`() {
        val fee = schedule.calculateFee(0)
        assertEquals(FeeSchedule.MIN_TX_FEE, fee)
    }
}
