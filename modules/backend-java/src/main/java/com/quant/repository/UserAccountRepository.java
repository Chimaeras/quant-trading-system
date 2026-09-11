package com.quant.repository;

import com.quant.entity.UserAccount;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface UserAccountRepository extends JpaRepository<UserAccount, Long> {
    Optional<UserAccount> findFirstByUserIdAndStatus(Long userId, Integer status);

    /**
     * 下单路径专用：以悲观行锁读取账户（R31-P1-2）。
     *
     * <p>委托是「读 availableCash → 校验 → 扣减 → 写回」的读-改-写，无行锁时两个并发请求
     * 会读到同一余额、各自通过校验后分别扣减，最终现金超出实际（丢更新）。加锁后同一账户的
     * 委托串行化——历史演练会话的会话资金同属该账户，故一并被保护。
     *
     * <p>返回 List 而非 Optional：库内 uk_user_account 为 (user_id, account_name)，同用户
     * 理论上可有多行；按 id 取最早一行与 findFirst 语义一致且结果确定。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT a FROM UserAccount a WHERE a.userId = :userId AND a.status = :status ORDER BY a.id ASC")
    List<UserAccount> findAllForUpdateByUserIdAndStatus(
            @Param("userId") Long userId, @Param("status") Integer status);
}
