package com.wk.ai.controller;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.wk.ai.dto.ChatTurn;
import com.wk.ai.service.ConversationMemoryService;
import com.wk.ai.service.ConversationMemoryService.SessionSummary;

/**
 * 会话管理接口（管理面）：列表、历史、删除。
 *
 * <p>当前 demo 无鉴权——任何人可查看/删除全部会话，生产环境必须加权限并收敛为
 * 「当前用户的会话」（身份接入后由 token 推导 userId 过滤）。
 */
@RestController
@RequestMapping("/admin/conversations")
public class SessionAdminController {

    private final ConversationMemoryService conversationMemory;

    public SessionAdminController(ConversationMemoryService conversationMemory) {
        this.conversationMemory = conversationMemory;
    }

    /** 会话列表：按最后访问时间倒序 */
    @GetMapping
    public List<SessionSummary> list() {
        return conversationMemory.listSessions();
    }

    /** 单会话完整历史（旧→新） */
    @GetMapping("/{conversationId}/history")
    public List<ChatTurn> history(@PathVariable String conversationId) {
        return conversationMemory.historyAsTurns(conversationId);
    }

    /** 删除单个会话 */
    @DeleteMapping("/{conversationId}")
    public ResponseEntity<Void> delete(@PathVariable String conversationId) {
        boolean existed = conversationMemory.delete(conversationId);
        return existed ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
    }
}
