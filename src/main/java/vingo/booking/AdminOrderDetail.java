package vingo.booking;

import java.util.List;

import vingo.api.model.AdminGetOrder200ResponseAllOfHistoryInner;
import vingo.api.model.Order;

/** Dữ liệu booking cung cấp cho adminops, tách phần payments do adminops đọc. */
public record AdminOrderDetail(Order order, List<AdminGetOrder200ResponseAllOfHistoryInner> history) {}
