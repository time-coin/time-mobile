package com.timecoin.wallet.di

// Database instances are managed directly by WalletService using two separate
// Room databases (wallet.db for mainnet, wallet-testnet.db for testnet) so that
// contacts, transactions, and payment requests never mix between networks.
