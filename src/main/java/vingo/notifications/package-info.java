/**
 * Module notifications: Gửi email OTP, email vé qua hàng đợi outbox. Chủ: Tiến.
 *
 * <p>Module khác chỉ được gọi class ở gốc package này. {@code web} và {@code internal} là nội bộ.
 */
@ApplicationModule(displayName = "notifications")
package vingo.notifications;

import org.springframework.modulith.ApplicationModule;
