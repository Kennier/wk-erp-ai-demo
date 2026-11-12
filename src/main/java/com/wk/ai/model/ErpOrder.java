package com.wk.ai.model;

import java.math.BigDecimal;
import java.time.LocalDate;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/**
 * 销售订单（示例表结构，按实际 ERP 调整）。
 */
@TableName("erp_sales_order")
public record ErpOrder(@TableId String orderNo, String customerName, String status,
                       BigDecimal amount, LocalDate deliveryDate) {
}
