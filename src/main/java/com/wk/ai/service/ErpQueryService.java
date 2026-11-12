package com.wk.ai.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wk.ai.config.ErpToolProperties;
import com.wk.ai.mapper.ErpCustomerMapper;
import com.wk.ai.mapper.ErpOrderMapper;
import com.wk.ai.mapper.ErpStockMapper;
import com.wk.ai.model.ErpCustomer;
import com.wk.ai.model.ErpOrder;
import com.wk.ai.model.ErpStock;

import tools.jackson.databind.ObjectMapper;

/**
 * ERP 查询业务逻辑：参数校验、行数限制、脱敏、超时控制、结果组装。
 *
 * <p>本类是工具层的核心逻辑所在（薄壳原则）：@Tool 方法只做转调，
 * 将来 MCP 化时本类原样复用。所有方法返回面向 LLM 的文本（查询结果 JSON
 * 或友好提示），异常统一抛 {@link ErpToolException}（消息可直接给用户）。
 */
@Service
public class ErpQueryService {

    private static final Logger log = LoggerFactory.getLogger(ErpQueryService.class);

    private static final Pattern ORDER_NO_PATTERN = Pattern.compile("^SO\\d{8}$");

    private static final Pattern PHONE_MASK_PATTERN = Pattern.compile("(\\d{3})\\d{4}(\\d{4})");

    private static final Map<String, String> ORDER_STATUS_ZH = Map.of(
            "CREATED", "已创建",
            "PAID", "已付款",
            "SHIPPED", "已发货",
            "COMPLETED", "已完成",
            "CANCELLED", "已取消");

    private final ErpOrderMapper orderMapper;
    private final ErpStockMapper stockMapper;
    private final ErpCustomerMapper customerMapper;
    private final ErpToolProperties properties;
    /** Spring 容器的 ObjectMapper（Boot 4 默认 Jackson 3，java.time 开箱即用） */
    private final ObjectMapper objectMapper;

    public ErpQueryService(ErpOrderMapper orderMapper, ErpStockMapper stockMapper,
                           ErpCustomerMapper customerMapper, ErpToolProperties properties,
                           ObjectMapper objectMapper) {
        this.orderMapper = orderMapper;
        this.stockMapper = stockMapper;
        this.customerMapper = customerMapper;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    /** 按订单号查询订单详情 */
    public String findOrder(String orderNo) {
        if (orderNo == null || !ORDER_NO_PATTERN.matcher(orderNo.trim()).matches()) {
            throw new ErpToolException("订单号格式不正确，应为 SO+8 位数字，如 SO20240001");
        }
        return withTimeout(() -> {
            ErpOrder order = orderMapper.selectById(orderNo.trim());
            if (order == null) {
                throw new ErpToolException("未找到订单 %s，请确认订单号是否正确".formatted(orderNo.trim()));
            }
            Map<String, Object> view = new HashMap<>();
            view.put("orderNo", order.orderNo());
            view.put("customerName", order.customerName());
            view.put("status", ORDER_STATUS_ZH.getOrDefault(order.status(), order.status()));
            view.put("amount", order.amount());
            view.put("deliveryDate", order.deliveryDate());
            return toJson(view);
        });
    }

    /** 按 SKU 查询各仓库可用库存 */
    public String findStock(String sku) {
        if (sku == null || sku.isBlank()) {
            throw new ErpToolException("SKU 不能为空");
        }
        return withTimeout(() -> {
            List<ErpStock> stocks = stockMapper.selectList(new LambdaQueryWrapper<ErpStock>()
                    .eq(ErpStock::sku, sku.trim()));
            if (stocks.isEmpty()) {
                throw new ErpToolException("未找到 SKU %s 的库存记录".formatted(sku.trim()));
            }
            int total = stocks.stream()
                    .mapToInt(s -> s.availableQty() == null ? 0 : s.availableQty())
                    .sum();
            List<Map<String, Object>> rows = stocks.stream()
                    .limit(properties.maxRows())
                    .map(s -> {
                        Map<String, Object> row = new HashMap<>();
                        row.put("warehouse", s.warehouse());
                        row.put("availableQty", s.availableQty());
                        return row;
                    })
                    .toList();
            Map<String, Object> view = new HashMap<>();
            view.put("sku", sku.trim());
            view.put("totalAvailableQty", total);
            view.put("warehouses", rows);
            if (stocks.size() > properties.maxRows()) {
                view.put("note", "仓库记录超过 %d 行已截断".formatted(properties.maxRows()));
            }
            return toJson(view);
        });
    }

    /** 按客户名称模糊查询客户信息（手机号脱敏） */
    public String findCustomers(String customerName) {
        if (customerName == null || customerName.isBlank()) {
            throw new ErpToolException("客户名称不能为空");
        }
        return withTimeout(() -> {
            List<ErpCustomer> customers = customerMapper.selectList(new LambdaQueryWrapper<ErpCustomer>()
                    .like(ErpCustomer::customerName, customerName.trim()));
            if (customers.isEmpty()) {
                throw new ErpToolException("未找到名称包含「%s」的客户".formatted(customerName.trim()));
            }
            List<Map<String, Object>> rows = customers.stream()
                    .limit(properties.maxRows())
                    .map(c -> {
                        Map<String, Object> row = new HashMap<>();
                        row.put("customerName", c.customerName());
                        row.put("creditLimit", c.creditLimit());
                        row.put("contactPhone", maskPhone(c.contactPhone()));
                        row.put("mainBusiness", c.mainBusiness());
                        return row;
                    })
                    .toList();
            Map<String, Object> view = new HashMap<>();
            view.put("matched", customers.size());
            view.put("customers", rows);
            if (customers.size() > properties.maxRows()) {
                view.put("note", "匹配客户超过 %d 个已截断，请提供更精确的名称".formatted(properties.maxRows()));
            }
            return toJson(view);
        });
    }

    /** 工具执行超时控制：超时/底层异常统一转译为 ErpToolException */
    private <T> T withTimeout(Supplier<T> query) {
        try {
            return CompletableFuture.supplyAsync(query)
                    .get(properties.timeoutSeconds(), TimeUnit.SECONDS);
        }
        catch (TimeoutException e) {
            throw new ErpToolException("订单系统响应超时，请稍后再试");
        }
        catch (ExecutionException e) {
            // 还原业务异常（参数/查无数据），系统异常统一提示
            if (e.getCause() instanceof ErpToolException toolException) {
                throw toolException;
            }
            log.error("ERP 查询失败", e.getCause());
            throw new ErpToolException("订单系统暂时不可用，请稍后再试");
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ErpToolException("查询被中断，请重试");
        }
    }

    /** 手机号脱敏：保留前 3 后 4 */
    private String maskPhone(String phone) {
        if (phone == null || phone.length() != 11) {
            return phone;
        }
        return PHONE_MASK_PATTERN.matcher(phone).replaceAll("$1****$2");
    }

    private String toJson(Object view) {
        try {
            return objectMapper.writeValueAsString(view);
        }
        catch (Exception e) {
            throw new ErpToolException("查询结果序列化失败");
        }
    }
}
