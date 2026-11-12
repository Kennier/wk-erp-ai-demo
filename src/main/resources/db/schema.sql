-- wk-ai ERP 示例表（表结构为占位示例，按实际 ERP 调整）
-- 手动执行：mysql -u<user> -p <database> < src/main/resources/db/schema.sql
-- 注意：不启用 Spring Boot sql init 自动执行，避免本地无 MySQL 时阻断启动

CREATE TABLE IF NOT EXISTS erp_sales_order (
    order_no      VARCHAR(20)  NOT NULL COMMENT '订单号，SO+8位数字',
    customer_name VARCHAR(100) NOT NULL COMMENT '客户名称',
    status        VARCHAR(20)  NOT NULL COMMENT 'CREATED/PAID/SHIPPED/COMPLETED/CANCELLED',
    amount        DECIMAL(12, 2) NULL COMMENT '订单金额',
    delivery_date DATE NULL COMMENT '交期',
    PRIMARY KEY (order_no)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '销售订单';

CREATE TABLE IF NOT EXISTS erp_stock (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    sku           VARCHAR(50)  NOT NULL COMMENT '商品编码',
    warehouse     VARCHAR(50)  NOT NULL COMMENT '仓库',
    available_qty INT          NOT NULL DEFAULT 0 COMMENT '可用量',
    PRIMARY KEY (id),
    KEY idx_stock_sku (sku)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '库存';

CREATE TABLE IF NOT EXISTS erp_customer (
    id             BIGINT        NOT NULL AUTO_INCREMENT,
    customer_name  VARCHAR(100)  NOT NULL COMMENT '客户名称',
    credit_limit   DECIMAL(14, 2) NULL COMMENT '信用额度',
    contact_phone  VARCHAR(20)   NULL COMMENT '联系电话',
    main_business  VARCHAR(200)  NULL COMMENT '主营',
    PRIMARY KEY (id),
    KEY idx_customer_name (customer_name)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '客户';

-- 示例数据（幂等：主键/唯一键冲突时忽略）
INSERT IGNORE INTO erp_sales_order (order_no, customer_name, status, amount, delivery_date) VALUES
('SO20240001', '杭州星辰科技', 'SHIPPED', 128000.00, '2026-10-30'),
('SO20240002', '上海远大制造', 'PAID', 56000.50, '2026-11-05');

INSERT IGNORE INTO erp_stock (sku, warehouse, available_qty) VALUES
('A100', '华东仓', 320),
('A100', '华南仓', 85),
('B200', '华东仓', 0);

INSERT IGNORE INTO erp_customer (customer_name, credit_limit, contact_phone, main_business) VALUES
('杭州星辰科技', 500000.00, '13800138000', '智能硬件研发'),
('上海远大制造', 1200000.00, '13900139000', '精密机械制造');
