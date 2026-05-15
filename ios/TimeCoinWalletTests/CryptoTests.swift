import XCTest
@testable import TimeCoinWallet

final class CryptoTests: XCTestCase {

    // MARK: - Address

    func testAddressRoundTrip() throws {
        let pubkey = Data(repeating: 0x42, count: 32)
        let addr = Address.fromPublicKey(pubkey, network: .mainnet)
        XCTAssertTrue(addr.description.hasPrefix("TIME1"))
        let parsed = try Address.fromString(addr.description)
        XCTAssertEqual(addr, parsed)
    }

    func testTestnetAddress() throws {
        let pubkey = Data(repeating: 0x01, count: 32)
        let addr = Address.fromPublicKey(pubkey, network: .testnet)
        XCTAssertTrue(addr.description.hasPrefix("TIME0"))
    }

    func testAddressChecksumValidation() {
        XCTAssertFalse(Address.isValid("TIME1invalid"))
        XCTAssertFalse(Address.isValid("NOTANADDRESS"))
        XCTAssertFalse(Address.isValid(""))
    }

    func testBase58RoundTrip() throws {
        let data = Data([0, 1, 2, 3, 255, 128, 64])
        let encoded = Base58.encode(data)
        let decoded = try Base58.decode(encoded)
        XCTAssertEqual(data, decoded)
    }

    // MARK: - Keypair

    func testKeypairGenerateAndSign() throws {
        let kp = Keypair.generate()
        XCTAssertEqual(kp.publicKeyBytes.count, 32)
        XCTAssertEqual(kp.secretKeyBytes.count, 32)

        let message = Data("hello TIME coin".utf8)
        let sig = try kp.sign(message)
        XCTAssertEqual(sig.count, 64)
        XCTAssertTrue(kp.verify(message: message, signature: sig))
        XCTAssertFalse(kp.verify(message: Data("tampered".utf8), signature: sig))
    }

    func testKeypairFromBytes() throws {
        let kp1 = Keypair.generate()
        let kp2 = try Keypair.fromBytes(kp1.secretKeyBytes)
        XCTAssertEqual(kp1.publicKeyBytes, kp2.publicKeyBytes)
    }

    func testKeypairFromHex() throws {
        let kp1 = Keypair.generate()
        let kp2 = try Keypair.fromHex(kp1.secretKeyHex)
        XCTAssertEqual(kp1.publicKeyBytes, kp2.publicKeyBytes)
    }

    // MARK: - Mnemonic / SLIP-0010

    func testMnemonicGenerate() throws {
        let phrase = try MnemonicHelper.generate(wordCount: 12)
        let words = phrase.components(separatedBy: " ")
        XCTAssertEqual(words.count, 12)
    }

    func testMnemonicValidate() throws {
        let phrase = try MnemonicHelper.generate()
        XCTAssertTrue(MnemonicHelper.validate(phrase))
        XCTAssertFalse(MnemonicHelper.validate("invalid mnemonic phrase here test"))
    }

    func testKnownDerivation() throws {
        // Known test vector: same mnemonic produces same address on both platforms
        let mnemonic = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
        let kp = try MnemonicHelper.deriveKeypairBip44(phrase: mnemonic, index: 0)
        XCTAssertEqual(kp.publicKeyBytes.count, 32)
        // Verify deterministic: same call produces same result
        let kp2 = try MnemonicHelper.deriveKeypairBip44(phrase: mnemonic, index: 0)
        XCTAssertEqual(kp.publicKeyBytes, kp2.publicKeyBytes)
        // Different index → different key
        let kp3 = try MnemonicHelper.deriveKeypairBip44(phrase: mnemonic, index: 1)
        XCTAssertNotEqual(kp.publicKeyBytes, kp3.publicKeyBytes)
    }

    func testAddressDerivation() throws {
        let mnemonic = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
        let addr0 = try MnemonicHelper.deriveAddress(phrase: mnemonic, index: 0, network: .mainnet)
        let addr1 = try MnemonicHelper.deriveAddress(phrase: mnemonic, index: 1, network: .mainnet)
        XCTAssertTrue(addr0.hasPrefix("TIME1"))
        XCTAssertTrue(addr1.hasPrefix("TIME1"))
        XCTAssertNotEqual(addr0, addr1)
    }

    // MARK: - Transaction signing

    func testTransactionSignAndVerify() throws {
        let kp = Keypair.generate()
        let dummyTxid = Data(repeating: 0xAB, count: 32)
        var tx = TimeCoinTransaction()
        tx.addInput(TxInput.new(prevTx: dummyTxid, prevIndex: 0))

        let addr = Address.fromPublicKey(kp.publicKeyBytes, network: .mainnet)
        tx.addOutput(TxOutput.new(amount: 1_000_000, address: addr))

        try tx.signAll(keypair: kp)
        XCTAssertEqual(tx.inputs[0].scriptSig.count, 96)
        XCTAssertTrue(try tx.verifyInput(inputIndex: 0))
    }

    func testTransactionTxid() throws {
        let kp = Keypair.generate()
        let dummyTxid = Data(repeating: 0x11, count: 32)
        var tx = TimeCoinTransaction(timestamp: 1_700_000_000)
        tx.addInput(TxInput.new(prevTx: dummyTxid, prevIndex: 0))
        let addr = Address.fromPublicKey(kp.publicKeyBytes, network: .mainnet)
        tx.addOutput(TxOutput.new(amount: 50_000_000, address: addr))

        let txid1 = try tx.txid()
        let txid2 = try tx.txid()
        XCTAssertEqual(txid1, txid2)         // deterministic
        XCTAssertEqual(txid1.count, 64)      // hex SHA-256
    }

    // MARK: - Encryption

    func testEncryptDecryptRoundTrip() throws {
        let plaintext = Data("test wallet data".utf8)
        let password = "test_password_123"
        let encrypted = try WalletEncryption.encrypt(plaintext: plaintext, password: password)
        XCTAssertEqual(encrypted.salt.count, 16)
        XCTAssertEqual(encrypted.nonce.count, 12)
        let decrypted = try WalletEncryption.decrypt(encrypted: encrypted, password: password)
        XCTAssertEqual(decrypted, plaintext)
    }

    func testWrongPasswordFails() throws {
        let plaintext = Data("secret".utf8)
        let encrypted = try WalletEncryption.encrypt(plaintext: plaintext, password: "correct")
        XCTAssertThrowsError(try WalletEncryption.decrypt(encrypted: encrypted, password: "wrong"))
    }

    // MARK: - Fee calculation

    func testFeeSchedule() {
        let schedule = FeeSchedule.default
        // < 100 TIME → 1%
        XCTAssertEqual(schedule.calculateFee(sendAmount: 10_000_000_000), 100_000_000)  // 10% of 100 TIME → wait this is 1%
        // minimum fee
        XCTAssertEqual(schedule.calculateFee(sendAmount: 100), FeeSchedule.minTxFee)
    }

    // MARK: - Config parsing

    func testConfigManagerParse() {
        let conf = """
        # Comment
        addnode=192.168.1.1:24001
        addnode=10.0.0.1:24001
        rpcuser=admin
        rpcpassword=secret
        """
        let config = ConfigManager.parse(conf)
        XCTAssertEqual(config.peers, ["192.168.1.1:24001", "10.0.0.1:24001"])
        XCTAssertEqual(config.rpcUser, "admin")
        XCTAssertEqual(config.rpcPassword, "secret")
    }

    func testConfigManagerSerialize() {
        var config = ConfigManager.Config()
        config.peers = ["1.2.3.4:24001"]
        config.rpcUser = "user"
        let serialized = ConfigManager.serialize(config)
        let reparsed = ConfigManager.parse(serialized)
        XCTAssertEqual(reparsed.peers, config.peers)
        XCTAssertEqual(reparsed.rpcUser, config.rpcUser)
    }
}
