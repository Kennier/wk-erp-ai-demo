package com.wk.ai.tools;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.springframework.stereotype.Component;

import com.wk.ai.dto.ToolCallInfo;

/**
 * 工具调用记录器（ThreadLocal）：薄壳层每次工具执行时登记，
 * ToolChatService 在本次问答结束时取出组装响应的 toolCalls 字段；
 * 同时为熔断提供依据——请求级调用次数上限 + 同工具同参数重复调用检测
 * （Spring AI 2.0 的 tool-call 循环无内置迭代上限，LLM 固执重试时靠这里硬熔断）。
 * Spring AI 的 tool-call 循环在调用线程同步执行，ThreadLocal 可靠。
 */
@Component
public class ErpToolRecorder {

    private final ThreadLocal<List<ToolCallInfo>> calls = ThreadLocal.withInitial(ArrayList::new);

    public void record(String name, Map<String, Object> args, String resultSummary) {
        calls.get().add(new ToolCallInfo(name, new LinkedHashMap<>(args), resultSummary));
    }

    /** 本次请求已发生的工具调用次数（含熔断触发的记录） */
    public int currentCount() {
        return calls.get().size();
    }

    /** 同工具 + 同参数是否已调用过（参数排序后比对，语序无关） */
    public boolean alreadyCalled(String name, Map<String, Object> args) {
        String fingerprint = fingerprint(name, args);
        return calls.get().stream()
                .anyMatch(call -> fingerprint(call.name(), call.args()).equals(fingerprint));
    }

    /** 取出并清空本次问答的全部工具调用记录 */
    public List<ToolCallInfo> drain() {
        List<ToolCallInfo> drained = new ArrayList<>(calls.get());
        calls.get().clear();
        return drained;
    }

    /** 调用指纹：工具名 + 参数按 key 排序拼接 */
    private String fingerprint(String name, Map<String, Object> args) {
        return name + ":" + new TreeMap<>(args);
    }
}
