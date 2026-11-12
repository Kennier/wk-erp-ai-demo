package com.wk.ai.model;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/**
 * 库存记录（示例表结构，按实际 ERP 调整）。同一 SKU 可在多个仓库各有记录。
 */
@TableName("erp_stock")
public record ErpStock(@TableId Long id, String sku, String warehouse, Integer availableQty) {
}
