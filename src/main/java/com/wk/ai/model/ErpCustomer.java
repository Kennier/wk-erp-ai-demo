package com.wk.ai.model;

import java.math.BigDecimal;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/**
 * 客户（示例表结构，按实际 ERP 调整）。
 */
@TableName("erp_customer")
public record ErpCustomer(@TableId Long id, String customerName, BigDecimal creditLimit,
                          String contactPhone, String mainBusiness) {
}
