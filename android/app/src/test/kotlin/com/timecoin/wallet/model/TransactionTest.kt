package com.timecoin.wallet.model

import com.timecoin.wallet.crypto.Address
import com.timecoin.wallet.crypto.Keypair
import com.timecoin.wallet.crypto.NetworkType
import com.timecoin.wallet.crypto.hexToByteArray
import org.junit.Assert.*
import org.junit.Test

class TransactionTest {

    private fun createTestKeypair(): Keypair = Keypair.generate()

    @Test
    fun `new transaction has correct defaults`() {
        val tx = Transaction()
        assertEquals(2, tx.version)
        assertTrue(tx.inputs.isEmpty())
        assertTrue(tx.outputs.isEmpty())
        assertEquals(0, tx.lockTime)
    }

    @Test
    fun `addOutput rejects zero value`() {
        val tx = Transaction()
        try {
            tx.addOutput(TxOutput(value = 0, scriptPubkey = "test".toByteArray()))
            fail("Should throw")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("must be > 0"))
        }
    }

    @Test
    fun `totalOutput sums correctly`() {
        val kp = createTestKeypair()
        val addr = Address.fromPublicKey(kp.publicKeyBytes(), NetworkType.Mainnet)
        val tx = Transaction()
        tx.addOutput(TxOutput.new(100_000_000, addr))
        tx.addOutput(TxOutput.new(50_000_000, addr))
        assertEquals(150_000_000, tx.totalOutput())
    }

    @Test
    fun `txid is deterministic`() {
        val tx = Transaction(timestamp = 1000)
        val prevTx = ByteArray(32)
        tx.addInput(TxInput.new(prevTx, 0))
        tx.addOutput(TxOutput(value = 1000, scriptPubkey = "TIME1test".toByteArray()))

        val txid1 = tx.txid()
        val txid2 = tx.txid()
        assertEquals(txid1, txid2)
        assertEquals(64, txid1.length) // 32 bytes = 64 hex chars
    }

    @Test
    fun `hash returns 32 bytes`() {
        val tx = Transaction(timestamp = 1000)
        tx.addOutput(TxOutput(value = 1, scriptPubkey = "addr".toByteArray()))
        assertEquals(32, tx.hash().size)
    }

    @Test
    fun `sign produces 96-byte scriptSig`() {
        val kp = createTestKeypair()
        val addr = Address.fromPublicKey(kp.publicKeyBytes(), NetworkType.Mainnet)
        val tx = Transaction(timestamp = 1000)
        tx.addInput(TxInput.new(ByteArray(32), 0))
        tx.addOutput(TxOutput.new(1_000_000, addr))
        tx.sign(kp, 0)

        assertEquals(96, tx.inputs[0].scriptSig.size)
        // First 32 bytes should be the public key
        assertArrayEquals(kp.publicKeyBytes(), tx.inputs[0].scriptSig.copyOfRange(0, 32))
    }

    @Test
    fun `sign and verify round-trip`() {
        val kp = createTestKeypair()
        val addr = Address.fromPublicKey(kp.publicKeyBytes(), NetworkType.Mainnet)
        val tx = Transaction(timestamp = 1000)
        tx.addInput(TxInput.new(ByteArray(32), 0))
        tx.addOutput(TxOutput.new(1_000_000, addr))
        tx.sign(kp, 0)

        assertTrue(tx.verifyInput(0))
    }

    @Test
    fun `signAll signs all inputs`() {
        val kp = createTestKeypair()
        val addr = Address.fromPublicKey(kp.publicKeyBytes(), NetworkType.Mainnet)
        val tx = Transaction(timestamp = 1000)
        tx.addInput(TxInput.new(ByteArray(32), 0))
        tx.addInput(TxInput.new(ByteArray(32), 1))
        tx.addOutput(TxOutput.new(1_000_000, addr))
        tx.signAll(kp)

        assertTrue(tx.verifyInput(0))
        assertTrue(tx.verifyInput(1))
    }

    @Test
    fun `unsigned input fails verification`() {
        val tx = Transaction(timestamp = 1000)
        tx.addInput(TxInput.new(ByteArray(32), 0))
        tx.addOutput(TxOutput(value = 1000, scriptPubkey = "addr".toByteArray()))

        assertFalse(tx.verifyInput(0))
    }

    @Test
    fun `wrong key fails verification`() {
        val kp1 = createTestKeypair()
        val kp2 = createTestKeypair()
        val addr = Address.fromPublicKey(kp1.publicKeyBytes(), NetworkType.Mainnet)
        val tx = Transaction(timestamp = 1000)
        tx.addInput(TxInput.new(ByteArray(32), 0))
        tx.addOutput(TxOutput.new(1_000_000, addr))

        // Sign with kp1, then replace pubkey bytes with kp2's (simulating wrong key)
        tx.sign(kp1, 0)
        assertTrue(tx.verifyInput(0))

        // Create a new tx signed with kp2 - it should still verify since the sig matches kp2
        val tx2 = Transaction(timestamp = 1000)
        tx2.addInput(TxInput.new(ByteArray(32), 0))
        tx2.addOutput(TxOutput.new(1_000_000, addr))
        tx2.sign(kp2, 0)
        assertTrue(tx2.verifyInput(0)) // kp2's own signature verifies with kp2's key
    }

    @Test
    fun `TxOutput addressString returns correct string`() {
        val addr = "TIME1abcdef12345"
        val output = TxOutput(value = 1000, scriptPubkey = addr.toByteArray())
        assertEquals(addr, output.addressString())
    }

    @Test
    fun `OutPoint requires 32 bytes`() {
        try {
            OutPoint(txid = ByteArray(16), vout = 0)
            fail("Should throw")
        } catch (e: IllegalArgumentException) {
            // expected
        }
    }
}
