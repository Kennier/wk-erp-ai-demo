package com.wk.ai.tools;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.stereotype.Component;

import com.wk.ai.service.ErpQueryService;
import com.wk.ai.service.ErpToolException;

/**
 * ERP 工具薄壳：@Tool 方法只做「调用记录 + 异常转译 + 转调 ErpQueryService」，
 * 禁止出现查询逻辑（薄壳原则，保证将来 MCP 化零重写）。
 *
 * <p>异常语义：{@link ErpToolException} 的消息（参数错误/查无数据/系统不可用）
 * 直接返回给 LLM 组织回答；不向 LLM 暴露堆栈。
 */
@Component
public class ErpTools {

    private static final Logger log = LoggerFactory.getLogger(ErpTools.class);

    /** 请求级工具调用次数上限：Spring AI 2.0 的 tool-call 循环无内置迭代上限，
     *  LLM 固执重试时靠此硬熔断（熔断文本会引导 LLM 收手总结） */
    private static final int MAX_TOOL_CALLS_PER_REQUEST = 8;

    private final ErpQueryService erpQueryService;
    private final ErpToolRecorder recorder;

    public ErpTools(ErpQueryService erpQueryService, ErpToolRecorder recorder) {
        this.erpQueryService = erpQueryService;
        this.recorder = recorder;
    }

    @org.springframework.ai.tool.annotation.Tool(description = """
            按订单号查询销售订单详情（状态/金额/客户/交期）。
            当用户询问订单进度、订单状态、发货情况时使用。
            用户未提供订单号时不要调用，先向用户询问订单号。
            """)
    public String queryOrder(
            @org.springframework.ai.tool.annotation.ToolParam(description = "订单号，格式 SO+8位数字，如 SO20240001")
            String orderNo) {
        return execute("queryOrder", Map.of("orderNo", orderNo),
                () -> erpQueryService.findOrder(orderNo));
    }

    @org.springframework.ai.tool.annotation.Tool(description = """
            按 SKU 查询商品在各仓库的可用库存与总量。
            当用户询问某商品库存、是否有货时使用。
            """)
    public String queryStock(
            @org.springframework.ai.tool.annotation.ToolParam(description = "商品 SKU 编码")
            String sku) {
        return execute("queryStock", Map.of("sku", sku),
                () -> erpQueryService.findStock(sku));
    }

    @org.springframework.ai.tool.annotation.Tool(description = """
            按客户名称模糊查询客户信息（信用额度/联系方式/主营）。
            当用户询问客户信息、客户信用时使用；名称不完整也可调用。
            """)
    public String queryCustomer(
            @org.springframework.ai.tool.annotation.ToolParam(description = "客户名称（支持模糊匹配）")
            String customerName) {
        return execute("queryCustomer", Map.of("customerName", customerName),
                () -> erpQueryService.findCustomers(customerName));
    }

    /** 统一的薄壳执行模板：熔断检查 + 审计登记 + 异常转译 */
    private String execute(String toolName, Map<String, Object> args, java.util.function.Supplier<String> call) {
        // 熔断 1：请求级调用次数上限——掐断 LLM 的固执重试/工具间打转
        if (recorder.currentCount() >= MAX_TOOL_CALLS_PER_REQUEST) {
            recorder.record(toolName, args, "已达调用上限");
            log.warn("工具调用熔断: 已达请求级上限 {}, tool={}", MAX_TOOL_CALLS_PER_REQUEST, toolName);
            return "已达到本次最大工具调用次数，请基于已有信息直接回答用户";
        }
        // 熔断 2：同工具 + 同参数重复调用——结果不会变化，引导 LLM 换参数或询问用户
        if (recorder.alreadyCalled(toolName, args)) {
            recorder.record(toolName, args, "重复调用已熔断");
            log.warn("工具重复调用熔断: tool={}, args={}", toolName, args);
            return "该查询已尝试过且结果不会变化，请更换参数后重试，或直接向用户说明情况、询问补充信息";
        }

        long start = System.currentTimeMillis();
        try {
            String result = call.get();
            recorder.record(toolName, args, "成功");
            log.info("ERP 工具调用: tool={}, args={}, 耗时={}ms", toolName, args, System.currentTimeMillis() - start);
            return result;
        }
        catch (ErpToolException e) {
            recorder.record(toolName, args, e.getMessage());
            log.info("ERP 工具调用: tool={}, args={}, 业务失败: {}", toolName, args, e.getMessage());
            return e.getMessage();
        }
        catch (Exception e) {
            recorder.record(toolName, args, "系统异常");
            log.error("ERP 工具调用异常: tool={}, args={}", toolName, args, e);
            return "订单系统暂时不可用，请稍后再试";
        }
    }
}
