#!/bin/bash
# Run this on your Mac to generate the Xcode project.
# Prerequisites: brew install xcodegen

set -e
cd "$(dirname "$0")"

echo "Checking for xcodegen..."
if ! command -v xcodegen &> /dev/null; then
    echo "Installing xcodegen via Homebrew..."
    brew install xcodegen
fi

echo "Generating Xcode project from project.yml..."
xcodegen generate

echo "Done! Open TimeCoinWallet.xcodeproj in Xcode."
echo "Then: File → Add Package Dependencies → resolve Argon2Swift, Alamofire, KeychainSwift"
echo "Or run: open TimeCoinWallet.xcodeproj"
