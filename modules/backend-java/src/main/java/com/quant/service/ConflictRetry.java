package com.quant.service;

import com.quant.exception.ConflictException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

import java.util.function.Supplier;

/**
 * 并发写入冲突重试（V2-MP-030）。
 *
 * <p>依赖 DB 唯一约束作为 CAS 门：高成本写操作（预测发布、快照创建等）在并发下
 * 读取 MAX+1 / count+1 会竞争同一候选键，唯一索引使后到者在胜者提交后收到
 * {@link DataIntegrityViolationException}——此时重试必然读到新值并成功。
 * InnoDB 唯一键并发 INSERT 会阻塞到胜者提交才报错，故紧接重试通常一次即中。
 *
 * <p>重试调用必须位于事务代理边界之外（controller 层），失败事务整体回滚后才可安全重放。
 * 不用 synchronized（多实例无效，见计划 MP-030「不要做」）；有界重试耗尽后抛 409。
 */
@Slf4j
@Component
public class ConflictRetry {

    private static final int MAX_ATTEMPTS = 4;

    /**
     * 有界重试：唯一键冲突时重新执行 {@code op}（每次是全新事务）。
     *
     * @param op      高成本写操作（须为事务代理方法，如 service 的 @Transactional 方法）
     * @param opName  操作名，用于日志与 409 提示
     */
    public <T> T retryOnUniqueConflict(Supplier<T> op, String opName) {
        for (int attempt = 1; ; attempt++) {
            try {
                return op.get();
            } catch (DataIntegrityViolationException e) {
                if (attempt >= MAX_ATTEMPTS) {
                    throw new ConflictException("并发写入冲突（" + opName + "），请稍后重试");
                }
                log.warn("并发写入冲突（{}）第 {} 次重试: {}", opName, attempt, e.getMessage());
                try {
                    Thread.sleep(50L * attempt);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new ConflictException("并发写入冲突（" + opName + "）", e);
                }
            }
        }
    }
}
