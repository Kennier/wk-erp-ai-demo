package com.wk.ai.service;

import java.util.List;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;

/**
 * Prompt 注入防护（L1 输入拦截层）：中英正则名单拦截「覆盖行为规则」与「提示词泄露」
 * 两类注入，命中直接拒绝（不打 LLM）。
 *
 * <p>规则只是第一道网——变形/编码注入防不胜防，纵深防御的其余层次：
 * system prompt 的结构化声明（数据 vs 指令隔离）、输出泄露检测（PromptLeakGuard）、
 * DATA 链路的参数化薄壳工具（对注入天然免疫）。规则库持续维护，
 * 注入类回归用例进评测集（category=security）。
 */
@Service
public class InputGuardService {

    public record GuardResult(boolean blocked, String reason) {

        static GuardResult pass() {
            return new GuardResult(false, null);
        }

        static GuardResult block(String reason) {
            return new GuardResult(true, reason);
        }
    }

    private static final List<Pattern> INJECTION_PATTERNS = List.of(
            // 覆盖行为规则类（中文）：动词与宾语间允许少量修饰词（"之前所有的指令"等语序）
            Pattern.compile("忽略.{0,6}(指令|设定|规则|提示词|提示|要求|约束)"),
            Pattern.compile("(无视|不顾|跳过|绕过).{0,6}(指令|设定|规则|约束|限制)"),
            Pattern.compile("(不再|不要|停止)(遵守|遵循|按照).{0,4}(指令|设定|规则|要求)"),
            // 提示词泄露类（中文）：动词前后两种语序都覆盖
            Pattern.compile("(输出|打印|显示|泄露|告诉我|复述|repeat).{0,8}(系统提示|system prompt|初始提示|内部指令|提示词原文)",
                    Pattern.CASE_INSENSITIVE),
            Pattern.compile("(系统提示词?|系统指令|初始指令|提示词).{0,8}(原文|打印出来|输出|显示|复述|泄露|是什么|有哪些)",
                    Pattern.CASE_INSENSITIVE),
            Pattern.compile("你(的)?(系统提示词|系统指令|初始指令|人设|设定)(是什么|有哪些|是什么内容|原文)"),
            // 英文
            Pattern.compile("(ignore|disregard) (all |any |the )?(previous|prior|above|earlier) (instructions?|rules?|guidelines?|prompts?)",
                    Pattern.CASE_INSENSITIVE),
            Pattern.compile("(reveal|show|print|repeat|output) (your |the )?(system )?(prompt|instructions?)( verbatim)?",
                    Pattern.CASE_INSENSITIVE));

    public GuardResult check(String question) {
        if (question == null || question.isBlank()) {
            return GuardResult.pass();
        }
        for (Pattern pattern : INJECTION_PATTERNS) {
            var matcher = pattern.matcher(question);
            if (matcher.find()) {
                return GuardResult.block("问题包含不受支持的内容（疑似指令注入）");
            }
        }
        return GuardResult.pass();
    }
}
