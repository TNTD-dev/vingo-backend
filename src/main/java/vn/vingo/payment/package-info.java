/**
 * Module payment: Tạo link VNPay, nhận IPN, đối soát querydr. Chủ: Luân. PR cần 2 người duyệt.
 *
 * <p>Module khác chỉ được gọi class ở gốc package này. {@code web} và {@code internal} là nội bộ.
 */
@ApplicationModule(displayName = "payment")
package vn.vingo.payment;

import org.springframework.modulith.ApplicationModule;
