/**
 * Hạ tầng dùng chung: security, lỗi, logging, config. Module mở, mọi module được gọi.
 *
 * <p>{@code shared} không được import module nghiệp vụ.
 */
@ApplicationModule(displayName = "shared", type = ApplicationModule.Type.OPEN)
package vn.vingo.shared;

import org.springframework.modulith.ApplicationModule;
