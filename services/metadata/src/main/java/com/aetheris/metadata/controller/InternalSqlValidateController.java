package com.aetheris.metadata.controller;

import com.aetheris.metadata.dto.SqlExecuteRequest;
import com.aetheris.metadata.service.SqlWriteGuard;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 内部 SQL 校验端点：只做白名单校验，不连接任何数据源、不执行语句。
 *
 * <p>存在的原因是用户体验：任务保存时就该告知「这条 SQL 不被允许」，
 * 而不是保存成功、点运行后才失败。实测中用户建的任务正是卡在这里。
 *
 * <p>刻意不把 {@link SqlWriteGuard} 复制到 workflow 服务：两份规则必然随时间漂移，
 * 届时「保存时通过、执行时拒绝」会更令人困惑。校验与执行共用同一份实现才能保证一致。
 */
@RestController
@RequestMapping("/internal/sql")
public class InternalSqlValidateController {

    /** 校验写入语句；合法返回 valid=true，否则返回 valid=false 与原因。 */
    @PostMapping("/validate-write")
    public ResponseEntity<Map<String, Object>> validateWrite(
            @Valid @RequestBody SqlExecuteRequest request) {
        try {
            SqlWriteGuard.validate(request.sql());
            return ResponseEntity.ok(Map.of("valid", true));
        } catch (IllegalArgumentException error) {
            // 200 而非 400：校验本身成功执行了，「不合法」是正常的业务结论。
            return ResponseEntity.ok(Map.of(
                    "valid", false,
                    "message", error.getMessage()));
        }
    }
}
