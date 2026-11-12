package com.wk.ai.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

/**
 * 双路汇总员：知识路与数据路的回答由一次 LLM 调用整合为连贯回答。
 *
 * <p>职责边界（写进 prompt 约束）：只整合、不重新推理、不补充新信息；
 * 知识路回答中的 [编号] 引用标注原样保留（citations 沿用知识路结果，引用与依据不脱节）；
 * 数据结果与知识描述冲突时以数据结果为准。
 */
@Service
public class DualSummarizer {

    private static final Logger log = LoggerFactory.getLogger(DualSummarizer.class);

    private static final String SUMMARIZE_PROMPT = """
            用户问题：%s

            以下是两个子系统对同一问题的处理结果：
            【知识库回答】：
            %s

            【业务数据查询结果】：
            %s

            请将两部分整合为一个连贯、自然的中文回答：
            1. 只整合以上内容，不要自行补充新信息
            2. 知识库回答中的 [编号] 引用标注原样保留
            3. 数据结果与知识描述冲突时，以数据结果为准
            """;

    private final ChatClient chatClient;

    public DualSummarizer(@Qualifier("chatClient") ChatClient chatClient) {
        this.chatClient = chatClient;
    }

    public String summarize(String question, String knowledgeAnswer, String dataAnswer) {
        String merged = chatClient.prompt()
                .user(SUMMARIZE_PROMPT.formatted(question, knowledgeAnswer, dataAnswer))
                .call()
                .content();
        log.debug("双路汇总完成");
        return merged;
    }
}
