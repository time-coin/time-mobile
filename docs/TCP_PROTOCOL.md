# TIME Coin TCP Protocol

## Connection

- **Host**: masternode IP/domain
- **Port**: 24100 (testnet), 24101 (mainnet)
- **Protocol**: Length-prefixed JSON over TCP

## Message Format

```
[4-byte length (big-endian)][UTF-8 JSON message]
```

## Messages

### RegisterXpub (Client → Server)

```json
{
  "RegisterXpub": {
    "xpub": "xpub6CUGRUonZSQ4..."
  }
}
```

### XpubRegistered (Server → Client)

```json
{
  "XpubRegistered": {
    "success": true,
    "message": "Monitoring 20 addresses"
  }
}
```

### NewTransactionNotification (Server → Client)

```json
{
  "NewTransactionNotification": {
    "transaction": {
      "tx_hash": "abc123...",
      "from_address": "TIME1...",
      "to_address": "TIME1...",
      "amount": 50000000,
      "timestamp": 1732034400,
      "block_height": 0,
      "confirmations": 0
    }
  }
}
```

### UtxoUpdate (Server → Client)

```json
{
  "UtxoUpdate": {
    "xpub": "xpub6CUGRUonZSQ4...",
    "utxos": [
      {
        "txid": "abc123...",
        "vout": 0,
        "address": "TIME1...",
        "amount": 100000000,
        "block_height": 1234,
        "confirmations": 5
      }
    ]
  }
}
```

## Error Handling

- Connection timeout: 30 seconds
- Reconnect on disconnect with exponential backoff
- Max reconnect attempts: 5

## Implementation Notes

- Use non-blocking sockets
- Implement heartbeat/ping every 60 seconds
- Parse length prefix in big-endian format
- Validate JSON before processing

See main TIME Coin repo for full protocol specification.
