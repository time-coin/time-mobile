import Foundation
import UserNotifications

enum NotificationManager {

    static func requestPermission() {
        UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound, .badge]) { _, _ in }
    }

    static func scheduleTransactionReceived(amount: Int64, address: String) {
        let content = UNMutableNotificationContent()
        content.title = "TIME Received"
        content.body = "+\(amount.timeDisplay) TIME"
        content.sound = .default

        let request = UNNotificationRequest(
            identifier: "tx_received_\(UUID().uuidString)",
            content: content,
            trigger: nil
        )
        UNUserNotificationCenter.current().add(request)
    }

    static func schedulePaymentRequestReceived(from requesterName: String, amount: Int64) {
        let content = UNMutableNotificationContent()
        content.title = "Payment Request"
        let sender = requesterName.isEmpty ? "Someone" : requesterName
        content.body = "\(sender) requested \(amount.timeDisplay) TIME"
        content.sound = .default

        let request = UNNotificationRequest(
            identifier: "pr_received_\(UUID().uuidString)",
            content: content,
            trigger: nil
        )
        UNUserNotificationCenter.current().add(request)
    }

    static func clearBadge() {
        UNUserNotificationCenter.current().setBadgeCount(0) { _ in }
    }
}
