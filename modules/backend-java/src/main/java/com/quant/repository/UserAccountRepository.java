package com.quant.repository;

import com.quant.entity.UserAccount;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
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

    /**
     * 刷新账户展示口径（持仓市值/总资产/总盈亏）的定向更新，只覆盖这三列。
     *
     * <p>不能用 {@code save(entity)} 回写：概览接口读出的账户是游离态，Hibernate 默认
     * {@code dynamicUpdate=false}，merge 会 UPDATE 全部映射列——包括读取那一刻的
     * availableCash。R31 给下单路径加了行锁，但读路径的这次回写仍能把已提交的扣款
     * 覆盖回去（丢更新 → 可超支）。
     *
     * <p>总资产在 SQL 侧用库内当前 availableCash 计算，保证与最新现金一致。
     * 单列 update 查询必须有事务，故此处显式标注（调用方 getAccountInfo 无事务）。
     */
    @Transactional
    @Modifying
    @Query("UPDATE UserAccount a SET a.positionValue = :positionValue, "
            + "a.totalAsset = COALESCE(a.availableCash, 0) + :positionValue, "
            + "a.totalProfit = :totalProfit WHERE a.id = :id")
    int updateDisplayMetrics(@Param("id") Long id,
                             @Param("positionValue") BigDecimal positionValue,
                             @Param("totalProfit") BigDecimal totalProfit);
}
